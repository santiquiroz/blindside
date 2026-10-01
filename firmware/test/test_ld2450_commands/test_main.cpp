#include <string.h>
#include <unity.h>

#include "../test_entry.h"
#include "../vectors.h"
#include "ld2450_commands.h"

namespace {

const uint8_t kEnableAck[] = {0xFD, 0xFC, 0xFB, 0xFA, 0x08, 0x00, 0xFF, 0x01, 0x00,
                              0x00, 0x01, 0x00, 0x40, 0x00, 0x04, 0x03, 0x02, 0x01};
const uint8_t kFirmwareAck[] = {0xFD, 0xFC, 0xFB, 0xFA, 0x0C, 0x00, 0xA0, 0x01, 0x00, 0x00, 0x00,
                                0x00, 0x02, 0x01, 0x16, 0x24, 0x06, 0x22, 0x04, 0x03, 0x02, 0x01};
const uint8_t kFailedMultiTargetAck[] = {0xFD, 0xFC, 0xFB, 0xFA, 0x04, 0x00, 0x90,
                                         0x01, 0x01, 0x00, 0x04, 0x03, 0x02, 0x01};

void assert_command_equals(const uint8_t* expected, size_t expected_length, const CommandBytes& actual) {
    TEST_ASSERT_EQUAL_UINT(expected_length, actual.length);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(expected, actual.bytes, expected_length);
}

}  // namespace

void setUp() {}
void tearDown() {}

void test_named_commands_match_shared_vectors() {
    assert_command_equals(VEC_CMD_ENABLE_CONFIG, VEC_CMD_ENABLE_CONFIG_LEN, enable_config_command());
    assert_command_equals(VEC_CMD_MULTI_TARGET, VEC_CMD_MULTI_TARGET_LEN, multi_target_command());
    assert_command_equals(VEC_CMD_READ_FIRMWARE, VEC_CMD_READ_FIRMWARE_LEN, read_firmware_command());
    assert_command_equals(VEC_CMD_BLUETOOTH_OFF, VEC_CMD_BLUETOOTH_OFF_LEN, bluetooth_off_command());
    assert_command_equals(VEC_CMD_RESTART, VEC_CMD_RESTART_LEN, restart_command());
}

void test_generic_builder_matches_end_config_and_set_baud_vectors() {
    const uint8_t baud_256000[] = {0x07, 0x00};
    assert_command_equals(VEC_CMD_END_CONFIG, VEC_CMD_END_CONFIG_LEN, build_command(kCmdEndConfig, nullptr, 0));
    assert_command_equals(VEC_CMD_SET_BAUD_256000, VEC_CMD_SET_BAUD_256000_LEN,
                          build_command(kCmdSetBaud, baud_256000, sizeof(baud_256000)));
}

void test_builder_rejects_values_longer_than_four_bytes() {
    const uint8_t too_long[5] = {1, 2, 3, 4, 5};
    TEST_ASSERT_EQUAL_UINT(0, build_command(kCmdSetBaud, too_long, sizeof(too_long)).length);
}

void test_enable_ack_is_found_with_its_value() {
    Ack ack = find_ack(kEnableAck, sizeof(kEnableAck), kCmdEnableConfig);
    const uint8_t expected_value[] = {0x01, 0x00, 0x40, 0x00};
    TEST_ASSERT_TRUE(ack.found);
    TEST_ASSERT_TRUE(ack.success);
    TEST_ASSERT_EQUAL_UINT(4, ack.value_length);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(expected_value, ack.value, 4);
}

void test_ack_is_found_after_data_frame_noise() {
    uint8_t stream[30 + sizeof(kEnableAck)];
    memcpy(stream, VEC_LD2450_OFFICIAL_FRAME, 30);
    memcpy(stream + 30, kEnableAck, sizeof(kEnableAck));
    TEST_ASSERT_TRUE(find_ack(stream, sizeof(stream), kCmdEnableConfig).found);
}

void test_ack_for_another_command_is_not_matched() {
    TEST_ASSERT_FALSE(find_ack(kEnableAck, sizeof(kEnableAck), kCmdRestart).found);
}

void test_truncated_ack_is_not_found() {
    TEST_ASSERT_FALSE(find_ack(kEnableAck, sizeof(kEnableAck) - 1, kCmdEnableConfig).found);
}

void test_failure_status_is_reported() {
    Ack ack = find_ack(kFailedMultiTargetAck, sizeof(kFailedMultiTargetAck), kCmdMultiTarget);
    TEST_ASSERT_TRUE(ack.found);
    TEST_ASSERT_FALSE(ack.success);
}

void test_firmware_version_text_matches_hilink_example() {
    Ack ack = find_ack(kFirmwareAck, sizeof(kFirmwareAck), kCmdReadFirmware);
    TEST_ASSERT_EQUAL_STRING("V1.02.22062416", firmware_text_from_ack(ack).text);
}

void test_firmware_text_is_empty_without_a_successful_ack() {
    TEST_ASSERT_EQUAL_STRING("", firmware_text_from_ack(Ack{}).text);
}

void test_baud_index_table() {
    TEST_ASSERT_EQUAL_UINT8(1, baud_index_for(9600));
    TEST_ASSERT_EQUAL_UINT8(7, baud_index_for(256000));
    TEST_ASSERT_EQUAL_UINT8(8, baud_index_for(460800));
    TEST_ASSERT_EQUAL_UINT8(0, baud_index_for(12345));
    TEST_ASSERT_EQUAL_UINT8(0, baud_index_for(0));
}

void test_probe_order_starts_at_default_and_covers_every_rate() {
    TEST_ASSERT_EQUAL_UINT32(256000, kBaudProbeOrder[0]);
    TEST_ASSERT_EQUAL_UINT(8, kBaudProbeCount);
    for (size_t i = 0; i < kBaudProbeCount; ++i) {
        TEST_ASSERT_NOT_EQUAL(0, baud_index_for(kBaudProbeOrder[i]));
    }
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_named_commands_match_shared_vectors);
    RUN_TEST(test_generic_builder_matches_end_config_and_set_baud_vectors);
    RUN_TEST(test_builder_rejects_values_longer_than_four_bytes);
    RUN_TEST(test_enable_ack_is_found_with_its_value);
    RUN_TEST(test_ack_is_found_after_data_frame_noise);
    RUN_TEST(test_ack_for_another_command_is_not_matched);
    RUN_TEST(test_truncated_ack_is_not_found);
    RUN_TEST(test_failure_status_is_reported);
    RUN_TEST(test_firmware_version_text_matches_hilink_example);
    RUN_TEST(test_firmware_text_is_empty_without_a_successful_ack);
    RUN_TEST(test_baud_index_table);
    RUN_TEST(test_probe_order_starts_at_default_and_covers_every_rate);
    return UNITY_END();
}
