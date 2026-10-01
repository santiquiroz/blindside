#pragma once

#include <stddef.h>
#include <stdint.h>

enum class SerialCommand : uint8_t { None, ShowKey, NewKey };

SerialCommand parse_serial_line(const char* line, size_t length);
