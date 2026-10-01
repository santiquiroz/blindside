#include <string.h>
#include <unity.h>

#include "../test_entry.h"
#include "../vectors.h"
#include "ld2450_frame.h"

namespace {

struct FeedOutcome {
    FrameParser parser;
    int ok_count;
    int bad_count;
    ParsedFrame last_frame;
};

FeedOutcome tallied(const FeedOutcome& outcome, const ParserStep& step) {
    FeedOutcome next = outcome;
    next.parser = step.parser;
    next.ok_count += step.event == FrameEvent::FrameOk ? 1 : 0;
    next.bad_count += step.event == FrameEvent::FrameBad ? 1 : 0;
    if (step.event == FrameEvent::FrameOk) {
        next.last_frame = step.frame;
    }
    return next;
}

FeedOutcome feed_bytes(const FeedOutcome& start, const uint8_t* bytes, size_t length, uint32_t t_ms) {
    FeedOutcome outcome = start;
    for (size_t i = 0; i < length; ++i) {
        outcome = tallied(outcome, frame_parser_feed(outcome.parser, bytes[i], t_ms));
    }
    return outcome;
}

FeedOutcome fresh_outcome() {
    FeedOutcome outcome{};
    outcome.parser = frame_parser_start();
    return outcome;
}

}  // namespace

void setUp() {}
void tearDown() {}

void test_official_frame_yields_one_frame_with_its_targets() {
    FeedOutcome outcome = feed_bytes(fresh_outcome(), VEC_LD2450_OFFICIAL_FRAME, VEC_LD2450_OFFICIAL_FRAME_LEN, 500);
    TEST_ASSERT_EQUAL_INT(1, outcome.ok_count);
    TEST_ASSERT_EQUAL_INT(0, outcome.bad_count);
    TEST_ASSERT_EQUAL_UINT32(500, outcome.last_frame.t_ms);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(VEC_LD2450_OFFICIAL_FRAME + 4, outcome.last_frame.targets, 24);
}

void test_frame_time_is_taken_when_the_header_completes() {
    FeedOutcome outcome = feed_bytes(fresh_outcome(), VEC_LD2450_OFFICIAL_FRAME, 3, 100);
    outcome = feed_bytes(outcome, VEC_LD2450_OFFICIAL_FRAME + 3, 1, 101);
    outcome = feed_bytes(outcome, VEC_LD2450_OFFICIAL_FRAME + 4, VEC_LD2450_OFFICIAL_FRAME_LEN - 4, 140);
    TEST_ASSERT_EQUAL_INT(1, outcome.ok_count);
    TEST_ASSERT_EQUAL_UINT32(101, outcome.last_frame.t_ms);
}

void test_garbage_before_frame_is_skipped() {
    const uint8_t garbage[] = {0x00, 0x55, 0xAA, 0x12, 0xAA, 0xFF, 0x01, 0xCC};
    FeedOutcome outcome = feed_bytes(fresh_outcome(), garbage, sizeof(garbage), 10);
    outcome = feed_bytes(outcome, VEC_LD2450_OFFICIAL_FRAME, VEC_LD2450_OFFICIAL_FRAME_LEN, 20);
    TEST_ASSERT_EQUAL_INT(1, outcome.ok_count);
    TEST_ASSERT_EQUAL_INT(0, outcome.bad_count);
}

void test_bad_tail_counts_a_bad_frame_then_recovers() {
    uint8_t broken[30];
    memcpy(broken, VEC_LD2450_OFFICIAL_FRAME, sizeof(broken));
    broken[29] = 0x00;
    FeedOutcome outcome = feed_bytes(fresh_outcome(), broken, sizeof(broken), 10);
    outcome = feed_bytes(outcome, VEC_LD2450_OFFICIAL_FRAME, VEC_LD2450_OFFICIAL_FRAME_LEN, 110);
    TEST_ASSERT_EQUAL_INT(1, outcome.bad_count);
    TEST_ASSERT_EQUAL_INT(1, outcome.ok_count);
}

void test_dropped_byte_loses_only_that_frame() {
    uint8_t short_frame[29];
    memcpy(short_frame, VEC_LD2450_OFFICIAL_FRAME, 10);
    memcpy(short_frame + 10, VEC_LD2450_OFFICIAL_FRAME + 11, 19);
    FeedOutcome outcome = feed_bytes(fresh_outcome(), short_frame, sizeof(short_frame), 10);
    outcome = feed_bytes(outcome, VEC_LD2450_OFFICIAL_FRAME, VEC_LD2450_OFFICIAL_FRAME_LEN, 110);
    TEST_ASSERT_EQUAL_INT(1, outcome.bad_count);
    TEST_ASSERT_EQUAL_INT(1, outcome.ok_count);
    TEST_ASSERT_EQUAL_UINT32(110, outcome.last_frame.t_ms);
}

void test_two_back_to_back_frames_both_parse() {
    FeedOutcome outcome = feed_bytes(fresh_outcome(), VEC_LD2450_OFFICIAL_FRAME, VEC_LD2450_OFFICIAL_FRAME_LEN, 10);
    outcome = feed_bytes(outcome, VEC_LD2450_OFFICIAL_FRAME, VEC_LD2450_OFFICIAL_FRAME_LEN, 110);
    TEST_ASSERT_EQUAL_INT(2, outcome.ok_count);
}

void test_resume_offset_finds_a_partial_header_at_the_end() {
    const uint8_t tail_header[] = {0x01, 0x02, 0xAA, 0xFF};
    const uint8_t no_header[] = {0x01, 0x02};
    const uint8_t repeated[] = {0xAA, 0xAA, 0xFF, 0x03};
    TEST_ASSERT_EQUAL_UINT(2, header_resume_offset(tail_header, sizeof(tail_header)));
    TEST_ASSERT_EQUAL_UINT(2, header_resume_offset(no_header, sizeof(no_header)));
    TEST_ASSERT_EQUAL_UINT(1, header_resume_offset(repeated, sizeof(repeated)));
}

void test_bad_frame_counter_saturates() {
    TEST_ASSERT_EQUAL_UINT16(1, bad_frames_after_one_more(0));
    TEST_ASSERT_EQUAL_UINT16(0xFFFF, bad_frames_after_one_more(0xFFFE));
    TEST_ASSERT_EQUAL_UINT16(0xFFFF, bad_frames_after_one_more(0xFFFF));
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_official_frame_yields_one_frame_with_its_targets);
    RUN_TEST(test_frame_time_is_taken_when_the_header_completes);
    RUN_TEST(test_garbage_before_frame_is_skipped);
    RUN_TEST(test_bad_tail_counts_a_bad_frame_then_recovers);
    RUN_TEST(test_dropped_byte_loses_only_that_frame);
    RUN_TEST(test_two_back_to_back_frames_both_parse);
    RUN_TEST(test_resume_offset_finds_a_partial_header_at_the_end);
    RUN_TEST(test_bad_frame_counter_saturates);
    return UNITY_END();
}
