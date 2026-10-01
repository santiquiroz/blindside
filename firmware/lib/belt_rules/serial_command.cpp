#include "serial_command.h"

#include <string.h>

namespace {

struct Slice {
    const char* text;
    size_t length;
};

bool is_blank(char c) {
    return c == ' ' || c == '\t' || c == '\r' || c == '\n';
}

Slice trimmed(const char* line, size_t length) {
    size_t start = 0;
    while (start < length && is_blank(line[start])) {
        start++;
    }
    size_t end = length;
    while (end > start && is_blank(line[end - 1])) {
        end--;
    }
    return Slice{line + start, end - start};
}

bool slice_equals(const Slice& slice, const char* word) {
    size_t word_length = strlen(word);
    return slice.length == word_length && memcmp(slice.text, word, word_length) == 0;
}

}  // namespace

SerialCommand parse_serial_line(const char* line, size_t length) {
    if (line == nullptr) {
        return SerialCommand::None;
    }
    Slice command = trimmed(line, length);
    if (slice_equals(command, "key")) {
        return SerialCommand::ShowKey;
    }
    return slice_equals(command, "key new") ? SerialCommand::NewKey : SerialCommand::None;
}
