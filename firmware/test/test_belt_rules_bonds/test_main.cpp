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
    uint8_t flags = session_flags_after_write(0, 1, true);
    TEST_ASSERT_TRUE(any_session_active(flags));
    TEST_ASSERT_TRUE(any_session_active(session_flags_after_write(flags, 0, false)));
    TEST_ASSERT_FALSE(any_session_active(session_flags_after_write(flags, 1, false)));
    TEST_ASSERT_EQUAL_UINT8(flags, session_flags_after_write(flags, kMaxBonds, true));
}

void test_replacing_a_bond_drops_its_session_flag_and_keeps_the_other() {
    uint8_t both = session_flags_after_write(session_flags_after_write(0, 0, true), 1, true);
    uint8_t second_only = session_flags_after_write(0, 1, true);
    TEST_ASSERT_EQUAL_UINT8(1, session_flags_without_bond(both, 0));
    TEST_ASSERT_EQUAL_UINT8(1, session_flags_without_bond(second_only, 0));
    TEST_ASSERT_EQUAL_UINT8(0, session_flags_without_bond(second_only, 1));
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
    RUN_TEST(test_replacing_a_bond_drops_its_session_flag_and_keeps_the_other);
    return UNITY_END();
}
