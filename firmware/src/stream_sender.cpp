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
    Fanout fanout;
    uint16_t next_seq;
    uint32_t next_status_ms;
    uint32_t reported_generation;
    uint32_t upstream_drops_seen;
    uint32_t skipped_cuts;
};

struct StatusPair {
    StatusEntry entries[kRadarCount];
};

struct LinkView {
    FanoutLink links[kMaxLinks];
    LinkParams params[kMaxLinks];
    NotifyOrder order;
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

FanoutLink fanout_link(const LinkSnapshot& link) {
    bool gated = stream_gate_open(StreamGateInput{link.connected, link.subscribed, link.trusted, link.mtu});
    return FanoutLink{gated, link.role, link.link_id, link.mtu};
}

LinkView current_links() {
    LinkView view{};
    for (uint8_t slot = 0; slot < kMaxLinks; ++slot) {
        LinkSnapshot link = ble_link_snapshot(slot);
        view.links[slot] = fanout_link(link);
        view.params[slot] = link.params;
    }
    view.order = notify_order(view.links);
    return view;
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

// LINK carries the parameters of the first link in notify order, the watch's when it is streaming.
CutInput cut_input_for(const SenderState& state, uint32_t cut_ms, uint32_t generation, const LinkView& view) {
    CutInput input = backlog_cut_input(g_backlog, cut_ms);
    if (status_due(cut_ms, state.next_status_ms)) {
        input = with_status(input, current_status().entries);
    }
    if (link_report_due(generation, state.reported_generation)) {
        input = with_link(input, view.params[view.order.slots[0]]);
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

void bundle(SenderState& state, uint32_t cut_ms, const LinkView& view) {
    uint32_t generation = ble_link_report_generation();
    CutInput input = cut_input_for(state, cut_ms, generation, view);
    HeaderFields header{packet_flags(current_health(cut_ms)), state.next_seq, cut_ms};
    size_t limit = payload_limit_for_mtu(gated_min_mtu(view.links));
    BundleResult result = bundle_cut(input, header, limit, g_packets, kOutboxCapacity);
    backlog_consume(g_backlog, result);
    state.fanout = fanout_refilled(state.fanout, result.packet_count, view.links);
    state.next_seq = result.next_seq;
    remember_optional_sections(state, result, cut_ms, generation);
}

void make_cut(SenderState& state, uint32_t cut_ms, const LinkView& view) {
    bool gate_open = view.order.count > 0;
    switch (cut_action(gate_open, fanout_pending(state.fanout, view.order))) {
        case CutAction::DiscardAll:
            backlog_clear(g_backlog);
            state.fanout = fanout_cleared(state.fanout);
            break;
        case CutAction::KeepBacklog:
            state.skipped_cuts++;
            break;
        case CutAction::Bundle:
            bundle(state, cut_ms, view);
            break;
    }
}

bool notify_planned_link(const Delivery& delivery) {
    if (!notify_allowed(delivery, ble_link_backlog(delivery.slot))) {
        return false;
    }
    const Packet& packet = g_packets[delivery.packet];
    return ble_link_notify(delivery.slot, delivery.link_id, packet.bytes, packet.length);
}

bool delivered_or_dropped(SenderState& state, const Delivery& delivery) {
    bool sent = notify_planned_link(delivery);
    state.fanout = fanout_after_notify(state.fanout, delivery, sent);
    return sent || !delivery.protected_link;
}

void flush_outbox(SenderState& state, const NotifyOrder& order, uint32_t cut_ms) {
    for (Delivery delivery = next_delivery(state.fanout, order); delivery.found;
         delivery = next_delivery(state.fanout, order)) {
        if (delivered_or_dropped(state, delivery)) {
            continue;
        }
        if (!retry_allowed(cut_ms, millis())) {
            return;
        }
        vTaskDelay(pdMS_TO_TICKS(kNotifyRetryMs));
    }
}

void publish_stats(const SenderState& state) {
    SenderStats stats{};
    stats.notify_failures = state.fanout.failures;
    stats.dropped_total = g_backlog.dropped_total;
    stats.skipped_cuts = state.skipped_cuts;
    for (size_t slot = 0; slot < kMaxLinks; ++slot) {
        stats.links[slot] = state.fanout.links[slot];
    }
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
        LinkView view = current_links();
        drain_sources(g_state);
        g_state.fanout = fanout_synced(g_state.fanout, view.links);
        make_cut(g_state, cut_ms, view);
        flush_outbox(g_state, view.order, cut_ms);
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
