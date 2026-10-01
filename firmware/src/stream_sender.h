#pragma once

#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <stdint.h>

#include "bundler.h"

struct SenderSources {
    QueueHandle_t radar_frames;
    QueueHandle_t imu_samples[kImuCount];
};

struct SenderStats {
    uint32_t packets_sent;
    uint32_t notify_failures;
    uint32_t dropped_total;
    uint32_t skipped_cuts;
};

void stream_sender_start(const SenderSources& sources);
SenderStats stream_sender_stats();
