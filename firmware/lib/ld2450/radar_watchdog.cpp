#include "radar_watchdog.h"

namespace {

constexpr uint8_t kMaxRestartCount = 0xFF;

bool elapsed_at_least(uint32_t since_ms, uint32_t now_ms, uint32_t duration_ms) {
    return now_ms - since_ms >= duration_ms;
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
    RadarWatchdog watchdog{};
    watchdog.last_frame_ms = now_ms;
    watchdog.last_restart_ms = now_ms;
    watchdog.restarted_while_silent = true;
    return watchdog;
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
