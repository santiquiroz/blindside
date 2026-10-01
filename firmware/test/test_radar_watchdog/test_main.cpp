#include <unity.h>

#include "../test_entry.h"
#include "radar_watchdog.h"

namespace {

RadarWatchdog checked(const RadarWatchdog& watchdog, uint32_t now_ms, WatchdogAction expected) {
    WatchdogStep step = watchdog_check(watchdog, now_ms);
    TEST_ASSERT_TRUE(step.action == expected);
    return step.watchdog;
}

}  // namespace

void setUp() {}
void tearDown() {}

void test_boot_config_counts_as_the_first_restart() {
    RadarWatchdog watchdog = watchdog_after_boot_config(0);
    watchdog = checked(watchdog, 2000, WatchdogAction::None);
    watchdog = checked(watchdog, 29999, WatchdogAction::None);
    watchdog = checked(watchdog, 30000, WatchdogAction::Restart);
    watchdog = checked(watchdog, 30001, WatchdogAction::None);
    watchdog = checked(watchdog, 60000, WatchdogAction::Restart);
    TEST_ASSERT_EQUAL_UINT8(2, watchdog.restarts);
}

void test_flowing_frames_never_restart() {
    RadarWatchdog watchdog = watchdog_after_boot_config(0);
    for (uint32_t t = 100; t < 100000; t += 100) {
        watchdog = watchdog_saw_frame(watchdog, t);
        watchdog = checked(watchdog, t + 50, WatchdogAction::None);
    }
    TEST_ASSERT_EQUAL_UINT8(0, watchdog.restarts);
}

void test_silence_mid_session_restarts_after_two_seconds_then_every_thirty() {
    RadarWatchdog watchdog = watchdog_saw_frame(watchdog_after_boot_config(0), 1000);
    watchdog = checked(watchdog, 2999, WatchdogAction::None);
    watchdog = checked(watchdog, 3000, WatchdogAction::Restart);
    watchdog = checked(watchdog, 3100, WatchdogAction::None);
    watchdog = checked(watchdog, 32999, WatchdogAction::None);
    watchdog = checked(watchdog, 33000, WatchdogAction::Restart);
    TEST_ASSERT_EQUAL_UINT8(2, watchdog.restarts);
}

void test_frames_after_a_restart_rearm_the_fast_restart() {
    RadarWatchdog watchdog = watchdog_saw_frame(watchdog_after_boot_config(0), 1000);
    watchdog = checked(watchdog, 3000, WatchdogAction::Restart);
    watchdog = watchdog_saw_frame(watchdog, 4000);
    watchdog = checked(watchdog, 5999, WatchdogAction::None);
    watchdog = checked(watchdog, 6000, WatchdogAction::Restart);
}

void test_manual_restart_defers_the_automatic_one() {
    RadarWatchdog watchdog = watchdog_saw_frame(watchdog_after_boot_config(0), 1000);
    watchdog = watchdog_restarted(watchdog, 1500);
    watchdog = checked(watchdog, 3500, WatchdogAction::None);
    watchdog = checked(watchdog, 31500, WatchdogAction::Restart);
    TEST_ASSERT_EQUAL_UINT8(2, watchdog.restarts);
}

void test_alive_only_within_two_seconds_of_a_frame() {
    RadarWatchdog watchdog = watchdog_after_boot_config(0);
    TEST_ASSERT_FALSE(watchdog_radar_alive(watchdog, 100));
    watchdog = watchdog_saw_frame(watchdog, 1000);
    TEST_ASSERT_TRUE(watchdog_radar_alive(watchdog, 2999));
    TEST_ASSERT_FALSE(watchdog_radar_alive(watchdog, 3000));
}

void test_restart_counter_saturates_at_255() {
    RadarWatchdog watchdog = watchdog_after_boot_config(0);
    for (uint32_t i = 0; i < 300; ++i) {
        watchdog = watchdog_restarted(watchdog, i);
    }
    TEST_ASSERT_EQUAL_UINT8(255, watchdog.restarts);
}

void test_millis_wrap_is_handled() {
    RadarWatchdog watchdog = watchdog_saw_frame(watchdog_after_boot_config(0xFFFFFE00u), 0xFFFFFF00u);
    TEST_ASSERT_TRUE(watchdog_radar_alive(watchdog, 0x00000100u));
    checked(watchdog, 0x00000100u, WatchdogAction::None);
    checked(watchdog, 0x000006D0u, WatchdogAction::Restart);
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_boot_config_counts_as_the_first_restart);
    RUN_TEST(test_flowing_frames_never_restart);
    RUN_TEST(test_silence_mid_session_restarts_after_two_seconds_then_every_thirty);
    RUN_TEST(test_frames_after_a_restart_rearm_the_fast_restart);
    RUN_TEST(test_manual_restart_defers_the_automatic_one);
    RUN_TEST(test_alive_only_within_two_seconds_of_a_frame);
    RUN_TEST(test_restart_counter_saturates_at_255);
    RUN_TEST(test_millis_wrap_is_handled);
    return UNITY_END();
}
