#pragma once

#include <NimBLEDevice.h>
#include <stddef.h>
#include <stdint.h>

#include "bundler.h"
#include "control_command.h"
#include "link_roles.h"

struct LinkSnapshot {
    bool connected;
    bool subscribed;
    bool trusted;
    bool peer_bonded;
    LinkRole role;
    uint16_t mtu;
    uint32_t connected_at_ms;
    uint32_t link_id;
    LinkParams params;
};

using InfoWriter = size_t (*)(uint8_t reader_slot, char* out, size_t out_size);

struct PeerSecurity {
    bool valid;
    bool secure;
    NimBLEAddress identity;
};

void ble_link_begin(const char* device_name, InfoWriter info_writer);
void ble_link_set_passkey(uint32_t passkey);
size_t ble_link_capacity();
size_t ble_link_connection_count();
LinkSnapshot ble_link_snapshot(uint8_t slot);
int ble_link_last_disconnect_reason();
bool ble_link_take_disconnect_event();
uint32_t ble_link_report_generation();
int8_t ble_link_tx_power();
bool ble_link_take_auth_event(uint8_t slot);
PeerSecurity ble_link_peer_security(uint8_t slot);
void ble_link_set_trusted(uint8_t slot, bool trusted);
void ble_link_set_role(uint8_t slot, LinkRole role);
void ble_link_reassert_conn_params();
ControlCommand ble_link_take_control(uint8_t slot);
uint16_t ble_link_backlog(uint8_t slot);
bool ble_link_notify(uint8_t slot, uint32_t link_id, const uint8_t* bytes, size_t length);
void ble_link_disconnect(uint8_t slot);
void ble_link_disconnect_all();
