#include "radar_watchdog.h"

namespace {

constexpr uint8_t kMaxRestartCount = 0xFF;

// Signed, so a stamp a higher-priority task took after the reader's millis() counts as 0 ms ago, not 49 days.
bool elapsed_at_least(uint32_t since_ms, uint32_t now_ms, uint32_t duration_ms) {
    return static_cast<int32_t>(now_ms - since_ms) >= static_cast<int32_t>(duration_ms);
}

bool is_silent(const RadarWatchdog& watchdog, uint32_t now_ms) {
    return elapsed_at_least(watchdog.last_frame_ms, now_ms, kRadarSilenceLimitMs);
}

bool restart_due(const RadarWatchdog& watchdog, uint32_t now_ms) {
    if (!is_silent(watchdog, now_ms)) {
        return false;
    }
    if (!watchdog.restarted_while_silent) {
        return true;
    }
    return elapsed_at_least(watchdog.last_restart_ms, now_ms, kRadarRetryPeriodMs);
}

}  // namespace

RadarWatchdog watchdog_after_boot_config(uint32_t now_ms) {
    return watchdog_after_config(RadarWatchdog{}, now_ms);
}

RadarWatchdog watchdog_after_config(const RadarWatchdog& watchdog, uint32_t now_ms) {
    RadarWatchdog next{};
    next.last_frame_ms = now_ms;
    next.last_restart_ms = now_ms;
    next.restarted_while_silent = true;
    next.restarts = watchdog.restarts;
    return next;
}

RadarWatchdog watchdog_saw_frame(const RadarWatchdog& watchdog, uint32_t now_ms) {
    RadarWatchdog next = watchdog;
    next.last_frame_ms = now_ms;
    next.has_frame = true;
    next.restarted_while_silent = false;
    return next;
}

RadarWatchdog watchdog_restarted(const RadarWatchdog& watchdog, uint32_t now_ms) {
    RadarWatchdog next = watchdog;
    next.last_restart_ms = now_ms;
    next.restarted_while_silent = true;
    next.restarts = watchdog.restarts == kMaxRestartCount ? kMaxRestartCount : static_cast<uint8_t>(watchdog.restarts + 1);
    return next;
}

WatchdogStep watchdog_check(const RadarWatchdog& watchdog, uint32_t now_ms) {
    if (!restart_due(watchdog, now_ms)) {
        return WatchdogStep{watchdog, WatchdogAction::None};
    }
    return WatchdogStep{watchdog_restarted(watchdog, now_ms), WatchdogAction::Restart};
}

bool watchdog_radar_alive(const RadarWatchdog& watchdog, uint32_t now_ms) {
    return watchdog.has_frame && !is_silent(watchdog, now_ms);
}
