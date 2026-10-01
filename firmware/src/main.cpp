#include <Arduino.h>
#include <Wire.h>
#include <esp_mac.h>
#include <esp_random.h>
#include <esp_system.h>

#include <atomic>

#include "ble_advertising.h"
#include "ble_link.h"
#include "ble_rules.h"
#include "blindside_config.h"
#include "control_command.h"
#include "cut_schedule.h"
#include "diagnostics.h"
#include "imu_task.h"
#include "info_json.h"
#include "led_pattern.h"
#include "pairing.h"
#include "radar_port.h"
#include "status_led.h"
#include "stream_sender.h"

namespace {

PairingState g_pairing;
DiagnosticsMemory g_diagnostics;
std::atomic<uint32_t> g_boot_id{0};
uint32_t g_next_diagnostics_ms = 0;
bool g_identify_active = false;
uint32_t g_identify_started_ms = 0;

void print_banner() {
    Serial.printf("blindside fw %s boot_id=%08lx reset=%s\n", config::kFirmwareVersion,
                  static_cast<unsigned long>(g_boot_id.load()), reset_reason_name(esp_reset_reason()));
}

RadarInfo radar_info(uint8_t radar_id) {
    RadarSnapshot radar = radar_port_snapshot(radar_id);
    return RadarInfo{radar_id, radar.firmware, radar.baud};
}

ImuInfo imu_info(uint8_t imu_id) {
    ImuSnapshot imu = imu_task_snapshot(imu_id);
    return ImuInfo{imu_id, imu.who_am_i, imu.repeats};
}

ConnInfo conn_info(const LinkSnapshot& link, const LinkDelivery& delivery) {
    LinkTally tally = tally_for_link(delivery, link.link_id);
    return ConnInfo{link.role, link.params, tally.sent, tally.dropped};
}

BeltInfo with_links(const BeltInfo& info) {
    BeltInfo next = info;
    SenderStats stats = stream_sender_stats();
    for (uint8_t slot = 0; slot < kMaxLinks; ++slot) {
        LinkSnapshot link = ble_link_snapshot(slot);
        if (link.connected) {
            next.conns[next.conn_count++] = conn_info(link, stats.links[slot]);
        }
    }
    next.bonds = pairing_bond_count();
    return next;
}

BeltInfo current_info(uint32_t now_ms, uint8_t reader_slot) {
    BeltInfo info{};
    info.firmware_version = config::kFirmwareVersion;
    info.boot_id = g_boot_id.load();
    info.reset_reason = reset_reason_name(esp_reset_reason());
    info.mtu = ble_link_snapshot(reader_slot).mtu;
    for (uint8_t i = 0; i < kRadarCount; ++i) {
        info.radars[i] = radar_info(i);
    }
    for (uint8_t i = 0; i < kImuCount; ++i) {
        info.imus[i] = imu_info(i);
    }
    info.tx_power_dbm = ble_link_tx_power();
    info.uptime_s = now_ms / 1000;
    return with_links(info);
}

// Runs on the NimBLE host task at each read of `info`; current_info only reads atomics and locked snapshots.
size_t write_info_json(uint8_t reader_slot, char* out, size_t out_size) {
    return format_info_json(current_info(millis(), reader_slot), out, out_size);
}

void start_ble() {
    uint8_t mac[6] = {0};
    esp_read_mac(mac, ESP_MAC_BT);
    ble_link_begin(device_name_from_mac(mac).text, write_info_json);
}

void start_radar_tasks(QueueHandle_t frames) {
    radar_port_start_task(radar_port_create(0, &Serial2, UART_NUM_2, config::kRadarARxPin, config::kRadarATxPin),
                          frames);
    radar_port_start_task(radar_port_create(1, &Serial1, UART_NUM_1, config::kRadarBRxPin, config::kRadarBTxPin),
                          frames);
}

void start_imu_tasks(const SenderSources& sources) {
    imu_task_start(imu_create(0, &Wire1, config::kImuASdaPin, config::kImuASclPin), sources.imu_samples[0]);
    imu_task_start(imu_create(1, &Wire, config::kImuBSdaPin, config::kImuBSclPin), sources.imu_samples[1]);
}

SenderSources create_queues() {
    SenderSources sources{};
    sources.radar_frames = xQueueCreate(config::kRadarQueueDepth, sizeof(RadarFrame));
    for (uint8_t imu = 0; imu < kImuCount; ++imu) {
        sources.imu_samples[imu] = xQueueCreate(config::kImuQueueDepth, sizeof(ImuSample));
    }
    return sources;
}

void start_tasks() {
    SenderSources sources = create_queues();
    start_radar_tasks(sources.radar_frames);
    start_imu_tasks(sources);
    stream_sender_start(sources);
}

void start_identify(uint32_t now_ms) {
    g_identify_active = true;
    g_identify_started_ms = now_ms;
}

void set_role(uint8_t slot, uint8_t argument) {
    LinkRole role = role_from_argument(argument);
    ble_link_set_role(slot, role);
    pairing_note_role(g_pairing, slot, role);
}

// pairing_poll runs first in loop(), so a link that just authenticated is already trusted here.
ControlAction action_for(uint8_t slot, const ControlCommand& command) {
    return control_action(command.kind, ble_link_snapshot(slot).trusted, pairing_session_running(g_pairing));
}

void apply_control(uint8_t slot, const ControlCommand& command, uint32_t now_ms) {
    switch (action_for(slot, command)) {
        case ControlAction::RestartRadar:
            radar_port_request_restart(command.argument);
            break;
        case ControlAction::Identify:
            start_identify(now_ms);
            break;
        case ControlAction::SetSession:
            pairing_note_session(g_pairing, slot, command.argument == 1);
            break;
        case ControlAction::OpenPairingWindow:
            pairing_open_window(g_pairing, now_ms);
            break;
        case ControlAction::SetRole:
            set_role(slot, command.argument);
            break;
        case ControlAction::RejectInvalid:
            Serial.println("control: ignored invalid write");
            break;
        case ControlAction::RejectUntrusted:
            Serial.println("control: ignored a write from an untrusted link");
            break;
        case ControlAction::Ignore:
            break;
    }
}

void handle_slot_control(uint8_t slot, uint32_t now_ms) {
    for (ControlCommand command = ble_link_take_control(slot); command.kind != ControlKind::None;
         command = ble_link_take_control(slot)) {
        apply_control(slot, command, now_ms);
    }
}

void handle_control(uint32_t now_ms) {
    for (uint8_t slot = 0; slot < kMaxLinks; ++slot) {
        handle_slot_control(slot, now_ms);
    }
}

void show_led(uint32_t now_ms) {
    if (g_identify_active && identify_finished(g_identify_started_ms, now_ms)) {
        g_identify_active = false;
    }
    status_led_show(LedInputs{now_ms, g_pairing.window.open, g_identify_active, g_identify_started_ms,
                              pairing_session_running(g_pairing)});
}

AdvertisingPlan advertising_plan() {
    return AdvertisingPlan{pairing_whitelist_only(g_pairing), g_pairing.window.open, g_pairing.trusted.count};
}

void print_diagnostics_if_due(uint32_t now_ms) {
    g_diagnostics = diagnostics_report_imu_changes(g_diagnostics, now_ms);
    if (!deadline_reached(now_ms, g_next_diagnostics_ms)) {
        return;
    }
    g_diagnostics = diagnostics_print(g_diagnostics, now_ms);
    g_next_diagnostics_ms = now_ms + config::kDiagnosticsPeriodMs;
}

}  // namespace

void setup() {
    Serial.setTxBufferSize(config::kSerialTxBufferBytes);
    Serial.begin(config::kSerialBaud);
    status_led_begin();
    status_led_show(LedInputs{millis(), false, false, 0, false});
    pinMode(config::kBootButtonPin, INPUT_PULLUP);
    start_ble();
    // esp_random() is only truly random once the radio is on, which start_ble() just did.
    g_boot_id = esp_random();
    print_banner();
    g_pairing = pairing_begin(millis());
    ble_advertising_start(pairing_whitelist_only(g_pairing), millis());
    start_tasks();
    g_next_diagnostics_ms = millis() + config::kDiagnosticsPeriodMs;
}

void loop() {
    uint32_t now_ms = millis();
    pairing_poll(g_pairing, now_ms);
    ble_advertising_poll(advertising_plan(), now_ms);
    handle_control(now_ms);
    show_led(now_ms);
    print_diagnostics_if_due(now_ms);
    delay(config::kLoopPeriodMs);
}
