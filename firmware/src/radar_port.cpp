#include "radar_port.h"

#include <atomic>
#include <string.h>

#include "blindside_config.h"
#include "radar_setup.h"

namespace {

constexpr size_t kAckWindowSize = 96;
constexpr size_t kAckWindowKeep = 32;
constexpr size_t kReadChunkSize = 64;
constexpr uint32_t kIdleBaud = kBaudProbeOrder[0];
const char* const kTaskNames[kRadarCount] = {"radar_rx_a", "radar_rx_b"};

struct RadarTask {
    RadarPort port;
    QueueHandle_t frames;
};

RadarTask g_tasks[kRadarCount];
portMUX_TYPE g_snapshot_lock = portMUX_INITIALIZER_UNLOCKED;
RadarSnapshot g_snapshots[kRadarCount];
std::atomic<bool> g_restart_requested[kRadarCount];
std::atomic<uint32_t> g_queue_overflows{0};

void send_command(HardwareSerial& serial, const CommandBytes& command) {
    serial.write(command.bytes, command.length);
}

void discard_input(HardwareSerial& serial) {
    while (serial.available() > 0) {
        serial.read();
    }
}

size_t keep_window_tail(uint8_t* window, size_t length) {
    memmove(window, window + length - kAckWindowKeep, kAckWindowKeep);
    return kAckWindowKeep;
}

size_t append_available(HardwareSerial& serial, uint8_t* window, size_t length) {
    while (serial.available() > 0) {
        if (length == kAckWindowSize) {
            length = keep_window_tail(window, length);
        }
        window[length++] = static_cast<uint8_t>(serial.read());
    }
    return length;
}

Ack await_ack(HardwareSerial& serial, uint16_t command_word) {
    uint8_t window[kAckWindowSize];
    size_t length = 0;
    uint32_t started_ms = millis();
    while (millis() - started_ms < config::kRadarAckTimeoutMs) {
        length = append_available(serial, window, length);
        Ack ack = find_ack(window, length, command_word);
        if (ack.found) {
            return ack;
        }
        delay(1);
    }
    return Ack{};
}

Ack exchange(RadarPort& port, const CommandBytes& command, uint16_t command_word) {
    send_command(*port.serial, command);
    return await_ack(*port.serial, command_word);
}

bool answers_at(RadarPort& port, uint32_t baud) {
    port.serial->updateBaudRate(baud);
    discard_input(*port.serial);
    return exchange(port, enable_config_command(), kCmdEnableConfig).found;
}

uint32_t detect_baud(RadarPort& port) {
    for (size_t i = 0; i < kBaudProbeCount; ++i) {
        if (answers_at(port, kBaudProbeOrder[i])) {
            return kBaudProbeOrder[i];
        }
    }
    port.serial->updateBaudRate(kIdleBaud);
    return 0;
}

void log_step(const RadarPort& port, const char* step, const Ack& ack) {
    Serial.printf("radar %u: %s %s\n", static_cast<unsigned>(port.radar_id), step, ack.success ? "ok" : "FAILED");
}

void run_boot_sequence(RadarPort& port) {
    Ack version = exchange(port, read_firmware_command(), kCmdReadFirmware);
    port.firmware = firmware_text_from_ack(version);
    log_step(port, "read-firmware", version);
    log_step(port, "multi-target", exchange(port, multi_target_command(), kCmdMultiTarget));
    log_step(port, "bluetooth-off", exchange(port, bluetooth_off_command(), kCmdBluetooth));
    log_step(port, "restart", exchange(port, restart_command(), kCmdRestart));
}

void open_uart(RadarPort& port) {
    port.serial->setRxBufferSize(config::kRadarRxBufferBytes);
    port.serial->begin(kIdleBaud, SERIAL_8N1, port.rx_pin, port.tx_pin);
}

void log_configuration(const RadarPort& port) {
    Serial.printf("radar %u: baud=%lu fw=%s\n", static_cast<unsigned>(port.radar_id),
                  static_cast<unsigned long>(port.baud), port.firmware.text);
}

void finish_configuration(RadarPort& port) {
    log_configuration(port);
    port.parser = frame_parser_start();
    port.restart_pending = false;
    port.watchdog = watchdog_after_config(port.watchdog, millis());
}

void probe_and_configure(RadarPort& port) {
    port.baud = detect_baud(port);
    if (port.baud != 0) {
        run_boot_sequence(port);
    }
    finish_configuration(port);
}

// Frames already parse at the idle UART rate, so that is the radar's baud even though it missed the boot probes.
void configure_at_current_baud(RadarPort& port) {
    port.baud = kIdleBaud;
    discard_input(*port.serial);
    log_step(port, "enable-config", exchange(port, enable_config_command(), kCmdEnableConfig));
    run_boot_sequence(port);
    finish_configuration(port);
}

void configure_radar(RadarPort& port) {
    open_uart(port);
    probe_and_configure(port);
    port.gaps = frame_gaps_start();
    port.gaps_started_ms = millis();
}

RadarSnapshot snapshot_of(const RadarPort& port) {
    RadarSnapshot snapshot{};
    snapshot.configured = true;
    snapshot.firmware = port.firmware;
    snapshot.baud = port.baud;
    snapshot.watchdog = port.watchdog;
    snapshot.status = StatusEntry{port.radar_id, port.bad_frames, port.watchdog.restarts, baud_index_for(port.baud)};
    snapshot.frames_ok = port.frames_ok;
    snapshot.last_window = port.finished_gaps;
    return snapshot;
}

void publish_snapshot(const RadarPort& port) {
    RadarSnapshot snapshot = snapshot_of(port);
    taskENTER_CRITICAL(&g_snapshot_lock);
    g_snapshots[port.radar_id] = snapshot;
    taskEXIT_CRITICAL(&g_snapshot_lock);
}

RadarFrame radar_frame_from(uint8_t radar_id, const ParsedFrame& parsed) {
    RadarFrame frame{};
    frame.radar_id = radar_id;
    frame.t_ms = parsed.t_ms;
    memcpy(frame.targets, parsed.targets, kRadarTargetsSize);
    return frame;
}

void push_frame(QueueHandle_t frames, const RadarFrame& frame) {
    if (xQueueSend(frames, &frame, 0) != pdTRUE) {
        g_queue_overflows++;
    }
}

void record_step(RadarPort& port, const ParserStep& step, QueueHandle_t frames, uint32_t now_ms) {
    if (step.event == FrameEvent::FrameBad) {
        port.bad_frames = bad_frames_after_one_more(port.bad_frames);
        return;
    }
    if (step.event != FrameEvent::FrameOk) {
        return;
    }
    port.frames_ok++;
    port.watchdog = watchdog_saw_frame(port.watchdog, now_ms);
    port.gaps = frame_gaps_with(port.gaps, step.frame.t_ms);
    push_frame(frames, radar_frame_from(port.radar_id, step.frame));
}

void feed_chunk(RadarPort& port, const uint8_t* chunk, size_t length, QueueHandle_t frames, uint32_t now_ms) {
    for (size_t i = 0; i < length; ++i) {
        ParserStep step = frame_parser_feed(port.parser, chunk[i], now_ms);
        port.parser = step.parser;
        record_step(port, step, frames, now_ms);
    }
}

size_t read_chunk(const RadarPort& port, uint8_t* chunk) {
    int first = uart_read_bytes(port.uart, chunk, 1, pdMS_TO_TICKS(config::kRadarReadTimeoutMs));
    if (first <= 0) {
        return 0;
    }
    int rest = uart_read_bytes(port.uart, chunk + 1, kReadChunkSize - 1, 0);
    return 1 + (rest > 0 ? static_cast<size_t>(rest) : 0);
}

void begin_restart(RadarPort& port, uint32_t now_ms) {
    send_command(*port.serial, enable_config_command());
    port.restart_pending = true;
    port.restart_due_ms = now_ms + config::kRadarRestartGapMs;
}

bool restart_due(const RadarPort& port, uint32_t now_ms) {
    return port.restart_pending && static_cast<int32_t>(now_ms - port.restart_due_ms) >= 0;
}

void finish_restart_if_due(RadarPort& port, uint32_t now_ms) {
    if (!restart_due(port, now_ms)) {
        return;
    }
    send_command(*port.serial, restart_command());
    port.restart_pending = false;
    port.parser = frame_parser_start();
    Serial.printf("radar %u: restart sent (total %u)\n", static_cast<unsigned>(port.radar_id),
                  static_cast<unsigned>(port.watchdog.restarts));
}

bool take_restart_request(RadarPort& port, uint32_t now_ms) {
    if (!g_restart_requested[port.radar_id].exchange(false)) {
        return false;
    }
    port.watchdog = watchdog_restarted(port.watchdog, now_ms);
    return true;
}

RadarSetupInput setup_input(const RadarPort& port, bool restart_wanted) {
    return RadarSetupInput{port.baud != 0, port.watchdog.has_frame, restart_wanted};
}

void apply_setup_action(RadarPort& port, RadarSetupAction action, uint32_t now_ms) {
    switch (action) {
        case RadarSetupAction::Restart:
            begin_restart(port, now_ms);
            break;
        case RadarSetupAction::ConfigureAtCurrentBaud:
            configure_at_current_baud(port);
            break;
        case RadarSetupAction::ProbeBaud:
            probe_and_configure(port);
            break;
        case RadarSetupAction::None:
            break;
    }
}

void supervise(RadarPort& port, uint32_t now_ms) {
    bool requested = take_restart_request(port, now_ms);
    finish_restart_if_due(port, now_ms);
    WatchdogStep step = watchdog_check(port.watchdog, now_ms);
    port.watchdog = step.watchdog;
    bool restart_wanted = requested || step.action == WatchdogAction::Restart;
    apply_setup_action(port, radar_setup_action(setup_input(port, restart_wanted)), now_ms);
}

void roll_gap_window(RadarPort& port, uint32_t now_ms) {
    if (now_ms - port.gaps_started_ms < config::kDiagnosticsPeriodMs) {
        return;
    }
    port.finished_gaps = port.gaps;
    port.gaps = frame_gaps_new_window(port.gaps);
    port.gaps_started_ms = now_ms;
}

void radar_rx_task(void* argument) {
    RadarTask& task = *static_cast<RadarTask*>(argument);
    RadarPort& port = task.port;
    configure_radar(port);
    uint8_t chunk[kReadChunkSize];
    for (;;) {
        size_t length = read_chunk(port, chunk);
        // The stamp is taken as the bytes arrive, never in a loop that also touches I2C (spec §4.4).
        uint32_t now_ms = millis();
        feed_chunk(port, chunk, length, task.frames, now_ms);
        supervise(port, now_ms);
        roll_gap_window(port, now_ms);
        publish_snapshot(port);
    }
}

}  // namespace

