#include "ble_link.h"

#include <Arduino.h>

#include <atomic>

#include "ble_advertising.h"
#include "blindside_config.h"
#include "info_json.h"
#include "info_reads.h"
#include "nimble_internals.h"
#include "pairing_rules.h"

static_assert(kNoConnHandle == BLE_HS_CONN_HANDLE_NONE, "a free slot holds NimBLE's empty handle");
static_assert(CONFIG_BT_NIMBLE_MAX_CONNECTIONS <= kMaxLinks, "every NimBLE connection needs a link slot");

namespace {

constexpr uint16_t kDefaultAttMtu = 23;
constexpr uint16_t kNotificationsEnabledBit = 0x0001;
// A phone writes 06, then the CCCD, then 04 within a few connection events; the loop may be busy printing meanwhile.
constexpr UBaseType_t kControlQueueDepth = 4;
constexpr uint8_t kKeyDistribution = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
// CCCD writes need an encrypted link, so a stranger cannot subscribe to the stream.
constexpr uint8_t kCccdPermissions = BLE_ATT_F_READ | BLE_ATT_F_WRITE | BLE_ATT_F_WRITE_ENC;
constexpr uint8_t kIoCapability = config::kRequireMitm ? BLE_HS_IO_DISPLAY_ONLY : BLE_HS_IO_NO_INPUT_OUTPUT;
constexpr uint32_t kControlProperties =
    NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_ENC | (config::kRequireMitm ? NIMBLE_PROPERTY::WRITE_AUTHEN : 0);

struct LinkSlot {
    std::atomic<uint16_t> handle{kNoConnHandle};
    std::atomic<bool> subscribed{false};
    std::atomic<bool> trusted{false};
    std::atomic<bool> peer_bonded{false};
    std::atomic<bool> auth_event{false};
    std::atomic<bool> params_retried{false};
    std::atomic<uint8_t> role{0};
    std::atomic<uint16_t> mtu{kDefaultAttMtu};
    std::atomic<uint16_t> interval_units{0};
    std::atomic<uint16_t> latency{0};
    std::atomic<uint16_t> supervision_units{0};
    std::atomic<uint32_t> connected_at_ms{0};
    std::atomic<uint32_t> link_id{0};
    QueueHandle_t control_queue{nullptr};
};

NimBLEServer* g_server = nullptr;
NimBLECharacteristic* g_stream = nullptr;
NimBLECharacteristic* g_info = nullptr;
NimBLECharacteristic* g_control = nullptr;
InfoWriter g_info_writer = nullptr;
char g_info_json[kInfoJsonBufferSize];
InfoReadMarks g_info_reads{};

LinkSlot g_slots[kMaxLinks];
std::atomic<bool> g_disconnect_event{false};
std::atomic<uint32_t> g_last_link_id{0};
std::atomic<uint32_t> g_report_generation{0};
std::atomic<int> g_disconnect_reason{0};
std::atomic<uint32_t> g_passkey{0};

int slot_of(uint16_t handle) {
    uint16_t handles[kMaxLinks];
    for (size_t i = 0; i < kMaxLinks; ++i) {
        handles[i] = g_slots[i].handle.load();
    }
    return slot_for_handle(handles, kMaxLinks, handle);
}

LinkSlot* slot_for(const NimBLEConnInfo& info) {
    int slot = slot_of(info.getConnHandle());
    return slot < 0 ? nullptr : &g_slots[slot];
}

LinkParams params_of(const LinkSlot& slot) {
    return LinkParams{slot.interval_units.load(), slot.latency.load(), slot.supervision_units.load()};
}

void store_conn_params(LinkSlot& slot, const NimBLEConnInfo& info) {
    slot.interval_units = info.getConnInterval();
    slot.latency = info.getConnLatency();
    slot.supervision_units = info.getConnTimeout();
}

void request_conn_params(uint16_t handle, const ConnParamsRequest& request) {
    g_server->updateConnParams(handle, request.min_units, request.max_units, request.latency, request.timeout_units);
}

void insist_on_role_params(LinkSlot& slot, uint16_t handle) {
    LinkRole role = static_cast<LinkRole>(slot.role.load());
    if (!conn_params_retry_wanted(role, params_of(slot), slot.params_retried.load())) {
        return;
    }
    slot.params_retried = true;
    request_conn_params(handle, conn_params_for_role(role));
}

void claim_slot(LinkSlot& slot, const NimBLEConnInfo& info) {
    slot.subscribed = false;
    slot.trusted = false;
    slot.auth_event = false;
    slot.params_retried = false;
    xQueueReset(slot.control_queue);
    slot.role = static_cast<uint8_t>(LinkRole::Watch);
    slot.peer_bonded = NimBLEDevice::isBonded(info.getIdAddress());
    slot.mtu = info.getMTU();
    store_conn_params(slot, info);
    slot.connected_at_ms = millis();
    slot.link_id = ++g_last_link_id;
    slot.handle = info.getConnHandle();
}

void release_slot(LinkSlot& slot) {
    slot.handle = kNoConnHandle;
    slot.subscribed = false;
    slot.trusted = false;
    slot.peer_bonded = false;
    slot.auth_event = false;
    slot.link_id = 0;
}

void start_link(NimBLEServer* server, uint16_t handle) {
    request_conn_params(handle, conn_params_for_role(LinkRole::Watch));
    server->setDataLen(handle, config::kDataLengthOctets);
    NimBLEDevice::startSecurity(handle);
}

class ServerCallbacks : public NimBLEServerCallbacks {
  public:
    void onConnect(NimBLEServer* server, NimBLEConnInfo& info) override {
        int free_slot = slot_of(kNoConnHandle);
        if (free_slot < 0) {
            server->disconnect(info.getConnHandle());
            return;
        }
        claim_slot(g_slots[free_slot], info);
        start_link(server, info.getConnHandle());
    }

