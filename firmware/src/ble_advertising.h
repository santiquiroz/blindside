#pragma once

#include <stddef.h>
#include <stdint.h>

struct AdvertisingPlan {
    bool whitelist_only;
    bool window_open;
    size_t bonds;
};

void ble_advertising_configure(const char* device_name);
void ble_advertising_start(bool whitelist_only, uint32_t now_ms);
void ble_advertising_poll(const AdvertisingPlan& plan, uint32_t now_ms);
