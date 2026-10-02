#include <unity.h>

#include "../test_entry.h"
#include "bond_rules.h"

namespace {

constexpr KeptBond kWatchHere{LinkRole::Watch, true};
constexpr KeptBond kWatchAway{LinkRole::Watch, false};
constexpr KeptBond kPhoneHere{LinkRole::Phone, true};
constexpr KeptBond kPhoneAway{LinkRole::Phone, false};

struct KeptPair {
    KeptBond bonds[kMaxBonds];
};

BondPlan plan_for(const KeptPair& pair) {
    return plan_new_bond(pair.bonds, kMaxBonds);
}

void assert_plan(const BondPlan& plan, BondAdmission admission, size_t replaced) {
    TEST_ASSERT_TRUE(plan.admission == admission);
    TEST_ASSERT_EQUAL_UINT(replaced, plan.replaced);
}

BondIdentity identity(uint8_t last_byte, uint8_t type) {
    return BondIdentity{{0x24, 0x6F, 0x28, 0xAB, 0x0C, last_byte}, type};
}

const BondIdentity kWatch = identity(0x1E, 0);
const BondIdentity kPhone = identity(0x2F, 1);
const BondIdentity kStranger = identity(0x3A, 1);
const BondIdentity kNewcomer = identity(0x4B, 1);
const IdentityList kNobody{nullptr, 0};

}  // namespace

void setUp() {}
void tearDown() {}

void test_a_new_bond_is_added_while_there_is_room() {
    const KeptBond one[kMaxBonds] = {kWatchHere, kWatchAway};
    assert_plan(plan_new_bond(one, 0), BondAdmission::Add, 0);
    assert_plan(plan_new_bond(one, 1), BondAdmission::Add, 0);
}

void test_a_new_device_replaces_the_phone_bond_that_is_not_connected() {
    assert_plan(plan_for(KeptPair{{kWatchHere, kPhoneAway}}), BondAdmission::Replace, 1);
    assert_plan(plan_for(KeptPair{{kPhoneAway, kWatchHere}}), BondAdmission::Replace, 0);
}

void test_with_nobody_connected_the_phone_bond_goes_and_the_watch_bond_stays() {
    assert_plan(plan_for(KeptPair{{kWatchAway, kPhoneAway}}), BondAdmission::Replace, 1);
    assert_plan(plan_for(KeptPair{{kPhoneAway, kWatchAway}}), BondAdmission::Replace, 0);
}

void test_the_only_watch_bond_is_kept_while_the_phone_is_connected() {
    assert_plan(plan_for(KeptPair{{kWatchAway, kPhoneHere}}), BondAdmission::Reject, 0);
    assert_plan(plan_for(KeptPair{{kPhoneHere, kWatchAway}}), BondAdmission::Reject, 0);
}

void test_with_two_watch_bonds_the_newest_idle_one_is_replaced() {
    assert_plan(plan_for(KeptPair{{kWatchAway, kWatchAway}}), BondAdmission::Replace, 1);
    assert_plan(plan_for(KeptPair{{kWatchAway, kWatchHere}}), BondAdmission::Replace, 0);
}

void test_with_two_phone_bonds_the_newest_idle_one_is_replaced() {
    assert_plan(plan_for(KeptPair{{kPhoneAway, kPhoneAway}}), BondAdmission::Replace, 1);
    assert_plan(plan_for(KeptPair{{kPhoneAway, kPhoneHere}}), BondAdmission::Replace, 0);
}

void test_a_new_bond_is_refused_while_both_bonded_devices_are_connected() {
    assert_plan(plan_for(KeptPair{{kWatchHere, kPhoneHere}}), BondAdmission::Reject, 0);
    assert_plan(plan_for(KeptPair{{kWatchHere, kWatchHere}}), BondAdmission::Reject, 0);
    assert_plan(plan_for(KeptPair{{kPhoneHere, kPhoneHere}}), BondAdmission::Reject, 0);
}

void test_boot_keeps_at_most_the_two_oldest_bonds() {
    TEST_ASSERT_EQUAL_UINT(0, bonds_kept_at_boot(0));
    TEST_ASSERT_EQUAL_UINT(1, bonds_kept_at_boot(1));
    TEST_ASSERT_EQUAL_UINT(2, bonds_kept_at_boot(2));
    TEST_ASSERT_EQUAL_UINT(2, bonds_kept_at_boot(3));
}

