#include "ld2450_frame.h"

#include <string.h>

namespace {

constexpr uint8_t kHeader[kLd2450HeaderSize] = {0xAA, 0xFF, 0x03, 0x00};
constexpr uint8_t kTailFirst = 0x55;
constexpr uint8_t kTailSecond = 0xCC;
constexpr size_t kTailOffset = kLd2450FrameSize - 2;

bool starts_like_header(const uint8_t* bytes, size_t length) {
    size_t compared = length < kLd2450HeaderSize ? length : kLd2450HeaderSize;
    return memcmp(bytes, kHeader, compared) == 0;
}

bool has_valid_tail(const uint8_t* frame) {
    return frame[kTailOffset] == kTailFirst && frame[kTailOffset + 1] == kTailSecond;
}

FrameParser with_byte(const FrameParser& parser, uint8_t byte, uint32_t now_ms) {
    FrameParser next = parser;
    next.buffer[next.length] = byte;
    next.length = static_cast<uint8_t>(next.length + 1);
    if (next.length == kLd2450HeaderSize) {
        next.header_t_ms = now_ms;
    }
    return next;
}

FrameParser resynced(const FrameParser& parser, uint32_t now_ms) {
    size_t offset = header_resume_offset(parser.buffer, parser.length);
    FrameParser next = frame_parser_start();
    next.length = static_cast<uint8_t>(parser.length - offset);
    memcpy(next.buffer, parser.buffer + offset, next.length);
    next.header_t_ms = now_ms;
    return next;
}

ParsedFrame frame_from(const FrameParser& parser) {
    ParsedFrame frame{};
    frame.t_ms = parser.header_t_ms;
    memcpy(frame.targets, parser.buffer + kLd2450HeaderSize, kLd2450TargetsSize);
    return frame;
}

ParserStep step_with(const FrameParser& parser, FrameEvent event) {
    ParserStep step{};
    step.parser = parser;
    step.event = event;
    return step;
}

ParserStep completed_frame_step(const FrameParser& full, uint32_t now_ms) {
    if (!has_valid_tail(full.buffer)) {
        return step_with(resynced(full, now_ms), FrameEvent::FrameBad);
    }
    ParserStep step = step_with(frame_parser_start(), FrameEvent::FrameOk);
    step.frame = frame_from(full);
    return step;
}

}  // namespace

FrameParser frame_parser_start() {
    return FrameParser{};
}

size_t header_resume_offset(const uint8_t* bytes, size_t length) {
    for (size_t offset = 1; offset < length; ++offset) {
        if (starts_like_header(bytes + offset, length - offset)) {
            return offset;
        }
    }
    return length;
}

ParserStep frame_parser_feed(const FrameParser& parser, uint8_t byte, uint32_t now_ms) {
    FrameParser next = with_byte(parser, byte, now_ms);
    if (!starts_like_header(next.buffer, next.length)) {
        return step_with(resynced(next, now_ms), FrameEvent::None);
    }
    if (next.length < kLd2450FrameSize) {
        return step_with(next, FrameEvent::None);
    }
    return completed_frame_step(next, now_ms);
}

uint16_t bad_frames_after_one_more(uint16_t bad_frames) {
    return bad_frames == UINT16_MAX ? bad_frames : static_cast<uint16_t>(bad_frames + 1);
}
