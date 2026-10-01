#pragma once

#include <stddef.h>
#include <stdint.h>

constexpr uint8_t kControlRestartRadar = 0x01;
constexpr uint8_t kControlIdentify = 0x03;
constexpr uint8_t kControlSessionActive = 0x04;
constexpr size_t kMaxControlSize = 4;

enum class ControlKind : uint8_t { None, Invalid, RestartRadar, Identify, SessionActive };

struct ControlCommand {
    ControlKind kind;
    uint8_t argument;
};

ControlCommand parse_control(const uint8_t* bytes, size_t length);
bool identify_allowed(bool session_active);
