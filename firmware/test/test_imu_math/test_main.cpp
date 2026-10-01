#include <unity.h>

#include "../test_entry.h"
#include "imu_accumulator.h"
#include "mpu6050_registers.h"

namespace {

ImuReading reading_with(int16_t ax, int16_t ay, int16_t az, int16_t gx, int16_t gy, int16_t gz) {
    return ImuReading{{ax, ay, az, gx, gy, gz}};
}

ImuRead good_read(int16_t gx) {
    return ImuRead{true, reading_with(0, 0, 4096, gx, 0, 0)};
}

ImuRead failed_read() {
    return ImuRead{};
}

struct FeedResult {
    ImuAccumulator accumulator;
    int samples;
    ImuSample last;
};

FeedResult fed(const FeedResult& result, const ImuReading& reading, uint32_t t_ms) {
    AccumulatorStep step = accumulator_add(result.accumulator, reading, t_ms);
    FeedResult next = result;
    next.accumulator = step.accumulator;
    next.samples += step.has_sample ? 1 : 0;
    if (step.has_sample) {
        next.last = step.sample;
    }
    return next;
}

FeedResult fresh() {
    FeedResult result{};
    result.accumulator = accumulator_start();
    return result;
}

struct StepLog {
    ImuChannel channel;
    size_t samples;
    bool on_grid;
    uint32_t last_t_ms;
};

StepLog logged(const StepLog& log, const ImuStep& step) {
    StepLog next = log;
    next.channel = step.channel;
    for (size_t i = 0; i < step.sample_count; ++i) {
        uint32_t expected_t_ms = 10 + 20 * static_cast<uint32_t>(next.samples);
        next.on_grid = next.on_grid && step.samples[i].t_ms == expected_t_ms;
        next.last_t_ms = step.samples[i].t_ms;
        next.samples++;
    }
    return next;
}

void assert_write(const RegisterWrite& write, uint8_t reg, uint8_t value) {
    TEST_ASSERT_EQUAL_HEX8(reg, write.reg);
    TEST_ASSERT_EQUAL_HEX8(value, write.value);
}

}  // namespace

void setUp() {}
void tearDown() {}

void test_four_readings_make_one_rounded_sample_at_the_slot_centre() {
    FeedResult result = fresh();
    result = fed(result, reading_with(1, 0, 4096, 10, -1, 0), 1000);
    result = fed(result, reading_with(2, 0, 4096, 11, -2, 0), 1005);
    result = fed(result, reading_with(3, 0, 4096, 12, -2, 0), 1010);
    TEST_ASSERT_EQUAL_INT(0, result.samples);
    result = fed(result, reading_with(4, 0, 4096, 13, -2, 0), 1015);
    TEST_ASSERT_EQUAL_INT(1, result.samples);
    TEST_ASSERT_EQUAL_UINT32(1010, result.last.t_ms);
    TEST_ASSERT_EQUAL_INT16(3, result.last.mean.axes[kAx]);
    TEST_ASSERT_EQUAL_INT16(4096, result.last.mean.axes[kAz]);
    TEST_ASSERT_EQUAL_INT16(12, result.last.mean.axes[kGx]);
    TEST_ASSERT_EQUAL_INT16(-2, result.last.mean.axes[kGy]);
}

void test_sums_cover_every_raw_reading_and_wrap_for_negatives() {
    FeedResult result = fresh();
    for (uint32_t i = 0; i < 4; ++i) {
        result = fed(result, reading_with(0, 0, 0, 10 + static_cast<int16_t>(i), -5, -5), 1000 + 5 * i);
    }
    TEST_ASSERT_EQUAL_UINT32(46, result.last.sums.x);
    TEST_ASSERT_EQUAL_UINT32(0xFFFFFFECu, result.last.sums.y);
    TEST_ASSERT_EQUAL_UINT32(0xFFFFFFECu, result.last.sums.z);
}

void test_second_sample_keeps_cumulative_sums_and_its_own_time() {
    FeedResult result = fresh();
    for (uint32_t i = 0; i < 8; ++i) {
        result = fed(result, reading_with(0, 0, 0, 1, 0, 0), 2000 + 5 * i);
    }
    TEST_ASSERT_EQUAL_INT(2, result.samples);
    TEST_ASSERT_EQUAL_UINT32(2030, result.last.t_ms);
    TEST_ASSERT_EQUAL_UINT32(8, result.last.sums.x);
}