    void onDisconnect(NimBLEServer*, NimBLEConnInfo& info, int reason) override {
        LinkSlot* slot = slot_for(info);
        if (slot != nullptr) {
            release_slot(*slot);
        }
        g_disconnect_reason = reason;
        g_disconnect_event = true;
    }

    void onMTUChange(uint16_t mtu, NimBLEConnInfo& info) override {
        LinkSlot* slot = slot_for(info);
        if (slot != nullptr) {
            slot->mtu = mtu;
        }
    }

    uint32_t onPassKeyDisplay() override {
        return g_passkey.load();
    }

    void onAuthenticationComplete(NimBLEConnInfo& info) override {
        LinkSlot* slot = slot_for(info);
        if (slot != nullptr) {
            slot->auth_event = true;
        }
    }

    void onConnParamsUpdate(NimBLEConnInfo& info) override {
        LinkSlot* slot = slot_for(info);
        if (slot != nullptr) {
            store_conn_params(*slot, info);
            insist_on_role_params(*slot, info.getConnHandle());
        }
        g_report_generation++;
    }
};

class StreamCallbacks : public NimBLECharacteristicCallbacks {
  public:
    void onSubscribe(NimBLECharacteristic*, NimBLEConnInfo& info, uint16_t sub_value) override {
        LinkSlot* slot = slot_for(info);
        if (slot == nullptr) {
            return;
        }
        bool enabled = (sub_value & kNotificationsEnabledBit) != 0;
        slot->subscribed = enabled;
        if (enabled) {
            g_report_generation++;
        }
    }
};

class ControlCallbacks : public NimBLECharacteristicCallbacks {
  public:
    void onWrite(NimBLECharacteristic* characteristic, NimBLEConnInfo& info) override {
        LinkSlot* slot = slot_for(info);
        if (slot == nullptr) {
            return;
        }
        NimBLEAttValue value = characteristic->getValue();
        ControlCommand command = parse_control(value.data(), value.length());
        xQueueSend(slot->control_queue, &command, 0);
    }
};

void refresh_info(NimBLECharacteristic* characteristic, uint8_t reader_slot) {
    size_t length = g_info_writer(reader_slot, g_info_json, sizeof(g_info_json));
    if (length > 0) {
        characteristic->setValue(reinterpret_cast<const uint8_t*>(g_info_json), length);
    }
}

class InfoCallbacks : public NimBLECharacteristicCallbacks {
  public:
    // NimBLE calls onRead only for the offset-0 Read; the Read Blobs of a long read are served from the stored value.
    void onRead(NimBLECharacteristic* characteristic, NimBLEConnInfo& info) override {
        int slot = slot_of(info.getConnHandle());
        if (slot < 0) {
            return;
        }
        uint32_t now_ms = millis();
        InfoReader reader{static_cast<size_t>(slot), info.getMTU()};
        // Both links share that stored value, so it is not replaced while the other link may still be reading it.
        bool refresh = info_refresh_allowed(g_info_reads, reader, now_ms);
        g_info_reads = info_read_marked(g_info_reads, reader.slot, now_ms);
        if (refresh) {
            g_info_reads = info_copy_rebuilt(g_info_reads, reader.mtu);
            refresh_info(characteristic, static_cast<uint8_t>(slot));
        }
    }
};

ServerCallbacks g_server_callbacks;
StreamCallbacks g_stream_callbacks;
ControlCallbacks g_control_callbacks;
InfoCallbacks g_info_callbacks;

void configure_security() {
    NimBLEDevice::setSecurityAuth(true, config::kRequireMitm, true);
    NimBLEDevice::setSecurityIOCap(kIoCapability);
    NimBLEDevice::setSecurityInitKey(kKeyDistribution);
    NimBLEDevice::setSecurityRespKey(kKeyDistribution);
}

void create_control_queues() {
    for (LinkSlot& slot : g_slots) {
        slot.control_queue = xQueueCreate(kControlQueueDepth, sizeof(ControlCommand));
    }
}

void create_gatt() {
    g_server = NimBLEDevice::createServer();
    g_server->setCallbacks(&g_server_callbacks, false);
    g_server->advertiseOnDisconnect(true);
    NimBLEService* service = g_server->createService(config::kServiceUuid);
    g_stream = service->createCharacteristic(config::kStreamUuid, NIMBLE_PROPERTY::NOTIFY);
    g_stream->setCallbacks(&g_stream_callbacks);
    g_info = service->createCharacteristic(config::kInfoUuid, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_ENC);
    g_info->setCallbacks(&g_info_callbacks);
    g_control = service->createCharacteristic(config::kControlUuid, kControlProperties, kMaxControlSize);
    g_control->setCallbacks(&g_control_callbacks);
    g_server->start();
}

bool link_may_receive_stream(const LinkSlot& link, uint32_t link_id) {
    return link_id != 0 && link.link_id.load() == link_id && link.trusted.load() && link.subscribed.load();
}

}  // namespace

