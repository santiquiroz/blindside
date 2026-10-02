#pragma once

#include <stdint.h>

constexpr uint32_t kBootLedMs = 2000;
constexpr uint32_t kPairingBlinkHalfPeriodMs = 250;
constexpr uint32_t kIdentifyBlinkHalfPeriodMs = 200;
constexpr uint32_t kIdentifyBlinkCount = 3;
constexpr uint32_t kIdentifyDurationMs = 2 * kIdentifyBlinkHalfPeriodMs * kIdentifyBlinkCount;

struct LedInputs {
    uint32_t now_ms;
    bool pairing_window_open;
    bool identify_active;
    uint32_t identify_started_ms;
    bool session_active;
};

bool led_on(const LedInputs& inputs);
bool identify_finished(uint32_t identify_started_ms, uint32_t now_ms);
