#pragma once

#include <stddef.h>
#include <stdint.h>

#include "imu_accumulator.h"

constexpr uint8_t kMpuDefaultAddress = 0x68;
constexpr uint8_t kMpuAlternateAddress = 0x69;
constexpr uint8_t kMpuRegSampleRateDivider = 0x19;
constexpr uint8_t kMpuRegConfig = 0x1A;
constexpr uint8_t kMpuRegGyroConfig = 0x1B;
constexpr uint8_t kMpuRegAccelConfig = 0x1C;
constexpr uint8_t kMpuRegAccelConfig2 = 0x1D;
constexpr uint8_t kMpuRegAccelXoutH = 0x3B;
constexpr uint8_t kMpuRegPowerManagement1 = 0x6B;
constexpr uint8_t kMpuRegWhoAmI = 0x75;
constexpr uint8_t kMpuGenuineWhoAmI = 0x68;
constexpr uint8_t kMpuBurstSize = 14;
constexpr size_t kMaxMpuConfigWrites = 6;

struct RegisterWrite {
    uint8_t reg;
    uint8_t value;
};

struct MpuConfigPlan {
    RegisterWrite writes[kMaxMpuConfigWrites];
    uint8_t count;
};

bool who_am_i_is_usable(uint8_t who_am_i);
bool needs_accel_config2(uint8_t who_am_i);
MpuConfigPlan mpu_config_plan(uint8_t who_am_i);
ImuReading reading_from_burst(const uint8_t* burst);
