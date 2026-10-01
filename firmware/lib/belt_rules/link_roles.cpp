#include "link_roles.h"

namespace {

constexpr ConnParamsRequest kWatchParams{kWatchIntervalMinUnits, kWatchIntervalMaxUnits, kLinkLatency,
                                         kSupervisionTimeoutUnits};
constexpr ConnParamsRequest kPhoneParams{kPhoneIntervalMinUnits, kPhoneIntervalMaxUnits, kLinkLatency,
                                         kSupervisionTimeoutUnits};

bool params_match_request(const LinkParams& params, const ConnParamsRequest& request) {
    bool interval_ok = params.interval_units >= request.min_units && params.interval_units <= request.max_units;
    return interval_ok && params.latency == request.latency;
}

bool another_device_may_connect(const AdvertisingNeed& need) {
    return need.window_open || need.bonds > need.trusted_links;
}

}  // namespace

LinkRole role_from_argument(uint8_t argument) {
    return argument == static_cast<uint8_t>(LinkRole::Phone) ? LinkRole::Phone : LinkRole::Watch;
}

const char* role_name(LinkRole role) {
    return role == LinkRole::Phone ? "phone" : "watch";
}

ConnParamsRequest conn_params_for_role(LinkRole role) {
    return role == LinkRole::Phone ? kPhoneParams : kWatchParams;
}

// Only the phone is asked again, and once: the watch app tunes its own priority, and a central that insists wins.
bool conn_params_retry_wanted(LinkRole role, const LinkParams& params, bool already_retried) {
    if (role != LinkRole::Phone || already_retried) {
        return false;
    }
    return !params_match_request(params, conn_params_for_role(role));
}

int slot_for_handle(const uint16_t* handles, size_t count, uint16_t handle) {
    for (size_t i = 0; i < count; ++i) {
        if (handles[i] == handle) {
            return static_cast<int>(i);
        }
    }
    return -1;
}

bool advertising_wanted(const AdvertisingNeed& need) {
    if (need.connections >= need.capacity) {
        return false;
    }
    if (need.connections == 0 || !need.second_link_on_demand) {
        return true;
    }
    return another_device_may_connect(need);
}
