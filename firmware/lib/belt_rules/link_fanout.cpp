#include "link_fanout.h"

namespace {

bool goes_before(const FanoutLink& link, const FanoutLink& other) {
    if (link.role != other.role) {
        return link.role == LinkRole::Watch;
    }
    return link.link_id < other.link_id;
}

size_t insert_position(const NotifyOrder& order, const FanoutLink* links, uint8_t slot) {
    size_t position = 0;
    while (position < order.count && !goes_before(links[slot], links[order.slots[position]])) {
        position++;
    }
    return position;
}

NotifyOrder with_slot_in_order(const NotifyOrder& order, const FanoutLink* links, uint8_t slot) {
    NotifyOrder next = order;
    size_t position = insert_position(order, links, slot);
    for (size_t i = next.count; i > position; --i) {
        next.slots[i] = next.slots[i - 1];
    }
    next.slots[position] = slot;
    next.count++;
    return next;
}

Delivery delivery_for(const Fanout& fanout, uint8_t slot, bool protected_link) {
    const LinkDelivery& link = fanout.links[slot];
    return Delivery{true, slot, link.next, protected_link, link.link_id};
}

Delivery earlier_delivery(const Delivery& best, const Delivery& candidate) {
    return !best.found || candidate.packet < best.packet ? candidate : best;
}

Fanout after_sent(const Fanout& fanout, uint8_t slot) {
    Fanout next = fanout;
    next.links[slot].next++;
    next.links[slot].sent++;
    return next;
}

Fanout after_failure(const Fanout& fanout, const Delivery& delivery) {
    Fanout next = fanout;
    if (delivery.protected_link) {
        next.failures++;
        return next;
    }
    next.links[delivery.slot].next++;
    next.links[delivery.slot].dropped++;
    return next;
}

}  // namespace

NotifyOrder notify_order(const FanoutLink* links) {
    NotifyOrder order{};
    for (uint8_t slot = 0; slot < kMaxLinks; ++slot) {
        if (links[slot].gated) {
            order = with_slot_in_order(order, links, slot);
        }
    }
    return order;
}

uint16_t gated_min_mtu(const FanoutLink* links) {
    uint16_t smallest = 0;
    for (size_t slot = 0; slot < kMaxLinks; ++slot) {
        bool smaller = smallest == 0 || links[slot].mtu < smallest;
        if (links[slot].gated && smaller) {
            smallest = links[slot].mtu;
        }
    }
    return smallest;
}

Fanout fanout_synced(const Fanout& fanout, const FanoutLink* links) {
    Fanout next = fanout;
    for (size_t slot = 0; slot < kMaxLinks; ++slot) {
        if (links[slot].link_id != fanout.links[slot].link_id) {
            next.links[slot] = LinkDelivery{links[slot].link_id, fanout.count, 0, 0};
        }
    }
    return next;
}

Fanout fanout_refilled(const Fanout& fanout, size_t packet_count, const FanoutLink* links) {
    Fanout next = fanout;
    next.count = packet_count;
    for (size_t slot = 0; slot < kMaxLinks; ++slot) {
        next.links[slot].next = links[slot].gated ? 0 : packet_count;
    }
    return next;
}

Fanout fanout_cleared(const Fanout& fanout) {
    Fanout next = fanout;
    next.count = 0;
    for (LinkDelivery& link : next.links) {
        link.next = 0;
    }
    return next;
}

bool fanout_pending(const Fanout& fanout, const NotifyOrder& order) {
    for (size_t i = 0; i < order.count; ++i) {
        if (fanout.links[order.slots[i]].next < fanout.count) {
            return true;
        }
    }
    return false;
}

Delivery next_delivery(const Fanout& fanout, const NotifyOrder& order) {
    Delivery best{};
    for (size_t i = 0; i < order.count; ++i) {
        uint8_t slot = order.slots[i];
        if (fanout.links[slot].next < fanout.count) {
            best = earlier_delivery(best, delivery_for(fanout, slot, i == 0));
        }
    }
    return best;
}

// NimBLE's notify only fails once shared buffers run out, so a stalled phone is capped before it can starve the watch.
bool notify_allowed(const Delivery& delivery, uint16_t link_backlog) {
    return delivery.protected_link || link_backlog < kUnprotectedBacklogCap;
}

Fanout fanout_after_notify(const Fanout& fanout, const Delivery& delivery, bool sent) {
    if (!delivery.found) {
        return fanout;
    }
    return sent ? after_sent(fanout, delivery.slot) : after_failure(fanout, delivery);
}

LinkTally tally_for_link(const LinkDelivery& delivery, uint32_t link_id) {
    bool current = link_id != 0 && delivery.link_id == link_id;
    return current ? LinkTally{delivery.sent, delivery.dropped} : LinkTally{0, 0};
}
