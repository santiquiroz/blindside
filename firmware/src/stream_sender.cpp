#include "stream_sender.h"

#include <Arduino.h>

#include "ble_link.h"
#include "ble_rules.h"
#include "blindside_config.h"
#include "cut_schedule.h"
#include "imu_task.h"
#include "radar_port.h"
#include "stream_backlog.h"

namespace {

constexpr size_t kOutboxCapacity = 6;

struct SenderState {
    SenderSources sources;
    Outbox outbox;
    uint16_t next_seq;
    uint32_t next_status_ms;
    uint32_t reported_generation;
    uint32_t upstream_drops_seen;
    uint32_t skipped_cuts;
};

struct StatusPair {
    StatusEntry entries[kRadarCount];
};

StreamBacklog g_backlog;
Packet g_packets[kOutboxCapacity];
SenderState g_state;
portMUX_TYPE g_stats_lock = portMUX_INITIALIZER_UNLOCKED;
SenderStats g_stats;

void drain_radar_frames(QueueHandle_t queue) {
    RadarFrame frame;
    while (xQueueReceive(queue, &frame, 0) == pdTRUE) {
        backlog_add_radar(g_backlog, frame);
    }
}

void drain_imu_samples(QueueHandle_t queue, uint8_t imu_id) {
    ImuSample sample;
    while (xQueueReceive(queue, &sample, 0) == pdTRUE) {
        backlog_add_imu(g_backlog, imu_id, sample);
    }
}

uint32_t upstream_drops() {
    return radar_port_queue_overflows() + imu_task_snapshot(0).queue_overflows + imu_task_snapshot(1).queue_overflows;
}

void drain_sources(SenderState& state) {
    drain_radar_frames(state.sources.radar_frames);
    for (uint8_t imu = 0; imu < kImuCount; ++imu) {
        drain_imu_samples(state.sources.imu_samples[imu], imu);
    }
    uint32_t drops = upstream_drops();
    backlog_note_upstream_drops(g_backlog, drops - state.upstream_drops_seen);
    state.upstream_drops_seen = drops;
}

LinkHealth current_health(uint32_t now_ms) {
    LinkHealth health{};
    for (uint8_t i = 0; i < kRadarCount; ++i) {
        health.radar_alive[i] = radar_snapshot_alive(radar_port_snapshot(i), now_ms);
    }
    for (uint8_t i = 0; i < kImuCount; ++i) {
        health.imu_ok[i] = imu_task_snapshot(i).ok;
    }
    health.data_dropped = g_backlog.dropped_since_cut;
    return health;
}

StatusPair current_status() {
    StatusPair status{};
    for (uint8_t i = 0; i < kRadarCount; ++i) {
        status.entries[i] = radar_port_snapshot(i).status;
        status.entries[i].radar_id = i;
    }
    return status;
}

CutInput cut_input_for(const SenderState& state, uint32_t cut_ms, uint32_t generation) {
    CutInput input = backlog_cut_input(g_backlog, cut_ms);
    if (status_due(cut_ms, state.next_status_ms)) {
        input = with_status(input, current_status().entries);
    }
    if (link_report_due(generation, state.reported_generation)) {
        input = with_link(input, ble_link_snapshot().params);
    }
    return input;
}

void remember_optional_sections(SenderState& state, const BundleResult& result, uint32_t cut_ms,
                                uint32_t generation) {
    if (result.status_consumed) {
        state.next_status_ms = next_status_after(cut_ms);
    }
    if (result.link_consumed) {
        state.reported_generation = generation;
    }
}

void bundle(SenderState& state, uint32_t cut_ms, uint16_t mtu) {
    uint32_t generation = ble_link_report_generation();
    CutInput input = cut_input_for(state, cut_ms, generation);
    HeaderFields header{packet_flags(current_health(cut_ms)), state.next_seq, cut_ms};
    BundleResult result = bundle_cut(input, header, payload_limit_for_mtu(mtu), g_packets, kOutboxCapacity);
    backlog_consume(g_backlog, result);
    state.outbox = outbox_refilled(state.outbox, result.packet_count);
    state.next_seq = result.next_seq;
    remember_optional_sections(state, result, cut_ms, generation);
}

void make_cut(SenderState& state, uint32_t cut_ms) {
    LinkSnapshot link = ble_link_snapshot();
    bool gate_open = stream_gate_open(StreamGateInput{link.connected, link.subscribed, link.trusted, link.mtu});
    switch (cut_action(gate_open, state.outbox)) {
        case CutAction::DiscardAll:
            backlog_clear(g_backlog);
            state.outbox = outbox_cleared(state.outbox);
            break;
        case CutAction::KeepBacklog:
            state.skipped_cuts++;
            break;
        case CutAction::Bundle:
            bundle(state, cut_ms, link.mtu);
            break;
    }
}

bool send_head(SenderState& state) {
    const Packet& packet = g_packets[state.outbox.head];
    bool sent = ble_link_notify(packet.bytes, packet.length);
    state.outbox = outbox_after_send(state.outbox, sent);
    return sent;
}

void flush_outbox(SenderState& state, uint32_t cut_ms) {
    while (!outbox_empty(state.outbox)) {
        if (send_head(state)) {
            continue;
        }
        if (!retry_allowed(cut_ms, millis())) {
            return;
        }
        vTaskDelay(pdMS_TO_TICKS(kNotifyRetryMs));
    }
}

void publish_stats(const SenderState& state) {
    SenderStats stats{state.outbox.sent, state.outbox.failures, g_backlog.dropped_total, state.skipped_cuts};
    taskENTER_CRITICAL(&g_stats_lock);
    g_stats = stats;
    taskEXIT_CRITICAL(&g_stats_lock);
}

void bundler_task(void*) {
    g_state.next_status_ms = millis();
    TickType_t wake = xTaskGetTickCount();
    for (;;) {
        vTaskDelayUntil(&wake, pdMS_TO_TICKS(kCutPeriodMs));
        uint32_t cut_ms = millis();
        drain_sources(g_state);
        make_cut(g_state, cut_ms);
        flush_outbox(g_state, cut_ms);
        publish_stats(g_state);
    }
}

}  // namespace

void stream_sender_start(const SenderSources& sources) {
    g_state = SenderState{};
    g_state.sources = sources;
    backlog_clear(g_backlog);
    xTaskCreatePinnedToCore(bundler_task, "bundler", config::kTaskStackBytes, nullptr, config::kBundlerTaskPriority,
                            nullptr, config::kSensorCore);
}

SenderStats stream_sender_stats() {
    taskENTER_CRITICAL(&g_stats_lock);
    SenderStats stats = g_stats;
    taskEXIT_CRITICAL(&g_stats_lock);
    return stats;
}
