#include <string.h>
#include <unity.h>

#include "../test_entry.h"
#include "ble_rules.h"
#include "bond_rules.h"
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

ConnInfo conn(LinkRole role, uint16_t interval_units, uint32_t sent, uint32_t dropped) {
    return ConnInfo{role, LinkParams{interval_units, 0, 400}, sent, dropped};
}

size_t occurrences(const char* text, const char* needle) {
    size_t count = 0;
    for (const char* at = strstr(text, needle); at != nullptr; at = strstr(at + 1, needle)) {
        count++;
    }
    return count;
}

BeltInfo example_info() {
    BeltInfo info{};
    info.firmware_version = "0.2.0";
    info.boot_id = 0x9f3a12c4u;
    info.reset_reason = "POWERON";
    info.mtu = 255;
    info.radars[0] = RadarInfo{0, firmware_named("V2.04.23101915"), 256000};
    info.radars[1] = RadarInfo{1, firmware_named("V2.04.23101915"), 256000};
    info.imus[0] = ImuInfo{0, 104, 0};
    info.imus[1] = ImuInfo{1, 112, 3};
    info.tx_power_dbm = 9;
    info.conns[0] = conn(LinkRole::Watch, 36, 1234, 0);
    info.conns[1] = conn(LinkRole::Phone, 60, 1200, 3);
    info.conn_count = 2;
    info.bonds = 2;
    info.uptime_s = 42;
    return info;
}

