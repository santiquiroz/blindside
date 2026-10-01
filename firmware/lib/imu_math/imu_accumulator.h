#pragma once

#include <stddef.h>
#include <stdint.h>

enum ImuAxis : uint8_t { kAx, kAy, kAz, kGx, kGy, kGz, kImuAxisCount };

constexpr uint8_t kReadingsPerSample = 4;
constexpr uint32_t kImuTickPeriodMs = 5;
constexpr uint32_t kImuSamplePeriodMs = kImuTickPeriodMs * kReadingsPerSample;
constexpr uint32_t kImuSampleCentreOffsetMs = kImuSamplePeriodMs / 2;
constexpr uint32_t kMaxTicksPerStep = 64;
constexpr size_t kMaxSamplesPerStep = kMaxTicksPerStep / kReadingsPerSample;

struct ImuReading {
    int16_t axes[kImuAxisCount];
};

struct ImuRead {
    bool ok;
    ImuReading reading;
};

struct GyroSums {
    uint32_t x;
    uint32_t y;
    uint32_t z;
};

struct ImuSample {
    uint32_t t_ms;
    ImuReading mean;
    GyroSums sums;
};

struct ImuAccumulator {
    int32_t totals[kImuAxisCount];
    uint8_t count;
    uint32_t slot_start_ms;
    GyroSums sums;
};

struct AccumulatorStep {
    ImuAccumulator accumulator;
    bool has_sample;
    ImuSample sample;
};

struct ImuChannel {
    ImuAccumulator accumulator;
    ImuReading last_reading;
    uint32_t next_tick_ms;
    uint32_t repeats;
};

struct ImuStep {
    ImuChannel channel;
    ImuSample samples[kMaxSamplesPerStep];
    size_t sample_count;
};

ImuAccumulator accumulator_start();
AccumulatorStep accumulator_add(const ImuAccumulator& accumulator, const ImuReading& reading, uint32_t tick_ms);
GyroSums sums_with(const GyroSums& sums, const ImuReading& reading);
int16_t rounded_quarter(int32_t total);
ImuChannel imu_channel_start(uint32_t first_tick_ms);
uint32_t imu_ticks_due(const ImuChannel& channel, uint32_t now_ms);
ImuStep imu_channel_step(const ImuChannel& channel, uint32_t now_ms, const ImuRead& fresh);
