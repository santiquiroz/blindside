#pragma once

#include <stddef.h>
#include <stdint.h>

#include "bundler.h"

constexpr size_t kMaxLinks = 2;
constexpr uint16_t kNoConnHandle = 0xFFFF;
// BLE units: connection interval 1.25 ms, supervision timeout 10 ms.
constexpr uint16_t kLinkLatency = 0;
constexpr uint16_t kSupervisionTimeoutUnits = 400;
constexpr uint16_t kWatchIntervalMinUnits = 24;
constexpr uint16_t kWatchIntervalMaxUnits = 40;
constexpr uint16_t kPhoneIntervalMinUnits = 48;
constexpr uint16_t kPhoneIntervalMaxUnits = 80;

enum class LinkRole : uint8_t { Watch = 0, Phone = 1 };

struct ConnParamsRequest {
    uint16_t min_units;
    uint16_t max_units;
    uint16_t latency;
    uint16_t timeout_units;
};

struct AdvertisingNeed {
    size_t connections;
    size_t capacity;
    size_t trusted_links;
    size_t bonds;
    bool window_open;
    bool second_link_on_demand;
};

LinkRole role_from_argument(uint8_t argument);
const char* role_name(LinkRole role);
ConnParamsRequest conn_params_for_role(LinkRole role);
bool conn_params_retry_wanted(LinkRole role, const LinkParams& params, bool already_retried);
int slot_for_handle(const uint16_t* handles, size_t count, uint16_t handle);
bool advertising_wanted(const AdvertisingNeed& need);
