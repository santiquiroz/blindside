#include <string.h>
#include <unity.h>

#include "../test_entry.h"
#include "../vectors.h"
#include "bundler.h"

namespace {

constexpr size_t kTestPacketCapacity = 16;
Packet g_packets[kTestPacketCapacity];
RadarFrame g_frames[2];
ImuSample g_imu[2][19];

const int16_t kImuAxesA[5][6] = {{0, 0, 4096, 10, -5, 3}, {1, -1, 4095, 11, -5, 3}, {2, -2, 4094, 12, -4, 2},
                                 {-1, 1, 4097, 9, -6, 4}, {0, 0, 4096, 10, -5, 3}};
const int16_t kImuAxesB[5][6] = {{10, 20, 4090, -3, 0, 655}, {11, 21, 4091, -3, 1, 656}, {12, 22, 4092, -2, 1, 657},
                                 {13, 23, 4093, -2, 0, 658}, {14, 24, 4094, -1, 0, 659}};
const int16_t kFlatAxes[6] = {0, 0, 4096, 0, 0, 0};
const StatusEntry kTypicalStatus[2] = {StatusEntry{0, 2, 0, 7}, StatusEntry{1, 0, 1, 7}};

// Offsets of the RADAR targets inside the shared vector: header 8 + IMU 80 + IMU 80 + STATUS 12, then 7 B per RADAR.
constexpr size_t kTypicalRadarATargets = 187;
constexpr size_t kTypicalRadarBTargets = 218;

RadarFrame frame_with(uint8_t radar_id, uint32_t t_ms, const uint8_t* targets) {
    RadarFrame frame{};
    frame.radar_id = radar_id;
    frame.t_ms = t_ms;
    memcpy(frame.targets, targets, kRadarTargetsSize);
    return frame;
}

ImuSample sample_with(uint32_t t_ms, const int16_t* axes, const GyroSums& sums) {
    ImuSample sample{};
    sample.t_ms = t_ms;
    memcpy(sample.mean.axes, axes, sizeof(sample.mean.axes));
    sample.sums = sums;
    return sample;
}

void fill_imu(uint8_t imu_id, const int16_t axes[5][6], const GyroSums& last_sums) {
    for (uint32_t i = 0; i < 5; ++i) {
        GyroSums sums = i == 4 ? last_sums : GyroSums{i, i, i};
        g_imu[imu_id][i] = sample_with(123380 + 20 * i, axes[i], sums);
    }
}

CutInput typical_cut() {
    g_frames[0] = frame_with(0, 123400, VEC_BUNDLE_TYPICAL + kTypicalRadarATargets);
    g_frames[1] = frame_with(1, 123410, VEC_BUNDLE_TYPICAL + kTypicalRadarBTargets);
    fill_imu(0, kImuAxesA, GyroSums{100u, 0xFFFFFFFAu, 7u});
    fill_imu(1, kImuAxesB, GyroSums{0xFFFFB1E0u, 3u, 3000000000u});
    CutInput input{};
    input.radar_frames = g_frames;
    input.radar_count = 2;
    input.imu_samples[0] = g_imu[0];
    input.imu_samples[1] = g_imu[1];
    input.imu_counts[0] = 5;
    input.imu_counts[1] = 5;
    return with_status(input, kTypicalStatus);
}

HeaderFields typical_header() {
    return HeaderFields{0x0F, 65535, 123456};
}

bool sections_are_whole(const Packet& packet) {
    size_t offset = kPacketHeaderSize;
    while (offset + 2 <= packet.length) {
        offset += 2 + packet.bytes[offset + 1];
    }
    return offset == packet.length;
}

uint16_t packet_seq(const Packet& packet) {
    return static_cast<uint16_t>(packet.bytes[2] | (packet.bytes[3] << 8));
}

uint32_t u32_at(const uint8_t* bytes) {
    return static_cast<uint32_t>(bytes[0]) | (static_cast<uint32_t>(bytes[1]) << 8) |
           (static_cast<uint32_t>(bytes[2]) << 16) | (static_cast<uint32_t>(bytes[3]) << 24);
}

void assert_all_typical_content_consumed(const BundleResult& result) {
    TEST_ASSERT_EQUAL_UINT(2, result.radar_consumed);
    TEST_ASSERT_EQUAL_UINT(5, result.imu_consumed[0]);
    TEST_ASSERT_EQUAL_UINT(5, result.imu_consumed[1]);
    TEST_ASSERT_TRUE(result.status_consumed);
}

}  // namespace

