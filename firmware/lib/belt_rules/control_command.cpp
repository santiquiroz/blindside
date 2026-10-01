#include "control_command.h"

namespace {

constexpr uint8_t kMaxBinaryArgument = 1;

ControlCommand invalid_command() {
    return ControlCommand{ControlKind::Invalid, 0};
}

ControlCommand command_with_binary_argument(ControlKind kind, const uint8_t* bytes, size_t length) {
    if (length != 2 || bytes[1] > kMaxBinaryArgument) {
        return invalid_command();
    }
    return ControlCommand{kind, bytes[1]};
}

ControlCommand identify_command(size_t length) {
    return length == 1 ? ControlCommand{ControlKind::Identify, 0} : invalid_command();
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
            return identify_command(length);
        case kControlSessionActive:
            return command_with_binary_argument(ControlKind::SessionActive, bytes, length);
        default:
            return invalid_command();
    }
}

bool identify_allowed(bool session_active) {
    return !session_active;
}
