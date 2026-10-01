#include <string.h>
#include <unity.h>

#include "../test_entry.h"
#include "ble_rules.h"
#include "control_command.h"
#include "info_json.h"

namespace {

ControlCommand parsed(const uint8_t* bytes, size_t length) {
    return parse_control(bytes, length);
}

void assert_command(const ControlCommand& command, ControlKind kind, uint8_t argument) {
    TEST_ASSERT_TRUE(command.kind == kind);
    TEST_ASSERT_EQUAL_UINT8(argument, command.argument);
}

FirmwareText firmware_named(const char* text) {
    FirmwareText firmware{};
    strncpy(firmware.text, text, sizeof(firmware.text) - 1);
    return firmware;
}

BeltInfo example_info() {
    BeltInfo info{};
    info.firmware_version = "0.1.0";
    info.boot_id = 0x9f3a12c4u;
    info.reset_reason = "POWERON";
    info.mtu = 255;
    info.radars[0] = RadarInfo{0, firmware_named("V2.04.23101915"), 256000};
    info.radars[1] = RadarInfo{1, firmware_named("V2.04.23101915"), 256000};
    info.imus[0] = ImuInfo{0, 104};
    info.imus[1] = ImuInfo{1, 112};
    info.tx_power_dbm = 9;
    info.conn = LinkParams{36, 0, 500};
    info.uptime_s = 42;
    return info;
}

BeltInfo longest_info() {
    BeltInfo info = example_info();
    info.reset_reason = "DEEPSLEEP";
    info.mtu = 517;
    info.radars[0].baud = 460800;
    info.radars[1].baud = 460800;
    info.imus[0].who_am_i = 255;
    info.imus[1].who_am_i = 255;
    info.conn = LinkParams{3200, 499, 3200};
    info.uptime_s = 4294967;
    return info;
}

StreamGateInput ready_link(uint16_t mtu) {
    return StreamGateInput{true, true, true, mtu};
}

}  // namespace

void setUp() {}
void tearDown() {}

void test_valid_control_writes_are_parsed() {
    const uint8_t restart_b[] = {0x01, 0x01};
    const uint8_t identify[] = {0x03};
    const uint8_t session_on[] = {0x04, 0x01};
    const uint8_t session_off[] = {0x04, 0x00};
    assert_command(parsed(restart_b, 2), ControlKind::RestartRadar, 1);
    assert_command(parsed(identify, 1), ControlKind::Identify, 0);
    assert_command(parsed(session_on, 2), ControlKind::SessionActive, 1);
    assert_command(parsed(session_off, 2), ControlKind::SessionActive, 0);
}

void test_malformed_control_writes_are_invalid() {
    const uint8_t radar_two[] = {0x01, 0x02};
    const uint8_t restart_short[] = {0x01};
    const uint8_t identify_long[] = {0x03, 0x00};
    const uint8_t session_two[] = {0x04, 0x02};
    const uint8_t set_zones[] = {0x02, 0x00, 0x01};
    const uint8_t unknown[] = {0x7F};
    assert_command(parsed(radar_two, 2), ControlKind::Invalid, 0);
    assert_command(parsed(restart_short, 1), ControlKind::Invalid, 0);
    assert_command(parsed(identify_long, 2), ControlKind::Invalid, 0);
    assert_command(parsed(session_two, 2), ControlKind::Invalid, 0);
    assert_command(parsed(set_zones, 3), ControlKind::Invalid, 0);
    assert_command(parsed(unknown, 1), ControlKind::Invalid, 0);
    assert_command(parsed(nullptr, 0), ControlKind::Invalid, 0);
}

void test_identify_is_refused_during_a_session() {
    TEST_ASSERT_FALSE(identify_allowed(true));
    TEST_ASSERT_TRUE(identify_allowed(false));
}

void test_stream_gate_needs_subscribed_trusted_peer_and_mtu_247() {
    TEST_ASSERT_TRUE(stream_gate_open(ready_link(255)));
    TEST_ASSERT_TRUE(stream_gate_open(ready_link(247)));
    TEST_ASSERT_FALSE(stream_gate_open(ready_link(246)));
    TEST_ASSERT_FALSE(stream_gate_open(ready_link(185)));
    TEST_ASSERT_FALSE(stream_gate_open(ready_link(23)));
    TEST_ASSERT_FALSE(stream_gate_open(StreamGateInput{true, false, true, 255}));
    TEST_ASSERT_FALSE(stream_gate_open(StreamGateInput{true, true, false, 255}));
    TEST_ASSERT_FALSE(stream_gate_open(StreamGateInput{false, true, true, 255}));
}