void setUp() {
    memset(g_packets, 0, sizeof(g_packets));
}

void tearDown() {}

void test_one_radar_frame_matches_shared_vector() {
    g_frames[0] = frame_with(0, 995, VEC_LD2450_OFFICIAL_FRAME + 4);
    CutInput input{};
    input.radar_frames = g_frames;
    input.radar_count = 1;
    BundleResult result = bundle_cut(input, HeaderFields{0x0F, 1, 1000}, 244, g_packets, kTestPacketCapacity);
    TEST_ASSERT_EQUAL_UINT(1, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(VEC_BUNDLE_ONE_RADAR_LEN, g_packets[0].length);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(VEC_BUNDLE_ONE_RADAR, g_packets[0].bytes, VEC_BUNDLE_ONE_RADAR_LEN);
    TEST_ASSERT_EQUAL_UINT16(2, result.next_seq);
}

void test_typical_cut_matches_shared_vector_in_one_packet() {
    BundleResult result = bundle_cut(typical_cut(), typical_header(), 252, g_packets, kTestPacketCapacity);
    TEST_ASSERT_EQUAL_UINT(1, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(242, g_packets[0].length);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(VEC_BUNDLE_TYPICAL, g_packets[0].bytes, 242);
    TEST_ASSERT_EQUAL_UINT16(0, result.next_seq);
    assert_all_typical_content_consumed(result);
}

void test_small_mtu_sends_imu_first_without_cutting_sections() {
    BundleResult result = bundle_cut(typical_cut(), typical_header(), 100, g_packets, kTestPacketCapacity);
    TEST_ASSERT_EQUAL_UINT(3, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(88, g_packets[0].length);
    TEST_ASSERT_EQUAL_UINT(100, g_packets[1].length);
    TEST_ASSERT_EQUAL_UINT(70, g_packets[2].length);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(VEC_BUNDLE_TYPICAL + 8, g_packets[0].bytes + 8, 80);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(VEC_BUNDLE_TYPICAL + 88, g_packets[1].bytes + 8, 92);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(VEC_BUNDLE_TYPICAL + 180, g_packets[2].bytes + 8, 62);
    TEST_ASSERT_EQUAL_UINT16(65535, packet_seq(g_packets[0]));
    TEST_ASSERT_EQUAL_UINT16(0, packet_seq(g_packets[1]));
    TEST_ASSERT_EQUAL_UINT16(1, packet_seq(g_packets[2]));
    TEST_ASSERT_EQUAL_UINT16(2, result.next_seq);
    for (size_t i = 0; i < result.packet_count; ++i) {
        TEST_ASSERT_TRUE(sections_are_whole(g_packets[i]));
        TEST_ASSERT_EQUAL_UINT8_ARRAY(VEC_BUNDLE_TYPICAL + 4, g_packets[i].bytes + 4, 4);
    }
    assert_all_typical_content_consumed(result);
}

void test_minimum_payload_uses_single_sample_imu_sections() {
    BundleResult result = bundle_cut(typical_cut(), typical_header(), 40, g_packets, kTestPacketCapacity);
    TEST_ASSERT_EQUAL_UINT(13, result.packet_count);
    for (size_t i = 0; i < result.packet_count; ++i) {
        TEST_ASSERT_LESS_OR_EQUAL_UINT(40, g_packets[i].length);
        TEST_ASSERT_TRUE(sections_are_whole(g_packets[i]));
    }
    TEST_ASSERT_EQUAL_HEX8(0x02, g_packets[0].bytes[8]);
    TEST_ASSERT_EQUAL_UINT8(30, g_packets[0].bytes[9]);
    TEST_ASSERT_EQUAL_UINT8(1, g_packets[0].bytes[15]);
    TEST_ASSERT_EQUAL_HEX8(0x03, g_packets[10].bytes[8]);
    TEST_ASSERT_EQUAL_HEX8(0x01, g_packets[11].bytes[8]);
    TEST_ASSERT_EQUAL_HEX8(0x01, g_packets[12].bytes[8]);
    assert_all_typical_content_consumed(result);
}

void test_payload_below_minimum_sends_nothing() {
    BundleResult result = bundle_cut(typical_cut(), typical_header(), 39, g_packets, kTestPacketCapacity);
    TEST_ASSERT_EQUAL_UINT(0, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(0, result.radar_consumed);
    TEST_ASSERT_EQUAL_UINT16(65535, result.next_seq);
}

void test_packet_budget_consumes_only_a_prefix() {
    BundleResult result = bundle_cut(typical_cut(), typical_header(), 100, g_packets, 1);
    TEST_ASSERT_EQUAL_UINT(1, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(5, result.imu_consumed[0]);
    TEST_ASSERT_EQUAL_UINT(0, result.imu_consumed[1]);
    TEST_ASSERT_FALSE(result.status_consumed);
    TEST_ASSERT_EQUAL_UINT(0, result.radar_consumed);
    TEST_ASSERT_EQUAL_UINT16(0, result.next_seq);
}

void test_empty_cut_sends_a_heartbeat_header() {
    const uint8_t expected[8] = {0x01, 0x03, 0x07, 0x00, 0x88, 0x13, 0x00, 0x00};
    BundleResult result = bundle_cut(CutInput{}, HeaderFields{0x03, 7, 5000}, 244, g_packets, kTestPacketCapacity);
    TEST_ASSERT_EQUAL_UINT(1, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(8, g_packets[0].length);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(expected, g_packets[0].bytes, 8);
}

void test_imu_gap_starts_a_new_section() {
    const uint32_t times[5] = {0, 20, 40, 100, 120};
    for (size_t i = 0; i < 5; ++i) {
        g_imu[0][i] = sample_with(times[i], kFlatAxes, GyroSums{0, 0, 0});
    }
    CutInput input{};
    input.imu_samples[0] = g_imu[0];
    input.imu_counts[0] = 5;
    BundleResult result = bundle_cut(input, HeaderFields{0x0F, 0, 200}, 244, g_packets, kTestPacketCapacity);
    TEST_ASSERT_EQUAL_UINT(1, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(108, g_packets[0].length);
    TEST_ASSERT_EQUAL_UINT8(3, g_packets[0].bytes[15]);
    TEST_ASSERT_EQUAL_HEX8(0x02, g_packets[0].bytes[64]);
    TEST_ASSERT_EQUAL_UINT8(100, g_packets[0].bytes[67]);
    TEST_ASSERT_EQUAL_UINT8(2, g_packets[0].bytes[71]);
    TEST_ASSERT_EQUAL_UINT(5, result.imu_consumed[0]);
}

void test_nineteen_samples_split_into_eighteen_and_one() {
    for (uint32_t i = 0; i < 19; ++i) {
        g_imu[0][i] = sample_with(1000 + 20 * i, kFlatAxes, GyroSums{i, 0, 0});
    }
    CutInput input{};
    input.imu_samples[0] = g_imu[0];
    input.imu_counts[0] = 19;
    BundleResult result = bundle_cut(input, HeaderFields{0x0F, 0, 1400}, 244, g_packets, kTestPacketCapacity);
    TEST_ASSERT_EQUAL_UINT(2, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(244, g_packets[0].length);
    TEST_ASSERT_EQUAL_UINT8(18, g_packets[0].bytes[15]);
    TEST_ASSERT_EQUAL_UINT32(17, u32_at(g_packets[0].bytes + 232));
    TEST_ASSERT_EQUAL_UINT(40, g_packets[1].length);
    TEST_ASSERT_EQUAL_UINT32(1360, u32_at(g_packets[1].bytes + 11));
    TEST_ASSERT_EQUAL_UINT8(1, g_packets[1].bytes[15]);
    TEST_ASSERT_EQUAL_UINT(19, result.imu_consumed[0]);
}

void test_link_section_precedes_radar_and_matches_shared_vector() {
    g_frames[0] = frame_with(0, 6990, VEC_LD2450_OFFICIAL_FRAME + 4);
    CutInput input{};
    input.radar_frames = g_frames;
    input.radar_count = 1;
    BundleResult result = bundle_cut(with_link(input, LinkParams{36, 0, 500}), HeaderFields{0x0F, 9, 7000}, 244,
                                     g_packets, kTestPacketCapacity);
    TEST_ASSERT_TRUE(result.link_consumed);
    TEST_ASSERT_EQUAL_UINT(1, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(VEC_BUNDLE_WITH_LINK_LEN, g_packets[0].length);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(VEC_BUNDLE_WITH_LINK, g_packets[0].bytes, VEC_BUNDLE_WITH_LINK_LEN);
}

void test_link_that_does_not_fit_opens_a_new_packet() {
    CutInput input = typical_cut();
    input.imu_counts[1] = 0;
    input.radar_count = 1;
    input = with_link(input, LinkParams{24, 0, 400});
    BundleResult result = bundle_cut(input, typical_header(), 100, g_packets, kTestPacketCapacity);
    TEST_ASSERT_EQUAL_UINT(2, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(100, g_packets[0].length);
    TEST_ASSERT_EQUAL_UINT(47, g_packets[1].length);
    TEST_ASSERT_EQUAL_HEX8(0x04, g_packets[1].bytes[8]);
    TEST_ASSERT_EQUAL_HEX8(0x01, g_packets[1].bytes[16]);
    TEST_ASSERT_TRUE(result.link_consumed);
    BundleResult cramped = bundle_cut(input, typical_header(), 100, g_packets, 1);
    TEST_ASSERT_FALSE(cramped.link_consumed);
    TEST_ASSERT_EQUAL_UINT(0, cramped.radar_consumed);
}

void test_optional_sections_are_requested_through_helpers() {
    const StatusEntry status[2] = {StatusEntry{0, 1, 2, 7}, StatusEntry{1, 3, 4, 5}};
    CutInput input = with_link(with_status(CutInput{}, status), LinkParams{24, 1, 400});
    TEST_ASSERT_TRUE(input.include_status);
    TEST_ASSERT_EQUAL_UINT8(4, input.status[1].restarts);
    TEST_ASSERT_TRUE(input.include_link);
    TEST_ASSERT_EQUAL_UINT16(400, input.link.supervision_units);
    TEST_ASSERT_FALSE(with_status(CutInput{}, nullptr).include_status);
}

void test_flags_encode_health_bits() {
    LinkHealth health{};
    health.radar_alive[0] = true;
    health.imu_ok[1] = true;
    health.data_dropped = true;
    TEST_ASSERT_EQUAL_HEX8(0x19, packet_flags(health));
    TEST_ASSERT_EQUAL_HEX8(0x00, packet_flags(LinkHealth{}));
}

void test_payload_limit_follows_mtu_and_caps_at_244() {
    TEST_ASSERT_EQUAL_UINT(244, payload_limit_for_mtu(255));
    TEST_ASSERT_EQUAL_UINT(244, payload_limit_for_mtu(517));
    TEST_ASSERT_EQUAL_UINT(244, payload_limit_for_mtu(247));
    TEST_ASSERT_EQUAL_UINT(97, payload_limit_for_mtu(100));
    TEST_ASSERT_EQUAL_UINT(20, payload_limit_for_mtu(23));
    TEST_ASSERT_EQUAL_UINT(0, payload_limit_for_mtu(3));
    TEST_ASSERT_EQUAL_UINT(0, payload_limit_for_mtu(0));
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_one_radar_frame_matches_shared_vector);
    RUN_TEST(test_typical_cut_matches_shared_vector_in_one_packet);
    RUN_TEST(test_small_mtu_sends_imu_first_without_cutting_sections);
    RUN_TEST(test_minimum_payload_uses_single_sample_imu_sections);
    RUN_TEST(test_payload_below_minimum_sends_nothing);
    RUN_TEST(test_packet_budget_consumes_only_a_prefix);
    RUN_TEST(test_empty_cut_sends_a_heartbeat_header);
    RUN_TEST(test_imu_gap_starts_a_new_section);
    RUN_TEST(test_nineteen_samples_split_into_eighteen_and_one);
    RUN_TEST(test_link_section_precedes_radar_and_matches_shared_vector);
    RUN_TEST(test_link_that_does_not_fit_opens_a_new_packet);
    RUN_TEST(test_optional_sections_are_requested_through_helpers);
    RUN_TEST(test_flags_encode_health_bits);
    RUN_TEST(test_payload_limit_follows_mtu_and_caps_at_244);
    return UNITY_END();
}
