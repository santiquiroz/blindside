#include "ble_link.h"

#include <Arduino.h>

#include <atomic>

#include "ble_rules.h"
#include "blindside_config.h"
#include "info_json.h"
#include "pairing_rules.h"

// Internal NimBLE host function (ble_gatts.c, declared only in the private ble_gatt_priv.h).
extern "C" void ble_gatts_set_clt_cfg_perm_flags(uint8_t flags);

namespace {

constexpr uint16_t kDefaultAttMtu = 23;
constexpr uint16_t kNotificationsEnabledBit = 0x0001;
constexpr uint32_t kControlMailboxFull = 0x80000000u;
constexpr uint32_t kControlLengthMask = 0xFF;
constexpr uint8_t kKeyDistribution = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
// CCCD writes need an encrypted link, so a stranger cannot subscribe to the stream.
constexpr uint8_t kCccdPermissions = BLE_ATT_F_READ | BLE_ATT_F_WRITE | BLE_ATT_F_WRITE_ENC;
constexpr uint8_t kIoCapability = config::kRequireMitm ? BLE_HS_IO_DISPLAY_ONLY : BLE_HS_IO_NO_INPUT_OUTPUT;
constexpr uint32_t kControlProperties =
    NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_ENC | (config::kRequireMitm ? NIMBLE_PROPERTY::WRITE_AUTHEN : 0);

struct AdvertisingState {
    bool whitelist_only;
    AdvertisingSpeed speed;
    uint32_t since_ms;
};

NimBLEServer* g_server = nullptr;
NimBLECharacteristic* g_stream = nullptr;
NimBLECharacteristic* g_info = nullptr;
NimBLECharacteristic* g_control = nullptr;
AdvertisingState g_advertising{};
InfoWriter g_info_writer = nullptr;
char g_info_json[kInfoJsonBufferSize];

std::atomic<bool> g_connected{false};
std::atomic<bool> g_subscribed{false};
std::atomic<bool> g_trusted{false};
std::atomic<bool> g_peer_bonded{false};
std::atomic<bool> g_auth_event{false};
std::atomic<bool> g_disconnect_event{false};
std::atomic<uint16_t> g_conn_handle{BLE_HS_CONN_HANDLE_NONE};
std::atomic<uint16_t> g_mtu{kDefaultAttMtu};
std::atomic<uint32_t> g_connected_at_ms{0};
std::atomic<uint16_t> g_interval_units{0};
std::atomic<uint16_t> g_latency{0};
std::atomic<uint16_t> g_supervision_units{0};
std::atomic<uint32_t> g_report_generation{0};
std::atomic<int> g_disconnect_reason{0};
std::atomic<uint32_t> g_passkey{0};
std::atomic<uint32_t> g_control_mailbox{0};

uint32_t packed_control(const uint8_t* bytes, size_t length) {
    uint32_t first = length > 0 ? bytes[0] : 0;
    uint32_t second = length > 1 ? bytes[1] : 0;
    uint32_t clamped = length > kMaxControlSize ? kMaxControlSize : static_cast<uint32_t>(length);
    return kControlMailboxFull | clamped | (first << 8) | (second << 16);
}

ControlCommand unpacked_control(uint32_t mailbox) {
    uint8_t bytes[kMaxControlSize] = {static_cast<uint8_t>(mailbox >> 8), static_cast<uint8_t>(mailbox >> 16), 0, 0};
    return parse_control(bytes, mailbox & kControlLengthMask);
}

void store_conn_params(const NimBLEConnInfo& info) {
    g_interval_units = info.getConnInterval();
    g_latency = info.getConnLatency();
    g_supervision_units = info.getConnTimeout();
}

class ServerCallbacks : public NimBLEServerCallbacks {
  public:
    void onConnect(NimBLEServer* server, NimBLEConnInfo& info) override {
        uint16_t handle = info.getConnHandle();
        g_trusted = false;
        g_subscribed = false;
        g_peer_bonded = NimBLEDevice::isBonded(info.getIdAddress());
        g_mtu = info.getMTU();
        store_conn_params(info);
        g_connected_at_ms = millis();
        g_conn_handle = handle;
        g_connected = true;
        server->updateConnParams(handle, config::kConnIntervalMinUnits, config::kConnIntervalMaxUnits,
                                 config::kConnLatency, config::kSupervisionTimeoutUnits);
        server->setDataLen(handle, config::kDataLengthOctets);
        NimBLEDevice::startSecurity(handle);
    }

    void onDisconnect(NimBLEServer*, NimBLEConnInfo&, int reason) override {
        g_connected = false;
        g_subscribed = false;
        g_trusted = false;
        g_peer_bonded = false;
        g_conn_handle = BLE_HS_CONN_HANDLE_NONE;
        g_disconnect_reason = reason;
        g_disconnect_event = true;
    }

    void onMTUChange(uint16_t mtu, NimBLEConnInfo&) override {
        g_mtu = mtu;
    }

    uint32_t onPassKeyDisplay() override {
        return g_passkey.load();
    }

    void onAuthenticationComplete(NimBLEConnInfo&) override {
        g_auth_event = true;
    }

