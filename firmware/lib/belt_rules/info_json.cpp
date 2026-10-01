#include "info_json.h"

#include <stdio.h>

namespace {

constexpr size_t kObjectTextSize = 128;
constexpr uint32_t kIntervalHundredthsPerUnit = 125;
constexpr uint32_t kSupervisionMsPerUnit = 10;

struct ObjectText {
    char text[kObjectTextSize];
};

ObjectText radar_object(const RadarInfo& radar) {
    ObjectText object{};
    snprintf(object.text, sizeof(object.text), "{\"id\":%u,\"fw\":\"%s\",\"baud\":%lu}",
             static_cast<unsigned>(radar.id), radar.firmware.text, static_cast<unsigned long>(radar.baud));
    return object;
}

ObjectText imu_object(const ImuInfo& imu) {
    ObjectText object{};
    snprintf(object.text, sizeof(object.text), "{\"id\":%u,\"who\":%u,\"gyro_lsb_dps\":65.5,\"accel_lsb_g\":4096}",
             static_cast<unsigned>(imu.id), static_cast<unsigned>(imu.who_am_i));
    return object;
}

// The interval is a multiple of 1.25 ms; one decimal, rounded half up, is what the contract shows ("45.0").
ObjectText conn_object(const LinkParams& conn) {
    uint32_t tenths = (conn.interval_units * kIntervalHundredthsPerUnit + 5) / 10;
    ObjectText object{};
    snprintf(object.text, sizeof(object.text), "{\"interval_ms\":%lu.%lu,\"latency\":%u,\"timeout_ms\":%lu}",
             static_cast<unsigned long>(tenths / 10), static_cast<unsigned long>(tenths % 10),
             static_cast<unsigned>(conn.latency),
             static_cast<unsigned long>(conn.supervision_units * kSupervisionMsPerUnit));
    return object;
}

}  // namespace

size_t format_info_json(const BeltInfo& info, char* out, size_t out_size) {
    ObjectText radar_a = radar_object(info.radars[0]);
    ObjectText radar_b = radar_object(info.radars[1]);
    ObjectText imu_a = imu_object(info.imus[0]);
    ObjectText imu_b = imu_object(info.imus[1]);
    ObjectText conn = conn_object(info.conn);
    int written = snprintf(out, out_size,
                           "{\"proto\":%u,\"fw\":\"%s\",\"boot_id\":\"%08lx\",\"reset\":\"%s\",\"mtu\":%u,"
                           "\"radars\":[%s,%s],\"imus\":[%s,%s],\"tx_power_dbm\":%d,\"conn\":%s,\"uptime_s\":%lu}",
                           static_cast<unsigned>(kProtocolVersion), info.firmware_version,
                           static_cast<unsigned long>(info.boot_id), info.reset_reason,
                           static_cast<unsigned>(info.mtu), radar_a.text, radar_b.text, imu_a.text, imu_b.text,
                           static_cast<int>(info.tx_power_dbm), conn.text, static_cast<unsigned long>(info.uptime_s));
    bool fits = written > 0 && static_cast<size_t>(written) < out_size;
    return fits ? static_cast<size_t>(written) : 0;
}
