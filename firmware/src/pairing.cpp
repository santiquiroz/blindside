#include "pairing.h"

#include <Arduino.h>
#include <Preferences.h>
#include <esp_random.h>

#include <atomic>

#include "ble_link.h"
#include "blindside_config.h"
#include "bond_guard.h"
#include "serial_command.h"

namespace {

constexpr uint32_t kNoStoredPasskey = UINT32_MAX;

struct KeptBonds {
    KeptBond bonds[kMaxBonds];
};

struct ConnectedIdentities {
    NimBLEAddress identities[kMaxLinks];
    size_t count;
};

std::atomic<uint8_t> g_bond_count{0};

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

void adopt_trusted(PairingState& state, const TrustedBonds& trusted) {
    state.trusted = trusted;
    bond_store_refresh_whitelist(trusted);
    bond_guard_trust(trusted);
    g_bond_count = static_cast<uint8_t>(trusted.count);
}

void open_window(PairingState& state, uint32_t now_ms) {
    state.window = window_opened(now_ms);
    Serial.println("pairing window open (60 s)");
}

void reset_pairing(PairingState& state, uint32_t now_ms) {
    bond_store_forget_all();
    adopt_trusted(state, TrustedBonds{});
    state.session_flags = 0;
    // The erased peers keep their encrypted links until they drop, so they are dropped now.
    ble_link_disconnect_all();
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

bool track_slot(PairingState& state, uint8_t slot, const LinkSnapshot& link) {
    if (link.link_id == state.slots[slot].link_id) {
        return false;
    }
    state.slots[slot] = SlotPairing{link.link_id, state.window.open, false};
    return true;
}

bool pairing_allowed(const PairingState& state, uint8_t slot) {
    return state.window.open || state.slots[slot].pairing_allowed;
}

void close_pairing_allowances(PairingState& state) {
    for (SlotPairing& slot : state.slots) {
        slot.pairing_allowed = false;
    }
}

bool identity_connected(const NimBLEAddress& identity) {
    for (uint8_t slot = 0; slot < kMaxLinks; ++slot) {
        PeerSecurity peer = ble_link_peer_security(slot);
        if (peer.valid && peer.identity == identity) {
            return true;
        }
    }
    return false;
}

KeptBonds kept_bonds(const TrustedBonds& trusted) {
    KeptBonds kept{};
    for (size_t i = 0; i < trusted.count; ++i) {
        kept.bonds[i] = KeptBond{trusted.roles[i], identity_connected(trusted.identities[i])};
    }
    return kept;
}

void forget_replaced_bond(PairingState& state, const BondPlan& plan) {
    if (plan.admission != BondAdmission::Replace) {
        return;
    }
    Serial.printf("pairing: replaced %s (%s)\n", state.trusted.identities[plan.replaced].toString().c_str(),
                  role_name(state.trusted.roles[plan.replaced]));
    state.trusted = bond_store_without(state.trusted, plan.replaced);
    state.session_flags = session_flags_without_bond(state.session_flags, plan.replaced);
}

void adopt_new_bond(PairingState& state, const NimBLEAddress& identity) {
    adopt_trusted(state, bond_store_with(state.trusted, identity));
    bond_store_forget_untrusted(state.trusted);
    bond_store_save_roles(state.trusted);
    state.window = window_closed();
    close_pairing_allowances(state);
    Serial.printf("pairing: bonded %s\n", identity.toString().c_str());
}

bool admitted_new_bond(PairingState& state, const NimBLEAddress& identity) {
    BondPlan plan = plan_new_bond(kept_bonds(state.trusted).bonds, state.trusted.count);
    if (plan.admission == BondAdmission::Reject) {
        Serial.println("pairing: new bond refused, no idle phone bond to replace and the watch bond stays");
        return false;
    }
    forget_replaced_bond(state, plan);
    adopt_new_bond(state, identity);
    return true;
}

void reject_peer(const PairingState& state, uint8_t slot, const PeerSecurity& peer) {
    if (!bond_store_contains(state.trusted, peer.identity)) {
        bond_store_forget(peer.identity);
    }
    ble_link_disconnect(slot);
    Serial.println("pairing: rejected peer");
}

bool decision_accepted(PairingState& state, AuthDecision decision, const NimBLEAddress& identity) {
    if (decision == AuthDecision::AcceptNewBond) {
        return admitted_new_bond(state, identity);
    }
    return decision == AuthDecision::AcceptTrusted;
}

void handle_auth_event(PairingState& state, uint8_t slot) {
    if (!ble_link_take_auth_event(slot)) {
        return;
    }
    PeerSecurity peer = ble_link_peer_security(slot);
    if (!peer.valid) {
        return;
    }
    bool trusted = bond_store_contains(state.trusted, peer.identity);
    AuthDecision decision = decide_authentication(peer.secure, trusted, pairing_allowed(state, slot));
    if (!decision_accepted(state, decision, peer.identity)) {
        reject_peer(state, slot, peer);
        return;
    }
    ble_link_set_trusted(slot, true);
}

bool peer_must_go(const PairingState& state, uint8_t slot, const LinkSnapshot& link, uint32_t now_ms) {
    bool allowed = pairing_allowed(state, slot);
    return should_drop_at_connect(allowed, link.peer_bonded) ||
           should_drop_unauthenticated(link.connected_at_ms, now_ms, allowed);
}

void drop_unwanted_peer(PairingState& state, uint8_t slot, uint32_t now_ms) {
    LinkSnapshot link = ble_link_snapshot(slot);
    bool waiting = link.connected && !link.trusted && !state.slots[slot].drop_requested;
    if (!waiting || !peer_must_go(state, slot, link, now_ms)) {
        return;
    }
    state.slots[slot].drop_requested = true;
    ble_link_disconnect(slot);
    Serial.println("pairing: dropped an unknown or unauthenticated peer");
}

int bond_index_of_slot(const PairingState& state, uint8_t slot) {
    PeerSecurity peer = ble_link_peer_security(slot);
    return peer.valid ? bond_store_index_of(state.trusted, peer.identity) : -1;
}

bool poll_slot(PairingState& state, uint8_t slot, uint32_t now_ms) {
    bool link_changed = track_slot(state, slot, ble_link_snapshot(slot));
    handle_auth_event(state, slot);
    drop_unwanted_peer(state, slot, now_ms);
    return link_changed;
}

ConnectedIdentities connected_identities() {
    ConnectedIdentities connected{};
    for (uint8_t slot = 0; slot < kMaxLinks; ++slot) {
        PeerSecurity peer = ble_link_peer_security(slot);
        if (peer.valid) {
            connected.identities[connected.count++] = peer.identity;
        }
    }
    return connected;
}

// A device that bonds and drops before its auth event is handled leaves a bond that would fill NimBLE's store.
void forget_stale_bonds(const PairingState& state) {
    ConnectedIdentities connected = connected_identities();
    size_t forgotten = bond_store_forget_stale(state.trusted, connected.identities, connected.count);
    if (forgotten > 0) {
        Serial.printf("pairing: forgot %u stale bond(s)\n", static_cast<unsigned>(forgotten));
    }
}

void report_store_guard() {
    BondGuardEvents events = bond_guard_take_events();
    if (events.evicted > 0) {
        Serial.printf("pairing: bond store full, deleted %lu untrusted bond(s)\n",
                      static_cast<unsigned long>(events.evicted));
    }
    if (events.refused > 0) {
        Serial.printf("pairing: bond store full, refused %lu write(s) to keep the trusted bonds\n",
                      static_cast<unsigned long>(events.refused));
    }
}

bool poll_slots(PairingState& state, uint32_t now_ms) {
    bool links_changed = false;
    for (uint8_t slot = 0; slot < kMaxLinks; ++slot) {
        links_changed = poll_slot(state, slot, now_ms) || links_changed;
    }
    return links_changed;
}

}  // namespace

PairingState pairing_begin(uint32_t now_ms) {
    bond_guard_install();
    PairingState state{};
    install_passkey(state, load_or_create_passkey());
    adopt_trusted(state, bond_store_load());
    state.window = initial_window(state.trusted.count > 0, now_ms);
    return state;
}

void pairing_poll(PairingState& state, uint32_t now_ms) {
    handle_button(state, now_ms);
    handle_serial(state);
    state.window = window_after_tick(state.window, now_ms, state.trusted.count > 0);
    if (poll_slots(state, now_ms)) {
        forget_stale_bonds(state);
    }
    report_store_guard();
}

void pairing_open_window(PairingState& state, uint32_t now_ms) {
    open_window(state, now_ms);
}

void pairing_note_role(PairingState& state, uint8_t slot, LinkRole role) {
    int index = bond_index_of_slot(state, slot);
    if (index < 0 || state.trusted.roles[index] == role) {
        return;
    }
    state.trusted = bond_store_with_role(state.trusted, static_cast<size_t>(index), role);
    bond_store_save_roles(state.trusted);
}

void pairing_note_session(PairingState& state, uint8_t slot, bool active) {
    int index = bond_index_of_slot(state, slot);
    if (index >= 0) {
        state.session_flags = session_flags_after_write(state.session_flags, static_cast<size_t>(index), active);
    }
}

bool pairing_session_running(const PairingState& state) {
    return session_running(state.session_flags, kept_bonds(state.trusted).bonds, state.trusted.count);
}

bool pairing_whitelist_only(const PairingState& state) {
    return config::kConnectWhitelistOnly && !state.window.open && state.trusted.count > 0;
}

uint8_t pairing_bond_count() {
    return g_bond_count.load();
}
