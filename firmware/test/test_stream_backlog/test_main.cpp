#include <string.h>
#include <unity.h>

#include "../test_entry.h"
#include "stream_backlog.h"

namespace {

StreamBacklog g_backlog;
Packet g_packets[6];

RadarFrame radar_at(uint8_t radar_id, uint32_t t_ms) {
    RadarFrame frame{};
    frame.radar_id = radar_id;
    frame.t_ms = t_ms;
    return frame;
}

ImuSample imu_at(uint32_t t_ms) {
    ImuSample sample{};
    sample.t_ms = t_ms;
    sample.mean.axes[kAz] = 4096;
    return sample;
}

BundleResult consumed(size_t radar, size_t imu0, size_t imu1, size_t packets) {
    BundleResult result{};
    result.radar_consumed = radar;
    result.imu_consumed[0] = imu0;
    result.imu_consumed[1] = imu1;
    result.packet_count = packets;
    return result;
}

}  // namespace

void setUp() {
    memset(&g_backlog, 0, sizeof(g_backlog));
    backlog_clear(g_backlog);
}

void tearDown() {}

void test_radar_frames_are_kept_in_time_order() {
    backlog_add_radar(g_backlog, radar_at(0, 300));
    backlog_add_radar(g_backlog, radar_at(1, 100));
    backlog_add_radar(g_backlog, radar_at(0, 200));
    TEST_ASSERT_EQUAL_UINT(3, g_backlog.radar_count);
    TEST_ASSERT_EQUAL_UINT32(100, g_backlog.radar[0].t_ms);
    TEST_ASSERT_EQUAL_UINT32(200, g_backlog.radar[1].t_ms);
    TEST_ASSERT_EQUAL_UINT32(300, g_backlog.radar[2].t_ms);
}

void test_only_frames_before_the_cutoff_are_ready() {
    backlog_add_radar(g_backlog, radar_at(0, 100));
    backlog_add_radar(g_backlog, radar_at(1, 200));
    backlog_add_radar(g_backlog, radar_at(0, 300));
    TEST_ASSERT_EQUAL_UINT(0, backlog_radar_ready(g_backlog, 100));
    TEST_ASSERT_EQUAL_UINT(2, backlog_radar_ready(g_backlog, 250));
    TEST_ASSERT_EQUAL_UINT(3, backlog_radar_ready(g_backlog, 301));
}

void test_cut_input_exposes_ready_frames_and_all_imu_samples() {
    backlog_add_radar(g_backlog, radar_at(0, 100));
    backlog_add_radar(g_backlog, radar_at(0, 300));
    backlog_add_imu(g_backlog, 0, imu_at(80));
    backlog_add_imu(g_backlog, 1, imu_at(80));
    backlog_add_imu(g_backlog, 1, imu_at(100));
    CutInput input = backlog_cut_input(g_backlog, 255);
    TEST_ASSERT_EQUAL_UINT(1, input.radar_count);
    TEST_ASSERT_EQUAL_UINT(1, input.imu_counts[0]);
    TEST_ASSERT_EQUAL_UINT(2, input.imu_counts[1]);
    TEST_ASSERT_FALSE(input.include_status);
    TEST_ASSERT_FALSE(input.include_link);
}

void test_cutoff_is_five_ms_before_the_cut() {
    TEST_ASSERT_EQUAL_UINT32(95, cut_cutoff_ms(100));
    TEST_ASSERT_EQUAL_UINT32(0xFFFFFFFDu, cut_cutoff_ms(2));
}

void test_consume_removes_the_bundled_prefixes() {
    backlog_add_radar(g_backlog, radar_at(0, 100));
    backlog_add_radar(g_backlog, radar_at(1, 200));
    backlog_add_radar(g_backlog, radar_at(0, 300));
    backlog_add_imu(g_backlog, 0, imu_at(80));
    backlog_add_imu(g_backlog, 0, imu_at(100));
    backlog_consume(g_backlog, consumed(2, 2, 0, 1));
    TEST_ASSERT_EQUAL_UINT(1, g_backlog.radar_count);
    TEST_ASSERT_EQUAL_UINT32(300, g_backlog.radar[0].t_ms);
    TEST_ASSERT_EQUAL_UINT(0, g_backlog.imu_count[0]);
}

void test_over_budget_drops_oldest_radar_frames_first() {
    for (uint32_t i = 0; i < 77; ++i) {
        backlog_add_radar(g_backlog, radar_at(0, i * 10));
    }
    TEST_ASSERT_EQUAL_UINT32(0, g_backlog.dropped_total);
    backlog_add_radar(g_backlog, radar_at(0, 770));
    TEST_ASSERT_EQUAL_UINT(77, g_backlog.radar_count);
    TEST_ASSERT_EQUAL_UINT32(10, g_backlog.radar[0].t_ms);
    TEST_ASSERT_EQUAL_UINT32(1, g_backlog.dropped_total);
    TEST_ASSERT_TRUE(g_backlog.dropped_since_cut);
    TEST_ASSERT_LESS_OR_EQUAL_UINT(kBacklogBudgetBytes, backlog_pending_bytes(g_backlog));
}