RadarPort radar_port_create(uint8_t radar_id, HardwareSerial* serial, uart_port_t uart, int8_t rx_pin, int8_t tx_pin) {
    RadarPort port{};
    port.radar_id = radar_id;
    port.serial = serial;
    port.uart = uart;
    port.rx_pin = rx_pin;
    port.tx_pin = tx_pin;
    port.parser = frame_parser_start();
    return port;
}

void radar_port_start_task(const RadarPort& port, QueueHandle_t frames) {
    RadarTask& task = g_tasks[port.radar_id];
    task.port = port;
    task.frames = frames;
    xTaskCreatePinnedToCore(radar_rx_task, kTaskNames[port.radar_id], config::kTaskStackBytes, &task,
                            config::kRadarTaskPriority, nullptr, config::kSensorCore);
}

void radar_port_request_restart(uint8_t radar_id) {
    g_restart_requested[radar_id] = true;
}

RadarSnapshot radar_port_snapshot(uint8_t radar_id) {
    taskENTER_CRITICAL(&g_snapshot_lock);
    RadarSnapshot snapshot = g_snapshots[radar_id];
    taskEXIT_CRITICAL(&g_snapshot_lock);
    return snapshot;
}

bool radar_snapshot_alive(const RadarSnapshot& snapshot, uint32_t now_ms) {
    return snapshot.configured && watchdog_radar_alive(snapshot.watchdog, now_ms);
}

uint32_t radar_port_queue_overflows() {
    return g_queue_overflows.load();
}
