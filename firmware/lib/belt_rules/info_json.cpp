#include "info_json.h"

#include <stdio.h>

namespace {

constexpr size_t kObjectTextSize = 128;
constexpr size_t kConnsTextSize = kMaxLinks * kObjectTextSize;
constexpr uint32_t kIntervalHundredthsPerUnit = 125;
constexpr uint32_t kSupervisionMsPerUnit = 10;

struct ObjectText {
    char text[kObjectTextSize];
};

struct ConnsText {
    char text[kConnsTextSize];
};

struct IntervalText {
    unsigned long whole_ms;
    unsigned long tenth_ms;
};

ObjectText radar_object(const RadarInfo& radar) {
    ObjectText object{};
    snprintf(object.text, sizeof(object.text), "{\"id\":%u,\"fw\":\"%s\",\"baud\":%lu}",
             static_cast<unsigned>(radar.id), radar.firmware.text, static_cast<unsigned long>(radar.baud));
    return object;
}

ObjectText imu_object(const ImuInfo& imu) {
    ObjectText object{};
    snprintf(object.text, sizeof(object.text), "{\"id\":%u,\"who\":%u,\"repeats\":%lu}",
             static_cast<unsigned>(imu.id), static_cast<unsigned>(imu.who_am_i),
             static_cast<unsigned long>(imu.repeats));
    return object;
}

// The interval is a multiple of 1.25 ms; one decimal, rounded half up, is what the contract shows ("45.0").
IntervalText interval_text(uint16_t interval_units) {
    uint32_t tenths = (interval_units * kIntervalHundredthsPerUnit + 5) / 10;
    return IntervalText{tenths / 10, tenths % 10};
}

ObjectText conn_object(const ConnInfo& conn) {
    IntervalText interval = interval_text(conn.params.interval_units);
    ObjectText object{};
    snprintf(object.text, sizeof(object.text),
             "{\"role\":\"%s\",\"itvl_ms\":%lu.%lu,\"lat\":%u,\"timeout_ms\":%lu,\"sent\":%lu,\"dropped\":%lu}",
             role_name(conn.role), interval.whole_ms, interval.tenth_ms, static_cast<unsigned>(conn.params.latency),
             static_cast<unsigned long>(conn.params.supervision_units * kSupervisionMsPerUnit),
             static_cast<unsigned long>(conn.sent % kInfoCounterModulus),
             static_cast<unsigned long>(conn.dropped % kInfoCounterModulus));
    return object;
}

size_t appended(char* out, size_t out_size, size_t used, const char* separator, const char* text) {
    int written = snprintf(out + used, out_size - used, "%s%s", separator, text);
    size_t next = used + (written > 0 ? static_cast<size_t>(written) : 0);
    return next < out_size ? next : out_size - 1;
}

ConnsText conns_array(const BeltInfo& info) {
    ConnsText conns{};
    size_t used = 0;
    size_t count = info.conn_count < kMaxLinks ? info.conn_count : kMaxLinks;
    for (size_t i = 0; i < count; ++i) {
        used = appended(conns.text, sizeof(conns.text), used, i == 0 ? "" : ",", conn_object(info.conns[i]).text);
    }
    return conns;
}

}  // namespace

size_t format_info_json(const BeltInfo& info, char* out, size_t out_size) {
    ObjectText radar_a = radar_object(info.radars[0]);
    ObjectText radar_b = radar_object(info.radars[1]);
    ObjectText imu_a = imu_object(info.imus[0]);
    ObjectText imu_b = imu_object(info.imus[1]);
    ConnsText conns = conns_array(info);
    int written = snprintf(out, out_size,
                           "{\"proto\":%u,\"fw\":\"%s\",\"boot_id\":\"%08lx\",\"reset\":\"%s\",\"mtu\":%u,"
                           "\"radars\":[%s,%s],\"imus\":[%s,%s],\"tx_power_dbm\":%d,\"conns\":[%s],\"bonds\":%u,"
                           "\"uptime_s\":%lu}",
                           static_cast<unsigned>(kProtocolVersion), info.firmware_version,
                           static_cast<unsigned long>(info.boot_id), info.reset_reason,
                           static_cast<unsigned>(info.mtu), radar_a.text, radar_b.text, imu_a.text, imu_b.text,
                           static_cast<int>(info.tx_power_dbm), conns.text, static_cast<unsigned>(info.bonds),
                           static_cast<unsigned long>(info.uptime_s));
    bool fits = written > 0 && static_cast<size_t>(written) < out_size;
    return fits ? static_cast<size_t>(written) : 0;
}
