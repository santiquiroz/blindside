#pragma once

#include <stddef.h>
#include <stdint.h>

#include "bundler.h"

constexpr size_t kBacklogBudgetBytes = 2400;
// A frame stamped in the last few ms may still have an older partner frame in flight on the other UART.
constexpr uint32_t kCutGuardMs = 5;
constexpr size_t kBacklogRadarCapacity = kBacklogBudgetBytes / kRadarSectionSize + 1;
constexpr size_t kBacklogImuCapacity = kBacklogBudgetBytes / kImuSampleWireSize + 1;

struct StreamBacklog {
    RadarFrame radar[kBacklogRadarCapacity];
    size_t radar_count;
    ImuSample imu[kImuCount][kBacklogImuCapacity];
    size_t imu_count[kImuCount];
    uint32_t dropped_total;
    bool dropped_since_cut;
};

void backlog_clear(StreamBacklog& backlog);
void backlog_add_radar(StreamBacklog& backlog, const RadarFrame& frame);
void backlog_add_imu(StreamBacklog& backlog, uint8_t imu_id, const ImuSample& sample);
size_t backlog_pending_bytes(const StreamBacklog& backlog);
size_t backlog_radar_ready(const StreamBacklog& backlog, uint32_t cutoff_ms);
uint32_t cut_cutoff_ms(uint32_t cut_ms);
CutInput backlog_cut_input(const StreamBacklog& backlog, uint32_t cut_ms);
void backlog_consume(StreamBacklog& backlog, const BundleResult& result);
void backlog_note_upstream_drops(StreamBacklog& backlog, uint32_t dropped);
