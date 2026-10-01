#include "imu_health.h"

namespace {

uint8_t saturating_increment(uint8_t value) {
    return value == UINT8_MAX ? value : static_cast<uint8_t>(value + 1);
}

ImuHealth down_since(uint32_t now_ms) {
    ImuHealth health{};
    health.down = true;
    health.last_recovery_ms = now_ms;
    return health;
}

ImuHealth after_good_read(const ImuHealth& health) {
    ImuHealth next = health;
    next.consecutive_failures = 0;
    next.consecutive_good = saturating_increment(health.consecutive_good);
    next.ok = health.ok || next.consecutive_good >= kImuGoodReadsToRecover;
    return next;
}

ImuHealth after_failed_read(const ImuHealth& health, uint32_t now_ms) {
    ImuHealth next = health;
    next.consecutive_good = 0;
    next.consecutive_failures = saturating_increment(health.consecutive_failures);
    return next.consecutive_failures >= kImuFailuresBeforeDown ? down_since(now_ms) : next;
}

}  // namespace

ImuHealth imu_health_after_probe(bool found, uint32_t now_ms) {
    return found ? ImuHealth{} : down_since(now_ms);
}

ImuHealth imu_health_after_read(const ImuHealth& health, bool read_ok, uint32_t now_ms) {
    return read_ok ? after_good_read(health) : after_failed_read(health, now_ms);
}

bool imu_recovery_due(const ImuHealth& health, uint32_t now_ms) {
    return health.down && now_ms - health.last_recovery_ms >= kImuRecoveryPeriodMs;
}

bool imu_should_read(const ImuHealth& health) {
    return !health.down;
}
