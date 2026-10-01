#include "bond_guard.h"

#include <NimBLEDevice.h>

#include <atomic>

namespace {

constexpr size_t kStoreCapacity = MYNEWT_VAL(BLE_STORE_MAX_BONDS);

struct GuardedTrust {
    BondIdentity items[kMaxBonds];
    size_t count;
};

struct StoredPeers {
    ble_addr_t addresses[kStoreCapacity];
    BondIdentity identities[kStoreCapacity];
    size_t count;
};

// The loop task adopts bonds and the NimBLE host task reports store overflows, so the kept identities are shared.
portMUX_TYPE g_trust_lock = portMUX_INITIALIZER_UNLOCKED;
GuardedTrust g_trust{};
std::atomic<uint32_t> g_evicted{0};
std::atomic<uint32_t> g_refused{0};

BondIdentity identity_of(const ble_addr_t& address) {
    return bond_store_identity(NimBLEAddress(address));
}

GuardedTrust trust_copy() {
    taskENTER_CRITICAL(&g_trust_lock);
    GuardedTrust copy = g_trust;
    taskEXIT_CRITICAL(&g_trust_lock);
    return copy;
}

StoredPeers stored_peers() {
    StoredPeers peers{};
    int count = 0;
    if (ble_store_util_bonded_peers(peers.addresses, &count, kStoreCapacity) != 0) {
        return peers;
    }
    peers.count = static_cast<size_t>(count);
    for (size_t i = 0; i < peers.count; ++i) {
        peers.identities[i] = identity_of(peers.addresses[i]);
    }
    return peers;
}

bool record_owner(int obj_type, const ble_store_value& value, ble_addr_t& owner) {
    switch (obj_type) {
        case BLE_STORE_OBJ_TYPE_OUR_SEC:
        case BLE_STORE_OBJ_TYPE_PEER_SEC:
            owner = value.sec.peer_addr;
            return true;
        case BLE_STORE_OBJ_TYPE_CCCD:
            owner = value.cccd.peer_addr;
            return true;
        case BLE_STORE_OBJ_TYPE_CSFC:
            owner = value.csfc.peer_addr;
            return true;
        default:
            return false;
    }
}

int refuse_write() {
    g_refused++;
    return BLE_HS_ESTORE_CAP;
}

int unpair_counted(const ble_addr_t& address) {
    int rc = ble_gap_unpair(&address);
    if (rc == 0) {
        g_evicted++;
    }
    return rc;
}

int evict_untrusted(const ble_addr_t& owner) {
    StoredPeers peers = stored_peers();
    GuardedTrust trust = trust_copy();
    size_t victim = bond_to_evict(IdentityList{peers.identities, peers.count}, IdentityList{trust.items, trust.count},
                                  identity_of(owner));
    return victim < peers.count ? unpair_counted(peers.addresses[victim]) : refuse_write();
}

int handle_overflow(const ble_store_status_event& event) {
    ble_addr_t owner{};
    return record_owner(event.overflow.obj_type, *event.overflow.value, owner) ? evict_untrusted(owner)
                                                                                : refuse_write();
}

class StoreGuard : public NimBLEDeviceCallbacks {
  public:
    // Replaces NimBLE's default, which deletes the oldest bond (normally the watch's) when the store overflows.
    int onStoreStatus(ble_store_status_event* event, void*) override {
        if (event->event_code == BLE_STORE_EVENT_OVERFLOW) {
            return handle_overflow(*event);
        }
        // FULL only warns that a pairing may overflow later; that overflow is handled when it happens.
        return event->event_code == BLE_STORE_EVENT_FULL ? 0 : BLE_HS_EUNKNOWN;
    }
};

StoreGuard g_guard;

}  // namespace

void bond_guard_install() {
    NimBLEDevice::setDeviceCallbacks(&g_guard);
}

void bond_guard_trust(const TrustedBonds& trusted) {
    GuardedTrust next{};
    for (size_t i = 0; i < trusted.count; ++i) {
        next.items[i] = bond_store_identity(trusted.identities[i]);
    }
    next.count = trusted.count;
    taskENTER_CRITICAL(&g_trust_lock);
    g_trust = next;
    taskEXIT_CRITICAL(&g_trust_lock);
}

BondGuardEvents bond_guard_take_events() {
    return BondGuardEvents{g_evicted.exchange(0), g_refused.exchange(0)};
}
