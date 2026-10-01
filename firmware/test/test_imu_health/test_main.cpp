#include <unity.h>

#include "../test_entry.h"
#include "imu_health.h"

namespace {

ImuHealth after_reads(const ImuHealth& health, bool read_ok, int count, uint32_t now_ms) {
    ImuHealth next = health;
    for (int i = 0; i < count; ++i) {
        next = imu_health_after_read(next, read_ok, now_ms);
    }
    return next;
}

ImuHealth healthy() {
    return after_reads(imu_health_after_probe(true, 0), true, 5, 0);
}

}  // namespace

void setUp() {}
void tearDown() {}

void test_a_found_imu_reports_ok_after_five_good_reads() {
    ImuHealth health = after_reads(imu_health_after_probe(true, 0), true, 4, 0);
    TEST_ASSERT_FALSE(health.ok);
    TEST_ASSERT_TRUE(imu_should_read(health));
    TEST_ASSERT_TRUE(imu_health_after_read(health, true, 0).ok);
}

void test_imu_goes_down_on_the_fifth_consecutive_error() {
    ImuHealth health = after_reads(healthy(), false, 4, 100);
    TEST_ASSERT_TRUE(health.ok);
    TEST_ASSERT_FALSE(health.down);
    health = imu_health_after_read(health, false, 120);
    TEST_ASSERT_FALSE(health.ok);
    TEST_ASSERT_TRUE(health.down);
    TEST_ASSERT_FALSE(imu_should_read(health));
}

void test_a_good_read_resets_the_error_streak() {
    ImuHealth health = after_reads(healthy(), false, 4, 100);
    health = imu_health_after_read(health, true, 105);
    health = after_reads(health, false, 4, 110);
    TEST_ASSERT_TRUE(health.ok);
    TEST_ASSERT_FALSE(health.down);
}

void test_recovery_is_attempted_once_per_second_while_down() {
    ImuHealth health = after_reads(healthy(), false, 5, 1000);
    TEST_ASSERT_FALSE(imu_recovery_due(health, 1999));
    TEST_ASSERT_TRUE(imu_recovery_due(health, 2000));
    health = imu_health_after_probe(false, 2000);
    TEST_ASSERT_FALSE(imu_recovery_due(health, 2999));
    TEST_ASSERT_TRUE(imu_recovery_due(health, 3000));
    TEST_ASSERT_FALSE(imu_recovery_due(healthy(), 5000));
}

void test_a_recovered_imu_is_ok_again_only_after_five_good_reads() {
    ImuHealth health = imu_health_after_probe(true, 3000);
    TEST_ASSERT_FALSE(health.down);
    health = after_reads(health, true, 4, 3005);
    TEST_ASSERT_FALSE(health.ok);
    health = imu_health_after_read(health, false, 3030);
    health = after_reads(health, true, 4, 3035);
    TEST_ASSERT_FALSE(health.ok);
    TEST_ASSERT_TRUE(imu_health_after_read(health, true, 3060).ok);
}

void test_a_missing_imu_starts_down_and_is_retried() {
    ImuHealth health = imu_health_after_probe(false, 0);
    TEST_ASSERT_TRUE(health.down);
    TEST_ASSERT_FALSE(health.ok);
    TEST_ASSERT_TRUE(imu_recovery_due(health, 1000));
}

void test_recovery_timing_survives_a_millis_wrap() {
    ImuHealth health = imu_health_after_probe(false, 0xFFFFFF00u);
    TEST_ASSERT_FALSE(imu_recovery_due(health, 0x000002E7u));
    TEST_ASSERT_TRUE(imu_recovery_due(health, 0x000002E8u));
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_a_found_imu_reports_ok_after_five_good_reads);
    RUN_TEST(test_imu_goes_down_on_the_fifth_consecutive_error);
    RUN_TEST(test_a_good_read_resets_the_error_streak);
    RUN_TEST(test_recovery_is_attempted_once_per_second_while_down);
    RUN_TEST(test_a_recovered_imu_is_ok_again_only_after_five_good_reads);
    RUN_TEST(test_a_missing_imu_starts_down_and_is_retried);
    RUN_TEST(test_recovery_timing_survives_a_millis_wrap);
    return UNITY_END();
}
