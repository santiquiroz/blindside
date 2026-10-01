#include "diag_format.h"

#include <stdio.h>

namespace {

constexpr size_t kPairTextSize = 8;
constexpr uint32_t kMsPerSecond = 1000;

struct PairText {
    char text[kPairTextSize];
};

LinkDiagText idle_link(uint8_t slot) {
    LinkDiagText line{};
    snprintf(line.text, sizeof(line.text), " link%u[-]", static_cast<unsigned>(slot));
    return line;
}

LinkDiagText connected_link(uint8_t slot, const LinkDiag& link) {
    LinkDiagText line{};
    snprintf(line.text, sizeof(line.text),
             " link%u[role=%s trusted=%d sub=%d mtu=%u itvl=%u lat=%u sup=%u sent=%lu dropped=%lu]",
             static_cast<unsigned>(slot), role_name(link.role), link.trusted ? 1 : 0, link.subscribed ? 1 : 0,
             static_cast<unsigned>(link.mtu), static_cast<unsigned>(link.params.interval_units),
             static_cast<unsigned>(link.params.latency), static_cast<unsigned>(link.params.supervision_units),
             static_cast<unsigned long>(link.sent), static_cast<unsigned long>(link.dropped));
    return line;
}

uint32_t window_seconds_left(const PairingWindow& window, uint32_t now_ms) {
    uint32_t elapsed_ms = now_ms - window.opened_ms;
    if (!window.open || elapsed_ms >= kPairingWindowMs) {
        return 0;
    }
    return (kPairingWindowMs - elapsed_ms + kMsPerSecond - 1) / kMsPerSecond;
}

// Without a bond the window stays open until the first one (pairing_rules), so it has no countdown.
PairText pairing_text(const DiagTail& tail) {
    PairText pair{};
    if (tail.window.open && tail.bonds == 0) {
        snprintf(pair.text, sizeof(pair.text), "open");
        return pair;
    }
    snprintf(pair.text, sizeof(pair.text), "%lu",
             static_cast<unsigned long>(window_seconds_left(tail.window, tail.now_ms)));
    return pair;
}

}  // namespace

LinkDiagText format_link_diag(uint8_t slot, const LinkDiag& link) {
    return link.connected ? connected_link(slot, link) : idle_link(slot);
}

DiagTailText format_diag_tail(const DiagTail& tail) {
    DiagTailText line{};
    snprintf(line.text, sizeof(line.text), " bonds=%u pair=%s disc=%d acl=%u hstk=%lu",
             static_cast<unsigned>(tail.bonds), pairing_text(tail).text, tail.disconnect_reason,
             static_cast<unsigned>(tail.free_acl_buffers), static_cast<unsigned long>(tail.host_stack_free));
    return line;
}
