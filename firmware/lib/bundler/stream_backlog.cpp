#include "stream_backlog.h"

#include <string.h>

namespace {

bool time_before(uint32_t a_ms, uint32_t b_ms) {
    return static_cast<int32_t>(a_ms - b_ms) < 0;
}

template <typename T>
void remove_front(T* items, size_t& count, size_t removed) {
    size_t kept = removed < count ? count - removed : 0;
    memmove(items, items + (count - kept), kept * sizeof(T));
    count = kept;
}

void mark_dropped(StreamBacklog& backlog) {
    backlog.dropped_total++;
    backlog.dropped_since_cut = true;
}

void drop_oldest_radar(StreamBacklog& backlog) {
    remove_front(backlog.radar, backlog.radar_count, 1);
    mark_dropped(backlog);
}

void drop_oldest_imu(StreamBacklog& backlog, uint8_t imu_id) {
    remove_front(backlog.imu[imu_id], backlog.imu_count[imu_id], 1);
    mark_dropped(backlog);
}

uint8_t imu_holding_oldest_sample(const StreamBacklog& backlog) {
    if (backlog.imu_count[0] == 0) {
        return 1;
    }
    if (backlog.imu_count[1] == 0) {
        return 0;
    }
    return time_before(backlog.imu[1][0].t_ms, backlog.imu[0][0].t_ms) ? 1 : 0;
}

void drop_one_item(StreamBacklog& backlog) {
    if (backlog.radar_count > 0) {
        drop_oldest_radar(backlog);
        return;
    }
    drop_oldest_imu(backlog, imu_holding_oldest_sample(backlog));
}

void enforce_budget(StreamBacklog& backlog) {
    while (backlog_pending_bytes(backlog) > kBacklogBudgetBytes) {
        drop_one_item(backlog);
    }
}

size_t radar_insert_position(const StreamBacklog& backlog, uint32_t t_ms) {
    size_t position = backlog.radar_count;
    while (position > 0 && time_before(t_ms, backlog.radar[position - 1].t_ms)) {
        position--;
    }
    return position;
}

void insert_radar_in_time_order(StreamBacklog& backlog, const RadarFrame& frame) {
    size_t position = radar_insert_position(backlog, frame.t_ms);
    size_t moved = backlog.radar_count - position;
    memmove(&backlog.radar[position + 1], &backlog.radar[position], moved * sizeof(RadarFrame));
    backlog.radar[position] = frame;
    backlog.radar_count++;
}

}  // namespace

void backlog_clear(StreamBacklog& backlog) {
    backlog.radar_count = 0;
    backlog.imu_count[0] = 0;
    backlog.imu_count[1] = 0;
    backlog.dropped_since_cut = false;
}

void backlog_add_radar(StreamBacklog& backlog, const RadarFrame& frame) {
    if (backlog.radar_count == kBacklogRadarCapacity) {
        drop_oldest_radar(backlog);
    }
    insert_radar_in_time_order(backlog, frame);
    enforce_budget(backlog);
}

void backlog_add_imu(StreamBacklog& backlog, uint8_t imu_id, const ImuSample& sample) {
    if (imu_id >= kImuCount) {
        return;
    }
    if (backlog.imu_count[imu_id] == kBacklogImuCapacity) {
        drop_oldest_imu(backlog, imu_id);
    }
    backlog.imu[imu_id][backlog.imu_count[imu_id]] = sample;
    backlog.imu_count[imu_id]++;
    enforce_budget(backlog);
}

size_t backlog_pending_bytes(const StreamBacklog& backlog) {
    size_t imu_samples = backlog.imu_count[0] + backlog.imu_count[1];
    return backlog.radar_count * kRadarSectionSize + imu_samples * kImuSampleWireSize;
}

size_t backlog_radar_ready(const StreamBacklog& backlog, uint32_t cutoff_ms) {
    size_t ready = 0;
    while (ready < backlog.radar_count && time_before(backlog.radar[ready].t_ms, cutoff_ms)) {
        ready++;
    }
    return ready;
}

uint32_t cut_cutoff_ms(uint32_t cut_ms) {
    return cut_ms - kCutGuardMs;
}

CutInput backlog_cut_input(const StreamBacklog& backlog, uint32_t cut_ms) {
    CutInput input{};
    input.radar_frames = backlog.radar;
    input.radar_count = backlog_radar_ready(backlog, cut_cutoff_ms(cut_ms));
    for (uint8_t imu = 0; imu < kImuCount; ++imu) {
        input.imu_samples[imu] = backlog.imu[imu];
        input.imu_counts[imu] = backlog.imu_count[imu];
    }
    return input;
}

void backlog_consume(StreamBacklog& backlog, const BundleResult& result) {
    remove_front(backlog.radar, backlog.radar_count, result.radar_consumed);
    for (uint8_t imu = 0; imu < kImuCount; ++imu) {
        remove_front(backlog.imu[imu], backlog.imu_count[imu], result.imu_consumed[imu]);
    }
    if (result.packet_count > 0) {
        backlog.dropped_since_cut = false;
    }
}

void backlog_note_upstream_drops(StreamBacklog& backlog, uint32_t dropped) {
    if (dropped == 0) {
        return;
    }
    backlog.dropped_total += dropped;
    backlog.dropped_since_cut = true;
}
