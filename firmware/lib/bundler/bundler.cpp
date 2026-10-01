#include "bundler.h"

#include <string.h>

namespace {

struct PacketWriter {
    Packet* packets;
    size_t max_packets;
    size_t count;
    size_t limit;
    uint8_t flags;
    uint32_t t_ms;
    uint16_t seq;
};

void put_u16(uint8_t* out, uint16_t value) {
    out[0] = static_cast<uint8_t>(value & 0xFF);
    out[1] = static_cast<uint8_t>(value >> 8);
}

void put_u32(uint8_t* out, uint32_t value) {
    for (uint8_t i = 0; i < 4; ++i) {
        out[i] = static_cast<uint8_t>((value >> (8 * i)) & 0xFF);
    }
}

uint8_t flag_if(bool condition, uint8_t flag) {
    return condition ? flag : 0;
}

bool current_packet_fits(const PacketWriter& writer, size_t size) {
    return writer.count > 0 && writer.packets[writer.count - 1].length + size <= writer.limit;
}

bool open_packet(PacketWriter& writer) {
    if (writer.count >= writer.max_packets) {
        return false;
    }
    Packet& packet = writer.packets[writer.count];
    packet.bytes[0] = kProtocolVersion;
    packet.bytes[1] = writer.flags;
    put_u16(packet.bytes + 2, writer.seq);
    put_u32(packet.bytes + 4, writer.t_ms);
    packet.length = kPacketHeaderSize;
    writer.count++;
    writer.seq = static_cast<uint16_t>(writer.seq + 1);
    return true;
}

uint8_t* reserve(PacketWriter& writer, size_t size) {
    bool has_room = current_packet_fits(writer, size) || (open_packet(writer) && current_packet_fits(writer, size));
    if (!has_room) {
        return nullptr;
    }
    Packet& packet = writer.packets[writer.count - 1];
    uint8_t* section = packet.bytes + packet.length;
    packet.length = static_cast<uint8_t>(packet.length + size);
    return section;
}

bool write_radar_section(PacketWriter& writer, const RadarFrame& frame) {
    uint8_t* out = reserve(writer, kRadarSectionSize);
    if (out == nullptr) {
        return false;
    }
    out[0] = kTlvRadar;
    out[1] = static_cast<uint8_t>(kRadarSectionSize - kTlvHeaderSize);
    out[2] = frame.radar_id;
    put_u32(out + 3, frame.t_ms);
    memcpy(out + 7, frame.targets, kRadarTargetsSize);
    return true;
}

uint8_t* put_imu_samples(uint8_t* out, const ImuSample* samples, size_t count) {
    for (size_t i = 0; i < count; ++i) {
        for (uint8_t axis = 0; axis < kImuAxisCount; ++axis) {
            put_u16(out, static_cast<uint16_t>(samples[i].mean.axes[axis]));
            out += 2;
        }
    }
    return out;
}

bool write_imu_section(PacketWriter& writer, uint8_t imu_id, const ImuSample* samples, size_t count) {
    size_t size = imu_section_size(count);
    uint8_t* out = reserve(writer, size);
    if (out == nullptr) {
        return false;
    }
    out[0] = kTlvImu;
    out[1] = static_cast<uint8_t>(size - kTlvHeaderSize);
    out[2] = imu_id;
    put_u32(out + 3, samples[0].t_ms);
    out[7] = static_cast<uint8_t>(count);
    uint8_t* sums = put_imu_samples(out + 8, samples, count);
    const GyroSums& last = samples[count - 1].sums;
    put_u32(sums, last.x);
    put_u32(sums + 4, last.y);
    put_u32(sums + 8, last.z);
    return true;
}

bool write_status_section(PacketWriter& writer, const StatusEntry* status) {
    uint8_t* out = reserve(writer, kStatusSectionSize);
    if (out == nullptr) {
        return false;
    }
    out[0] = kTlvStatus;
    out[1] = static_cast<uint8_t>(kStatusSectionSize - kTlvHeaderSize);
    for (size_t i = 0; i < kRadarCount; ++i) {
        uint8_t* entry = out + kTlvHeaderSize + i * kStatusEntrySize;
        entry[0] = status[i].radar_id;
        put_u16(entry + 1, status[i].bad_frames);
        entry[3] = status[i].restarts;
        entry[4] = status[i].baud_index;
    }
    return true;
}

bool write_link_section(PacketWriter& writer, const LinkParams& link) {
    uint8_t* out = reserve(writer, kLinkSectionSize);
    if (out == nullptr) {
        return false;
    }
    out[0] = kTlvLink;
    out[1] = static_cast<uint8_t>(kLinkSectionSize - kTlvHeaderSize);
    put_u16(out + 2, link.interval_units);
    put_u16(out + 4, link.latency);
    put_u16(out + 6, link.supervision_units);
    return true;
}

size_t samples_per_section(size_t limit) {
    size_t fit = (limit - kPacketHeaderSize - kImuSectionFixedSize) / kImuSampleWireSize;
    return fit < kMaxImuSamplesPerSection ? fit : kMaxImuSamplesPerSection;
}

bool follows_on_grid(const ImuSample& previous, const ImuSample& next) {
    return next.t_ms == previous.t_ms + kImuSamplePeriodMs;
}

size_t contiguous_run(const ImuSample* samples, size_t count, size_t max_run) {
    size_t run = 1;
    while (run < count && run < max_run && follows_on_grid(samples[run - 1], samples[run])) {
        run++;
    }
    return run;
}

size_t write_radar_frames(PacketWriter& writer, const RadarFrame* frames, size_t count) {
    size_t written = 0;
    while (written < count && write_radar_section(writer, frames[written])) {
        written++;
    }
    return written;
}

size_t write_imu_samples(PacketWriter& writer, uint8_t imu_id, const ImuSample* samples, size_t count) {
    size_t max_run = samples_per_section(writer.limit);
    size_t written = 0;
    while (written < count) {
        size_t run = contiguous_run(samples + written, count - written, max_run);
        if (!write_imu_section(writer, imu_id, samples + written, run)) {
            break;
        }
        written += run;
    }
    return written;
}

bool write_imu_group(PacketWriter& writer, const CutInput& input, BundleResult& result) {
    for (uint8_t imu = 0; imu < kImuCount; ++imu) {
        result.imu_consumed[imu] = write_imu_samples(writer, imu, input.imu_samples[imu], input.imu_counts[imu]);
        if (result.imu_consumed[imu] != input.imu_counts[imu]) {
            return false;
        }
    }
    return true;
}

bool write_optional_sections(PacketWriter& writer, const CutInput& input, BundleResult& result) {
    result.status_consumed = input.include_status && write_status_section(writer, input.status);
    if (input.include_status && !result.status_consumed) {
        return false;
    }
    result.link_consumed = input.include_link && write_link_section(writer, input.link);
    return !input.include_link || result.link_consumed;
}

PacketWriter writer_for(const HeaderFields& header, size_t payload_limit, Packet* packets, size_t max_packets) {
    PacketWriter writer{};
    writer.packets = packets;
    writer.max_packets = max_packets;
    writer.limit = payload_limit < kMaxPacketSize ? payload_limit : kMaxPacketSize;
    writer.flags = header.flags;
    writer.t_ms = header.t_ms;
    writer.seq = header.first_seq;
    return writer;
}

}  // namespace

