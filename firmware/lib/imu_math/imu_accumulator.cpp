#include "imu_accumulator.h"

namespace {

struct TickFeed {
    ImuChannel channel;
    bool has_sample;
    ImuSample sample;
};

uint32_t wrapped_add(uint32_t sum, int16_t value) {
    return sum + static_cast<uint32_t>(static_cast<int32_t>(value));
}

ImuAccumulator with_reading(const ImuAccumulator& accumulator, const ImuReading& reading, uint32_t tick_ms) {
    ImuAccumulator next = accumulator;
    if (next.count == 0) {
        next.slot_start_ms = tick_ms;
    }
    for (uint8_t axis = 0; axis < kImuAxisCount; ++axis) {
        next.totals[axis] += reading.axes[axis];
    }
    next.count = static_cast<uint8_t>(next.count + 1);
    next.sums = sums_with(accumulator.sums, reading);
    return next;
}

ImuSample sample_from(const ImuAccumulator& accumulator) {
    ImuSample sample{};
    sample.t_ms = accumulator.slot_start_ms + kImuSampleCentreOffsetMs;
    for (uint8_t axis = 0; axis < kImuAxisCount; ++axis) {
        sample.mean.axes[axis] = rounded_quarter(accumulator.totals[axis]);
    }
    sample.sums = accumulator.sums;
    return sample;
}

ImuAccumulator next_slot(const ImuAccumulator& accumulator) {
    ImuAccumulator next = accumulator_start();
    next.sums = accumulator.sums;
    return next;
}

TickFeed fed_one_tick(const ImuChannel& channel, const ImuRead& read) {
    ImuReading reading = read.ok ? read.reading : channel.last_reading;
    AccumulatorStep step = accumulator_add(channel.accumulator, reading, channel.next_tick_ms);
    TickFeed feed{};
    feed.channel = channel;
    feed.channel.accumulator = step.accumulator;
    feed.channel.last_reading = reading;
    feed.channel.next_tick_ms = channel.next_tick_ms + kImuTickPeriodMs;
    feed.channel.repeats = channel.repeats + (read.ok ? 0 : 1);
    feed.has_sample = step.has_sample;
    feed.sample = step.sample;
    return feed;
}

ImuStep with_tick(const ImuStep& step, const ImuRead& read) {
    TickFeed feed = fed_one_tick(step.channel, read);
    ImuStep next = step;
    next.channel = feed.channel;
    if (feed.has_sample) {
        next.samples[next.sample_count] = feed.sample;
        next.sample_count++;
    }
    return next;
}

}  // namespace

ImuAccumulator accumulator_start() {
    return ImuAccumulator{};
}

AccumulatorStep accumulator_add(const ImuAccumulator& accumulator, const ImuReading& reading, uint32_t tick_ms) {
    ImuAccumulator next = with_reading(accumulator, reading, tick_ms);
    if (next.count < kReadingsPerSample) {
        return AccumulatorStep{next, false, ImuSample{}};
    }
    return AccumulatorStep{next_slot(next), true, sample_from(next)};
}

GyroSums sums_with(const GyroSums& sums, const ImuReading& reading) {
    return GyroSums{wrapped_add(sums.x, reading.axes[kGx]), wrapped_add(sums.y, reading.axes[kGy]),
                    wrapped_add(sums.z, reading.axes[kGz])};
}

int16_t rounded_quarter(int32_t total) {
    int32_t magnitude = total < 0 ? -total : total;
    int32_t rounded = (magnitude + 2) / 4;
    return static_cast<int16_t>(total < 0 ? -rounded : rounded);
}

ImuChannel imu_channel_start(uint32_t first_tick_ms) {
    ImuChannel channel{};
    channel.next_tick_ms = first_tick_ms;
    return channel;
}

uint32_t imu_ticks_due(const ImuChannel& channel, uint32_t now_ms) {
    int32_t ahead_ms = static_cast<int32_t>(now_ms - channel.next_tick_ms);
    return ahead_ms < 0 ? 0 : static_cast<uint32_t>(ahead_ms) / kImuTickPeriodMs + 1;
}

ImuStep imu_channel_step(const ImuChannel& channel, uint32_t now_ms, const ImuRead& fresh) {
    uint32_t due = imu_ticks_due(channel, now_ms);
    uint32_t fed = due < kMaxTicksPerStep ? due : kMaxTicksPerStep;
    const ImuRead repeat{};
    ImuStep step{};
    step.channel = channel;
    for (uint32_t tick = 0; tick < fed; ++tick) {
        bool is_current_tick = tick + 1 == due;
        step = with_tick(step, is_current_tick ? fresh : repeat);
    }
    return step;
}
