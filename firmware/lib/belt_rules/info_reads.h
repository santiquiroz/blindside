#pragma once

#include <stddef.h>
#include <stdint.h>

#include "link_roles.h"

constexpr uint32_t kInfoReadShareMs = 2000;

struct InfoReadMarks {
    bool marked[kMaxLinks];
    uint32_t at_ms[kMaxLinks];
    uint16_t copy_mtu;
};

struct InfoReader {
    size_t slot;
    uint16_t mtu;
};

bool info_refresh_allowed(const InfoReadMarks& marks, const InfoReader& reader, uint32_t now_ms);
InfoReadMarks info_read_marked(const InfoReadMarks& marks, size_t reader_slot, uint32_t now_ms);
InfoReadMarks info_copy_rebuilt(const InfoReadMarks& marks, uint16_t mtu);
