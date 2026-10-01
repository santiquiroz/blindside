#include <string.h>
#include <unity.h>

#include "../test_entry.h"
#include "stream_backlog.h"

namespace {

constexpr size_t kTestPacketCapacity = 8;
StreamBacklog g_backlog;
Packet g_packets[kTestPacketCapacity];

struct CutWalk {
    size_t imu_sections;
    size_t radar_sections;
    bool imu_after_radar;
    bool radar_time_went_back;
    bool sections_whole;
    size_t longest_packet;
    uint32_t last_radar_t_ms;
};

RadarFrame radar_at(uint8_t radar_id, uint32_t t_ms) {
    RadarFrame frame{};
    frame.radar_id = radar_id;
    frame.t_ms = t_ms;
    return frame;
}

void add_imu_run(uint8_t imu_id, uint32_t first_t_ms, uint32_t count) {
    for (uint32_t i = 0; i < count; ++i) {
        ImuSample sample{};
        sample.t_ms = first_t_ms + 20 * i;
        backlog_add_imu(g_backlog, imu_id, sample);
    }
}

void add_radar_frames(const RadarFrame* frames, size_t count) {
    for (size_t i = 0; i < count; ++i) {
        backlog_add_radar(g_backlog, frames[i]);
    }
}

uint32_t u32_at(const uint8_t* bytes) {
    return static_cast<uint32_t>(bytes[0]) | (static_cast<uint32_t>(bytes[1]) << 8) |
           (static_cast<uint32_t>(bytes[2]) << 16) | (static_cast<uint32_t>(bytes[3]) << 24);
}

CutWalk walked_radar(const CutWalk& walk, const uint8_t* section) {
    CutWalk next = walk;
    uint32_t t_ms = u32_at(section + 3);
    next.radar_time_went_back = walk.radar_time_went_back || (walk.radar_sections > 0 && t_ms < walk.last_radar_t_ms);
    next.last_radar_t_ms = t_ms;
    next.radar_sections++;
    return next;
}

CutWalk walked_imu(const CutWalk& walk) {
    CutWalk next = walk;
    next.imu_after_radar = walk.imu_after_radar || walk.radar_sections > 0;
    next.imu_sections++;
    return next;
}

CutWalk walked_section(const CutWalk& walk, const uint8_t* section) {
    if (section[0] == kTlvRadar) {
        return walked_radar(walk, section);
    }
    return section[0] == kTlvImu ? walked_imu(walk) : walk;
}

CutWalk walked_packet(const CutWalk& walk, const Packet& packet) {
    CutWalk next = walk;
    next.longest_packet = packet.length > walk.longest_packet ? packet.length : walk.longest_packet;
    size_t offset = kPacketHeaderSize;
    while (offset + 2 <= packet.length) {
        next = walked_section(next, packet.bytes + offset);
        offset += 2 + packet.bytes[offset + 1];
    }
    next.sections_whole = next.sections_whole && offset == packet.length;
    return next;
}

CutWalk walk_packets(const BundleResult& result) {
    CutWalk walk{};
    walk.sections_whole = true;
    for (size_t i = 0; i < result.packet_count; ++i) {
        walk = walked_packet(walk, g_packets[i]);
    }
    return walk;
}

BundleResult cut_at(uint32_t cut_ms, size_t payload_limit) {
    CutInput input = backlog_cut_input(g_backlog, cut_ms);
    return bundle_cut(input, HeaderFields{0x0F, 0, cut_ms}, payload_limit, g_packets, kTestPacketCapacity);
}

void add_three_frames_and_imus(uint32_t imu_a_samples, uint32_t imu_b_samples) {
    const RadarFrame frames[] = {radar_at(0, 10), radar_at(1, 40), radar_at(0, 90)};
    add_radar_frames(frames, 3);
    add_imu_run(0, 10, imu_a_samples);
    add_imu_run(1, 10, imu_b_samples);
}

void assert_clean_split(const BundleResult& result, size_t payload_limit, size_t radar_sections) {
    CutWalk walk = walk_packets(result);
    TEST_ASSERT_TRUE(walk.sections_whole);
    TEST_ASSERT_LESS_OR_EQUAL_UINT(payload_limit, walk.longest_packet);
    TEST_ASSERT_EQUAL_UINT(radar_sections, walk.radar_sections);
    TEST_ASSERT_FALSE(walk.imu_after_radar);
    TEST_ASSERT_FALSE(walk.radar_time_went_back);
}

}  // namespace