size_t payload_limit_for_mtu(uint16_t mtu) {
    if (mtu <= kAttNotifyOverhead) {
        return 0;
    }
    size_t payload = mtu - kAttNotifyOverhead;
    return payload < kMaxPacketSize ? payload : kMaxPacketSize;
}

size_t imu_section_size(size_t sample_count) {
    return kImuSectionFixedSize + sample_count * kImuSampleWireSize;
}

uint8_t packet_flags(const LinkHealth& health) {
    return flag_if(health.radar_alive[0], kFlagRadarAAlive) | flag_if(health.radar_alive[1], kFlagRadarBAlive) |
           flag_if(health.imu_ok[0], kFlagImuAOk) | flag_if(health.imu_ok[1], kFlagImuBOk) |
           flag_if(health.data_dropped, kFlagDataDropped);
}

CutInput with_status(const CutInput& input, const StatusEntry* status) {
    CutInput next = input;
    next.include_status = status != nullptr;
    if (next.include_status) {
        memcpy(next.status, status, sizeof(next.status));
    }
    return next;
}

CutInput with_link(const CutInput& input, const LinkParams& link) {
    CutInput next = input;
    next.include_link = true;
    next.link = link;
    return next;
}

BundleResult bundle_cut(const CutInput& input, const HeaderFields& header, size_t payload_limit, Packet* packets,
                        size_t max_packets) {
    BundleResult result{};
    result.next_seq = header.first_seq;
    if (payload_limit < kMinStreamPayload || max_packets == 0) {
        return result;
    }
    PacketWriter writer = writer_for(header, payload_limit, packets, max_packets);
    // IMU, STATUS and LINK go before RADAR so a cut's IMU data never arrives after its frames (spec §4.2).
    bool radar_may_follow = write_imu_group(writer, input, result) && write_optional_sections(writer, input, result);
    result.radar_consumed = radar_may_follow ? write_radar_frames(writer, input.radar_frames, input.radar_count) : 0;
    if (writer.count == 0) {
        open_packet(writer);  // a cut with nothing to send still proves the link is alive
    }
    result.packet_count = writer.count;
    result.next_seq = writer.seq;
    return result;
}