void test_advertising_is_fast_for_thirty_seconds() {
    TEST_ASSERT_TRUE(advertising_speed(0) == AdvertisingSpeed::Fast);
    TEST_ASSERT_TRUE(advertising_speed(29999) == AdvertisingSpeed::Fast);
    TEST_ASSERT_TRUE(advertising_speed(30000) == AdvertisingSpeed::Slow);
}

void test_device_name_uses_last_two_mac_bytes() {
    const uint8_t mac[6] = {0x24, 0x6F, 0x28, 0xAB, 0x0C, 0x1E};
    TEST_ASSERT_EQUAL_STRING("Blindside-0C1E", device_name_from_mac(mac).text);
}

void test_info_json_matches_the_contract_example() {
    const char* expected =
        R"({"proto":1,"fw":"0.1.0","boot_id":"9f3a12c4","reset":"POWERON","mtu":255,)"
        R"("radars":[{"id":0,"fw":"V2.04.23101915","baud":256000},{"id":1,"fw":"V2.04.23101915","baud":256000}],)"
        R"("imus":[{"id":0,"who":104,"gyro_lsb_dps":65.5,"accel_lsb_g":4096},)"
        R"({"id":1,"who":112,"gyro_lsb_dps":65.5,"accel_lsb_g":4096}],)"
        R"("tx_power_dbm":9,"conn":{"interval_ms":45.0,"latency":0,"timeout_ms":5000},"uptime_s":42})";
    char json[kInfoJsonBufferSize];
    size_t length = format_info_json(example_info(), json, sizeof(json));
    TEST_ASSERT_EQUAL_STRING(expected, json);
    TEST_ASSERT_EQUAL_UINT(388, length);
}

void test_info_json_worst_case_still_fits_in_400_bytes() {
    char json[kInfoJsonBufferSize];
    size_t length = format_info_json(longest_info(), json, sizeof(json));
    TEST_ASSERT_EQUAL_UINT(400, length);
    TEST_ASSERT_NOT_NULL(strstr(json, R"("conn":{"interval_ms":4000.0,"latency":499,"timeout_ms":32000})"));
}

void test_info_json_rounds_the_interval_to_one_decimal() {
    BeltInfo info = example_info();
    info.conn = LinkParams{39, 0, 400};
    char json[kInfoJsonBufferSize];
    format_info_json(info, json, sizeof(json));
    TEST_ASSERT_NOT_NULL(strstr(json, R"("interval_ms":48.8,)"));
    TEST_ASSERT_NOT_NULL(strstr(json, R"("timeout_ms":4000})"));
}

void test_info_json_reports_a_missing_radar() {
    BeltInfo info = example_info();
    info.radars[1] = RadarInfo{1, FirmwareText{}, 0};
    char json[kInfoJsonBufferSize];
    format_info_json(info, json, sizeof(json));
    TEST_ASSERT_NOT_NULL(strstr(json, R"({"id":1,"fw":"","baud":0})"));
}

void test_info_json_reports_zero_when_it_does_not_fit() {
    char small[64];
    TEST_ASSERT_EQUAL_UINT(0, format_info_json(example_info(), small, sizeof(small)));
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_valid_control_writes_are_parsed);
    RUN_TEST(test_malformed_control_writes_are_invalid);
    RUN_TEST(test_identify_is_refused_during_a_session);
    RUN_TEST(test_stream_gate_needs_subscribed_trusted_peer_and_mtu_247);
    RUN_TEST(test_advertising_is_fast_for_thirty_seconds);
    RUN_TEST(test_device_name_uses_last_two_mac_bytes);
    RUN_TEST(test_info_json_matches_the_contract_example);
    RUN_TEST(test_info_json_worst_case_still_fits_in_400_bytes);
    RUN_TEST(test_info_json_rounds_the_interval_to_one_decimal);
    RUN_TEST(test_info_json_reports_a_missing_radar);
    RUN_TEST(test_info_json_reports_zero_when_it_does_not_fit);
    return UNITY_END();
}
