#include "ld2450_commands.h"

#include <stdio.h>
#include <string.h>

namespace {

constexpr uint8_t kCommandHeader[4] = {0xFD, 0xFC, 0xFB, 0xFA};
constexpr uint8_t kCommandTail[4] = {0x04, 0x03, 0x02, 0x01};
constexpr size_t kMarkerSize = 4;
constexpr size_t kLengthFieldSize = 2;
constexpr size_t kWordSize = 2;
constexpr size_t kStatusSize = 2;
constexpr size_t kValueOffset = kMarkerSize + kLengthFieldSize + kWordSize;
constexpr uint16_t kAckFlag = 0x0100;
constexpr size_t kFirmwareValueSize = 8;
constexpr uint32_t kBaudByIndex[] = {0, 9600, 19200, 38400, 57600, 115200, 230400, 256000, 460800};
constexpr uint8_t kBaudIndexCount = sizeof(kBaudByIndex) / sizeof(kBaudByIndex[0]);

void put_u16_le(uint8_t* out, uint16_t value) {
    out[0] = static_cast<uint8_t>(value & 0xFF);
    out[1] = static_cast<uint8_t>(value >> 8);
}

uint16_t get_u16_le(const uint8_t* in) {
    return static_cast<uint16_t>(in[0] | (in[1] << 8));
}

uint32_t get_u32_le(const uint8_t* in) {
    return static_cast<uint32_t>(in[0]) | (static_cast<uint32_t>(in[1]) << 8) |
           (static_cast<uint32_t>(in[2]) << 16) | (static_cast<uint32_t>(in[3]) << 24);
}

CommandBytes command_with_u16(uint16_t word, uint16_t value) {
    uint8_t bytes[2];
    put_u16_le(bytes, value);
    return build_command(word, bytes, sizeof(bytes));
}

bool command_header_at(const uint8_t* data, size_t length, size_t offset) {
    return offset + kMarkerSize <= length && memcmp(data + offset, kCommandHeader, kMarkerSize) == 0;
}

Ack ack_from_payload(const uint8_t* status_and_value, size_t length) {
    size_t value_length = length - kStatusSize;
    if (value_length > kMaxAckValueSize) {
        value_length = kMaxAckValueSize;
    }
    Ack ack{};
    ack.found = true;
    ack.success = get_u16_le(status_and_value) == 0;
    memcpy(ack.value, status_and_value + kStatusSize, value_length);
    ack.value_length = static_cast<uint8_t>(value_length);
    return ack;
}

Ack ack_at(const uint8_t* data, size_t length, size_t offset, uint16_t command_word) {
    size_t body = offset + kMarkerSize + kLengthFieldSize;
    if (body > length) {
        return Ack{};
    }
    size_t in_frame = get_u16_le(data + offset + kMarkerSize);
    size_t tail = body + in_frame;
    bool complete = in_frame >= kWordSize + kStatusSize && tail + kMarkerSize <= length;
    if (!complete || memcmp(data + tail, kCommandTail, kMarkerSize) != 0) {
        return Ack{};
    }
    if (get_u16_le(data + body) != (command_word | kAckFlag)) {
        return Ack{};
    }
    return ack_from_payload(data + body + kWordSize, in_frame - kWordSize);
}

}  // namespace

CommandBytes build_command(uint16_t word, const uint8_t* value, size_t value_length) {
    CommandBytes command{};
    if (value_length > kMaxCommandValueSize) {
        return command;
    }
    memcpy(command.bytes, kCommandHeader, kMarkerSize);
    put_u16_le(command.bytes + kMarkerSize, static_cast<uint16_t>(kWordSize + value_length));
    put_u16_le(command.bytes + kMarkerSize + kLengthFieldSize, word);
    if (value_length > 0) {
        memcpy(command.bytes + kValueOffset, value, value_length);
    }
    memcpy(command.bytes + kValueOffset + value_length, kCommandTail, kMarkerSize);
    command.length = static_cast<uint8_t>(kValueOffset + value_length + kMarkerSize);
    return command;
}

CommandBytes enable_config_command() {
    return command_with_u16(kCmdEnableConfig, 0x0001);
}

CommandBytes multi_target_command() {
    return build_command(kCmdMultiTarget, nullptr, 0);
}

CommandBytes read_firmware_command() {
    return build_command(kCmdReadFirmware, nullptr, 0);
}

CommandBytes bluetooth_off_command() {
    return command_with_u16(kCmdBluetooth, 0x0000);
}

CommandBytes restart_command() {
    return build_command(kCmdRestart, nullptr, 0);
}

Ack find_ack(const uint8_t* data, size_t length, uint16_t command_word) {
    for (size_t offset = 0; offset < length; ++offset) {
        if (!command_header_at(data, length, offset)) {
            continue;
        }
        Ack ack = ack_at(data, length, offset, command_word);
        if (ack.found) {
            return ack;
        }
    }
    return Ack{};
}

FirmwareText firmware_text_from_ack(const Ack& ack) {
    FirmwareText firmware{};
    if (!ack.found || !ack.success || ack.value_length < kFirmwareValueSize) {
        return firmware;
    }
    uint16_t major = get_u16_le(ack.value + 2);
    uint32_t minor = get_u32_le(ack.value + 4);
    snprintf(firmware.text, sizeof(firmware.text), "V%X.%02X.%08lX", static_cast<unsigned>(major >> 8),
             static_cast<unsigned>(major & 0xFF), static_cast<unsigned long>(minor));
    return firmware;
}

uint8_t baud_index_for(uint32_t baud) {
    for (uint8_t index = 1; index < kBaudIndexCount; ++index) {
        if (kBaudByIndex[index] == baud) {
            return index;
        }
    }
    return 0;
}
