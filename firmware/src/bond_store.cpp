#include "bond_store.h"

#include <Preferences.h>
#include <string.h>

#include "blindside_config.h"

namespace {

struct RoleRecords {
    BondRoleRecord entries[kMaxBonds];
    size_t count;
};

size_t stored_bond_count() {
    int stored = NimBLEDevice::getNumBonds();
    return stored > 0 ? static_cast<size_t>(stored) : 0;
}

void clear_whitelist() {
    for (size_t i = NimBLEDevice::getWhiteListCount(); i > 0; --i) {
        NimBLEDevice::whiteListRemove(NimBLEDevice::getWhiteListAddress(i - 1));
    }
}

BondIdentity identity_bytes(const NimBLEAddress& address) {
    BondIdentity identity{};
    memcpy(identity.address, address.getVal(), kBondAddressSize);
    identity.type = address.getType();
    return identity;
}

RoleRecords stored_role_records() {
    RoleRecords records{};
    Preferences preferences;
    preferences.begin(config::kPreferencesNamespace, true);
    size_t bytes = preferences.getBytes(config::kBondRolesKey, records.entries, sizeof(records.entries));
    preferences.end();
    records.count = bytes / sizeof(BondRoleRecord);
    return records;
}

TrustedBonds with_recorded_roles(const TrustedBonds& bonds) {
    RoleRecords records = stored_role_records();
    TrustedBonds next = bonds;
    for (size_t i = 0; i < next.count; ++i) {
        next.roles[i] = recorded_role(records.entries, records.count, identity_bytes(next.identities[i]));
    }
    return next;
}

}  // namespace

// NimBLE lists bonds in storage order, oldest first, so a crash mid-adoption loses only the newest bond.
TrustedBonds bond_store_load() {
    TrustedBonds bonds{};
    size_t kept = bonds_kept_at_boot(stored_bond_count());
    for (size_t i = 0; i < kept; ++i) {
        bonds = bond_store_with(bonds, NimBLEDevice::getBondedAddress(static_cast<int>(i)));
    }
    bond_store_forget_untrusted(bonds);
    return with_recorded_roles(bonds);
}

int bond_store_index_of(const TrustedBonds& bonds, const NimBLEAddress& identity) {
    for (size_t i = 0; i < bonds.count; ++i) {
        if (bonds.identities[i] == identity) {
            return static_cast<int>(i);
        }
    }
    return -1;
}

bool bond_store_contains(const TrustedBonds& bonds, const NimBLEAddress& identity) {
    return bond_store_index_of(bonds, identity) >= 0;
}

TrustedBonds bond_store_with(const TrustedBonds& bonds, const NimBLEAddress& identity) {
    if (bonds.count >= kMaxBonds || bond_store_contains(bonds, identity)) {
        return bonds;
    }
    TrustedBonds next = bonds;
    next.identities[next.count] = identity;
    next.roles[next.count] = LinkRole::Watch;
    next.count++;
    return next;
}

TrustedBonds bond_store_without(const TrustedBonds& bonds, size_t index) {
    TrustedBonds next{};
    for (size_t i = 0; i < bonds.count; ++i) {
        if (i != index) {
            next.identities[next.count] = bonds.identities[i];
            next.roles[next.count] = bonds.roles[i];
            next.count++;
        }
    }
    return next;
}

TrustedBonds bond_store_with_role(const TrustedBonds& bonds, size_t index, LinkRole role) {
    TrustedBonds next = bonds;
    if (index < next.count) {
        next.roles[index] = role;
    }
    return next;
}

void bond_store_save_roles(const TrustedBonds& bonds) {
    RoleRecords records{};
    for (size_t i = 0; i < bonds.count; ++i) {
        records.entries[i] = BondRoleRecord{identity_bytes(bonds.identities[i]), static_cast<uint8_t>(bonds.roles[i])};
    }
    Preferences preferences;
    preferences.begin(config::kPreferencesNamespace, false);
    preferences.putBytes(config::kBondRolesKey, records.entries, bonds.count * sizeof(BondRoleRecord));
    preferences.end();
}

void bond_store_forget(const NimBLEAddress& identity) {
    NimBLEDevice::deleteBond(identity);
}

void bond_store_forget_untrusted(const TrustedBonds& bonds) {
    for (int i = static_cast<int>(stored_bond_count()) - 1; i >= 0; --i) {
        NimBLEAddress bonded = NimBLEDevice::getBondedAddress(i);
        if (!bond_store_contains(bonds, bonded)) {
            NimBLEDevice::deleteBond(bonded);
        }
    }
}

void bond_store_forget_all() {
    NimBLEDevice::deleteAllBonds();
    Preferences preferences;
    preferences.begin(config::kPreferencesNamespace, false);
    preferences.remove(config::kBondRolesKey);
    preferences.end();
}

void bond_store_refresh_whitelist(const TrustedBonds& bonds) {
    clear_whitelist();
    for (size_t i = 0; i < bonds.count; ++i) {
        NimBLEDevice::whiteListAdd(bonds.identities[i]);
    }
}