void setUp() {
    memset(&g_backlog, 0, sizeof(g_backlog));
    memset(g_packets, 0, sizeof(g_packets));
    backlog_clear(g_backlog);
}

void tearDown() {}

void test_mtu_255_splits_249_bytes_of_content_without_passing_244() {
    add_three_frames_and_imus(4, 5);
    BundleResult result = cut_at(100, payload_limit_for_mtu(255));
    TEST_ASSERT_EQUAL_UINT(2, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(218, g_packets[0].length);
    TEST_ASSERT_EQUAL_UINT(39, g_packets[1].length);
    assert_clean_split(result, 244, 3);
}

void test_mtu_185_still_splits_without_breaking_sections() {
    add_three_frames_and_imus(4, 5);
    BundleResult result = cut_at(100, payload_limit_for_mtu(185));
    TEST_ASSERT_EQUAL_UINT(2, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(156, g_packets[0].length);
    TEST_ASSERT_EQUAL_UINT(101, g_packets[1].length);
    assert_clean_split(result, 182, 3);
}

void test_three_frames_and_two_seven_sample_imus_take_two_packets() {
    add_three_frames_and_imus(7, 7);
    BundleResult result = cut_at(100, 244);
    TEST_ASSERT_EQUAL_UINT(2, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(216, g_packets[0].length);
    TEST_ASSERT_EQUAL_UINT(101, g_packets[1].length);
    assert_clean_split(result, 244, 3);
}

void test_cut_margin_takes_the_frame_at_94_and_defers_the_one_at_96() {
    const RadarFrame frames[] = {radar_at(0, 94), radar_at(1, 96)};
    add_radar_frames(frames, 2);
    CutInput first = backlog_cut_input(g_backlog, 100);
    TEST_ASSERT_EQUAL_UINT(1, first.radar_count);
    TEST_ASSERT_EQUAL_UINT32(94, first.radar_frames[0].t_ms);
    backlog_consume(g_backlog, bundle_cut(first, HeaderFields{0x03, 0, 100}, 244, g_packets, kTestPacketCapacity));
    CutInput second = backlog_cut_input(g_backlog, 200);
    TEST_ASSERT_EQUAL_UINT(1, second.radar_count);
    TEST_ASSERT_EQUAL_UINT32(96, second.radar_frames[0].t_ms);
}

void assert_split_order(size_t payload_limit, size_t expected_packets) {
    setUp();
    const RadarFrame frames[] = {radar_at(0, 12), radar_at(0, 92), radar_at(1, 40)};
    add_radar_frames(frames, 3);
    add_imu_run(0, 10, 6);
    add_imu_run(1, 10, 6);
    BundleResult result = cut_at(100, payload_limit);
    TEST_ASSERT_EQUAL_UINT(expected_packets, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(3, result.radar_consumed);
    assert_clean_split(result, payload_limit, 3);
}

void test_split_order_keeps_radar_time_order_and_imu_first() {
    assert_split_order(241, 2);
    assert_split_order(100, 4);
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_mtu_255_splits_249_bytes_of_content_without_passing_244);
    RUN_TEST(test_mtu_185_still_splits_without_breaking_sections);
    RUN_TEST(test_three_frames_and_two_seven_sample_imus_take_two_packets);
    RUN_TEST(test_cut_margin_takes_the_frame_at_94_and_defers_the_one_at_96);
    RUN_TEST(test_split_order_keeps_radar_time_order_and_imu_first);
    return UNITY_END();
}
