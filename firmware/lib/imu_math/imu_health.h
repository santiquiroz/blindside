#pragma once

#include <stdint.h>

constexpr uint8_t kImuFailuresBeforeDown = 5;
constexpr uint8_t kImuGoodReadsToRecover = 5;
constexpr uint32_t kImuRecoveryPeriodMs = 1000;

struct ImuHealth {
    bool ok;
    bool down;
    uint8_t consecutive_failures;
    uint8_t consecutive_good;
    uint32_t last_recovery_ms;
};

ImuHealth imu_health_after_probe(bool found, uint32_t now_ms);
ImuHealth imu_health_after_read(const ImuHealth& health, bool read_ok, uint32_t now_ms);
bool imu_recovery_due(const ImuHealth& health, uint32_t now_ms);
bool imu_should_read(const ImuHealth& health);
