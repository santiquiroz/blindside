#pragma once

#include <stddef.h>
#include <stdint.h>

constexpr size_t kLd2450FrameSize = 30;
constexpr size_t kLd2450HeaderSize = 4;
constexpr size_t kLd2450TargetsSize = 24;

enum class FrameEvent : uint8_t { None, FrameOk, FrameBad };

struct FrameParser {
    uint8_t buffer[kLd2450FrameSize];
    uint8_t length;
    uint32_t header_t_ms;
};

struct ParsedFrame {
    uint32_t t_ms;
    uint8_t targets[kLd2450TargetsSize];
};

struct ParserStep {
    FrameParser parser;
    FrameEvent event;
    ParsedFrame frame;
};

FrameParser frame_parser_start();
ParserStep frame_parser_feed(const FrameParser& parser, uint8_t byte, uint32_t now_ms);
size_t header_resume_offset(const uint8_t* bytes, size_t length);
uint16_t bad_frames_after_one_more(uint16_t bad_frames);