void test_roles_are_recalled_by_identity_and_default_to_the_watch() {
    const BondRoleRecord records[kMaxBonds] = {{identity(0x1E, 0), 0}, {identity(0x2F, 1), 1}};
    TEST_ASSERT_TRUE(recorded_role(records, kMaxBonds, identity(0x2F, 1)) == LinkRole::Phone);
    TEST_ASSERT_TRUE(recorded_role(records, kMaxBonds, identity(0x1E, 0)) == LinkRole::Watch);
    TEST_ASSERT_TRUE(recorded_role(records, kMaxBonds, identity(0x2F, 0)) == LinkRole::Watch);
    TEST_ASSERT_TRUE(recorded_role(records, 0, identity(0x2F, 1)) == LinkRole::Watch);
}

void test_a_session_flag_belongs_to_one_bond() {
    const KeptBond both_here[kMaxBonds] = {kWatchHere, kPhoneHere};
    uint8_t flags = session_flags_after_write(0, 1, true);
    TEST_ASSERT_TRUE(session_running(flags, both_here, kMaxBonds));
    TEST_ASSERT_TRUE(session_running(session_flags_after_write(flags, 0, false), both_here, kMaxBonds));
    TEST_ASSERT_FALSE(session_running(session_flags_after_write(flags, 1, false), both_here, kMaxBonds));
    TEST_ASSERT_EQUAL_UINT8(flags, session_flags_after_write(flags, kMaxBonds, true));
}

void test_a_connected_bond_with_its_flag_runs_a_session_whatever_its_role() {
    const KeptBond both_here[kMaxBonds] = {kWatchHere, kPhoneHere};
    TEST_ASSERT_TRUE(session_running(session_flags_after_write(0, 0, true), both_here, kMaxBonds));
    TEST_ASSERT_TRUE(session_running(session_flags_after_write(0, 1, true), both_here, kMaxBonds));
    TEST_ASSERT_FALSE(session_running(0, both_here, kMaxBonds));
}

void test_a_phone_session_ends_with_its_link() {
    const KeptBond phone_gone[kMaxBonds] = {kWatchHere, kPhoneAway};
    const KeptBond phone_alone_gone[kMaxBonds] = {kPhoneAway, kWatchAway};
    TEST_ASSERT_FALSE(session_running(session_flags_after_write(0, 1, true), phone_gone, kMaxBonds));
    TEST_ASSERT_FALSE(session_running(session_flags_after_write(0, 0, true), phone_alone_gone, kMaxBonds));
}

void test_a_watch_session_survives_the_watch_dropping() {
    const KeptBond watch_gone[kMaxBonds] = {kWatchAway, kPhoneHere};
    const KeptBond nobody_here[kMaxBonds] = {kWatchAway, kPhoneAway};
    TEST_ASSERT_TRUE(session_running(session_flags_after_write(0, 0, true), watch_gone, kMaxBonds));
    TEST_ASSERT_TRUE(session_running(session_flags_after_write(0, 0, true), nobody_here, kMaxBonds));
}

void test_a_flag_without_a_kept_bond_never_runs_a_session() {
    const KeptBond watch_only[kMaxBonds] = {kWatchHere, kWatchHere};
    TEST_ASSERT_FALSE(session_running(session_flags_after_write(0, 1, true), watch_only, 1));
    TEST_ASSERT_FALSE(session_running(session_flags_after_write(0, 0, true), watch_only, 0));
}

void test_replacing_a_bond_drops_its_session_flag_and_keeps_the_other() {
    uint8_t both = session_flags_after_write(session_flags_after_write(0, 0, true), 1, true);
    uint8_t second_only = session_flags_after_write(0, 1, true);
    TEST_ASSERT_EQUAL_UINT8(1, session_flags_without_bond(both, 0));
    TEST_ASSERT_EQUAL_UINT8(1, session_flags_without_bond(second_only, 0));
    TEST_ASSERT_EQUAL_UINT8(0, session_flags_without_bond(second_only, 1));
}

void test_a_bond_neither_kept_nor_connected_is_stale() {
    const BondIdentity trusted[] = {kWatch, kPhone};
    TEST_ASSERT_TRUE(bond_is_stale(kStranger, IdentityList{trusted, 2}, kNobody));
    TEST_ASSERT_TRUE(bond_is_stale(identity(0x1E, 1), IdentityList{trusted, 2}, kNobody));
}

