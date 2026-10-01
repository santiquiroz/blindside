#include <unity.h>

#include "../test_entry.h"
#include "link_fanout.h"

namespace {

constexpr uint8_t kPhoneSlot = 0;
constexpr uint8_t kWatchSlot = 1;

struct LinkPair {
    FanoutLink links[kMaxLinks];
};

FanoutLink streaming(LinkRole role, uint32_t link_id) {
    return FanoutLink{true, role, link_id, 255};
}

FanoutLink not_streaming(uint32_t link_id) {
    return FanoutLink{false, LinkRole::Watch, link_id, 23};
}

LinkPair watch_and_phone() {
    return LinkPair{{streaming(LinkRole::Phone, 1), streaming(LinkRole::Watch, 2)}};
}

Fanout loaded(const LinkPair& pair, size_t packets) {
    Fanout synced = fanout_synced(Fanout{}, pair.links);
    return fanout_refilled(synced, packets, pair.links);
}

Fanout notified(const Fanout& fanout, const NotifyOrder& order, bool sent) {
    return fanout_after_notify(fanout, next_delivery(fanout, order), sent);
}

void assert_delivery(const Delivery& delivery, uint8_t slot, size_t packet) {
    TEST_ASSERT_TRUE(delivery.found);
    TEST_ASSERT_EQUAL_UINT8(slot, delivery.slot);
    TEST_ASSERT_EQUAL_UINT(packet, delivery.packet);
}

}  // namespace

void setUp() {}
void tearDown() {}

void test_the_watch_is_notified_before_the_phone() {
    LinkPair pair = watch_and_phone();
    NotifyOrder order = notify_order(pair.links);
    TEST_ASSERT_EQUAL_UINT(2, order.count);
    TEST_ASSERT_EQUAL_UINT8(kWatchSlot, order.slots[0]);
    TEST_ASSERT_EQUAL_UINT8(kPhoneSlot, order.slots[1]);
}

void test_two_watch_links_go_oldest_first() {
    LinkPair pair{{streaming(LinkRole::Watch, 5), streaming(LinkRole::Watch, 3)}};
    NotifyOrder order = notify_order(pair.links);
    TEST_ASSERT_EQUAL_UINT8(1, order.slots[0]);
    TEST_ASSERT_EQUAL_UINT8(0, order.slots[1]);
}

void test_links_without_a_stream_are_left_out() {
    LinkPair pair{{not_streaming(4), streaming(LinkRole::Phone, 2)}};
    NotifyOrder order = notify_order(pair.links);
    TEST_ASSERT_EQUAL_UINT(1, order.count);
    TEST_ASSERT_EQUAL_UINT8(1, order.slots[0]);
    TEST_ASSERT_EQUAL_UINT(0, notify_order(LinkPair{{not_streaming(1), not_streaming(2)}}.links).count);
}

void test_each_packet_goes_to_the_watch_then_the_phone() {
    LinkPair pair = watch_and_phone();
    NotifyOrder order = notify_order(pair.links);
    Fanout fanout = loaded(pair, 2);
    assert_delivery(next_delivery(fanout, order), kWatchSlot, 0);
    fanout = notified(fanout, order, true);
    assert_delivery(next_delivery(fanout, order), kPhoneSlot, 0);
    fanout = notified(fanout, order, true);
    assert_delivery(next_delivery(fanout, order), kWatchSlot, 1);
    fanout = notified(fanout, order, true);
    assert_delivery(next_delivery(fanout, order), kPhoneSlot, 1);
    fanout = notified(fanout, order, true);
    TEST_ASSERT_FALSE(next_delivery(fanout, order).found);
    TEST_ASSERT_FALSE(fanout_pending(fanout, order));
    TEST_ASSERT_EQUAL_UINT32(2, fanout.links[kWatchSlot].sent);
    TEST_ASSERT_EQUAL_UINT32(2, fanout.links[kPhoneSlot].sent);
}

void test_a_full_queue_retries_the_watch_and_never_drops_it() {
    LinkPair pair = watch_and_phone();
    NotifyOrder order = notify_order(pair.links);
    Fanout fanout = notified(loaded(pair, 2), order, false);
    TEST_ASSERT_TRUE(next_delivery(loaded(pair, 2), order).protected_link);
    assert_delivery(next_delivery(fanout, order), kWatchSlot, 0);
    TEST_ASSERT_EQUAL_UINT32(1, fanout.failures);
    TEST_ASSERT_EQUAL_UINT32(0, fanout.links[kWatchSlot].dropped);
    TEST_ASSERT_TRUE(fanout_pending(fanout, order));
}