void test_radar_frames_are_dropped_before_imu_samples() {
    for (uint32_t i = 0; i < 200; ++i) {
        backlog_add_imu(g_backlog, 0, imu_at(i * 20));
    }
    backlog_add_radar(g_backlog, radar_at(0, 5000));
    TEST_ASSERT_EQUAL_UINT(0, g_backlog.radar_count);
    TEST_ASSERT_EQUAL_UINT(200, g_backlog.imu_count[0]);
    TEST_ASSERT_EQUAL_UINT32(1, g_backlog.dropped_total);
}

void test_without_radar_frames_the_oldest_imu_sample_is_dropped() {
    for (uint32_t i = 0; i < 200; ++i) {
        backlog_add_imu(g_backlog, 0, imu_at(i * 20));
    }
    backlog_add_imu(g_backlog, 1, imu_at(5000));
    TEST_ASSERT_EQUAL_UINT(199, g_backlog.imu_count[0]);
    TEST_ASSERT_EQUAL_UINT32(20, g_backlog.imu[0][0].t_ms);
    TEST_ASSERT_EQUAL_UINT(1, g_backlog.imu_count[1]);
    TEST_ASSERT_EQUAL_UINT32(1, g_backlog.dropped_total);
}

void test_dropped_flag_clears_only_after_a_packet_was_built() {
    for (uint32_t i = 0; i < 78; ++i) {
        backlog_add_radar(g_backlog, radar_at(0, i * 10));
    }
    backlog_consume(g_backlog, consumed(0, 0, 0, 0));
    TEST_ASSERT_TRUE(g_backlog.dropped_since_cut);
    backlog_consume(g_backlog, consumed(1, 0, 0, 1));
    TEST_ASSERT_FALSE(g_backlog.dropped_since_cut);
}

void test_clear_empties_but_keeps_the_drop_total() {
    for (uint32_t i = 0; i < 78; ++i) {
        backlog_add_radar(g_backlog, radar_at(0, i * 10));
    }
    backlog_clear(g_backlog);
    TEST_ASSERT_EQUAL_UINT(0, backlog_pending_bytes(g_backlog));
    TEST_ASSERT_EQUAL_UINT32(1, g_backlog.dropped_total);
    TEST_ASSERT_FALSE(g_backlog.dropped_since_cut);
}

void test_a_typical_cut_empties_the_backlog_in_one_packet() {
    StatusEntry status[2] = {StatusEntry{0, 0, 0, 7}, StatusEntry{1, 0, 0, 7}};
    backlog_add_radar(g_backlog, radar_at(0, 100));
    backlog_add_radar(g_backlog, radar_at(1, 110));
    for (uint32_t i = 0; i < 5; ++i) {
        backlog_add_imu(g_backlog, 0, imu_at(80 + 20 * i));
        backlog_add_imu(g_backlog, 1, imu_at(80 + 20 * i));
    }
    CutInput input = with_status(backlog_cut_input(g_backlog, 200), status);
    BundleResult result = bundle_cut(input, HeaderFields{0x0F, 0, 200}, 244, g_packets, 6);
    backlog_consume(g_backlog, result);
    TEST_ASSERT_EQUAL_UINT(1, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(242, g_packets[0].length);
    TEST_ASSERT_EQUAL_UINT(0, backlog_pending_bytes(g_backlog));
}

void test_upstream_drops_set_the_flag_and_the_total() {
    backlog_note_upstream_drops(g_backlog, 0);
    TEST_ASSERT_FALSE(g_backlog.dropped_since_cut);
    backlog_note_upstream_drops(g_backlog, 3);
    TEST_ASSERT_TRUE(g_backlog.dropped_since_cut);
    TEST_ASSERT_EQUAL_UINT32(3, g_backlog.dropped_total);
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_radar_frames_are_kept_in_time_order);
    RUN_TEST(test_only_frames_before_the_cutoff_are_ready);
    RUN_TEST(test_cut_input_exposes_ready_frames_and_all_imu_samples);
    RUN_TEST(test_cutoff_is_five_ms_before_the_cut);
    RUN_TEST(test_consume_removes_the_bundled_prefixes);
    RUN_TEST(test_over_budget_drops_oldest_radar_frames_first);
    RUN_TEST(test_radar_frames_are_dropped_before_imu_samples);
    RUN_TEST(test_without_radar_frames_the_oldest_imu_sample_is_dropped);
    RUN_TEST(test_dropped_flag_clears_only_after_a_packet_was_built);
    RUN_TEST(test_clear_empties_but_keeps_the_drop_total);
    RUN_TEST(test_a_typical_cut_empties_the_backlog_in_one_packet);
    RUN_TEST(test_upstream_drops_set_the_flag_and_the_total);
    return UNITY_END();
}