void ble_link_begin(const char* device_name, InfoWriter info_writer) {
    g_info_writer = info_writer;
    create_control_queues();
    NimBLEDevice::init(device_name);
    NimBLEDevice::setPower(config::kBleTxPowerDbm);
    NimBLEDevice::setMTU(config::kPreferredMtu);
    configure_security();
    nimble_set_cccd_permissions(kCccdPermissions);
    create_gatt();
    ble_advertising_configure(device_name);
}

void ble_link_set_passkey(uint32_t passkey) {
    g_passkey = passkey;
    NimBLEDevice::setSecurityPasskey(passkey);
}

size_t ble_link_capacity() {
    return CONFIG_BT_NIMBLE_MAX_CONNECTIONS;
}

size_t ble_link_connection_count() {
    size_t count = 0;
    for (const LinkSlot& slot : g_slots) {
        count += slot.handle.load() != kNoConnHandle ? 1 : 0;
    }
    return count;
}

LinkSnapshot ble_link_snapshot(uint8_t slot) {
    const LinkSlot& link = g_slots[slot];
    LinkSnapshot snapshot{};
    snapshot.connected = link.handle.load() != kNoConnHandle;
    snapshot.subscribed = link.subscribed.load();
    snapshot.trusted = link.trusted.load();
    snapshot.peer_bonded = link.peer_bonded.load();
    snapshot.role = static_cast<LinkRole>(link.role.load());
    snapshot.mtu = link.mtu.load();
    snapshot.connected_at_ms = link.connected_at_ms.load();
    snapshot.link_id = link.link_id.load();
    snapshot.params = params_of(link);
    return snapshot;
}

