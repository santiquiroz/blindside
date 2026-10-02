#include "diagnostics.h"

#include <Arduino.h>

#include "ble_link.h"
#include "diag_format.h"
#include "nimble_internals.h"
#include "pairing.h"
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

LinkDiag link_diag(const LinkSnapshot& link, const LinkDelivery& delivery) {
    LinkTally tally = tally_for_link(delivery, link.link_id);
    return LinkDiag{link.connected, link.role, link.trusted, link.subscribed,
                    link.mtu,       link.params, tally.sent, tally.dropped};
}

DiagTail diag_tail(const PairingWindow& window, uint32_t now_ms) {
    return DiagTail{pairing_bond_count(), window, now_ms, ble_link_last_disconnect_reason(), nimble_free_acl_buffers(),
                    nimble_host_stack_free()};
}

void print_links(const PairingWindow& window, uint32_t now_ms) {
    SenderStats stats = stream_sender_stats();
    for (uint8_t slot = 0; slot < kMaxLinks; ++slot) {
        Serial.print(format_link_diag(slot, link_diag(ble_link_snapshot(slot), stats.links[slot])).text);
    }
    Serial.print(format_diag_tail(diag_tail(window, now_ms)).text);
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

DiagnosticsMemory diagnostics_print(const DiagnosticsMemory& memory, uint32_t now_ms, const PairingWindow& window) {
    DiagnosticsMemory next = memory;
    Serial.printf("diag up=%lus", static_cast<unsigned long>(now_ms / 1000));
    for (uint8_t i = 0; i < kRadarCount; ++i) {
        print_radar(i, now_ms);
    }
    for (uint8_t i = 0; i < kImuCount; ++i) {
        next.window_start[i] = imu_task_snapshot(i);
        print_imu(i, next.window_start[i], memory.window_start[i]);
    }
    print_links(window, now_ms);
    print_sender();
    return next;
}