void test_rounding_is_half_away_from_zero() {
    TEST_ASSERT_EQUAL_INT16(0, rounded_quarter(0));
    TEST_ASSERT_EQUAL_INT16(0, rounded_quarter(1));
    TEST_ASSERT_EQUAL_INT16(1, rounded_quarter(2));
    TEST_ASSERT_EQUAL_INT16(-1, rounded_quarter(-2));
    TEST_ASSERT_EQUAL_INT16(2, rounded_quarter(6));
    TEST_ASSERT_EQUAL_INT16(-2, rounded_quarter(-6));
    TEST_ASSERT_EQUAL_INT16(32767, rounded_quarter(4 * 32767));
    TEST_ASSERT_EQUAL_INT16(-32768, rounded_quarter(4 * -32768));
}

void test_ticks_fall_due_on_the_5_ms_grid() {
    ImuChannel channel = imu_channel_start(1000);
    TEST_ASSERT_EQUAL_UINT32(0, imu_ticks_due(channel, 999));
    TEST_ASSERT_EQUAL_UINT32(1, imu_ticks_due(channel, 1000));
    TEST_ASSERT_EQUAL_UINT32(1, imu_ticks_due(channel, 1004));
    TEST_ASSERT_EQUAL_UINT32(2, imu_ticks_due(channel, 1005));
}

void test_ticks_due_survive_a_millis_wrap() {
    ImuChannel channel = imu_channel_start(0xFFFFFFFEu);
    TEST_ASSERT_EQUAL_UINT32(3, imu_ticks_due(channel, 0x00000008u));
    TEST_ASSERT_EQUAL_UINT32(0, imu_ticks_due(channel, 0xFFFFFFFDu));
}

void test_failed_read_repeats_the_previous_reading_and_sums_advance_four_per_block() {
    ImuStep step = imu_channel_step(imu_channel_start(1000), 1000, good_read(8));
    step = imu_channel_step(step.channel, 1005, failed_read());
    step = imu_channel_step(step.channel, 1010, failed_read());
    step = imu_channel_step(step.channel, 1015, good_read(16));
    TEST_ASSERT_EQUAL_UINT(1, step.sample_count);
    TEST_ASSERT_EQUAL_UINT32(1010, step.samples[0].t_ms);
    TEST_ASSERT_EQUAL_INT16(10, step.samples[0].mean.axes[kGx]);
    TEST_ASSERT_EQUAL_UINT32(40, step.samples[0].sums.x);
    TEST_ASSERT_EQUAL_UINT32(2, step.channel.repeats);
}

void test_skipped_slots_keep_the_samples_on_the_20_ms_grid() {
    ImuStep step = imu_channel_step(imu_channel_start(1000), 1000, good_read(4));
    step = imu_channel_step(step.channel, 1032, good_read(4));
    TEST_ASSERT_EQUAL_UINT(1, step.sample_count);
    TEST_ASSERT_EQUAL_UINT32(1010, step.samples[0].t_ms);
    TEST_ASSERT_EQUAL_UINT32(5, step.channel.repeats);
    TEST_ASSERT_EQUAL_UINT32(1035, step.channel.next_tick_ms);
    step = imu_channel_step(step.channel, 1037, good_read(4));
    TEST_ASSERT_EQUAL_UINT(1, step.sample_count);
    TEST_ASSERT_EQUAL_UINT32(1030, step.samples[0].t_ms);
    TEST_ASSERT_EQUAL_UINT32(32, step.samples[0].sums.x);
}