void test_a_kept_bond_is_never_stale_even_when_its_device_is_away() {
    const BondIdentity trusted[] = {kWatch, kPhone};
    TEST_ASSERT_FALSE(bond_is_stale(kWatch, IdentityList{trusted, 2}, kNobody));
    TEST_ASSERT_FALSE(bond_is_stale(kPhone, IdentityList{trusted, 2}, kNobody));
}

void test_a_connected_newcomer_bond_is_not_stale_before_the_belt_decides() {
    const BondIdentity trusted[] = {kWatch, kPhone};
    const BondIdentity connected[] = {kWatch, kNewcomer};
    TEST_ASSERT_FALSE(bond_is_stale(kNewcomer, IdentityList{trusted, 2}, IdentityList{connected, 2}));
    TEST_ASSERT_TRUE(bond_is_stale(kStranger, IdentityList{trusted, 2}, IdentityList{connected, 2}));
}

void test_an_overflow_evicts_the_oldest_untrusted_bond_and_never_the_watch() {
    const BondIdentity stored[] = {kWatch, kStranger, kPhone, kNewcomer};
    const BondIdentity trusted[] = {kWatch, kPhone};
    TEST_ASSERT_EQUAL_UINT(1, bond_to_evict(IdentityList{stored, 4}, IdentityList{trusted, 2}, identity(0x5C, 0)));
}

void test_an_overflow_never_evicts_the_bond_being_written() {
    const BondIdentity stored[] = {kWatch, kNewcomer, kPhone, kStranger};
    const BondIdentity trusted[] = {kWatch, kPhone};
    TEST_ASSERT_EQUAL_UINT(3, bond_to_evict(IdentityList{stored, 4}, IdentityList{trusted, 2}, kNewcomer));
}

void test_an_overflow_with_only_kept_bonds_is_refused() {
    const BondIdentity stored[] = {kWatch, kPhone, kNewcomer};
    const BondIdentity trusted[] = {kWatch, kPhone};
    TEST_ASSERT_EQUAL_UINT(3, bond_to_evict(IdentityList{stored, 3}, IdentityList{trusted, 2}, kNewcomer));
    TEST_ASSERT_EQUAL_UINT(2, bond_to_evict(IdentityList{stored, 2}, IdentityList{trusted, 2}, kNewcomer));
    TEST_ASSERT_EQUAL_UINT(0, bond_to_evict(kNobody, IdentityList{trusted, 2}, kNewcomer));
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_a_new_bond_is_added_while_there_is_room);
    RUN_TEST(test_a_new_device_replaces_the_phone_bond_that_is_not_connected);
    RUN_TEST(test_with_nobody_connected_the_phone_bond_goes_and_the_watch_bond_stays);
    RUN_TEST(test_the_only_watch_bond_is_kept_while_the_phone_is_connected);
    RUN_TEST(test_with_two_watch_bonds_the_newest_idle_one_is_replaced);
    RUN_TEST(test_with_two_phone_bonds_the_newest_idle_one_is_replaced);
    RUN_TEST(test_a_new_bond_is_refused_while_both_bonded_devices_are_connected);
    RUN_TEST(test_boot_keeps_at_most_the_two_oldest_bonds);
    RUN_TEST(test_roles_are_recalled_by_identity_and_default_to_the_watch);
    RUN_TEST(test_a_session_flag_belongs_to_one_bond);
    RUN_TEST(test_a_connected_bond_with_its_flag_runs_a_session_whatever_its_role);
    RUN_TEST(test_a_phone_session_ends_with_its_link);
    RUN_TEST(test_a_watch_session_survives_the_watch_dropping);
    RUN_TEST(test_a_flag_without_a_kept_bond_never_runs_a_session);
    RUN_TEST(test_replacing_a_bond_drops_its_session_flag_and_keeps_the_other);
    RUN_TEST(test_a_bond_neither_kept_nor_connected_is_stale);
    RUN_TEST(test_a_kept_bond_is_never_stale_even_when_its_device_is_away);
    RUN_TEST(test_a_connected_newcomer_bond_is_not_stale_before_the_belt_decides);
    RUN_TEST(test_an_overflow_evicts_the_oldest_untrusted_bond_and_never_the_watch);
    RUN_TEST(test_an_overflow_never_evicts_the_bond_being_written);
    RUN_TEST(test_an_overflow_with_only_kept_bonds_is_refused);
    return UNITY_END();
}
