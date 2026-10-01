#pragma once

#include <stdint.h>

struct FrameGaps {
    bool has_last;
    uint32_t last_t_ms;
    uint32_t count;
    uint32_t min_gap_ms;
    uint32_t max_gap_ms;
};

FrameGaps frame_gaps_start();
FrameGaps frame_gaps_with(const FrameGaps& gaps, uint32_t t_ms);
FrameGaps frame_gaps_new_window(const FrameGaps& gaps);
