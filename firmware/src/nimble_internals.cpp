#include "nimble_internals.h"

#include <NimBLEDevice.h>

// Private NimBLE host headers: the CCCD permission hook, the per-connection packet counters and the host lock.
#include "nimble/nimble/host/src/ble_hs_priv.h"
#include "nimble/porting/npl/freertos/include/nimble/nimble_port_freertos.h"

namespace {

uint16_t queued_packets(const ble_hs_conn& conn) {
    uint16_t count = 0;
    const os_mbuf_pkthdr* entry = nullptr;
    STAILQ_FOREACH(entry, &conn.bhc_tx_q, omp_next) {
        count++;
    }
    return count;
}

}  // namespace

void nimble_set_cccd_permissions(uint8_t flags) {
    ble_gatts_set_clt_cfg_perm_flags(flags);
}

// Packets the controller holds for this link (sent, not yet acknowledged) plus those the host queued behind them.
uint16_t nimble_link_backlog(uint16_t conn_handle) {
    ble_hs_lock();
    const ble_hs_conn* conn = ble_hs_conn_find(conn_handle);
    uint16_t backlog = conn == nullptr ? 0 : static_cast<uint16_t>(conn->bhc_outstanding_pkts + queued_packets(*conn));
    ble_hs_unlock();
    return backlog;
}

uint16_t nimble_free_acl_buffers() {
    ble_hs_lock();
    uint16_t free_buffers = ble_hs_hci_avail_pkts;
    ble_hs_unlock();
    return free_buffers;
}

uint32_t nimble_host_stack_free() {
    return static_cast<uint32_t>(nimble_port_freertos_get_hs_hwm());
}
