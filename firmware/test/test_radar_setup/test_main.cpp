#include <unity.h>

#include "../test_entry.h"
#include "radar_setup.h"
#include "radar_watchdog.h"

namespace {

RadarSetupAction action_for(bool configured, bool has_frame, bool restart_wanted) {
    return radar_setup_action(RadarSetupInput{configured, has_frame, restart_wanted});
}

RadarSetupAction action_after_check(bool configured, const RadarWatchdog& watchdog, uint32_t now_ms) {
    WatchdogStep step = watchdog_check(watchdog, now_ms);
    return action_for(configured, step.watchdog.has_frame, step.action == WatchdogAction::Restart);
}

}  // namespace

void setUp() {}
void tearDown() {}

void test_unconfigured_radar_sending_frames_is_configured_at_the_current_baud() {
    TEST_ASSERT_TRUE(action_for(false, true, false) == RadarSetupAction::ConfigureAtCurrentBaud);
    TEST_ASSERT_TRUE(action_for(false, true, true) == RadarSetupAction::ConfigureAtCurrentBaud);
}

void test_silent_unconfigured_radar_is_probed_again_when_a_restart_is_wanted() {
    TEST_ASSERT_TRUE(action_for(false, false, true) == RadarSetupAction::ProbeBaud);
    TEST_ASSERT_TRUE(action_for(false, false, false) == RadarSetupAction::None);
}

void test_configured_radar_only_restarts_when_asked() {
    TEST_ASSERT_TRUE(action_for(true, true, true) == RadarSetupAction::Restart);
    TEST_ASSERT_TRUE(action_for(true, false, true) == RadarSetupAction::Restart);
    TEST_ASSERT_TRUE(action_for(true, true, false) == RadarSetupAction::None);
    TEST_ASSERT_TRUE(action_for(true, false, false) == RadarSetupAction::None);
}

void test_late_radar_is_configured_on_its_first_frame() {
    RadarWatchdog watchdog = watchdog_saw_frame(watchdog_after_boot_config(0), 4500);
    TEST_ASSERT_TRUE(action_after_check(false, watchdog, 4500) == RadarSetupAction::ConfigureAtCurrentBaud);
}

void test_silent_unconfigured_radar_is_probed_again_at_the_thirty_second_retry() {
    RadarWatchdog watchdog = watchdog_after_boot_config(0);
    TEST_ASSERT_TRUE(action_after_check(false, watchdog, 29999) == RadarSetupAction::None);
    TEST_ASSERT_TRUE(action_after_check(false, watchdog, 30000) == RadarSetupAction::ProbeBaud);
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_unconfigured_radar_sending_frames_is_configured_at_the_current_baud);
    RUN_TEST(test_silent_unconfigured_radar_is_probed_again_when_a_restart_is_wanted);
    RUN_TEST(test_configured_radar_only_restarts_when_asked);
    RUN_TEST(test_late_radar_is_configured_on_its_first_frame);
    RUN_TEST(test_silent_unconfigured_radar_is_probed_again_at_the_thirty_second_retry);
    return UNITY_END();
}
