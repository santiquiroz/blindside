#pragma once

#include <esp_system.h>
#include <stdint.h>

#include "bundler.h"
#include "imu_task.h"

struct DiagnosticsMemory {
    ImuSnapshot window_start[kImuCount];
    bool imu_ok_seen[kImuCount];
};

const char* reset_reason_name(esp_reset_reason_t reason);
DiagnosticsMemory diagnostics_report_imu_changes(const DiagnosticsMemory& memory, uint32_t now_ms);
DiagnosticsMemory diagnostics_print(const DiagnosticsMemory& memory, uint32_t now_ms);
