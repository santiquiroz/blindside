#pragma once

#include <stdint.h>

constexpr uint32_t kRadarSilenceLimitMs = 2000;
constexpr uint32_t kRadarRetryPeriodMs = 30000;

struct RadarWatchdog {
    uint32_t last_frame_ms;
    uint32_t last_restart_ms;
    bool has_frame;
    bool restarted_while_silent;
    uint8_t restarts;
};

enum class WatchdogAction : uint8_t { None, Restart };

struct WatchdogStep {
    RadarWatchdog watchdog;
    WatchdogAction action;
};

RadarWatchdog watchdog_after_boot_config(uint32_t now_ms);
RadarWatchdog watchdog_saw_frame(const RadarWatchdog& watchdog, uint32_t now_ms);
RadarWatchdog watchdog_restarted(const RadarWatchdog& watchdog, uint32_t now_ms);
WatchdogStep watchdog_check(const RadarWatchdog& watchdog, uint32_t now_ms);
bool watchdog_radar_alive(const RadarWatchdog& watchdog, uint32_t now_ms);