BeltInfo longest_info() {
    BeltInfo info = example_info();
    info.reset_reason = "DEEPSLEEP";
    info.mtu = 517;
    info.radars[0] = RadarInfo{0, firmware_named("VFF.FF.FFFFFFFF"), 460800};
    info.radars[1] = RadarInfo{1, firmware_named("VFF.FF.FFFFFFFF"), 460800};
    info.imus[0] = ImuInfo{0, 255, 4294967295u};
    info.imus[1] = ImuInfo{1, 255, 4294967295u};
    info.conns[0] = ConnInfo{LinkRole::Watch, LinkParams{3200, 499, 3200}, 999999, 999999};
    info.conns[1] = ConnInfo{LinkRole::Phone, LinkParams{3200, 499, 3200}, 999999, 999999};
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

void test_open_pairing_and_set_role_writes_are_parsed() {
    const uint8_t open_pairing[] = {0x05};
    const uint8_t role_watch[] = {0x06, 0x00};
    const uint8_t role_phone[] = {0x06, 0x01};
    assert_command(parsed(open_pairing, 1), ControlKind::OpenPairingWindow, 0);
    assert_command(parsed(role_watch, 2), ControlKind::SetRole, 0);
    assert_command(parsed(role_phone, 2), ControlKind::SetRole, 1);
}

void test_malformed_open_pairing_and_set_role_writes_are_invalid() {
    const uint8_t open_pairing_long[] = {0x05, 0x01};
    const uint8_t role_missing[] = {0x06};
    const uint8_t role_two[] = {0x06, 0x02};
    const uint8_t role_long[] = {0x06, 0x01, 0x00};
    const uint8_t next_free[] = {0x07};
    assert_command(parsed(open_pairing_long, 2), ControlKind::Invalid, 0);
    assert_command(parsed(role_missing, 1), ControlKind::Invalid, 0);
    assert_command(parsed(role_two, 2), ControlKind::Invalid, 0);
    assert_command(parsed(role_long, 3), ControlKind::Invalid, 0);
    assert_command(parsed(next_free, 1), ControlKind::Invalid, 0);
}

void test_an_untrusted_link_changes_nothing() {
    const ControlKind kinds[] = {ControlKind::Invalid,       ControlKind::RestartRadar,      ControlKind::Identify,
                                 ControlKind::SessionActive, ControlKind::OpenPairingWindow, ControlKind::SetRole};
    for (ControlKind kind : kinds) {
        TEST_ASSERT_TRUE(control_action(kind, false, false) == ControlAction::RejectUntrusted);
    }
    TEST_ASSERT_TRUE(control_action(ControlKind::None, false, false) == ControlAction::Ignore);
}

void test_a_trusted_link_gets_the_action_of_its_command() {
    TEST_ASSERT_TRUE(control_action(ControlKind::None, true, false) == ControlAction::Ignore);
    TEST_ASSERT_TRUE(control_action(ControlKind::Invalid, true, false) == ControlAction::RejectInvalid);
    TEST_ASSERT_TRUE(control_action(ControlKind::RestartRadar, true, true) == ControlAction::RestartRadar);
    TEST_ASSERT_TRUE(control_action(ControlKind::Identify, true, false) == ControlAction::Identify);
    TEST_ASSERT_TRUE(control_action(ControlKind::SessionActive, true, true) == ControlAction::SetSession);
    TEST_ASSERT_TRUE(control_action(ControlKind::OpenPairingWindow, true, true) == ControlAction::OpenPairingWindow);
    TEST_ASSERT_TRUE(control_action(ControlKind::SetRole, true, true) == ControlAction::SetRole);
}

void test_identify_is_ignored_while_any_bonded_device_has_a_session() {
    uint8_t watch_in_session = session_flags_after_write(0, 0, true);
    TEST_ASSERT_TRUE(control_action(ControlKind::Identify, true, any_session_active(watch_in_session)) ==
                     ControlAction::Ignore);
    TEST_ASSERT_TRUE(control_action(ControlKind::Identify, true, any_session_active(0)) == ControlAction::Identify);
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
        R"({"proto":1,"fw":"0.2.0","boot_id":"9f3a12c4","reset":"POWERON","mtu":255,)"
        R"("radars":[{"id":0,"fw":"V2.04.23101915","baud":256000},{"id":1,"fw":"V2.04.23101915","baud":256000}],)"
        R"("imus":[{"id":0,"who":104,"repeats":0},{"id":1,"who":112,"repeats":3}],"tx_power_dbm":9,)"
        R"("conns":[{"role":"watch","itvl_ms":45.0,"lat":0,"timeout_ms":4000,"sent":1234,"dropped":0},)"
        R"({"role":"phone","itvl_ms":75.0,"lat":0,"timeout_ms":4000,"sent":1200,"dropped":3}],)"
        R"("bonds":2,"uptime_s":42})";
    char json[kInfoJsonBufferSize];
    size_t length = format_info_json(example_info(), json, sizeof(json));
    TEST_ASSERT_EQUAL_STRING(expected, json);
    TEST_ASSERT_EQUAL_UINT(460, length);
}

void test_info_json_worst_case_with_two_links_fits_in_the_512_byte_attribute() {
    char json[kInfoJsonBufferSize];
    size_t length = format_info_json(longest_info(), json, sizeof(json));
    TEST_ASSERT_EQUAL_UINT(511, length);
    TEST_ASSERT_TRUE(length <= kInfoJsonMaxBytes);
    TEST_ASSERT_NOT_NULL(strstr(json, R"({"id":1,"fw":"VFF.FF.FFFFFFFF","baud":460800})"));
    TEST_ASSERT_NOT_NULL(strstr(json, R"({"id":1,"who":255,"repeats":4294967295})"));
    TEST_ASSERT_NOT_NULL(strstr(
        json, R"({"role":"phone","itvl_ms":4000.0,"lat":499,"timeout_ms":32000,"sent":999999,"dropped":999999})"));
}

void test_info_json_rounds_the_interval_to_one_decimal() {
    BeltInfo info = example_info();
    info.conns[0].params = LinkParams{39, 0, 400};
    char json[kInfoJsonBufferSize];
    format_info_json(info, json, sizeof(json));
    TEST_ASSERT_NOT_NULL(strstr(json, R"("itvl_ms":48.8,)"));
    TEST_ASSERT_NOT_NULL(strstr(json, R"("timeout_ms":4000,)"));
}

void test_info_json_lists_one_link_or_none() {
    BeltInfo info = example_info();
    info.conn_count = 1;
    info.bonds = 1;
    char json[kInfoJsonBufferSize];
    format_info_json(info, json, sizeof(json));
    TEST_ASSERT_NOT_NULL(strstr(
        json, R"("conns":[{"role":"watch","itvl_ms":45.0,"lat":0,"timeout_ms":4000,"sent":1234,"dropped":0}],"bonds":1,)"));
    info.conn_count = 0;
    info.bonds = 0;
    format_info_json(info, json, sizeof(json));
    TEST_ASSERT_NOT_NULL(strstr(json, R"("conns":[],"bonds":0,)"));
}

void test_info_counters_wrap_at_one_million() {
    BeltInfo info = example_info();
    info.conns[0].sent = 1000001;
    info.conns[0].dropped = 4294967295u;
    char json[kInfoJsonBufferSize];
    format_info_json(info, json, sizeof(json));
    TEST_ASSERT_NOT_NULL(strstr(json, R"("sent":1,"dropped":967295})"));
}

void test_info_json_has_a_single_mtu_key_for_the_watch_reader() {
    char json[kInfoJsonBufferSize];
    format_info_json(longest_info(), json, sizeof(json));
    TEST_ASSERT_EQUAL_UINT(1, occurrences(json, R"("mtu")"));
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
    RUN_TEST(test_open_pairing_and_set_role_writes_are_parsed);
    RUN_TEST(test_malformed_open_pairing_and_set_role_writes_are_invalid);
    RUN_TEST(test_an_untrusted_link_changes_nothing);
    RUN_TEST(test_a_trusted_link_gets_the_action_of_its_command);
    RUN_TEST(test_identify_is_ignored_while_any_bonded_device_has_a_session);
    RUN_TEST(test_identify_is_refused_during_a_session);
    RUN_TEST(test_stream_gate_needs_subscribed_trusted_peer_and_mtu_247);
    RUN_TEST(test_advertising_is_fast_for_thirty_seconds);
    RUN_TEST(test_device_name_uses_last_two_mac_bytes);
    RUN_TEST(test_info_json_matches_the_contract_example);
    RUN_TEST(test_info_json_worst_case_with_two_links_fits_in_the_512_byte_attribute);
    RUN_TEST(test_info_json_rounds_the_interval_to_one_decimal);
    RUN_TEST(test_info_json_lists_one_link_or_none);
    RUN_TEST(test_info_counters_wrap_at_one_million);
    RUN_TEST(test_info_json_has_a_single_mtu_key_for_the_watch_reader);
    RUN_TEST(test_info_json_reports_a_missing_radar);
    RUN_TEST(test_info_json_reports_zero_when_it_does_not_fit);
    return UNITY_END();
}
