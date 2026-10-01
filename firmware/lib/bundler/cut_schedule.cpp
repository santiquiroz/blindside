#include "cut_schedule.h"

bool deadline_reached(uint32_t now_ms, uint32_t deadline_ms) {
    return static_cast<int32_t>(now_ms - deadline_ms) >= 0;
}

bool status_due(uint32_t now_ms, uint32_t next_status_ms) {
    return deadline_reached(now_ms, next_status_ms);
}

uint32_t next_status_after(uint32_t sent_at_ms) {
    return sent_at_ms + kStatusPeriodMs;
}

bool link_report_due(uint32_t link_generation, uint32_t reported_generation) {
    return link_generation != reported_generation;
}

CutAction cut_action(bool gate_open, bool delivery_pending) {
    if (!gate_open) {
        return CutAction::DiscardAll;
    }
    return delivery_pending ? CutAction::KeepBacklog : CutAction::Bundle;
}

bool retry_allowed(uint32_t cut_ms, uint32_t now_ms) {
    return now_ms - cut_ms + kNotifyRetryMs < kCutPeriodMs;
}
