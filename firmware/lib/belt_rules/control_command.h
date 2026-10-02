#pragma once

#include <stddef.h>
#include <stdint.h>

constexpr uint8_t kControlRestartRadar = 0x01;
constexpr uint8_t kControlIdentify = 0x03;
constexpr uint8_t kControlSessionActive = 0x04;
constexpr uint8_t kControlOpenPairingWindow = 0x05;
constexpr uint8_t kControlSetRole = 0x06;
constexpr size_t kMaxControlSize = 4;

enum class ControlKind : uint8_t {
    None,
    Invalid,
    RestartRadar,
    Identify,
    SessionActive,
    OpenPairingWindow,
    SetRole,
};

enum class ControlAction : uint8_t {
    Ignore,
    RejectUntrusted,
    RejectInvalid,
    RestartRadar,
    Identify,
    SetSession,
    OpenPairingWindow,
    SetRole,
};

struct ControlCommand {
    ControlKind kind;
    uint8_t argument;
};

ControlCommand parse_control(const uint8_t* bytes, size_t length);
bool identify_allowed(bool session_active);
ControlAction control_action(ControlKind kind, bool link_trusted, bool session_running);
