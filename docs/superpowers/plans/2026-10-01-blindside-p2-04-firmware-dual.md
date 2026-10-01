# Blindside fase 2 — Plan 04: firmware con doble conexión (reloj + celular) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Belt firmware 0.2.0 that serves the watch and the phone at the same time (or either alone): two NimBLE connections; up to two bonds with a role-aware replacement rule that never trades away the only watch bond; the new `control` commands `05 OPEN_PAIRING_WINDOW` and `06 SET_ROLE`; watch-first notification where only the phone is dropped, with a per-link backlog cap so a stalled phone cannot starve the watch; notifications bound to the connection they were planned for; `info` with `conns`/`bonds` inside 512 B; session state per bonded device; a per-link `diag` line with the pairing window and buffer health; the updated `protocol/PROTOCOL.md`, contracts note and hardware checklist; and the bench E2E that gates the merge (flash on COM6, watch regression over ADB).

**Architecture:** Every decision that can be pure goes into `firmware/lib/belt_rules/` and gets native Unity tests: link roles and connection parameters (including the phone's one-time re-request), slot lookup and the advertising rule, the shared-`info` read guard, the fan-out (notify order, retry-vs-drop, the backlog cap, per-link counters), the bond plan, roles and session flags per bond, the `05`/`06` parsing and the action each `control` write gets, the LED rule during a session, the `info` JSON and the `diag` text. The Arduino glue in `firmware/src/` moves from one connection to **link slots** in an expand-contract sequence: Task 2 gives `ble_link` per-slot state, a slot API, a per-slot `control` queue and a small module that owns every NimBLE-internal call, while keeping thin single-link wrappers; Tasks 3-7 move each caller (stream sender, pairing, control, `info`, diagnostics) to slots; Task 8 deletes the wrappers and switches NimBLE to two connections; Task 10 runs the automatable part of spec §7 on the bench. Every code task ends with all native suites green and a green `esp32dev` build. The stream packet format, the bundler and the shared vectors do not change.

**Tech Stack:** PlatformIO 6.2 (`python -m platformio`), `espressif32` / Arduino core 2.0.17 / ESP-IDF 4.4, NimBLE-Arduino 2.5.1 (`h2zero/NimBLE-Arduino@^2`), Unity 2.6.0, C++ `gnu++17`, native suites run locally with `python tools/run_native_tests.py` (Zig toolchain from PyPI); Task 10 uses `pyserial` and `adb`, both installed on the bench PC. Every line of firmware code in this plan was compiled and its tests run on 2026-10-01 against those exact versions, task by task (18 native suites green at the end, `esp32dev` build green after every task, the new and changed suites cross-compiled for `esp32dev`); the Task 10 script's checks were run against sample `diag` lines.

**Spec:** [`docs/superpowers/specs/2026-10-01-blindside-android-companion-design.md`](../specs/2026-10-01-blindside-android-companion-design.md) §2 (and §5, §7 where they touch the belt). Background: MVP spec [`2026-09-30-blindside-v1-design.md`](../specs/2026-09-30-blindside-v1-design.md) §4, contracts [`2026-09-30-blindside-mvp-00-contracts.md`](2026-09-30-blindside-mvp-00-contracts.md), MVP firmware plan [`2026-09-30-blindside-mvp-02-firmware.md`](2026-09-30-blindside-mvp-02-firmware.md). Read the phase-2 spec §2 before Task 1.

## Global Constraints

- **Always work in a worktree.** Other phase-2 plans run at the same time and may switch the branch of the main checkout. Create it once: `git -C C:/personal/blindside worktree add C:/personal/blindside-p2-firmware -b feat/p2-firmware-dual main`. In this plan `<repo>` means `C:/personal/blindside-p2-firmware`: firmware commands run from `<repo>/firmware`, the docs commit from `<repo>`. Never `cd` into `C:/personal/blindside` and never switch its branch. PlatformIO downloads NimBLE-Arduino into the worktree's `.pio/` on the first build, and `tools/run_native_tests.py` downloads Unity on its first run.
- **Regla de oro (spec):** nothing in this phase may break the watch alone. This plan never merges into `main` and never pushes; the branch is merged by the orchestrator only after all tests pass **and** the watch regression E2E passes (Task 10 Part A = spec §7 step 2 = checklist H21).
- Commits: conventional prefix (`feat:`, `fix:`, `refactor:`, `test:`, `chore:`, `docs:`) with the description in Spanish; the git author is preconfigured; **never** add a `Co-Authored-By` line; never `--no-verify`; never push; no destructive git (`reset --hard`, `clean`, `checkout -- .`, `branch -D`, `worktree remove`).
- Owner code rules (mandatory): atomic functions whose name says what they do; low cyclomatic complexity (extract branches, early returns); **no doc comments** — only a one-line comment when the *why* is not obvious; pure functions with explicit inputs and outputs (small structs by value); immutable data where the language allows it; files ≤ 400 lines typical.
- Tests:
  - `python tools/run_native_tests.py [suite …]` (from `firmware/`) builds and runs the native Unity suites with Zig (`python -m pip install --user ziglang` once if missing). It prints `N suites passed, M failed`.
  - `python -m platformio run -e esp32dev` must end in `[SUCCESS]`. Every task runs it, because `src/` is not covered by native tests.
  - CI (`.github/workflows/ci.yml`, job `firmware`: `pio test -e native` and `pio run -e esp32dev`) stays the authority; nothing in it changes.
- **The bench is attached** (checked 2026-10-01): the belt's ESP32 on `COM6` (Silicon Labs CP210x), the watch (`adb shell getprop ro.build.characteristics` contains `watch`, model SM-L310, e.g. `192.168.10.116:<port>`) and the phone (SM-S938B, `192.168.10.119:<port>`) on `adb` over Wi-Fi. Tasks 1-9 never touch them. Task 10 is the only one that does, and only with these actions: flash the application (`-t upload`, which keeps NVS: passkey, bonds and roles survive), read COM6 (opened without resetting the board, or with Task 10's explicit reset), `adb shell am start` / `am force-stop` of `io.github.santiquiroz.blindside`, and `adb shell cmd bluetooth_manager disable|enable` on the phone. Never erase flash or NVS (`-t erase`, `erase_flash`), never send `key new`, never press BOOT. Whatever cannot be automated goes to `firmware/HARDWARE_CHECKLIST.md` (Task 9).
- Do not touch `protocol/vectors/*`, `firmware/test/vectors.h` or `protocol/tools/make_vectors.py`: the stream packet format does not change.
- Spec §2 values (verbatim, binding, except the two marked deviations, which are in "Decisions Santiago must confirm"):
  - `CONFIG_BT_NIMBLE_MAX_CONNECTIONS=2`; while there are fewer than 2 connections the belt keeps advertising. **Deviation:** with one link up the belt advertises only while a bonded device is away or the pairing window is open (design note "Advertising with one link up").
  - Up to **2** bonds; the controller filter-accept list holds **all** trusted identities; a new pairing with 2 bonds replaces the bond that is **not** connected; if both are connected it is refused. **Deviation:** the only watch bond is never the one replaced (design note "Bond replacement").
  - Pairing window: without bonds open until the first bond (as today); with bonds 60 s by the BOOT gesture (first 60 s, as today) or by `05 OPEN_PAIRING_WINDOW`, accepted **only from a trusted link**.
  - `06 SET_ROLE <0=watch, 1=phone>` per connection; a link that never sends it is a watch.
  - Connection parameters: watch 30-50 ms, phone 60-100 ms. Each packet is notified **to the watch first**. When the notification queue is full, phone packets are dropped and never watch packets; each connection has its own counter.
  - Each packet goes to **each** trusted, subscribed connection; the bundler and the format do not change.
  - `info` adds `"conns":[{"role":"watch","itvl_ms":45.0,"lat":0,"timeout_ms":5000,"sent":N,"dropped":N}, …]` and `"bonds":K`, within 512 B.
  - `SESSION_ACTIVE` is stored per connection; `IDENTIFY` is ignored if **any** connection has the session active. (Implemented per bonded device, which is what "per connection" needs once connections come and go: design note "Session state per bonded device".)
  - The serial `diag` line shows each connection (role, trusted, sub, itvl, sent, dropped).
  - Security otherwise unchanged: an unauthenticated link is expelled, the passkey is per device, characteristics are encrypted.

## Review Focus

