#include <Arduino.h>
#include <Wire.h>
#include <esp_mac.h>
#include <esp_random.h>
#include <esp_system.h>

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
uint32_t g_boot_id = 0;
uint32_t g_next_info_ms = 0;
uint32_t g_next_diagnostics_ms = 0;
bool g_session_active = false;
bool g_identify_active = false;
uint32_t g_identify_started_ms = 0;

void print_banner() {
    Serial.printf("blindside fw %s boot_id=%08lx reset=%s\n", config::kFirmwareVersion,
                  static_cast<unsigned long>(g_boot_id), reset_reason_name(esp_reset_reason()));
}

void start_ble() {
    uint8_t mac[6] = {0};
    esp_read_mac(mac, ESP_MAC_BT);
    ble_link_begin(device_name_from_mac(mac).text);
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
    if (!identify_allowed(g_session_active)) {
        return;
    }
    g_identify_active = true;
    g_identify_started_ms = now_ms;
}

void handle_control(uint32_t now_ms) {
    ControlCommand command = ble_link_take_control();
    switch (command.kind) {
        case ControlKind::RestartRadar:
            radar_port_request_restart(command.argument);
            break;
        case ControlKind::Identify:
            start_identify(now_ms);
            break;
        case ControlKind::SessionActive:
            g_session_active = command.argument == 1;
            break;
        case ControlKind::Invalid:
            Serial.println("control: ignored invalid write");
            break;
        case ControlKind::None:
            break;
    }
}

RadarInfo radar_info(uint8_t radar_id) {
    RadarSnapshot radar = radar_port_snapshot(radar_id);
    return RadarInfo{radar_id, radar.firmware, radar.baud};
}

ImuInfo imu_info(uint8_t imu_id) {
    ImuSnapshot imu = imu_task_snapshot(imu_id);
    return ImuInfo{imu_id, imu.who_am_i, imu.repeats};
}

BeltInfo current_info(uint32_t now_ms) {
    LinkSnapshot link = ble_link_snapshot();
    BeltInfo info{};
    info.firmware_version = config::kFirmwareVersion;
    info.boot_id = g_boot_id;
    info.reset_reason = reset_reason_name(esp_reset_reason());
    info.mtu = link.mtu;
    for (uint8_t i = 0; i < kRadarCount; ++i) {
        info.radars[i] = radar_info(i);
    }
    for (uint8_t i = 0; i < kImuCount; ++i) {
        info.imus[i] = imu_info(i);
    }
    info.tx_power_dbm = ble_link_tx_power();
    info.conn = link.params;
    info.uptime_s = now_ms / 1000;
    return info;
}

void refresh_info_if_due(uint32_t now_ms) {
    if (!deadline_reached(now_ms, g_next_info_ms)) {
        return;
    }
    char json[kInfoJsonBufferSize];
    size_t length = format_info_json(current_info(now_ms), json, sizeof(json));
    if (length > 0) {
        ble_link_update_info(json, length);
    }
    g_next_info_ms = now_ms + config::kInfoRefreshMs;
}

void show_led(uint32_t now_ms) {
    if (g_identify_active && identify_finished(g_identify_started_ms, now_ms)) {
        g_identify_active = false;
    }
    status_led_show(LedInputs{now_ms, g_pairing.window.open, g_identify_active, g_identify_started_ms});
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
    status_led_show(LedInputs{millis(), false, false, 0});
    pinMode(config::kBootButtonPin, INPUT_PULLUP);
    start_ble();
    // esp_random() is only truly random once the radio is on, which start_ble() just did.
    g_boot_id = esp_random();
    print_banner();
    g_pairing = pairing_begin(millis());
    ble_link_start_advertising(pairing_whitelist_only(g_pairing), millis());
    start_tasks();
    g_next_info_ms = millis();
    g_next_diagnostics_ms = millis() + config::kDiagnosticsPeriodMs;
}

void loop() {
    uint32_t now_ms = millis();
    pairing_poll(g_pairing, now_ms);
    ble_link_poll_advertising(pairing_whitelist_only(g_pairing), now_ms);
    handle_control(now_ms);
    refresh_info_if_due(now_ms);
    show_led(now_ms);
    print_diagnostics_if_due(now_ms);
    delay(config::kLoopPeriodMs);
}
