#pragma once

#include <stddef.h>
#include <stdint.h>

#include "link_roles.h"

constexpr size_t kMaxBonds = 2;
constexpr size_t kBondAddressSize = 6;

enum class BondAdmission : uint8_t { Add, Replace, Reject };

struct BondPlan {
    BondAdmission admission;
    size_t replaced;
};

struct KeptBond {
    LinkRole role;
    bool connected;
};

struct BondIdentity {
    uint8_t address[kBondAddressSize];
    uint8_t type;
};

struct BondRoleRecord {
    BondIdentity identity;
    uint8_t role;
};

struct IdentityList {
    const BondIdentity* items;
    size_t count;
};

BondPlan plan_new_bond(const KeptBond* kept, size_t kept_count);
size_t bonds_kept_at_boot(size_t stored_bonds);
LinkRole recorded_role(const BondRoleRecord* records, size_t count, const BondIdentity& identity);
uint8_t session_flags_after_write(uint8_t flags, size_t bond_index, bool active);
uint8_t session_flags_without_bond(uint8_t flags, size_t removed_index);
bool session_running(uint8_t session_flags, const KeptBond* kept, size_t kept_count);
bool bond_is_stale(const BondIdentity& stored, IdentityList trusted, IdentityList connected);
size_t bond_to_evict(IdentityList stored, IdentityList trusted, const BondIdentity& writer);
