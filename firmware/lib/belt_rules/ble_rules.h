#pragma once

#include <stddef.h>
#include <stdint.h>

constexpr uint32_t kFastAdvertisingWindowMs = 30000;
constexpr uint16_t kMinNotifyMtu = 247;
constexpr size_t kDeviceNameSize = 16;

enum class AdvertisingSpeed : uint8_t { Fast, Slow };

struct DeviceName {
    char text[kDeviceNameSize];
};

struct StreamGateInput {
    bool connected;
    bool subscribed;
    bool trusted;
    uint16_t mtu;
};

bool stream_gate_open(const StreamGateInput& input);
AdvertisingSpeed advertising_speed(uint32_t advertising_for_ms);
DeviceName device_name_from_mac(const uint8_t* mac);
