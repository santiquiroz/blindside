#include <unity.h>

#include "../test_entry.h"
#include "cut_schedule.h"

void setUp() {}
void tearDown() {}

void test_a_closed_gate_discards_everything() {
    TEST_ASSERT_TRUE(cut_action(false, false) == CutAction::DiscardAll);
    TEST_ASSERT_TRUE(cut_action(false, true) == CutAction::DiscardAll);
}

void test_pending_packets_skip_the_cut_and_keep_the_backlog() {
    TEST_ASSERT_TRUE(cut_action(true, true) == CutAction::KeepBacklog);
}

void test_an_empty_outbox_bundles_the_cut() {
    TEST_ASSERT_TRUE(cut_action(true, false) == CutAction::Bundle);
}

void test_status_is_due_once_per_second() {
    TEST_ASSERT_FALSE(status_due(999, 1000));
    TEST_ASSERT_TRUE(status_due(1000, 1000));
    TEST_ASSERT_EQUAL_UINT32(2100, next_status_after(1100));
}

void test_deadlines_survive_a_millis_wrap() {
    TEST_ASSERT_TRUE(deadline_reached(0x00000010u, 0xFFFFFFF0u));
    TEST_ASSERT_FALSE(deadline_reached(0xFFFFFFE0u, 0xFFFFFFF0u));
    TEST_ASSERT_EQUAL_UINT32(0x000003D8u, next_status_after(0xFFFFFFF0u));
}

void test_retries_stop_before_the_next_cut() {
    TEST_ASSERT_TRUE(retry_allowed(1000, 1000));
    TEST_ASSERT_TRUE(retry_allowed(1000, 1089));
    TEST_ASSERT_FALSE(retry_allowed(1000, 1090));
    TEST_ASSERT_TRUE(retry_allowed(0xFFFFFFF0u, 0x00000040u));
}

void test_link_report_is_due_until_its_generation_is_sent() {
    TEST_ASSERT_FALSE(link_report_due(0, 0));
    TEST_ASSERT_TRUE(link_report_due(1, 0));
    TEST_ASSERT_FALSE(link_report_due(1, 1));
    TEST_ASSERT_TRUE(link_report_due(0, 0xFFFFFFFFu));
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_a_closed_gate_discards_everything);
    RUN_TEST(test_pending_packets_skip_the_cut_and_keep_the_backlog);
    RUN_TEST(test_an_empty_outbox_bundles_the_cut);
    RUN_TEST(test_status_is_due_once_per_second);
    RUN_TEST(test_deadlines_survive_a_millis_wrap);
    RUN_TEST(test_retries_stop_before_the_next_cut);
    RUN_TEST(test_link_report_is_due_until_its_generation_is_sent);
    return UNITY_END();
}