void test_a_long_stall_is_fed_over_several_steps_without_moving_the_grid() {
    StepLog log{};
    log.on_grid = true;
    log = logged(log, imu_channel_step(imu_channel_start(0), 0, good_read(1)));
    ImuStep step = imu_channel_step(log.channel, 995, good_read(1));
    TEST_ASSERT_EQUAL_UINT(kMaxSamplesPerStep, step.sample_count);
    log = logged(log, step);
    while (imu_ticks_due(log.channel, 995) > 0) {
        log = logged(log, imu_channel_step(log.channel, 995, good_read(1)));
    }
    TEST_ASSERT_TRUE(log.on_grid);
    TEST_ASSERT_EQUAL_UINT(50, log.samples);
    TEST_ASSERT_EQUAL_UINT32(990, log.last_t_ms);
    TEST_ASSERT_EQUAL_UINT32(1000, log.channel.next_tick_ms);
    TEST_ASSERT_EQUAL_UINT32(198, log.channel.repeats);
}

void test_genuine_mpu6050_gets_five_writes_without_accel_config2() {
    MpuConfigPlan plan = mpu_config_plan(0x68);
    TEST_ASSERT_EQUAL_UINT8(5, plan.count);
    assert_write(plan.writes[0], 0x6B, 0x01);
    assert_write(plan.writes[1], 0x19, 0x00);
    assert_write(plan.writes[2], 0x1A, 0x03);
    assert_write(plan.writes[3], 0x1B, 0x08);
    assert_write(plan.writes[4], 0x1C, 0x10);
}

void test_clone_also_gets_accel_config2() {
    MpuConfigPlan plan = mpu_config_plan(0x70);
    TEST_ASSERT_EQUAL_UINT8(6, plan.count);
    assert_write(plan.writes[5], 0x1D, 0x03);
    TEST_ASSERT_EQUAL_UINT8(6, mpu_config_plan(0x71).count);
}

void test_who_am_i_tolerates_clones_but_not_bus_errors() {
    TEST_ASSERT_TRUE(who_am_i_is_usable(0x68));
    TEST_ASSERT_TRUE(who_am_i_is_usable(0x70));
    TEST_ASSERT_TRUE(who_am_i_is_usable(0x98));
    TEST_ASSERT_FALSE(who_am_i_is_usable(0x00));
    TEST_ASSERT_FALSE(who_am_i_is_usable(0xFF));
    TEST_ASSERT_FALSE(needs_accel_config2(0x68));
    TEST_ASSERT_TRUE(needs_accel_config2(0x70));
}

void test_burst_is_big_endian_and_skips_temperature() {
    const uint8_t burst[14] = {0x10, 0x00, 0xFF, 0xFE, 0x00, 0x01, 0x12, 0x34,
                               0x00, 0x83, 0xFF, 0x7D, 0x7F, 0xFF};
    ImuReading reading = reading_from_burst(burst);
    TEST_ASSERT_EQUAL_INT16(4096, reading.axes[kAx]);
    TEST_ASSERT_EQUAL_INT16(-2, reading.axes[kAy]);
    TEST_ASSERT_EQUAL_INT16(1, reading.axes[kAz]);
    TEST_ASSERT_EQUAL_INT16(131, reading.axes[kGx]);
    TEST_ASSERT_EQUAL_INT16(-131, reading.axes[kGy]);
    TEST_ASSERT_EQUAL_INT16(32767, reading.axes[kGz]);
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_four_readings_make_one_rounded_sample_at_the_slot_centre);
    RUN_TEST(test_sums_cover_every_raw_reading_and_wrap_for_negatives);
    RUN_TEST(test_second_sample_keeps_cumulative_sums_and_its_own_time);
    RUN_TEST(test_rounding_is_half_away_from_zero);
    RUN_TEST(test_ticks_fall_due_on_the_5_ms_grid);
    RUN_TEST(test_ticks_due_survive_a_millis_wrap);
    RUN_TEST(test_failed_read_repeats_the_previous_reading_and_sums_advance_four_per_block);
    RUN_TEST(test_skipped_slots_keep_the_samples_on_the_20_ms_grid);
    RUN_TEST(test_a_long_stall_is_fed_over_several_steps_without_moving_the_grid);
    RUN_TEST(test_genuine_mpu6050_gets_five_writes_without_accel_config2);
    RUN_TEST(test_clone_also_gets_accel_config2);
    RUN_TEST(test_who_am_i_tolerates_clones_but_not_bus_errors);
    RUN_TEST(test_burst_is_big_endian_and_skips_temperature);
    return UNITY_END();
}
