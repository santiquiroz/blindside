#pragma once

#include <stdint.h>

constexpr uint32_t kPairingWindowMs = 60000;
constexpr uint32_t kGestureWindowMs = 60000;
constexpr uint32_t kHoldToOpenWindowMs = 3000;
constexpr uint32_t kHoldToResetPairingMs = 10000;
constexpr uint32_t kPasskeyModulus = 1000000;
constexpr uint32_t kUnauthenticatedGraceMs = 5000;

enum class ButtonGesture : uint8_t { None, OpenPairingWindow, ResetPairing };
enum class AuthDecision : uint8_t { AcceptTrusted, AcceptNewBond, Reject };

struct PairingWindow {
    bool open;
    uint32_t opened_ms;
};

bool gestures_still_counted(uint32_t now_ms);
ButtonGesture gesture_on_release(uint32_t hold_ms, uint32_t released_at_ms);
PairingWindow window_opened(uint32_t now_ms);
PairingWindow window_closed();
PairingWindow initial_window(bool has_trusted_bond, uint32_t now_ms);
PairingWindow window_after_tick(const PairingWindow& window, uint32_t now_ms);
bool link_is_secure(bool encrypted, bool bonded, bool authenticated, bool require_mitm);
AuthDecision decide_authentication(bool link_secure, bool peer_is_trusted, bool pairing_allowed);
bool should_drop_at_connect(bool pairing_allowed, bool peer_is_trusted_identity);
bool should_drop_unauthenticated(uint32_t connected_ms, uint32_t now_ms, bool pairing_allowed);
uint32_t passkey_from_random(uint32_t random_value);
