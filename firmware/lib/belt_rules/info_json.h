#pragma once

#include <stddef.h>
#include <stdint.h>

#include "bundler.h"
#include "ld2450_commands.h"
#include "link_roles.h"

constexpr size_t kInfoJsonMaxBytes = 512;
constexpr size_t kInfoJsonBufferSize = kInfoJsonMaxBytes + 1;
// Six digits per counter is what keeps two links inside the 512 B attribute in the worst case.
constexpr uint32_t kInfoCounterModulus = 1000000;

struct RadarInfo {
    uint8_t id;
    FirmwareText firmware;
    uint32_t baud;
};

struct ImuInfo {
    uint8_t id;
    uint8_t who_am_i;
    uint32_t repeats;
};

struct ConnInfo {
    LinkRole role;
    LinkParams params;
    uint32_t sent;
    uint32_t dropped;
};

struct BeltInfo {
    const char* firmware_version;
    uint32_t boot_id;
    const char* reset_reason;
    uint16_t mtu;
    RadarInfo radars[kRadarCount];
    ImuInfo imus[kImuCount];
    int8_t tx_power_dbm;
    ConnInfo conns[kMaxLinks];
    size_t conn_count;
    uint8_t bonds;
    uint32_t uptime_s;
};

size_t format_info_json(const BeltInfo& info, char* out, size_t out_size);
