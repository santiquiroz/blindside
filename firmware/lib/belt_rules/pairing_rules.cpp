#include "pairing_rules.h"

namespace {

uint32_t unauthenticated_grace_ms(bool pairing_allowed) {
    return pairing_allowed ? kPairingWindowMs : kUnauthenticatedGraceMs;
}

ButtonGesture gesture_for_hold(uint32_t hold_ms) {
    if (hold_ms >= kHoldToResetPairingMs) {
        return ButtonGesture::ResetPairing;
    }
    return hold_ms >= kHoldToOpenWindowMs ? ButtonGesture::OpenPairingWindow : ButtonGesture::None;
}

}  // namespace

bool gestures_still_counted(uint32_t now_ms) {
    return now_ms <= kGestureWindowMs;
}

ButtonGesture gesture_on_release(uint32_t hold_ms, uint32_t released_at_ms) {
    return gestures_still_counted(released_at_ms) ? gesture_for_hold(hold_ms) : ButtonGesture::None;
}

PairingWindow window_opened(uint32_t now_ms) {
    return PairingWindow{true, now_ms};
}

PairingWindow window_closed() {
    return PairingWindow{false, 0};
}

PairingWindow initial_window(bool has_trusted_bond, uint32_t now_ms) {
    return has_trusted_bond ? window_closed() : window_opened(now_ms);
}

PairingWindow window_after_tick(const PairingWindow& window, uint32_t now_ms) {
    bool expired = window.open && now_ms - window.opened_ms >= kPairingWindowMs;
    return expired ? window_closed() : window;
}

bool link_is_secure(bool encrypted, bool bonded, bool authenticated, bool require_mitm) {
    return encrypted && bonded && (authenticated || !require_mitm);
}

AuthDecision decide_authentication(bool link_secure, bool peer_is_trusted, bool pairing_allowed) {
    if (!link_secure) {
        return AuthDecision::Reject;
    }
    if (peer_is_trusted) {
        return AuthDecision::AcceptTrusted;
    }
    return pairing_allowed ? AuthDecision::AcceptNewBond : AuthDecision::Reject;
}

bool should_drop_at_connect(bool pairing_allowed, bool peer_is_trusted_identity) {
    return !pairing_allowed && !peer_is_trusted_identity;
}

bool should_drop_unauthenticated(uint32_t connected_ms, uint32_t now_ms, bool pairing_allowed) {
    return now_ms - connected_ms >= unauthenticated_grace_ms(pairing_allowed);
}

uint32_t passkey_from_random(uint32_t random_value) {
    return random_value % kPasskeyModulus;
}
