#pragma once

#include <stddef.h>
#include <stdint.h>

#include "bond_store.h"
#include "link_roles.h"
#include "pairing_rules.h"

constexpr size_t kSerialLineCapacity = 24;

struct SlotPairing {
    uint32_t link_id;
    bool pairing_allowed;
    bool drop_requested;
};

struct PairingState {
    PairingWindow window;
    TrustedBonds trusted;
    uint8_t session_flags;
    uint32_t passkey;
    bool button_was_pressed;
    uint32_t button_pressed_ms;
    SlotPairing slots[kMaxLinks];
    char serial_line[kSerialLineCapacity];
    size_t serial_length;
};

PairingState pairing_begin(uint32_t now_ms);
void pairing_poll(PairingState& state, uint32_t now_ms);
void pairing_open_window(PairingState& state, uint32_t now_ms);
void pairing_note_role(PairingState& state, uint8_t slot, LinkRole role);
void pairing_note_session(PairingState& state, uint8_t slot, bool active);
bool pairing_session_running(const PairingState& state);
bool pairing_whitelist_only(const PairingState& state);
uint8_t pairing_bond_count();
