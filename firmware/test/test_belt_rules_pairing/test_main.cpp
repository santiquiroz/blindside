#include <unity.h>

#include "../test_entry.h"
#include "led_pattern.h"
#include "pairing_rules.h"
#include "serial_command.h"

namespace {

LedInputs plain(uint32_t now_ms) {
    return LedInputs{now_ms, false, false, 0, false};
}

LedInputs pairing(uint32_t now_ms) {
    return LedInputs{now_ms, true, false, 0, false};
}

LedInputs identify(uint32_t now_ms, uint32_t started_ms, bool pairing_open) {
    return LedInputs{now_ms, pairing_open, true, started_ms, false};
}

}  // namespace

void setUp() {}
void tearDown() {}

void test_releasing_boot_after_three_to_ten_seconds_opens_the_window() {
    TEST_ASSERT_TRUE(gesture_on_release(2999, 5000) == ButtonGesture::None);
    TEST_ASSERT_TRUE(gesture_on_release(3000, 5000) == ButtonGesture::OpenPairingWindow);
    TEST_ASSERT_TRUE(gesture_on_release(9999, 15000) == ButtonGesture::OpenPairingWindow);
}

void test_releasing_boot_after_ten_seconds_resets_pairing() {
    TEST_ASSERT_TRUE(gesture_on_release(10000, 15000) == ButtonGesture::ResetPairing);
    TEST_ASSERT_TRUE(gesture_on_release(25000, 40000) == ButtonGesture::ResetPairing);
}

void test_gestures_only_count_in_the_first_minute() {
    TEST_ASSERT_TRUE(gesture_on_release(3000, 60000) == ButtonGesture::OpenPairingWindow);
    TEST_ASSERT_TRUE(gesture_on_release(3000, 60001) == ButtonGesture::None);
    TEST_ASSERT_TRUE(gesture_on_release(12000, 65000) == ButtonGesture::None);
    TEST_ASSERT_TRUE(gestures_still_counted(60000));
    TEST_ASSERT_FALSE(gestures_still_counted(60001));
}

void test_window_lasts_sixty_seconds() {
    PairingWindow window = window_opened(1000);
    TEST_ASSERT_TRUE(window_after_tick(window, 60999, true).open);
    TEST_ASSERT_FALSE(window_after_tick(window, 61000, true).open);
    TEST_ASSERT_FALSE(window_after_tick(window_closed(), 5, true).open);
}

void test_window_stays_open_while_no_watch_is_bonded() {
    PairingWindow window = window_opened(1000);
    TEST_ASSERT_TRUE(window_after_tick(window, 61000, false).open);
    TEST_ASSERT_TRUE(window_after_tick(window, 3600000, false).open);
    TEST_ASSERT_TRUE(window_after_tick(window_closed(), 5, false).open);
}

void test_window_opens_at_boot_only_without_a_bond() {
    TEST_ASSERT_TRUE(initial_window(false, 5).open);
    TEST_ASSERT_EQUAL_UINT32(5, initial_window(false, 5).opened_ms);
    TEST_ASSERT_FALSE(initial_window(true, 5).open);
}

void test_authentication_decisions() {
    TEST_ASSERT_TRUE(decide_authentication(false, true, true) == AuthDecision::Reject);
    TEST_ASSERT_TRUE(decide_authentication(true, true, false) == AuthDecision::AcceptTrusted);
    TEST_ASSERT_TRUE(decide_authentication(true, false, true) == AuthDecision::AcceptNewBond);
    TEST_ASSERT_TRUE(decide_authentication(true, false, false) == AuthDecision::Reject);
}

void test_secure_link_needs_mitm_only_when_required() {
    TEST_ASSERT_TRUE(link_is_secure(true, true, true, true));
    TEST_ASSERT_FALSE(link_is_secure(true, true, false, true));
    TEST_ASSERT_TRUE(link_is_secure(true, true, false, false));
    TEST_ASSERT_FALSE(link_is_secure(true, false, true, false));
    TEST_ASSERT_FALSE(link_is_secure(false, true, true, false));
}

void test_unknown_peers_are_dropped_at_once_outside_the_window() {
    TEST_ASSERT_TRUE(should_drop_at_connect(false, false));
    TEST_ASSERT_FALSE(should_drop_at_connect(false, true));
    TEST_ASSERT_FALSE(should_drop_at_connect(true, false));
    TEST_ASSERT_FALSE(should_drop_at_connect(true, true));
}

void test_unauthenticated_peers_get_five_seconds_or_the_pairing_window() {
    TEST_ASSERT_FALSE(should_drop_unauthenticated(1000, 5999, false));
    TEST_ASSERT_TRUE(should_drop_unauthenticated(1000, 6000, false));
    TEST_ASSERT_FALSE(should_drop_unauthenticated(1000, 60999, true));
    TEST_ASSERT_TRUE(should_drop_unauthenticated(1000, 61000, true));
}

