#pragma once

#include <stddef.h>
#include <stdint.h>

constexpr uint32_t kCutPeriodMs = 100;
constexpr uint32_t kStatusPeriodMs = 1000;
constexpr uint32_t kNotifyRetryMs = 10;

enum class CutAction : uint8_t { DiscardAll, KeepBacklog, Bundle };

struct Outbox {
    size_t head;
    size_t count;
    uint32_t sent;
    uint32_t failures;
};

bool deadline_reached(uint32_t now_ms, uint32_t deadline_ms);
bool status_due(uint32_t now_ms, uint32_t next_status_ms);
uint32_t next_status_after(uint32_t sent_at_ms);
bool link_report_due(uint32_t link_generation, uint32_t reported_generation);
CutAction cut_action(bool gate_open, const Outbox& outbox);
Outbox outbox_refilled(const Outbox& outbox, size_t packet_count);
Outbox outbox_cleared(const Outbox& outbox);
Outbox outbox_after_send(const Outbox& outbox, bool sent);
bool outbox_empty(const Outbox& outbox);
bool retry_allowed(uint32_t cut_ms, uint32_t now_ms);
