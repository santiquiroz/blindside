#include "imu_task.h"

#include "blindside_config.h"
#include "bundler.h"
#include "imu_health.h"

namespace {

const char* const kTaskNames[kImuCount] = {"imu_a", "imu_b"};

struct ImuTask {
    ImuDevice device;
    ImuHealth health;
    ImuChannel channel;
    QueueHandle_t samples;
    ImuSnapshot counters;
};

ImuTask g_tasks[kImuCount];
portMUX_TYPE g_snapshot_lock = portMUX_INITIALIZER_UNLOCKED;
ImuSnapshot g_snapshots[kImuCount];

ImuSnapshot snapshot_of(const ImuTask& task) {
    ImuSnapshot snapshot = task.counters;
    snapshot.ok = task.health.ok;
    snapshot.who_am_i = task.device.who_am_i;
    snapshot.repeats = task.channel.repeats;
    return snapshot;
}

void publish(const ImuTask& task) {
    ImuSnapshot snapshot = snapshot_of(task);
    taskENTER_CRITICAL(&g_snapshot_lock);
    g_snapshots[task.device.imu_id] = snapshot;
    taskEXIT_CRITICAL(&g_snapshot_lock);
}

void recover_if_due(ImuTask& task, uint32_t now_ms) {
    if (!imu_recovery_due(task.health, now_ms)) {
        return;
    }
    task.health = imu_health_after_probe(imu_recover(task.device), now_ms);
}

void count_read(ImuSnapshot& counters, bool read_ok) {
    counters.reads_ok += read_ok ? 1 : 0;
    counters.read_failures += read_ok ? 0 : 1;
}

ImuRead read_if_up(ImuTask& task, uint32_t now_ms) {
    if (!imu_should_read(task.health)) {
        return ImuRead{};
    }
    ImuRead read = imu_read(task.device);
    task.health = imu_health_after_read(task.health, read.ok, now_ms);
    count_read(task.counters, read.ok);
    return read;
}

void push_samples(ImuTask& task, const ImuStep& step) {
    for (size_t i = 0; i < step.sample_count; ++i) {
        bool queued = xQueueSend(task.samples, &step.samples[i], 0) == pdTRUE;
        task.counters.samples += queued ? 1 : 0;
        task.counters.queue_overflows += queued ? 0 : 1;
    }
}

void run_tick(ImuTask& task, uint32_t now_ms) {
    recover_if_due(task, now_ms);
    if (imu_ticks_due(task.channel, now_ms) == 0) {
        return;
    }
    ImuRead fresh = read_if_up(task, now_ms);
    ImuStep step = imu_channel_step(task.channel, now_ms, fresh);
    task.channel = step.channel;
    push_samples(task, step);
    publish(task);
}

void imu_task(void* argument) {
    ImuTask& task = *static_cast<ImuTask*>(argument);
    task.health = imu_health_after_probe(imu_begin(task.device), millis());
    publish(task);
    TickType_t wake = xTaskGetTickCount();
    task.channel = imu_channel_start(millis() + kImuTickPeriodMs - config::kImuGridLagMs);
    for (;;) {
        vTaskDelayUntil(&wake, pdMS_TO_TICKS(kImuTickPeriodMs));
        run_tick(task, millis());
    }
}

}  // namespace

void imu_task_start(const ImuDevice& device, QueueHandle_t samples) {
    ImuTask& task = g_tasks[device.imu_id];
    task = ImuTask{};
    task.device = device;
    task.samples = samples;
    xTaskCreatePinnedToCore(imu_task, kTaskNames[device.imu_id], config::kTaskStackBytes, &task,
                            config::kImuTaskPriority, nullptr, config::kSensorCore);
}

ImuSnapshot imu_task_snapshot(uint8_t imu_id) {
    taskENTER_CRITICAL(&g_snapshot_lock);
    ImuSnapshot snapshot = g_snapshots[imu_id];
    taskEXIT_CRITICAL(&g_snapshot_lock);
    return snapshot;
}
