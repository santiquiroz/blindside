#pragma once

#include <stddef.h>
#include <stdint.h>

constexpr uint32_t kCutPeriodMs = 100;
constexpr uint32_t kStatusPeriodMs = 1000;
constexpr uint32_t kNotifyRetryMs = 10;

enum class CutAction : uint8_t { DiscardAll, KeepBacklog, Bundle };

bool deadline_reached(uint32_t now_ms, uint32_t deadline_ms);
bool status_due(uint32_t now_ms, uint32_t next_status_ms);
uint32_t next_status_after(uint32_t sent_at_ms);
bool link_report_due(uint32_t link_generation, uint32_t reported_generation);
CutAction cut_action(bool gate_open, bool delivery_pending);
bool retry_allowed(uint32_t cut_ms, uint32_t now_ms);
