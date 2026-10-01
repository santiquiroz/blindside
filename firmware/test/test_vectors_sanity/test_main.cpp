#include <unity.h>

#include "../test_entry.h"
#include "../vectors.h"

void setUp() {}
void tearDown() {}

void test_generated_vectors_have_expected_sizes() {
    TEST_ASSERT_EQUAL_UINT(30, VEC_LD2450_OFFICIAL_FRAME_LEN);
    TEST_ASSERT_EQUAL_UINT(39, VEC_BUNDLE_ONE_RADAR_LEN);
    TEST_ASSERT_EQUAL_UINT(242, VEC_BUNDLE_TYPICAL_LEN);
    TEST_ASSERT_EQUAL_UINT(47, VEC_BUNDLE_WITH_LINK_LEN);
    TEST_ASSERT_EQUAL_UINT(14, VEC_CMD_ENABLE_CONFIG_LEN);
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_generated_vectors_have_expected_sizes);
    return UNITY_END();
}
