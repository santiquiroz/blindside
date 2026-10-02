#include "ble_advertising.h"

#include <NimBLEDevice.h>

#include "ble_link.h"
#include "ble_rules.h"
#include "blindside_config.h"
#include "link_roles.h"

namespace {

struct AdvertisingState {
    bool whitelist_only;
    AdvertisingSpeed speed;
    uint32_t since_ms;
};

AdvertisingState g_advertising{};

void apply_interval(NimBLEAdvertising* advertising, AdvertisingSpeed speed) {
    bool fast = speed == AdvertisingSpeed::Fast;
    advertising->setMinInterval(fast ? config::kFastAdvertisingMinUnits : config::kSlowAdvertisingMinUnits);
    advertising->setMaxInterval(fast ? config::kFastAdvertisingMaxUnits : config::kSlowAdvertisingMaxUnits);
}

void restart_advertising(bool whitelist_only, AdvertisingSpeed speed) {
    NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
    advertising->stop();
    advertising->setScanFilter(false, whitelist_only);
    apply_interval(advertising, speed);
    advertising->start();
    g_advertising.whitelist_only = whitelist_only;
    g_advertising.speed = speed;
}

bool advertising_needs_restart(bool whitelist_only, AdvertisingSpeed wanted) {
    bool active = NimBLEDevice::getAdvertising()->isAdvertising();
    return !active || whitelist_only != g_advertising.whitelist_only || wanted != g_advertising.speed;
}

void stop_advertising_if_running() {
    NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
    if (advertising->isAdvertising()) {
        advertising->stop();
    }
}

size_t trusted_link_count() {
    size_t count = 0;
    for (uint8_t slot = 0; slot < kMaxLinks; ++slot) {
        count += ble_link_snapshot(slot).trusted ? 1 : 0;
    }
    return count;
}

AdvertisingNeed advertising_need(const AdvertisingPlan& plan) {
    return AdvertisingNeed{ble_link_connection_count(), ble_link_capacity(), trusted_link_count(), plan.bonds,
                           plan.window_open, config::kSecondLinkAdvertisingOnDemand};
}

}  // namespace

void ble_advertising_configure(const char* device_name) {
    NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
    advertising->enableScanResponse(true);
    advertising->setName(device_name);
    advertising->addServiceUUID(config::kServiceUuid);
}

void ble_advertising_start(bool whitelist_only, uint32_t now_ms) {
    g_advertising.since_ms = now_ms;
    restart_advertising(whitelist_only, AdvertisingSpeed::Fast);
}

void ble_advertising_poll(const AdvertisingPlan& plan, uint32_t now_ms) {
    if (ble_link_take_disconnect_event()) {
        g_advertising.since_ms = now_ms;
    }
    if (!advertising_wanted(advertising_need(plan))) {
        stop_advertising_if_running();
        return;
    }
    AdvertisingSpeed wanted = advertising_speed(now_ms - g_advertising.since_ms);
    if (advertising_needs_restart(plan.whitelist_only, wanted)) {
        restart_advertising(plan.whitelist_only, wanted);
    }
}
