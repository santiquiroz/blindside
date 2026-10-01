#include "mpu6050_registers.h"

namespace {

constexpr uint8_t kWakeWithGyroClock = 0x01;
// 1 kHz internal rate so every 200 Hz poll reads a fresh DLPF-filtered value.
constexpr uint8_t kSampleRateDivider1kHz = 0x00;
constexpr uint8_t kDlpf42Hz = 0x03;
constexpr uint8_t kGyroRange500Dps = 0x08;
constexpr uint8_t kAccelRange8G = 0x10;
constexpr uint8_t kAccelDlpf42Hz = 0x03;
constexpr uint8_t kBusErrorLow = 0x00;
constexpr uint8_t kBusErrorHigh = 0xFF;
constexpr uint8_t kBurstOffsets[kImuAxisCount] = {0, 2, 4, 8, 10, 12};

MpuConfigPlan with_write(const MpuConfigPlan& plan, uint8_t reg, uint8_t value) {
    MpuConfigPlan next = plan;
    next.writes[next.count] = RegisterWrite{reg, value};
    next.count = static_cast<uint8_t>(next.count + 1);
    return next;
}

int16_t big_endian_i16(const uint8_t* bytes) {
    return static_cast<int16_t>(static_cast<uint16_t>((bytes[0] << 8) | bytes[1]));
}

}  // namespace

bool who_am_i_is_usable(uint8_t who_am_i) {
    return who_am_i != kBusErrorLow && who_am_i != kBusErrorHigh;
}

bool needs_accel_config2(uint8_t who_am_i) {
    return who_am_i != kMpuGenuineWhoAmI;
}

MpuConfigPlan mpu_config_plan(uint8_t who_am_i) {
    MpuConfigPlan plan{};
    plan = with_write(plan, kMpuRegPowerManagement1, kWakeWithGyroClock);
    plan = with_write(plan, kMpuRegSampleRateDivider, kSampleRateDivider1kHz);
    plan = with_write(plan, kMpuRegConfig, kDlpf42Hz);
    plan = with_write(plan, kMpuRegGyroConfig, kGyroRange500Dps);
    plan = with_write(plan, kMpuRegAccelConfig, kAccelRange8G);
    if (!needs_accel_config2(who_am_i)) {
        return plan;
    }
    return with_write(plan, kMpuRegAccelConfig2, kAccelDlpf42Hz);
}

ImuReading reading_from_burst(const uint8_t* burst) {
    ImuReading reading{};
    for (uint8_t axis = 0; axis < kImuAxisCount; ++axis) {
        reading.axes[axis] = big_endian_i16(burst + kBurstOffsets[axis]);
    }
    return reading;
}