void test_a_full_queue_drops_the_phone_packet_and_moves_on() {
    LinkPair pair = watch_and_phone();
    NotifyOrder order = notify_order(pair.links);
    Fanout fanout = notified(loaded(pair, 2), order, true);
    TEST_ASSERT_FALSE(next_delivery(fanout, order).protected_link);
    fanout = notified(fanout, order, false);
    TEST_ASSERT_EQUAL_UINT32(1, fanout.links[kPhoneSlot].dropped);
    TEST_ASSERT_EQUAL_UINT32(0, fanout.failures);
    assert_delivery(next_delivery(fanout, order), kWatchSlot, 1);
}

void test_a_phone_at_its_backlog_cap_is_dropped_without_a_notify() {
    LinkPair pair = watch_and_phone();
    NotifyOrder order = notify_order(pair.links);
    Fanout fanout = notified(loaded(pair, 2), order, true);
    Delivery phone = next_delivery(fanout, order);
    TEST_ASSERT_TRUE(notify_allowed(phone, kUnprotectedBacklogCap - 1));
    TEST_ASSERT_FALSE(notify_allowed(phone, kUnprotectedBacklogCap));
    fanout = fanout_after_notify(fanout, phone, notify_allowed(phone, kUnprotectedBacklogCap));
    TEST_ASSERT_EQUAL_UINT32(1, fanout.links[kPhoneSlot].dropped);
    TEST_ASSERT_EQUAL_UINT32(0, fanout.failures);
    assert_delivery(next_delivery(fanout, order), kWatchSlot, 1);
}

void test_the_first_link_in_notify_order_is_never_capped() {
    LinkPair pair = watch_and_phone();
    Delivery watch = next_delivery(loaded(pair, 1), notify_order(pair.links));
    TEST_ASSERT_TRUE(watch.protected_link);
    TEST_ASSERT_TRUE(notify_allowed(watch, 1000));
}

void test_deliveries_name_the_connection_they_were_planned_for() {
    LinkPair pair = watch_and_phone();
    NotifyOrder order = notify_order(pair.links);
    Fanout fanout = loaded(pair, 1);
    TEST_ASSERT_EQUAL_UINT32(2, next_delivery(fanout, order).link_id);
    TEST_ASSERT_EQUAL_UINT32(1, next_delivery(notified(fanout, order, true), order).link_id);
}

void test_the_phone_alone_is_retried_like_the_watch() {
    LinkPair pair{{streaming(LinkRole::Phone, 1), not_streaming(0)}};
    NotifyOrder order = notify_order(pair.links);
    Fanout fanout = notified(loaded(pair, 1), order, false);
    TEST_ASSERT_EQUAL_UINT32(0, fanout.links[kPhoneSlot].dropped);
    TEST_ASSERT_EQUAL_UINT32(1, fanout.failures);
    assert_delivery(next_delivery(fanout, order), kPhoneSlot, 0);
}

void test_a_link_that_starts_streaming_mid_outbox_joins_at_the_next_cut() {
    LinkPair before{{not_streaming(1), streaming(LinkRole::Watch, 2)}};
    Fanout fanout = loaded(before, 2);
    LinkPair after = watch_and_phone();
    NotifyOrder order = notify_order(after.links);
    fanout = fanout_synced(fanout, after.links);
    assert_delivery(next_delivery(fanout, order), kWatchSlot, 0);
    fanout = notified(notified(fanout, order, true), order, true);
    TEST_ASSERT_FALSE(fanout_pending(fanout, order));
    TEST_ASSERT_EQUAL_UINT32(0, fanout.links[kPhoneSlot].sent);
    fanout = fanout_refilled(fanout, 1, after.links);
    assert_delivery(next_delivery(fanout, order), kWatchSlot, 0);
}

void test_a_rival_that_never_subscribes_does_not_touch_the_watch_stream() {
    LinkPair pair{{not_streaming(9), streaming(LinkRole::Watch, 2)}};
    NotifyOrder order = notify_order(pair.links);
    Fanout fanout = loaded(pair, 3);
    for (size_t i = 0; i < 3; ++i) {
        assert_delivery(next_delivery(fanout, order), kWatchSlot, i);
        fanout = notified(fanout, order, true);
    }
    TEST_ASSERT_EQUAL_UINT32(3, fanout.links[kWatchSlot].sent);
    TEST_ASSERT_EQUAL_UINT32(0, fanout.links[0].sent);
    TEST_ASSERT_EQUAL_UINT32(0, fanout.links[0].dropped);
}

