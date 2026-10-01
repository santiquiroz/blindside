#include <unity.h>

#include "../test_entry.h"
#include "info_reads.h"
#include "link_roles.h"

namespace {

constexpr uint16_t kStreamMtu = 255;
constexpr uint16_t kDefaultMtu = 23;

uint32_t units_to_ms(uint16_t units) {
    return units * 125u / 100u;
}

InfoReader reader(size_t slot, uint16_t mtu) {
    return InfoReader{slot, mtu};
}

InfoReadMarks read_by(size_t slot, uint16_t mtu, uint32_t at_ms) {
    return info_copy_rebuilt(info_read_marked(InfoReadMarks{}, slot, at_ms), mtu);
}

AdvertisingNeed one_link_up(size_t trusted_links, size_t bonds, bool window_open) {
    return AdvertisingNeed{1, kMaxLinks, trusted_links, bonds, window_open, true};
}

}  // namespace

void setUp() {}
void tearDown() {}

void test_role_argument_zero_is_the_watch_and_one_is_the_phone() {
    TEST_ASSERT_TRUE(role_from_argument(0) == LinkRole::Watch);
    TEST_ASSERT_TRUE(role_from_argument(1) == LinkRole::Phone);
}

void test_role_names_match_the_info_contract() {
    TEST_ASSERT_EQUAL_STRING("watch", role_name(LinkRole::Watch));
    TEST_ASSERT_EQUAL_STRING("phone", role_name(LinkRole::Phone));
}

void test_the_watch_asks_for_30_to_50_ms() {
    ConnParamsRequest watch = conn_params_for_role(LinkRole::Watch);
    TEST_ASSERT_EQUAL_UINT32(30, units_to_ms(watch.min_units));
    TEST_ASSERT_EQUAL_UINT32(50, units_to_ms(watch.max_units));
    TEST_ASSERT_EQUAL_UINT16(0, watch.latency);
    TEST_ASSERT_EQUAL_UINT16(400, watch.timeout_units);
}

void test_the_phone_asks_for_60_to_100_ms() {
    ConnParamsRequest phone = conn_params_for_role(LinkRole::Phone);
    TEST_ASSERT_EQUAL_UINT32(60, units_to_ms(phone.min_units));
    TEST_ASSERT_EQUAL_UINT32(100, units_to_ms(phone.max_units));
    TEST_ASSERT_EQUAL_UINT16(0, phone.latency);
    TEST_ASSERT_EQUAL_UINT16(400, phone.timeout_units);
}

void test_only_the_phone_is_asked_again_and_only_once() {
    LinkParams android_low_power{96, 2, 500};
    LinkParams phone_with_latency{60, 2, 400};
    LinkParams phone_as_asked{60, 0, 400};
    TEST_ASSERT_TRUE(conn_params_retry_wanted(LinkRole::Phone, android_low_power, false));
    TEST_ASSERT_TRUE(conn_params_retry_wanted(LinkRole::Phone, phone_with_latency, false));
    TEST_ASSERT_FALSE(conn_params_retry_wanted(LinkRole::Phone, android_low_power, true));
    TEST_ASSERT_FALSE(conn_params_retry_wanted(LinkRole::Phone, phone_as_asked, false));
    TEST_ASSERT_FALSE(conn_params_retry_wanted(LinkRole::Watch, android_low_power, false));
}

void test_slots_are_found_by_connection_handle() {
    const uint16_t handles[kMaxLinks] = {kNoConnHandle, 7};
    TEST_ASSERT_EQUAL_INT(1, slot_for_handle(handles, kMaxLinks, 7));
    TEST_ASSERT_EQUAL_INT(0, slot_for_handle(handles, kMaxLinks, kNoConnHandle));
    TEST_ASSERT_EQUAL_INT(-1, slot_for_handle(handles, kMaxLinks, 9));
}

void test_a_full_belt_has_no_free_slot() {
    const uint16_t handles[kMaxLinks] = {3, 7};
    TEST_ASSERT_EQUAL_INT(-1, slot_for_handle(handles, kMaxLinks, kNoConnHandle));
}

void test_the_belt_advertises_while_a_slot_is_free() {
    TEST_ASSERT_TRUE(advertising_wanted(AdvertisingNeed{0, 2, 0, 1, false, false}));
    TEST_ASSERT_TRUE(advertising_wanted(AdvertisingNeed{1, 2, 1, 1, false, false}));
    TEST_ASSERT_FALSE(advertising_wanted(AdvertisingNeed{2, 2, 2, 2, true, false}));
    TEST_ASSERT_FALSE(advertising_wanted(AdvertisingNeed{1, 1, 0, 1, true, true}));
}