    void onConnParamsUpdate(NimBLEConnInfo& info) override {
        store_conn_params(info);
        g_report_generation++;
    }
};

class StreamCallbacks : public NimBLECharacteristicCallbacks {
  public:
    void onSubscribe(NimBLECharacteristic*, NimBLEConnInfo&, uint16_t sub_value) override {
        bool enabled = (sub_value & kNotificationsEnabledBit) != 0;
        g_subscribed = enabled;
        if (enabled) {
            g_report_generation++;
        }
    }
};

class ControlCallbacks : public NimBLECharacteristicCallbacks {
  public:
    void onWrite(NimBLECharacteristic* characteristic, NimBLEConnInfo&) override {
        NimBLEAttValue value = characteristic->getValue();
        g_control_mailbox = packed_control(value.data(), value.length());
    }
};

class InfoCallbacks : public NimBLECharacteristicCallbacks {
  public:
    // NimBLE calls onRead only for the offset-0 Read, so every Read Blob of one long read gets the same bytes.
    void onRead(NimBLECharacteristic* characteristic, NimBLEConnInfo&) override {
        size_t length = g_info_writer(g_info_json, sizeof(g_info_json));
        if (length > 0) {
            characteristic->setValue(reinterpret_cast<const uint8_t*>(g_info_json), length);
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

void configure_advertising_data(const char* device_name) {
    NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
    advertising->enableScanResponse(true);
    advertising->setName(device_name);
    advertising->addServiceUUID(config::kServiceUuid);
}

void apply_interval(NimBLEAdvertising* advertising, AdvertisingSpeed speed) {
    bool fast = speed == AdvertisingSpeed::Fast;
    advertising->setMinInterval(fast ? config::kFastAdvertisingMinUnits : config::kSlowAdvertisingMinUnits);
    advertising->setMaxInterval(fast ? config::kFastAdvertisingMaxUnits : config::kSlowAdvertisingMaxUnits);
}

void restart_advertising(bool whitelist_only, AdvertisingSpeed speed) {
    NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
    advertising->stop();
    advertising->setScanFilter(false, whitelist_only);
    apply_interval(advertising, speed);
    advertising->start();
    g_advertising.whitelist_only = whitelist_only;
    g_advertising.speed = speed;
}

bool advertising_needs_restart(bool whitelist_only, AdvertisingSpeed wanted) {
    bool active = NimBLEDevice::getAdvertising()->isAdvertising();
    return !active || whitelist_only != g_advertising.whitelist_only || wanted != g_advertising.speed;
}

LinkParams current_params() {
    return LinkParams{g_interval_units.load(), g_latency.load(), g_supervision_units.load()};
}

}  // namespace

void ble_link_begin(const char* device_name, InfoWriter info_writer) {
    g_info_writer = info_writer;
    NimBLEDevice::init(device_name);
    NimBLEDevice::setPower(config::kBleTxPowerDbm);
    NimBLEDevice::setMTU(config::kPreferredMtu);
    configure_security();
    ble_gatts_set_clt_cfg_perm_flags(kCccdPermissions);
    create_gatt();
    configure_advertising_data(device_name);
}

void ble_link_set_passkey(uint32_t passkey) {
    g_passkey = passkey;
    NimBLEDevice::setSecurityPasskey(passkey);
}

void ble_link_start_advertising(bool whitelist_only, uint32_t now_ms) {
    g_advertising.since_ms = now_ms;
    restart_advertising(whitelist_only, AdvertisingSpeed::Fast);
}

void ble_link_poll_advertising(bool whitelist_only, uint32_t now_ms) {
    if (g_disconnect_event.exchange(false)) {
        g_advertising.since_ms = now_ms;
    }
    if (g_connected.load()) {
        return;
    }
    AdvertisingSpeed wanted = advertising_speed(now_ms - g_advertising.since_ms);
    if (advertising_needs_restart(whitelist_only, wanted)) {
        restart_advertising(whitelist_only, wanted);
    }
}

LinkSnapshot ble_link_snapshot() {
    LinkSnapshot snapshot{};
    snapshot.connected = g_connected.load();
    snapshot.subscribed = g_subscribed.load();
    snapshot.trusted = g_trusted.load();
    snapshot.peer_bonded = g_peer_bonded.load();
    snapshot.mtu = g_mtu.load();
    snapshot.connected_at_ms = g_connected_at_ms.load();
    snapshot.params = current_params();
    snapshot.last_disconnect_reason = g_disconnect_reason.load();
    return snapshot;
}

uint32_t ble_link_report_generation() {
    return g_report_generation.load();
}

int8_t ble_link_tx_power() {
    return static_cast<int8_t>(NimBLEDevice::getPower());
}

bool ble_link_take_auth_event() {
    return g_auth_event.exchange(false);
}

PeerSecurity ble_link_peer_security() {
    PeerSecurity peer{};
    ble_gap_conn_desc desc{};
    uint16_t handle = g_conn_handle.load();
    if (handle == BLE_HS_CONN_HANDLE_NONE || ble_gap_conn_find(handle, &desc) != 0) {
        return peer;
    }
    peer.valid = true;
    peer.secure = link_is_secure(desc.sec_state.encrypted, desc.sec_state.bonded, desc.sec_state.authenticated,
                                 config::kRequireMitm);
    peer.identity = NimBLEAddress(desc.peer_id_addr);
    return peer;
}

void ble_link_set_trusted(bool trusted) {
    g_trusted = trusted;
}

ControlCommand ble_link_take_control() {
    uint32_t mailbox = g_control_mailbox.exchange(0);
    if ((mailbox & kControlMailboxFull) == 0) {
        return ControlCommand{ControlKind::None, 0};
    }
    return unpacked_control(mailbox);
}

bool ble_link_notify(const uint8_t* bytes, size_t length) {
    uint16_t handle = g_conn_handle.load();
    if (handle == BLE_HS_CONN_HANDLE_NONE) {
        return false;
    }
    return g_stream->notify(bytes, length, handle);
}

void ble_link_disconnect() {
    uint16_t handle = g_conn_handle.load();
    if (handle != BLE_HS_CONN_HANDLE_NONE) {
        g_server->disconnect(handle);
    }
}
