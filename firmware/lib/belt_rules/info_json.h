#pragma once

#include <stddef.h>
#include <stdint.h>

#include "bundler.h"
#include "ld2450_commands.h"

constexpr size_t kInfoJsonMaxBytes = 400;
constexpr size_t kInfoJsonBufferSize = kInfoJsonMaxBytes + 1;

struct RadarInfo {
    uint8_t id;
    FirmwareText firmware;
    uint32_t baud;
};

struct ImuInfo {
    uint8_t id;
    uint8_t who_am_i;
};

struct BeltInfo {
    const char* firmware_version;
    uint32_t boot_id;
    const char* reset_reason;
    uint16_t mtu;
    RadarInfo radars[kRadarCount];
    ImuInfo imus[kImuCount];
    int8_t tx_power_dbm;
    LinkParams conn;
    uint32_t uptime_s;
};

size_t format_info_json(const BeltInfo& info, char* out, size_t out_size);