1. **The phone reads `info` while the watch is in the middle of its long read** (both read right after connecting, or the phone's Belt screen polls) → the watch must never get JSON spliced from two documents, and its `mtuFromInfo` must see a link MTU that can stream: a copy built for a link below MTU 247 (a phone whose MTU request timed out) is rebuilt for a reader at ≥ 247, otherwise the watch's `effectiveMtu = min(callback, info)` would be 23 and it would end in "MTU insuficiente". Pinned by `test_info_is_shared_for_two_seconds_after_another_link_reads_it`, `test_info_share_window_survives_a_millis_wrap` and `test_a_copy_built_below_mtu_247_is_rebuilt_for_a_reader_that_can_stream` (Task 1) and `test_info_json_has_a_single_mtu_key_for_the_watch_reader` (Task 6).
2. **The phone's link stalls or dies while the watch streams** (phone at 60-100 ms with the body blocking it, Bluetooth off, out of range, up to its 4 s supervision timeout) → NimBLE's notify only fails after shared resources run out (controller ACL credits are global, every link queues into the same msys pool), so the drop rule alone does not keep the watch from waiting. Every link other than the first in notify order is capped at `kUnprotectedBacklogCap` = 3 packets held (controller + host queue, read from NimBLE's per-connection counters); beyond that its packet is dropped and counted without a notify. Pinned by `test_a_full_queue_retries_the_watch_and_never_drops_it`, `test_a_full_queue_drops_the_phone_packet_and_moves_on`, `test_a_phone_at_its_backlog_cap_is_dropped_without_a_notify` and `test_the_first_link_in_notify_order_is_never_capped` (Task 3). The isolation itself is glue (`nimble_link_backlog`, Task 2) and is checked on the bench by H32 / Task 10 Part B: the watch's recording has no `seq` gaps, `dropped(watch)=0`, `tx[skipped]` stays flat and `acl` never reaches 0.
3. **A rival connects into the free slot (or reconnects in a loop) while the watch streams** → the rival never receives `stream` and does not touch the watch's delivery, even when it takes the watch's freed slot inside a flush retry (~90 ms): `ble_link_notify(slot, link_id, …)` sends only to the connection the delivery was planned for, and only while it is trusted and subscribed. Pinned by `test_a_rival_that_never_subscribes_does_not_touch_the_watch_stream` and `test_deliveries_name_the_connection_they_were_planned_for` (Task 3) and the existing `test_unknown_peers_are_dropped_at_once_outside_the_window`; bench check H22 (including a rival reconnecting in a loop while the watch's Bluetooth is toggled off and on).
4. **The phone asks the watch to open the pairing window during a game** (`/belt/open-pairing` → `05` while a session is active) → the window opens but the LED stays dark (MVP light discipline); `diag` shows it as `pair=<seconds>`. Pinned by `test_pairing_window_stays_dark_while_a_session_runs` (Task 5) and `test_an_open_window_counts_down_its_seconds` (Task 7).
5. **IDENTIFY and SESSION_ACTIVE across devices and reconnections** (the phone writes `04 00` or `03` while the watch is in a game; the watch drops for a moment; a phone is force-stopped and never writes `04 00`) → IDENTIFY stays refused while any bonded device holds a session, and a dead phone's stale flag cannot block the watch alone: the flag belongs to the bond, follows the device into any slot, and is cleared by that device's `04 00`, by its bond being replaced, or by the BOOT reset. Pinned by `test_identify_is_ignored_while_any_bonded_device_has_a_session` (Task 5), `test_a_session_flag_belongs_to_one_bond` and `test_replacing_a_bond_drops_its_session_flag_and_keeps_the_other` (Task 4); bench check H28.
6. **A new device pairs while two bonds exist** → it replaces an idle phone bond, never the only watch bond (the newcomer's role is unknown until it writes `06`). Pinned by the seven `plan_new_bond` tests of Task 4; bench check H27.
7. **`control` writes arrive faster than `loop()` drains them** (`06 01`, the CCCD write and `04 01` within a few connection events) **or from a link that authenticated but is not trusted** → every write is queued per link (4 deep) and applied in order; an untrusted link changes nothing. Pinned by `test_an_untrusted_link_changes_nothing` and `test_a_trusted_link_gets_the_action_of_its_command` (Task 5).

## Design notes (resolved ambiguities and deviations)

These fill gaps in spec §2. Task 9 writes the wire-visible ones into `protocol/PROTOCOL.md`.

- **`info` size (deviation, needs Santiago's OK).** The spec's `conns` entries cannot fit next to the MVP fields: with two links and every field at its longest the MVP document plus `conns`/`bonds` is 668 B. To stay ≤ 512 B the firmware (a) drops `conn` (replaced by `conns[]`; nobody reads it), (b) drops the per-IMU constants `gyro_lsb_dps`/`accel_lsb_g` (radar-core's `parseBeltInfo` already defaults to the same 65.5/4096), and (c) reports `sent`/`dropped` modulo 1 000 000. The worst case is then 511 B (Task 6 test); the realistic two-link document is 460 B. `proto` stays 1 (the packet format is unchanged), firmware version becomes `0.2.0`.
- **`info.mtu` is the reading link's own MTU**, and it is the only `"mtu"` key (the watch's `mtuFromInfo` regex takes the first one).
- **Shared `info` copy.** NimBLE stores one value per characteristic for all links and serves Read Blobs from it, so a new document built for link B in the middle of link A's long read would splice A's JSON. Rule (`info_refresh_allowed`): a Read at offset 0 builds a new document only if no **other** link did a Read at offset 0 in the last 2 s; otherwise it gets the stored copy (built for the other link, with that link's `mtu`, normally the same 255). **Exception:** if the stored copy was built for a link below MTU 247 and the reader's MTU is ≥ 247, the document is rebuilt anyway; the other link cannot stream, so splicing its long read costs nothing, and its MTU never reaches the watch. Known limit, documented: two links that both poll faster than every 2 s keep sharing one copy; the watch only reads `info` at connection.
- **Primary link and the backlog cap.** "Notify order" = watch-role links first, then phone-role links, older connection first within a role. The first link in that order is the *protected* one: on a full queue it is retried until the next cut (MVP behaviour) and the backlog is kept; every other link drops that packet, counts it, and moves on. A phone alone is therefore retried like the MVP watch. Every non-protected link is also **capped**: before it is notified the belt reads that connection's backlog from NimBLE (packets the controller holds, `bhc_outstanding_pkts`, plus packets queued in the host, `bhc_tx_q`, under `ble_hs_lock`, through the private header the MVP already uses for the CCCD permission hook); at `kUnprotectedBacklogCap` = 3 or more the packet is dropped and counted without calling notify. Why: NimBLE-Arduino's notify returns false only when an mbuf cannot be allocated; until then a packet the controller cannot take is queued and reported as sent. Controller ACL credits are global (`ble_hs_hci_avail_pkts`) and every link queues into the same msys pool, so a phone that stops acknowledging would hold both until its supervision timeout and stall or drop the watch. Three packets cover one cut plus one late connection event of a healthy phone at 60-100 ms. The controller's buffer total is printed at boot (`ble: controller acl buffers=N`, Task 7) and must be ≥ 8 (= 2 × cap + 2, checked by Task 10); `diag` shows the free ones (`acl=`).
- **Notify bound to its connection.** `ble_link_notify(slot, link_id, …)` sends only if the slot still holds the connection the delivery was planned for (`link_id`) and that connection is trusted and subscribed at send time. NimBLE-Arduino 2.5.1's `NimBLECharacteristic::notify(…, connHandle)` calls `ble_gattc_notify_custom` without checking the CCCD or encryption, and a slot freed by a disconnect is taken by the next peer at once (advertising restarts on disconnect), possibly inside a flush retry.
- **LINK TLV** (0x04) still carries one link's parameters: the first link in notify order. Each link's own parameters are in `info.conns`.
- **Joining mid-cut.** A link that becomes deliverable while packets of a cut are pending gets packets from the next cut on (its counters start at the next `Bundle`).
- **Counters** (`sent`, `dropped`) belong to a connection: a new connection in a slot starts them at 0 (the bundler sees a new `link_id`).
- **Session state per bonded device.** `SESSION_ACTIVE` is one flag per kept bond (`PairingState.session_flags`, bit i = `trusted.identities[i]`), set and cleared by `04` that device writes on any link (its identity comes from `ble_link_peer_security(slot)`). It survives the device's disconnections and follows it into whatever slot it takes next (the MVP rule "persists across disconnections"), and it is cleared by that device's `04 00`, when its bond is replaced, or by the BOOT ≥ 10 s reset; a reboot clears all of them. `04` from an untrusted link is ignored. A flag per slot could not do this: a force-stopped phone never writes `04 00`, the watch reconnecting alone may take the other slot, and the stale `1` would block IDENTIFY and darken the BOOT-gesture LED (H9/H12) until a reboot.
- **Light rule.** While any bonded device holds a session, an open pairing window does not light the LED (otherwise `05` during a game would blink the belt).
- **Opening the window never disconnects anyone** (MVP disconnected the single peer so a new watch could find the belt; with two slots the belt advertises while one is free). With two links up there is no free slot, which is the spec's "if both are connected, refuse". The ≥ 10 s BOOT reset erases the bonds **and** disconnects every link.
- **Bond replacement (deviation, needs Santiago's OK).** Each bond carries a role, learned when that device writes `06` on a trusted link and kept in NVS (`blindside/bond_roles`, identity → role; a bond with no record is a watch, so the MVP watch's bond is one). With two bonds a new device replaces the newest phone-role bond that is not connected; failing that, if there are two watch-role bonds, the newest one that is not connected; otherwise the new bond is refused. So the only watch bond is never traded for a newcomer whose role is still unknown (it writes `06` only after bonding), whether the watch is away while an old phone is connected or neither device is connected. This is the spec's "replace the bond that is not connected" except when that bond is the only watch. A lost or stolen device is revoked with the BOOT ≥ 10 s reset, which erases every bond: the MVP's "a new bond replaces the old one" no longer revokes it. At boot, more than two stored bonds (a crash between NimBLE storing a third bond and the belt deciding) keep the two oldest. `CONFIG_BT_NIMBLE_MAX_BONDS=3` so NimBLE never auto-deletes a bond (its store-full policy deletes the oldest, which could be the connected watch) before the belt decides.
- **Only the first pairing of a window is kept.** When a bond is adopted the window closes and every slot's pairing allowance is cleared, so a second device that connected during the same window cannot also bond.
- **Advertising with one link up (deviation, needs Santiago's OK).** Spec §2 says that with only the watch connected the belt advertises again "but the filter-accept list still keeps a rival from connecting". That mitigation does not hold: the firmware ships `config::kConnectWhitelistOnly = false` (spike S10: the classic ESP32 resolves RPAs in the host), so anyone can connect into the free slot, hold it through a 60 s pairing window (keeping the phone from pairing), or reconnect in a loop and take radio time from the watch. With `config::kSecondLinkAdvertisingOnDemand = true` (default) the belt advertises with one link up only while a bonded device is not connected or the pairing window is open (`advertising_wanted`), so a watch-only user (one bond) gets exactly 0.1.0's behaviour: no advertising while the watch is connected. `false` restores the spec's literal rule. The host-side drop of unknown peers (< 0.5 s outside the window) is unchanged.
- **Phone connection parameters.** When a phone-role link reports parameters outside 48-80 units or with latency > 0, the belt asks for 60-100 ms once more (once per `06`), because the central's later request wins: plan 06's `LOW_POWER` after setup (100-125 ms, latency 2) overrides the request the belt made on `06 01`. The watch is never asked again (MVP behaviour; the watch app tunes its own priority). The real fix is in the phone app (cross-plan flag 7).
- **Supervision timeout stays 4 s for both roles.** A shorter phone timeout would shorten the dead-phone window, but the backlog cap already isolates the watch during it, and a phone in a pocket behind the body would drop its link more often.
- **`control` writes are queued and trust-checked.** Each slot has a FreeRTOS queue of 4 parsed commands, filled in `onWrite` on the NimBLE host task and drained in order by `loop()` after `pairing_poll`, so a link that just authenticated is already trusted when its first write is applied. The MVP's single mailbox kept only the last write, so a phone's `06 01` could be overwritten by its `04 01`. A command from a link that is not trusted changes nothing (`control_action`), `04` and `06` included; a full queue ignores the newest write.
- **Buffers.** `CONFIG_BT_NIMBLE_MSYS1_BLOCK_COUNT` 24 → 32: each link holds its own queued notifications.
- **Host task stack.** `info` is built on the NimBLE host task, and Task 6 adds the `conns` text to it (about 0.6 KB more stack). `CONFIG_BT_NIMBLE_HOST_TASK_STACK_SIZE` goes 6144 → 8192 (RAM is at 16 %, and the stack is allocated at run time), and `diag` prints the task's lowest free stack so far (`hstk=`, from `nimble_port_freertos_get_hs_hwm()`). Task 10 and H33 require ≥ 1024 B after both links have read `info`.
- **`diag` format change.** `link[...]` becomes `link0[role trusted sub mtu itvl lat sup sent dropped] link1[...]` (`link1[-]` for a free slot) followed by `bonds=K pair=P disc=R acl=A hstk=S`: `pair` is the pairing window's seconds left (`0` closed, `open` while there is no bond), `acl` the free controller buffers and `hstk` the host task's free-stack low-water mark in bytes. `tx[sent ...]` becomes `tx[fail dropped skipped]` because `sent` is now per link.

## Decisions Santiago must confirm

- The `info` trims above (drop `conn` and the IMU scale constants, counters modulo 10⁶).
- The LED stays dark for a pairing window opened during a session.
- The BOOT ≥ 10 s reset also disconnects every link.
- Bond replacement by role: a new device replaces an idle phone bond, never the only watch bond (the pairing is refused instead, e.g. old phone connected and watch away); a lost device is revoked only by the BOOT ≥ 10 s reset.
- Advertising with one link up only while a bonded device is away or the window is open (`kSecondLinkAdvertisingOnDemand = true`), because the spec's whitelist mitigation is off (`kConnectWhitelistOnly = false`, S10).
- The phone's backlog cap (3 packets): on a weak link the phone loses more packets (counted in its `dropped`) so that the watch never waits.
- `SESSION_ACTIVE` kept per bonded device instead of per connection slot.

## Cross-plan flags (for the orchestrator and the phone/shared plans)

1. The phone must write `06 01` as its first `control` write, right after the link is encrypted and **before** enabling notifications (otherwise it competes as a second watch until it does), and on every connection. The watch app needs no change.
2. Phone `info` parser: `conns[]` with `role`, `itvl_ms`, `lat`, `timeout_ms`, `sent`, `dropped` (counters wrap at 1 000 000); `bonds` 0-2; no `conn`; no `gyro_lsb_dps`/`accel_lsb_g` (use 65.5 and 4096). `mtu` is the reader's own.
3. `05` is exactly one byte. It is ignored from a link that is not trusted; the window lasts 60 s and closes on the first bond. **It has no acknowledgement:** the GATT write succeeds even when the belt ignores it (untrusted link, both slots taken, or firmware 0.1.0), so the watch's "Pedida al cinturón" state is optimistic; the only confirmation is `diag` `pair=<seconds>` (Task 7) or the phone's pairing outcome.
4. "Pair this phone" with both the watch and an old phone connected cannot work (no free slot), and with an old phone connected and the watch away the belt refuses the new bond (it would replace the only watch bond). The phone UI should say so.
5. Spec §7 steps 2 and 5 and plan 05's checklist read the new `diag` format: `link0[role=watch trusted=1 sub=1 … sent=N dropped=0] link1[…] bonds=K pair=P disc=R acl=A hstk=S tx[…]`. Plan 05's item 21 ("la línea `diag` del cinturón muestra la ventana abierta 60 s") reads `pair=` counting down from 60.
6. Plan 05 (shared module and watch) makes the watch write `06 00` on every connection: compatible; the belt keeps the watch role and asks again for 30-50 ms.
7. **Plan 06 must not change the phone's priority after `06 01`.** `settledPriorityFor(PHONE) = LOW_POWER`, requested after setup, overrides the belt's 60-100 ms request (the central's later update wins; the comment "LOW_POWER lets the firmware's request win" is wrong), breaks spec §2 and H23 (`itvl=48…80 lat=0`) and adds latency 2 to the link the belt caps. Request `BALANCED` only while connecting, before `06 01`, and nothing afterwards. The belt asks once more (design note "Phone connection parameters"), which only masks it.
8. Plans 05 and 06 should also always work in a worktree: this plan never touches the main checkout, but two plans that `git switch` the same checkout break each other.
9. Spec §7 ownership: Task 10 of this plan runs steps 1-2 now (the merge gate) and steps 5-6 plus H32 once the plan 05 watch build and the plan 06 phone app are installed and the phone is paired. Step 4 (pairing the phone hands-free through both UIs) and step 7 (recording transfer) belong to whoever executes the apps' E2E; no plan owns them yet (plan 06 lists the autonomous E2E as out of scope).

## File structure

| File | Responsibility |
|---|---|
| `firmware/lib/belt_rules/link_roles.{h,cpp}` (new) | `kMaxLinks`, `LinkRole`, role names, connection parameters per role and the phone's one-time re-request, slot lookup by handle, the advertising rule |
| `firmware/lib/belt_rules/info_reads.{h,cpp}` (new) | When a Read at offset 0 may rebuild `info` (shared-copy guard and its MTU exception) |
| `firmware/lib/belt_rules/link_fanout.{h,cpp}` (new) | Notify order, per-link delivery cursors, retry-vs-drop, the backlog cap, per-link counters |
| `firmware/lib/belt_rules/bond_rules.{h,cpp}` (new) | Which bond a new device replaces (by role), how many bonds boot keeps, roles recalled by identity, session flags per bond |
| `firmware/lib/belt_rules/diag_format.{h,cpp}` (new) | Text of one link and of the tail (`bonds pair disc acl hstk`) of the `diag` line |
| `firmware/lib/belt_rules/control_command.{h,cpp}` | `05`, `06`, and the action each write gets from its link's trust and the session state |
| `firmware/lib/belt_rules/led_pattern.{h,cpp}` | Pairing blink suppressed during a session |
| `firmware/lib/belt_rules/info_json.{h,cpp}` | `conns`, `bonds`, trimmed fields |
| `firmware/lib/bundler/cut_schedule.{h,cpp}` | `cut_action(gate_open, delivery_pending)`; `Outbox` removed (replaced by `Fanout`) |
| `firmware/src/ble_link.{h,cpp}` | NimBLE GATT server with per-slot state, the slot API, a `control` queue per slot, notify bound to its connection |
| `firmware/src/nimble_internals.{h,cpp}` (new) | The only user of NimBLE's private headers: CCCD permissions, per-connection backlog, free controller buffers, host stack low-water mark |
| `firmware/src/ble_advertising.{h,cpp}` (new) | Advertising data, speed, restart/stop by the advertising rule (moved out of `ble_link`) |
| `firmware/src/bond_store.{h,cpp}` (new) | NimBLE bond store, filter-accept list and the per-bond roles in NVS |
| `firmware/src/pairing.{h,cpp}` | Window, passkey, BOOT, per-slot authentication and drops, two bonds with roles, session flags per bond |
| `firmware/src/stream_sender.{h,cpp}` | Bundler task with fan-out to every deliverable link, capped for the non-protected ones |
| `firmware/src/main.cpp` | `control` per slot (queued, trust-checked), the `info` writer, the advertising plan, the boot buffer line |
| `firmware/src/diagnostics.{h,cpp}` | Per-link `diag` and its tail |
| `firmware/include/blindside_config.h`, `firmware/platformio.ini` | Version 0.2.0, NimBLE limits, host stack 8192, `kSecondLinkAdvertisingOnDemand`, `kBondRolesKey` |
| `firmware/tools/bench_e2e.py` (new) | Bench E2E: reads `diag` from COM6 and checks spec §7 steps 2, 5, 6 and H32 |
| `protocol/PROTOCOL.md`, `docs/superpowers/plans/2026-09-30-blindside-mvp-00-contracts.md`, `firmware/HARDWARE_CHECKLIST.md` | Normative wire changes and bench checklist |
| `firmware/test/test_belt_rules_roles/`, `test_link_fanout/`, `test_belt_rules_bonds/`, `test_belt_rules_diag/` (new) | Native suites |

---

### Task 1: Link roles, slots and the shared-`info` guard (pure)

**Files:**
- Create: `firmware/lib/belt_rules/link_roles.h`, `firmware/lib/belt_rules/link_roles.cpp`
- Create: `firmware/lib/belt_rules/info_reads.h`, `firmware/lib/belt_rules/info_reads.cpp`
- Test: `firmware/test/test_belt_rules_roles/test_main.cpp`

**Interfaces:**
- Consumes: `LinkParams` (`bundler.h`) and `kMinNotifyMtu` (`ble_rules.h`), both MVP.
- Produces (used by Tasks 2-8):
  - `constexpr size_t kMaxLinks = 2;` `constexpr uint16_t kNoConnHandle = 0xFFFF;`
  - `enum class LinkRole : uint8_t { Watch = 0, Phone = 1 };`
  - `struct ConnParamsRequest { uint16_t min_units; uint16_t max_units; uint16_t latency; uint16_t timeout_units; };`
  - `LinkRole role_from_argument(uint8_t argument);` `const char* role_name(LinkRole role);` (`"watch"`/`"phone"`)
  - `ConnParamsRequest conn_params_for_role(LinkRole role);`
  - `int slot_for_handle(const uint16_t* handles, size_t count, uint16_t handle);` (index or −1; `kNoConnHandle` finds a free slot)
  - `bool conn_params_retry_wanted(LinkRole role, const LinkParams& params, bool already_retried);` (true only for a phone-role link whose parameters left 48-80 units / latency 0, and only once)
  - `struct AdvertisingNeed { size_t connections; size_t capacity; size_t trusted_links; size_t bonds; bool window_open; bool second_link_on_demand; };` `bool advertising_wanted(const AdvertisingNeed& need);`
  - `constexpr uint32_t kInfoReadShareMs = 2000;` `struct InfoReadMarks { bool marked[kMaxLinks]; uint32_t at_ms[kMaxLinks]; uint16_t copy_mtu; };` `struct InfoReader { size_t slot; uint16_t mtu; };`
  - `bool info_refresh_allowed(const InfoReadMarks& marks, const InfoReader& reader, uint32_t now_ms);`
  - `InfoReadMarks info_read_marked(const InfoReadMarks& marks, size_t reader_slot, uint32_t now_ms);` `InfoReadMarks info_copy_rebuilt(const InfoReadMarks& marks, uint16_t mtu);`

- [ ] **Step 1: Create the worktree**

```bash
git -C C:/personal/blindside worktree add C:/personal/blindside-p2-firmware -b feat/p2-firmware-dual main
cd C:/personal/blindside-p2-firmware/firmware
python tools/run_native_tests.py
```

Expected: `14 suites passed, 0 failed` (the first run downloads Unity into `.pio/native-runner`).

- [ ] **Step 2: Write the failing test**

`firmware/test/test_belt_rules_roles/test_main.cpp`:

```cpp
#include <unity.h>

#include "../test_entry.h"
#include "info_reads.h"
#include "link_roles.h"

namespace {

constexpr uint16_t kStreamMtu = 255;
constexpr uint16_t kDefaultMtu = 23;

uint32_t units_to_ms(uint16_t units) {
    return units * 125u / 100u;
}

InfoReader reader(size_t slot, uint16_t mtu) {
    return InfoReader{slot, mtu};
}

InfoReadMarks read_by(size_t slot, uint16_t mtu, uint32_t at_ms) {
    return info_copy_rebuilt(info_read_marked(InfoReadMarks{}, slot, at_ms), mtu);
}

AdvertisingNeed one_link_up(size_t trusted_links, size_t bonds, bool window_open) {
    return AdvertisingNeed{1, kMaxLinks, trusted_links, bonds, window_open, true};
}

}  // namespace

void setUp() {}
void tearDown() {}

void test_role_argument_zero_is_the_watch_and_one_is_the_phone() {
    TEST_ASSERT_TRUE(role_from_argument(0) == LinkRole::Watch);
    TEST_ASSERT_TRUE(role_from_argument(1) == LinkRole::Phone);
}

void test_role_names_match_the_info_contract() {
    TEST_ASSERT_EQUAL_STRING("watch", role_name(LinkRole::Watch));
    TEST_ASSERT_EQUAL_STRING("phone", role_name(LinkRole::Phone));
}

void test_the_watch_asks_for_30_to_50_ms() {
    ConnParamsRequest watch = conn_params_for_role(LinkRole::Watch);
    TEST_ASSERT_EQUAL_UINT32(30, units_to_ms(watch.min_units));
    TEST_ASSERT_EQUAL_UINT32(50, units_to_ms(watch.max_units));
    TEST_ASSERT_EQUAL_UINT16(0, watch.latency);
    TEST_ASSERT_EQUAL_UINT16(400, watch.timeout_units);
}

void test_the_phone_asks_for_60_to_100_ms() {
    ConnParamsRequest phone = conn_params_for_role(LinkRole::Phone);
    TEST_ASSERT_EQUAL_UINT32(60, units_to_ms(phone.min_units));
    TEST_ASSERT_EQUAL_UINT32(100, units_to_ms(phone.max_units));
    TEST_ASSERT_EQUAL_UINT16(0, phone.latency);
    TEST_ASSERT_EQUAL_UINT16(400, phone.timeout_units);
}

void test_only_the_phone_is_asked_again_and_only_once() {
    LinkParams android_low_power{96, 2, 500};
    LinkParams phone_with_latency{60, 2, 400};
    LinkParams phone_as_asked{60, 0, 400};
    TEST_ASSERT_TRUE(conn_params_retry_wanted(LinkRole::Phone, android_low_power, false));
    TEST_ASSERT_TRUE(conn_params_retry_wanted(LinkRole::Phone, phone_with_latency, false));
    TEST_ASSERT_FALSE(conn_params_retry_wanted(LinkRole::Phone, android_low_power, true));
    TEST_ASSERT_FALSE(conn_params_retry_wanted(LinkRole::Phone, phone_as_asked, false));
    TEST_ASSERT_FALSE(conn_params_retry_wanted(LinkRole::Watch, android_low_power, false));
}

void test_slots_are_found_by_connection_handle() {
    const uint16_t handles[kMaxLinks] = {kNoConnHandle, 7};
    TEST_ASSERT_EQUAL_INT(1, slot_for_handle(handles, kMaxLinks, 7));
    TEST_ASSERT_EQUAL_INT(0, slot_for_handle(handles, kMaxLinks, kNoConnHandle));
    TEST_ASSERT_EQUAL_INT(-1, slot_for_handle(handles, kMaxLinks, 9));
}

void test_a_full_belt_has_no_free_slot() {
    const uint16_t handles[kMaxLinks] = {3, 7};
    TEST_ASSERT_EQUAL_INT(-1, slot_for_handle(handles, kMaxLinks, kNoConnHandle));
}

void test_the_belt_advertises_while_a_slot_is_free() {
    TEST_ASSERT_TRUE(advertising_wanted(AdvertisingNeed{0, 2, 0, 1, false, false}));
    TEST_ASSERT_TRUE(advertising_wanted(AdvertisingNeed{1, 2, 1, 1, false, false}));
    TEST_ASSERT_FALSE(advertising_wanted(AdvertisingNeed{2, 2, 2, 2, true, false}));
    TEST_ASSERT_FALSE(advertising_wanted(AdvertisingNeed{1, 1, 0, 1, true, true}));
}

void test_with_one_link_up_it_advertises_only_for_an_absent_bond_or_an_open_window() {
    TEST_ASSERT_TRUE(advertising_wanted(AdvertisingNeed{0, 2, 0, 1, false, true}));
    TEST_ASSERT_FALSE(advertising_wanted(one_link_up(1, 1, false)));
    TEST_ASSERT_TRUE(advertising_wanted(one_link_up(1, 2, false)));
    TEST_ASSERT_TRUE(advertising_wanted(one_link_up(1, 1, true)));
    TEST_ASSERT_TRUE(advertising_wanted(one_link_up(0, 1, false)));
}

void test_info_is_regenerated_when_nobody_else_is_reading() {
    TEST_ASSERT_TRUE(info_refresh_allowed(InfoReadMarks{}, reader(0, kStreamMtu), 5000));
    TEST_ASSERT_TRUE(info_refresh_allowed(read_by(0, kStreamMtu, 1000), reader(0, kStreamMtu), 1001));
}

void test_info_is_shared_for_two_seconds_after_another_link_reads_it() {
    InfoReadMarks marks = read_by(1, kStreamMtu, 1000);
    TEST_ASSERT_FALSE(info_refresh_allowed(marks, reader(0, kStreamMtu), 1000));
    TEST_ASSERT_FALSE(info_refresh_allowed(marks, reader(0, kStreamMtu), 2999));
    TEST_ASSERT_TRUE(info_refresh_allowed(marks, reader(0, kStreamMtu), 3000));
}

void test_info_share_window_survives_a_millis_wrap() {
    InfoReadMarks marks = read_by(1, kStreamMtu, 0xFFFFFF00u);
    TEST_ASSERT_FALSE(info_refresh_allowed(marks, reader(0, kStreamMtu), 0x00000100u));
}

void test_a_copy_built_below_mtu_247_is_rebuilt_for_a_reader_that_can_stream() {
    InfoReadMarks phone_at_23 = read_by(1, kDefaultMtu, 1000);
    TEST_ASSERT_TRUE(info_refresh_allowed(phone_at_23, reader(0, kStreamMtu), 1500));
    TEST_ASSERT_FALSE(info_refresh_allowed(phone_at_23, reader(0, kDefaultMtu), 1500));
    InfoReadMarks watch_at_255 = read_by(0, kStreamMtu, 1000);
    TEST_ASSERT_FALSE(info_refresh_allowed(watch_at_255, reader(1, kDefaultMtu), 1500));
}

void test_marking_a_read_records_only_that_slot() {
    InfoReadMarks marks = info_read_marked(read_by(0, kStreamMtu, 10), 1, 20);
    TEST_ASSERT_TRUE(marks.marked[0]);
    TEST_ASSERT_EQUAL_UINT32(10, marks.at_ms[0]);
    TEST_ASSERT_TRUE(marks.marked[1]);
    TEST_ASSERT_EQUAL_UINT32(20, marks.at_ms[1]);
    TEST_ASSERT_EQUAL_UINT16(kStreamMtu, marks.copy_mtu);
    InfoReadMarks unchanged = info_read_marked(InfoReadMarks{}, kMaxLinks, 30);
    TEST_ASSERT_FALSE(unchanged.marked[0]);
    TEST_ASSERT_FALSE(unchanged.marked[1]);
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_role_argument_zero_is_the_watch_and_one_is_the_phone);
    RUN_TEST(test_role_names_match_the_info_contract);
    RUN_TEST(test_the_watch_asks_for_30_to_50_ms);
    RUN_TEST(test_the_phone_asks_for_60_to_100_ms);
    RUN_TEST(test_only_the_phone_is_asked_again_and_only_once);
    RUN_TEST(test_slots_are_found_by_connection_handle);
    RUN_TEST(test_a_full_belt_has_no_free_slot);
    RUN_TEST(test_the_belt_advertises_while_a_slot_is_free);
    RUN_TEST(test_with_one_link_up_it_advertises_only_for_an_absent_bond_or_an_open_window);
    RUN_TEST(test_info_is_regenerated_when_nobody_else_is_reading);
    RUN_TEST(test_info_is_shared_for_two_seconds_after_another_link_reads_it);
    RUN_TEST(test_info_share_window_survives_a_millis_wrap);
    RUN_TEST(test_a_copy_built_below_mtu_247_is_rebuilt_for_a_reader_that_can_stream);
    RUN_TEST(test_marking_a_read_records_only_that_slot);
    return UNITY_END();
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `python tools/run_native_tests.py test_belt_rules_roles`
Expected: a compiler error `'info_reads.h' file not found`, then `build failed` and `0 suites passed, 1 failed`.

- [ ] **Step 4: Write the implementation**

`firmware/lib/belt_rules/link_roles.h`:

```cpp
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "bundler.h"

constexpr size_t kMaxLinks = 2;
constexpr uint16_t kNoConnHandle = 0xFFFF;
// BLE units: connection interval 1.25 ms, supervision timeout 10 ms.
constexpr uint16_t kLinkLatency = 0;
constexpr uint16_t kSupervisionTimeoutUnits = 400;
constexpr uint16_t kWatchIntervalMinUnits = 24;
constexpr uint16_t kWatchIntervalMaxUnits = 40;
constexpr uint16_t kPhoneIntervalMinUnits = 48;
constexpr uint16_t kPhoneIntervalMaxUnits = 80;

enum class LinkRole : uint8_t { Watch = 0, Phone = 1 };

struct ConnParamsRequest {
    uint16_t min_units;
    uint16_t max_units;
    uint16_t latency;
    uint16_t timeout_units;
};

struct AdvertisingNeed {
    size_t connections;
    size_t capacity;
    size_t trusted_links;
    size_t bonds;
    bool window_open;
    bool second_link_on_demand;
};

LinkRole role_from_argument(uint8_t argument);
const char* role_name(LinkRole role);
ConnParamsRequest conn_params_for_role(LinkRole role);
bool conn_params_retry_wanted(LinkRole role, const LinkParams& params, bool already_retried);
int slot_for_handle(const uint16_t* handles, size_t count, uint16_t handle);
bool advertising_wanted(const AdvertisingNeed& need);
```

`firmware/lib/belt_rules/link_roles.cpp`:

```cpp
#include "link_roles.h"

namespace {

constexpr ConnParamsRequest kWatchParams{kWatchIntervalMinUnits, kWatchIntervalMaxUnits, kLinkLatency,
                                         kSupervisionTimeoutUnits};
constexpr ConnParamsRequest kPhoneParams{kPhoneIntervalMinUnits, kPhoneIntervalMaxUnits, kLinkLatency,
                                         kSupervisionTimeoutUnits};

bool params_match_request(const LinkParams& params, const ConnParamsRequest& request) {
    bool interval_ok = params.interval_units >= request.min_units && params.interval_units <= request.max_units;
    return interval_ok && params.latency == request.latency;
}

bool another_device_may_connect(const AdvertisingNeed& need) {
    return need.window_open || need.bonds > need.trusted_links;
}

}  // namespace

LinkRole role_from_argument(uint8_t argument) {
    return argument == static_cast<uint8_t>(LinkRole::Phone) ? LinkRole::Phone : LinkRole::Watch;
}

const char* role_name(LinkRole role) {
    return role == LinkRole::Phone ? "phone" : "watch";
}

ConnParamsRequest conn_params_for_role(LinkRole role) {
    return role == LinkRole::Phone ? kPhoneParams : kWatchParams;
}

// Only the phone is asked again, and once: the watch app tunes its own priority, and a central that insists wins.
bool conn_params_retry_wanted(LinkRole role, const LinkParams& params, bool already_retried) {
    if (role != LinkRole::Phone || already_retried) {
        return false;
    }
    return !params_match_request(params, conn_params_for_role(role));
}

int slot_for_handle(const uint16_t* handles, size_t count, uint16_t handle) {
    for (size_t i = 0; i < count; ++i) {
        if (handles[i] == handle) {
            return static_cast<int>(i);
        }
    }
    return -1;
}

bool advertising_wanted(const AdvertisingNeed& need) {
    if (need.connections >= need.capacity) {
        return false;
    }
    if (need.connections == 0 || !need.second_link_on_demand) {
        return true;
    }
    return another_device_may_connect(need);
}
```

`firmware/lib/belt_rules/info_reads.h`:

```cpp
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
```

`firmware/lib/belt_rules/info_reads.cpp`:

```cpp
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
```

- [ ] **Step 5: Run the tests**

Run: `python tools/run_native_tests.py test_belt_rules_roles` → `14 Tests 0 Failures 0 Ignored`, `1 suites passed, 0 failed`.
Run: `python tools/run_native_tests.py` → `15 suites passed, 0 failed`.
Run: `python -m platformio run -e esp32dev` → `[SUCCESS]` (nothing in `src/` uses the new files yet).

- [ ] **Step 6: Commit**

```bash
git add lib/belt_rules/link_roles.h lib/belt_rules/link_roles.cpp lib/belt_rules/info_reads.h lib/belt_rules/info_reads.cpp test/test_belt_rules_roles/test_main.cpp
git commit -m "feat: reglas de roles, ranuras de enlace, anuncio y lectura compartida de info para la doble conexión"
```

---

### Task 2: `ble_link` with link slots (expand) and advertising split

**Files:**
- Create: `firmware/src/ble_advertising.h`, `firmware/src/ble_advertising.cpp`
- Create: `firmware/src/nimble_internals.h`, `firmware/src/nimble_internals.cpp`
- Modify (full rewrite): `firmware/src/ble_link.h`, `firmware/src/ble_link.cpp`
- Modify: `firmware/src/main.cpp` (includes, `current_info`, `write_info_json`, advertising calls and plan)
- Modify: `firmware/src/diagnostics.cpp` (`print_link`)
- Modify: `firmware/include/blindside_config.h` (connection-parameter constants move to `link_roles.h`; `kSecondLinkAdvertisingOnDemand`)

**Interfaces:**
- Consumes (Task 1): `kMaxLinks`, `kNoConnHandle`, `LinkRole`, `conn_params_for_role`, `conn_params_retry_wanted`, `slot_for_handle`, `AdvertisingNeed`, `advertising_wanted`, `InfoReadMarks`, `InfoReader`, `info_refresh_allowed`, `info_read_marked`, `info_copy_rebuilt`.
- Produces (used by Tasks 3-8):
  - `struct LinkSnapshot { bool connected; bool subscribed; bool trusted; bool peer_bonded; LinkRole role; uint16_t mtu; uint32_t connected_at_ms; uint32_t link_id; LinkParams params; };` (`link_id` is 0 while the slot is free and a new value ≥ 1 for each connection)
  - `using InfoWriter = size_t (*)(uint8_t reader_slot, char* out, size_t out_size);`
  - `size_t ble_link_capacity();` `size_t ble_link_connection_count();` `LinkSnapshot ble_link_snapshot(uint8_t slot);` `int ble_link_last_disconnect_reason();` `bool ble_link_take_disconnect_event();`
  - `bool ble_link_take_auth_event(uint8_t slot);` `PeerSecurity ble_link_peer_security(uint8_t slot);` `void ble_link_set_trusted(uint8_t slot, bool trusted);` `void ble_link_set_role(uint8_t slot, LinkRole role);` `ControlCommand ble_link_take_control(uint8_t slot);` (the oldest queued write of that slot, `ControlKind::None` when there is none) `uint16_t ble_link_backlog(uint8_t slot);` (packets NimBLE holds for that connection) `bool ble_link_notify(uint8_t slot, uint32_t link_id, const uint8_t* bytes, size_t length);` (false unless the slot still holds connection `link_id`, trusted and subscribed) `void ble_link_disconnect(uint8_t slot);` `void ble_link_disconnect_all();`
  - `struct AdvertisingPlan { bool whitelist_only; bool window_open; size_t bonds; };` `void ble_advertising_configure(const char* device_name);` `void ble_advertising_start(bool whitelist_only, uint32_t now_ms);` `void ble_advertising_poll(const AdvertisingPlan& plan, uint32_t now_ms);`
  - `void nimble_set_cccd_permissions(uint8_t flags);` `uint16_t nimble_link_backlog(uint16_t conn_handle);` `uint16_t nimble_free_acl_buffers();` `uint32_t nimble_host_stack_free();` (the last two for Task 7)
  - Temporary single-link wrappers on slot 0 (deleted in Task 8): `ble_link_snapshot()`, `ble_link_take_auth_event()`, `ble_link_peer_security()`, `ble_link_set_trusted(bool)`, `ble_link_take_control()`, `ble_link_notify(const uint8_t*, size_t)`, `ble_link_disconnect()`.

`CONFIG_BT_NIMBLE_MAX_CONNECTIONS` stays 1 until Task 8, so in this task only slot 0 is ever used and the behaviour is the MVP's (with one connection `advertising_wanted` reduces to "no link, advertise"). There is no native test for `src/`; the deliverable is a green build with the unchanged suites. The per-connection backlog and the free controller buffers live in NimBLE's private host structures (`ble_hs_conn`, `ble_hs_hci_avail_pkts`) and need its host lock; `nimble_internals.cpp` is the only file that includes the private `ble_hs_priv.h`, and the CCCD permission hook the MVP declared by hand in `ble_link.cpp` moves there too.

- [ ] **Step 1: Create the advertising module (moved out of `ble_link.cpp`)**

`firmware/src/ble_advertising.h`:

```cpp
#pragma once

#include <stddef.h>
#include <stdint.h>

struct AdvertisingPlan {
    bool whitelist_only;
    bool window_open;
    size_t bonds;
};

void ble_advertising_configure(const char* device_name);
void ble_advertising_start(bool whitelist_only, uint32_t now_ms);
void ble_advertising_poll(const AdvertisingPlan& plan, uint32_t now_ms);
```

`firmware/src/ble_advertising.cpp`:

```cpp
#include "ble_advertising.h"

#include <NimBLEDevice.h>

#include "ble_link.h"
#include "ble_rules.h"
#include "blindside_config.h"
#include "link_roles.h"

namespace {

struct AdvertisingState {
    bool whitelist_only;
    AdvertisingSpeed speed;
    uint32_t since_ms;
};

AdvertisingState g_advertising{};

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

void stop_advertising_if_running() {
    NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
    if (advertising->isAdvertising()) {
        advertising->stop();
    }
}

size_t trusted_link_count() {
    size_t count = 0;
    for (uint8_t slot = 0; slot < kMaxLinks; ++slot) {
        count += ble_link_snapshot(slot).trusted ? 1 : 0;
    }
    return count;
}

AdvertisingNeed advertising_need(const AdvertisingPlan& plan) {
    return AdvertisingNeed{ble_link_connection_count(), ble_link_capacity(), trusted_link_count(), plan.bonds,
                           plan.window_open, config::kSecondLinkAdvertisingOnDemand};
}

}  // namespace

void ble_advertising_configure(const char* device_name) {
    NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
    advertising->enableScanResponse(true);
    advertising->setName(device_name);
    advertising->addServiceUUID(config::kServiceUuid);
}

void ble_advertising_start(bool whitelist_only, uint32_t now_ms) {
    g_advertising.since_ms = now_ms;
    restart_advertising(whitelist_only, AdvertisingSpeed::Fast);
}

void ble_advertising_poll(const AdvertisingPlan& plan, uint32_t now_ms) {
    if (ble_link_take_disconnect_event()) {
        g_advertising.since_ms = now_ms;
    }
    if (!advertising_wanted(advertising_need(plan))) {
        stop_advertising_if_running();
        return;
    }
    AdvertisingSpeed wanted = advertising_speed(now_ms - g_advertising.since_ms);
    if (advertising_needs_restart(plan.whitelist_only, wanted)) {
        restart_advertising(plan.whitelist_only, wanted);
    }
}
```

- [ ] **Step 2: Create the NimBLE-internals module**

`firmware/src/nimble_internals.h`:

```cpp
#pragma once

#include <stdint.h>

void nimble_set_cccd_permissions(uint8_t flags);
uint16_t nimble_link_backlog(uint16_t conn_handle);
uint16_t nimble_free_acl_buffers();
uint32_t nimble_host_stack_free();
```

`firmware/src/nimble_internals.cpp`:

```cpp
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
```

- [ ] **Step 3: Replace `firmware/src/ble_link.h`**

```cpp
#pragma once

#include <NimBLEDevice.h>
#include <stddef.h>
#include <stdint.h>

#include "bundler.h"
#include "control_command.h"
#include "link_roles.h"

struct LinkSnapshot {
    bool connected;
    bool subscribed;
    bool trusted;
    bool peer_bonded;
    LinkRole role;
    uint16_t mtu;
    uint32_t connected_at_ms;
    uint32_t link_id;
    LinkParams params;
};

using InfoWriter = size_t (*)(uint8_t reader_slot, char* out, size_t out_size);

struct PeerSecurity {
    bool valid;
    bool secure;
    NimBLEAddress identity;
};

void ble_link_begin(const char* device_name, InfoWriter info_writer);
void ble_link_set_passkey(uint32_t passkey);
size_t ble_link_capacity();
size_t ble_link_connection_count();
LinkSnapshot ble_link_snapshot(uint8_t slot);
int ble_link_last_disconnect_reason();
bool ble_link_take_disconnect_event();
uint32_t ble_link_report_generation();
int8_t ble_link_tx_power();
bool ble_link_take_auth_event(uint8_t slot);
PeerSecurity ble_link_peer_security(uint8_t slot);
void ble_link_set_trusted(uint8_t slot, bool trusted);
void ble_link_set_role(uint8_t slot, LinkRole role);
ControlCommand ble_link_take_control(uint8_t slot);
uint16_t ble_link_backlog(uint8_t slot);
bool ble_link_notify(uint8_t slot, uint32_t link_id, const uint8_t* bytes, size_t length);
void ble_link_disconnect(uint8_t slot);
void ble_link_disconnect_all();

// Single-link calls on slot 0, kept only until every caller moves to slots.
LinkSnapshot ble_link_snapshot();
bool ble_link_take_auth_event();
PeerSecurity ble_link_peer_security();
void ble_link_set_trusted(bool trusted);
ControlCommand ble_link_take_control();
bool ble_link_notify(const uint8_t* bytes, size_t length);
void ble_link_disconnect();
```

- [ ] **Step 4: Replace `firmware/src/ble_link.cpp`**

```cpp
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
```

- [ ] **Step 5: Move the connection-parameter constants out of `blindside_config.h` and add the advertising switch**

In `firmware/include/blindside_config.h` replace:

```cpp
// BLE units: connection interval 1.25 ms, supervision timeout 10 ms, advertising interval 0.625 ms.
constexpr uint16_t kConnIntervalMinUnits = 24;
constexpr uint16_t kConnIntervalMaxUnits = 40;
constexpr uint16_t kConnLatency = 0;
constexpr uint16_t kSupervisionTimeoutUnits = 400;
constexpr uint16_t kDataLengthOctets = 251;
```

with:

```cpp
// BLE units: advertising interval 0.625 ms (connection parameters live in link_roles.h).
constexpr uint16_t kDataLengthOctets = 251;
```

and right after `constexpr bool kConnectWhitelistOnly = false;` add:

```cpp
// With one link up, advertise only while a bonded device is away or the pairing window is open; false advertises
// whenever a slot is free (spec §2 as written). 0.1.0 never advertised with the watch connected.
constexpr bool kSecondLinkAdvertisingOnDemand = true;
```

- [ ] **Step 6: Adapt `main.cpp` and `diagnostics.cpp`**

In `firmware/src/main.cpp`:
- add `#include "ble_advertising.h"` right above `#include "ble_link.h"`;
- replace

```cpp
BeltInfo current_info(uint32_t now_ms) {
    LinkSnapshot link = ble_link_snapshot();
```

with

```cpp
BeltInfo current_info(uint32_t now_ms, uint8_t reader_slot) {
    LinkSnapshot link = ble_link_snapshot(reader_slot);
```

- replace

```cpp
size_t write_info_json(char* out, size_t out_size) {
    return format_info_json(current_info(millis()), out, out_size);
```

with

```cpp
size_t write_info_json(uint8_t reader_slot, char* out, size_t out_size) {
    return format_info_json(current_info(millis(), reader_slot), out, out_size);
```

- replace `ble_link_start_advertising(pairing_whitelist_only(g_pairing), millis());` with `ble_advertising_start(pairing_whitelist_only(g_pairing), millis());` and `ble_link_poll_advertising(pairing_whitelist_only(g_pairing), now_ms);` with `ble_advertising_poll(advertising_plan(), now_ms);`;
- add, right above `void print_diagnostics_if_due(uint32_t now_ms) {`:

```cpp
// The MVP pairing keeps a single bond; Task 4 counts both.
AdvertisingPlan advertising_plan() {
    size_t bonds = g_pairing.trusted.isNull() ? 0 : 1;
    return AdvertisingPlan{pairing_whitelist_only(g_pairing), g_pairing.window.open, bonds};
}
```

In `firmware/src/diagnostics.cpp`, in `print_link()`, replace `link.last_disconnect_reason` with `ble_link_last_disconnect_reason()`.

- [ ] **Step 7: Build and run the suites**

Run: `python -m platformio run -e esp32dev` → `[SUCCESS]`, no warning from `src/` (RAM ≈ 16.1 %).
Run: `python tools/run_native_tests.py` → `15 suites passed, 0 failed`.
Run: `grep -rn "kConnIntervalMinUnits\|kConnLatency\|ble_link_start_advertising\|ble_link_poll_advertising" src include lib` → no output.
Run: `grep -rln "ble_hs_priv\|ble_gatts_set_clt_cfg_perm_flags" src` → only `src/nimble_internals.cpp`.

- [ ] **Step 8: Commit**

```bash
git add src/ble_advertising.h src/ble_advertising.cpp src/nimble_internals.h src/nimble_internals.cpp src/ble_link.h src/ble_link.cpp src/main.cpp src/diagnostics.cpp include/blindside_config.h
git commit -m "refactor: el enlace BLE del cinturón guarda su estado por ranura, encola los comandos de control y separa el anuncio"
```

---

### Task 3: Fan-out — watch first, phone drops, per-link counters

**Files:**
- Create: `firmware/lib/belt_rules/link_fanout.h`, `firmware/lib/belt_rules/link_fanout.cpp`
- Test: `firmware/test/test_link_fanout/test_main.cpp`
- Modify: `firmware/lib/bundler/cut_schedule.h`, `firmware/lib/bundler/cut_schedule.cpp`, `firmware/test/test_cut_schedule/test_main.cpp` (`Outbox` removed, `cut_action(bool, bool)`)
- Modify (full rewrite): `firmware/src/stream_sender.h`, `firmware/src/stream_sender.cpp`
- Modify: `firmware/src/diagnostics.cpp` (`print_sender`)

**Interfaces:**
- Consumes: `kMaxLinks`, `LinkRole` (Task 1); `ble_link_snapshot(uint8_t)`, `ble_link_backlog(uint8_t)`, `ble_link_notify(uint8_t, uint32_t, …)`, `LinkSnapshot::link_id`/`role` (Task 2); `stream_gate_open`, `payload_limit_for_mtu`, `bundle_cut` (MVP).
- Produces:
  - `struct FanoutLink { bool gated; LinkRole role; uint32_t link_id; uint16_t mtu; };`
  - `struct NotifyOrder { uint8_t slots[kMaxLinks]; size_t count; };`
  - `struct LinkDelivery { uint32_t link_id; size_t next; uint32_t sent; uint32_t dropped; };`
  - `struct Fanout { size_t count; uint32_t failures; LinkDelivery links[kMaxLinks]; };`
  - `struct Delivery { bool found; uint8_t slot; size_t packet; bool protected_link; uint32_t link_id; };` `struct LinkTally { uint32_t sent; uint32_t dropped; };`
  - `constexpr uint16_t kUnprotectedBacklogCap = 3;` `bool notify_allowed(const Delivery& delivery, uint16_t link_backlog);` (always true for the protected link; otherwise true while the link holds fewer than 3 packets)
  - `NotifyOrder notify_order(const FanoutLink* links);` `uint16_t gated_min_mtu(const FanoutLink* links);`
  - `Fanout fanout_synced(const Fanout&, const FanoutLink*);` `Fanout fanout_refilled(const Fanout&, size_t packet_count, const FanoutLink*);` `Fanout fanout_cleared(const Fanout&);` `bool fanout_pending(const Fanout&, const NotifyOrder&);` `Delivery next_delivery(const Fanout&, const NotifyOrder&);` `Fanout fanout_after_notify(const Fanout&, const Delivery&, bool sent);` `LinkTally tally_for_link(const LinkDelivery&, uint32_t link_id);`
  - `CutAction cut_action(bool gate_open, bool delivery_pending);`
  - `struct SenderStats { uint32_t notify_failures; uint32_t dropped_total; uint32_t skipped_cuts; LinkDelivery links[kMaxLinks]; };` (Tasks 6 and 7 read `links[slot]` through `tally_for_link`)

- [ ] **Step 1: Write the failing fan-out test**

`firmware/test/test_link_fanout/test_main.cpp`:

```cpp
#include <unity.h>

#include "../test_entry.h"
#include "link_fanout.h"

namespace {

constexpr uint8_t kPhoneSlot = 0;
constexpr uint8_t kWatchSlot = 1;

struct LinkPair {
    FanoutLink links[kMaxLinks];
};

FanoutLink streaming(LinkRole role, uint32_t link_id) {
    return FanoutLink{true, role, link_id, 255};
}

FanoutLink not_streaming(uint32_t link_id) {
    return FanoutLink{false, LinkRole::Watch, link_id, 23};
}

LinkPair watch_and_phone() {
    return LinkPair{{streaming(LinkRole::Phone, 1), streaming(LinkRole::Watch, 2)}};
}

Fanout loaded(const LinkPair& pair, size_t packets) {
    Fanout synced = fanout_synced(Fanout{}, pair.links);
    return fanout_refilled(synced, packets, pair.links);
}

Fanout notified(const Fanout& fanout, const NotifyOrder& order, bool sent) {
    return fanout_after_notify(fanout, next_delivery(fanout, order), sent);
}

void assert_delivery(const Delivery& delivery, uint8_t slot, size_t packet) {
    TEST_ASSERT_TRUE(delivery.found);
    TEST_ASSERT_EQUAL_UINT8(slot, delivery.slot);
    TEST_ASSERT_EQUAL_UINT(packet, delivery.packet);
}

}  // namespace

void setUp() {}
void tearDown() {}

void test_the_watch_is_notified_before_the_phone() {
    LinkPair pair = watch_and_phone();
    NotifyOrder order = notify_order(pair.links);
    TEST_ASSERT_EQUAL_UINT(2, order.count);
    TEST_ASSERT_EQUAL_UINT8(kWatchSlot, order.slots[0]);
    TEST_ASSERT_EQUAL_UINT8(kPhoneSlot, order.slots[1]);
}

void test_two_watch_links_go_oldest_first() {
    LinkPair pair{{streaming(LinkRole::Watch, 5), streaming(LinkRole::Watch, 3)}};
    NotifyOrder order = notify_order(pair.links);
    TEST_ASSERT_EQUAL_UINT8(1, order.slots[0]);
    TEST_ASSERT_EQUAL_UINT8(0, order.slots[1]);
}

void test_links_without_a_stream_are_left_out() {
    LinkPair pair{{not_streaming(4), streaming(LinkRole::Phone, 2)}};
    NotifyOrder order = notify_order(pair.links);
    TEST_ASSERT_EQUAL_UINT(1, order.count);
    TEST_ASSERT_EQUAL_UINT8(1, order.slots[0]);
    TEST_ASSERT_EQUAL_UINT(0, notify_order(LinkPair{{not_streaming(1), not_streaming(2)}}.links).count);
}

void test_each_packet_goes_to_the_watch_then_the_phone() {
    LinkPair pair = watch_and_phone();
    NotifyOrder order = notify_order(pair.links);
    Fanout fanout = loaded(pair, 2);
    assert_delivery(next_delivery(fanout, order), kWatchSlot, 0);
    fanout = notified(fanout, order, true);
    assert_delivery(next_delivery(fanout, order), kPhoneSlot, 0);
    fanout = notified(fanout, order, true);
    assert_delivery(next_delivery(fanout, order), kWatchSlot, 1);
    fanout = notified(fanout, order, true);
    assert_delivery(next_delivery(fanout, order), kPhoneSlot, 1);
    fanout = notified(fanout, order, true);
    TEST_ASSERT_FALSE(next_delivery(fanout, order).found);
    TEST_ASSERT_FALSE(fanout_pending(fanout, order));
    TEST_ASSERT_EQUAL_UINT32(2, fanout.links[kWatchSlot].sent);
    TEST_ASSERT_EQUAL_UINT32(2, fanout.links[kPhoneSlot].sent);
}

void test_a_full_queue_retries_the_watch_and_never_drops_it() {
    LinkPair pair = watch_and_phone();
    NotifyOrder order = notify_order(pair.links);
    Fanout fanout = notified(loaded(pair, 2), order, false);
    TEST_ASSERT_TRUE(next_delivery(loaded(pair, 2), order).protected_link);
    assert_delivery(next_delivery(fanout, order), kWatchSlot, 0);
    TEST_ASSERT_EQUAL_UINT32(1, fanout.failures);
    TEST_ASSERT_EQUAL_UINT32(0, fanout.links[kWatchSlot].dropped);
    TEST_ASSERT_TRUE(fanout_pending(fanout, order));
}

void test_a_full_queue_drops_the_phone_packet_and_moves_on() {
    LinkPair pair = watch_and_phone();
    NotifyOrder order = notify_order(pair.links);
    Fanout fanout = notified(loaded(pair, 2), order, true);
    TEST_ASSERT_FALSE(next_delivery(fanout, order).protected_link);
    fanout = notified(fanout, order, false);
    TEST_ASSERT_EQUAL_UINT32(1, fanout.links[kPhoneSlot].dropped);
    TEST_ASSERT_EQUAL_UINT32(0, fanout.failures);
    assert_delivery(next_delivery(fanout, order), kWatchSlot, 1);
}

void test_a_phone_at_its_backlog_cap_is_dropped_without_a_notify() {
    LinkPair pair = watch_and_phone();
    NotifyOrder order = notify_order(pair.links);
    Fanout fanout = notified(loaded(pair, 2), order, true);
    Delivery phone = next_delivery(fanout, order);
    TEST_ASSERT_TRUE(notify_allowed(phone, kUnprotectedBacklogCap - 1));
    TEST_ASSERT_FALSE(notify_allowed(phone, kUnprotectedBacklogCap));
    fanout = fanout_after_notify(fanout, phone, notify_allowed(phone, kUnprotectedBacklogCap));
    TEST_ASSERT_EQUAL_UINT32(1, fanout.links[kPhoneSlot].dropped);
    TEST_ASSERT_EQUAL_UINT32(0, fanout.failures);
    assert_delivery(next_delivery(fanout, order), kWatchSlot, 1);
}

void test_the_first_link_in_notify_order_is_never_capped() {
    LinkPair pair = watch_and_phone();
    Delivery watch = next_delivery(loaded(pair, 1), notify_order(pair.links));
    TEST_ASSERT_TRUE(watch.protected_link);
    TEST_ASSERT_TRUE(notify_allowed(watch, 1000));
}

void test_deliveries_name_the_connection_they_were_planned_for() {
    LinkPair pair = watch_and_phone();
    NotifyOrder order = notify_order(pair.links);
    Fanout fanout = loaded(pair, 1);
    TEST_ASSERT_EQUAL_UINT32(2, next_delivery(fanout, order).link_id);
    TEST_ASSERT_EQUAL_UINT32(1, next_delivery(notified(fanout, order, true), order).link_id);
}

void test_the_phone_alone_is_retried_like_the_watch() {
    LinkPair pair{{streaming(LinkRole::Phone, 1), not_streaming(0)}};
    NotifyOrder order = notify_order(pair.links);
    Fanout fanout = notified(loaded(pair, 1), order, false);
    TEST_ASSERT_EQUAL_UINT32(0, fanout.links[kPhoneSlot].dropped);
    TEST_ASSERT_EQUAL_UINT32(1, fanout.failures);
    assert_delivery(next_delivery(fanout, order), kPhoneSlot, 0);
}

void test_a_link_that_starts_streaming_mid_outbox_joins_at_the_next_cut() {
    LinkPair before{{not_streaming(1), streaming(LinkRole::Watch, 2)}};
    Fanout fanout = loaded(before, 2);
    LinkPair after = watch_and_phone();
    NotifyOrder order = notify_order(after.links);
    fanout = fanout_synced(fanout, after.links);
    assert_delivery(next_delivery(fanout, order), kWatchSlot, 0);
    fanout = notified(notified(fanout, order, true), order, true);
    TEST_ASSERT_FALSE(fanout_pending(fanout, order));
    TEST_ASSERT_EQUAL_UINT32(0, fanout.links[kPhoneSlot].sent);
    fanout = fanout_refilled(fanout, 1, after.links);
    assert_delivery(next_delivery(fanout, order), kWatchSlot, 0);
}

void test_a_rival_that_never_subscribes_does_not_touch_the_watch_stream() {
    LinkPair pair{{not_streaming(9), streaming(LinkRole::Watch, 2)}};
    NotifyOrder order = notify_order(pair.links);
    Fanout fanout = loaded(pair, 3);
    for (size_t i = 0; i < 3; ++i) {
        assert_delivery(next_delivery(fanout, order), kWatchSlot, i);
        fanout = notified(fanout, order, true);
    }
    TEST_ASSERT_EQUAL_UINT32(3, fanout.links[kWatchSlot].sent);
    TEST_ASSERT_EQUAL_UINT32(0, fanout.links[0].sent);
    TEST_ASSERT_EQUAL_UINT32(0, fanout.links[0].dropped);
}

void test_counters_restart_with_each_new_connection() {
    LinkPair pair = watch_and_phone();
    NotifyOrder order = notify_order(pair.links);
    Fanout fanout = notified(notified(loaded(pair, 1), order, true), order, false);
    TEST_ASSERT_EQUAL_UINT32(1, fanout.links[kPhoneSlot].dropped);
    LinkPair reconnected{{streaming(LinkRole::Phone, 7), streaming(LinkRole::Watch, 2)}};
    fanout = fanout_synced(fanout, reconnected.links);
    TEST_ASSERT_EQUAL_UINT32(7, fanout.links[kPhoneSlot].link_id);
    TEST_ASSERT_EQUAL_UINT32(0, fanout.links[kPhoneSlot].sent);
    TEST_ASSERT_EQUAL_UINT32(0, fanout.links[kPhoneSlot].dropped);
    TEST_ASSERT_EQUAL_UINT32(1, fanout.links[kWatchSlot].sent);
}

void test_clearing_keeps_the_counters() {
    LinkPair pair = watch_and_phone();
    NotifyOrder order = notify_order(pair.links);
    Fanout fanout = fanout_cleared(notified(loaded(pair, 2), order, true));
    TEST_ASSERT_FALSE(fanout_pending(fanout, order));
    TEST_ASSERT_EQUAL_UINT32(1, fanout.links[kWatchSlot].sent);
}

void test_bundles_use_the_smallest_mtu_of_the_streaming_links() {
    LinkPair pair{{FanoutLink{true, LinkRole::Phone, 1, 247}, FanoutLink{true, LinkRole::Watch, 2, 255}}};
    TEST_ASSERT_EQUAL_UINT16(247, gated_min_mtu(pair.links));
    LinkPair one{{not_streaming(1), FanoutLink{true, LinkRole::Watch, 2, 255}}};
    TEST_ASSERT_EQUAL_UINT16(255, gated_min_mtu(one.links));
    TEST_ASSERT_EQUAL_UINT16(0, gated_min_mtu(LinkPair{{not_streaming(1), not_streaming(2)}}.links));
}

void test_tallies_belong_only_to_the_current_connection() {
    LinkDelivery delivery{4, 0, 10, 2};
    LinkTally current = tally_for_link(delivery, 4);
    TEST_ASSERT_EQUAL_UINT32(10, current.sent);
    TEST_ASSERT_EQUAL_UINT32(2, current.dropped);
    TEST_ASSERT_EQUAL_UINT32(0, tally_for_link(delivery, 5).sent);
    TEST_ASSERT_EQUAL_UINT32(0, tally_for_link(LinkDelivery{0, 0, 3, 0}, 0).sent);
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_the_watch_is_notified_before_the_phone);
    RUN_TEST(test_two_watch_links_go_oldest_first);
    RUN_TEST(test_links_without_a_stream_are_left_out);
    RUN_TEST(test_each_packet_goes_to_the_watch_then_the_phone);
    RUN_TEST(test_a_full_queue_retries_the_watch_and_never_drops_it);
    RUN_TEST(test_a_full_queue_drops_the_phone_packet_and_moves_on);
    RUN_TEST(test_a_phone_at_its_backlog_cap_is_dropped_without_a_notify);
    RUN_TEST(test_the_first_link_in_notify_order_is_never_capped);
    RUN_TEST(test_deliveries_name_the_connection_they_were_planned_for);
    RUN_TEST(test_the_phone_alone_is_retried_like_the_watch);
    RUN_TEST(test_a_link_that_starts_streaming_mid_outbox_joins_at_the_next_cut);
    RUN_TEST(test_a_rival_that_never_subscribes_does_not_touch_the_watch_stream);
    RUN_TEST(test_counters_restart_with_each_new_connection);
    RUN_TEST(test_clearing_keeps_the_counters);
    RUN_TEST(test_bundles_use_the_smallest_mtu_of_the_streaming_links);
    RUN_TEST(test_tallies_belong_only_to_the_current_connection);
    return UNITY_END();
}
```

- [ ] **Step 2: Change the `cut_schedule` tests to the new `cut_action` signature**

In `firmware/test/test_cut_schedule/test_main.cpp` replace the five functions `test_a_closed_gate_discards_everything`, `test_pending_packets_skip_the_cut_and_keep_the_backlog`, `test_an_empty_outbox_bundles_the_cut`, `test_the_outbox_advances_only_on_successful_sends` and `test_clearing_the_outbox_keeps_the_tallies` with:

```cpp
void test_a_closed_gate_discards_everything() {
    TEST_ASSERT_TRUE(cut_action(false, false) == CutAction::DiscardAll);
    TEST_ASSERT_TRUE(cut_action(false, true) == CutAction::DiscardAll);
}

void test_pending_packets_skip_the_cut_and_keep_the_backlog() {
    TEST_ASSERT_TRUE(cut_action(true, true) == CutAction::KeepBacklog);
}

void test_an_empty_outbox_bundles_the_cut() {
    TEST_ASSERT_TRUE(cut_action(true, false) == CutAction::Bundle);
}
```

and delete the two lines `RUN_TEST(test_the_outbox_advances_only_on_successful_sends);` and `RUN_TEST(test_clearing_the_outbox_keeps_the_tallies);` (their behaviour now lives in `test_link_fanout`).

- [ ] **Step 3: Run them to verify they fail**

Run: `python tools/run_native_tests.py test_link_fanout test_cut_schedule`
Expected: `'link_fanout.h' file not found` for the first suite and `no viable conversion from 'bool' to 'const Outbox'` (or similar) for the second; `0 suites passed, 2 failed`.

- [ ] **Step 4: Write `link_fanout`**

`firmware/lib/belt_rules/link_fanout.h`:

```cpp
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "link_roles.h"

// Packets a link other than the first in notify order may hold in the controller and the host queue at once.
constexpr uint16_t kUnprotectedBacklogCap = 3;

struct FanoutLink {
    bool gated;
    LinkRole role;
    uint32_t link_id;
    uint16_t mtu;
};

struct NotifyOrder {
    uint8_t slots[kMaxLinks];
    size_t count;
};

struct LinkDelivery {
    uint32_t link_id;
    size_t next;
    uint32_t sent;
    uint32_t dropped;
};

struct Fanout {
    size_t count;
    uint32_t failures;
    LinkDelivery links[kMaxLinks];
};

struct Delivery {
    bool found;
    uint8_t slot;
    size_t packet;
    bool protected_link;
    uint32_t link_id;
};

struct LinkTally {
    uint32_t sent;
    uint32_t dropped;
};

NotifyOrder notify_order(const FanoutLink* links);
uint16_t gated_min_mtu(const FanoutLink* links);
Fanout fanout_synced(const Fanout& fanout, const FanoutLink* links);
Fanout fanout_refilled(const Fanout& fanout, size_t packet_count, const FanoutLink* links);
Fanout fanout_cleared(const Fanout& fanout);
bool fanout_pending(const Fanout& fanout, const NotifyOrder& order);
Delivery next_delivery(const Fanout& fanout, const NotifyOrder& order);
bool notify_allowed(const Delivery& delivery, uint16_t link_backlog);
Fanout fanout_after_notify(const Fanout& fanout, const Delivery& delivery, bool sent);
LinkTally tally_for_link(const LinkDelivery& delivery, uint32_t link_id);
```

`firmware/lib/belt_rules/link_fanout.cpp`:

```cpp
#include "link_fanout.h"

namespace {

bool goes_before(const FanoutLink& link, const FanoutLink& other) {
    if (link.role != other.role) {
        return link.role == LinkRole::Watch;
    }
    return link.link_id < other.link_id;
}

size_t insert_position(const NotifyOrder& order, const FanoutLink* links, uint8_t slot) {
    size_t position = 0;
    while (position < order.count && !goes_before(links[slot], links[order.slots[position]])) {
        position++;
    }
    return position;
}

NotifyOrder with_slot_in_order(const NotifyOrder& order, const FanoutLink* links, uint8_t slot) {
    NotifyOrder next = order;
    size_t position = insert_position(order, links, slot);
    for (size_t i = next.count; i > position; --i) {
        next.slots[i] = next.slots[i - 1];
    }
    next.slots[position] = slot;
    next.count++;
    return next;
}

Delivery delivery_for(const Fanout& fanout, uint8_t slot, bool protected_link) {
    const LinkDelivery& link = fanout.links[slot];
    return Delivery{true, slot, link.next, protected_link, link.link_id};
}

Delivery earlier_delivery(const Delivery& best, const Delivery& candidate) {
    return !best.found || candidate.packet < best.packet ? candidate : best;
}

Fanout after_sent(const Fanout& fanout, uint8_t slot) {
    Fanout next = fanout;
    next.links[slot].next++;
    next.links[slot].sent++;
    return next;
}

Fanout after_failure(const Fanout& fanout, const Delivery& delivery) {
    Fanout next = fanout;
    if (delivery.protected_link) {
        next.failures++;
        return next;
    }
    next.links[delivery.slot].next++;
    next.links[delivery.slot].dropped++;
    return next;
}

}  // namespace

NotifyOrder notify_order(const FanoutLink* links) {
    NotifyOrder order{};
    for (uint8_t slot = 0; slot < kMaxLinks; ++slot) {
        if (links[slot].gated) {
            order = with_slot_in_order(order, links, slot);
        }
    }
    return order;
}

uint16_t gated_min_mtu(const FanoutLink* links) {
    uint16_t smallest = 0;
    for (size_t slot = 0; slot < kMaxLinks; ++slot) {
        bool smaller = smallest == 0 || links[slot].mtu < smallest;
        if (links[slot].gated && smaller) {
            smallest = links[slot].mtu;
        }
    }
    return smallest;
}

Fanout fanout_synced(const Fanout& fanout, const FanoutLink* links) {
    Fanout next = fanout;
    for (size_t slot = 0; slot < kMaxLinks; ++slot) {
        if (links[slot].link_id != fanout.links[slot].link_id) {
            next.links[slot] = LinkDelivery{links[slot].link_id, fanout.count, 0, 0};
        }
    }
    return next;
}

Fanout fanout_refilled(const Fanout& fanout, size_t packet_count, const FanoutLink* links) {
    Fanout next = fanout;
    next.count = packet_count;
    for (size_t slot = 0; slot < kMaxLinks; ++slot) {
        next.links[slot].next = links[slot].gated ? 0 : packet_count;
    }
    return next;
}

Fanout fanout_cleared(const Fanout& fanout) {
    Fanout next = fanout;
    next.count = 0;
    for (LinkDelivery& link : next.links) {
        link.next = 0;
    }
    return next;
}

bool fanout_pending(const Fanout& fanout, const NotifyOrder& order) {
    for (size_t i = 0; i < order.count; ++i) {
        if (fanout.links[order.slots[i]].next < fanout.count) {
            return true;
        }
    }
    return false;
}

Delivery next_delivery(const Fanout& fanout, const NotifyOrder& order) {
    Delivery best{};
    for (size_t i = 0; i < order.count; ++i) {
        uint8_t slot = order.slots[i];
        if (fanout.links[slot].next < fanout.count) {
            best = earlier_delivery(best, delivery_for(fanout, slot, i == 0));
        }
    }
    return best;
}

// NimBLE's notify only fails once shared buffers run out, so a stalled phone is capped before it can starve the watch.
bool notify_allowed(const Delivery& delivery, uint16_t link_backlog) {
    return delivery.protected_link || link_backlog < kUnprotectedBacklogCap;
}

Fanout fanout_after_notify(const Fanout& fanout, const Delivery& delivery, bool sent) {
    if (!delivery.found) {
        return fanout;
    }
    return sent ? after_sent(fanout, delivery.slot) : after_failure(fanout, delivery);
}

LinkTally tally_for_link(const LinkDelivery& delivery, uint32_t link_id) {
    bool current = link_id != 0 && delivery.link_id == link_id;
    return current ? LinkTally{delivery.sent, delivery.dropped} : LinkTally{0, 0};
}
```

- [ ] **Step 5: Replace `Outbox` in `cut_schedule`**

`firmware/lib/bundler/cut_schedule.h` — delete the `struct Outbox { … };` block and replace the five declarations

```cpp
CutAction cut_action(bool gate_open, const Outbox& outbox);
Outbox outbox_refilled(const Outbox& outbox, size_t packet_count);
Outbox outbox_cleared(const Outbox& outbox);
Outbox outbox_after_send(const Outbox& outbox, bool sent);
bool outbox_empty(const Outbox& outbox);
```

with

```cpp
CutAction cut_action(bool gate_open, bool delivery_pending);
```

`firmware/lib/bundler/cut_schedule.cpp` — delete the whole anonymous namespace (`after_success`, `after_failure`) and the functions `outbox_refilled`, `outbox_cleared`, `outbox_after_send`, `outbox_empty`; replace `cut_action` with:

```cpp
CutAction cut_action(bool gate_open, bool delivery_pending) {
    if (!gate_open) {
        return CutAction::DiscardAll;
    }
    return delivery_pending ? CutAction::KeepBacklog : CutAction::Bundle;
}
```

The file then holds only `deadline_reached`, `status_due`, `next_status_after`, `link_report_due`, `cut_action` and `retry_allowed`.

- [ ] **Step 6: Run the pure tests**

Run: `python tools/run_native_tests.py test_link_fanout test_cut_schedule` → `16 Tests 0 Failures` and `7 Tests 0 Failures`, `2 suites passed, 0 failed`.

- [ ] **Step 7: Rewrite the sender for fan-out**

`firmware/src/stream_sender.h`:

```cpp
#pragma once

#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <stdint.h>

#include "bundler.h"
#include "link_fanout.h"

struct SenderSources {
    QueueHandle_t radar_frames;
    QueueHandle_t imu_samples[kImuCount];
};

struct SenderStats {
    uint32_t notify_failures;
    uint32_t dropped_total;
    uint32_t skipped_cuts;
    LinkDelivery links[kMaxLinks];
};

void stream_sender_start(const SenderSources& sources);
SenderStats stream_sender_stats();
```

`firmware/src/stream_sender.cpp`:

```cpp
#include "stream_sender.h"

#include <Arduino.h>

#include "ble_link.h"
#include "ble_rules.h"
#include "blindside_config.h"
#include "cut_schedule.h"
#include "imu_task.h"
#include "radar_port.h"
#include "stream_backlog.h"

namespace {

constexpr size_t kOutboxCapacity = 6;

struct SenderState {
    SenderSources sources;
    Fanout fanout;
    uint16_t next_seq;
    uint32_t next_status_ms;
    uint32_t reported_generation;
    uint32_t upstream_drops_seen;
    uint32_t skipped_cuts;
};

struct StatusPair {
    StatusEntry entries[kRadarCount];
};

struct LinkView {
    FanoutLink links[kMaxLinks];
    LinkParams params[kMaxLinks];
    NotifyOrder order;
};

StreamBacklog g_backlog;
Packet g_packets[kOutboxCapacity];
SenderState g_state;
portMUX_TYPE g_stats_lock = portMUX_INITIALIZER_UNLOCKED;
SenderStats g_stats;

void drain_radar_frames(QueueHandle_t queue) {
    RadarFrame frame;
    while (xQueueReceive(queue, &frame, 0) == pdTRUE) {
        backlog_add_radar(g_backlog, frame);
    }
}

void drain_imu_samples(QueueHandle_t queue, uint8_t imu_id) {
    ImuSample sample;
    while (xQueueReceive(queue, &sample, 0) == pdTRUE) {
        backlog_add_imu(g_backlog, imu_id, sample);
    }
}

uint32_t upstream_drops() {
    return radar_port_queue_overflows() + imu_task_snapshot(0).queue_overflows + imu_task_snapshot(1).queue_overflows;
}

void drain_sources(SenderState& state) {
    drain_radar_frames(state.sources.radar_frames);
    for (uint8_t imu = 0; imu < kImuCount; ++imu) {
        drain_imu_samples(state.sources.imu_samples[imu], imu);
    }
    uint32_t drops = upstream_drops();
    backlog_note_upstream_drops(g_backlog, drops - state.upstream_drops_seen);
    state.upstream_drops_seen = drops;
}

FanoutLink fanout_link(const LinkSnapshot& link) {
    bool gated = stream_gate_open(StreamGateInput{link.connected, link.subscribed, link.trusted, link.mtu});
    return FanoutLink{gated, link.role, link.link_id, link.mtu};
}

LinkView current_links() {
    LinkView view{};
    for (uint8_t slot = 0; slot < kMaxLinks; ++slot) {
        LinkSnapshot link = ble_link_snapshot(slot);
        view.links[slot] = fanout_link(link);
        view.params[slot] = link.params;
    }
    view.order = notify_order(view.links);
    return view;
}

LinkHealth current_health(uint32_t now_ms) {
    LinkHealth health{};
    for (uint8_t i = 0; i < kRadarCount; ++i) {
        health.radar_alive[i] = radar_snapshot_alive(radar_port_snapshot(i), now_ms);
    }
    for (uint8_t i = 0; i < kImuCount; ++i) {
        health.imu_ok[i] = imu_task_snapshot(i).ok;
    }
    health.data_dropped = g_backlog.dropped_since_cut;
    return health;
}

StatusPair current_status() {
    StatusPair status{};
    for (uint8_t i = 0; i < kRadarCount; ++i) {
        status.entries[i] = radar_port_snapshot(i).status;
        status.entries[i].radar_id = i;
    }
    return status;
}

// LINK carries the parameters of the first link in notify order, the watch's when it is streaming.
CutInput cut_input_for(const SenderState& state, uint32_t cut_ms, uint32_t generation, const LinkView& view) {
    CutInput input = backlog_cut_input(g_backlog, cut_ms);
    if (status_due(cut_ms, state.next_status_ms)) {
        input = with_status(input, current_status().entries);
    }
    if (link_report_due(generation, state.reported_generation)) {
        input = with_link(input, view.params[view.order.slots[0]]);
    }
    return input;
}

void remember_optional_sections(SenderState& state, const BundleResult& result, uint32_t cut_ms,
                                uint32_t generation) {
    if (result.status_consumed) {
        state.next_status_ms = next_status_after(cut_ms);
    }
    if (result.link_consumed) {
        state.reported_generation = generation;
    }
}

void bundle(SenderState& state, uint32_t cut_ms, const LinkView& view) {
    uint32_t generation = ble_link_report_generation();
    CutInput input = cut_input_for(state, cut_ms, generation, view);
    HeaderFields header{packet_flags(current_health(cut_ms)), state.next_seq, cut_ms};
    size_t limit = payload_limit_for_mtu(gated_min_mtu(view.links));
    BundleResult result = bundle_cut(input, header, limit, g_packets, kOutboxCapacity);
    backlog_consume(g_backlog, result);
    state.fanout = fanout_refilled(state.fanout, result.packet_count, view.links);
    state.next_seq = result.next_seq;
    remember_optional_sections(state, result, cut_ms, generation);
}

void make_cut(SenderState& state, uint32_t cut_ms, const LinkView& view) {
    bool gate_open = view.order.count > 0;
    switch (cut_action(gate_open, fanout_pending(state.fanout, view.order))) {
        case CutAction::DiscardAll:
            backlog_clear(g_backlog);
            state.fanout = fanout_cleared(state.fanout);
            break;
        case CutAction::KeepBacklog:
            state.skipped_cuts++;
            break;
        case CutAction::Bundle:
            bundle(state, cut_ms, view);
            break;
    }
}

bool notify_planned_link(const Delivery& delivery) {
    if (!notify_allowed(delivery, ble_link_backlog(delivery.slot))) {
        return false;
    }
    const Packet& packet = g_packets[delivery.packet];
    return ble_link_notify(delivery.slot, delivery.link_id, packet.bytes, packet.length);
}

bool delivered_or_dropped(SenderState& state, const Delivery& delivery) {
    bool sent = notify_planned_link(delivery);
    state.fanout = fanout_after_notify(state.fanout, delivery, sent);
    return sent || !delivery.protected_link;
}

void flush_outbox(SenderState& state, const NotifyOrder& order, uint32_t cut_ms) {
    for (Delivery delivery = next_delivery(state.fanout, order); delivery.found;
         delivery = next_delivery(state.fanout, order)) {
        if (delivered_or_dropped(state, delivery)) {
            continue;
        }
        if (!retry_allowed(cut_ms, millis())) {
            return;
        }
        vTaskDelay(pdMS_TO_TICKS(kNotifyRetryMs));
    }
}

void publish_stats(const SenderState& state) {
    SenderStats stats{};
    stats.notify_failures = state.fanout.failures;
    stats.dropped_total = g_backlog.dropped_total;
    stats.skipped_cuts = state.skipped_cuts;
    for (size_t slot = 0; slot < kMaxLinks; ++slot) {
        stats.links[slot] = state.fanout.links[slot];
    }
    taskENTER_CRITICAL(&g_stats_lock);
    g_stats = stats;
    taskEXIT_CRITICAL(&g_stats_lock);
}

void bundler_task(void*) {
    g_state.next_status_ms = millis();
    TickType_t wake = xTaskGetTickCount();
    for (;;) {
        vTaskDelayUntil(&wake, pdMS_TO_TICKS(kCutPeriodMs));
        uint32_t cut_ms = millis();
        LinkView view = current_links();
        drain_sources(g_state);
        g_state.fanout = fanout_synced(g_state.fanout, view.links);
        make_cut(g_state, cut_ms, view);
        flush_outbox(g_state, view.order, cut_ms);
        publish_stats(g_state);
    }
}

}  // namespace

void stream_sender_start(const SenderSources& sources) {
    g_state = SenderState{};
    g_state.sources = sources;
    backlog_clear(g_backlog);
    xTaskCreatePinnedToCore(bundler_task, "bundler", config::kTaskStackBytes, nullptr, config::kBundlerTaskPriority,
                            nullptr, config::kSensorCore);
}

SenderStats stream_sender_stats() {
    taskENTER_CRITICAL(&g_stats_lock);
    SenderStats stats = g_stats;
    taskEXIT_CRITICAL(&g_stats_lock);
    return stats;
}
```

- [ ] **Step 8: `tx[...]` without the old total**

In `firmware/src/diagnostics.cpp` replace the body of `print_sender()`'s `Serial.printf` call:

```cpp
    Serial.printf(" tx[sent=%lu fail=%lu dropped=%lu skipped=%lu]\n", static_cast<unsigned long>(stats.packets_sent),
                  static_cast<unsigned long>(stats.notify_failures), static_cast<unsigned long>(stats.dropped_total),
                  static_cast<unsigned long>(stats.skipped_cuts));
```

with

```cpp
    Serial.printf(" tx[fail=%lu dropped=%lu skipped=%lu]\n", static_cast<unsigned long>(stats.notify_failures),
                  static_cast<unsigned long>(stats.dropped_total), static_cast<unsigned long>(stats.skipped_cuts));
```

(`sent` per link reaches the `diag` line in Task 7.)

- [ ] **Step 9: Build and run everything**

Run: `python -m platformio run -e esp32dev` → `[SUCCESS]`.
Run: `python tools/run_native_tests.py` → `16 suites passed, 0 failed`.
Run: `grep -rnwE "Outbox|outbox_refilled|outbox_cleared|outbox_after_send|outbox_empty" lib src test` → no output (`kOutboxCapacity`, `flush_outbox` and test names such as `test_an_empty_outbox_bundles_the_cut` are not whole-word matches).

- [ ] **Step 10: Commit**

```bash
git add lib/belt_rules/link_fanout.h lib/belt_rules/link_fanout.cpp test/test_link_fanout/test_main.cpp lib/bundler/cut_schedule.h lib/bundler/cut_schedule.cpp test/test_cut_schedule/test_main.cpp src/stream_sender.h src/stream_sender.cpp src/diagnostics.cpp
git commit -m "feat: el cinturón reparte cada paquete al reloj primero y descarta y limita solo los del celular"
```

---

### Task 4: Two bonds with roles, session flags per bond and per-link pairing

**Files:**
- Create: `firmware/lib/belt_rules/bond_rules.h`, `firmware/lib/belt_rules/bond_rules.cpp`
- Test: `firmware/test/test_belt_rules_bonds/test_main.cpp`
- Create: `firmware/src/bond_store.h`, `firmware/src/bond_store.cpp`
- Modify (full rewrite): `firmware/src/pairing.h`, `firmware/src/pairing.cpp`
- Modify: `firmware/include/blindside_config.h` (`kBondRolesKey`), `firmware/src/main.cpp` (`advertising_plan` counts both bonds)

**Interfaces:**
- Consumes: `kMaxLinks`, `LinkRole`, `role_from_argument`, `role_name` (Task 1); `ble_link_snapshot(uint8_t)`, `ble_link_take_auth_event(uint8_t)`, `ble_link_peer_security(uint8_t)`, `ble_link_set_trusted(uint8_t, bool)`, `ble_link_disconnect(uint8_t)`, `ble_link_disconnect_all()` (Task 2); MVP `pairing_rules.h`.
- Produces:
  - `constexpr size_t kMaxBonds = 2;` `enum class BondAdmission : uint8_t { Add, Replace, Reject };` `struct BondPlan { BondAdmission admission; size_t replaced; };` `struct KeptBond { LinkRole role; bool connected; };`
  - `BondPlan plan_new_bond(const KeptBond* kept, size_t kept_count);` `size_t bonds_kept_at_boot(size_t stored_bonds);`
  - `struct BondIdentity { uint8_t address[6]; uint8_t type; };` `struct BondRoleRecord { BondIdentity identity; uint8_t role; };` `LinkRole recorded_role(const BondRoleRecord* records, size_t count, const BondIdentity& identity);`
  - `uint8_t session_flags_after_write(uint8_t flags, size_t bond_index, bool active);` `uint8_t session_flags_without_bond(uint8_t flags, size_t removed_index);` `bool any_session_active(uint8_t session_flags);` (Task 5)
  - `struct TrustedBonds { NimBLEAddress identities[kMaxBonds]; LinkRole roles[kMaxBonds]; size_t count; };` and the `bond_store_*` functions below; the roles persist in NVS under `blindside/bond_roles`.
  - `PairingState` gains `uint8_t session_flags` (bit i belongs to `trusted.identities[i]`). `void pairing_open_window(PairingState& state, uint32_t now_ms);`, `void pairing_note_role(PairingState& state, uint8_t slot, LinkRole role);`, `void pairing_note_session(PairingState& state, uint8_t slot, bool active);` and `bool pairing_session_running(const PairingState& state);` (Task 5), and `uint8_t pairing_bond_count();` (Tasks 6 and 7; an atomic, safe to read from the NimBLE host task).
  - `pairing_begin`, `pairing_poll`, `pairing_whitelist_only` keep their signatures.

Until Task 8 the belt still accepts one connection, so opening the window while the watch is connected does not let a second device in yet; that is expected in this intermediate state.

- [ ] **Step 1: Write the failing test**

`firmware/test/test_belt_rules_bonds/test_main.cpp`:

```cpp
#include <unity.h>

#include "../test_entry.h"
#include "bond_rules.h"

namespace {

constexpr KeptBond kWatchHere{LinkRole::Watch, true};
constexpr KeptBond kWatchAway{LinkRole::Watch, false};
constexpr KeptBond kPhoneHere{LinkRole::Phone, true};
constexpr KeptBond kPhoneAway{LinkRole::Phone, false};

struct KeptPair {
    KeptBond bonds[kMaxBonds];
};

BondPlan plan_for(const KeptPair& pair) {
    return plan_new_bond(pair.bonds, kMaxBonds);
}

void assert_plan(const BondPlan& plan, BondAdmission admission, size_t replaced) {
    TEST_ASSERT_TRUE(plan.admission == admission);
    TEST_ASSERT_EQUAL_UINT(replaced, plan.replaced);
}

BondIdentity identity(uint8_t last_byte, uint8_t type) {
    return BondIdentity{{0x24, 0x6F, 0x28, 0xAB, 0x0C, last_byte}, type};
}

}  // namespace

void setUp() {}
void tearDown() {}

void test_a_new_bond_is_added_while_there_is_room() {
    const KeptBond one[kMaxBonds] = {kWatchHere, kWatchAway};
    assert_plan(plan_new_bond(one, 0), BondAdmission::Add, 0);
    assert_plan(plan_new_bond(one, 1), BondAdmission::Add, 0);
}

void test_a_new_device_replaces_the_phone_bond_that_is_not_connected() {
    assert_plan(plan_for(KeptPair{{kWatchHere, kPhoneAway}}), BondAdmission::Replace, 1);
    assert_plan(plan_for(KeptPair{{kPhoneAway, kWatchHere}}), BondAdmission::Replace, 0);
}

void test_with_nobody_connected_the_phone_bond_goes_and_the_watch_bond_stays() {
    assert_plan(plan_for(KeptPair{{kWatchAway, kPhoneAway}}), BondAdmission::Replace, 1);
    assert_plan(plan_for(KeptPair{{kPhoneAway, kWatchAway}}), BondAdmission::Replace, 0);
}

void test_the_only_watch_bond_is_kept_while_the_phone_is_connected() {
    assert_plan(plan_for(KeptPair{{kWatchAway, kPhoneHere}}), BondAdmission::Reject, 0);
    assert_plan(plan_for(KeptPair{{kPhoneHere, kWatchAway}}), BondAdmission::Reject, 0);
}

void test_with_two_watch_bonds_the_newest_idle_one_is_replaced() {
    assert_plan(plan_for(KeptPair{{kWatchAway, kWatchAway}}), BondAdmission::Replace, 1);
    assert_plan(plan_for(KeptPair{{kWatchAway, kWatchHere}}), BondAdmission::Replace, 0);
}

void test_with_two_phone_bonds_the_newest_idle_one_is_replaced() {
    assert_plan(plan_for(KeptPair{{kPhoneAway, kPhoneAway}}), BondAdmission::Replace, 1);
    assert_plan(plan_for(KeptPair{{kPhoneAway, kPhoneHere}}), BondAdmission::Replace, 0);
}

void test_a_new_bond_is_refused_while_both_bonded_devices_are_connected() {
    assert_plan(plan_for(KeptPair{{kWatchHere, kPhoneHere}}), BondAdmission::Reject, 0);
    assert_plan(plan_for(KeptPair{{kWatchHere, kWatchHere}}), BondAdmission::Reject, 0);
    assert_plan(plan_for(KeptPair{{kPhoneHere, kPhoneHere}}), BondAdmission::Reject, 0);
}

void test_boot_keeps_at_most_the_two_oldest_bonds() {
    TEST_ASSERT_EQUAL_UINT(0, bonds_kept_at_boot(0));
    TEST_ASSERT_EQUAL_UINT(1, bonds_kept_at_boot(1));
    TEST_ASSERT_EQUAL_UINT(2, bonds_kept_at_boot(2));
    TEST_ASSERT_EQUAL_UINT(2, bonds_kept_at_boot(3));
}

void test_roles_are_recalled_by_identity_and_default_to_the_watch() {
    const BondRoleRecord records[kMaxBonds] = {{identity(0x1E, 0), 0}, {identity(0x2F, 1), 1}};
    TEST_ASSERT_TRUE(recorded_role(records, kMaxBonds, identity(0x2F, 1)) == LinkRole::Phone);
    TEST_ASSERT_TRUE(recorded_role(records, kMaxBonds, identity(0x1E, 0)) == LinkRole::Watch);
    TEST_ASSERT_TRUE(recorded_role(records, kMaxBonds, identity(0x2F, 0)) == LinkRole::Watch);
    TEST_ASSERT_TRUE(recorded_role(records, 0, identity(0x2F, 1)) == LinkRole::Watch);
}

void test_a_session_flag_belongs_to_one_bond() {
    uint8_t flags = session_flags_after_write(0, 1, true);
    TEST_ASSERT_TRUE(any_session_active(flags));
    TEST_ASSERT_TRUE(any_session_active(session_flags_after_write(flags, 0, false)));
    TEST_ASSERT_FALSE(any_session_active(session_flags_after_write(flags, 1, false)));
    TEST_ASSERT_EQUAL_UINT8(flags, session_flags_after_write(flags, kMaxBonds, true));
}

void test_replacing_a_bond_drops_its_session_flag_and_keeps_the_other() {
    uint8_t both = session_flags_after_write(session_flags_after_write(0, 0, true), 1, true);
    uint8_t second_only = session_flags_after_write(0, 1, true);
    TEST_ASSERT_EQUAL_UINT8(1, session_flags_without_bond(both, 0));
    TEST_ASSERT_EQUAL_UINT8(1, session_flags_without_bond(second_only, 0));
    TEST_ASSERT_EQUAL_UINT8(0, session_flags_without_bond(second_only, 1));
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_a_new_bond_is_added_while_there_is_room);
    RUN_TEST(test_a_new_device_replaces_the_phone_bond_that_is_not_connected);
    RUN_TEST(test_with_nobody_connected_the_phone_bond_goes_and_the_watch_bond_stays);
    RUN_TEST(test_the_only_watch_bond_is_kept_while_the_phone_is_connected);
    RUN_TEST(test_with_two_watch_bonds_the_newest_idle_one_is_replaced);
    RUN_TEST(test_with_two_phone_bonds_the_newest_idle_one_is_replaced);
    RUN_TEST(test_a_new_bond_is_refused_while_both_bonded_devices_are_connected);
    RUN_TEST(test_boot_keeps_at_most_the_two_oldest_bonds);
    RUN_TEST(test_roles_are_recalled_by_identity_and_default_to_the_watch);
    RUN_TEST(test_a_session_flag_belongs_to_one_bond);
    RUN_TEST(test_replacing_a_bond_drops_its_session_flag_and_keeps_the_other);
    return UNITY_END();
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `python tools/run_native_tests.py test_belt_rules_bonds`
Expected: `'bond_rules.h' file not found`, `build failed`, `0 suites passed, 1 failed`.

- [ ] **Step 3: Write `bond_rules`**

`firmware/lib/belt_rules/bond_rules.h`:

```cpp
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "link_roles.h"

constexpr size_t kMaxBonds = 2;
constexpr size_t kBondAddressSize = 6;

enum class BondAdmission : uint8_t { Add, Replace, Reject };

struct BondPlan {
    BondAdmission admission;
    size_t replaced;
};

struct KeptBond {
    LinkRole role;
    bool connected;
};

struct BondIdentity {
    uint8_t address[kBondAddressSize];
    uint8_t type;
};

struct BondRoleRecord {
    BondIdentity identity;
    uint8_t role;
};

BondPlan plan_new_bond(const KeptBond* kept, size_t kept_count);
size_t bonds_kept_at_boot(size_t stored_bonds);
LinkRole recorded_role(const BondRoleRecord* records, size_t count, const BondIdentity& identity);
uint8_t session_flags_after_write(uint8_t flags, size_t bond_index, bool active);
uint8_t session_flags_without_bond(uint8_t flags, size_t removed_index);
bool any_session_active(uint8_t session_flags);
```

`firmware/lib/belt_rules/bond_rules.cpp`:

```cpp
#include "bond_rules.h"

#include <string.h>

namespace {

constexpr size_t kWatchBondsBeforeOneMayGo = 2;

bool replaceable(const KeptBond& bond, LinkRole role) {
    return !bond.connected && bond.role == role;
}

size_t newest_replaceable(const KeptBond* kept, size_t count, LinkRole role) {
    for (size_t i = count; i > 0; --i) {
        if (replaceable(kept[i - 1], role)) {
            return i - 1;
        }
    }
    return count;
}

size_t bonds_with_role(const KeptBond* kept, size_t count, LinkRole role) {
    size_t matching = 0;
    for (size_t i = 0; i < count; ++i) {
        matching += kept[i].role == role ? 1 : 0;
    }
    return matching;
}

BondPlan replacing(size_t index, size_t count) {
    return index < count ? BondPlan{BondAdmission::Replace, index} : BondPlan{BondAdmission::Reject, 0};
}

bool same_identity(const BondIdentity& a, const BondIdentity& b) {
    return a.type == b.type && memcmp(a.address, b.address, kBondAddressSize) == 0;
}

uint8_t bond_bit(size_t bond_index) {
    return static_cast<uint8_t>(1u << bond_index);
}

}  // namespace

BondPlan plan_new_bond(const KeptBond* kept, size_t kept_count) {
    if (kept_count < kMaxBonds) {
        return BondPlan{BondAdmission::Add, 0};
    }
    size_t phone = newest_replaceable(kept, kept_count, LinkRole::Phone);
    if (phone < kept_count) {
        return BondPlan{BondAdmission::Replace, phone};
    }
    // The newcomer's role is unknown until it writes 06, so the only watch bond is never traded for it.
    if (bonds_with_role(kept, kept_count, LinkRole::Watch) < kWatchBondsBeforeOneMayGo) {
        return BondPlan{BondAdmission::Reject, 0};
    }
    return replacing(newest_replaceable(kept, kept_count, LinkRole::Watch), kept_count);
}

size_t bonds_kept_at_boot(size_t stored_bonds) {
    return stored_bonds < kMaxBonds ? stored_bonds : kMaxBonds;
}

LinkRole recorded_role(const BondRoleRecord* records, size_t count, const BondIdentity& identity) {
    for (size_t i = 0; i < count; ++i) {
        if (same_identity(records[i].identity, identity)) {
            return role_from_argument(records[i].role);
        }
    }
    return LinkRole::Watch;
}

uint8_t session_flags_after_write(uint8_t flags, size_t bond_index, bool active) {
    if (bond_index >= kMaxBonds) {
        return flags;
    }
    return active ? static_cast<uint8_t>(flags | bond_bit(bond_index))
                  : static_cast<uint8_t>(flags & ~bond_bit(bond_index));
}

uint8_t session_flags_without_bond(uint8_t flags, size_t removed_index) {
    uint8_t below = static_cast<uint8_t>(flags & (bond_bit(removed_index) - 1u));
    uint8_t above = static_cast<uint8_t>((flags >> (removed_index + 1)) << removed_index);
    return static_cast<uint8_t>(below | above);
}

bool any_session_active(uint8_t session_flags) {
    return session_flags != 0;
}
```

- [ ] **Step 4: Run the test**

Run: `python tools/run_native_tests.py test_belt_rules_bonds` → `11 Tests 0 Failures`, `1 suites passed, 0 failed`.

- [ ] **Step 5: Write the bond store glue**

`firmware/src/bond_store.h`:

```cpp
#pragma once

#include <NimBLEDevice.h>
#include <stddef.h>

#include "bond_rules.h"

struct TrustedBonds {
    NimBLEAddress identities[kMaxBonds];
    LinkRole roles[kMaxBonds];
    size_t count;
};

TrustedBonds bond_store_load();
int bond_store_index_of(const TrustedBonds& bonds, const NimBLEAddress& identity);
bool bond_store_contains(const TrustedBonds& bonds, const NimBLEAddress& identity);
TrustedBonds bond_store_with(const TrustedBonds& bonds, const NimBLEAddress& identity);
TrustedBonds bond_store_without(const TrustedBonds& bonds, size_t index);
TrustedBonds bond_store_with_role(const TrustedBonds& bonds, size_t index, LinkRole role);
void bond_store_save_roles(const TrustedBonds& bonds);
void bond_store_forget(const NimBLEAddress& identity);
void bond_store_forget_untrusted(const TrustedBonds& bonds);
void bond_store_forget_all();
void bond_store_refresh_whitelist(const TrustedBonds& bonds);
```

`firmware/src/bond_store.cpp`:

```cpp
#include "bond_store.h"

#include <Preferences.h>
#include <string.h>

#include "blindside_config.h"

namespace {

struct RoleRecords {
    BondRoleRecord entries[kMaxBonds];
    size_t count;
};

size_t stored_bond_count() {
    int stored = NimBLEDevice::getNumBonds();
    return stored > 0 ? static_cast<size_t>(stored) : 0;
}

void clear_whitelist() {
    for (size_t i = NimBLEDevice::getWhiteListCount(); i > 0; --i) {
        NimBLEDevice::whiteListRemove(NimBLEDevice::getWhiteListAddress(i - 1));
    }
}

BondIdentity identity_bytes(const NimBLEAddress& address) {
    BondIdentity identity{};
    memcpy(identity.address, address.getVal(), kBondAddressSize);
    identity.type = address.getType();
    return identity;
}

RoleRecords stored_role_records() {
    RoleRecords records{};
    Preferences preferences;
    preferences.begin(config::kPreferencesNamespace, true);
    size_t bytes = preferences.getBytes(config::kBondRolesKey, records.entries, sizeof(records.entries));
    preferences.end();
    records.count = bytes / sizeof(BondRoleRecord);
    return records;
}

TrustedBonds with_recorded_roles(const TrustedBonds& bonds) {
    RoleRecords records = stored_role_records();
    TrustedBonds next = bonds;
    for (size_t i = 0; i < next.count; ++i) {
        next.roles[i] = recorded_role(records.entries, records.count, identity_bytes(next.identities[i]));
    }
    return next;
}

}  // namespace

// NimBLE lists bonds in storage order, oldest first, so a crash mid-adoption loses only the newest bond.
TrustedBonds bond_store_load() {
    TrustedBonds bonds{};
    size_t kept = bonds_kept_at_boot(stored_bond_count());
    for (size_t i = 0; i < kept; ++i) {
        bonds = bond_store_with(bonds, NimBLEDevice::getBondedAddress(static_cast<int>(i)));
    }
    bond_store_forget_untrusted(bonds);
    return with_recorded_roles(bonds);
}

int bond_store_index_of(const TrustedBonds& bonds, const NimBLEAddress& identity) {
    for (size_t i = 0; i < bonds.count; ++i) {
        if (bonds.identities[i] == identity) {
            return static_cast<int>(i);
        }
    }
    return -1;
}

bool bond_store_contains(const TrustedBonds& bonds, const NimBLEAddress& identity) {
    return bond_store_index_of(bonds, identity) >= 0;
}

TrustedBonds bond_store_with(const TrustedBonds& bonds, const NimBLEAddress& identity) {
    if (bonds.count >= kMaxBonds || bond_store_contains(bonds, identity)) {
        return bonds;
    }
    TrustedBonds next = bonds;
    next.identities[next.count] = identity;
    next.roles[next.count] = LinkRole::Watch;
    next.count++;
    return next;
}

TrustedBonds bond_store_without(const TrustedBonds& bonds, size_t index) {
    TrustedBonds next{};
    for (size_t i = 0; i < bonds.count; ++i) {
        if (i != index) {
            next.identities[next.count] = bonds.identities[i];
            next.roles[next.count] = bonds.roles[i];
            next.count++;
        }
    }
    return next;
}

TrustedBonds bond_store_with_role(const TrustedBonds& bonds, size_t index, LinkRole role) {
    TrustedBonds next = bonds;
    if (index < next.count) {
        next.roles[index] = role;
    }
    return next;
}

void bond_store_save_roles(const TrustedBonds& bonds) {
    RoleRecords records{};
    for (size_t i = 0; i < bonds.count; ++i) {
        records.entries[i] = BondRoleRecord{identity_bytes(bonds.identities[i]), static_cast<uint8_t>(bonds.roles[i])};
    }
    Preferences preferences;
    preferences.begin(config::kPreferencesNamespace, false);
    preferences.putBytes(config::kBondRolesKey, records.entries, bonds.count * sizeof(BondRoleRecord));
    preferences.end();
}

void bond_store_forget(const NimBLEAddress& identity) {
    NimBLEDevice::deleteBond(identity);
}

void bond_store_forget_untrusted(const TrustedBonds& bonds) {
    for (int i = static_cast<int>(stored_bond_count()) - 1; i >= 0; --i) {
        NimBLEAddress bonded = NimBLEDevice::getBondedAddress(i);
        if (!bond_store_contains(bonds, bonded)) {
            NimBLEDevice::deleteBond(bonded);
        }
    }
}

void bond_store_forget_all() {
    NimBLEDevice::deleteAllBonds();
    Preferences preferences;
    preferences.begin(config::kPreferencesNamespace, false);
    preferences.remove(config::kBondRolesKey);
    preferences.end();
}

void bond_store_refresh_whitelist(const TrustedBonds& bonds) {
    clear_whitelist();
    for (size_t i = 0; i < bonds.count; ++i) {
        NimBLEDevice::whiteListAdd(bonds.identities[i]);
    }
}
```

- [ ] **Step 6: Rewrite pairing per slot**

`firmware/src/pairing.h`:

```cpp
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "bond_store.h"
#include "link_roles.h"
#include "pairing_rules.h"

constexpr size_t kSerialLineCapacity = 24;

struct SlotPairing {
    uint32_t link_id;
    bool pairing_allowed;
    bool drop_requested;
};

struct PairingState {
    PairingWindow window;
    TrustedBonds trusted;
    uint8_t session_flags;
    uint32_t passkey;
    bool button_was_pressed;
    uint32_t button_pressed_ms;
    SlotPairing slots[kMaxLinks];
    char serial_line[kSerialLineCapacity];
    size_t serial_length;
};

PairingState pairing_begin(uint32_t now_ms);
void pairing_poll(PairingState& state, uint32_t now_ms);
void pairing_open_window(PairingState& state, uint32_t now_ms);
void pairing_note_role(PairingState& state, uint8_t slot, LinkRole role);
void pairing_note_session(PairingState& state, uint8_t slot, bool active);
bool pairing_session_running(const PairingState& state);
bool pairing_whitelist_only(const PairingState& state);
uint8_t pairing_bond_count();
```

`firmware/src/pairing.cpp`:

```cpp
#include "pairing.h"

#include <Arduino.h>
#include <Preferences.h>
#include <esp_random.h>

#include <atomic>

#include "ble_link.h"
#include "blindside_config.h"
#include "serial_command.h"

namespace {

constexpr uint32_t kNoStoredPasskey = UINT32_MAX;

struct KeptBonds {
    KeptBond bonds[kMaxBonds];
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

void track_slot(PairingState& state, uint8_t slot, const LinkSnapshot& link) {
    if (link.link_id != state.slots[slot].link_id) {
        state.slots[slot] = SlotPairing{link.link_id, state.window.open, false};
    }
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

void poll_slot(PairingState& state, uint8_t slot, uint32_t now_ms) {
    track_slot(state, slot, ble_link_snapshot(slot));
    handle_auth_event(state, slot);
    drop_unwanted_peer(state, slot, now_ms);
}

}  // namespace

PairingState pairing_begin(uint32_t now_ms) {
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
    for (uint8_t slot = 0; slot < kMaxLinks; ++slot) {
        poll_slot(state, slot, now_ms);
    }
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
    return any_session_active(state.session_flags);
}

bool pairing_whitelist_only(const PairingState& state) {
    return config::kConnectWhitelistOnly && !state.window.open && state.trusted.count > 0;
}

uint8_t pairing_bond_count() {
    return g_bond_count.load();
}
```

- [ ] **Step 7: Store the roles key and count both bonds when advertising**

In `firmware/include/blindside_config.h`, right after `constexpr char kPasskeyKey[] = "passkey";`, add:

```cpp
constexpr char kBondRolesKey[] = "bond_roles";
```

In `firmware/src/main.cpp` replace the Task 2 helper

```cpp
// The MVP pairing keeps a single bond; Task 4 counts both.
AdvertisingPlan advertising_plan() {
    size_t bonds = g_pairing.trusted.isNull() ? 0 : 1;
    return AdvertisingPlan{pairing_whitelist_only(g_pairing), g_pairing.window.open, bonds};
}
```

with

```cpp
AdvertisingPlan advertising_plan() {
    return AdvertisingPlan{pairing_whitelist_only(g_pairing), g_pairing.window.open, g_pairing.trusted.count};
}
```

- [ ] **Step 8: Build and run everything**

Run: `python -m platformio run -e esp32dev` → `[SUCCESS]`.
Run: `python tools/run_native_tests.py` → `17 suites passed, 0 failed`.

- [ ] **Step 9: Commit**

```bash
git add lib/belt_rules/bond_rules.h lib/belt_rules/bond_rules.cpp test/test_belt_rules_bonds/test_main.cpp src/bond_store.h src/bond_store.cpp src/pairing.h src/pairing.cpp include/blindside_config.h src/main.cpp
git commit -m "feat: el cinturón guarda dos bonds con su rol, nunca cambia el único bond del reloj y empareja por enlace"
```

---

### Task 5: `05 OPEN_PAIRING_WINDOW`, `06 SET_ROLE`, queued and trust-checked control

**Files:**
- Modify (full rewrite): `firmware/lib/belt_rules/control_command.h`, `firmware/lib/belt_rules/control_command.cpp`
- Modify: `firmware/lib/belt_rules/led_pattern.h`, `firmware/lib/belt_rules/led_pattern.cpp`
- Modify: `firmware/test/test_belt_rules_link/test_main.cpp`, `firmware/test/test_belt_rules_pairing/test_main.cpp`
- Modify: `firmware/src/main.cpp` (control per slot through `control_action`, session per bonded device, LED)

**Interfaces:**
- Consumes: `role_from_argument`, `kMaxLinks` (Task 1); `ble_link_take_control(uint8_t)` (queued), `ble_link_set_role(uint8_t, LinkRole)`, `ble_link_snapshot(uint8_t).trusted` (Task 2); `pairing_open_window`, `pairing_note_role`, `pairing_note_session`, `pairing_session_running`, `any_session_active(uint8_t)`, `session_flags_after_write` (Task 4).
- Produces:
  - `constexpr uint8_t kControlOpenPairingWindow = 0x05;` `constexpr uint8_t kControlSetRole = 0x06;`
  - `ControlKind::OpenPairingWindow` (argument 0), `ControlKind::SetRole` (argument 0/1)
  - `enum class ControlAction : uint8_t { Ignore, RejectUntrusted, RejectInvalid, RestartRadar, Identify, SetSession, OpenPairingWindow, SetRole };` `ControlAction control_action(ControlKind kind, bool link_trusted, bool session_running);` (`RejectUntrusted` for every command from an untrusted link; `Ignore` for IDENTIFY while a session runs)
  - `struct LedInputs` gains a fifth field `bool session_active;` (the pairing blink is suppressed while it is true)

- [ ] **Step 1: Write the failing control tests**

In `firmware/test/test_belt_rules_link/test_main.cpp`, add `#include "bond_rules.h"` right after `#include "ble_rules.h"`, and add after `test_valid_control_writes_are_parsed()`:

```cpp
void test_open_pairing_and_set_role_writes_are_parsed() {
    const uint8_t open_pairing[] = {0x05};
    const uint8_t role_watch[] = {0x06, 0x00};
    const uint8_t role_phone[] = {0x06, 0x01};
    assert_command(parsed(open_pairing, 1), ControlKind::OpenPairingWindow, 0);
    assert_command(parsed(role_watch, 2), ControlKind::SetRole, 0);
    assert_command(parsed(role_phone, 2), ControlKind::SetRole, 1);
}

void test_malformed_open_pairing_and_set_role_writes_are_invalid() {
    const uint8_t open_pairing_long[] = {0x05, 0x01};
    const uint8_t role_missing[] = {0x06};
    const uint8_t role_two[] = {0x06, 0x02};
    const uint8_t role_long[] = {0x06, 0x01, 0x00};
    const uint8_t next_free[] = {0x07};
    assert_command(parsed(open_pairing_long, 2), ControlKind::Invalid, 0);
    assert_command(parsed(role_missing, 1), ControlKind::Invalid, 0);
    assert_command(parsed(role_two, 2), ControlKind::Invalid, 0);
    assert_command(parsed(role_long, 3), ControlKind::Invalid, 0);
    assert_command(parsed(next_free, 1), ControlKind::Invalid, 0);
}

void test_an_untrusted_link_changes_nothing() {
    const ControlKind kinds[] = {ControlKind::Invalid,       ControlKind::RestartRadar,      ControlKind::Identify,
                                 ControlKind::SessionActive, ControlKind::OpenPairingWindow, ControlKind::SetRole};
    for (ControlKind kind : kinds) {
        TEST_ASSERT_TRUE(control_action(kind, false, false) == ControlAction::RejectUntrusted);
    }
    TEST_ASSERT_TRUE(control_action(ControlKind::None, false, false) == ControlAction::Ignore);
}

void test_a_trusted_link_gets_the_action_of_its_command() {
    TEST_ASSERT_TRUE(control_action(ControlKind::None, true, false) == ControlAction::Ignore);
    TEST_ASSERT_TRUE(control_action(ControlKind::Invalid, true, false) == ControlAction::RejectInvalid);
    TEST_ASSERT_TRUE(control_action(ControlKind::RestartRadar, true, true) == ControlAction::RestartRadar);
    TEST_ASSERT_TRUE(control_action(ControlKind::Identify, true, false) == ControlAction::Identify);
    TEST_ASSERT_TRUE(control_action(ControlKind::SessionActive, true, true) == ControlAction::SetSession);
    TEST_ASSERT_TRUE(control_action(ControlKind::OpenPairingWindow, true, true) == ControlAction::OpenPairingWindow);
    TEST_ASSERT_TRUE(control_action(ControlKind::SetRole, true, true) == ControlAction::SetRole);
}

void test_identify_is_ignored_while_any_bonded_device_has_a_session() {
    uint8_t watch_in_session = session_flags_after_write(0, 0, true);
    TEST_ASSERT_TRUE(control_action(ControlKind::Identify, true, any_session_active(watch_in_session)) ==
                     ControlAction::Ignore);
    TEST_ASSERT_TRUE(control_action(ControlKind::Identify, true, any_session_active(0)) == ControlAction::Identify);
}
```

and in `run_all_tests()` add, right after `RUN_TEST(test_malformed_control_writes_are_invalid);`:

```cpp
    RUN_TEST(test_open_pairing_and_set_role_writes_are_parsed);
    RUN_TEST(test_malformed_open_pairing_and_set_role_writes_are_invalid);
    RUN_TEST(test_an_untrusted_link_changes_nothing);
    RUN_TEST(test_a_trusted_link_gets_the_action_of_its_command);
    RUN_TEST(test_identify_is_ignored_while_any_bonded_device_has_a_session);
```

- [ ] **Step 2: Write the failing LED test**

In `firmware/test/test_belt_rules_pairing/test_main.cpp`:
- give the three helpers the fifth field: `return LedInputs{now_ms, false, false, 0, false};`, `return LedInputs{now_ms, true, false, 0, false};`, `return LedInputs{now_ms, pairing_open, true, started_ms, false};`;
- add before `test_identify_blinks_three_times_then_stops()`:

```cpp
void test_pairing_window_stays_dark_while_a_session_runs() {
    for (uint32_t t = 2000; t < 62000; t += 37) {
        TEST_ASSERT_FALSE(led_on(LedInputs{t, true, false, 0, true}));
    }
}
```

- add `RUN_TEST(test_pairing_window_stays_dark_while_a_session_runs);` right before `RUN_TEST(test_identify_blinks_three_times_then_stops);`.

- [ ] **Step 3: Run them to verify they fail**

Run: `python tools/run_native_tests.py test_belt_rules_link test_belt_rules_pairing`
Expected: errors such as `no member named 'OpenPairingWindow' in 'ControlKind'`, `use of undeclared identifier 'control_action'` and `excess elements in struct initializer` (LedInputs); `0 suites passed, 2 failed`.

- [ ] **Step 4: Write the control rules**

`firmware/lib/belt_rules/control_command.h`:

```cpp
#pragma once

#include <stddef.h>
#include <stdint.h>

constexpr uint8_t kControlRestartRadar = 0x01;
constexpr uint8_t kControlIdentify = 0x03;
constexpr uint8_t kControlSessionActive = 0x04;
constexpr uint8_t kControlOpenPairingWindow = 0x05;
constexpr uint8_t kControlSetRole = 0x06;
constexpr size_t kMaxControlSize = 4;

enum class ControlKind : uint8_t {
    None,
    Invalid,
    RestartRadar,
    Identify,
    SessionActive,
    OpenPairingWindow,
    SetRole,
};

enum class ControlAction : uint8_t {
    Ignore,
    RejectUntrusted,
    RejectInvalid,
    RestartRadar,
    Identify,
    SetSession,
    OpenPairingWindow,
    SetRole,
};

struct ControlCommand {
    ControlKind kind;
    uint8_t argument;
};

ControlCommand parse_control(const uint8_t* bytes, size_t length);
bool identify_allowed(bool session_active);
ControlAction control_action(ControlKind kind, bool link_trusted, bool session_running);
```

`firmware/lib/belt_rules/control_command.cpp`:

```cpp
#include "control_command.h"

namespace {

constexpr uint8_t kMaxBinaryArgument = 1;

// Indexed by ControlKind.
constexpr ControlAction kTrustedActions[] = {
    ControlAction::Ignore,       ControlAction::RejectInvalid, ControlAction::RestartRadar,
    ControlAction::Identify,     ControlAction::SetSession,    ControlAction::OpenPairingWindow,
    ControlAction::SetRole,
};
static_assert(sizeof(kTrustedActions) / sizeof(kTrustedActions[0]) == static_cast<size_t>(ControlKind::SetRole) + 1,
              "one action per control kind");

ControlCommand invalid_command() {
    return ControlCommand{ControlKind::Invalid, 0};
}

ControlCommand command_with_binary_argument(ControlKind kind, const uint8_t* bytes, size_t length) {
    if (length != 2 || bytes[1] > kMaxBinaryArgument) {
        return invalid_command();
    }
    return ControlCommand{kind, bytes[1]};
}

ControlCommand command_without_argument(ControlKind kind, size_t length) {
    return length == 1 ? ControlCommand{kind, 0} : invalid_command();
}

}  // namespace

ControlCommand parse_control(const uint8_t* bytes, size_t length) {
    if (bytes == nullptr || length == 0) {
        return invalid_command();
    }
    switch (bytes[0]) {
        case kControlRestartRadar:
            return command_with_binary_argument(ControlKind::RestartRadar, bytes, length);
        case kControlIdentify:
            return command_without_argument(ControlKind::Identify, length);
        case kControlSessionActive:
            return command_with_binary_argument(ControlKind::SessionActive, bytes, length);
        case kControlOpenPairingWindow:
            return command_without_argument(ControlKind::OpenPairingWindow, length);
        case kControlSetRole:
            return command_with_binary_argument(ControlKind::SetRole, bytes, length);
        default:
            return invalid_command();
    }
}

bool identify_allowed(bool session_active) {
    return !session_active;
}

// A link that authenticated but is not trusted is about to be dropped, so nothing it writes may change the belt.
ControlAction control_action(ControlKind kind, bool link_trusted, bool session_running) {
    if (kind == ControlKind::None) {
        return ControlAction::Ignore;
    }
    if (!link_trusted) {
        return ControlAction::RejectUntrusted;
    }
    if (kind == ControlKind::Identify && !identify_allowed(session_running)) {
        return ControlAction::Ignore;
    }
    return kTrustedActions[static_cast<size_t>(kind)];
}
```

- [ ] **Step 5: Dark pairing window during a session**

`firmware/lib/belt_rules/led_pattern.h` — `LedInputs` becomes:

```cpp
struct LedInputs {
    uint32_t now_ms;
    bool pairing_window_open;
    bool identify_active;
    uint32_t identify_started_ms;
    bool session_active;
};
```

`firmware/lib/belt_rules/led_pattern.cpp` — add inside the anonymous namespace, after `identify_showing`:

```cpp
bool pairing_blink_showing(const LedInputs& inputs) {
    return inputs.pairing_window_open && !inputs.session_active;
}
```

and in `led_on` replace `if (inputs.pairing_window_open) {` with `if (pairing_blink_showing(inputs)) {`.

- [ ] **Step 6: Run the pure tests**

Run: `python tools/run_native_tests.py test_belt_rules_link test_belt_rules_pairing` → `16 Tests 0 Failures` and `19 Tests 0 Failures`, `2 suites passed, 0 failed`.

- [ ] **Step 7: Control per link in `main.cpp`**

In `firmware/src/main.cpp`:
- delete `bool g_session_active = false;` (the session now lives per bonded device in `g_pairing.session_flags`, Task 4);
- replace the functions `start_identify` and `handle_control` with:

```cpp
void start_identify(uint32_t now_ms) {
    g_identify_active = true;
    g_identify_started_ms = now_ms;
}

void set_role(uint8_t slot, uint8_t argument) {
    LinkRole role = role_from_argument(argument);
    ble_link_set_role(slot, role);
    pairing_note_role(g_pairing, slot, role);
}

// pairing_poll runs first in loop(), so a link that just authenticated is already trusted here.
ControlAction action_for(uint8_t slot, const ControlCommand& command) {
    return control_action(command.kind, ble_link_snapshot(slot).trusted, pairing_session_running(g_pairing));
}

void apply_control(uint8_t slot, const ControlCommand& command, uint32_t now_ms) {
    switch (action_for(slot, command)) {
        case ControlAction::RestartRadar:
            radar_port_request_restart(command.argument);
            break;
        case ControlAction::Identify:
            start_identify(now_ms);
            break;
        case ControlAction::SetSession:
            pairing_note_session(g_pairing, slot, command.argument == 1);
            break;
        case ControlAction::OpenPairingWindow:
            pairing_open_window(g_pairing, now_ms);
            break;
        case ControlAction::SetRole:
            set_role(slot, command.argument);
            break;
        case ControlAction::RejectInvalid:
            Serial.println("control: ignored invalid write");
            break;
        case ControlAction::RejectUntrusted:
            Serial.println("control: ignored a write from an untrusted link");
            break;
        case ControlAction::Ignore:
            break;
    }
}

void handle_slot_control(uint8_t slot, uint32_t now_ms) {
    for (ControlCommand command = ble_link_take_control(slot); command.kind != ControlKind::None;
         command = ble_link_take_control(slot)) {
        apply_control(slot, command, now_ms);
    }
}

void handle_control(uint32_t now_ms) {
    for (uint8_t slot = 0; slot < kMaxLinks; ++slot) {
        handle_slot_control(slot, now_ms);
    }
}
```

- in `show_led` replace the `status_led_show(...)` call with:

```cpp
    status_led_show(LedInputs{now_ms, g_pairing.window.open, g_identify_active, g_identify_started_ms,
                              pairing_session_running(g_pairing)});
```

- in `setup()` replace `status_led_show(LedInputs{millis(), false, false, 0});` with `status_led_show(LedInputs{millis(), false, false, 0, false});`.

`loop()` already calls `pairing_poll` before `handle_control`; keep that order (the comment on `action_for` says why). `pairing_note_role` writes NVS only when a bond's role changes (the phone's first `06 01`), never on every connection.

- [ ] **Step 8: Build and run everything**

Run: `python -m platformio run -e esp32dev` → `[SUCCESS]`, no `-Wswitch` warning.
Run: `python tools/run_native_tests.py` → `17 suites passed, 0 failed`.

- [ ] **Step 9: Commit**

```bash
git add lib/belt_rules/control_command.h lib/belt_rules/control_command.cpp lib/belt_rules/led_pattern.h lib/belt_rules/led_pattern.cpp test/test_belt_rules_link/test_main.cpp test/test_belt_rules_pairing/test_main.cpp src/main.cpp
git commit -m "feat: comandos 05 OPEN_PAIRING_WINDOW y 06 SET_ROLE, en orden y solo desde enlaces de confianza, con la sesión guardada por dispositivo"
```

---

### Task 6: `info` with `conns` and `bonds` inside 512 B

**Files:**
- Modify (full rewrite): `firmware/lib/belt_rules/info_json.h`, `firmware/lib/belt_rules/info_json.cpp`
- Modify (full rewrite): `firmware/test/test_belt_rules_link/test_main.cpp`
- Modify: `firmware/src/main.cpp` (`current_info`)

**Interfaces:**
- Consumes: `LinkRole`, `role_name`, `kMaxLinks` (Task 1); `ble_link_snapshot(uint8_t)` (Task 2); `SenderStats::links`, `tally_for_link`, `LinkTally` (Task 3); `pairing_bond_count()` (Task 4).
- Produces:
  - `constexpr uint32_t kInfoCounterModulus = 1000000;`
  - `struct ConnInfo { LinkRole role; LinkParams params; uint32_t sent; uint32_t dropped; };`
  - `struct BeltInfo { const char* firmware_version; uint32_t boot_id; const char* reset_reason; uint16_t mtu; RadarInfo radars[kRadarCount]; ImuInfo imus[kImuCount]; int8_t tx_power_dbm; ConnInfo conns[kMaxLinks]; size_t conn_count; uint8_t bonds; uint32_t uptime_s; };` (the field `conn` is gone)
  - `size_t format_info_json(const BeltInfo& info, char* out, size_t out_size);` (unchanged signature)

- [ ] **Step 1: Write the failing tests (whole suite)**

Replace `firmware/test/test_belt_rules_link/test_main.cpp` with:

```cpp
#include <string.h>
#include <unity.h>

#include "../test_entry.h"
#include "ble_rules.h"
#include "bond_rules.h"
#include "control_command.h"
#include "info_json.h"

namespace {

ControlCommand parsed(const uint8_t* bytes, size_t length) {
    return parse_control(bytes, length);
}

void assert_command(const ControlCommand& command, ControlKind kind, uint8_t argument) {
    TEST_ASSERT_TRUE(command.kind == kind);
    TEST_ASSERT_EQUAL_UINT8(argument, command.argument);
}

FirmwareText firmware_named(const char* text) {
    FirmwareText firmware{};
    strncpy(firmware.text, text, sizeof(firmware.text) - 1);
    return firmware;
}

ConnInfo conn(LinkRole role, uint16_t interval_units, uint32_t sent, uint32_t dropped) {
    return ConnInfo{role, LinkParams{interval_units, 0, 400}, sent, dropped};
}

size_t occurrences(const char* text, const char* needle) {
    size_t count = 0;
    for (const char* at = strstr(text, needle); at != nullptr; at = strstr(at + 1, needle)) {
        count++;
    }
    return count;
}

BeltInfo example_info() {
    BeltInfo info{};
    info.firmware_version = "0.2.0";
    info.boot_id = 0x9f3a12c4u;
    info.reset_reason = "POWERON";
    info.mtu = 255;
    info.radars[0] = RadarInfo{0, firmware_named("V2.04.23101915"), 256000};
    info.radars[1] = RadarInfo{1, firmware_named("V2.04.23101915"), 256000};
    info.imus[0] = ImuInfo{0, 104, 0};
    info.imus[1] = ImuInfo{1, 112, 3};
    info.tx_power_dbm = 9;
    info.conns[0] = conn(LinkRole::Watch, 36, 1234, 0);
    info.conns[1] = conn(LinkRole::Phone, 60, 1200, 3);
    info.conn_count = 2;
    info.bonds = 2;
    info.uptime_s = 42;
    return info;
}

BeltInfo longest_info() {
    BeltInfo info = example_info();
    info.reset_reason = "DEEPSLEEP";
    info.mtu = 517;
    info.radars[0] = RadarInfo{0, firmware_named("VFF.FF.FFFFFFFF"), 460800};
    info.radars[1] = RadarInfo{1, firmware_named("VFF.FF.FFFFFFFF"), 460800};
    info.imus[0] = ImuInfo{0, 255, 4294967295u};
    info.imus[1] = ImuInfo{1, 255, 4294967295u};
    info.conns[0] = ConnInfo{LinkRole::Watch, LinkParams{3200, 499, 3200}, 999999, 999999};
    info.conns[1] = ConnInfo{LinkRole::Phone, LinkParams{3200, 499, 3200}, 999999, 999999};
    info.uptime_s = 4294967;
    return info;
}

StreamGateInput ready_link(uint16_t mtu) {
    return StreamGateInput{true, true, true, mtu};
}

}  // namespace

void setUp() {}
void tearDown() {}

void test_valid_control_writes_are_parsed() {
    const uint8_t restart_b[] = {0x01, 0x01};
    const uint8_t identify[] = {0x03};
    const uint8_t session_on[] = {0x04, 0x01};
    const uint8_t session_off[] = {0x04, 0x00};
    assert_command(parsed(restart_b, 2), ControlKind::RestartRadar, 1);
    assert_command(parsed(identify, 1), ControlKind::Identify, 0);
    assert_command(parsed(session_on, 2), ControlKind::SessionActive, 1);
    assert_command(parsed(session_off, 2), ControlKind::SessionActive, 0);
}

void test_open_pairing_and_set_role_writes_are_parsed() {
    const uint8_t open_pairing[] = {0x05};
    const uint8_t role_watch[] = {0x06, 0x00};
    const uint8_t role_phone[] = {0x06, 0x01};
    assert_command(parsed(open_pairing, 1), ControlKind::OpenPairingWindow, 0);
    assert_command(parsed(role_watch, 2), ControlKind::SetRole, 0);
    assert_command(parsed(role_phone, 2), ControlKind::SetRole, 1);
}

void test_malformed_open_pairing_and_set_role_writes_are_invalid() {
    const uint8_t open_pairing_long[] = {0x05, 0x01};
    const uint8_t role_missing[] = {0x06};
    const uint8_t role_two[] = {0x06, 0x02};
    const uint8_t role_long[] = {0x06, 0x01, 0x00};
    const uint8_t next_free[] = {0x07};
    assert_command(parsed(open_pairing_long, 2), ControlKind::Invalid, 0);
    assert_command(parsed(role_missing, 1), ControlKind::Invalid, 0);
    assert_command(parsed(role_two, 2), ControlKind::Invalid, 0);
    assert_command(parsed(role_long, 3), ControlKind::Invalid, 0);
    assert_command(parsed(next_free, 1), ControlKind::Invalid, 0);
}

void test_an_untrusted_link_changes_nothing() {
    const ControlKind kinds[] = {ControlKind::Invalid,       ControlKind::RestartRadar,      ControlKind::Identify,
                                 ControlKind::SessionActive, ControlKind::OpenPairingWindow, ControlKind::SetRole};
    for (ControlKind kind : kinds) {
        TEST_ASSERT_TRUE(control_action(kind, false, false) == ControlAction::RejectUntrusted);
    }
    TEST_ASSERT_TRUE(control_action(ControlKind::None, false, false) == ControlAction::Ignore);
}

void test_a_trusted_link_gets_the_action_of_its_command() {
    TEST_ASSERT_TRUE(control_action(ControlKind::None, true, false) == ControlAction::Ignore);
    TEST_ASSERT_TRUE(control_action(ControlKind::Invalid, true, false) == ControlAction::RejectInvalid);
    TEST_ASSERT_TRUE(control_action(ControlKind::RestartRadar, true, true) == ControlAction::RestartRadar);
    TEST_ASSERT_TRUE(control_action(ControlKind::Identify, true, false) == ControlAction::Identify);
    TEST_ASSERT_TRUE(control_action(ControlKind::SessionActive, true, true) == ControlAction::SetSession);
    TEST_ASSERT_TRUE(control_action(ControlKind::OpenPairingWindow, true, true) == ControlAction::OpenPairingWindow);
    TEST_ASSERT_TRUE(control_action(ControlKind::SetRole, true, true) == ControlAction::SetRole);
}

void test_identify_is_ignored_while_any_bonded_device_has_a_session() {
    uint8_t watch_in_session = session_flags_after_write(0, 0, true);
    TEST_ASSERT_TRUE(control_action(ControlKind::Identify, true, any_session_active(watch_in_session)) ==
                     ControlAction::Ignore);
    TEST_ASSERT_TRUE(control_action(ControlKind::Identify, true, any_session_active(0)) == ControlAction::Identify);
}

void test_malformed_control_writes_are_invalid() {
    const uint8_t radar_two[] = {0x01, 0x02};
    const uint8_t restart_short[] = {0x01};
    const uint8_t identify_long[] = {0x03, 0x00};
    const uint8_t session_two[] = {0x04, 0x02};
    const uint8_t set_zones[] = {0x02, 0x00, 0x01};
    const uint8_t unknown[] = {0x7F};
    assert_command(parsed(radar_two, 2), ControlKind::Invalid, 0);
    assert_command(parsed(restart_short, 1), ControlKind::Invalid, 0);
    assert_command(parsed(identify_long, 2), ControlKind::Invalid, 0);
    assert_command(parsed(session_two, 2), ControlKind::Invalid, 0);
    assert_command(parsed(set_zones, 3), ControlKind::Invalid, 0);
    assert_command(parsed(unknown, 1), ControlKind::Invalid, 0);
    assert_command(parsed(nullptr, 0), ControlKind::Invalid, 0);
}

void test_identify_is_refused_during_a_session() {
    TEST_ASSERT_FALSE(identify_allowed(true));
    TEST_ASSERT_TRUE(identify_allowed(false));
}

void test_stream_gate_needs_subscribed_trusted_peer_and_mtu_247() {
    TEST_ASSERT_TRUE(stream_gate_open(ready_link(255)));
    TEST_ASSERT_TRUE(stream_gate_open(ready_link(247)));
    TEST_ASSERT_FALSE(stream_gate_open(ready_link(246)));
    TEST_ASSERT_FALSE(stream_gate_open(ready_link(185)));
    TEST_ASSERT_FALSE(stream_gate_open(ready_link(23)));
    TEST_ASSERT_FALSE(stream_gate_open(StreamGateInput{true, false, true, 255}));
    TEST_ASSERT_FALSE(stream_gate_open(StreamGateInput{true, true, false, 255}));
    TEST_ASSERT_FALSE(stream_gate_open(StreamGateInput{false, true, true, 255}));
}

void test_advertising_is_fast_for_thirty_seconds() {
    TEST_ASSERT_TRUE(advertising_speed(0) == AdvertisingSpeed::Fast);
    TEST_ASSERT_TRUE(advertising_speed(29999) == AdvertisingSpeed::Fast);
    TEST_ASSERT_TRUE(advertising_speed(30000) == AdvertisingSpeed::Slow);
}

void test_device_name_uses_last_two_mac_bytes() {
    const uint8_t mac[6] = {0x24, 0x6F, 0x28, 0xAB, 0x0C, 0x1E};
    TEST_ASSERT_EQUAL_STRING("Blindside-0C1E", device_name_from_mac(mac).text);
}

void test_info_json_matches_the_contract_example() {
    const char* expected =
        R"({"proto":1,"fw":"0.2.0","boot_id":"9f3a12c4","reset":"POWERON","mtu":255,)"
        R"("radars":[{"id":0,"fw":"V2.04.23101915","baud":256000},{"id":1,"fw":"V2.04.23101915","baud":256000}],)"
        R"("imus":[{"id":0,"who":104,"repeats":0},{"id":1,"who":112,"repeats":3}],"tx_power_dbm":9,)"
        R"("conns":[{"role":"watch","itvl_ms":45.0,"lat":0,"timeout_ms":4000,"sent":1234,"dropped":0},)"
        R"({"role":"phone","itvl_ms":75.0,"lat":0,"timeout_ms":4000,"sent":1200,"dropped":3}],)"
        R"("bonds":2,"uptime_s":42})";
    char json[kInfoJsonBufferSize];
    size_t length = format_info_json(example_info(), json, sizeof(json));
    TEST_ASSERT_EQUAL_STRING(expected, json);
    TEST_ASSERT_EQUAL_UINT(460, length);
}

void test_info_json_worst_case_with_two_links_fits_in_the_512_byte_attribute() {
    char json[kInfoJsonBufferSize];
    size_t length = format_info_json(longest_info(), json, sizeof(json));
    TEST_ASSERT_EQUAL_UINT(511, length);
    TEST_ASSERT_TRUE(length <= kInfoJsonMaxBytes);
    TEST_ASSERT_NOT_NULL(strstr(json, R"({"id":1,"fw":"VFF.FF.FFFFFFFF","baud":460800})"));
    TEST_ASSERT_NOT_NULL(strstr(json, R"({"id":1,"who":255,"repeats":4294967295})"));
    TEST_ASSERT_NOT_NULL(strstr(
        json, R"({"role":"phone","itvl_ms":4000.0,"lat":499,"timeout_ms":32000,"sent":999999,"dropped":999999})"));
}

void test_info_json_rounds_the_interval_to_one_decimal() {
    BeltInfo info = example_info();
    info.conns[0].params = LinkParams{39, 0, 400};
    char json[kInfoJsonBufferSize];
    format_info_json(info, json, sizeof(json));
    TEST_ASSERT_NOT_NULL(strstr(json, R"("itvl_ms":48.8,)"));
    TEST_ASSERT_NOT_NULL(strstr(json, R"("timeout_ms":4000,)"));
}

void test_info_json_lists_one_link_or_none() {
    BeltInfo info = example_info();
    info.conn_count = 1;
    info.bonds = 1;
    char json[kInfoJsonBufferSize];
    format_info_json(info, json, sizeof(json));
    TEST_ASSERT_NOT_NULL(strstr(
        json, R"("conns":[{"role":"watch","itvl_ms":45.0,"lat":0,"timeout_ms":4000,"sent":1234,"dropped":0}],"bonds":1,)"));
    info.conn_count = 0;
    info.bonds = 0;
    format_info_json(info, json, sizeof(json));
    TEST_ASSERT_NOT_NULL(strstr(json, R"("conns":[],"bonds":0,)"));
}

void test_info_counters_wrap_at_one_million() {
    BeltInfo info = example_info();
    info.conns[0].sent = 1000001;
    info.conns[0].dropped = 4294967295u;
    char json[kInfoJsonBufferSize];
    format_info_json(info, json, sizeof(json));
    TEST_ASSERT_NOT_NULL(strstr(json, R"("sent":1,"dropped":967295})"));
}

void test_info_json_has_a_single_mtu_key_for_the_watch_reader() {
    char json[kInfoJsonBufferSize];
    format_info_json(longest_info(), json, sizeof(json));
    TEST_ASSERT_EQUAL_UINT(1, occurrences(json, R"("mtu")"));
}

void test_info_json_reports_a_missing_radar() {
    BeltInfo info = example_info();
    info.radars[1] = RadarInfo{1, FirmwareText{}, 0};
    char json[kInfoJsonBufferSize];
    format_info_json(info, json, sizeof(json));
    TEST_ASSERT_NOT_NULL(strstr(json, R"({"id":1,"fw":"","baud":0})"));
}

void test_info_json_reports_zero_when_it_does_not_fit() {
    char small[64];
    TEST_ASSERT_EQUAL_UINT(0, format_info_json(example_info(), small, sizeof(small)));
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_valid_control_writes_are_parsed);
    RUN_TEST(test_malformed_control_writes_are_invalid);
    RUN_TEST(test_open_pairing_and_set_role_writes_are_parsed);
    RUN_TEST(test_malformed_open_pairing_and_set_role_writes_are_invalid);
    RUN_TEST(test_an_untrusted_link_changes_nothing);
    RUN_TEST(test_a_trusted_link_gets_the_action_of_its_command);
    RUN_TEST(test_identify_is_ignored_while_any_bonded_device_has_a_session);
    RUN_TEST(test_identify_is_refused_during_a_session);
    RUN_TEST(test_stream_gate_needs_subscribed_trusted_peer_and_mtu_247);
    RUN_TEST(test_advertising_is_fast_for_thirty_seconds);
    RUN_TEST(test_device_name_uses_last_two_mac_bytes);
    RUN_TEST(test_info_json_matches_the_contract_example);
    RUN_TEST(test_info_json_worst_case_with_two_links_fits_in_the_512_byte_attribute);
    RUN_TEST(test_info_json_rounds_the_interval_to_one_decimal);
    RUN_TEST(test_info_json_lists_one_link_or_none);
    RUN_TEST(test_info_counters_wrap_at_one_million);
    RUN_TEST(test_info_json_has_a_single_mtu_key_for_the_watch_reader);
    RUN_TEST(test_info_json_reports_a_missing_radar);
    RUN_TEST(test_info_json_reports_zero_when_it_does_not_fit);
    return UNITY_END();
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `python tools/run_native_tests.py test_belt_rules_link`
Expected: `unknown type name 'ConnInfo'`, `build failed`, `0 suites passed, 1 failed`.

- [ ] **Step 3: Write the new `info` formatter**

`firmware/lib/belt_rules/info_json.h`:

```cpp
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "bundler.h"
#include "ld2450_commands.h"
#include "link_roles.h"

constexpr size_t kInfoJsonMaxBytes = 512;
constexpr size_t kInfoJsonBufferSize = kInfoJsonMaxBytes + 1;
// Six digits per counter is what keeps two links inside the 512 B attribute in the worst case.
constexpr uint32_t kInfoCounterModulus = 1000000;

struct RadarInfo {
    uint8_t id;
    FirmwareText firmware;
    uint32_t baud;
};

struct ImuInfo {
    uint8_t id;
    uint8_t who_am_i;
    uint32_t repeats;
};

struct ConnInfo {
    LinkRole role;
    LinkParams params;
    uint32_t sent;
    uint32_t dropped;
};

struct BeltInfo {
    const char* firmware_version;
    uint32_t boot_id;
    const char* reset_reason;
    uint16_t mtu;
    RadarInfo radars[kRadarCount];
    ImuInfo imus[kImuCount];
    int8_t tx_power_dbm;
    ConnInfo conns[kMaxLinks];
    size_t conn_count;
    uint8_t bonds;
    uint32_t uptime_s;
};

size_t format_info_json(const BeltInfo& info, char* out, size_t out_size);
```

`firmware/lib/belt_rules/info_json.cpp`:

```cpp
#include "info_json.h"

#include <stdio.h>

namespace {

constexpr size_t kObjectTextSize = 128;
constexpr size_t kConnsTextSize = kMaxLinks * kObjectTextSize;
constexpr uint32_t kIntervalHundredthsPerUnit = 125;
constexpr uint32_t kSupervisionMsPerUnit = 10;

struct ObjectText {
    char text[kObjectTextSize];
};

struct ConnsText {
    char text[kConnsTextSize];
};

struct IntervalText {
    unsigned long whole_ms;
    unsigned long tenth_ms;
};

ObjectText radar_object(const RadarInfo& radar) {
    ObjectText object{};
    snprintf(object.text, sizeof(object.text), "{\"id\":%u,\"fw\":\"%s\",\"baud\":%lu}",
             static_cast<unsigned>(radar.id), radar.firmware.text, static_cast<unsigned long>(radar.baud));
    return object;
}

ObjectText imu_object(const ImuInfo& imu) {
    ObjectText object{};
    snprintf(object.text, sizeof(object.text), "{\"id\":%u,\"who\":%u,\"repeats\":%lu}",
             static_cast<unsigned>(imu.id), static_cast<unsigned>(imu.who_am_i),
             static_cast<unsigned long>(imu.repeats));
    return object;
}

// The interval is a multiple of 1.25 ms; one decimal, rounded half up, is what the contract shows ("45.0").
IntervalText interval_text(uint16_t interval_units) {
    uint32_t tenths = (interval_units * kIntervalHundredthsPerUnit + 5) / 10;
    return IntervalText{tenths / 10, tenths % 10};
}

ObjectText conn_object(const ConnInfo& conn) {
    IntervalText interval = interval_text(conn.params.interval_units);
    ObjectText object{};
    snprintf(object.text, sizeof(object.text),
             "{\"role\":\"%s\",\"itvl_ms\":%lu.%lu,\"lat\":%u,\"timeout_ms\":%lu,\"sent\":%lu,\"dropped\":%lu}",
             role_name(conn.role), interval.whole_ms, interval.tenth_ms, static_cast<unsigned>(conn.params.latency),
             static_cast<unsigned long>(conn.params.supervision_units * kSupervisionMsPerUnit),
             static_cast<unsigned long>(conn.sent % kInfoCounterModulus),
             static_cast<unsigned long>(conn.dropped % kInfoCounterModulus));
    return object;
}

size_t appended(char* out, size_t out_size, size_t used, const char* separator, const char* text) {
    int written = snprintf(out + used, out_size - used, "%s%s", separator, text);
    size_t next = used + (written > 0 ? static_cast<size_t>(written) : 0);
    return next < out_size ? next : out_size - 1;
}

ConnsText conns_array(const BeltInfo& info) {
    ConnsText conns{};
    size_t used = 0;
    size_t count = info.conn_count < kMaxLinks ? info.conn_count : kMaxLinks;
    for (size_t i = 0; i < count; ++i) {
        used = appended(conns.text, sizeof(conns.text), used, i == 0 ? "" : ",", conn_object(info.conns[i]).text);
    }
    return conns;
}

}  // namespace

size_t format_info_json(const BeltInfo& info, char* out, size_t out_size) {
    ObjectText radar_a = radar_object(info.radars[0]);
    ObjectText radar_b = radar_object(info.radars[1]);
    ObjectText imu_a = imu_object(info.imus[0]);
    ObjectText imu_b = imu_object(info.imus[1]);
    ConnsText conns = conns_array(info);
    int written = snprintf(out, out_size,
                           "{\"proto\":%u,\"fw\":\"%s\",\"boot_id\":\"%08lx\",\"reset\":\"%s\",\"mtu\":%u,"
                           "\"radars\":[%s,%s],\"imus\":[%s,%s],\"tx_power_dbm\":%d,\"conns\":[%s],\"bonds\":%u,"
                           "\"uptime_s\":%lu}",
                           static_cast<unsigned>(kProtocolVersion), info.firmware_version,
                           static_cast<unsigned long>(info.boot_id), info.reset_reason,
                           static_cast<unsigned>(info.mtu), radar_a.text, radar_b.text, imu_a.text, imu_b.text,
                           static_cast<int>(info.tx_power_dbm), conns.text, static_cast<unsigned>(info.bonds),
                           static_cast<unsigned long>(info.uptime_s));
    bool fits = written > 0 && static_cast<size_t>(written) < out_size;
    return fits ? static_cast<size_t>(written) : 0;
}
```

- [ ] **Step 4: Run the test**

Run: `python tools/run_native_tests.py test_belt_rules_link` → `19 Tests 0 Failures`, `1 suites passed, 0 failed`.

- [ ] **Step 5: Fill `conns` and `bonds` in `main.cpp`**

In `firmware/src/main.cpp`, replace the head of `current_info`

```cpp
BeltInfo current_info(uint32_t now_ms, uint8_t reader_slot) {
    LinkSnapshot link = ble_link_snapshot(reader_slot);
    BeltInfo info{};
    info.firmware_version = config::kFirmwareVersion;
    info.boot_id = g_boot_id.load();
    info.reset_reason = reset_reason_name(esp_reset_reason());
    info.mtu = link.mtu;
```

with

```cpp
ConnInfo conn_info(const LinkSnapshot& link, const LinkDelivery& delivery) {
    LinkTally tally = tally_for_link(delivery, link.link_id);
    return ConnInfo{link.role, link.params, tally.sent, tally.dropped};
}

BeltInfo with_links(const BeltInfo& info) {
    BeltInfo next = info;
    SenderStats stats = stream_sender_stats();
    for (uint8_t slot = 0; slot < kMaxLinks; ++slot) {
        LinkSnapshot link = ble_link_snapshot(slot);
        if (link.connected) {
            next.conns[next.conn_count++] = conn_info(link, stats.links[slot]);
        }
    }
    next.bonds = pairing_bond_count();
    return next;
}

BeltInfo current_info(uint32_t now_ms, uint8_t reader_slot) {
    BeltInfo info{};
    info.firmware_version = config::kFirmwareVersion;
    info.boot_id = g_boot_id.load();
    info.reset_reason = reset_reason_name(esp_reset_reason());
    info.mtu = ble_link_snapshot(reader_slot).mtu;
```

and its tail

```cpp
    info.tx_power_dbm = ble_link_tx_power();
    info.conn = link.params;
    info.uptime_s = now_ms / 1000;
    return info;
}
```

with

```cpp
    info.tx_power_dbm = ble_link_tx_power();
    info.uptime_s = now_ms / 1000;
    return with_links(info);
}
```

`with_links` runs on the NimBLE host task like the rest of `current_info`: it reads atomics (`ble_link_snapshot`, `pairing_bond_count`) and the lock-protected `stream_sender_stats()`. Together with the `ConnsText` buffer of `format_info_json` it adds about 0.6 KB of stack to that task; Task 7 prints the task's low-water mark (`hstk`) and Task 8 raises its stack to 8192 B.

- [ ] **Step 6: Build and run everything**

Run: `python -m platformio run -e esp32dev` → `[SUCCESS]`.
Run: `python tools/run_native_tests.py` → `17 suites passed, 0 failed`.

- [ ] **Step 7: Commit**

```bash
git add lib/belt_rules/info_json.h lib/belt_rules/info_json.cpp test/test_belt_rules_link/test_main.cpp src/main.cpp
git commit -m "feat: info lista las conexiones y los bonds dentro de los 512 B"
```

---

### Task 7: `diag` line per link

**Files:**
- Create: `firmware/lib/belt_rules/diag_format.h`, `firmware/lib/belt_rules/diag_format.cpp`
- Test: `firmware/test/test_belt_rules_diag/test_main.cpp`
- Modify: `firmware/src/diagnostics.h`, `firmware/src/diagnostics.cpp`, `firmware/src/main.cpp` (`diagnostics_print` gets the pairing window; boot line with the controller's buffer total)

**Interfaces:**
- Consumes: `LinkRole`, `role_name` (Task 1); `ble_link_snapshot(uint8_t)`, `ble_link_last_disconnect_reason()`, `nimble_free_acl_buffers()`, `nimble_host_stack_free()` (Task 2); `SenderStats::links`, `tally_for_link` (Task 3); `pairing_bond_count()` (Task 4); `PairingWindow`, `window_opened`, `window_closed`, `kPairingWindowMs` (MVP `pairing_rules.h`).
- Produces: `struct LinkDiag { bool connected; LinkRole role; bool trusted; bool subscribed; uint16_t mtu; LinkParams params; uint32_t sent; uint32_t dropped; };` `struct LinkDiagText { char text[kLinkDiagTextSize]; };` `LinkDiagText format_link_diag(uint8_t slot, const LinkDiag& link);` `struct DiagTail { uint8_t bonds; PairingWindow window; uint32_t now_ms; int disconnect_reason; uint16_t free_acl_buffers; uint32_t host_stack_free; };` `struct DiagTailText { char text[kDiagTailTextSize]; };` `DiagTailText format_diag_tail(const DiagTail& tail);` `DiagnosticsMemory diagnostics_print(const DiagnosticsMemory& memory, uint32_t now_ms, const PairingWindow& window);`
- Serial format (read by `tools/bench_e2e.py`, spec §7 and the checklist): `… link0[role=watch trusted=1 sub=1 mtu=255 itvl=36 lat=0 sup=400 sent=1234 dropped=0] link1[-] bonds=1 pair=0 disc=531 acl=10 hstk=2900 tx[fail=0 dropped=0 skipped=0]`, plus once at boot `ble: controller acl buffers=N`. `pair` is the pairing window's seconds left (`0` closed, `open` while there is no bond), `acl` the free controller buffers, `hstk` the NimBLE host task's lowest free stack so far in bytes.

- [ ] **Step 1: Write the failing test**

`firmware/test/test_belt_rules_diag/test_main.cpp`:

```cpp
#include <string.h>
#include <unity.h>

#include "../test_entry.h"
#include "diag_format.h"

namespace {

LinkDiag watch_link() {
    return LinkDiag{true, LinkRole::Watch, true, true, 255, LinkParams{36, 0, 400}, 1234, 0};
}

DiagTail tail_with(uint8_t bonds, const PairingWindow& window, uint32_t now_ms) {
    return DiagTail{bonds, window, now_ms, 19, 9, 2100};
}

DiagTailText one_bond_tail(const PairingWindow& window, uint32_t now_ms) {
    return format_diag_tail(tail_with(1, window, now_ms));
}

}  // namespace

void setUp() {}
void tearDown() {}

void test_a_streaming_watch_shows_role_trust_subscription_and_counters() {
    TEST_ASSERT_EQUAL_STRING(" link0[role=watch trusted=1 sub=1 mtu=255 itvl=36 lat=0 sup=400 sent=1234 dropped=0]",
                             format_link_diag(0, watch_link()).text);
}

void test_a_phone_still_pairing_shows_untrusted_and_unsubscribed() {
    LinkDiag phone{true, LinkRole::Phone, false, false, 23, LinkParams{48, 0, 400}, 0, 0};
    TEST_ASSERT_EQUAL_STRING(" link1[role=phone trusted=0 sub=0 mtu=23 itvl=48 lat=0 sup=400 sent=0 dropped=0]",
                             format_link_diag(1, phone).text);
}

void test_a_free_slot_is_a_dash() {
    LinkDiag idle = watch_link();
    idle.connected = false;
    TEST_ASSERT_EQUAL_STRING(" link1[-]", format_link_diag(1, idle).text);
}

void test_the_longest_link_entry_fits_its_buffer() {
    LinkDiag longest{true, LinkRole::Phone, true, true, 517, LinkParams{3200, 499, 3200}, 4294967295u, 4294967295u};
    LinkDiagText line = format_link_diag(1, longest);
    TEST_ASSERT_NOT_NULL(strstr(line.text, "sent=4294967295 dropped=4294967295]"));
    TEST_ASSERT_TRUE(strlen(line.text) < kLinkDiagTextSize);
}

void test_the_tail_shows_bonds_window_disconnect_buffers_and_host_stack() {
    TEST_ASSERT_EQUAL_STRING(" bonds=1 pair=0 disc=19 acl=9 hstk=2100", one_bond_tail(window_closed(), 5000).text);
}

void test_an_open_window_counts_down_its_seconds() {
    PairingWindow window = window_opened(1000);
    TEST_ASSERT_EQUAL_STRING(" bonds=1 pair=60 disc=19 acl=9 hstk=2100", one_bond_tail(window, 1000).text);
    TEST_ASSERT_EQUAL_STRING(" bonds=1 pair=30 disc=19 acl=9 hstk=2100", one_bond_tail(window, 31500).text);
    TEST_ASSERT_EQUAL_STRING(" bonds=1 pair=1 disc=19 acl=9 hstk=2100", one_bond_tail(window, 60999).text);
    TEST_ASSERT_EQUAL_STRING(" bonds=1 pair=0 disc=19 acl=9 hstk=2100", one_bond_tail(window, 61000).text);
}

void test_without_bonds_the_window_is_open_with_no_end() {
    TEST_ASSERT_EQUAL_STRING(" bonds=0 pair=open disc=19 acl=9 hstk=2100",
                             format_diag_tail(tail_with(0, window_opened(0), 900000)).text);
}

void test_the_longest_tail_fits_its_buffer() {
    DiagTail longest{255, window_opened(0), 1, -2147483647 - 1, 65535, 4294967295u};
    TEST_ASSERT_TRUE(strlen(format_diag_tail(longest).text) < kDiagTailTextSize - 1);
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_a_streaming_watch_shows_role_trust_subscription_and_counters);
    RUN_TEST(test_a_phone_still_pairing_shows_untrusted_and_unsubscribed);
    RUN_TEST(test_a_free_slot_is_a_dash);
    RUN_TEST(test_the_longest_link_entry_fits_its_buffer);
    RUN_TEST(test_the_tail_shows_bonds_window_disconnect_buffers_and_host_stack);
    RUN_TEST(test_an_open_window_counts_down_its_seconds);
    RUN_TEST(test_without_bonds_the_window_is_open_with_no_end);
    RUN_TEST(test_the_longest_tail_fits_its_buffer);
    return UNITY_END();
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `python tools/run_native_tests.py test_belt_rules_diag`
Expected: `'diag_format.h' file not found`, `0 suites passed, 1 failed`.

- [ ] **Step 3: Write the formatter**

`firmware/lib/belt_rules/diag_format.h`:

```cpp
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "bundler.h"
#include "link_roles.h"
#include "pairing_rules.h"

constexpr size_t kLinkDiagTextSize = 128;
constexpr size_t kDiagTailTextSize = 96;

struct LinkDiag {
    bool connected;
    LinkRole role;
    bool trusted;
    bool subscribed;
    uint16_t mtu;
    LinkParams params;
    uint32_t sent;
    uint32_t dropped;
};

struct LinkDiagText {
    char text[kLinkDiagTextSize];
};

struct DiagTail {
    uint8_t bonds;
    PairingWindow window;
    uint32_t now_ms;
    int disconnect_reason;
    uint16_t free_acl_buffers;
    uint32_t host_stack_free;
};

struct DiagTailText {
    char text[kDiagTailTextSize];
};

LinkDiagText format_link_diag(uint8_t slot, const LinkDiag& link);
DiagTailText format_diag_tail(const DiagTail& tail);
```

`firmware/lib/belt_rules/diag_format.cpp`:

```cpp
#include "diag_format.h"

#include <stdio.h>

namespace {

constexpr size_t kPairTextSize = 8;
constexpr uint32_t kMsPerSecond = 1000;

struct PairText {
    char text[kPairTextSize];
};

LinkDiagText idle_link(uint8_t slot) {
    LinkDiagText line{};
    snprintf(line.text, sizeof(line.text), " link%u[-]", static_cast<unsigned>(slot));
    return line;
}

LinkDiagText connected_link(uint8_t slot, const LinkDiag& link) {
    LinkDiagText line{};
    snprintf(line.text, sizeof(line.text),
             " link%u[role=%s trusted=%d sub=%d mtu=%u itvl=%u lat=%u sup=%u sent=%lu dropped=%lu]",
             static_cast<unsigned>(slot), role_name(link.role), link.trusted ? 1 : 0, link.subscribed ? 1 : 0,
             static_cast<unsigned>(link.mtu), static_cast<unsigned>(link.params.interval_units),
             static_cast<unsigned>(link.params.latency), static_cast<unsigned>(link.params.supervision_units),
             static_cast<unsigned long>(link.sent), static_cast<unsigned long>(link.dropped));
    return line;
}

uint32_t window_seconds_left(const PairingWindow& window, uint32_t now_ms) {
    uint32_t elapsed_ms = now_ms - window.opened_ms;
    if (!window.open || elapsed_ms >= kPairingWindowMs) {
        return 0;
    }
    return (kPairingWindowMs - elapsed_ms + kMsPerSecond - 1) / kMsPerSecond;
}

// Without a bond the window stays open until the first one (pairing_rules), so it has no countdown.
PairText pairing_text(const DiagTail& tail) {
    PairText pair{};
    if (tail.window.open && tail.bonds == 0) {
        snprintf(pair.text, sizeof(pair.text), "open");
        return pair;
    }
    snprintf(pair.text, sizeof(pair.text), "%lu",
             static_cast<unsigned long>(window_seconds_left(tail.window, tail.now_ms)));
    return pair;
}

}  // namespace

LinkDiagText format_link_diag(uint8_t slot, const LinkDiag& link) {
    return link.connected ? connected_link(slot, link) : idle_link(slot);
}

DiagTailText format_diag_tail(const DiagTail& tail) {
    DiagTailText line{};
    snprintf(line.text, sizeof(line.text), " bonds=%u pair=%s disc=%d acl=%u hstk=%lu",
             static_cast<unsigned>(tail.bonds), pairing_text(tail).text, tail.disconnect_reason,
             static_cast<unsigned>(tail.free_acl_buffers), static_cast<unsigned long>(tail.host_stack_free));
    return line;
}
```

- [ ] **Step 4: Run the test**

Run: `python tools/run_native_tests.py test_belt_rules_diag` → `8 Tests 0 Failures`, `1 suites passed, 0 failed`.

- [ ] **Step 5: Print every link and the tail in `diagnostics.cpp`**

In `firmware/src/diagnostics.h` add `#include "pairing_rules.h"` right after `#include "imu_task.h"` and replace the last declaration with:

```cpp
DiagnosticsMemory diagnostics_print(const DiagnosticsMemory& memory, uint32_t now_ms, const PairingWindow& window);
```

In `firmware/src/diagnostics.cpp`:
- add `#include "diag_format.h"`, `#include "nimble_internals.h"` and `#include "pairing.h"` to the includes (keep them sorted: `ble_link.h`, `diag_format.h`, `nimble_internals.h`, `pairing.h`, `radar_port.h`, `stream_sender.h`);
- replace the whole `print_link()` function with:

```cpp
LinkDiag link_diag(const LinkSnapshot& link, const LinkDelivery& delivery) {
    LinkTally tally = tally_for_link(delivery, link.link_id);
    return LinkDiag{link.connected, link.role, link.trusted, link.subscribed,
                    link.mtu,       link.params, tally.sent, tally.dropped};
}

DiagTail diag_tail(const PairingWindow& window, uint32_t now_ms) {
    return DiagTail{pairing_bond_count(), window, now_ms, ble_link_last_disconnect_reason(), nimble_free_acl_buffers(),
                    nimble_host_stack_free()};
}

void print_links(const PairingWindow& window, uint32_t now_ms) {
    SenderStats stats = stream_sender_stats();
    for (uint8_t slot = 0; slot < kMaxLinks; ++slot) {
        Serial.print(format_link_diag(slot, link_diag(ble_link_snapshot(slot), stats.links[slot])).text);
    }
    Serial.print(format_diag_tail(diag_tail(window, now_ms)).text);
}
```

- give `diagnostics_print` the signature declared above (`DiagnosticsMemory diagnostics_print(const DiagnosticsMemory& memory, uint32_t now_ms, const PairingWindow& window) {`) and replace `print_link();` with `print_links(window, now_ms);`.

In `firmware/src/main.cpp`:
- add `#include "nimble_internals.h"` right after `#include "led_pattern.h"`;
- add, right above `RadarInfo radar_info(uint8_t radar_id) {`:

```cpp
// Before any link exists every controller buffer is free, so this is the controller's total (the cap's headroom).
void print_ble_buffers() {
    Serial.printf("ble: controller acl buffers=%u\n", static_cast<unsigned>(nimble_free_acl_buffers()));
}
```

- in `setup()` add `print_ble_buffers();` on the line right after `print_banner();`;
- in `print_diagnostics_if_due` replace `g_diagnostics = diagnostics_print(g_diagnostics, now_ms);` with `g_diagnostics = diagnostics_print(g_diagnostics, now_ms, g_pairing.window);`.

- [ ] **Step 6: Build and run everything**

Run: `python -m platformio run -e esp32dev` → `[SUCCESS]`.
Run: `python tools/run_native_tests.py` → `18 suites passed, 0 failed`.

- [ ] **Step 7: Commit**

```bash
git add lib/belt_rules/diag_format.h lib/belt_rules/diag_format.cpp test/test_belt_rules_diag/test_main.cpp src/diagnostics.h src/diagnostics.cpp src/main.cpp
git commit -m "feat: la línea diag muestra cada conexión, la ventana de emparejamiento y la salud de los búferes BLE"
```

---

### Task 8: Enable two connections (contract) — firmware 0.2.0

**Files:**
- Modify: `firmware/src/ble_link.h`, `firmware/src/ble_link.cpp` (delete the single-link wrappers)
- Modify: `firmware/platformio.ini` (two connections, three bond records, more buffers, host stack 8192)
- Modify: `firmware/include/blindside_config.h` (version)

**Interfaces:**
- Consumes: everything above.
- Produces: the final firmware. No single-link call remains; `ble_link_capacity()` returns 2.

- [ ] **Step 1: Prove no caller uses the wrappers**

Run: `grep -rnE "ble_link_(snapshot|take_auth_event|peer_security|take_control|disconnect)\(\)|ble_link_set_trusted\((true|false)\)|ble_link_notify\((packet|bytes)" src`
Expected: matches only in `src/ble_link.h` and `src/ble_link.cpp` (the wrapper declarations and definitions themselves). If any other file matches, move that call to the slot API before going on.

- [ ] **Step 2: Delete the wrappers**

In `firmware/src/ble_link.h` delete the comment line `// Single-link calls on slot 0, kept only until every caller moves to slots.` and the seven declarations below it. In `firmware/src/ble_link.cpp` delete the seven wrapper definitions at the end of the file (from `LinkSnapshot ble_link_snapshot() {` to the final `ble_link_disconnect(0);` `}`). The file ends with `ble_link_disconnect_all()`.

- [ ] **Step 3: Two connections, three bond records, more buffers, a bigger host stack**

In `firmware/platformio.ini`, `[env:esp32dev]` `build_flags`, replace

```ini
    -D CONFIG_BT_NIMBLE_MAX_CONNECTIONS=1
    -D CONFIG_BT_NIMBLE_MSYS1_BLOCK_COUNT=24
```

with

```ini
    -D CONFIG_BT_NIMBLE_MAX_CONNECTIONS=2
    ; Two kept bonds plus the one a new device stores before the belt decides which bond it replaces.
    -D CONFIG_BT_NIMBLE_MAX_BONDS=3
    ; Each link queues its own notifications, so two links need more buffers than one.
    -D CONFIG_BT_NIMBLE_MSYS1_BLOCK_COUNT=32
```

and replace

```ini
    ; `info` is formatted inside the NimBLE host task at each read, so it gets more than the default 4096 B.
    -D CONFIG_BT_NIMBLE_HOST_TASK_STACK_SIZE=6144
```

with

```ini
    ; `info` (with both links' `conns`) is built inside the NimBLE host task; `diag` shows its stack left (`hstk`).
    -D CONFIG_BT_NIMBLE_HOST_TASK_STACK_SIZE=8192
```

In `firmware/include/blindside_config.h` replace `constexpr char kFirmwareVersion[] = "0.1.0";` with `constexpr char kFirmwareVersion[] = "0.2.0";`.

- [ ] **Step 4: Full verification**

Run: `python tools/run_native_tests.py` → `18 suites passed, 0 failed`.
Run: `python -m platformio run -e esp32dev` → `[SUCCESS]`, RAM ≈ 16.1 % (≈ 52.9 kB; the host stack is allocated at run time), no warning from `src/` or `lib/`.
Run: `python -m platformio test -e esp32dev -f test_link_fanout -f test_belt_rules_roles -f test_belt_rules_bonds -f test_belt_rules_diag -f test_belt_rules_link -f test_belt_rules_pairing -f test_cut_schedule --without-uploading --without-testing` → each suite `[SKIPPED]` (built for the ESP32, not uploaded), none `[ERRORED]`.
Run: `wc -l src/*.cpp lib/belt_rules/*.cpp` → every file ≤ 400 lines (`ble_link.cpp` ≈ 394).

- [ ] **Step 5: Commit**

```bash
git add src/ble_link.h src/ble_link.cpp platformio.ini include/blindside_config.h
git commit -m "feat: firmware 0.2.0 con dos conexiones simultáneas (reloj y celular)"
```

---

### Task 9: Protocol, contracts and hardware checklist

**Files:**
- Modify (full rewrite): `protocol/PROTOCOL.md`
- Modify: `docs/superpowers/plans/2026-09-30-blindside-mvp-00-contracts.md` (`control` table and `info` section)
- Modify: `firmware/HARDWARE_CHECKLIST.md`

**Interfaces:**
- Consumes: the behaviour of Tasks 1-8 (every number below matches a test or a constant).
- Produces: the normative wire description the phone-app and shared-module plans read.

- [ ] **Step 1: Replace `protocol/PROTOCOL.md`**

````markdown
# Blindside BLE protocol, version 1

Normative description of the link between the belt (ESP32, `firmware/`) and its clients: the watch
(`watch/wear-app`) and, from firmware 0.2.0, the phone (`watch/phone-app`). Sources: spec §4.2-§4.3
(`docs/superpowers/specs/2026-09-30-blindside-v1-design.md`), spec §2 of phase 2
(`docs/superpowers/specs/2026-10-01-blindside-android-companion-design.md`) and the shared contracts
(`docs/superpowers/plans/2026-09-30-blindside-mvp-00-contracts.md`). If this file and the contracts
disagree, the contracts win and this file is fixed.

Byte-exact examples live in `protocol/vectors/vectors.json` and `firmware/test/vectors.h`, both
generated by `python protocol/tools/make_vectors.py`. Never edit them by hand.

## 1. GATT

| Item | UUID | Properties | Security |
|---|---|---|---|
| Service | `569f3867-024f-4498-a979-90a762ad3593` | primary | — |
| `stream` | `37869398-ecc2-4915-90a1-13d39d708ad5` | notify | the CCCD can only be written on an encrypted link |
| `info` | `278b9369-d8ac-4eda-868b-7bfd0dea5dc6` | read | READ_ENC (encrypted link) |
| `control` | `725c9a6e-0c7b-45d2-bef6-48c03be7c092` | write (with response) | WRITE_AUTHEN (encrypted, MITM-authenticated link); WRITE_ENC under the S12 plan B (§3) |

- The table is frozen: same service, same three characteristics, same order and handles in every
  build. The S12 plan B only relaxes the `control` permission bits.
- Up to **two connections** at once (firmware 0.2.0; 0.1.0 accepted one). With fewer than two links
  up the belt advertises for another one when it may be needed (§2); with two links up it does not
  advertise.
- Each link has a **role**: watch (default) or phone, set with `06 SET_ROLE` (§6). A link that never
  writes `06` is a watch, so the MVP watch app works unchanged.
- The belt proposes ATT MTU 255; Android 14+ requests 517, so the result is 255.
  Payload limit per notification: `min(MTU − 3, 244)` bytes.
- After connecting the belt asks for interval 30-50 ms, latency 0, supervision timeout 4 s
  (`updateConnParams(h, 24, 40, 0, 400)`), data length 251, and starts security. When a link
  writes `06 01` the belt asks that link for 60-100 ms (`updateConnParams(h, 48, 80, 0, 400)`);
  `06 00` asks for 30-50 ms again. If a phone-role link then reports other parameters (an interval
  outside 60-100 ms, or latency > 0), the belt asks for 60-100 ms once more; it never re-asks a watch.
- The belt notifies `stream` on a link only when all of these hold for that link: it is encrypted
  with a bonded (trusted) peer, notifications are enabled in its CCCD, and its ATT MTU is ≥ 247
  (payload limit 244). Below MTU 247 nothing is notified on that link. The belt checks this again
  at each notification, against the very connection the packet was planned for, so a peer that takes
  a freed connection slot never receives a packet meant for the previous one.

## 2. Advertising and connection filtering

- Advertising data: flags + the 128-bit service UUID (clients scan with a `ScanFilter` on it).
- Scan response: complete local name `Blindside-XXXX`, the last two bytes of the ESP32 Bluetooth MAC
  in upper-case hex.
- Interval 20-50 ms during the first 30 s after boot or after a disconnection, 100-200 ms afterwards.
  The same rule applies while one link is up and the belt advertises for a second one.
- With one link up the belt advertises only while a bonded device is not connected or the pairing
  window is open (`config::kSecondLinkAdvertisingOnDemand = true`, the default; `false` advertises
  whenever a connection slot is free). With a single bond and the watch connected it does not
  advertise, exactly like 0.1.0. With two links up it never advertises.
- Every bonded identity address (up to two, §3) is kept in the controller filter-accept list. The
  "connect from the list only" policy is enabled only when `config::kConnectWhitelistOnly` is true
  (spike S10).
- Whatever the filter does, the host enforces the bonds on each link:
  - outside the pairing window, a peer whose identity address is not bonded is disconnected as soon
    as the firmware sees the connection (no grace period);
  - a bonded peer gets 5 s to encrypt; a peer connected while the window is open gets 60 s to bond;
  - only the window's first successful pairing is kept: the window closes on it, and any other peer
    that was waiting to pair loses its allowance and is dropped 5 s after it connected.
- Known risk (phase 2): the filter-accept list does not stop a rival while
  `config::kConnectWhitelistOnly` is false (S10), so whenever the belt advertises with one link up a
  rival can connect to the free slot. Outside the pairing window it is dropped as soon as the
  firmware sees it (< 0.5 s), never receives `stream` (§1), and the watch's stream is not delayed by
  it. During an open window it can hold the free slot until the window's first bond (60 s) and keep
  the phone from pairing. Advertising on demand (above) limits this to the times a second device is
  expected.

## 3. Pairing

- LE Secure Connections and bonding; the belt distributes and requests the encryption key and the
  identity key (IRK).
  - Default (S12 passes): MITM with a passkey, IO capability DISPLAY_ONLY.
  - S12 plan B (`config::kRequireMitm = false`): LE SC Just Works, accepted only inside the pairing
    window; `control` becomes WRITE_ENC. Known weakness: whoever connects during the window can bond.
- Passkey: 6 random digits created on first boot, stored in NVS (`blindside/passkey`), printed on
  the serial port at every boot and by the serial command `key`. Only the serial command `key new`
  replaces it (it also changes after a full flash or NVS erase). Never in source code or build flags.
- Bonds: up to **two** are kept (typically the watch and the phone). Each bond has a role, learned
  when that device writes `06` (§6) on a trusted link and kept in NVS (`blindside/bond_roles`); a bond
  without a record is a watch. When a new device bonds:
  - with fewer than two bonds, it is added;
  - with two bonds, it replaces the newest **phone** bond whose device is not connected; if there is
    none and both bonds are watches, the newest watch bond whose device is not connected;
  - otherwise the new bond is refused: both bonded devices are connected (in practice the belt has no
    free slot for a third connection then), or the only idle bond is the only watch bond (the
    newcomer's role is unknown until it writes `06`, so the watch is never traded for it).
  - At boot, if more than two bonds are stored, the two oldest are kept and the rest are erased.
  - A lost or stolen device stays bonded until the BOOT ≥ 10 s reset below erases every bond; the
    remaining devices are then paired again.
- Pairing window (LED blinking, see the light rule below): lasts 60 s and closes on the first
  successful bond. It opens:
  - at boot when there is no bond, and then stays open until the first bond;
  - with the BOOT gesture below;
  - with `05 OPEN_PAIRING_WINDOW` written by a **trusted** link (§6), so the paired watch can let
    the phone pair without touching the belt.
  Opening the window never disconnects anyone.
- BOOT gestures count only during the first 60 s after power-on and act **on release**:
  - held 3-10 s: opens the pairing window;
  - held 10 s or more: erases every bond and nothing else (the passkey stays), disconnects every
    link, and opens the window.
- Light rule: while any bonded device has `SESSION_ACTIVE = 1` (§6), an open pairing window does not
  light the LED.

## 4. `stream` packets

All integers are little-endian.

### 4.1 Header (8 B)

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `version` = 1 |
| 1 | 1 | `flags`: bit0 radar A alive, bit1 radar B alive, bit2 IMU A ok, bit3 IMU B ok, bit4 data dropped since the previous cut |
| 2 | 2 | `seq`, +1 per packet, wraps at 65536 |
| 4 | 4 | `t_ms`, ESP32 `millis()` at the cut |

### 4.2 Sections (`u8 type | u8 len | payload[len]`)

RADAR, type 0x01, len 29 (31 B with the TLV header):

| Offset in payload | Size | Field |
|---|---|---|
| 0 | 1 | `radar_id` (0 = A, left box; 1 = B, right box) |
| 1 | 4 | `t_ms`, ESP32 `millis()` taken by the radar's receive task when the bytes holding the frame header `AA FF 03 00` arrive |
| 5 | 24 | 3 targets × (`u16 x`, `u16 y`, `u16 speed`, `u16 resolution`) copied raw from the LD2450; x (mm), y (mm) and speed (cm/s) are sign-magnitude: bit 15 = 1 means positive; an absent target is 8 zero bytes |

IMU, type 0x02, len 18 + 12n (20 + 12n B with the TLV header):

| Offset in payload | Size | Field |
|---|---|---|
| 0 | 1 | `imu_id` (0 = A, 1 = B) |
| 1 | 4 | `t_first_ms`, the time of the first sample |
| 5 | 1 | `n`, 1-18 |
| 6 | 12n | n × `i16` (ax, ay, az, gx, gy, gz) |
| 6 + 12n | 12 | `u32 sum_gx`, `u32 sum_gy`, `u32 sum_gz` |

- The IMU is read on a fixed 5 ms grid. Each 50 Hz sample is the mean of one block of exactly 4
  readings, one 20 ms slot `[s, s + 20)` with readings at s, s+5, s+10 and s+15. The sample's time is
  the slot centre, `s + 10`. Sample *i* of a section has time `t_first_ms + 20·i`.
- A failed read, a read skipped while the IMU is down, or a slot the firmware fell behind on repeats
  the previous reading (zeros before the first good one). The sums therefore advance by exactly 4
  readings per slot, and samples stay on the grid. Repeats are counted per IMU (`imus[i].repeats` in
  `info`, §5, and the serial diagnostics line).
- A section only holds samples on a contiguous 20 ms grid; a gap (samples lost before reaching the
  bundler) starts a new section. A run longer than 18 samples is split into consecutive sections.
- Each sample is the mean of 4 readings, rounded half away from zero. Gyro ±500 °/s
  (65.5 LSB per °/s), accel ±8 g (4096 LSB per g), axes in the chip frame.
- `sum_g*` are the running sums of the raw 200 Hz gyro readings since boot, through the last reading
  of the section's last sample. They wrap as u32; take differences and read them as int32.

STATUS, type 0x03, len 10, sent about once per second:
2 × (`u8 radar_id`, `u16 bad_frames` saturating at 0xFFFF, `u8 restarts` saturating at 0xFF,
`u8 baud_index`). `baud_index`: 1 = 9600, 2 = 19200, 3 = 38400, 4 = 57600, 5 = 115200, 6 = 230400,
7 = 256000, 8 = 460800, 0 = radar not detected (or not configured yet).

LINK, type 0x04, len 6 (8 B with the TLV header): `u16 interval` (× 1.25 ms), `u16 latency`
(connection events), `u16 supervision_timeout` (× 10 ms). Sent in the first cut after every
connection-parameter update reported by NimBLE (on any link) and once after a link enables
notifications. If a cut cannot carry it, it goes in the next one. With two links it carries the
parameters of the **first link in notify order** (§4.3: the watch when it is streaming); each
link's own parameters are in `info.conns` (§5).

Types 0x10-0x1F are reserved (0x10 = thermal camera). Unknown types are skipped using `len`.

### 4.3 Cuts, ordering, splitting and fan-out

- Every 100 ms the belt makes a cut: RADAR frames with `t_ms` < cut time − 5 ms, all completed IMU
  samples, STATUS when due and LINK when pending.
- Fill order inside a cut: IMU A, IMU B, STATUS, LINK, then the RADAR frames of both radars in
  ascending `t_ms`. So every RADAR frame in packet k has `t_ms` ≤ those in packet k+1, and a cut's
  IMU data never arrives after its radar frames.
- If a cut does not fit in one packet it is split into consecutive packets (consecutive `seq`, same
  header `t_ms` and `flags`), each filled in that order before the next one opens. A section is never
  split. With two links the payload limit is the smaller of the two links' limits (both are 244 B
  at MTU ≥ 247).
- A cut with nothing to send still produces one header-only packet (heartbeat). Readers must accept a
  packet with zero sections.
- Fan-out: every packet goes to every link that passes the §1 notify rule, with the same bytes and
  the same `seq`. Notify order: watch-role links before phone-role links, older connection first
  within a role. Each packet is notified to the first link in that order before any other link
  gets it. Every other link may hold at most 3 packets in the controller and the host queue at once;
  while it holds 3, its next packet is dropped for it (§4.4).
- A link that starts streaming (enables notifications) in the middle of a cut gets packets from the
  next cut on.

### 4.4 Loss and drops

- `seq` gaps mean packets that were never sent to that link (disconnection, or a dropped packet on
  a phone link, below). Lost packets = `((seq − seq_prev) mod 65536) − 1`, unsigned arithmetic.
- If `t_ms` goes backwards, or `boot_id` in `info` changes, the ESP32 rebooted: reset the reference.
- The first link in notify order (the watch) is never skipped: when its BLE notification queue is
  full the belt retries it until the next cut is due, and keeps the backlog. When that queue stays
  full, the belt keeps up to ~1 s of pending data; beyond that it drops the oldest RADAR frames
  first, then the oldest IMU samples, counts them, and sets `flags` bit4 on the next cut. Samples or
  frames lost inside the belt (an internal queue overflow) also set bit4.
- Any other link (the phone): when its queue is full, or it already holds 3 packets in the
  controller and the host queue (a slow or silent link), that packet is dropped for that link only,
  counted in its `dropped` (§5), and the belt moves on. It never delays the watch, including while a
  phone that vanished without a goodbye waits out its 4 s supervision timeout.
- When the first link in notify order vanishes without a goodbye (Bluetooth off, out of range), it
  stays first until its supervision timeout (4 s): the belt keeps retrying it, and the other link may
  receive nothing meanwhile. Then the other link becomes first and receives what the belt kept (up to
  about 1 s; anything older was dropped and counted, bit4).

### 4.5 Reader rules

- Skip unknown section types using `len`.
- If `len` exceeds the bytes left in the packet, drop the rest of the packet and count it as truncated.

## 5. `info` (UTF-8 JSON, ≤ 512 B, generated at each read)

```json
{"proto":1,"fw":"0.2.0","boot_id":"9f3a12c4","reset":"POWERON","mtu":255,"radars":[{"id":0,"fw":"V2.04.23101915","baud":256000},{"id":1,"fw":"V2.04.23101915","baud":256000}],"imus":[{"id":0,"who":104,"repeats":0},{"id":1,"who":112,"repeats":3}],"tx_power_dbm":9,"conns":[{"role":"watch","itvl_ms":45.0,"lat":0,"timeout_ms":4000,"sent":1234,"dropped":0},{"role":"phone","itvl_ms":75.0,"lat":0,"timeout_ms":4000,"sent":1200,"dropped":3}],"bonds":2,"uptime_s":42}
```

- `boot_id`: random 32-bit value per boot, 8 lower-case hex digits.
- `reset`: one of `UNKNOWN`, `POWERON`, `EXT`, `SW`, `PANIC`, `INT_WDT`, `TASK_WDT`, `WDT`,
  `DEEPSLEEP`, `BROWNOUT`, `SDIO`.
- `mtu`: the ATT MTU of the link that reads the document. It is the only `"mtu"` key in it.
- A radar not detected (or still being configured) has `"fw":""` and `"baud":0`. An IMU never found
  has `"who":0`.
- `imus[i].repeats` (u32): raw 200 Hz readings repeated since boot because a read failed or its slot
  was skipped (§4.2). The IMU scales are fixed (65.5 LSB per °/s, 4096 LSB per g, §4.2); readers use
  those values.
- `tx_power_dbm`: `NimBLEDevice::getPower()`, expected 9.
- `conns`: one entry per connected link, in connection-slot order, `[]` with none:
  - `role`: `"watch"` or `"phone"` (§1, §6);
  - `itvl_ms`: the link's last reported interval, units × 1.25 with one decimal; `lat`: latency in
    connection events; `timeout_ms`: supervision timeout, units × 10;
  - `sent`: packets notified on this link since it connected; `dropped`: packets this link missed
    because its queue was full or it already held 3 packets (§4.4). Both restart at 0 on each new connection and are reported
    modulo 1 000 000 (they wrap; take differences mod 10⁶).
- `bonds`: bonded devices kept by the belt, 0-2 (§3).
- Firmware 0.2.0 removed `conn` (now `conns[]`) and the per-IMU `gyro_lsb_dps` / `accel_lsb_g`
  constants; readers that used them fall back to the fixed values above.
- Size: at most 512 B (the ATT maximum attribute length); with two links and every field at its
  longest it is 511 B. It is longer than one ATT read, so clients read it long (Read, then Read
  Blob); readers must accept it in one piece of up to 512 B.
- The belt builds the document when the Read at offset 0 arrives, and the Read Blob requests of the
  same long read are served from that copy, so one long read never mixes two versions. The two links
  share that copy: a Read at offset 0 within 2 s of the other link's Read at offset 0 gets the
  document built for the other link (same bytes, the other link's `mtu`) instead of a new one.
  Exception: a copy built for a link whose MTU is below 247 is rebuilt for a reader at 247 or more,
  so a reader that can stream never gets the `mtu` of a link that cannot.

## 6. `control` writes

| Bytes | Meaning |
|---|---|
| `01 <id>` | Restart radar `<id>` (0 or 1): enable configuration, then restart (0x00A3) 100 ms later. A radar not configured yet (`baud` 0) is probed and configured instead |
| `03` | IDENTIFY: blink the LED 3 times (1.2 s). Ignored while any bonded device has a session active |
| `04 <0/1>` | SESSION_ACTIVE: a client writes 1 when its session starts and 0 when it stops. The belt keeps one value per **bonded device**: it survives that device's disconnections and follows it into any connection, until that device writes `04` again, its bond is replaced, the bonds are erased (BOOT ≥ 10 s) or the ESP32 reboots |
| `05` | OPEN_PAIRING_WINDOW: opens the pairing window for 60 s (§3). Accepted only from a trusted (bonded and encrypted) link; ignored from any other. There is no acknowledgement: the write succeeds even when it is ignored, and the serial `diag` line shows the window (`pair=<seconds>`) |
| `06 <0/1>` | SET_ROLE for the writing link: 0 = watch, 1 = phone. Changes its notify priority (§4.3), its drop policy (§4.4) and its connection parameters (§1), and the belt remembers it as the role of that bonded device (§3). A link that never writes it is a watch |

- The phone writes `06 01` as its first `control` write, right after the link is encrypted and
  before it enables notifications, so it never takes a watch's priority.
- Writes are applied in the order they arrive. The belt queues up to 4 per link; a write that finds
  the queue full is ignored.
- Every write from a link that is not trusted (authenticated but not bonded with the belt, about to
  be dropped) is ignored, whatever its content.
- Any other content (wrong length, radar id > 1, role > 1, `05` with an argument, unknown command,
  including `02 …` which is not part of the MVP) is ignored.
````

- [ ] **Step 2: Keep the contracts in line with PROTOCOL.md**

In `docs/superpowers/plans/2026-09-30-blindside-mvp-00-contracts.md`, replace the three `control` rows

```markdown
| `01 <id>` | Restart radar `<id>`: enable config, then restart (`A3`) |
| `03` | IDENTIFY: blink the LED 3×. Ignored while a session is active |
| `04 <0/1>` | SESSION_ACTIVE: the watch sets 1 on start and 0 on stop |
```

with

```markdown
| `01 <id>` | Restart radar `<id>`: enable config, then restart (`A3`) |
| `03` | IDENTIFY: blink the LED 3×. Ignored while any bonded device has a session active |
| `04 <0/1>` | SESSION_ACTIVE: a client sets 1 on start and 0 on stop; kept per bonded device, across its reconnections (firmware 0.2.0) |
| `05` | OPEN_PAIRING_WINDOW (firmware 0.2.0): opens the pairing window for 60 s; only from a trusted link; no acknowledgement |
| `06 <0/1>` | SET_ROLE (firmware 0.2.0): 0 = watch (default), 1 = phone; the phone writes `06 01` first |
```

and replace the `info` example block and its first two bullets (from the line ```` ```json ```` after `### \`info\` JSON (example)` down to the bullet that ends `… under the limit.`) with:

````markdown
```json
{"proto":1,"fw":"0.2.0","boot_id":"9f3a12c4","reset":"POWERON","mtu":255,
 "radars":[{"id":0,"fw":"V2.04.23101915","baud":256000},{"id":1,"fw":"V2.04.23101915","baud":256000}],
 "imus":[{"id":0,"who":104,"repeats":0},{"id":1,"who":112,"repeats":3}],"tx_power_dbm":9,
 "conns":[{"role":"watch","itvl_ms":45.0,"lat":0,"timeout_ms":4000,"sent":1234,"dropped":0},
          {"role":"phone","itvl_ms":75.0,"lat":0,"timeout_ms":4000,"sent":1200,"dropped":3}],
 "bonds":2,"uptime_s":42}
```

- **Size limit: ≤ 512 B** (the ATT maximum attribute length). The value is longer than one ATT read at MTU 247, so clients read it long (ATT Read Blob; Android's `readCharacteristic` does it on its own, and NimBLE serves it). Readers must accept it in one piece of up to 512 B. With two links and every field at its longest the document is 511 B.
- `imus[i].repeats` (u32): raw 200 Hz readings repeated since boot because a read failed or its slot was skipped (spec §4.2, "Las repeticiones se cuentan por IMU en `info`").
- `mtu` is the reading link's own ATT MTU and the only `"mtu"` key.
- Firmware 0.2.0 (phase-2 spec §2): `conns` lists each connected link (`role` `watch`/`phone`, `itvl_ms`, `lat`, `timeout_ms`, `sent`, `dropped`; counters per connection, modulo 1 000 000) and `bonds` counts the kept bonds (0-2). It removed `conn` and the per-IMU `gyro_lsb_dps`/`accel_lsb_g`, whose fixed values (65.5, 4096) readers default to. Details: `protocol/PROTOCOL.md` §5.
````

Keep the last bullet (`- Readers ignore fields they do not know.`) as it is.

- [ ] **Step 3: Update the hardware checklist**

In `firmware/HARDWARE_CHECKLIST.md`:
- replace the format line (line 6) with:

```markdown
`diag up=… radar0[alive ok bad rst baud gap=min..max] radar1[…] imu0[ok who reads fail rep smp] imu1[…] link0[role trusted sub mtu itvl lat sup sent dropped] link1[…] bonds=K pair=P disc=R acl=A hstk=S tx[fail dropped skipped]`. Un enlace libre se ve como `link1[-]`; `sent` y `dropped` son de esa conexión y vuelven a 0 en cada conexión nueva. `pair` son los segundos que le quedan a la ventana de emparejamiento (`0` cerrada, `open` sin bonds), `acl` los búferes libres del controlador BLE y `hstk` lo mínimo que le ha quedado libre a la pila de la tarea NimBLE, en bytes. Al arrancar sale una vez `ble: controller acl buffers=N`.
```

- in **H1** replace `blindside fw 0.1.0` with `blindside fw 0.2.0`;
- in **H12** replace `` Sostener ≥ 10 s y soltar: `pairing: bonds erased`, la ventana se abre y la clave **no** cambia; `` with `` Sostener ≥ 10 s y soltar: `pairing: bonds erased`, se caen todos los enlaces, la ventana se abre y la clave **no** cambia; ``;
- replace **H14** with:

```markdown
- [ ] **H14. Enlace sano.** Con el reloj conectado: `link0[role=watch trusted=1 sub=1 mtu=255 itvl=24…40 …]` con `sent` subiendo ~50-60 por línea y `dropped=0`; `tx[fail dropped skipped]` cerca de 0 con el reloj al lado; `acl` igual o casi igual al total de `ble: controller acl buffers=N`.
```

- append at the end of the file:

```markdown

## Doble conexión (firmware 0.2.0, fase 2)

Usan las apps de la fase 2. `firmware/tools/bench_e2e.py` (Tarea 10 del plan 04) automatiza H21 y, con las apps instaladas y el celular emparejado, partes de H24, H25, H32 y H33; el resto queda aquí.

- [ ] **H21. Regresión del reloj solo (bloquea la fusión; la automatiza la Tarea 10, Parte A).** Flashear 0.2.0: `blindside fw 0.2.0` y `ble: controller acl buffers=N` con N ≥ 8. Con el reloj ya emparejado en 0.1.0 (el bond se conserva), abrir la app del reloj con arranque automático. En ≤ 30 s: `link0[role=watch trusted=1 sub=1 mtu=255 itvl=24…40 …]` con `sent` subiendo, `link1[-]`, `bonds=1`, `pair=0`, `dropped=0`, `hstk` ≥ 1024. Con un solo bond y el reloj conectado, nRF Connect (otro teléfono) **no** ve el cinturón (no se anuncia, como 0.1.0). La app del reloj no muestra error de MTU y graba como con 0.1.0.
- [ ] **H22. Anuncio con un enlace y rival.** Con reloj y celular emparejados (`bonds=2`) y solo el reloj conectado, nRF Connect (otro teléfono) ve `Blindside-XXXX`. Al conectarse sin emparejar: `pairing: dropped an unknown or unauthenticated peer` en < 0,5 s; el `sent` del enlace del reloj sigue subiendo y la grabación del reloj no tiene saltos de `seq`. Repetir con nRF Connect reconectando en bucle 1 min mientras se apaga y se enciende dos veces el Bluetooth del reloj: el reloj reconecta en ≤ 5 s cada vez, nRF Connect nunca muestra una notificación de `stream` y, entre apagones, la grabación del reloj no tiene saltos.
- [ ] **H23. Emparejar el celular desde el reloj (`05`).** Reloj: Ajustes → "Emparejar celular". Monitor: `pairing window open (60 s)` y `pair=` contando hacia atrás desde 60 en las líneas `diag`. Con la sesión del reloj activa el LED **no** parpadea (cámara del teléfono a oscuras); sin sesión, parpadea. En el celular: "Iniciar radar" y la clave. Monitor: `pairing: bonded …`; `diag`: `pair=0`, `link1[role=phone trusted=1 sub=1 … itvl=48…80 lat=0 …]` y `bonds=2`. Si queda `itvl` en 80-100 con `lat=2`, la app del celular cambió su prioridad después de `06 01` (bandera 7 del plan 04): anotarlo.
- [ ] **H24. Dos flujos.** Reloj y celular conectados 5 min: `sent` sube en los dos enlaces; el `dropped` del enlace `role=watch` queda en 0; el del celular puede subir poco; `tx[fail]` cerca de 0; `acl` nunca llega a 0. La pantalla Cinturón del celular muestra `conns` con los dos roles y `bonds=2`.
- [ ] **H25. Celular fuera.** `adb shell am force-stop io.github.santiquiroz.blindside` en el celular: en la siguiente línea `diag` su enlace pasa a `[-]`; el del reloj sigue sin hueco y nRF Connect vuelve a ver el cinturón anunciándose (`bonds=2` con el celular ausente). Dejarlo así 10 min: la grabación del reloj no tiene saltos de `seq`, el `dropped` del reloj queda en 0 y `tx[skipped]` no sube aunque el cinturón se esté anunciando. Abrir otra vez la app del celular: reconecta sin pedir clave.
- [ ] **H26. Celular solo.** Con los dos conectados, apagar el Bluetooth del reloj: durante hasta un timeout de supervisión (4 s) el celular puede no recibir nada (el reloj sigue primero en el orden y el cinturón lo reintenta); después el enlace del reloj pasa a `[-]`, el celular sigue recibiendo y su `dropped` deja de subir (ahora es el primero en el orden). Encender el Bluetooth del reloj: reconecta en ≤ 5 s y vuelve a ir primero.
- [ ] **H27. Tercer dispositivo.** (a) Con el reloj conectado y el Bluetooth del celular apagado, abrir la ventana desde el reloj y emparejar un tercer teléfono con nRF Connect (con la clave). Monitor: `pairing: replaced <dirección del celular> (phone)` y `pairing: bonded …`, `bonds=2`; el reloj no se desconecta. (b) Volver a emparejar el celular (H23): el reemplazado es ahora el tercer teléfono (para el cinturón es un "reloj" sin `06`, y hay dos bonds de reloj). (c) Con el celular conectado y el Bluetooth del reloj apagado, apagar y encender el ESP32 y, en el primer minuto, sostener BOOT 3-10 s; emparejar el tercer teléfono: `pairing: new bond refused, …`, `bonds=2`, y al encender su Bluetooth el reloj reconecta sin pedir clave. Al terminar, olvidar el cinturón en el tercer teléfono.
- [ ] **H28. IDENTIFY y sesión por dispositivo.** Con la sesión del reloj activa, IDENTIFY desde el celular no enciende el LED, aunque el celular haya escrito `04 00`. Detener la sesión en el reloj: IDENTIFY desde el celular parpadea 3 veces. Luego, con la sesión del celular activa, `adb shell am force-stop` del celular (nunca escribe `04 00`): IDENTIFY desde el reloj no enciende nada; abrir otra vez la app del celular y detener su sesión: IDENTIFY desde el reloj parpadea. Con la sesión del reloj activa, apagar y encender su Bluetooth (vuelve como una conexión nueva) y detener la sesión: IDENTIFY desde el celular parpadea.
- [ ] **H29. Borrado total.** En los primeros 60 s tras encender, con los dos conectados, sostener BOOT ≥ 10 s y soltar: `pairing: bonds erased`, los dos enlaces caen (`link0[-] link1[-]`), `bonds=0`, `pair=open`. Re-emparejar reloj y celular.
- [ ] **H30. Reinicio con dos bonds.** Apagar y encender el ESP32 con reloj y celular cerca: `bonds=2` en la primera línea `diag` y los dos reconectan en ≤ 5 s cada uno sin pedir clave. Opcional: repetir H27(a) justo después del reinicio, antes de que el celular reconecte; el reemplazado sigue siendo el celular (el rol se guarda en NVS).
- [ ] **H31. Resistencia doble.** 1 h con reloj y celular conectados: sin `PANIC` ni `BROWNOUT`, `dropped` del reloj en 0, `tx[skipped]` cerca de 0, `acl` nunca en 0. Si el `dropped` del celular sube sin parar con los dos al lado, anotar cuánto sube por línea (dato para subir `kUnprotectedBacklogCap` o `CONFIG_BT_NIMBLE_MSYS1_BLOCK_COUNT`, siempre con N ≥ 2 × cap + 2).
- [ ] **H32. Celular que desaparece sin despedirse (bloquea la fusión de la app del celular; la Tarea 10, Parte B, automatiza la variante con el Bluetooth apagado).** Con reloj y celular transmitiendo, alejar el celular hasta perder el enlace (o `adb shell cmd bluetooth_manager disable` en el celular). Mientras su enlace espera el timeout de supervisión (4 s) y después: la grabación del reloj no tiene saltos de `seq`, el `dropped` del reloj queda en 0, `tx[skipped]` no sube, `acl` nunca llega a 0 y el `dropped` del celular sube hasta que su enlace pasa a `[-]` con `disc=520` (0x208, timeout de supervisión). Si sale `disc=531` (0x213) el celular cerró el enlace con aviso y la prueba no ejercitó el caso: repetir alejándolo.
- [ ] **H33. Pila de la tarea NimBLE.** Con reloj y celular conectados y después de que los dos lean `info` (el celular, abriendo su pantalla Cinturón), `hstk` ≥ 1024 en todas las líneas `diag`. Si baja de 1024, subir `CONFIG_BT_NIMBLE_HOST_TASK_STACK_SIZE` y anotarlo.
```

- [ ] **Step 4: Check the docs against the code**

Run (from `<repo>`): `grep -n "tx\[sent\|link\[conn\|fw 0\.1\.0" protocol/PROTOCOL.md firmware/HARDWARE_CHECKLIST.md` → no output.
Run (from `<repo>`): `grep -n "per connection slot\|oldest one if" protocol/PROTOCOL.md docs/superpowers/plans/2026-09-30-blindside-mvp-00-contracts.md` → no output.
Run (from `firmware/`): `python tools/run_native_tests.py` → `18 suites passed, 0 failed` (docs only, sanity).

- [ ] **Step 5: Commit**

```bash
cd C:/personal/blindside-p2-firmware
git add protocol/PROTOCOL.md docs/superpowers/plans/2026-09-30-blindside-mvp-00-contracts.md firmware/HARDWARE_CHECKLIST.md
git commit -m "docs: protocolo, contratos y checklist de hardware para la doble conexión del cinturón"
```

---

### Task 10: Bench E2E — spec §7 steps 1-2 now, steps 5-6 and H32 when the apps exist

**Files:**
- Create: `firmware/tools/bench_e2e.py`

**Interfaces:**
- Consumes: the `diag` format and the boot line `ble: controller acl buffers=N` (Task 7); the boot banner `blindside fw <version>` (MVP); the watch app `io.github.santiquiroz.blindside/.wear.MainActivity` (MVP, auto-start when the belt is paired); for Part B, the phone app `io.github.santiquiroz.blindside/.phone.MainActivity` (plan 06) and the watch build of plan 05.
- Produces: `python tools/bench_e2e.py [--port COM6] [--seconds 45] [--check watch|both|watch-steady] [--reset] [--start-watch-app] [--expect-fw 0.2.0]`. It prints every serial line it reads and ends with `PASS <check>` (exit 0) or `FAIL <check>: <reasons>` (exit 1). Every check also requires `dropped(watch)=0`, `acl` > 0 and `hstk` ≥ 1024 in every `diag` line, and, when the boot lines were read (`--reset`), `ble: controller acl buffers` ≥ 8 and the expected firmware version.

This task owns spec §7 steps 1-2 (the merge gate of this branch, = H21) and steps 5-6 plus H32. Part A runs now. Part B runs only once the plan 05 watch build and the plan 06 phone app are installed and the phone is paired with the belt (H23, or the apps' own E2E); it gates merging the phone-app branch, not this one, because without a phone link none of what it checks can happen. Use only the hardware actions listed in Global Constraints.

**Part A — flash 0.2.0 and the watch regression (blocks the merge)**

- [ ] **Step 1: Check the bench**

```bash
cd C:/personal/blindside-p2-firmware/firmware
python -m serial.tools.list_ports -v
adb devices -l
python -c "import serial"
```

Expected: `COM6` with `Silicon Labs CP210x USB to UART Bridge`; an adb device whose `adb -s <id> shell getprop ro.build.characteristics` contains `watch` (the watch, model `SM_L310`); no error from the import (otherwise `python -m pip install --user pyserial`). Call the watch's adb id `<watch>` below. If COM6 or the watch is missing, stop and report "bench not available": the branch stays unmerged and nothing else changes.

- [ ] **Step 2: Write the script**

`firmware/tools/bench_e2e.py`:

```python
# Bench E2E (phase-2 spec §7): reads the belt's serial lines from COM6 and checks one scenario.
import argparse
import re
import subprocess
import sys
import time

import serial

WATCH_ACTIVITY = "io.github.santiquiroz.blindside/.wear.MainActivity"
FIRST_STREAM_DEADLINE_S = 30
MIN_ACL_BUFFERS = 8
MIN_HOST_STACK_BYTES = 1024
NO_BOOT = {"fw": None, "acl_total": None}
LINK = re.compile(
    r"link(\d)\[role=(\w+) trusted=(\d) sub=(\d) mtu=(\d+) itvl=(\d+) lat=(\d+) sup=(\d+) sent=(\d+) dropped=(\d+)\]")
TAIL = re.compile(
    r"bonds=(\d+) pair=(\w+) disc=(-?\d+) acl=(\d+) hstk=(\d+) tx\[fail=(\d+) dropped=(\d+) skipped=(\d+)\]")
BOOT_FW = re.compile(r"blindside fw (\S+)")
BOOT_ACL = re.compile(r"ble: controller acl buffers=(\d+)")


def link_fields(match):
    return {"slot": int(match[1]), "role": match[2], "trusted": match[3] == "1", "sub": match[4] == "1",
            "mtu": int(match[5]), "itvl": int(match[6]), "lat": int(match[7]), "sent": int(match[9]),
            "dropped": int(match[10])}


def parse_diag(line, at_s):
    tail = TAIL.search(line)
    if not line.startswith("diag ") or tail is None:
        return None
    return {"at_s": at_s, "links": [link_fields(m) for m in LINK.finditer(line)], "bonds": int(tail[1]),
            "pair": tail[2], "disc": int(tail[3]), "acl": int(tail[4]), "hstk": int(tail[5]),
            "skipped": int(tail[8])}


def first_match(pattern, lines):
    return next((found[1] for found in map(pattern.search, lines) if found), None)


def boot_facts(lines):
    acl_total = first_match(BOOT_ACL, lines)
    return {"fw": first_match(BOOT_FW, lines), "acl_total": int(acl_total) if acl_total else None}


def streaming(diag, role):
    return [link for link in diag["links"] if link["role"] == role and link["trusted"] and link["sub"]]


def sent_rising(diags, role):
    counts = [streaming(diag, role)[0]["sent"] for diag in diags if streaming(diag, role)]
    return len(counts) >= 2 and all(later > earlier for earlier, later in zip(counts, counts[1:]))


def watch_never_dropped(diags):
    return all(link["dropped"] == 0 for diag in diags for link in streaming(diag, "watch"))


def skipped_flat(diags):
    return diags[-1]["skipped"] == diags[0]["skipped"]


def first_stream_in_time(diags, role):
    return any(streaming(diag, role) and diag["at_s"] <= FIRST_STREAM_DEADLINE_S for diag in diags)


def scenario_rules(diags, check):
    rules = {
        "watch": [("watch streaming within 30 s", first_stream_in_time(diags, "watch")),
                  ("watch sent rising", sent_rising(diags, "watch"))],
        "both": [("watch sent rising", sent_rising(diags, "watch")),
                 ("phone sent rising", sent_rising(diags, "phone"))],
        "watch-steady": [("watch sent rising", sent_rising(diags, "watch")), ("tx[skipped] flat", skipped_flat(diags))],
    }
    return rules[check]


def common_rules(diags, boot, expect_fw):
    return [("dropped(watch)=0", watch_never_dropped(diags)),
            ("free controller buffers never 0", all(diag["acl"] > 0 for diag in diags)),
            ("host stack >= 1024 B", all(diag["hstk"] >= MIN_HOST_STACK_BYTES for diag in diags)),
            ("controller acl buffers >= 8", boot["acl_total"] is None or boot["acl_total"] >= MIN_ACL_BUFFERS),
            (f"firmware {expect_fw}", expect_fw is None or boot["fw"] == expect_fw)]


def verdict(diags, boot, check, expect_fw):
    if len(diags) < 3:
        return ["fewer than 3 diag lines in 0.2.0 format"]
    rules = scenario_rules(diags, check) + common_rules(diags, boot, expect_fw)
    return [name for name, passed in rules if not passed]


def adb_devices():
    out = subprocess.run(["adb", "devices"], capture_output=True, text=True, check=True).stdout
    return [line.split("\t")[0] for line in out.splitlines()[1:] if line.endswith("\tdevice")]


def is_watch(device):
    props = subprocess.run(["adb", "-s", device, "shell", "getprop", "ro.build.characteristics"],
                           capture_output=True, text=True).stdout
    return "watch" in props


def start_watch_app():
    watches = [device for device in adb_devices() if is_watch(device)]
    if not watches:
        raise SystemExit("FAIL: no watch on adb")
    subprocess.run(["adb", "-s", watches[0], "shell", "am", "start", "-n", WATCH_ACTIVITY], check=True,
                   capture_output=True)


# Opening the port with DTR/RTS asserted resets the DevKit through its auto-reset circuit.
def open_without_reset(port):
    link = serial.Serial()
    link.port = port
    link.baudrate = 115200
    link.timeout = 0.5
    link.dtr = False
    link.rts = False
    link.open()
    return link


# EN low for 150 ms with GPIO0 high: a normal boot, so the boot lines can be read.
def reset_board(link):
    link.rts = True
    time.sleep(0.15)
    link.rts = False


def prepare_bench(link, reset, start_app):
    if reset:
        reset_board(link)
    if start_app:
        start_watch_app()


def read_lines(port, seconds, reset, start_app):
    lines = []
    started = time.time()
    with open_without_reset(port) as link:
        prepare_bench(link, reset, start_app)
        while time.time() - started < seconds:
            line = link.readline().decode("utf-8", errors="replace").strip()
            print(line, flush=True)
            lines.append((line, time.time() - started))
    return lines


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", default="COM6")
    parser.add_argument("--seconds", type=float, default=45)
    parser.add_argument("--check", choices=["watch", "both", "watch-steady"], default="watch")
    parser.add_argument("--start-watch-app", action="store_true")
    parser.add_argument("--reset", action="store_true")
    parser.add_argument("--expect-fw")
    args = parser.parse_args()
    lines = read_lines(args.port, args.seconds, args.reset, args.start_watch_app)
    diags = [diag for diag in (parse_diag(line, at_s) for line, at_s in lines) if diag]
    missing = verdict(diags, boot_facts([line for line, _ in lines]), args.check, args.expect_fw)
    print(f"FAIL {args.check}: {', '.join(missing)}" if missing else f"PASS {args.check}")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 3: Check the script without hardware**

```bash
python -B -c "import sys; sys.path.insert(0, 'tools'); import bench_e2e as b; line = 'diag up=60s link0[role=watch trusted=1 sub=1 mtu=255 itvl=36 lat=0 sup=400 sent={} dropped=0] link1[-] bonds=1 pair=0 disc=531 acl=10 hstk=2900 tx[fail=0 dropped=0 skipped=0]'; d = [b.parse_diag(line.format(s), t) for s, t in ((10, 6), (65, 11), (120, 16))]; boot = b.boot_facts(['blindside fw 0.2.0 boot_id=9f3a12c4 reset=POWERON', 'ble: controller acl buffers=6']); print(b.verdict(d, b.NO_BOOT, 'watch', None), b.verdict(d[:1], b.NO_BOOT, 'watch', None), b.verdict(d, boot, 'watch', '0.2.0'))"
```

Expected: `[] ['fewer than 3 diag lines in 0.2.0 format'] ['controller acl buffers >= 8']`.

- [ ] **Step 4: Commit the script**

```bash
git add tools/bench_e2e.py
git commit -m "test: script E2E de banco que lee el diag del cinturón por COM6 y revisa la regresión del reloj"
```

- [ ] **Step 5: Note whether the watch app is running**

Run: `adb -s <watch> shell pidof io.github.santiquiroz.blindside` → a PID if it runs, nothing otherwise. Step 9 leaves it the same way.

- [ ] **Step 6: Flash 0.2.0**

Run: `python -m platformio run -e esp32dev -t upload --upload-port COM6` → `[SUCCESS]`. The upload writes the bootloader, the partition table and the application only; NVS (the passkey and the watch's bond from 0.1.0) survives. If the port is busy (another serial monitor), stop and report; never kill another process.

- [ ] **Step 7: Watch regression (spec §7 step 2, H21)**

Run: `python tools/bench_e2e.py --reset --start-watch-app --expect-fw 0.2.0 --check watch --seconds 45`

Expected: the last line is `PASS watch`. The printed lines include `blindside fw 0.2.0 …`, `ble: controller acl buffers=N` (N ≥ 8; write N down in the report), `blindside passkey: …`, and, within 30 s of the start, `diag` lines with `link0[role=watch trusted=1 sub=1 mtu=255 itvl=24…40 …]` (or `link1[…]`), `sent` rising, `dropped=0`, the other slot `[-]`, `bonds=1`, `pair=0`.

Then run: `python tools/bench_e2e.py --check watch-steady --seconds 120` → `PASS watch-steady` (two minutes with the watch alone: `dropped(watch)=0`, `tx[skipped]` flat, `acl` > 0, `hstk` ≥ 1024).

- [ ] **Step 8: Roll back if Step 7 failed**

Only if a `FAIL` line appeared in Step 7: put 0.1.0 back and report the failing output; the branch stays unmerged.

```bash
git -C C:/personal/blindside-p2-firmware worktree add --detach C:/personal/blindside-p2-rollback main
cd C:/personal/blindside-p2-rollback/firmware
python -m platformio run -e esp32dev -t upload --upload-port COM6
python C:/personal/blindside-p2-firmware/firmware/tools/bench_e2e.py --reset --seconds 15
```

The last command must print `blindside fw 0.1.0` (its own verdict is `FAIL` because 0.1.0 prints the old `diag` format; ignore it). On `PASS` in Step 7, leave 0.2.0 on the belt: plans 05 and 06 need it, and the orchestrator decides the merge.

- [ ] **Step 9: Leave the watch as it was**

If Step 5 printed no PID: `adb -s <watch> shell am force-stop io.github.santiquiroz.blindside`. The belt keeps the watch's session flag until the watch's next session ends or the belt reboots (design note "Session state per bonded device"); that is expected.

**Part B — two links (only when the apps of plans 05 and 06 are installed and the phone is paired)**

- [ ] **Step 10: Check that Part B can run**

Run: `adb -s <phone> shell pm list packages io.github.santiquiroz.blindside` → `package:io.github.santiquiroz.blindside` (`<phone>` is the adb id whose characteristics say `phone`, model `SM_S938B`, `192.168.10.119`), and a `diag` line printed by `python tools/bench_e2e.py --seconds 8` (ignore its verdict) shows `bonds=2`. If either is missing, skip Part B and say so in the report.

- [ ] **Step 11: Two flows (spec §7 step 5, H24, H33)**

Run: `adb -s <phone> shell am start -n io.github.santiquiroz.blindside/.phone.MainActivity` (it auto-starts the radar when the belt is paired), then `python tools/bench_e2e.py --start-watch-app --check both --seconds 60` → `PASS both`: a `role=watch` and a `role=phone` link, both `trusted=1 sub=1` with `sent` rising, `dropped(watch)=0`, `hstk` ≥ 1024 after both apps read `info`. Note the phone's `itvl` and `lat`: anything other than 48-80 with `lat=0` means the phone app still changes its priority after `06 01` (cross-plan flag 7); report it.

- [ ] **Step 12: Phone force-stopped (spec §7 step 6, H25)**

Run: `adb -s <phone> shell am force-stop io.github.santiquiroz.blindside`, then `python tools/bench_e2e.py --check watch-steady --seconds 60` → `PASS watch-steady`, and from the second `diag` line on the phone's slot shows `[-]`.

- [ ] **Step 13: Phone vanishing (H32)**

Restart the phone app as in Step 11 and wait for `PASS both` again. Then run `adb -s <phone> shell cmd bluetooth_manager disable` and immediately `python tools/bench_e2e.py --check watch-steady --seconds 60` → `PASS watch-steady` (`dropped(watch)=0`, `tx[skipped]` flat, `acl` never 0 while the phone's link waits out its supervision timeout). Then `adb -s <phone> shell cmd bluetooth_manager enable`. Write down the `disc=` value printed once the phone's slot is `[-]`: `520` (0x208, supervision timeout) means the silent-link path was exercised; `531` (0x213) means the phone said goodbye before switching off, and only the manual H32 (walking out of range) exercises that path.

- [ ] **Step 14: Report**

Report every `PASS`/`FAIL` line, N from `ble: controller acl buffers=N`, the lowest `hstk` seen, the phone's `itvl`/`lat` from Step 11 and the `disc=` value from Step 13. A `FAIL` in Part B blocks merging the phone-app branch; this branch's merge depends on Part A only.

---

## Hand-off

The branch `feat/p2-firmware-dual` (worktree `C:/personal/blindside-p2-firmware`) ends with 10 commits, 18 native suites green and a green `esp32dev` build. After Task 10 the belt runs 0.2.0 if Part A passed, or 0.1.0 again if it failed. The branch is **not** merged: per the spec's golden rule the orchestrator merges it only after Task 10 Part A (H21 = spec §7 step 2) printed `PASS watch` and `PASS watch-steady`. Report those lines, N from `ble: controller acl buffers=N` and the lowest `hstk` seen. Part B (spec §7 steps 5-6 and H32) runs once the plan 05 watch build and the plan 06 phone app are installed and the phone is paired; its result gates the phone-app branch. H22-H33 are Santiago's bench checks (H21 and the automatable parts of H24, H25, H32 and H33 are covered by Task 10). Spec §7 step 4 (pairing the phone hands-free through both UIs) and step 7 (recording transfer) are owned by no plan yet (cross-plan flag 9). Bring to the orchestrator: "Decisions Santiago must confirm" and cross-plan flags 3, 5, 7, 8 and 9.
