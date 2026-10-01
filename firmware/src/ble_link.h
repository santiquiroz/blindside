#pragma once

#include <NimBLEDevice.h>
#include <stddef.h>
#include <stdint.h>

#include "bundler.h"
#include "control_command.h"

struct LinkSnapshot {
    bool connected;
    bool subscribed;
    bool trusted;
    bool peer_bonded;
    uint16_t mtu;
    uint32_t connected_at_ms;
    LinkParams params;
    int last_disconnect_reason;
};

struct PeerSecurity {
    bool valid;
    bool secure;
    NimBLEAddress identity;
};

void ble_link_begin(const char* device_name);
void ble_link_set_passkey(uint32_t passkey);
void ble_link_start_advertising(bool whitelist_only, uint32_t now_ms);
void ble_link_poll_advertising(bool whitelist_only, uint32_t now_ms);
LinkSnapshot ble_link_snapshot();
uint32_t ble_link_report_generation();
int8_t ble_link_tx_power();
bool ble_link_take_auth_event();
PeerSecurity ble_link_peer_security();
void ble_link_set_trusted(bool trusted);
ControlCommand ble_link_take_control();
bool ble_link_notify(const uint8_t* bytes, size_t length);
void ble_link_update_info(const char* json, size_t length);
void ble_link_disconnect();
