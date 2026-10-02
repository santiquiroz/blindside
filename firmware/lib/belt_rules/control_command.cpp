#include "control_command.h"

namespace {

constexpr uint8_t kMaxBinaryArgument = 1;

// Indexed by ControlKind.
constexpr ControlAction kTrustedActions[] = {
    ControlAction::Ignore,       ControlAction::RejectInvalid, ControlAction::RestartRadar,
    ControlAction::Identify,     ControlAction::SetSession,    ControlAction::OpenPairingWindow,
    ControlAction::SetRole,
};
static_assert(sizeof(kTrustedActions) / sizeof(kTrustedActions[0]) == static_cast<size_t>(ControlKind::SetRole) + 1,
              "one action per control kind");

ControlCommand invalid_command() {
    return ControlCommand{ControlKind::Invalid, 0};
}

ControlCommand command_with_binary_argument(ControlKind kind, const uint8_t* bytes, size_t length) {
    if (length != 2 || bytes[1] > kMaxBinaryArgument) {
        return invalid_command();
    }
    return ControlCommand{kind, bytes[1]};
}

ControlCommand command_without_argument(ControlKind kind, size_t length) {
    return length == 1 ? ControlCommand{kind, 0} : invalid_command();
}

}  // namespace

ControlCommand parse_control(const uint8_t* bytes, size_t length) {
    if (bytes == nullptr || length == 0) {
        return invalid_command();
    }
    switch (bytes[0]) {
        case kControlRestartRadar:
            return command_with_binary_argument(ControlKind::RestartRadar, bytes, length);
        case kControlIdentify:
            return command_without_argument(ControlKind::Identify, length);
        case kControlSessionActive:
            return command_with_binary_argument(ControlKind::SessionActive, bytes, length);
        case kControlOpenPairingWindow:
            return command_without_argument(ControlKind::OpenPairingWindow, length);
        case kControlSetRole:
            return command_with_binary_argument(ControlKind::SetRole, bytes, length);
        default:
            return invalid_command();
    }
}

bool identify_allowed(bool session_active) {
    return !session_active;
}

// A link that authenticated but is not trusted is about to be dropped, so nothing it writes may change the belt.
ControlAction control_action(ControlKind kind, bool link_trusted, bool session_running) {
    if (kind == ControlKind::None) {
        return ControlAction::Ignore;
    }
    if (!link_trusted) {
        return ControlAction::RejectUntrusted;
    }
    if (kind == ControlKind::Identify && !identify_allowed(session_running)) {
        return ControlAction::Ignore;
    }
    return kTrustedActions[static_cast<size_t>(kind)];
}
