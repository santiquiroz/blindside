#include <string.h>
#include <unity.h>

#include "../test_entry.h"
#include "diag_format.h"

namespace {

LinkDiag watch_link() {
    return LinkDiag{true, LinkRole::Watch, true, true, 255, LinkParams{36, 0, 400}, 1234, 0};
}

DiagTail tail_with(uint8_t bonds, const PairingWindow& window, uint32_t now_ms) {
    return DiagTail{bonds, window, now_ms, 19, 9, 2100};
}

DiagTailText one_bond_tail(const PairingWindow& window, uint32_t now_ms) {
    return format_diag_tail(tail_with(1, window, now_ms));
}

}  // namespace

void setUp() {}
void tearDown() {}

void test_a_streaming_watch_shows_role_trust_subscription_and_counters() {
    TEST_ASSERT_EQUAL_STRING(" link0[role=watch trusted=1 sub=1 mtu=255 itvl=36 lat=0 sup=400 sent=1234 dropped=0]",
                             format_link_diag(0, watch_link()).text);
}

void test_a_phone_still_pairing_shows_untrusted_and_unsubscribed() {
    LinkDiag phone{true, LinkRole::Phone, false, false, 23, LinkParams{48, 0, 400}, 0, 0};
    TEST_ASSERT_EQUAL_STRING(" link1[role=phone trusted=0 sub=0 mtu=23 itvl=48 lat=0 sup=400 sent=0 dropped=0]",
                             format_link_diag(1, phone).text);
}

void test_a_free_slot_is_a_dash() {
    LinkDiag idle = watch_link();
    idle.connected = false;
    TEST_ASSERT_EQUAL_STRING(" link1[-]", format_link_diag(1, idle).text);
}

void test_the_longest_link_entry_fits_its_buffer() {
    LinkDiag longest{true, LinkRole::Phone, true, true, 517, LinkParams{3200, 499, 3200}, 4294967295u, 4294967295u};
    LinkDiagText line = format_link_diag(1, longest);
    TEST_ASSERT_NOT_NULL(strstr(line.text, "sent=4294967295 dropped=4294967295]"));
    TEST_ASSERT_TRUE(strlen(line.text) < kLinkDiagTextSize);
}

void test_the_tail_shows_bonds_window_disconnect_buffers_and_host_stack() {
    TEST_ASSERT_EQUAL_STRING(" bonds=1 pair=0 disc=19 acl=9 hstk=2100", one_bond_tail(window_closed(), 5000).text);
}

void test_an_open_window_counts_down_its_seconds() {
    PairingWindow window = window_opened(1000);
    TEST_ASSERT_EQUAL_STRING(" bonds=1 pair=60 disc=19 acl=9 hstk=2100", one_bond_tail(window, 1000).text);
    TEST_ASSERT_EQUAL_STRING(" bonds=1 pair=30 disc=19 acl=9 hstk=2100", one_bond_tail(window, 31500).text);
    TEST_ASSERT_EQUAL_STRING(" bonds=1 pair=1 disc=19 acl=9 hstk=2100", one_bond_tail(window, 60999).text);
    TEST_ASSERT_EQUAL_STRING(" bonds=1 pair=0 disc=19 acl=9 hstk=2100", one_bond_tail(window, 61000).text);
}

void test_without_bonds_the_window_is_open_with_no_end() {
    TEST_ASSERT_EQUAL_STRING(" bonds=0 pair=open disc=19 acl=9 hstk=2100",
                             format_diag_tail(tail_with(0, window_opened(0), 900000)).text);
}

void test_the_longest_tail_fits_its_buffer() {
    DiagTail longest{255, window_opened(0), 1, -2147483647 - 1, 65535, 4294967295u};
    TEST_ASSERT_TRUE(strlen(format_diag_tail(longest).text) < kDiagTailTextSize - 1);
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_a_streaming_watch_shows_role_trust_subscription_and_counters);
    RUN_TEST(test_a_phone_still_pairing_shows_untrusted_and_unsubscribed);
    RUN_TEST(test_a_free_slot_is_a_dash);
    RUN_TEST(test_the_longest_link_entry_fits_its_buffer);
    RUN_TEST(test_the_tail_shows_bonds_window_disconnect_buffers_and_host_stack);
    RUN_TEST(test_an_open_window_counts_down_its_seconds);
    RUN_TEST(test_without_bonds_the_window_is_open_with_no_end);
    RUN_TEST(test_the_longest_tail_fits_its_buffer);
    return UNITY_END();
}
