#include "diagnostics.h"

#include <Arduino.h>

#include "ble_link.h"
#include "radar_port.h"
#include "stream_sender.h"

namespace {

// Indexed by esp_reset_reason_t (ESP-IDF 4.4 values 0-10); anything newer reports UNKNOWN.
const char* const kResetReasonNames[] = {"UNKNOWN",  "POWERON", "EXT",       "SW",       "PANIC", "INT_WDT",
                                         "TASK_WDT", "WDT",     "DEEPSLEEP", "BROWNOUT", "SDIO"};
constexpr size_t kResetReasonCount = sizeof(kResetReasonNames) / sizeof(kResetReasonNames[0]);

void print_radar(uint8_t radar_id, uint32_t now_ms) {
    RadarSnapshot radar = radar_port_snapshot(radar_id);
    Serial.printf(" radar%u[alive=%d ok=%lu bad=%u rst=%u baud=%lu gap=%lu..%lu]", static_cast<unsigned>(radar_id),
                  radar_snapshot_alive(radar, now_ms) ? 1 : 0, static_cast<unsigned long>(radar.frames_ok),
                  static_cast<unsigned>(radar.status.bad_frames), static_cast<unsigned>(radar.status.restarts),
                  static_cast<unsigned long>(radar.baud), static_cast<unsigned long>(radar.last_window.min_gap_ms),
                  static_cast<unsigned long>(radar.last_window.max_gap_ms));
}

void print_imu(uint8_t imu_id, const ImuSnapshot& now, const ImuSnapshot& before) {
    Serial.printf(" imu%u[ok=%d who=0x%02X reads=%lu fail=%lu rep=%lu smp=%lu]", static_cast<unsigned>(imu_id),
                  now.ok ? 1 : 0, static_cast<unsigned>(now.who_am_i),
                  static_cast<unsigned long>(now.reads_ok - before.reads_ok),
                  static_cast<unsigned long>(now.read_failures), static_cast<unsigned long>(now.repeats),
                  static_cast<unsigned long>(now.samples - before.samples));
}

void print_link() {
    LinkSnapshot link = ble_link_snapshot();
    Serial.printf(" link[conn=%d sub=%d trusted=%d mtu=%u itvl=%u lat=%u sup=%u disc=%d]", link.connected ? 1 : 0,
                  link.subscribed ? 1 : 0, link.trusted ? 1 : 0, static_cast<unsigned>(link.mtu),
                  static_cast<unsigned>(link.params.interval_units), static_cast<unsigned>(link.params.latency),
                  static_cast<unsigned>(link.params.supervision_units), ble_link_last_disconnect_reason());
}

void print_sender() {
    SenderStats stats = stream_sender_stats();
    Serial.printf(" tx[fail=%lu dropped=%lu skipped=%lu]\n", static_cast<unsigned long>(stats.notify_failures),
                  static_cast<unsigned long>(stats.dropped_total), static_cast<unsigned long>(stats.skipped_cuts));
}

void report_imu_change(uint8_t imu_id, const ImuSnapshot& imu, uint32_t now_ms) {
    Serial.printf("imu %u: %s at %lu ms (who=0x%02X)\n", static_cast<unsigned>(imu_id), imu.ok ? "ok" : "DOWN",
                  static_cast<unsigned long>(now_ms), static_cast<unsigned>(imu.who_am_i));
}

}  // namespace

const char* reset_reason_name(esp_reset_reason_t reason) {
    size_t index = static_cast<size_t>(reason);
    return index < kResetReasonCount ? kResetReasonNames[index] : kResetReasonNames[0];
}

DiagnosticsMemory diagnostics_report_imu_changes(const DiagnosticsMemory& memory, uint32_t now_ms) {
    DiagnosticsMemory next = memory;
    for (uint8_t i = 0; i < kImuCount; ++i) {
        ImuSnapshot imu = imu_task_snapshot(i);
        if (imu.ok != memory.imu_ok_seen[i]) {
            report_imu_change(i, imu, now_ms);
        }
        next.imu_ok_seen[i] = imu.ok;
    }
    return next;
}

DiagnosticsMemory diagnostics_print(const DiagnosticsMemory& memory, uint32_t now_ms) {
    DiagnosticsMemory next = memory;
    Serial.printf("diag up=%lus", static_cast<unsigned long>(now_ms / 1000));
    for (uint8_t i = 0; i < kRadarCount; ++i) {
        print_radar(i, now_ms);
    }
    for (uint8_t i = 0; i < kImuCount; ++i) {
        next.window_start[i] = imu_task_snapshot(i);
        print_imu(i, next.window_start[i], memory.window_start[i]);
    }
    print_link();
    print_sender();
    return next;
}
