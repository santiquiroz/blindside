#pragma once

#include <stddef.h>
#include <stdint.h>

#include "bundler.h"
#include "link_roles.h"
#include "pairing_rules.h"

constexpr size_t kLinkDiagTextSize = 128;
constexpr size_t kDiagTailTextSize = 96;

struct LinkDiag {
    bool connected;
    LinkRole role;
    bool trusted;
    bool subscribed;
    uint16_t mtu;
    LinkParams params;
    uint32_t sent;
    uint32_t dropped;
};

struct LinkDiagText {
    char text[kLinkDiagTextSize];
};

struct DiagTail {
    uint8_t bonds;
    PairingWindow window;
    uint32_t now_ms;
    int disconnect_reason;
    uint16_t free_acl_buffers;
    uint32_t host_stack_free;
};

struct DiagTailText {
    char text[kDiagTailTextSize];
};

LinkDiagText format_link_diag(uint8_t slot, const LinkDiag& link);
DiagTailText format_diag_tail(const DiagTail& tail);
