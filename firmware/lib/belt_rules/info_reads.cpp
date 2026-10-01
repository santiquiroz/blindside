#include "info_reads.h"

#include "ble_rules.h"

namespace {

bool read_in_progress(const InfoReadMarks& marks, size_t slot, uint32_t now_ms) {
    return marks.marked[slot] && now_ms - marks.at_ms[slot] < kInfoReadShareMs;
}

bool other_link_reading(const InfoReadMarks& marks, size_t reader_slot, uint32_t now_ms) {
    for (size_t slot = 0; slot < kMaxLinks; ++slot) {
        if (slot != reader_slot && read_in_progress(marks, slot, now_ms)) {
            return true;
        }
    }
    return false;
}

// A link below MTU 247 cannot stream, so splicing its long read costs nothing, and its MTU never reaches the watch.
bool copy_useless_to(const InfoReadMarks& marks, uint16_t reader_mtu) {
    return marks.copy_mtu < kMinNotifyMtu && reader_mtu >= kMinNotifyMtu;
}

}  // namespace

bool info_refresh_allowed(const InfoReadMarks& marks, const InfoReader& reader, uint32_t now_ms) {
    return copy_useless_to(marks, reader.mtu) || !other_link_reading(marks, reader.slot, now_ms);
}

InfoReadMarks info_read_marked(const InfoReadMarks& marks, size_t reader_slot, uint32_t now_ms) {
    InfoReadMarks next = marks;
    if (reader_slot < kMaxLinks) {
        next.marked[reader_slot] = true;
        next.at_ms[reader_slot] = now_ms;
    }
    return next;
}

InfoReadMarks info_copy_rebuilt(const InfoReadMarks& marks, uint16_t mtu) {
    InfoReadMarks next = marks;
    next.copy_mtu = mtu;
    return next;
}