void test_counters_restart_with_each_new_connection() {
    LinkPair pair = watch_and_phone();
    NotifyOrder order = notify_order(pair.links);
    Fanout fanout = notified(notified(loaded(pair, 1), order, true), order, false);
    TEST_ASSERT_EQUAL_UINT32(1, fanout.links[kPhoneSlot].dropped);
    LinkPair reconnected{{streaming(LinkRole::Phone, 7), streaming(LinkRole::Watch, 2)}};
    fanout = fanout_synced(fanout, reconnected.links);
    TEST_ASSERT_EQUAL_UINT32(7, fanout.links[kPhoneSlot].link_id);
    TEST_ASSERT_EQUAL_UINT32(0, fanout.links[kPhoneSlot].sent);
    TEST_ASSERT_EQUAL_UINT32(0, fanout.links[kPhoneSlot].dropped);
    TEST_ASSERT_EQUAL_UINT32(1, fanout.links[kWatchSlot].sent);
}

void test_clearing_keeps_the_counters() {
    LinkPair pair = watch_and_phone();
    NotifyOrder order = notify_order(pair.links);
    Fanout fanout = fanout_cleared(notified(loaded(pair, 2), order, true));
    TEST_ASSERT_FALSE(fanout_pending(fanout, order));
    TEST_ASSERT_EQUAL_UINT32(1, fanout.links[kWatchSlot].sent);
}

void test_bundles_use_the_smallest_mtu_of_the_streaming_links() {
    LinkPair pair{{FanoutLink{true, LinkRole::Phone, 1, 247}, FanoutLink{true, LinkRole::Watch, 2, 255}}};
    TEST_ASSERT_EQUAL_UINT16(247, gated_min_mtu(pair.links));
    LinkPair one{{not_streaming(1), FanoutLink{true, LinkRole::Watch, 2, 255}}};
    TEST_ASSERT_EQUAL_UINT16(255, gated_min_mtu(one.links));
    TEST_ASSERT_EQUAL_UINT16(0, gated_min_mtu(LinkPair{{not_streaming(1), not_streaming(2)}}.links));
}

void test_tallies_belong_only_to_the_current_connection() {
    LinkDelivery delivery{4, 0, 10, 2};
    LinkTally current = tally_for_link(delivery, 4);
    TEST_ASSERT_EQUAL_UINT32(10, current.sent);
    TEST_ASSERT_EQUAL_UINT32(2, current.dropped);
    TEST_ASSERT_EQUAL_UINT32(0, tally_for_link(delivery, 5).sent);
    TEST_ASSERT_EQUAL_UINT32(0, tally_for_link(LinkDelivery{0, 0, 3, 0}, 0).sent);
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_the_watch_is_notified_before_the_phone);
    RUN_TEST(test_two_watch_links_go_oldest_first);
    RUN_TEST(test_links_without_a_stream_are_left_out);
    RUN_TEST(test_each_packet_goes_to_the_watch_then_the_phone);
    RUN_TEST(test_a_full_queue_retries_the_watch_and_never_drops_it);
    RUN_TEST(test_a_full_queue_drops_the_phone_packet_and_moves_on);
    RUN_TEST(test_a_phone_at_its_backlog_cap_is_dropped_without_a_notify);
    RUN_TEST(test_the_first_link_in_notify_order_is_never_capped);
    RUN_TEST(test_deliveries_name_the_connection_they_were_planned_for);
    RUN_TEST(test_the_phone_alone_is_retried_like_the_watch);
    RUN_TEST(test_a_link_that_starts_streaming_mid_outbox_joins_at_the_next_cut);
    RUN_TEST(test_a_rival_that_never_subscribes_does_not_touch_the_watch_stream);
    RUN_TEST(test_counters_restart_with_each_new_connection);
    RUN_TEST(test_clearing_keeps_the_counters);
    RUN_TEST(test_bundles_use_the_smallest_mtu_of_the_streaming_links);
    RUN_TEST(test_tallies_belong_only_to_the_current_connection);
    return UNITY_END();
}
