#include "cut_schedule.h"

namespace {

Outbox after_success(const Outbox& outbox) {
    Outbox next = outbox;
    next.head++;
    next.count--;
    next.sent++;
    return next;
}

Outbox after_failure(const Outbox& outbox) {
    Outbox next = outbox;
    next.failures++;
    return next;
}

}  // namespace

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

CutAction cut_action(bool gate_open, const Outbox& outbox) {
    if (!gate_open) {
        return CutAction::DiscardAll;
    }
    return outbox_empty(outbox) ? CutAction::Bundle : CutAction::KeepBacklog;
}

Outbox outbox_refilled(const Outbox& outbox, size_t packet_count) {
    Outbox next = outbox;
    next.head = 0;
    next.count = packet_count;
    return next;
}

Outbox outbox_cleared(const Outbox& outbox) {
    return outbox_refilled(outbox, 0);
}

Outbox outbox_after_send(const Outbox& outbox, bool sent) {
    return sent ? after_success(outbox) : after_failure(outbox);
}

bool outbox_empty(const Outbox& outbox) {
    return outbox.count == 0;
}

bool retry_allowed(uint32_t cut_ms, uint32_t now_ms) {
    return now_ms - cut_ms + kNotifyRetryMs < kCutPeriodMs;
}
