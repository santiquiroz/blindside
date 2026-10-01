#pragma once

#include <stddef.h>
#include <stdint.h>

#include "imu_accumulator.h"

constexpr uint8_t kProtocolVersion = 1;
constexpr size_t kPacketHeaderSize = 8;
constexpr size_t kMaxPacketSize = 244;
constexpr size_t kAttNotifyOverhead = 3;
constexpr uint8_t kTlvRadar = 0x01;
constexpr uint8_t kTlvImu = 0x02;
constexpr uint8_t kTlvStatus = 0x03;
constexpr uint8_t kTlvLink = 0x04;
constexpr size_t kTlvHeaderSize = 2;
constexpr size_t kRadarCount = 2;
constexpr size_t kImuCount = 2;
constexpr size_t kRadarTargetsSize = 24;
constexpr size_t kRadarSectionSize = kTlvHeaderSize + 1 + 4 + kRadarTargetsSize;
constexpr size_t kStatusEntrySize = 5;
constexpr size_t kStatusSectionSize = kTlvHeaderSize + kRadarCount * kStatusEntrySize;
constexpr size_t kLinkSectionSize = kTlvHeaderSize + 3 * 2;
constexpr size_t kImuSectionFixedSize = kTlvHeaderSize + 1 + 4 + 1 + 3 * 4;
constexpr size_t kImuSampleWireSize = 12;
constexpr size_t kMaxImuSamplesPerSection = 18;
constexpr size_t kMinStreamPayload = kPacketHeaderSize + kImuSectionFixedSize + kImuSampleWireSize;

constexpr uint8_t kFlagRadarAAlive = 0x01;
constexpr uint8_t kFlagRadarBAlive = 0x02;
constexpr uint8_t kFlagImuAOk = 0x04;
constexpr uint8_t kFlagImuBOk = 0x08;
constexpr uint8_t kFlagDataDropped = 0x10;

struct RadarFrame {
    uint8_t radar_id;
    uint32_t t_ms;
    uint8_t targets[kRadarTargetsSize];
};

struct StatusEntry {
    uint8_t radar_id;
    uint16_t bad_frames;
    uint8_t restarts;
    uint8_t baud_index;
};

struct LinkParams {
    uint16_t interval_units;
    uint16_t latency;
    uint16_t supervision_units;
};

struct Packet {
    uint8_t bytes[kMaxPacketSize];
    uint8_t length;
};

struct CutInput {
    const RadarFrame* radar_frames;
    size_t radar_count;
    const ImuSample* imu_samples[kImuCount];
    size_t imu_counts[kImuCount];
    bool include_status;
    StatusEntry status[kRadarCount];
    bool include_link;
    LinkParams link;
};

struct HeaderFields {
    uint8_t flags;
    uint16_t first_seq;
    uint32_t t_ms;
};

struct BundleResult {
    size_t packet_count;
    size_t radar_consumed;
    size_t imu_consumed[kImuCount];
    bool status_consumed;
    bool link_consumed;
    uint16_t next_seq;
};

struct LinkHealth {
    bool radar_alive[kRadarCount];
    bool imu_ok[kImuCount];
    bool data_dropped;
};

size_t payload_limit_for_mtu(uint16_t mtu);
size_t imu_section_size(size_t sample_count);
uint8_t packet_flags(const LinkHealth& health);
CutInput with_status(const CutInput& input, const StatusEntry* status);
CutInput with_link(const CutInput& input, const LinkParams& link);
BundleResult bundle_cut(const CutInput& input, const HeaderFields& header, size_t payload_limit, Packet* packets,
                        size_t max_packets);