int ble_link_last_disconnect_reason() {
    return g_disconnect_reason.load();
}

bool ble_link_take_disconnect_event() {
    return g_disconnect_event.exchange(false);
}

uint32_t ble_link_report_generation() {
    return g_report_generation.load();
}

int8_t ble_link_tx_power() {
    return static_cast<int8_t>(NimBLEDevice::getPower());
}

bool ble_link_take_auth_event(uint8_t slot) {
    return g_slots[slot].auth_event.exchange(false);
}

PeerSecurity ble_link_peer_security(uint8_t slot) {
    PeerSecurity peer{};
    ble_gap_conn_desc desc{};
    uint16_t handle = g_slots[slot].handle.load();
    if (handle == kNoConnHandle || ble_gap_conn_find(handle, &desc) != 0) {
        return peer;
    }
    peer.valid = true;
    peer.secure = link_is_secure(desc.sec_state.encrypted, desc.sec_state.bonded, desc.sec_state.authenticated,
                                 config::kRequireMitm);
    peer.identity = NimBLEAddress(desc.peer_id_addr);
    return peer;
}

void ble_link_set_trusted(uint8_t slot, bool trusted) {
    g_slots[slot].trusted = trusted;
}

void ble_link_set_role(uint8_t slot, LinkRole role) {
    uint16_t handle = g_slots[slot].handle.load();
    if (handle == kNoConnHandle) {
        return;
    }
    g_slots[slot].role = static_cast<uint8_t>(role);
    g_slots[slot].params_retried = false;
    request_conn_params(handle, conn_params_for_role(role));
}

ControlCommand ble_link_take_control(uint8_t slot) {
    ControlCommand command{ControlKind::None, 0};
    xQueueReceive(g_slots[slot].control_queue, &command, 0);
    return command;
}

uint16_t ble_link_backlog(uint8_t slot) {
    uint16_t handle = g_slots[slot].handle.load();
    return handle == kNoConnHandle ? 0 : nimble_link_backlog(handle);
}

bool ble_link_notify(uint8_t slot, uint32_t link_id, const uint8_t* bytes, size_t length) {
    const LinkSlot& link = g_slots[slot];
    uint16_t handle = link.handle.load();
    // NimBLE notifies a given handle without checking its CCCD or encryption, and a freed slot is reused at once.
    if (handle == kNoConnHandle || !link_may_receive_stream(link, link_id)) {
        return false;
    }
    return g_stream->notify(bytes, length, handle);
}

void ble_link_disconnect(uint8_t slot) {
    uint16_t handle = g_slots[slot].handle.load();
    if (handle != kNoConnHandle) {
        g_server->disconnect(handle);
    }
}

void ble_link_disconnect_all() {
    for (uint8_t slot = 0; slot < kMaxLinks; ++slot) {
        ble_link_disconnect(slot);
    }
}

LinkSnapshot ble_link_snapshot() {
    return ble_link_snapshot(0);
}

bool ble_link_take_auth_event() {
    return ble_link_take_auth_event(0);
}

PeerSecurity ble_link_peer_security() {
    return ble_link_peer_security(0);
}

void ble_link_set_trusted(bool trusted) {
    ble_link_set_trusted(0, trusted);
}

ControlCommand ble_link_take_control() {
    return ble_link_take_control(0);
}

bool ble_link_notify(const uint8_t* bytes, size_t length) {
    return ble_link_notify(0, g_slots[0].link_id.load(), bytes, length);
}

void ble_link_disconnect() {
    ble_link_disconnect(0);
}
