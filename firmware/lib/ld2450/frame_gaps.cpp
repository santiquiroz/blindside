#include "frame_gaps.h"

namespace {

uint32_t smaller_gap(const FrameGaps& gaps, uint32_t gap_ms) {
    bool first = gaps.count == 0;
    return first || gap_ms < gaps.min_gap_ms ? gap_ms : gaps.min_gap_ms;
}

uint32_t larger_gap(const FrameGaps& gaps, uint32_t gap_ms) {
    return gap_ms > gaps.max_gap_ms ? gap_ms : gaps.max_gap_ms;
}

FrameGaps with_gap(const FrameGaps& gaps, uint32_t gap_ms) {
    FrameGaps next = gaps;
    next.min_gap_ms = smaller_gap(gaps, gap_ms);
    next.max_gap_ms = larger_gap(gaps, gap_ms);
    next.count = gaps.count + 1;
    return next;
}

}  // namespace

FrameGaps frame_gaps_start() {
    return FrameGaps{};
}

FrameGaps frame_gaps_with(const FrameGaps& gaps, uint32_t t_ms) {
    FrameGaps next = gaps.has_last ? with_gap(gaps, t_ms - gaps.last_t_ms) : gaps;
    next.has_last = true;
    next.last_t_ms = t_ms;
    return next;
}

FrameGaps frame_gaps_new_window(const FrameGaps& gaps) {
    FrameGaps next = frame_gaps_start();
    next.has_last = gaps.has_last;
    next.last_t_ms = gaps.last_t_ms;
    return next;
}
