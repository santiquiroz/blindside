#include "pairing.h"

#include <Arduino.h>
#include <Preferences.h>
#include <esp_random.h>

#include "ble_link.h"
#include "blindside_config.h"
#include "serial_command.h"

namespace {

constexpr uint32_t kNoStoredPasskey = UINT32_MAX;

uint32_t stored_passkey() {
    Preferences preferences;
    preferences.begin(config::kPreferencesNamespace, true);
    uint32_t passkey = preferences.getUInt(config::kPasskeyKey, kNoStoredPasskey);
    preferences.end();
    return passkey;
}

uint32_t store_new_passkey() {
    uint32_t passkey = passkey_from_random(esp_random());
    Preferences preferences;
    preferences.begin(config::kPreferencesNamespace, false);
    preferences.putUInt(config::kPasskeyKey, passkey);
    preferences.end();
    return passkey;
}

uint32_t load_or_create_passkey() {
    uint32_t passkey = stored_passkey();
    return passkey < kPasskeyModulus ? passkey : store_new_passkey();
}

void print_passkey(uint32_t passkey) {
    Serial.printf("blindside passkey: %06lu\n", static_cast<unsigned long>(passkey));
}

void install_passkey(PairingState& state, uint32_t passkey) {
    state.passkey = passkey;
    ble_link_set_passkey(passkey);
    print_passkey(passkey);
}

NimBLEAddress load_trusted_bond() {
    int bonds = NimBLEDevice::getNumBonds();
    if (bonds == 1) {
        return NimBLEDevice::getBondedAddress(0);
    }
    if (bonds > 1) {
        NimBLEDevice::deleteAllBonds();
    }
    return NimBLEAddress();
}

void clear_whitelist() {
    for (size_t i = NimBLEDevice::getWhiteListCount(); i > 0; --i) {
        NimBLEDevice::whiteListRemove(NimBLEDevice::getWhiteListAddress(i - 1));
    }
}

void refresh_whitelist(const NimBLEAddress& trusted) {
    clear_whitelist();
    if (!trusted.isNull()) {
        NimBLEDevice::whiteListAdd(trusted);
    }
}

void delete_bonds_except(const NimBLEAddress& keep) {
    for (int i = NimBLEDevice::getNumBonds() - 1; i >= 0; --i) {
        NimBLEAddress bonded = NimBLEDevice::getBondedAddress(i);
        if (bonded != keep) {
            NimBLEDevice::deleteBond(bonded);
        }
    }
}

void open_window(PairingState& state, uint32_t now_ms) {
    state.window = window_opened(now_ms);
    // With a peer connected the belt does not advertise, so a new watch could never find it.
    ble_link_disconnect();
    Serial.println("pairing window open (60 s)");
}

void reset_pairing(PairingState& state, uint32_t now_ms) {
    NimBLEDevice::deleteAllBonds();
    state.trusted = NimBLEAddress();
    refresh_whitelist(state.trusted);
    Serial.println("pairing: bonds erased");
    open_window(state, now_ms);
}

void apply_gesture(PairingState& state, ButtonGesture gesture, uint32_t now_ms) {
    if (gesture == ButtonGesture::OpenPairingWindow) {
        open_window(state, now_ms);
    }
    if (gesture == ButtonGesture::ResetPairing) {
        reset_pairing(state, now_ms);
    }
}

bool button_released(PairingState& state, uint32_t now_ms) {
    bool pressed = digitalRead(config::kBootButtonPin) == LOW;
    if (pressed && !state.button_was_pressed) {
        state.button_pressed_ms = now_ms;
    }
    bool released = state.button_was_pressed && !pressed;
    state.button_was_pressed = pressed;
    return released;
}

void handle_button(PairingState& state, uint32_t now_ms) {
    if (!gestures_still_counted(now_ms)) {
        return;
    }
    if (button_released(state, now_ms)) {
        apply_gesture(state, gesture_on_release(now_ms - state.button_pressed_ms, now_ms), now_ms);
    }
}

void run_serial_command(PairingState& state, SerialCommand command) {
    if (command == SerialCommand::ShowKey) {
        print_passkey(state.passkey);
    }
    if (command == SerialCommand::NewKey) {
        install_passkey(state, store_new_passkey());
    }
}

void append_serial_char(PairingState& state, char c) {
    if (state.serial_length < kSerialLineCapacity) {
        state.serial_line[state.serial_length++] = c;
    }
}

void handle_serial(PairingState& state) {
    while (Serial.available() > 0) {
        char c = static_cast<char>(Serial.read());
        if (c != '\n') {
            append_serial_char(state, c);
            continue;
        }
        run_serial_command(state, parse_serial_line(state.serial_line, state.serial_length));
        state.serial_length = 0;
    }
}

void track_connection(PairingState& state) {
    bool connected = ble_link_snapshot().connected;
    if (connected && !state.connection_seen) {
        state.pairing_allowed_for_connection = state.window.open;
        state.drop_requested = false;
    }
    state.connection_seen = connected;
}

bool pairing_allowed(const PairingState& state) {
    return state.window.open || state.pairing_allowed_for_connection;
}

void adopt_new_bond(PairingState& state, const NimBLEAddress& identity) {
    delete_bonds_except(identity);
    state.trusted = identity;
    refresh_whitelist(identity);
    state.window = window_closed();
    Serial.printf("pairing: bonded %s\n", identity.toString().c_str());
}

void reject_peer(const PairingState& state, const PeerSecurity& peer) {
    if (peer.identity != state.trusted) {
        NimBLEDevice::deleteBond(peer.identity);
    }
    ble_link_disconnect();
    Serial.println("pairing: rejected peer");
}

void apply_decision(PairingState& state, AuthDecision decision, const PeerSecurity& peer) {
    if (decision == AuthDecision::Reject) {
        reject_peer(state, peer);
        return;
    }
    if (decision == AuthDecision::AcceptNewBond) {
        adopt_new_bond(state, peer.identity);
    }
    ble_link_set_trusted(true);
}

void handle_auth_event(PairingState& state) {
    if (!ble_link_take_auth_event()) {
        return;
    }
    PeerSecurity peer = ble_link_peer_security();
    if (!peer.valid) {
        return;
    }
    bool trusted = !state.trusted.isNull() && peer.identity == state.trusted;
    apply_decision(state, decide_authentication(peer.secure, trusted, pairing_allowed(state)), peer);
}

bool peer_must_go(const PairingState& state, const LinkSnapshot& link, uint32_t now_ms) {
    bool allowed = pairing_allowed(state);
    return should_drop_at_connect(allowed, link.peer_bonded) ||
           should_drop_unauthenticated(link.connected_at_ms, now_ms, allowed);
}

void drop_unwanted_peer(PairingState& state, uint32_t now_ms) {
    LinkSnapshot link = ble_link_snapshot();
    bool waiting = link.connected && !link.trusted && !state.drop_requested;
    if (!waiting || !peer_must_go(state, link, now_ms)) {
        return;
    }
    state.drop_requested = true;
    ble_link_disconnect();
    Serial.println("pairing: dropped an unknown or unauthenticated peer");
}

}  // namespace

PairingState pairing_begin(uint32_t now_ms) {
    PairingState state{};
    install_passkey(state, load_or_create_passkey());
    state.trusted = load_trusted_bond();
    refresh_whitelist(state.trusted);
    state.window = initial_window(!state.trusted.isNull(), now_ms);
    return state;
}

void pairing_poll(PairingState& state, uint32_t now_ms) {
    handle_button(state, now_ms);
    handle_serial(state);
    state.window = window_after_tick(state.window, now_ms);
    track_connection(state);
    handle_auth_event(state);
    drop_unwanted_peer(state, now_ms);
}

bool pairing_whitelist_only(const PairingState& state) {
    return config::kConnectWhitelistOnly && !state.window.open && !state.trusted.isNull();
}
