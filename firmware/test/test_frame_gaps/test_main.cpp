#include <unity.h>

#include "../test_entry.h"
#include "frame_gaps.h"

namespace {

FrameGaps fed(const uint32_t* times, uint32_t count) {
    FrameGaps gaps = frame_gaps_start();
    for (uint32_t i = 0; i < count; ++i) {
        gaps = frame_gaps_with(gaps, times[i]);
    }
    return gaps;
}

}  // namespace

void setUp() {}
void tearDown() {}

void test_the_first_frame_measures_no_gap() {
    const uint32_t times[] = {500};
    FrameGaps gaps = fed(times, 1);
    TEST_ASSERT_EQUAL_UINT32(0, gaps.count);
    TEST_ASSERT_TRUE(gaps.has_last);
}

void test_gaps_keep_their_minimum_and_maximum() {
    const uint32_t times[] = {1000, 1100, 1198, 1301};
    FrameGaps gaps = fed(times, 4);
    TEST_ASSERT_EQUAL_UINT32(3, gaps.count);
    TEST_ASSERT_EQUAL_UINT32(98, gaps.min_gap_ms);
    TEST_ASSERT_EQUAL_UINT32(103, gaps.max_gap_ms);
}

void test_a_new_window_still_measures_its_first_gap() {
    const uint32_t times[] = {1000, 1100};
    FrameGaps gaps = frame_gaps_new_window(fed(times, 2));
    TEST_ASSERT_EQUAL_UINT32(0, gaps.count);
    gaps = frame_gaps_with(gaps, 1250);
    TEST_ASSERT_EQUAL_UINT32(1, gaps.count);
    TEST_ASSERT_EQUAL_UINT32(150, gaps.min_gap_ms);
    TEST_ASSERT_EQUAL_UINT32(150, gaps.max_gap_ms);
}

void test_gaps_survive_a_millis_wrap() {
    const uint32_t times[] = {0xFFFFFFF0u, 0x00000050u};
    TEST_ASSERT_EQUAL_UINT32(96, fed(times, 2).max_gap_ms);
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_the_first_frame_measures_no_gap);
    RUN_TEST(test_gaps_keep_their_minimum_and_maximum);
    RUN_TEST(test_a_new_window_still_measures_its_first_gap);
    RUN_TEST(test_gaps_survive_a_millis_wrap);
    return UNITY_END();
}
