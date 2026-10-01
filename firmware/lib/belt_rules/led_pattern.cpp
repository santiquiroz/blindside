#include "led_pattern.h"

namespace {

bool in_lit_half(uint32_t elapsed_ms, uint32_t half_period_ms) {
    return (elapsed_ms / half_period_ms) % 2 == 0;
}

bool identify_showing(const LedInputs& inputs) {
    return inputs.identify_active && !identify_finished(inputs.identify_started_ms, inputs.now_ms);
}

bool pairing_blink_showing(const LedInputs& inputs) {
    return inputs.pairing_window_open && !inputs.session_active;
}

}  // namespace

bool identify_finished(uint32_t identify_started_ms, uint32_t now_ms) {
    return now_ms - identify_started_ms >= kIdentifyDurationMs;
}

bool led_on(const LedInputs& inputs) {
    if (identify_showing(inputs)) {
        return in_lit_half(inputs.now_ms - inputs.identify_started_ms, kIdentifyBlinkHalfPeriodMs);
    }
    if (pairing_blink_showing(inputs)) {
        return in_lit_half(inputs.now_ms, kPairingBlinkHalfPeriodMs);
    }
    return inputs.now_ms < kBootLedMs;
}
