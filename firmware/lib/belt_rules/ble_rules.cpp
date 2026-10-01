#include "ble_rules.h"

#include <stdio.h>

bool stream_gate_open(const StreamGateInput& input) {
    bool peer_ready = input.connected && input.subscribed && input.trusted;
    return peer_ready && input.mtu >= kMinNotifyMtu;
}

AdvertisingSpeed advertising_speed(uint32_t advertising_for_ms) {
    return advertising_for_ms < kFastAdvertisingWindowMs ? AdvertisingSpeed::Fast : AdvertisingSpeed::Slow;
}

DeviceName device_name_from_mac(const uint8_t* mac) {
    DeviceName name{};
    snprintf(name.text, sizeof(name.text), "Blindside-%02X%02X", mac[4], mac[5]);
    return name;
}