void test_with_one_link_up_it_advertises_only_for_an_absent_bond_or_an_open_window() {
    TEST_ASSERT_TRUE(advertising_wanted(AdvertisingNeed{0, 2, 0, 1, false, true}));
    TEST_ASSERT_FALSE(advertising_wanted(one_link_up(1, 1, false)));
    TEST_ASSERT_TRUE(advertising_wanted(one_link_up(1, 2, false)));
    TEST_ASSERT_TRUE(advertising_wanted(one_link_up(1, 1, true)));
    TEST_ASSERT_TRUE(advertising_wanted(one_link_up(0, 1, false)));
}

void test_info_is_regenerated_when_nobody_else_is_reading() {
    TEST_ASSERT_TRUE(info_refresh_allowed(InfoReadMarks{}, reader(0, kStreamMtu), 5000));
    TEST_ASSERT_TRUE(info_refresh_allowed(read_by(0, kStreamMtu, 1000), reader(0, kStreamMtu), 1001));
}

void test_info_is_shared_for_two_seconds_after_another_link_reads_it() {
    InfoReadMarks marks = read_by(1, kStreamMtu, 1000);
    TEST_ASSERT_FALSE(info_refresh_allowed(marks, reader(0, kStreamMtu), 1000));
    TEST_ASSERT_FALSE(info_refresh_allowed(marks, reader(0, kStreamMtu), 2999));
    TEST_ASSERT_TRUE(info_refresh_allowed(marks, reader(0, kStreamMtu), 3000));
}

void test_info_share_window_survives_a_millis_wrap() {
    InfoReadMarks marks = read_by(1, kStreamMtu, 0xFFFFFF00u);
    TEST_ASSERT_FALSE(info_refresh_allowed(marks, reader(0, kStreamMtu), 0x00000100u));
}

void test_a_copy_built_below_mtu_247_is_rebuilt_for_a_reader_that_can_stream() {
    InfoReadMarks phone_at_23 = read_by(1, kDefaultMtu, 1000);
    TEST_ASSERT_TRUE(info_refresh_allowed(phone_at_23, reader(0, kStreamMtu), 1500));
    TEST_ASSERT_FALSE(info_refresh_allowed(phone_at_23, reader(0, kDefaultMtu), 1500));
    InfoReadMarks watch_at_255 = read_by(0, kStreamMtu, 1000);
    TEST_ASSERT_FALSE(info_refresh_allowed(watch_at_255, reader(1, kDefaultMtu), 1500));
}

void test_marking_a_read_records_only_that_slot() {
    InfoReadMarks marks = info_read_marked(read_by(0, kStreamMtu, 10), 1, 20);
    TEST_ASSERT_TRUE(marks.marked[0]);
    TEST_ASSERT_EQUAL_UINT32(10, marks.at_ms[0]);
    TEST_ASSERT_TRUE(marks.marked[1]);
    TEST_ASSERT_EQUAL_UINT32(20, marks.at_ms[1]);
    TEST_ASSERT_EQUAL_UINT16(kStreamMtu, marks.copy_mtu);
    InfoReadMarks unchanged = info_read_marked(InfoReadMarks{}, kMaxLinks, 30);
    TEST_ASSERT_FALSE(unchanged.marked[0]);
    TEST_ASSERT_FALSE(unchanged.marked[1]);
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_role_argument_zero_is_the_watch_and_one_is_the_phone);
    RUN_TEST(test_role_names_match_the_info_contract);
    RUN_TEST(test_the_watch_asks_for_30_to_50_ms);
    RUN_TEST(test_the_phone_asks_for_60_to_100_ms);
    RUN_TEST(test_only_the_phone_is_asked_again_and_only_once);
    RUN_TEST(test_slots_are_found_by_connection_handle);
    RUN_TEST(test_a_full_belt_has_no_free_slot);
    RUN_TEST(test_the_belt_advertises_while_a_slot_is_free);
    RUN_TEST(test_with_one_link_up_it_advertises_only_for_an_absent_bond_or_an_open_window);
    RUN_TEST(test_info_is_regenerated_when_nobody_else_is_reading);
    RUN_TEST(test_info_is_shared_for_two_seconds_after_another_link_reads_it);
    RUN_TEST(test_info_share_window_survives_a_millis_wrap);
    RUN_TEST(test_a_copy_built_below_mtu_247_is_rebuilt_for_a_reader_that_can_stream);
    RUN_TEST(test_marking_a_read_records_only_that_slot);
    return UNITY_END();
}
