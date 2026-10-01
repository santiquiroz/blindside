#pragma once

#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <stdint.h>

#include "imu_mpu6050.h"

struct ImuSnapshot {
    bool ok;
    uint8_t who_am_i;
    uint32_t reads_ok;
    uint32_t read_failures;
    uint32_t repeats;
    uint32_t samples;
    uint32_t queue_overflows;
};

void imu_task_start(const ImuDevice& device, QueueHandle_t samples);
ImuSnapshot imu_task_snapshot(uint8_t imu_id);
