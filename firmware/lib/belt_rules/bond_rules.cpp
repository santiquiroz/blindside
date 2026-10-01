#include "bond_rules.h"

#include <string.h>

namespace {

constexpr size_t kWatchBondsBeforeOneMayGo = 2;

bool replaceable(const KeptBond& bond, LinkRole role) {
    return !bond.connected && bond.role == role;
}

size_t newest_replaceable(const KeptBond* kept, size_t count, LinkRole role) {
    for (size_t i = count; i > 0; --i) {
        if (replaceable(kept[i - 1], role)) {
            return i - 1;
        }
    }
    return count;
}

size_t bonds_with_role(const KeptBond* kept, size_t count, LinkRole role) {
    size_t matching = 0;
    for (size_t i = 0; i < count; ++i) {
        matching += kept[i].role == role ? 1 : 0;
    }
    return matching;
}

BondPlan replacing(size_t index, size_t count) {
    return index < count ? BondPlan{BondAdmission::Replace, index} : BondPlan{BondAdmission::Reject, 0};
}

bool same_identity(const BondIdentity& a, const BondIdentity& b) {
    return a.type == b.type && memcmp(a.address, b.address, kBondAddressSize) == 0;
}

uint8_t bond_bit(size_t bond_index) {
    return static_cast<uint8_t>(1u << bond_index);
}

bool flag_set(uint8_t flags, size_t bond_index) {
    return (flags & bond_bit(bond_index)) != 0;
}

// A watch keeps its session across its own drops (MVP); a phone's ends with its link, since a force-stopped
// phone never writes 04 00.
bool flag_counts(const KeptBond& bond) {
    return bond.connected || bond.role == LinkRole::Watch;
}

}  // namespace

BondPlan plan_new_bond(const KeptBond* kept, size_t kept_count) {
    if (kept_count < kMaxBonds) {
        return BondPlan{BondAdmission::Add, 0};
    }
    size_t phone = newest_replaceable(kept, kept_count, LinkRole::Phone);
    if (phone < kept_count) {
        return BondPlan{BondAdmission::Replace, phone};
    }
    // The newcomer's role is unknown until it writes 06, so the only watch bond is never traded for it.
    if (bonds_with_role(kept, kept_count, LinkRole::Watch) < kWatchBondsBeforeOneMayGo) {
        return BondPlan{BondAdmission::Reject, 0};
    }
    return replacing(newest_replaceable(kept, kept_count, LinkRole::Watch), kept_count);
}

size_t bonds_kept_at_boot(size_t stored_bonds) {
    return stored_bonds < kMaxBonds ? stored_bonds : kMaxBonds;
}

LinkRole recorded_role(const BondRoleRecord* records, size_t count, const BondIdentity& identity) {
    for (size_t i = 0; i < count; ++i) {
        if (same_identity(records[i].identity, identity)) {
            return role_from_argument(records[i].role);
        }
    }
    return LinkRole::Watch;
}

uint8_t session_flags_after_write(uint8_t flags, size_t bond_index, bool active) {
    if (bond_index >= kMaxBonds) {
        return flags;
    }
    return active ? static_cast<uint8_t>(flags | bond_bit(bond_index))
                  : static_cast<uint8_t>(flags & ~bond_bit(bond_index));
}

uint8_t session_flags_without_bond(uint8_t flags, size_t removed_index) {
    uint8_t below = static_cast<uint8_t>(flags & (bond_bit(removed_index) - 1u));
    uint8_t above = static_cast<uint8_t>((flags >> (removed_index + 1)) << removed_index);
    return static_cast<uint8_t>(below | above);
}

bool session_running(uint8_t session_flags, const KeptBond* kept, size_t kept_count) {
    size_t count = kept_count < kMaxBonds ? kept_count : kMaxBonds;
    for (size_t i = 0; i < count; ++i) {
        if (flag_set(session_flags, i) && flag_counts(kept[i])) {
            return true;
        }
    }
    return false;
}
