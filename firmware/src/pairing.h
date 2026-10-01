#pragma once

#include <NimBLEDevice.h>
#include <stddef.h>
#include <stdint.h>

#include "pairing_rules.h"

constexpr size_t kSerialLineCapacity = 24;

struct PairingState {
    PairingWindow window;
    NimBLEAddress trusted;
    uint32_t passkey;
    bool button_was_pressed;
    uint32_t button_pressed_ms;
    bool connection_seen;
    bool pairing_allowed_for_connection;
    bool drop_requested;
    char serial_line[kSerialLineCapacity];
    size_t serial_length;
};

PairingState pairing_begin(uint32_t now_ms);
void pairing_poll(PairingState& state, uint32_t now_ms);
bool pairing_whitelist_only(const PairingState& state);