void test_passkey_has_six_digits() {
    TEST_ASSERT_EQUAL_UINT32(0, passkey_from_random(0));
    TEST_ASSERT_EQUAL_UINT32(999999, passkey_from_random(999999));
    TEST_ASSERT_EQUAL_UINT32(0, passkey_from_random(1000000));
    TEST_ASSERT_EQUAL_UINT32(967295, passkey_from_random(0xFFFFFFFFu));
}

void test_led_is_on_for_the_first_two_seconds_after_boot() {
    TEST_ASSERT_TRUE(led_on(plain(0)));
    TEST_ASSERT_TRUE(led_on(plain(1999)));
    TEST_ASSERT_FALSE(led_on(plain(2000)));
}

void test_led_stays_off_in_game() {
    for (uint32_t t = 2000; t < 600000; t += 37) {
        TEST_ASSERT_FALSE(led_on(plain(t)));
    }
}

void test_pairing_window_blinks_every_250_ms() {
    TEST_ASSERT_TRUE(led_on(pairing(2000)));
    TEST_ASSERT_FALSE(led_on(pairing(2250)));
    TEST_ASSERT_TRUE(led_on(pairing(2500)));
}

void test_pairing_window_stays_dark_while_a_session_runs() {
    for (uint32_t t = 2000; t < 62000; t += 37) {
        TEST_ASSERT_FALSE(led_on(LedInputs{t, true, false, 0, true}));
    }
}

void test_identify_blinks_three_times_then_stops() {
    const uint32_t start = 10000;
    TEST_ASSERT_TRUE(led_on(identify(start, start, false)));
    TEST_ASSERT_FALSE(led_on(identify(start + 200, start, false)));
    TEST_ASSERT_TRUE(led_on(identify(start + 400, start, false)));
    TEST_ASSERT_TRUE(led_on(identify(start + 999, start, false)));
    TEST_ASSERT_FALSE(led_on(identify(start + 1000, start, false)));
    TEST_ASSERT_FALSE(led_on(identify(start + 1200, start, false)));
    TEST_ASSERT_FALSE(identify_finished(start, start + 1199));
    TEST_ASSERT_TRUE(identify_finished(start, start + 1200));
}

void test_identify_pattern_wins_over_the_pairing_blink() {
    TEST_ASSERT_FALSE(led_on(identify(10200, 10000, true)));
}

void test_serial_key_commands_are_exact_after_trimming() {
    TEST_ASSERT_TRUE(parse_serial_line("key", 3) == SerialCommand::ShowKey);
    TEST_ASSERT_TRUE(parse_serial_line(" key \r\n", 7) == SerialCommand::ShowKey);
    TEST_ASSERT_TRUE(parse_serial_line("key new", 7) == SerialCommand::NewKey);
    TEST_ASSERT_TRUE(parse_serial_line("key new\r", 8) == SerialCommand::NewKey);
}

void test_other_serial_lines_are_ignored() {
    TEST_ASSERT_TRUE(parse_serial_line("key  new\r", 9) == SerialCommand::None);
    TEST_ASSERT_TRUE(parse_serial_line("keys", 4) == SerialCommand::None);
    TEST_ASSERT_TRUE(parse_serial_line("KEY", 3) == SerialCommand::None);
    TEST_ASSERT_TRUE(parse_serial_line("", 0) == SerialCommand::None);
    TEST_ASSERT_TRUE(parse_serial_line(nullptr, 0) == SerialCommand::None);
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_releasing_boot_after_three_to_ten_seconds_opens_the_window);
    RUN_TEST(test_releasing_boot_after_ten_seconds_resets_pairing);
    RUN_TEST(test_gestures_only_count_in_the_first_minute);
    RUN_TEST(test_window_lasts_sixty_seconds);
    RUN_TEST(test_window_stays_open_while_no_watch_is_bonded);
    RUN_TEST(test_window_opens_at_boot_only_without_a_bond);
    RUN_TEST(test_authentication_decisions);
    RUN_TEST(test_secure_link_needs_mitm_only_when_required);
    RUN_TEST(test_unknown_peers_are_dropped_at_once_outside_the_window);
    RUN_TEST(test_unauthenticated_peers_get_five_seconds_or_the_pairing_window);
    RUN_TEST(test_passkey_has_six_digits);
    RUN_TEST(test_led_is_on_for_the_first_two_seconds_after_boot);
    RUN_TEST(test_led_stays_off_in_game);
    RUN_TEST(test_pairing_window_blinks_every_250_ms);
    RUN_TEST(test_pairing_window_stays_dark_while_a_session_runs);
    RUN_TEST(test_identify_blinks_three_times_then_stops);
    RUN_TEST(test_identify_pattern_wins_over_the_pairing_blink);
    RUN_TEST(test_serial_key_commands_are_exact_after_trimming);
    RUN_TEST(test_other_serial_lines_are_ignored);
    return UNITY_END();
}
