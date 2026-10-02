#pragma once

#include <stddef.h>
#include <stdint.h>

#include "link_roles.h"

// Packets a link other than the first in notify order may hold in the controller and the host queue at once.
constexpr uint16_t kUnprotectedBacklogCap = 3;

struct FanoutLink {
    bool gated;
    LinkRole role;
    uint32_t link_id;
    uint16_t mtu;
};

struct NotifyOrder {
    uint8_t slots[kMaxLinks];
    size_t count;
};

struct LinkDelivery {
    uint32_t link_id;
    size_t next;
    uint32_t sent;
    uint32_t dropped;
};

struct Fanout {
    size_t count;
    uint32_t failures;
    LinkDelivery links[kMaxLinks];
};

struct Delivery {
    bool found;
    uint8_t slot;
    size_t packet;
    bool protected_link;
    uint32_t link_id;
};

struct LinkTally {
    uint32_t sent;
    uint32_t dropped;
};

NotifyOrder notify_order(const FanoutLink* links);
uint16_t gated_min_mtu(const FanoutLink* links);
Fanout fanout_synced(const Fanout& fanout, const FanoutLink* links);
Fanout fanout_refilled(const Fanout& fanout, size_t packet_count, const FanoutLink* links);
Fanout fanout_cleared(const Fanout& fanout);
bool fanout_pending(const Fanout& fanout, const NotifyOrder& order);
Delivery next_delivery(const Fanout& fanout, const NotifyOrder& order);
bool notify_allowed(const Delivery& delivery, uint16_t link_backlog);
Fanout fanout_after_notify(const Fanout& fanout, const Delivery& delivery, bool sent);
LinkTally tally_for_link(const LinkDelivery& delivery, uint32_t link_id);
