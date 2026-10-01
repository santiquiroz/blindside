#pragma once

#include <stddef.h>
#include <stdint.h>

constexpr uint16_t kCmdEnableConfig = 0x00FF;
constexpr uint16_t kCmdEndConfig = 0x00FE;
constexpr uint16_t kCmdMultiTarget = 0x0090;
constexpr uint16_t kCmdReadFirmware = 0x00A0;
constexpr uint16_t kCmdSetBaud = 0x00A1;
constexpr uint16_t kCmdRestart = 0x00A3;
constexpr uint16_t kCmdBluetooth = 0x00A4;

constexpr size_t kMaxCommandValueSize = 4;
constexpr size_t kMaxCommandSize = 12 + kMaxCommandValueSize;
constexpr size_t kMaxAckValueSize = 16;
constexpr size_t kFirmwareTextSize = 24;

constexpr uint32_t kBaudProbeOrder[] = {256000, 115200, 460800, 230400, 57600, 38400, 19200, 9600};
constexpr size_t kBaudProbeCount = sizeof(kBaudProbeOrder) / sizeof(kBaudProbeOrder[0]);

struct CommandBytes {
    uint8_t bytes[kMaxCommandSize];
    uint8_t length;
};

struct Ack {
    bool found;
    bool success;
    uint8_t value[kMaxAckValueSize];
    uint8_t value_length;
};

struct FirmwareText {
    char text[kFirmwareTextSize];
};

CommandBytes build_command(uint16_t word, const uint8_t* value, size_t value_length);
CommandBytes enable_config_command();
CommandBytes multi_target_command();
CommandBytes read_firmware_command();
CommandBytes bluetooth_off_command();
CommandBytes restart_command();
Ack find_ack(const uint8_t* data, size_t length, uint16_t command_word);
FirmwareText firmware_text_from_ack(const Ack& ack);
uint8_t baud_index_for(uint32_t baud);
