# Blindside MVP — Plan 02: Firmware (ESP32) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Belt firmware for the Manizales MVP (2026-10-11): it configures two HLK-LD2450 radars and two MPU6050 IMUs, packs raw frames, 50 Hz IMU batches, STATUS and LINK into 100 ms TLV cuts, and streams them over an encrypted, bonded NimBLE link to the Galaxy Watch, plus the normative `protocol/PROTOCOL.md`, the shared-vector update and the repository CI.

**Architecture:** Every decision that can be pure lives in a PlatformIO library under `firmware/lib/` (frame sync, command bytes, watchdog, IMU averaging on a fixed grid, IMU health, TLV bundling, backlog/drop policy, cut schedule, pairing/LED/control/serial rules) and is covered by Unity tests built for the `native` environment. Thin Arduino glue in `firmware/src/` runs as FreeRTOS tasks, as spec §4.4 requires: one `imu_task` per IMU and one `radar_rx` task per UART (core 1, each owning its bus), a `bundler` task every 100 ms, and `loop()` for pairing, LED, control, serial commands, `info` and diagnostics. Tasks share data only through FreeRTOS queues and lock-protected snapshots; NimBLE callbacks only write atomics.

**Tech Stack:** PlatformIO (`espressif32`, Arduino core 2.0.17 = `framework-arduinoespressif32` 4.20017, ESP-IDF 4.4, FreeRTOS tick 1 kHz), NimBLE-Arduino 2.x (`^2` resolves to 2.5.1 on 2026-09-30; every NimBLE, Arduino and FreeRTOS call below was compile-checked against those versions), Unity, GitHub Actions, Python 3 (vector generator; local test runner on the Zig toolchain from PyPI).

**Spec:** [`docs/superpowers/specs/2026-09-30-blindside-v1-design.md`](../specs/2026-09-30-blindside-v1-design.md) (§2.2, §4, §8, §9 "Firmware" and "Seguridad", §10.3, §12). **Binding contracts:** [`2026-09-30-blindside-mvp-00-contracts.md`](2026-09-30-blindside-mvp-00-contracts.md). Read both before Task 1.

## Global Constraints

- Repo `C:/personal/blindside`, branch `main`, AGPL-3.0, remote `origin` = `https://github.com/santiquiroz/blindside.git`. Run firmware commands from `C:/personal/blindside/firmware`.
- Commits: conventional prefixes (`feat:`, `fix:`, `test:`, `docs:`, `chore:`, `ci:`) with the description in Spanish; author already configured (`Santiago Quiroz upegui`); **never** add a `Co-Authored-By` line. Never push without Santiago's explicit authorisation in the session.
- Owner code rules (mandatory): atomic functions whose name says what they do; low cyclomatic complexity (extract branches into named functions, early returns); **no doc comments** — only a one-line comment when the *why* is not obvious; pure functions with explicit dependencies (small structs passed and returned by value; large fixed buffers passed by explicit reference); immutable data where the language allows it; files of 200-400 lines typical, 800 max.
- Toolchain (contract): `platform = espressif32`, `framework = arduino`, `board = esp32dev`, `lib_deps = h2zero/NimBLE-Arduino@^2`; second environment `native` with Unity. C++ dialect `gnu++17` in both environments.
- **Tests.** CI (`pio test -e native`, job `firmware`) is the authority. There is no installed C/C++ host compiler, so locally:
  - `python tools/run_native_tests.py [suite …]` (from `firmware/`) builds and runs the native suites with the Zig toolchain from PyPI (`python -m pip install --user ziglang`, once). It downloads Unity v2.6.0 into `firmware/.pio/native-runner/` on first use.
  - `python -m platformio test -e esp32dev -f <suite> --without-uploading --without-testing` builds a suite with the ESP32 cross-compiler: `[SKIPPED]` means it built (nothing is uploaded), `[ERRORED]` means it did not.
  - With Docker Desktop running, from the repo root: `docker run --rm -v "${PWD}:/w" -w /w/firmware python:3.12 sh -c "pip install platformio && pio test -e native"` runs exactly what CI runs.
  - Santiago runs the suites on the board with `pio test -e esp32dev`.
- Windows: keep the PlatformIO core on a short path (the default `~/.platformio` is fine). A toolchain under a deep directory fails with `xtensa-esp32-elf-g++: error: CreateProcess: No such file or directory`.
- Shared vectors: `python protocol/tools/make_vectors.py` generates `protocol/vectors/vectors.json` and `firmware/test/vectors.h`. **Never edit those outputs by hand**; CI fails if they drift from the generator. Task 1 owns this plan's generator change (fill order and the LINK vector).
- MVP scope is spec §12 "Entra" only: no `SET_ZONES` (0x02), no MOSFET power cut, no polished S10 plan B, no new purchases (D17).
- Pins (spec §2.2, verbatim): radar A UART2 RX **GPIO16** / TX **GPIO17**; radar B UART1 remapped RX **GPIO26** / TX **GPIO27**; IMU A I2C1 (`Wire1`) SDA **GPIO32** / SCL **GPIO33**; IMU B I2C0 (`Wire`) SDA **GPIO21** / SCL **GPIO22**; I2C at **100 kHz**; status LED **GPIO2**; BOOT button **GPIO0**, read only after boot and only during the first 60 s; never route a radar output to GPIO12.
- Radar UART RX buffers are **2 KB**, set with `setRxBufferSize` **before** `begin()`; 8N1; baud probe order starts at 256000.
- Radar boot sequence (spec §4.1): enable config (0x00FF) → read firmware (0x00A0) → multi-target (0x0090) → Bluetooth off (0x00A4, value 0x0000) → restart (0x00A3) sent last, still in config mode.
- Watchdog (spec §4.1): 2 s without valid frames → enable + restart; then retry every 30 s while silent; restarts counted (u8, saturating) and the radar flagged dead in `flags`.
- IMU (spec §4.1): read at **200 Hz**; gyro ±500 °/s (`GYRO_CONFIG = 0x08`, 65.5 LSB/(°/s)); accel ±8 g (`ACCEL_CONFIG = 0x10`, 4096 LSB/g); `CONFIG` `DLPF_CFG = 3`; `ACCEL_CONFIG2 (0x1D) = 0x03` only when `WHO_AM_I != 0x68`; output 50 Hz = mean of 4 raw readings; cumulative u32 sums of raw gx, gy, gz at 200 Hz since boot.
- IMU timing (contract, spec §4.2): fixed 5 ms grid; each 50 Hz sample is one 20 ms slot `[s, s+20)` of exactly 4 readings, stamped at the slot centre `s + 10`; a failed or skipped read repeats the previous reading, so the sums advance exactly 4 readings per slot and the grid never moves; repeats are counted per IMU.
- IMU faults (spec §4.4, §8): `Wire.setTimeOut(4)`; the ok bit drops after **5** consecutive errors; while down, the IMU's own task recovers the bus once per second (`end()` then `begin()`) and re-probes; the ok bit returns after **5** good reads.
- Packets (contract): little-endian, header 8 B, TLV sections RADAR 0x01, IMU 0x02 (n ≤ 18 per section), STATUS 0x03, LINK 0x04; every packet ≤ **244 B**; payload limit = `min(MTU − 3, 244)`; the bundler splits a cut into several packets and never splits a section.
- Fill order (contract, spec §4.2): IMU A, IMU B, STATUS, LINK, then RADAR of both radars in ascending `t_ms`. Every RADAR frame in packet k has `t_ms` ≤ those in packet k+1.
- BLE (spec §4.2-§4.3, contract UUIDs): `stream` notify with CCCD write requiring encryption; notify only with the CCCD active, a trusted bonded peer and `getPeerMTU() ≥ 247`; `info` READ_ENC JSON ≤ 400 B with the contract's fields; `control` WRITE_AUTHEN (WRITE_ENC under the S12 plan B); one connection; `updateConnParams(h, 24, 40, 0, 400)`; `setDataLen(h, 251)`; `setPower(9)` before advertising; advertising 20-50 ms for 30 s after start/disconnect, then 100-200 ms; LE Secure Connections + bonding + MITM, IO capability DisplayOnly, IRK distributed.
- Pairing (spec §4.3): window of 60 s, opened at boot when there is no bond; BOOT gestures count only in the first 60 s after power-on and act **on release**: 3-10 s opens the window, ≥ 10 s erases the bonds and nothing else; the 6-digit passkey lives in NVS, is printed at every boot and by the serial command `key`, and only `key new` replaces it.
- Threading (spec §4.4): `imu_a` / `imu_b` tasks (core 1, priority 5, `vTaskDelayUntil` 5 ms, each on its own `TwoWire`); `radar_rx_a` / `radar_rx_b` tasks (core 1, priority 6, blocked on `uart_read_bytes`, stamping `millis()` as the bytes arrive, never in a loop that touches I2C); `bundler` task (core 1, priority 3, `vTaskDelayUntil` 100 ms); `loop()` (priority 1) keeps only pairing, LED, control, serial commands, `info` and diagnostics. Each mutable object has one owner task; others read FreeRTOS queues or snapshots copied under a `portMUX` lock; NimBLE callbacks (host task, core 0) only write `std::atomic` fields.
- Light discipline (spec §4.5): the LED is lit only during the first 2 s after boot, while the pairing window is open, and during IDENTIFY; IDENTIFY is ignored while `SESSION_ACTIVE = 1`.

## Review Focus

1. **The radar UART joins mid-frame or loses a byte** (power-up while the module already streams; noise on the 50-80 cm cable) → the parser must resynchronise and lose at most the damaged frame, never the next good one. Pinned by `test_garbage_before_frame_is_skipped` and `test_dropped_byte_loses_only_that_frame` in Task 2.
2. **The watch stops draining notifications for seconds while the link stays up** (body blocking, `BLE_HS_ENOMEM`) → memory stays bounded (~1 s backlog), the oldest radar frames go first, then the oldest IMU samples, every drop is counted, `flags.bit4` is set on the next cut, and a cut is skipped instead of overwriting packets still waiting. Pinned by the budget/drop-order/flag tests in Task 8 and `test_pending_packets_skip_the_cut_and_keep_the_backlog` in Task 11.
3. **An unusual MTU** (watch skipped the exchange → 23; or a negotiated 185) → below MTU 247 nothing is notified; the pure bundler still never exceeds its limit and never cuts a section at 40, 100 or 182 B. Pinned by `test_stream_gate_needs_subscribed_trusted_peer_and_mtu_247` (Task 9) and `test_small_mtu_sends_imu_first_without_cutting_sections`, `test_minimum_payload_uses_single_sample_imu_sections` (Task 7), `test_mtu_185_still_splits_without_breaking_sections` (Task 8).
4. **An IMU cable fails or its task falls behind mid-game** → samples stay on the 20 ms grid, the gyro sums advance exactly 4 readings per slot (so the watch's gap reconstruction stays exact), the ok bit drops after 5 errors, recovery runs once per second and the bit returns after 5 good reads. Pinned by `test_failed_read_repeats_the_previous_reading_and_sums_advance_four_per_block`, `test_skipped_slots_keep_the_samples_on_the_20_ms_grid`, `test_a_long_stall_is_fed_over_several_steps_without_moving_the_grid` (Task 5) and the Task 6 health tests.
5. **The pouch presses BOOT, or light leaks, during a game** → after the first 60 s no gesture acts (so the watch is never dropped and the bonds are never erased mid-game), and the LED stays off except at boot, in the pairing window and during IDENTIFY, which is refused with `SESSION_ACTIVE = 1`. Pinned by `test_gestures_only_count_in_the_first_minute`, `test_led_stays_off_in_game` (Task 10) and `test_identify_is_refused_during_a_session` (Task 9).

## Contract notes (additions and resolved ambiguities)

These fill gaps without contradicting plan 00. The ones a reader of the wire format needs are also in `protocol/PROTOCOL.md` (Task 1).

- **Extra files** beyond the plan-00 layout: `lib/ld2450/radar_watchdog.{h,cpp}` (spec §4.4 names a `radar_watchdog` module) and `lib/ld2450/frame_gaps.{h,cpp}`; `lib/imu_math/mpu6050_registers.{h,cpp}` and `lib/imu_math/imu_health.{h,cpp}`; `lib/bundler/stream_backlog.{h,cpp}` and `lib/bundler/cut_schedule.{h,cpp}`; `lib/belt_rules/` (control parsing, stream gate, `info` JSON, device name, pairing rules, LED pattern, serial commands); `src/imu_task.{h,cpp}`, `src/stream_sender.{h,cpp}` (the bundler task), `src/diagnostics.{h,cpp}`; `firmware/tools/run_native_tests.py`; `test/test_entry.h` and the extra suites `test_vectors_sanity`, `test_radar_watchdog`, `test_frame_gaps`, `test_imu_health`, `test_stream_backlog`, `test_bundler_spec_cases`, `test_cut_schedule`, `test_belt_rules_link`, `test_belt_rules_pairing`. Pure logic must live in `lib/` because PlatformIO does not build `src/` for native tests.
- **The 40 B minimum is only the bundler's own guard.** `kMinStreamPayload` (header + one single-sample IMU section) keeps the pure function safe at any limit, so the spec §9 cases at 182, 100 and 40 B can be tested. On the radio, the contract's rule applies: nothing is notified below MTU 247.
- **Heartbeat:** a cut with nothing to send still emits one header-only packet (8 B, zero sections), so the watch sees the link alive even with both radars and both IMUs dead. Readers must accept a packet with no sections.
- **Rounding:** the 50 Hz mean is the sum of 4 readings divided by 4, rounded half away from zero.
- **IMU repeats before the first good reading** are zeros; samples of an IMU that never answered are still sent on the grid, with its ok bit at 0.
- **LINK bookkeeping:** NimBLE callbacks bump a report generation counter (each `onConnParamsUpdate`, and `onSubscribe` with notifications enabled); the bundler includes LINK while the generation differs from the last one it sent. A counter instead of a flag cannot lose an update that lands between "read" and "clear".
- **IMU repeat counts are not in `info`.** With the contract's fields, the worst-case `info` is exactly 400 B (Task 9 test), so the per-IMU repeat counts of spec §4.2 go to the serial diagnostics line (`rep=`). Flagged below.
- **Radar time stamp:** `radar_rx` takes `millis()` when `uart_read_bytes` returns the chunk holding the header. The UART driver hands a 30-byte burst over at its RX timeout (2 symbols in Arduino 2.0.17; the FIFO-full threshold is 112 B), so the stamp is a near-constant ~1.2 ms after the real header, with jitter far below the 5 ms cut guard.
- **IMU grid lag:** the IMU task's grid starts 2 ms behind its first wake (`config::kImuGridLagMs`), so a FreeRTOS tick that fires a little early or late never makes a tick look skipped. Sample times are grid times.
- **Radar configuration runs inside each `radar_rx` task** (both radars in parallel), so `setup()` returns at once and the LED rules hold from boot. Until a radar finishes, `info` shows `"fw":"","baud":0` and STATUS `baud_index` 0.
- **S10 default:** NimBLE-Arduino forces host-based privacy on the classic ESP32, so the controller's filter-accept list holds the watch's identity address while the watch connects with a rotating RPA; the controller filter will most likely reject the bonded watch. The whitelist is maintained, but `config::kConnectWhitelistOnly` defaults to **false** and the host enforces the bond: outside the window, a peer whose identity is not bonded is dropped on the next `loop()` pass (≤ 5 ms), with no grace. Dropping at once cannot break a working reconnection: if NimBLE fails to resolve the watch's RPA, it also cannot find the watch's LTK, so encryption would fail anyway.
- **CCCD encryption** uses NimBLE's internal `ble_gatts_set_clt_cfg_perm_flags()` (`ble_gatts.c`, declared only in the private `ble_gatt_priv.h` of NimBLE-Arduino 2.5.1), so every CCCD write needs an encrypted link; the stream gate additionally requires a trusted bonded peer.
- **IMU internal rate:** `SMPLRT_DIV = 0` (1 kHz internal with the DLPF on) so each 200 Hz poll reads a fresh filtered value; the 200 Hz sampling of spec §4.1 is the ESP32 poll.

## Decisions Santiago must confirm

- **S10:** `config::kConnectWhitelistOnly = false` by default (host-side bond check, documented plan B of spec §4.3). Spec §4.3 expects the controller filter on; checklist H10 decides.
- **S12:** `config::kRequireMitm = true` by default. If checklist H20 fails on 2-3 Oct, setting it to `false` is the whole plan B (LE SC Just Works inside the window, `control` relaxed to WRITE_ENC, same GATT handles).
- **IMU repeats in `info`:** they do not fit next to the contract's fields within 400 B; they are on serial only.

## Cross-plan flags (for the orchestrator)

Checked against plan 01 as it stood on 2026-09-30 at 23:57.

1. **Plan 01 `BundleEncoder` order (action needed).** Task 1 regenerates `bundle_typical` in the contract's fill order (header, IMU 0, IMU 1, STATUS, RADAR 0, RADAR 1; still 242 B). Plan 01's `BundleEncoder.encode` still writes RADAR first, so its byte-exact `BundleEncoderTest` (`encode(decode(bundle_typical)) == bundle_typical`) fails until it writes IMU batches, then STATUS, then LINK, then RADAR frames.
2. **IMU timestamps (resolved).** Plan 02 now stamps the slot centre, as the contract says and as plan 01 already assumes; plan 01's note "plan 02 stamps the first raw reading" is obsolete and can be deleted.
3. **LINK (0x04).** Plan 01 already decodes LINK. The new shared vector `bundle_with_link` (47 B: header, LINK 36/0/500, one RADAR) carries `expected.link` = `{"interval_units": 36, "latency": 0, "supervision_units": 500}` for a byte-exact decode test.
4. **`info` repeat counts** (spec §4.2) are not in `info`, see "Contract notes". If the watch needs them, the contract has to raise the 400 B limit or drop a field.
5. **Local tests.** The contract says native tests run "in CI only". `firmware/tools/run_native_tests.py` now runs them locally without an installed compiler; CI stays authoritative.

## File map

| Path | Responsibility | Task |
|---|---|---|
| `protocol/tools/make_vectors.py` | contract fill order for `bundle_typical`; new `bundle_with_link` | 1 |
| `protocol/vectors/vectors.json`, `firmware/test/vectors.h` | regenerated, never hand-edited | 1 |
| `firmware/platformio.ini` | envs `esp32dev`, `native`; Unity; build flags | 1 |
| `firmware/include/blindside_config.h` | pins, glue timing, task layout, UUIDs, BLE parameters, S10/S12 flags | 1 |
| `firmware/src/main.cpp` | `setup()` + `loop()` | 1 (stub), 17 |
| `firmware/test/test_entry.h` | Unity entry point for native (`main`) and board (`setup/loop`) | 1 |
| `firmware/test/test_vectors_sanity/test_main.cpp` | proves the test pipeline and the regenerated vectors | 1 |
| `firmware/tools/run_native_tests.py` | local native test runner on the Zig toolchain | 1 |
| `protocol/PROTOCOL.md` | normative wire/GATT/pairing description | 1 |
| `.github/workflows/ci.yml` | vectors freshness, firmware native tests + esp32dev build, radar-core tests, wear-app build | 1 |
| `firmware/lib/ld2450/ld2450_frame.{h,cpp}` | pure frame synchroniser | 2 |
| `firmware/lib/ld2450/ld2450_commands.{h,cpp}` | command bytes, ACK finder, firmware text, baud table | 3 |
| `firmware/lib/ld2450/radar_watchdog.{h,cpp}` | pure 2 s / 30 s restart policy | 4 |
| `firmware/lib/ld2450/frame_gaps.{h,cpp}` | min/max Δt between frames per diagnostics window | 4 |
| `firmware/lib/imu_math/imu_accumulator.{h,cpp}` | 200 → 50 Hz mean, cumulative sums, 5 ms grid with repeats | 5 |
| `firmware/lib/imu_math/mpu6050_registers.{h,cpp}` | register plan, WHO_AM_I rules, burst parsing | 5 |
| `firmware/lib/imu_math/imu_health.{h,cpp}` | down after 5 errors, recovery once per second, ok after 5 good reads | 6 |
| `firmware/lib/bundler/bundler.{h,cpp}` | TLV encoding (incl. LINK), fill order, splitting, flags, payload limit | 7 |
| `firmware/lib/bundler/stream_backlog.{h,cpp}` | pending store, cut selection (cut − 5 ms), drop policy | 8 |
| `firmware/lib/belt_rules/control_command.{h,cpp}` | `control` write parsing, IDENTIFY rule | 9 |
| `firmware/lib/belt_rules/ble_rules.{h,cpp}` | stream gate (MTU ≥ 247), advertising speed, device name | 9 |
| `firmware/lib/belt_rules/info_json.{h,cpp}` | `info` JSON with TX power and connection parameters | 9 |
| `firmware/lib/belt_rules/pairing_rules.{h,cpp}` | BOOT gestures on release, window, auth and drop decisions, passkey | 10 |
| `firmware/lib/belt_rules/led_pattern.{h,cpp}` | LED on/off rule | 10 |
| `firmware/lib/belt_rules/serial_command.{h,cpp}` | `key` / `key new` parsing | 10 |
| `firmware/lib/bundler/cut_schedule.{h,cpp}` | cut action, STATUS cadence, outbox state, retry window, LINK due | 11 |
| `firmware/src/radar_port.{h,cpp}` | UART glue, baud detection, boot sequence, `radar_rx` task | 12 |
| `firmware/src/imu_mpu6050.{h,cpp}` | I2C driver for one IMU (probe, read, bus recovery) | 13 |
| `firmware/src/imu_task.{h,cpp}` | `imu_task` (5 ms grid, health, queue) | 13 |
| `firmware/src/ble_link.{h,cpp}` | NimBLE server, characteristics, callbacks → atomics, advertising | 14 |
| `firmware/src/pairing.{h,cpp}` | NVS passkey, bonds, whitelist, BOOT gestures, serial commands, peer drops | 15 |
| `firmware/src/status_led.{h,cpp}` | GPIO2 driver | 15 |
| `firmware/src/stream_sender.{h,cpp}` | `bundler` task: drain queues, cut, notify with retries | 16 |
| `firmware/src/diagnostics.{h,cpp}` | serial diagnostics line, IMU transitions, reset reason names | 17 |
| `firmware/HARDWARE_CHECKLIST.md` | bench/field checklist for Santiago (Spanish) | 17 |

**Task dependencies:** 1 → everything. 2, 3, 4, 5, 10 and 11 depend only on 1. 6 needs 5. 7 needs 5. 8 needs 7. 9 needs 3 and 7. 12 needs 2, 3, 4, 7. 13 needs 5, 6, 7. 14 needs 9 and 10. 15 needs 10 and 14. 16 needs 8, 9, 11, 12, 13, 14. 17 needs all.

---

### Task 1: Firmware scaffold, shared vectors in contract order, local runner, CI and `PROTOCOL.md`

**Files:**
- Modify: `protocol/tools/make_vectors.py` (full replacement below)
- Regenerate: `protocol/vectors/vectors.json`, `firmware/test/vectors.h`
- Create: `firmware/platformio.ini`
- Create: `firmware/include/blindside_config.h`
- Create: `firmware/src/main.cpp` (stub, replaced in Task 17)
- Create: `firmware/test/test_entry.h`
- Create: `firmware/test/test_vectors_sanity/test_main.cpp`
- Create: `firmware/tools/run_native_tests.py`
- Create: `protocol/PROTOCOL.md`
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - Vectors: `VEC_BUNDLE_TYPICAL` (242 B: header 8, IMU 0 at bytes 8-87, IMU 1 at 88-167, STATUS at 168-179, RADAR 0 at 180-210 with its 24 target bytes at 187, RADAR 1 at 211-241 with its targets at 218); `VEC_BUNDLE_WITH_LINK` (47 B: header `01 0F 09 00 58 1B 00 00`, LINK `04 06 24 00 00 00 F4 01`, RADAR 0 at t 6990 with the official targets); every vector that existed before keeps its name.
  - Namespace `config` constants used by every glue file (`config::kRadarARxPin`, `config::kRadarReadTimeoutMs`, `config::kI2cTimeoutMs`, `config::kImuGridLagMs`, `config::kSensorCore`, `config::kRadarTaskPriority`, `config::kImuTaskPriority`, `config::kBundlerTaskPriority`, `config::kTaskStackBytes`, `config::kRadarQueueDepth`, `config::kImuQueueDepth`, `config::kServiceUuid`, `config::kConnectWhitelistOnly`, `config::kRequireMitm`, … exactly as written below).
  - `test/test_entry.h`, which declares `int run_all_tests();` and supplies `main()` (native) or `setup()/loop()` (board). Every later test file defines `run_all_tests()`, `setUp()` and `tearDown()` and includes `"../test_entry.h"`.
  - `tools/run_native_tests.py [suite …]`: exit code 0 only when every selected suite passes.

- [ ] **Step 1: Install the local tools (once per machine)**

Run: `python -m pip install --user platformio ziglang && python -m platformio --version && python -m ziglang version`
Expected: `PlatformIO Core, version 6.x` and a Zig version (0.16.0 on 2026-09-30).

- [ ] **Step 2: Replace `protocol/tools/make_vectors.py` with the contract fill order and the LINK vector**

```python
# Single source of truth for both ends of the BLE link: writes the Kotlin and C++ test vectors.
import json
import pathlib
import struct

ROOT = pathlib.Path(__file__).resolve().parents[2]

FRAME_HEADER = bytes([0xAA, 0xFF, 0x03, 0x00])
FRAME_TAIL = bytes([0x55, 0xCC])
CMD_HEADER = bytes([0xFD, 0xFC, 0xFB, 0xFA])
CMD_TAIL = bytes([0x04, 0x03, 0x02, 0x01])

TLV_RADAR = 0x01
TLV_IMU = 0x02
TLV_STATUS = 0x03
TLV_LINK = 0x04


# The LD2450 uses sign-magnitude, not two's complement: bit 15 set means positive.
def sign_magnitude(value: int) -> int:
    magnitude = abs(value)
    if magnitude > 0x7FFF:
        raise ValueError(f"magnitude out of range: {value}")
    return (0x8000 | magnitude) if value > 0 else magnitude


def target_bytes(x_mm: int, y_mm: int, speed_cms: int, resolution_mm: int) -> bytes:
    return struct.pack(
        "<HHHH",
        sign_magnitude(x_mm),
        sign_magnitude(y_mm),
        sign_magnitude(speed_cms),
        resolution_mm,
    )


EMPTY_TARGET = bytes(8)


def targets_block(targets) -> bytes:
    raw = b"".join(target_bytes(*t) for t in targets)
    return raw + EMPTY_TARGET * (3 - len(targets))


def ld2450_frame(targets) -> bytes:
    return FRAME_HEADER + targets_block(targets) + FRAME_TAIL


def command(word: int, value: bytes = b"") -> bytes:
    payload = struct.pack("<H", word) + value
    return CMD_HEADER + struct.pack("<H", len(payload)) + payload + CMD_TAIL


def header(flags: int, seq: int, t_ms: int) -> bytes:
    return struct.pack("<BBHI", 1, flags, seq & 0xFFFF, t_ms & 0xFFFFFFFF)


def tlv(kind: int, payload: bytes) -> bytes:
    return struct.pack("<BB", kind, len(payload)) + payload


def radar_section(radar_id: int, t_ms: int, targets) -> bytes:
    return tlv(TLV_RADAR, struct.pack("<BI", radar_id, t_ms) + targets_block(targets))


def imu_section(imu_id: int, t_first_ms: int, samples, gyro_sums) -> bytes:
    body = struct.pack("<BIB", imu_id, t_first_ms, len(samples))
    for sample in samples:
        body += struct.pack("<6h", *sample)
    body += struct.pack("<3I", *[s & 0xFFFFFFFF for s in gyro_sums])
    return tlv(TLV_IMU, body)


def status_section(entries) -> bytes:
    body = b"".join(struct.pack("<BHBB", *e) for e in entries)
    return tlv(TLV_STATUS, body)


def link_section(interval_units: int, latency: int, supervision_units: int) -> bytes:
    return tlv(TLV_LINK, struct.pack("<HHH", interval_units, latency, supervision_units))


OFFICIAL_TARGET = (-782, 1713, -16, 320)
RIGHT_TARGET = (500, 3000, 25, 360)
IMU_SAMPLES_A = [(0, 0, 4096, 10, -5, 3), (1, -1, 4095, 11, -5, 3), (2, -2, 4094, 12, -4, 2),
                 (-1, 1, 4097, 9, -6, 4), (0, 0, 4096, 10, -5, 3)]
IMU_SAMPLES_B = [(10, 20, 4090, -3, 0, 655), (11, 21, 4091, -3, 1, 656), (12, 22, 4092, -2, 1, 657),
                 (13, 23, 4093, -2, 0, 658), (14, 24, 4094, -1, 0, 659)]


def build_vectors():
    vectors = {}

    vectors["sign_magnitude"] = [
        {"raw": 0x0000, "value": 0},
        {"raw": 0x030E, "value": -782},
        {"raw": 0x86B1, "value": 1713},
        {"raw": 0x0010, "value": -16},
        {"raw": 0x8010, "value": 16},
        {"raw": 0x8000, "value": 0},
        {"raw": 0xFFFF, "value": 32767},
        {"raw": 0x7FFF, "value": -32767},
    ]

    official = ld2450_frame([OFFICIAL_TARGET])
    vectors["ld2450_official_frame"] = {
        "hex": official.hex(),
        "targets": [{"x_mm": -782, "y_mm": 1713, "speed_cms": -16, "resolution_mm": 320}],
    }

    vectors["ld2450_commands"] = {
        "enable_config": command(0x00FF, struct.pack("<H", 0x0001)).hex(),
        "end_config": command(0x00FE).hex(),
        "multi_target": command(0x0090).hex(),
        "read_firmware": command(0x00A0).hex(),
        "set_baud_256000": command(0x00A1, struct.pack("<H", 0x0007)).hex(),
        "restart": command(0x00A3).hex(),
        "bluetooth_off": command(0x00A4, struct.pack("<H", 0x0000)).hex(),
    }

    one_radar = header(0x0F, 1, 1000) + radar_section(0, 995, [OFFICIAL_TARGET])
    vectors["bundle_one_radar"] = {
        "hex": one_radar.hex(),
        "expected": {
            "version": 1, "flags": 0x0F, "seq": 1, "t_ms": 1000, "truncated": False,
            "radar_frames": [{"radar_id": 0, "t_ms": 995, "targets": [
                {"x_mm": -782, "y_mm": 1713, "speed_cms": -16, "resolution_mm": 320}]}],
            "imu_batches": [], "statuses": [],
        },
    }

    sums_a = (100, -6, 7)
    sums_b = (-20000, 3, 3_000_000_000)
    # Fill order (spec §4.2): IMU, STATUS and LINK first, then RADAR sorted by t_ms.
    typical = (
        header(0x0F, 65535, 123456)
        + imu_section(0, 123380, IMU_SAMPLES_A, sums_a)
        + imu_section(1, 123380, IMU_SAMPLES_B, sums_b)
        + status_section([(0, 2, 0, 7), (1, 0, 1, 7)])
        + radar_section(0, 123400, [OFFICIAL_TARGET])
        + radar_section(1, 123410, [RIGHT_TARGET])
    )
    assert len(typical) == 242, len(typical)
    vectors["bundle_typical"] = {
        "hex": typical.hex(),
        "size": len(typical),
        "expected": {
            "version": 1, "flags": 0x0F, "seq": 65535, "t_ms": 123456, "truncated": False,
            "radar_frames": [
                {"radar_id": 0, "t_ms": 123400, "targets": [
                    {"x_mm": -782, "y_mm": 1713, "speed_cms": -16, "resolution_mm": 320}]},
                {"radar_id": 1, "t_ms": 123410, "targets": [
                    {"x_mm": 500, "y_mm": 3000, "speed_cms": 25, "resolution_mm": 360}]},
            ],
            "imu_batches": [
                {"imu_id": 0, "t_first_ms": 123380, "samples": [list(s) for s in IMU_SAMPLES_A],
                 "gyro_sums_u32": [s & 0xFFFFFFFF for s in sums_a]},
                {"imu_id": 1, "t_first_ms": 123380, "samples": [list(s) for s in IMU_SAMPLES_B],
                 "gyro_sums_u32": [s & 0xFFFFFFFF for s in sums_b]},
            ],
            "statuses": [
                {"radar_id": 0, "bad_frames": 2, "restarts": 0, "baud_index": 7},
                {"radar_id": 1, "bad_frames": 0, "restarts": 1, "baud_index": 7},
            ],
        },
    }

    with_link = header(0x0F, 9, 7000) + link_section(36, 0, 500) + radar_section(0, 6990, [OFFICIAL_TARGET])
    vectors["bundle_with_link"] = {
        "hex": with_link.hex(),
        "size": len(with_link),
        "expected": {
            "version": 1, "flags": 0x0F, "seq": 9, "t_ms": 7000, "truncated": False,
            "link": {"interval_units": 36, "latency": 0, "supervision_units": 500},
            "radar_frames": [{"radar_id": 0, "t_ms": 6990, "targets": [
                {"x_mm": -782, "y_mm": 1713, "speed_cms": -16, "resolution_mm": 320}]}],
            "imu_batches": [], "statuses": [],
        },
    }

    unknown = header(0x03, 7, 5000) + tlv(0x7E, bytes([1, 2, 3])) + radar_section(1, 4990, [RIGHT_TARGET])
    vectors["bundle_unknown_tlv"] = {
        "hex": unknown.hex(),
        "expected_radar_ids": [1],
    }

    full_radar = radar_section(0, 4990, [OFFICIAL_TARGET])
    truncated = header(0x01, 8, 5100) + full_radar[:12]
    vectors["bundle_truncated"] = {
        "hex": truncated.hex(),
        "expected": {"truncated": True, "radar_frames": 0},
    }

    return vectors


def c_array(name: str, data: bytes) -> str:
    body = ", ".join(f"0x{b:02X}" for b in data)
    return f"static const uint8_t {name}[] = {{{body}}};\nstatic const size_t {name}_LEN = {len(data)};\n"


def write_c_header(vectors) -> str:
    lines = [
        "// Generated by protocol/tools/make_vectors.py. Do not edit by hand.",
        "#pragma once",
        "#include <stddef.h>",
        "#include <stdint.h>",
        "",
        c_array("VEC_LD2450_OFFICIAL_FRAME", bytes.fromhex(vectors["ld2450_official_frame"]["hex"])),
    ]
    for key, hex_value in vectors["ld2450_commands"].items():
        lines.append(c_array(f"VEC_CMD_{key.upper()}", bytes.fromhex(hex_value)))
    for key in ["bundle_one_radar", "bundle_typical", "bundle_with_link", "bundle_unknown_tlv", "bundle_truncated"]:
        lines.append(c_array(f"VEC_{key.upper()}", bytes.fromhex(vectors[key]["hex"])))
    return "\n".join(lines)


def main():
    vectors = build_vectors()
    json_path = ROOT / "protocol" / "vectors" / "vectors.json"
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(vectors, indent=2) + "\n", encoding="utf-8", newline="\n")
    header_path = ROOT / "firmware" / "test" / "vectors.h"
    header_path.parent.mkdir(parents=True, exist_ok=True)
    header_path.write_text(write_c_header(vectors) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {json_path.relative_to(ROOT)} and {header_path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 3: Regenerate the vectors and check the new layout**

Run (from the repo root): `python protocol/tools/make_vectors.py && python -c "import json; v = json.load(open('protocol/vectors/vectors.json')); t = bytes.fromhex(v['bundle_typical']['hex']); l = bytes.fromhex(v['bundle_with_link']['hex']); print(len(t), t[8], t[88], t[168], t[180], t[211], len(l), l[8:16].hex())"`
Expected: `wrote protocol/vectors/vectors.json and firmware/test/vectors.h` (path separators may be `\` on Windows), then exactly `242 2 2 3 1 1 47 040624000000f401` (section types at bytes 8, 88, 168, 180 and 211 of `bundle_typical` are IMU, IMU, STATUS, RADAR, RADAR; the LINK section of `bundle_with_link` is `04 06 24 00 00 00 F4 01`).

- [ ] **Step 4: Write `firmware/platformio.ini`**

```ini
[platformio]
default_envs = esp32dev

[env]
test_framework = unity

[env:esp32dev]
platform = espressif32
board = esp32dev
framework = arduino
lib_deps = h2zero/NimBLE-Arduino@^2
monitor_speed = 115200
build_unflags = -std=gnu++11
build_flags =
    -std=gnu++17
    -D CONFIG_BT_NIMBLE_MAX_CONNECTIONS=1
    -D CONFIG_BT_NIMBLE_MSYS1_BLOCK_COUNT=24

[env:native]
platform = native
build_flags =
    -std=gnu++17
    -Wall
    -Wextra
```

- [ ] **Step 5: Write `firmware/include/blindside_config.h`**

```cpp
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace config {

constexpr char kFirmwareVersion[] = "0.1.0";
constexpr uint32_t kSerialBaud = 115200;
constexpr size_t kSerialTxBufferBytes = 1024;

constexpr int8_t kRadarARxPin = 16;
constexpr int8_t kRadarATxPin = 17;
// UART1's default pins (9/10) belong to the SPI flash, so radar B goes through the GPIO matrix.
constexpr int8_t kRadarBRxPin = 26;
constexpr int8_t kRadarBTxPin = 27;
constexpr size_t kRadarRxBufferBytes = 2048;
constexpr uint32_t kRadarAckTimeoutMs = 300;
constexpr uint32_t kRadarRestartGapMs = 100;
constexpr uint32_t kRadarReadTimeoutMs = 20;

constexpr int kImuASdaPin = 32;
constexpr int kImuASclPin = 33;
constexpr int kImuBSdaPin = 21;
constexpr int kImuBSclPin = 22;
constexpr uint32_t kI2cFrequencyHz = 100000;
constexpr uint16_t kI2cTimeoutMs = 4;
// IMU ticks sit this far behind the task's wakes, so FreeRTOS tick jitter never makes a tick look skipped.
constexpr uint32_t kImuGridLagMs = 2;

constexpr int kStatusLedPin = 2;
constexpr int kBootButtonPin = 0;

constexpr uint32_t kInfoRefreshMs = 1000;
constexpr uint32_t kDiagnosticsPeriodMs = 5000;
constexpr uint32_t kLoopPeriodMs = 5;

constexpr int kSensorCore = 1;
constexpr uint32_t kRadarTaskPriority = 6;
constexpr uint32_t kImuTaskPriority = 5;
constexpr uint32_t kBundlerTaskPriority = 3;
constexpr uint32_t kTaskStackBytes = 6144;
constexpr size_t kRadarQueueDepth = 32;
constexpr size_t kImuQueueDepth = 32;

constexpr char kServiceUuid[] = "569f3867-024f-4498-a979-90a762ad3593";
constexpr char kStreamUuid[] = "37869398-ecc2-4915-90a1-13d39d708ad5";
constexpr char kInfoUuid[] = "278b9369-d8ac-4eda-868b-7bfd0dea5dc6";
constexpr char kControlUuid[] = "725c9a6e-0c7b-45d2-bef6-48c03be7c092";

// BLE units: connection interval 1.25 ms, supervision timeout 10 ms, advertising interval 0.625 ms.
constexpr uint16_t kConnIntervalMinUnits = 24;
constexpr uint16_t kConnIntervalMaxUnits = 40;
constexpr uint16_t kConnLatency = 0;
constexpr uint16_t kSupervisionTimeoutUnits = 400;
constexpr uint16_t kDataLengthOctets = 251;
constexpr uint16_t kPreferredMtu = 255;
constexpr int8_t kBleTxPowerDbm = 9;
constexpr uint16_t kFastAdvertisingMinUnits = 32;
constexpr uint16_t kFastAdvertisingMaxUnits = 80;
constexpr uint16_t kSlowAdvertisingMinUnits = 160;
constexpr uint16_t kSlowAdvertisingMaxUnits = 320;
// The classic ESP32 resolves peer RPAs in the host, so the controller filter may never match the watch (spike S10).
constexpr bool kConnectWhitelistOnly = false;
// Spike S12: false switches to plan B (LE SC Just Works inside the window, `control` relaxed to WRITE_ENC).
constexpr bool kRequireMitm = true;

constexpr char kPreferencesNamespace[] = "blindside";
constexpr char kPasskeyKey[] = "passkey";

}  // namespace config
```

- [ ] **Step 6: Write the stub `firmware/src/main.cpp`**

```cpp
#include <Arduino.h>

#include "blindside_config.h"

void setup() {
    Serial.begin(config::kSerialBaud);
    Serial.printf("blindside fw %s\n", config::kFirmwareVersion);
}

void loop() {}
```

- [ ] **Step 7: Write `firmware/test/test_entry.h`**

```cpp
#pragma once

int run_all_tests();

#ifdef ARDUINO
#include <Arduino.h>

void setup() {
    delay(2000);  // lets the serial monitor attach before Unity prints
    run_all_tests();
}

void loop() {}
#else
int main() {
    return run_all_tests();
}
#endif
```

- [ ] **Step 8: Write the sanity suite `firmware/test/test_vectors_sanity/test_main.cpp`**

```cpp
#include <unity.h>

#include "../test_entry.h"
#include "../vectors.h"

void setUp() {}
void tearDown() {}

void test_generated_vectors_have_expected_sizes() {
    TEST_ASSERT_EQUAL_UINT(30, VEC_LD2450_OFFICIAL_FRAME_LEN);
    TEST_ASSERT_EQUAL_UINT(39, VEC_BUNDLE_ONE_RADAR_LEN);
    TEST_ASSERT_EQUAL_UINT(242, VEC_BUNDLE_TYPICAL_LEN);
    TEST_ASSERT_EQUAL_UINT(47, VEC_BUNDLE_WITH_LINK_LEN);
    TEST_ASSERT_EQUAL_UINT(14, VEC_CMD_ENABLE_CONFIG_LEN);
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_generated_vectors_have_expected_sizes);
    return UNITY_END();
}
```

- [ ] **Step 9: Write the local runner `firmware/tools/run_native_tests.py`**

```python
# Native Unity suites without an installed compiler (python -m pip install --user ziglang); CI stays authoritative.
import pathlib
import subprocess
import sys
import urllib.request

FIRMWARE = pathlib.Path(__file__).resolve().parents[1]
BUILD = FIRMWARE / ".pio" / "native-runner"
UNITY_VERSION = "v2.6.0"
UNITY_FILES = ("unity.c", "unity.h", "unity_internals.h")
UNITY_URL = "https://raw.githubusercontent.com/ThrowTheSwitch/Unity/{version}/src/{name}"


def zig(*args):
    return [sys.executable, "-m", "ziglang", *[str(a) for a in args]]


def fetch_unity():
    target = BUILD / f"unity-{UNITY_VERSION}"
    target.mkdir(parents=True, exist_ok=True)
    for name in UNITY_FILES:
        path = target / name
        if not path.exists():
            urllib.request.urlretrieve(UNITY_URL.format(version=UNITY_VERSION, name=name), path)
    return target


def library_dirs():
    libraries = FIRMWARE / "lib"
    return sorted(path for path in libraries.iterdir() if path.is_dir()) if libraries.exists() else []


def library_sources():
    return sorted((FIRMWARE / "lib").rglob("*.cpp"))


def selected_suites(names):
    suites = sorted(path for path in (FIRMWARE / "test").iterdir() if (path / "test_main.cpp").exists())
    return [suite for suite in suites if not names or suite.name in names]


def compile_unity(unity):
    obj = BUILD / "unity.o"
    subprocess.run(zig("cc", "-c", unity / "unity.c", "-I", unity, "-o", obj), check=True)
    return obj


def include_flags(unity):
    folders = [unity, FIRMWARE / "include", FIRMWARE / "test", *library_dirs()]
    return [flag for folder in folders for flag in ("-I", folder)]


def build_suite(suite, unity, unity_obj):
    exe = BUILD / f"{suite.name}.exe"
    # Zig's bundled libc++ headers trip clang's nullability warning on Windows; it is noise, not our code.
    command = zig("c++", "-std=gnu++17", "-Wall", "-Wextra", "-Wno-nullability-completeness", *include_flags(unity),
                  suite / "test_main.cpp", *library_sources(), unity_obj, "-o", exe)
    return exe if subprocess.run(command).returncode == 0 else None


def run_suite(suite, exe):
    print(f"== {suite.name}", flush=True)
    if exe is None:
        print("build failed")
        return False
    return subprocess.run([str(exe)]).returncode == 0


def main(names):
    unity = fetch_unity()
    unity_obj = compile_unity(unity)
    results = [run_suite(suite, build_suite(suite, unity, unity_obj)) for suite in selected_suites(names)]
    print(f"{results.count(True)} suites passed, {results.count(False)} failed")
    return 0 if results and all(results) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
```

- [ ] **Step 10: Run the sanity suite locally and compile-check the stub firmware**

Run (from `firmware/`): `python tools/run_native_tests.py test_vectors_sanity`
Expected: `1 Tests 0 Failures 0 Ignored` and `1 suites passed, 0 failed` (the first run downloads Unity).
Run: `python -m platformio run -e esp32dev`
Expected: `SUCCESS` (the first run downloads the espressif32 platform and toolchain, ~5 min).
Run: `python -m platformio test -e esp32dev -f test_vectors_sanity --without-uploading --without-testing`
Expected: `esp32dev:test_vectors_sanity [SKIPPED]`.

- [ ] **Step 11: Write `protocol/PROTOCOL.md`**

````markdown
# Blindside BLE protocol, version 1

Normative description of the link between the belt (ESP32, `firmware/`) and the watch (`watch/`).
Sources: spec §4.2-§4.3 (`docs/superpowers/specs/2026-09-30-blindside-v1-design.md`) and the shared
contracts (`docs/superpowers/plans/2026-09-30-blindside-mvp-00-contracts.md`). If this file and the
contracts disagree, the contracts win and this file is fixed.

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
- One connection at a time. While a peer is connected the belt does not advertise.
- The belt proposes ATT MTU 255; the watch requests 517 (Android 14+), so the result is 255.
  Payload limit per notification: `min(MTU − 3, 244)` bytes.
- After connecting the belt asks for interval 30-50 ms, latency 0, supervision timeout 4 s
  (`updateConnParams(h, 24, 40, 0, 400)`), data length 251, and starts security.
- The belt sends `stream` notifications only when all of these hold: the link is encrypted with the
  bonded (trusted) peer, notifications are enabled in the CCCD, and the ATT MTU is ≥ 247
  (payload limit 244). Below MTU 247 nothing is notified.

## 2. Advertising and connection filtering

- Advertising data: flags + the 128-bit service UUID (the watch scans with a `ScanFilter` on it).
- Scan response: complete local name `Blindside-XXXX`, the last two bytes of the ESP32 Bluetooth MAC
  in upper-case hex.
- Interval 20-50 ms during the first 30 s after boot or after a disconnection, 100-200 ms afterwards.
- The bonded identity address is kept in the controller filter-accept list. The "connect from the
  list only" policy is enabled only when `config::kConnectWhitelistOnly` is true (spike S10).
- Whatever the filter does, the host enforces the bond:
  - outside the pairing window, a peer whose identity address is not bonded is disconnected as soon
    as the firmware sees the connection (no grace period);
  - the bonded watch gets 5 s to encrypt; a peer connected while the window is open gets 60 s to bond;
  - a bond created by anyone other than the window's first successful pairing is deleted.

## 3. Pairing

- LE Secure Connections and bonding; the belt distributes and requests the encryption key and the
  identity key (IRK).
  - Default (S12 passes): MITM with a passkey, IO capability DISPLAY_ONLY.
  - S12 plan B (`config::kRequireMitm = false`): LE SC Just Works, accepted only inside the pairing
    window; `control` becomes WRITE_ENC. Known weakness: whoever connects during the window can bond.
- Passkey: 6 random digits created on first boot, stored in NVS (`blindside/passkey`), printed on
  the serial port at every boot and by the serial command `key`. Only the serial command `key new`
  replaces it (it also changes after a full flash or NVS erase). Never in source code or build flags.
- Pairing window (LED blinking): opens at boot when there is no bond, or with the BOOT gesture below;
  lasts 60 s; closes after 60 s or on the first successful bond. A new bond replaces the old one.
- BOOT gestures count only during the first 60 s after power-on and act **on release**:
  - held 3-10 s: opens the pairing window;
  - held 10 s or more: erases every bond and nothing else (the passkey stays). With no bond left, the
    window opens.
  - A gesture disconnects the current peer, because the belt does not advertise while connected.

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
  readings per slot, and samples stay on the grid. Repeats are counted per IMU (serial diagnostics).
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
connection-parameter update reported by NimBLE and once after the watch enables notifications.
If a cut cannot carry it, it goes in the next one.

Types 0x10-0x1F are reserved (0x10 = thermal camera). Unknown types are skipped using `len`.

### 4.3 Cuts, ordering and splitting

- Every 100 ms the belt makes a cut: RADAR frames with `t_ms` < cut time − 5 ms, all completed IMU
  samples, STATUS when due and LINK when pending.
- Fill order inside a cut: IMU A, IMU B, STATUS, LINK, then the RADAR frames of both radars in
  ascending `t_ms`. So every RADAR frame in packet k has `t_ms` ≤ those in packet k+1, and a cut's
  IMU data never arrives after its radar frames.
- If a cut does not fit in one packet it is split into consecutive packets (consecutive `seq`, same
  header `t_ms` and `flags`), each filled in that order before the next one opens. A section is never
  split.
- A cut with nothing to send still produces one header-only packet (heartbeat). Readers must accept a
  packet with zero sections.

### 4.4 Loss and drops

- `seq` gaps mean packets that were never sent (disconnection). Lost packets =
  `((seq − seq_prev) mod 65536) − 1`, unsigned arithmetic.
- If `t_ms` goes backwards, or `boot_id` in `info` changes, the ESP32 rebooted: reset the reference.
- When the BLE notification queue stays full, the belt keeps up to ~1 s of pending data. Beyond that
  it drops the oldest RADAR frames first, then the oldest IMU samples, counts them, and sets `flags`
  bit4 on the next cut. Samples or frames lost inside the belt (an internal queue overflow) also set
  bit4.

### 4.5 Reader rules

- Skip unknown section types using `len`.
- If `len` exceeds the bytes left in the packet, drop the rest of the packet and count it as truncated.

## 5. `info` (UTF-8 JSON, ≤ 400 B, refreshed every second)

```json
{"proto":1,"fw":"0.1.0","boot_id":"9f3a12c4","reset":"POWERON","mtu":255,"radars":[{"id":0,"fw":"V2.04.23101915","baud":256000},{"id":1,"fw":"V2.04.23101915","baud":256000}],"imus":[{"id":0,"who":104,"gyro_lsb_dps":65.5,"accel_lsb_g":4096},{"id":1,"who":112,"gyro_lsb_dps":65.5,"accel_lsb_g":4096}],"tx_power_dbm":9,"conn":{"interval_ms":45.0,"latency":0,"timeout_ms":5000},"uptime_s":42}
```

- `boot_id`: random 32-bit value per boot, 8 lower-case hex digits.
- `reset`: one of `UNKNOWN`, `POWERON`, `EXT`, `SW`, `PANIC`, `INT_WDT`, `TASK_WDT`, `WDT`,
  `DEEPSLEEP`, `BROWNOUT`, `SDIO`.
- A radar not detected (or still being configured) has `"fw":""` and `"baud":0`. An IMU never found
  has `"who":0`.
- `tx_power_dbm`: `NimBLEDevice::getPower()`, expected 9.
- `conn`: the last parameters NimBLE reported (`interval_ms` = units × 1.25 with one decimal,
  `timeout_ms` = units × 10); all zero before the first connection.
- The per-IMU repeat counts of spec §4.2 do not fit in 400 B next to the contract fields; they are on
  the serial diagnostics line (`rep=`).

## 6. `control` writes

| Bytes | Meaning |
|---|---|
| `01 <id>` | Restart radar `<id>` (0 or 1): enable configuration, then restart (0x00A3) 100 ms later |
| `03` | IDENTIFY: blink the LED 3 times (1.2 s). Ignored while a session is active |
| `04 <0/1>` | SESSION_ACTIVE: the watch writes 1 when a session starts and 0 when it stops. The value persists across disconnections until written again or until the ESP32 reboots |

Any other content (wrong length, radar id > 1, unknown command, including `02 …` which is not part
of the MVP) is ignored.
````

- [ ] **Step 12: Write `.github/workflows/ci.yml`**

```yaml
name: ci

on:
  push:
    branches: [main]
  pull_request:

jobs:
  vectors:
    name: shared vectors match the generator
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: '3.12'
      - run: python protocol/tools/make_vectors.py
      - run: git diff --exit-code -- protocol/vectors firmware/test/vectors.h

  firmware:
    name: firmware native tests and esp32dev build
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: firmware
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: '3.12'
      - uses: actions/cache@v4
        with:
          path: |
            ~/.platformio/.cache
            ~/.platformio/packages
            ~/.platformio/platforms
          key: pio-${{ runner.os }}-${{ hashFiles('firmware/platformio.ini') }}
      - run: pip install --upgrade platformio
      - run: pio test -e native
      - run: pio run -e esp32dev

  radar-core:
    name: radar-core unit tests
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - id: scaffold
        run: |
          if [ -f watch/gradlew ]; then
            echo "present=true" >> "$GITHUB_OUTPUT"
          else
            echo "present=false" >> "$GITHUB_OUTPUT"
            echo "::notice::watch/gradlew not present yet (plan 01 Task 1); skipping"
          fi
      - if: steps.scaffold.outputs.present == 'true'
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - if: steps.scaffold.outputs.present == 'true'
        uses: gradle/actions/setup-gradle@v4
      - if: steps.scaffold.outputs.present == 'true'
        working-directory: watch
        run: chmod +x gradlew && ./gradlew :radar-core:test --no-daemon

  wear-app:
    name: wear-app assembleDebug
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - id: scaffold
        run: |
          if [ -f watch/gradlew ] && [ -f watch/wear-app/build.gradle.kts ]; then
            echo "present=true" >> "$GITHUB_OUTPUT"
          else
            echo "present=false" >> "$GITHUB_OUTPUT"
            echo "::notice::watch/wear-app not present yet (plan 03); skipping"
          fi
      - if: steps.scaffold.outputs.present == 'true'
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - if: steps.scaffold.outputs.present == 'true'
        uses: android-actions/setup-android@v3
      - if: steps.scaffold.outputs.present == 'true'
        uses: gradle/actions/setup-gradle@v4
      - if: steps.scaffold.outputs.present == 'true'
        working-directory: watch
        run: chmod +x gradlew && ./gradlew :wear-app:assembleDebug --no-daemon
```

- [ ] **Step 13: Commit**

```bash
git -C C:/personal/blindside add protocol/tools/make_vectors.py protocol/vectors/vectors.json firmware/test/vectors.h firmware/platformio.ini firmware/include/blindside_config.h firmware/src/main.cpp firmware/test/test_entry.h firmware/test/test_vectors_sanity/test_main.cpp firmware/tools/run_native_tests.py protocol/PROTOCOL.md .github/workflows/ci.yml
git -C C:/personal/blindside commit -m "feat: esqueleto del firmware, vectores en el orden del contrato, runner local, CI y PROTOCOL.md"
```

- [ ] **Step 14: Verify the committed vectors match the generator**

Run (from the repo root): `python protocol/tools/make_vectors.py && git -C C:/personal/blindside diff --exit-code -- protocol/vectors firmware/test/vectors.h`
Expected: exit code 0, no diff (this is what CI's `vectors` job checks).

- [ ] **Step 15: Run CI (only with authorisation)**

`origin` already exists. Pushing needs Santiago's explicit authorisation in this session; without it, record "CI pending: checklist H0" in the task report and continue (the local runner already ran the suite). With it: `unset GITHUB_TOKEN` (on this machine it belongs to a work account), `git -C C:/personal/blindside push -u origin main`, then `gh run watch`. Expected: jobs `vectors` and `firmware` green; `radar-core` and `wear-app` green with a "skipping" notice until plans 01/03 land.

---

### Task 2: LD2450 frame synchroniser (`ld2450_frame`)

**Files:**
- Create: `firmware/lib/ld2450/ld2450_frame.h`
- Create: `firmware/lib/ld2450/ld2450_frame.cpp`
- Test: `firmware/test/test_ld2450_frame/test_main.cpp`

**Interfaces:**
- Consumes: `VEC_LD2450_OFFICIAL_FRAME` from `test/vectors.h`.
- Produces (used by Task 12):
  - `constexpr size_t kLd2450FrameSize = 30; kLd2450HeaderSize = 4; kLd2450TargetsSize = 24;`
  - `enum class FrameEvent : uint8_t { None, FrameOk, FrameBad };`
  - `struct FrameParser { uint8_t buffer[30]; uint8_t length; uint32_t header_t_ms; };`
  - `struct ParsedFrame { uint32_t t_ms; uint8_t targets[24]; };`
  - `struct ParserStep { FrameParser parser; FrameEvent event; ParsedFrame frame; };`
  - `FrameParser frame_parser_start();`
  - `ParserStep frame_parser_feed(const FrameParser& parser, uint8_t byte, uint32_t now_ms);`
  - `size_t header_resume_offset(const uint8_t* bytes, size_t length);`
  - `uint16_t bad_frames_after_one_more(uint16_t bad_frames);` (saturates at 0xFFFF)

- [ ] **Step 1: Write the failing tests `firmware/test/test_ld2450_frame/test_main.cpp`**

```cpp
#include <string.h>
#include <unity.h>

#include "../test_entry.h"
#include "../vectors.h"
#include "ld2450_frame.h"

namespace {

struct FeedOutcome {
    FrameParser parser;
    int ok_count;
    int bad_count;
    ParsedFrame last_frame;
};

FeedOutcome tallied(const FeedOutcome& outcome, const ParserStep& step) {
    FeedOutcome next = outcome;
    next.parser = step.parser;
    next.ok_count += step.event == FrameEvent::FrameOk ? 1 : 0;
    next.bad_count += step.event == FrameEvent::FrameBad ? 1 : 0;
    if (step.event == FrameEvent::FrameOk) {
        next.last_frame = step.frame;
    }
    return next;
}

FeedOutcome feed_bytes(const FeedOutcome& start, const uint8_t* bytes, size_t length, uint32_t t_ms) {
    FeedOutcome outcome = start;
    for (size_t i = 0; i < length; ++i) {
        outcome = tallied(outcome, frame_parser_feed(outcome.parser, bytes[i], t_ms));
    }
    return outcome;
}

FeedOutcome fresh_outcome() {
    FeedOutcome outcome{};
    outcome.parser = frame_parser_start();
    return outcome;
}

}  // namespace

void setUp() {}
void tearDown() {}

void test_official_frame_yields_one_frame_with_its_targets() {
    FeedOutcome outcome = feed_bytes(fresh_outcome(), VEC_LD2450_OFFICIAL_FRAME, VEC_LD2450_OFFICIAL_FRAME_LEN, 500);
    TEST_ASSERT_EQUAL_INT(1, outcome.ok_count);
    TEST_ASSERT_EQUAL_INT(0, outcome.bad_count);
    TEST_ASSERT_EQUAL_UINT32(500, outcome.last_frame.t_ms);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(VEC_LD2450_OFFICIAL_FRAME + 4, outcome.last_frame.targets, 24);
}

void test_frame_time_is_taken_when_the_header_completes() {
    FeedOutcome outcome = feed_bytes(fresh_outcome(), VEC_LD2450_OFFICIAL_FRAME, 3, 100);
    outcome = feed_bytes(outcome, VEC_LD2450_OFFICIAL_FRAME + 3, 1, 101);
    outcome = feed_bytes(outcome, VEC_LD2450_OFFICIAL_FRAME + 4, VEC_LD2450_OFFICIAL_FRAME_LEN - 4, 140);
    TEST_ASSERT_EQUAL_INT(1, outcome.ok_count);
    TEST_ASSERT_EQUAL_UINT32(101, outcome.last_frame.t_ms);
}

void test_garbage_before_frame_is_skipped() {
    const uint8_t garbage[] = {0x00, 0x55, 0xAA, 0x12, 0xAA, 0xFF, 0x01, 0xCC};
    FeedOutcome outcome = feed_bytes(fresh_outcome(), garbage, sizeof(garbage), 10);
    outcome = feed_bytes(outcome, VEC_LD2450_OFFICIAL_FRAME, VEC_LD2450_OFFICIAL_FRAME_LEN, 20);
    TEST_ASSERT_EQUAL_INT(1, outcome.ok_count);
    TEST_ASSERT_EQUAL_INT(0, outcome.bad_count);
}

void test_bad_tail_counts_a_bad_frame_then_recovers() {
    uint8_t broken[30];
    memcpy(broken, VEC_LD2450_OFFICIAL_FRAME, sizeof(broken));
    broken[29] = 0x00;
    FeedOutcome outcome = feed_bytes(fresh_outcome(), broken, sizeof(broken), 10);
    outcome = feed_bytes(outcome, VEC_LD2450_OFFICIAL_FRAME, VEC_LD2450_OFFICIAL_FRAME_LEN, 110);
    TEST_ASSERT_EQUAL_INT(1, outcome.bad_count);
    TEST_ASSERT_EQUAL_INT(1, outcome.ok_count);
}

void test_dropped_byte_loses_only_that_frame() {
    uint8_t short_frame[29];
    memcpy(short_frame, VEC_LD2450_OFFICIAL_FRAME, 10);
    memcpy(short_frame + 10, VEC_LD2450_OFFICIAL_FRAME + 11, 19);
    FeedOutcome outcome = feed_bytes(fresh_outcome(), short_frame, sizeof(short_frame), 10);
    outcome = feed_bytes(outcome, VEC_LD2450_OFFICIAL_FRAME, VEC_LD2450_OFFICIAL_FRAME_LEN, 110);
    TEST_ASSERT_EQUAL_INT(1, outcome.bad_count);
    TEST_ASSERT_EQUAL_INT(1, outcome.ok_count);
    TEST_ASSERT_EQUAL_UINT32(110, outcome.last_frame.t_ms);
}

void test_two_back_to_back_frames_both_parse() {
    FeedOutcome outcome = feed_bytes(fresh_outcome(), VEC_LD2450_OFFICIAL_FRAME, VEC_LD2450_OFFICIAL_FRAME_LEN, 10);
    outcome = feed_bytes(outcome, VEC_LD2450_OFFICIAL_FRAME, VEC_LD2450_OFFICIAL_FRAME_LEN, 110);
    TEST_ASSERT_EQUAL_INT(2, outcome.ok_count);
}

void test_resume_offset_finds_a_partial_header_at_the_end() {
    const uint8_t tail_header[] = {0x01, 0x02, 0xAA, 0xFF};
    const uint8_t no_header[] = {0x01, 0x02};
    const uint8_t repeated[] = {0xAA, 0xAA, 0xFF, 0x03};
    TEST_ASSERT_EQUAL_UINT(2, header_resume_offset(tail_header, sizeof(tail_header)));
    TEST_ASSERT_EQUAL_UINT(2, header_resume_offset(no_header, sizeof(no_header)));
    TEST_ASSERT_EQUAL_UINT(1, header_resume_offset(repeated, sizeof(repeated)));
}

void test_bad_frame_counter_saturates() {
    TEST_ASSERT_EQUAL_UINT16(1, bad_frames_after_one_more(0));
    TEST_ASSERT_EQUAL_UINT16(0xFFFF, bad_frames_after_one_more(0xFFFE));
    TEST_ASSERT_EQUAL_UINT16(0xFFFF, bad_frames_after_one_more(0xFFFF));
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_official_frame_yields_one_frame_with_its_targets);
    RUN_TEST(test_frame_time_is_taken_when_the_header_completes);
    RUN_TEST(test_garbage_before_frame_is_skipped);
    RUN_TEST(test_bad_tail_counts_a_bad_frame_then_recovers);
    RUN_TEST(test_dropped_byte_loses_only_that_frame);
    RUN_TEST(test_two_back_to_back_frames_both_parse);
    RUN_TEST(test_resume_offset_finds_a_partial_header_at_the_end);
    RUN_TEST(test_bad_frame_counter_saturates);
    return UNITY_END();
}
```

- [ ] **Step 2: Run the suite to verify it fails**

Run (from `firmware/`): `python tools/run_native_tests.py test_ld2450_frame`
Expected: the build stops with `fatal error: 'ld2450_frame.h' file not found` (`pio test` says `fatal error: ld2450_frame.h: No such file or directory`) and the runner ends with `build failed` and `0 suites passed, 1 failed`.

- [ ] **Step 3: Write `firmware/lib/ld2450/ld2450_frame.h`**

```cpp
#pragma once

#include <stddef.h>
#include <stdint.h>

constexpr size_t kLd2450FrameSize = 30;
constexpr size_t kLd2450HeaderSize = 4;
constexpr size_t kLd2450TargetsSize = 24;

enum class FrameEvent : uint8_t { None, FrameOk, FrameBad };

struct FrameParser {
    uint8_t buffer[kLd2450FrameSize];
    uint8_t length;
    uint32_t header_t_ms;
};

struct ParsedFrame {
    uint32_t t_ms;
    uint8_t targets[kLd2450TargetsSize];
};

struct ParserStep {
    FrameParser parser;
    FrameEvent event;
    ParsedFrame frame;
};

FrameParser frame_parser_start();
ParserStep frame_parser_feed(const FrameParser& parser, uint8_t byte, uint32_t now_ms);
size_t header_resume_offset(const uint8_t* bytes, size_t length);
uint16_t bad_frames_after_one_more(uint16_t bad_frames);
```

- [ ] **Step 4: Write `firmware/lib/ld2450/ld2450_frame.cpp`**

```cpp
#include "ld2450_frame.h"

#include <string.h>

namespace {

constexpr uint8_t kHeader[kLd2450HeaderSize] = {0xAA, 0xFF, 0x03, 0x00};
constexpr uint8_t kTailFirst = 0x55;
constexpr uint8_t kTailSecond = 0xCC;
constexpr size_t kTailOffset = kLd2450FrameSize - 2;

bool starts_like_header(const uint8_t* bytes, size_t length) {
    size_t compared = length < kLd2450HeaderSize ? length : kLd2450HeaderSize;
    return memcmp(bytes, kHeader, compared) == 0;
}

bool has_valid_tail(const uint8_t* frame) {
    return frame[kTailOffset] == kTailFirst && frame[kTailOffset + 1] == kTailSecond;
}

FrameParser with_byte(const FrameParser& parser, uint8_t byte, uint32_t now_ms) {
    FrameParser next = parser;
    next.buffer[next.length] = byte;
    next.length = static_cast<uint8_t>(next.length + 1);
    if (next.length == kLd2450HeaderSize) {
        next.header_t_ms = now_ms;
    }
    return next;
}

FrameParser resynced(const FrameParser& parser, uint32_t now_ms) {
    size_t offset = header_resume_offset(parser.buffer, parser.length);
    FrameParser next = frame_parser_start();
    next.length = static_cast<uint8_t>(parser.length - offset);
    memcpy(next.buffer, parser.buffer + offset, next.length);
    next.header_t_ms = now_ms;
    return next;
}

ParsedFrame frame_from(const FrameParser& parser) {
    ParsedFrame frame{};
    frame.t_ms = parser.header_t_ms;
    memcpy(frame.targets, parser.buffer + kLd2450HeaderSize, kLd2450TargetsSize);
    return frame;
}

ParserStep step_with(const FrameParser& parser, FrameEvent event) {
    ParserStep step{};
    step.parser = parser;
    step.event = event;
    return step;
}

ParserStep completed_frame_step(const FrameParser& full, uint32_t now_ms) {
    if (!has_valid_tail(full.buffer)) {
        return step_with(resynced(full, now_ms), FrameEvent::FrameBad);
    }
    ParserStep step = step_with(frame_parser_start(), FrameEvent::FrameOk);
    step.frame = frame_from(full);
    return step;
}

}  // namespace

FrameParser frame_parser_start() {
    return FrameParser{};
}

size_t header_resume_offset(const uint8_t* bytes, size_t length) {
    for (size_t offset = 1; offset < length; ++offset) {
        if (starts_like_header(bytes + offset, length - offset)) {
            return offset;
        }
    }
    return length;
}

ParserStep frame_parser_feed(const FrameParser& parser, uint8_t byte, uint32_t now_ms) {
    FrameParser next = with_byte(parser, byte, now_ms);
    if (!starts_like_header(next.buffer, next.length)) {
        return step_with(resynced(next, now_ms), FrameEvent::None);
    }
    if (next.length < kLd2450FrameSize) {
        return step_with(next, FrameEvent::None);
    }
    return completed_frame_step(next, now_ms);
}

uint16_t bad_frames_after_one_more(uint16_t bad_frames) {
    return bad_frames == UINT16_MAX ? bad_frames : static_cast<uint16_t>(bad_frames + 1);
}
```

- [ ] **Step 5: Run the suite to verify it passes**

Run (from `firmware/`): `python tools/run_native_tests.py test_ld2450_frame`
Expected: `8 Tests 0 Failures 0 Ignored` and `1 suites passed, 0 failed`.
Run: `python -m platformio test -e esp32dev -f test_ld2450_frame --without-uploading --without-testing`
Expected: `esp32dev:test_ld2450_frame [SKIPPED]`. CI's `pio test -e native` is authoritative; on the board (Santiago): `pio test -e esp32dev -f test_ld2450_frame`.

- [ ] **Step 6: Commit**

```bash
git -C C:/personal/blindside add firmware/lib/ld2450/ld2450_frame.h firmware/lib/ld2450/ld2450_frame.cpp firmware/test/test_ld2450_frame/test_main.cpp
git -C C:/personal/blindside commit -m "feat: sincronizador de tramas del LD2450 con resincronización"
```

---

### Task 3: LD2450 commands, ACKs and baud table (`ld2450_commands`)

**Files:**
- Create: `firmware/lib/ld2450/ld2450_commands.h`
- Create: `firmware/lib/ld2450/ld2450_commands.cpp`
- Test: `firmware/test/test_ld2450_commands/test_main.cpp`

**Interfaces:**
- Consumes: `VEC_CMD_*` and `VEC_LD2450_OFFICIAL_FRAME` from `test/vectors.h`.
- Produces (used by Tasks 9, 12):
  - command words `kCmdEnableConfig 0x00FF`, `kCmdEndConfig 0x00FE`, `kCmdMultiTarget 0x0090`, `kCmdReadFirmware 0x00A0`, `kCmdSetBaud 0x00A1`, `kCmdRestart 0x00A3`, `kCmdBluetooth 0x00A4`
  - `constexpr uint32_t kBaudProbeOrder[8]` (256000 first) and `kBaudProbeCount`
  - `constexpr size_t kFirmwareTextSize = 24;`
  - `struct CommandBytes { uint8_t bytes[16]; uint8_t length; };`
  - `struct Ack { bool found; bool success; uint8_t value[16]; uint8_t value_length; };`
  - `struct FirmwareText { char text[24]; };`
  - `CommandBytes build_command(uint16_t word, const uint8_t* value, size_t value_length);`
  - `CommandBytes enable_config_command(); multi_target_command(); read_firmware_command(); bluetooth_off_command(); restart_command();`
  - `Ack find_ack(const uint8_t* data, size_t length, uint16_t command_word);`
  - `FirmwareText firmware_text_from_ack(const Ack& ack);`
  - `uint8_t baud_index_for(uint32_t baud);` (0 when unknown)

- [ ] **Step 1: Write the failing tests `firmware/test/test_ld2450_commands/test_main.cpp`**

```cpp
#include <string.h>
#include <unity.h>

#include "../test_entry.h"
#include "../vectors.h"
#include "ld2450_commands.h"

namespace {

const uint8_t kEnableAck[] = {0xFD, 0xFC, 0xFB, 0xFA, 0x08, 0x00, 0xFF, 0x01, 0x00,
                              0x00, 0x01, 0x00, 0x40, 0x00, 0x04, 0x03, 0x02, 0x01};
const uint8_t kFirmwareAck[] = {0xFD, 0xFC, 0xFB, 0xFA, 0x0C, 0x00, 0xA0, 0x01, 0x00, 0x00, 0x00,
                                0x00, 0x02, 0x01, 0x16, 0x24, 0x06, 0x22, 0x04, 0x03, 0x02, 0x01};
const uint8_t kFailedMultiTargetAck[] = {0xFD, 0xFC, 0xFB, 0xFA, 0x04, 0x00, 0x90,
                                         0x01, 0x01, 0x00, 0x04, 0x03, 0x02, 0x01};

void assert_command_equals(const uint8_t* expected, size_t expected_length, const CommandBytes& actual) {
    TEST_ASSERT_EQUAL_UINT(expected_length, actual.length);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(expected, actual.bytes, expected_length);
}

}  // namespace

void setUp() {}
void tearDown() {}

void test_named_commands_match_shared_vectors() {
    assert_command_equals(VEC_CMD_ENABLE_CONFIG, VEC_CMD_ENABLE_CONFIG_LEN, enable_config_command());
    assert_command_equals(VEC_CMD_MULTI_TARGET, VEC_CMD_MULTI_TARGET_LEN, multi_target_command());
    assert_command_equals(VEC_CMD_READ_FIRMWARE, VEC_CMD_READ_FIRMWARE_LEN, read_firmware_command());
    assert_command_equals(VEC_CMD_BLUETOOTH_OFF, VEC_CMD_BLUETOOTH_OFF_LEN, bluetooth_off_command());
    assert_command_equals(VEC_CMD_RESTART, VEC_CMD_RESTART_LEN, restart_command());
}

void test_generic_builder_matches_end_config_and_set_baud_vectors() {
    const uint8_t baud_256000[] = {0x07, 0x00};
    assert_command_equals(VEC_CMD_END_CONFIG, VEC_CMD_END_CONFIG_LEN, build_command(kCmdEndConfig, nullptr, 0));
    assert_command_equals(VEC_CMD_SET_BAUD_256000, VEC_CMD_SET_BAUD_256000_LEN,
                          build_command(kCmdSetBaud, baud_256000, sizeof(baud_256000)));
}

void test_builder_rejects_values_longer_than_four_bytes() {
    const uint8_t too_long[5] = {1, 2, 3, 4, 5};
    TEST_ASSERT_EQUAL_UINT(0, build_command(kCmdSetBaud, too_long, sizeof(too_long)).length);
}

void test_enable_ack_is_found_with_its_value() {
    Ack ack = find_ack(kEnableAck, sizeof(kEnableAck), kCmdEnableConfig);
    const uint8_t expected_value[] = {0x01, 0x00, 0x40, 0x00};
    TEST_ASSERT_TRUE(ack.found);
    TEST_ASSERT_TRUE(ack.success);
    TEST_ASSERT_EQUAL_UINT(4, ack.value_length);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(expected_value, ack.value, 4);
}

void test_ack_is_found_after_data_frame_noise() {
    uint8_t stream[30 + sizeof(kEnableAck)];
    memcpy(stream, VEC_LD2450_OFFICIAL_FRAME, 30);
    memcpy(stream + 30, kEnableAck, sizeof(kEnableAck));
    TEST_ASSERT_TRUE(find_ack(stream, sizeof(stream), kCmdEnableConfig).found);
}

void test_ack_for_another_command_is_not_matched() {
    TEST_ASSERT_FALSE(find_ack(kEnableAck, sizeof(kEnableAck), kCmdRestart).found);
}

void test_truncated_ack_is_not_found() {
    TEST_ASSERT_FALSE(find_ack(kEnableAck, sizeof(kEnableAck) - 1, kCmdEnableConfig).found);
}

void test_failure_status_is_reported() {
    Ack ack = find_ack(kFailedMultiTargetAck, sizeof(kFailedMultiTargetAck), kCmdMultiTarget);
    TEST_ASSERT_TRUE(ack.found);
    TEST_ASSERT_FALSE(ack.success);
}

void test_firmware_version_text_matches_hilink_example() {
    Ack ack = find_ack(kFirmwareAck, sizeof(kFirmwareAck), kCmdReadFirmware);
    TEST_ASSERT_EQUAL_STRING("V1.02.22062416", firmware_text_from_ack(ack).text);
}

void test_firmware_text_is_empty_without_a_successful_ack() {
    TEST_ASSERT_EQUAL_STRING("", firmware_text_from_ack(Ack{}).text);
}

void test_baud_index_table() {
    TEST_ASSERT_EQUAL_UINT8(1, baud_index_for(9600));
    TEST_ASSERT_EQUAL_UINT8(7, baud_index_for(256000));
    TEST_ASSERT_EQUAL_UINT8(8, baud_index_for(460800));
    TEST_ASSERT_EQUAL_UINT8(0, baud_index_for(12345));
    TEST_ASSERT_EQUAL_UINT8(0, baud_index_for(0));
}

void test_probe_order_starts_at_default_and_covers_every_rate() {
    TEST_ASSERT_EQUAL_UINT32(256000, kBaudProbeOrder[0]);
    TEST_ASSERT_EQUAL_UINT(8, kBaudProbeCount);
    for (size_t i = 0; i < kBaudProbeCount; ++i) {
        TEST_ASSERT_NOT_EQUAL(0, baud_index_for(kBaudProbeOrder[i]));
    }
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_named_commands_match_shared_vectors);
    RUN_TEST(test_generic_builder_matches_end_config_and_set_baud_vectors);
    RUN_TEST(test_builder_rejects_values_longer_than_four_bytes);
    RUN_TEST(test_enable_ack_is_found_with_its_value);
    RUN_TEST(test_ack_is_found_after_data_frame_noise);
    RUN_TEST(test_ack_for_another_command_is_not_matched);
    RUN_TEST(test_truncated_ack_is_not_found);
    RUN_TEST(test_failure_status_is_reported);
    RUN_TEST(test_firmware_version_text_matches_hilink_example);
    RUN_TEST(test_firmware_text_is_empty_without_a_successful_ack);
    RUN_TEST(test_baud_index_table);
    RUN_TEST(test_probe_order_starts_at_default_and_covers_every_rate);
    return UNITY_END();
}
```

- [ ] **Step 2: Run the suite to verify it fails**

Run (from `firmware/`): `python tools/run_native_tests.py test_ld2450_commands`
Expected: the build stops with `fatal error: 'ld2450_commands.h' file not found` and the runner ends with `build failed` and `0 suites passed, 1 failed`.

- [ ] **Step 3: Write `firmware/lib/ld2450/ld2450_commands.h`**

```cpp
#pragma once

#include <stddef.h>
#include <stdint.h>

constexpr uint16_t kCmdEnableConfig = 0x00FF;
constexpr uint16_t kCmdEndConfig = 0x00FE;
constexpr uint16_t kCmdMultiTarget = 0x0090;
constexpr uint16_t kCmdReadFirmware = 0x00A0;
constexpr uint16_t kCmdSetBaud = 0x00A1;
constexpr uint16_t kCmdRestart = 0x00A3;
constexpr uint16_t kCmdBluetooth = 0x00A4;

constexpr size_t kMaxCommandValueSize = 4;
constexpr size_t kMaxCommandSize = 12 + kMaxCommandValueSize;
constexpr size_t kMaxAckValueSize = 16;
constexpr size_t kFirmwareTextSize = 24;

constexpr uint32_t kBaudProbeOrder[] = {256000, 115200, 460800, 230400, 57600, 38400, 19200, 9600};
constexpr size_t kBaudProbeCount = sizeof(kBaudProbeOrder) / sizeof(kBaudProbeOrder[0]);

struct CommandBytes {
    uint8_t bytes[kMaxCommandSize];
    uint8_t length;
};

struct Ack {
    bool found;
    bool success;
    uint8_t value[kMaxAckValueSize];
    uint8_t value_length;
};

struct FirmwareText {
    char text[kFirmwareTextSize];
};

CommandBytes build_command(uint16_t word, const uint8_t* value, size_t value_length);
CommandBytes enable_config_command();
CommandBytes multi_target_command();
CommandBytes read_firmware_command();
CommandBytes bluetooth_off_command();
CommandBytes restart_command();
Ack find_ack(const uint8_t* data, size_t length, uint16_t command_word);
FirmwareText firmware_text_from_ack(const Ack& ack);
uint8_t baud_index_for(uint32_t baud);
```

- [ ] **Step 4: Write `firmware/lib/ld2450/ld2450_commands.cpp`**

```cpp
#include "ld2450_commands.h"

#include <stdio.h>
#include <string.h>

namespace {

constexpr uint8_t kCommandHeader[4] = {0xFD, 0xFC, 0xFB, 0xFA};
constexpr uint8_t kCommandTail[4] = {0x04, 0x03, 0x02, 0x01};
constexpr size_t kMarkerSize = 4;
constexpr size_t kLengthFieldSize = 2;
constexpr size_t kWordSize = 2;
constexpr size_t kStatusSize = 2;
constexpr size_t kValueOffset = kMarkerSize + kLengthFieldSize + kWordSize;
constexpr uint16_t kAckFlag = 0x0100;
constexpr size_t kFirmwareValueSize = 8;
constexpr uint32_t kBaudByIndex[] = {0, 9600, 19200, 38400, 57600, 115200, 230400, 256000, 460800};
constexpr uint8_t kBaudIndexCount = sizeof(kBaudByIndex) / sizeof(kBaudByIndex[0]);

void put_u16_le(uint8_t* out, uint16_t value) {
    out[0] = static_cast<uint8_t>(value & 0xFF);
    out[1] = static_cast<uint8_t>(value >> 8);
}

uint16_t get_u16_le(const uint8_t* in) {
    return static_cast<uint16_t>(in[0] | (in[1] << 8));
}

uint32_t get_u32_le(const uint8_t* in) {
    return static_cast<uint32_t>(in[0]) | (static_cast<uint32_t>(in[1]) << 8) |
           (static_cast<uint32_t>(in[2]) << 16) | (static_cast<uint32_t>(in[3]) << 24);
}

CommandBytes command_with_u16(uint16_t word, uint16_t value) {
    uint8_t bytes[2];
    put_u16_le(bytes, value);
    return build_command(word, bytes, sizeof(bytes));
}

bool command_header_at(const uint8_t* data, size_t length, size_t offset) {
    return offset + kMarkerSize <= length && memcmp(data + offset, kCommandHeader, kMarkerSize) == 0;
}

Ack ack_from_payload(const uint8_t* status_and_value, size_t length) {
    size_t value_length = length - kStatusSize;
    if (value_length > kMaxAckValueSize) {
        value_length = kMaxAckValueSize;
    }
    Ack ack{};
    ack.found = true;
    ack.success = get_u16_le(status_and_value) == 0;
    memcpy(ack.value, status_and_value + kStatusSize, value_length);
    ack.value_length = static_cast<uint8_t>(value_length);
    return ack;
}

Ack ack_at(const uint8_t* data, size_t length, size_t offset, uint16_t command_word) {
    size_t body = offset + kMarkerSize + kLengthFieldSize;
    if (body > length) {
        return Ack{};
    }
    size_t in_frame = get_u16_le(data + offset + kMarkerSize);
    size_t tail = body + in_frame;
    bool complete = in_frame >= kWordSize + kStatusSize && tail + kMarkerSize <= length;
    if (!complete || memcmp(data + tail, kCommandTail, kMarkerSize) != 0) {
        return Ack{};
    }
    if (get_u16_le(data + body) != (command_word | kAckFlag)) {
        return Ack{};
    }
    return ack_from_payload(data + body + kWordSize, in_frame - kWordSize);
}

}  // namespace

CommandBytes build_command(uint16_t word, const uint8_t* value, size_t value_length) {
    CommandBytes command{};
    if (value_length > kMaxCommandValueSize) {
        return command;
    }
    memcpy(command.bytes, kCommandHeader, kMarkerSize);
    put_u16_le(command.bytes + kMarkerSize, static_cast<uint16_t>(kWordSize + value_length));
    put_u16_le(command.bytes + kMarkerSize + kLengthFieldSize, word);
    if (value_length > 0) {
        memcpy(command.bytes + kValueOffset, value, value_length);
    }
    memcpy(command.bytes + kValueOffset + value_length, kCommandTail, kMarkerSize);
    command.length = static_cast<uint8_t>(kValueOffset + value_length + kMarkerSize);
    return command;
}

CommandBytes enable_config_command() {
    return command_with_u16(kCmdEnableConfig, 0x0001);
}

CommandBytes multi_target_command() {
    return build_command(kCmdMultiTarget, nullptr, 0);
}

CommandBytes read_firmware_command() {
    return build_command(kCmdReadFirmware, nullptr, 0);
}

CommandBytes bluetooth_off_command() {
    return command_with_u16(kCmdBluetooth, 0x0000);
}

CommandBytes restart_command() {
    return build_command(kCmdRestart, nullptr, 0);
}

Ack find_ack(const uint8_t* data, size_t length, uint16_t command_word) {
    for (size_t offset = 0; offset < length; ++offset) {
        if (!command_header_at(data, length, offset)) {
            continue;
        }
        Ack ack = ack_at(data, length, offset, command_word);
        if (ack.found) {
            return ack;
        }
    }
    return Ack{};
}

FirmwareText firmware_text_from_ack(const Ack& ack) {
    FirmwareText firmware{};
    if (!ack.found || !ack.success || ack.value_length < kFirmwareValueSize) {
        return firmware;
    }
    uint16_t major = get_u16_le(ack.value + 2);
    uint32_t minor = get_u32_le(ack.value + 4);
    snprintf(firmware.text, sizeof(firmware.text), "V%X.%02X.%08lX", static_cast<unsigned>(major >> 8),
             static_cast<unsigned>(major & 0xFF), static_cast<unsigned long>(minor));
    return firmware;
}

uint8_t baud_index_for(uint32_t baud) {
    for (uint8_t index = 1; index < kBaudIndexCount; ++index) {
        if (kBaudByIndex[index] == baud) {
            return index;
        }
    }
    return 0;
}
```

- [ ] **Step 5: Run the suite to verify it passes**

Run (from `firmware/`): `python tools/run_native_tests.py test_ld2450_commands`
Expected: `12 Tests 0 Failures 0 Ignored` and `1 suites passed, 0 failed`.
Run: `python -m platformio test -e esp32dev -f test_ld2450_commands --without-uploading --without-testing`
Expected: `esp32dev:test_ld2450_commands [SKIPPED]`. CI is authoritative; board: `pio test -e esp32dev -f test_ld2450_commands`.

- [ ] **Step 6: Commit**

```bash
git -C C:/personal/blindside add firmware/lib/ld2450/ld2450_commands.h firmware/lib/ld2450/ld2450_commands.cpp firmware/test/test_ld2450_commands/test_main.cpp
git -C C:/personal/blindside commit -m "feat: comandos del LD2450, lectura de ACK, versión de firmware y tabla de baudios"
```

---

### Task 4: Radar watchdog policy and frame-gap statistics (`radar_watchdog`, `frame_gaps`)

**Files:**
- Create: `firmware/lib/ld2450/radar_watchdog.h`
- Create: `firmware/lib/ld2450/radar_watchdog.cpp`
- Create: `firmware/lib/ld2450/frame_gaps.h`
- Create: `firmware/lib/ld2450/frame_gaps.cpp`
- Test: `firmware/test/test_radar_watchdog/test_main.cpp`
- Test: `firmware/test/test_frame_gaps/test_main.cpp`

**Interfaces:**
- Consumes: nothing.
- Produces (used by Tasks 12, 16, 17):
  - `constexpr uint32_t kRadarSilenceLimitMs = 2000; kRadarRetryPeriodMs = 30000;`
  - `struct RadarWatchdog { uint32_t last_frame_ms; uint32_t last_restart_ms; bool has_frame; bool restarted_while_silent; uint8_t restarts; };`
  - `enum class WatchdogAction : uint8_t { None, Restart };`
  - `struct WatchdogStep { RadarWatchdog watchdog; WatchdogAction action; };`
  - `RadarWatchdog watchdog_after_boot_config(uint32_t now_ms);` (the boot sequence ends with a restart, so it counts as the first attempt; `restarts` stays 0)
  - `RadarWatchdog watchdog_saw_frame(const RadarWatchdog&, uint32_t now_ms);`
  - `RadarWatchdog watchdog_restarted(const RadarWatchdog&, uint32_t now_ms);` (also used for `control 01`)
  - `WatchdogStep watchdog_check(const RadarWatchdog&, uint32_t now_ms);`
  - `bool watchdog_radar_alive(const RadarWatchdog&, uint32_t now_ms);`
  - `struct FrameGaps { bool has_last; uint32_t last_t_ms; uint32_t count; uint32_t min_gap_ms; uint32_t max_gap_ms; };`
  - `FrameGaps frame_gaps_start(); FrameGaps frame_gaps_with(const FrameGaps&, uint32_t t_ms); FrameGaps frame_gaps_new_window(const FrameGaps&);` (a new window keeps the last frame time, so its first gap is still measured; used for the spec §9 "Δt ±2 ms" bench check)

- [ ] **Step 1: Write the failing watchdog tests `firmware/test/test_radar_watchdog/test_main.cpp`**

```cpp
#include <unity.h>

#include "../test_entry.h"
#include "radar_watchdog.h"

namespace {

RadarWatchdog checked(const RadarWatchdog& watchdog, uint32_t now_ms, WatchdogAction expected) {
    WatchdogStep step = watchdog_check(watchdog, now_ms);
    TEST_ASSERT_TRUE(step.action == expected);
    return step.watchdog;
}

}  // namespace

void setUp() {}
void tearDown() {}

void test_boot_config_counts_as_the_first_restart() {
    RadarWatchdog watchdog = watchdog_after_boot_config(0);
    watchdog = checked(watchdog, 2000, WatchdogAction::None);
    watchdog = checked(watchdog, 29999, WatchdogAction::None);
    watchdog = checked(watchdog, 30000, WatchdogAction::Restart);
    watchdog = checked(watchdog, 30001, WatchdogAction::None);
    watchdog = checked(watchdog, 60000, WatchdogAction::Restart);
    TEST_ASSERT_EQUAL_UINT8(2, watchdog.restarts);
}

void test_flowing_frames_never_restart() {
    RadarWatchdog watchdog = watchdog_after_boot_config(0);
    for (uint32_t t = 100; t < 100000; t += 100) {
        watchdog = watchdog_saw_frame(watchdog, t);
        watchdog = checked(watchdog, t + 50, WatchdogAction::None);
    }
    TEST_ASSERT_EQUAL_UINT8(0, watchdog.restarts);
}

void test_silence_mid_session_restarts_after_two_seconds_then_every_thirty() {
    RadarWatchdog watchdog = watchdog_saw_frame(watchdog_after_boot_config(0), 1000);
    watchdog = checked(watchdog, 2999, WatchdogAction::None);
    watchdog = checked(watchdog, 3000, WatchdogAction::Restart);
    watchdog = checked(watchdog, 3100, WatchdogAction::None);
    watchdog = checked(watchdog, 32999, WatchdogAction::None);
    watchdog = checked(watchdog, 33000, WatchdogAction::Restart);
    TEST_ASSERT_EQUAL_UINT8(2, watchdog.restarts);
}

void test_frames_after_a_restart_rearm_the_fast_restart() {
    RadarWatchdog watchdog = watchdog_saw_frame(watchdog_after_boot_config(0), 1000);
    watchdog = checked(watchdog, 3000, WatchdogAction::Restart);
    watchdog = watchdog_saw_frame(watchdog, 4000);
    watchdog = checked(watchdog, 5999, WatchdogAction::None);
    watchdog = checked(watchdog, 6000, WatchdogAction::Restart);
}

void test_manual_restart_defers_the_automatic_one() {
    RadarWatchdog watchdog = watchdog_saw_frame(watchdog_after_boot_config(0), 1000);
    watchdog = watchdog_restarted(watchdog, 1500);
    watchdog = checked(watchdog, 3500, WatchdogAction::None);
    watchdog = checked(watchdog, 31500, WatchdogAction::Restart);
    TEST_ASSERT_EQUAL_UINT8(2, watchdog.restarts);
}

void test_alive_only_within_two_seconds_of_a_frame() {
    RadarWatchdog watchdog = watchdog_after_boot_config(0);
    TEST_ASSERT_FALSE(watchdog_radar_alive(watchdog, 100));
    watchdog = watchdog_saw_frame(watchdog, 1000);
    TEST_ASSERT_TRUE(watchdog_radar_alive(watchdog, 2999));
    TEST_ASSERT_FALSE(watchdog_radar_alive(watchdog, 3000));
}

void test_restart_counter_saturates_at_255() {
    RadarWatchdog watchdog = watchdog_after_boot_config(0);
    for (uint32_t i = 0; i < 300; ++i) {
        watchdog = watchdog_restarted(watchdog, i);
    }
    TEST_ASSERT_EQUAL_UINT8(255, watchdog.restarts);
}

void test_millis_wrap_is_handled() {
    RadarWatchdog watchdog = watchdog_saw_frame(watchdog_after_boot_config(0xFFFFFE00u), 0xFFFFFF00u);
    TEST_ASSERT_TRUE(watchdog_radar_alive(watchdog, 0x00000100u));
    checked(watchdog, 0x00000100u, WatchdogAction::None);
    checked(watchdog, 0x000006D0u, WatchdogAction::Restart);
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_boot_config_counts_as_the_first_restart);
    RUN_TEST(test_flowing_frames_never_restart);
    RUN_TEST(test_silence_mid_session_restarts_after_two_seconds_then_every_thirty);
    RUN_TEST(test_frames_after_a_restart_rearm_the_fast_restart);
    RUN_TEST(test_manual_restart_defers_the_automatic_one);
    RUN_TEST(test_alive_only_within_two_seconds_of_a_frame);
    RUN_TEST(test_restart_counter_saturates_at_255);
    RUN_TEST(test_millis_wrap_is_handled);
    return UNITY_END();
}
```

(`0x00000100` is 512 ms after `0xFFFFFF00`, and `0x000006D0` is 0x6D0 + 0x100 = 0x7D0 = 2000 ms after it.)

- [ ] **Step 2: Run the suite to verify it fails**

Run (from `firmware/`): `python tools/run_native_tests.py test_radar_watchdog`
Expected: the build stops with `fatal error: 'radar_watchdog.h' file not found` and the runner ends with `build failed` and `0 suites passed, 1 failed`.

- [ ] **Step 3: Write `firmware/lib/ld2450/radar_watchdog.h`**

```cpp
#pragma once

#include <stdint.h>

constexpr uint32_t kRadarSilenceLimitMs = 2000;
constexpr uint32_t kRadarRetryPeriodMs = 30000;

struct RadarWatchdog {
    uint32_t last_frame_ms;
    uint32_t last_restart_ms;
    bool has_frame;
    bool restarted_while_silent;
    uint8_t restarts;
};

enum class WatchdogAction : uint8_t { None, Restart };

struct WatchdogStep {
    RadarWatchdog watchdog;
    WatchdogAction action;
};

RadarWatchdog watchdog_after_boot_config(uint32_t now_ms);
RadarWatchdog watchdog_saw_frame(const RadarWatchdog& watchdog, uint32_t now_ms);
RadarWatchdog watchdog_restarted(const RadarWatchdog& watchdog, uint32_t now_ms);
WatchdogStep watchdog_check(const RadarWatchdog& watchdog, uint32_t now_ms);
bool watchdog_radar_alive(const RadarWatchdog& watchdog, uint32_t now_ms);
```

- [ ] **Step 4: Write `firmware/lib/ld2450/radar_watchdog.cpp`**

```cpp
#include "radar_watchdog.h"

namespace {

constexpr uint8_t kMaxRestartCount = 0xFF;

bool elapsed_at_least(uint32_t since_ms, uint32_t now_ms, uint32_t duration_ms) {
    return now_ms - since_ms >= duration_ms;
}

bool is_silent(const RadarWatchdog& watchdog, uint32_t now_ms) {
    return elapsed_at_least(watchdog.last_frame_ms, now_ms, kRadarSilenceLimitMs);
}

bool restart_due(const RadarWatchdog& watchdog, uint32_t now_ms) {
    if (!is_silent(watchdog, now_ms)) {
        return false;
    }
    if (!watchdog.restarted_while_silent) {
        return true;
    }
    return elapsed_at_least(watchdog.last_restart_ms, now_ms, kRadarRetryPeriodMs);
}

}  // namespace

RadarWatchdog watchdog_after_boot_config(uint32_t now_ms) {
    RadarWatchdog watchdog{};
    watchdog.last_frame_ms = now_ms;
    watchdog.last_restart_ms = now_ms;
    watchdog.restarted_while_silent = true;
    return watchdog;
}

RadarWatchdog watchdog_saw_frame(const RadarWatchdog& watchdog, uint32_t now_ms) {
    RadarWatchdog next = watchdog;
    next.last_frame_ms = now_ms;
    next.has_frame = true;
    next.restarted_while_silent = false;
    return next;
}

RadarWatchdog watchdog_restarted(const RadarWatchdog& watchdog, uint32_t now_ms) {
    RadarWatchdog next = watchdog;
    next.last_restart_ms = now_ms;
    next.restarted_while_silent = true;
    next.restarts = watchdog.restarts == kMaxRestartCount ? kMaxRestartCount : static_cast<uint8_t>(watchdog.restarts + 1);
    return next;
}

WatchdogStep watchdog_check(const RadarWatchdog& watchdog, uint32_t now_ms) {
    if (!restart_due(watchdog, now_ms)) {
        return WatchdogStep{watchdog, WatchdogAction::None};
    }
    return WatchdogStep{watchdog_restarted(watchdog, now_ms), WatchdogAction::Restart};
}

bool watchdog_radar_alive(const RadarWatchdog& watchdog, uint32_t now_ms) {
    return watchdog.has_frame && !is_silent(watchdog, now_ms);
}
```

- [ ] **Step 5: Run the suite to verify it passes**

Run (from `firmware/`): `python tools/run_native_tests.py test_radar_watchdog`
Expected: `8 Tests 0 Failures 0 Ignored`.

- [ ] **Step 6: Write the failing frame-gap tests `firmware/test/test_frame_gaps/test_main.cpp`**

```cpp
#include <unity.h>

#include "../test_entry.h"
#include "frame_gaps.h"

namespace {

FrameGaps fed(const uint32_t* times, uint32_t count) {
    FrameGaps gaps = frame_gaps_start();
    for (uint32_t i = 0; i < count; ++i) {
        gaps = frame_gaps_with(gaps, times[i]);
    }
    return gaps;
}

}  // namespace

void setUp() {}
void tearDown() {}

void test_the_first_frame_measures_no_gap() {
    const uint32_t times[] = {500};
    FrameGaps gaps = fed(times, 1);
    TEST_ASSERT_EQUAL_UINT32(0, gaps.count);
    TEST_ASSERT_TRUE(gaps.has_last);
}

void test_gaps_keep_their_minimum_and_maximum() {
    const uint32_t times[] = {1000, 1100, 1198, 1301};
    FrameGaps gaps = fed(times, 4);
    TEST_ASSERT_EQUAL_UINT32(3, gaps.count);
    TEST_ASSERT_EQUAL_UINT32(98, gaps.min_gap_ms);
    TEST_ASSERT_EQUAL_UINT32(103, gaps.max_gap_ms);
}

void test_a_new_window_still_measures_its_first_gap() {
    const uint32_t times[] = {1000, 1100};
    FrameGaps gaps = frame_gaps_new_window(fed(times, 2));
    TEST_ASSERT_EQUAL_UINT32(0, gaps.count);
    gaps = frame_gaps_with(gaps, 1250);
    TEST_ASSERT_EQUAL_UINT32(1, gaps.count);
    TEST_ASSERT_EQUAL_UINT32(150, gaps.min_gap_ms);
    TEST_ASSERT_EQUAL_UINT32(150, gaps.max_gap_ms);
}

void test_gaps_survive_a_millis_wrap() {
    const uint32_t times[] = {0xFFFFFFF0u, 0x00000050u};
    TEST_ASSERT_EQUAL_UINT32(96, fed(times, 2).max_gap_ms);
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_the_first_frame_measures_no_gap);
    RUN_TEST(test_gaps_keep_their_minimum_and_maximum);
    RUN_TEST(test_a_new_window_still_measures_its_first_gap);
    RUN_TEST(test_gaps_survive_a_millis_wrap);
    return UNITY_END();
}
```

- [ ] **Step 7: Run the suite to verify it fails**

Run (from `firmware/`): `python tools/run_native_tests.py test_frame_gaps`
Expected: the build stops with `fatal error: 'frame_gaps.h' file not found` and the runner ends with `build failed` and `0 suites passed, 1 failed`.

- [ ] **Step 8: Write `firmware/lib/ld2450/frame_gaps.h`**

```cpp
#pragma once

#include <stdint.h>

struct FrameGaps {
    bool has_last;
    uint32_t last_t_ms;
    uint32_t count;
    uint32_t min_gap_ms;
    uint32_t max_gap_ms;
};

FrameGaps frame_gaps_start();
FrameGaps frame_gaps_with(const FrameGaps& gaps, uint32_t t_ms);
FrameGaps frame_gaps_new_window(const FrameGaps& gaps);
```

- [ ] **Step 9: Write `firmware/lib/ld2450/frame_gaps.cpp`**

```cpp
#include "frame_gaps.h"

namespace {

uint32_t smaller_gap(const FrameGaps& gaps, uint32_t gap_ms) {
    bool first = gaps.count == 0;
    return first || gap_ms < gaps.min_gap_ms ? gap_ms : gaps.min_gap_ms;
}

uint32_t larger_gap(const FrameGaps& gaps, uint32_t gap_ms) {
    return gap_ms > gaps.max_gap_ms ? gap_ms : gaps.max_gap_ms;
}

FrameGaps with_gap(const FrameGaps& gaps, uint32_t gap_ms) {
    FrameGaps next = gaps;
    next.min_gap_ms = smaller_gap(gaps, gap_ms);
    next.max_gap_ms = larger_gap(gaps, gap_ms);
    next.count = gaps.count + 1;
    return next;
}

}  // namespace

FrameGaps frame_gaps_start() {
    return FrameGaps{};
}

FrameGaps frame_gaps_with(const FrameGaps& gaps, uint32_t t_ms) {
    FrameGaps next = gaps.has_last ? with_gap(gaps, t_ms - gaps.last_t_ms) : gaps;
    next.has_last = true;
    next.last_t_ms = t_ms;
    return next;
}

FrameGaps frame_gaps_new_window(const FrameGaps& gaps) {
    FrameGaps next = frame_gaps_start();
    next.has_last = gaps.has_last;
    next.last_t_ms = gaps.last_t_ms;
    return next;
}
```

- [ ] **Step 10: Run both suites to verify they pass**

Run (from `firmware/`): `python tools/run_native_tests.py test_radar_watchdog test_frame_gaps`
Expected: `8 Tests 0 Failures 0 Ignored`, `4 Tests 0 Failures 0 Ignored` and `2 suites passed, 0 failed`.
Run: `python -m platformio test -e esp32dev -f test_radar_watchdog -f test_frame_gaps --without-uploading --without-testing`
Expected: both suites `[SKIPPED]`. CI is authoritative; board: `pio test -e esp32dev -f test_radar_watchdog -f test_frame_gaps`.

- [ ] **Step 11: Commit**

```bash
git -C C:/personal/blindside add firmware/lib/ld2450/radar_watchdog.h firmware/lib/ld2450/radar_watchdog.cpp firmware/lib/ld2450/frame_gaps.h firmware/lib/ld2450/frame_gaps.cpp firmware/test/test_radar_watchdog/test_main.cpp firmware/test/test_frame_gaps/test_main.cpp
git -C C:/personal/blindside commit -m "feat: política del watchdog de radares y estadística de intervalos entre tramas"
```

---

### Task 5: IMU math — 5 ms grid, 50 Hz mean at the slot centre, repeats, cumulative sums, MPU6050 register plan (`imu_math`)

**Files:**
- Create: `firmware/lib/imu_math/imu_accumulator.h`
- Create: `firmware/lib/imu_math/imu_accumulator.cpp`
- Create: `firmware/lib/imu_math/mpu6050_registers.h`
- Create: `firmware/lib/imu_math/mpu6050_registers.cpp`
- Test: `firmware/test/test_imu_math/test_main.cpp`

**Interfaces:**
- Consumes: nothing.
- Produces (used by Tasks 6, 7, 8, 13):
  - `enum ImuAxis : uint8_t { kAx, kAy, kAz, kGx, kGy, kGz, kImuAxisCount };`
  - `constexpr uint8_t kReadingsPerSample = 4; constexpr uint32_t kImuTickPeriodMs = 5; kImuSamplePeriodMs = 20; kImuSampleCentreOffsetMs = 10; kMaxTicksPerStep = 64; constexpr size_t kMaxSamplesPerStep = 16;`
  - `struct ImuReading { int16_t axes[kImuAxisCount]; };`
  - `struct ImuRead { bool ok; ImuReading reading; };` (`ok == false` means "repeat the previous reading")
  - `struct GyroSums { uint32_t x; uint32_t y; uint32_t z; };`
  - `struct ImuSample { uint32_t t_ms; ImuReading mean; GyroSums sums; };` (`t_ms` = slot centre)
  - `struct ImuAccumulator { int32_t totals[kImuAxisCount]; uint8_t count; uint32_t slot_start_ms; GyroSums sums; };`
  - `struct AccumulatorStep { ImuAccumulator accumulator; bool has_sample; ImuSample sample; };`
  - `struct ImuChannel { ImuAccumulator accumulator; ImuReading last_reading; uint32_t next_tick_ms; uint32_t repeats; };`
  - `struct ImuStep { ImuChannel channel; ImuSample samples[kMaxSamplesPerStep]; size_t sample_count; };`
  - `ImuAccumulator accumulator_start();`
  - `AccumulatorStep accumulator_add(const ImuAccumulator&, const ImuReading&, uint32_t tick_ms);`
  - `GyroSums sums_with(const GyroSums&, const ImuReading&);`
  - `int16_t rounded_quarter(int32_t total);`
  - `ImuChannel imu_channel_start(uint32_t first_tick_ms);`
  - `uint32_t imu_ticks_due(const ImuChannel&, uint32_t now_ms);` (grid ticks with time ≤ now, wrap-safe)
  - `ImuStep imu_channel_step(const ImuChannel&, uint32_t now_ms, const ImuRead& fresh);` (feeds up to 64 due ticks; only the tick nearest `now` gets `fresh`, every other tick repeats the last reading; a longer stall is finished by the next calls without moving the grid)
  - register constants `kMpuRegWhoAmI 0x75`, `kMpuRegAccelXoutH 0x3B`, `kMpuDefaultAddress 0x68`, `kMpuAlternateAddress 0x69`, `kMpuBurstSize 14`, …
  - `struct RegisterWrite { uint8_t reg; uint8_t value; };`
  - `struct MpuConfigPlan { RegisterWrite writes[6]; uint8_t count; };`
  - `bool who_am_i_is_usable(uint8_t who_am_i); bool needs_accel_config2(uint8_t who_am_i);`
  - `MpuConfigPlan mpu_config_plan(uint8_t who_am_i);`
  - `ImuReading reading_from_burst(const uint8_t* burst);` (14 big-endian bytes, temperature skipped)

- [ ] **Step 1: Write the failing tests `firmware/test/test_imu_math/test_main.cpp`**

```cpp
#include <unity.h>

#include "../test_entry.h"
#include "imu_accumulator.h"
#include "mpu6050_registers.h"

namespace {

ImuReading reading_with(int16_t ax, int16_t ay, int16_t az, int16_t gx, int16_t gy, int16_t gz) {
    return ImuReading{{ax, ay, az, gx, gy, gz}};
}

ImuRead good_read(int16_t gx) {
    return ImuRead{true, reading_with(0, 0, 4096, gx, 0, 0)};
}

ImuRead failed_read() {
    return ImuRead{};
}

struct FeedResult {
    ImuAccumulator accumulator;
    int samples;
    ImuSample last;
};

FeedResult fed(const FeedResult& result, const ImuReading& reading, uint32_t t_ms) {
    AccumulatorStep step = accumulator_add(result.accumulator, reading, t_ms);
    FeedResult next = result;
    next.accumulator = step.accumulator;
    next.samples += step.has_sample ? 1 : 0;
    if (step.has_sample) {
        next.last = step.sample;
    }
    return next;
}

FeedResult fresh() {
    FeedResult result{};
    result.accumulator = accumulator_start();
    return result;
}

struct StepLog {
    ImuChannel channel;
    size_t samples;
    bool on_grid;
    uint32_t last_t_ms;
};

StepLog logged(const StepLog& log, const ImuStep& step) {
    StepLog next = log;
    next.channel = step.channel;
    for (size_t i = 0; i < step.sample_count; ++i) {
        uint32_t expected_t_ms = 10 + 20 * static_cast<uint32_t>(next.samples);
        next.on_grid = next.on_grid && step.samples[i].t_ms == expected_t_ms;
        next.last_t_ms = step.samples[i].t_ms;
        next.samples++;
    }
    return next;
}

void assert_write(const RegisterWrite& write, uint8_t reg, uint8_t value) {
    TEST_ASSERT_EQUAL_HEX8(reg, write.reg);
    TEST_ASSERT_EQUAL_HEX8(value, write.value);
}

}  // namespace

void setUp() {}
void tearDown() {}

void test_four_readings_make_one_rounded_sample_at_the_slot_centre() {
    FeedResult result = fresh();
    result = fed(result, reading_with(1, 0, 4096, 10, -1, 0), 1000);
    result = fed(result, reading_with(2, 0, 4096, 11, -2, 0), 1005);
    result = fed(result, reading_with(3, 0, 4096, 12, -2, 0), 1010);
    TEST_ASSERT_EQUAL_INT(0, result.samples);
    result = fed(result, reading_with(4, 0, 4096, 13, -2, 0), 1015);
    TEST_ASSERT_EQUAL_INT(1, result.samples);
    TEST_ASSERT_EQUAL_UINT32(1010, result.last.t_ms);
    TEST_ASSERT_EQUAL_INT16(3, result.last.mean.axes[kAx]);
    TEST_ASSERT_EQUAL_INT16(4096, result.last.mean.axes[kAz]);
    TEST_ASSERT_EQUAL_INT16(12, result.last.mean.axes[kGx]);
    TEST_ASSERT_EQUAL_INT16(-2, result.last.mean.axes[kGy]);
}

void test_sums_cover_every_raw_reading_and_wrap_for_negatives() {
    FeedResult result = fresh();
    for (uint32_t i = 0; i < 4; ++i) {
        result = fed(result, reading_with(0, 0, 0, 10 + static_cast<int16_t>(i), -5, -5), 1000 + 5 * i);
    }
    TEST_ASSERT_EQUAL_UINT32(46, result.last.sums.x);
    TEST_ASSERT_EQUAL_UINT32(0xFFFFFFECu, result.last.sums.y);
    TEST_ASSERT_EQUAL_UINT32(0xFFFFFFECu, result.last.sums.z);
}

void test_second_sample_keeps_cumulative_sums_and_its_own_time() {
    FeedResult result = fresh();
    for (uint32_t i = 0; i < 8; ++i) {
        result = fed(result, reading_with(0, 0, 0, 1, 0, 0), 2000 + 5 * i);
    }
    TEST_ASSERT_EQUAL_INT(2, result.samples);
    TEST_ASSERT_EQUAL_UINT32(2030, result.last.t_ms);
    TEST_ASSERT_EQUAL_UINT32(8, result.last.sums.x);
}

void test_rounding_is_half_away_from_zero() {
    TEST_ASSERT_EQUAL_INT16(0, rounded_quarter(0));
    TEST_ASSERT_EQUAL_INT16(0, rounded_quarter(1));
    TEST_ASSERT_EQUAL_INT16(1, rounded_quarter(2));
    TEST_ASSERT_EQUAL_INT16(-1, rounded_quarter(-2));
    TEST_ASSERT_EQUAL_INT16(2, rounded_quarter(6));
    TEST_ASSERT_EQUAL_INT16(-2, rounded_quarter(-6));
    TEST_ASSERT_EQUAL_INT16(32767, rounded_quarter(4 * 32767));
    TEST_ASSERT_EQUAL_INT16(-32768, rounded_quarter(4 * -32768));
}

void test_ticks_fall_due_on_the_5_ms_grid() {
    ImuChannel channel = imu_channel_start(1000);
    TEST_ASSERT_EQUAL_UINT32(0, imu_ticks_due(channel, 999));
    TEST_ASSERT_EQUAL_UINT32(1, imu_ticks_due(channel, 1000));
    TEST_ASSERT_EQUAL_UINT32(1, imu_ticks_due(channel, 1004));
    TEST_ASSERT_EQUAL_UINT32(2, imu_ticks_due(channel, 1005));
}

void test_ticks_due_survive_a_millis_wrap() {
    ImuChannel channel = imu_channel_start(0xFFFFFFFEu);
    TEST_ASSERT_EQUAL_UINT32(3, imu_ticks_due(channel, 0x00000008u));
    TEST_ASSERT_EQUAL_UINT32(0, imu_ticks_due(channel, 0xFFFFFFFDu));
}

void test_failed_read_repeats_the_previous_reading_and_sums_advance_four_per_block() {
    ImuStep step = imu_channel_step(imu_channel_start(1000), 1000, good_read(8));
    step = imu_channel_step(step.channel, 1005, failed_read());
    step = imu_channel_step(step.channel, 1010, failed_read());
    step = imu_channel_step(step.channel, 1015, good_read(16));
    TEST_ASSERT_EQUAL_UINT(1, step.sample_count);
    TEST_ASSERT_EQUAL_UINT32(1010, step.samples[0].t_ms);
    TEST_ASSERT_EQUAL_INT16(10, step.samples[0].mean.axes[kGx]);
    TEST_ASSERT_EQUAL_UINT32(40, step.samples[0].sums.x);
    TEST_ASSERT_EQUAL_UINT32(2, step.channel.repeats);
}

void test_skipped_slots_keep_the_samples_on_the_20_ms_grid() {
    ImuStep step = imu_channel_step(imu_channel_start(1000), 1000, good_read(4));
    step = imu_channel_step(step.channel, 1032, good_read(4));
    TEST_ASSERT_EQUAL_UINT(1, step.sample_count);
    TEST_ASSERT_EQUAL_UINT32(1010, step.samples[0].t_ms);
    TEST_ASSERT_EQUAL_UINT32(5, step.channel.repeats);
    TEST_ASSERT_EQUAL_UINT32(1035, step.channel.next_tick_ms);
    step = imu_channel_step(step.channel, 1037, good_read(4));
    TEST_ASSERT_EQUAL_UINT(1, step.sample_count);
    TEST_ASSERT_EQUAL_UINT32(1030, step.samples[0].t_ms);
    TEST_ASSERT_EQUAL_UINT32(32, step.samples[0].sums.x);
}

void test_a_long_stall_is_fed_over_several_steps_without_moving_the_grid() {
    StepLog log{};
    log.on_grid = true;
    log = logged(log, imu_channel_step(imu_channel_start(0), 0, good_read(1)));
    ImuStep step = imu_channel_step(log.channel, 995, good_read(1));
    TEST_ASSERT_EQUAL_UINT(kMaxSamplesPerStep, step.sample_count);
    log = logged(log, step);
    while (imu_ticks_due(log.channel, 995) > 0) {
        log = logged(log, imu_channel_step(log.channel, 995, good_read(1)));
    }
    TEST_ASSERT_TRUE(log.on_grid);
    TEST_ASSERT_EQUAL_UINT(50, log.samples);
    TEST_ASSERT_EQUAL_UINT32(990, log.last_t_ms);
    TEST_ASSERT_EQUAL_UINT32(1000, log.channel.next_tick_ms);
    TEST_ASSERT_EQUAL_UINT32(198, log.channel.repeats);
}

void test_genuine_mpu6050_gets_five_writes_without_accel_config2() {
    MpuConfigPlan plan = mpu_config_plan(0x68);
    TEST_ASSERT_EQUAL_UINT8(5, plan.count);
    assert_write(plan.writes[0], 0x6B, 0x01);
    assert_write(plan.writes[1], 0x19, 0x00);
    assert_write(plan.writes[2], 0x1A, 0x03);
    assert_write(plan.writes[3], 0x1B, 0x08);
    assert_write(plan.writes[4], 0x1C, 0x10);
}

void test_clone_also_gets_accel_config2() {
    MpuConfigPlan plan = mpu_config_plan(0x70);
    TEST_ASSERT_EQUAL_UINT8(6, plan.count);
    assert_write(plan.writes[5], 0x1D, 0x03);
    TEST_ASSERT_EQUAL_UINT8(6, mpu_config_plan(0x71).count);
}

void test_who_am_i_tolerates_clones_but_not_bus_errors() {
    TEST_ASSERT_TRUE(who_am_i_is_usable(0x68));
    TEST_ASSERT_TRUE(who_am_i_is_usable(0x70));
    TEST_ASSERT_TRUE(who_am_i_is_usable(0x98));
    TEST_ASSERT_FALSE(who_am_i_is_usable(0x00));
    TEST_ASSERT_FALSE(who_am_i_is_usable(0xFF));
    TEST_ASSERT_FALSE(needs_accel_config2(0x68));
    TEST_ASSERT_TRUE(needs_accel_config2(0x70));
}

void test_burst_is_big_endian_and_skips_temperature() {
    const uint8_t burst[14] = {0x10, 0x00, 0xFF, 0xFE, 0x00, 0x01, 0x12, 0x34,
                               0x00, 0x83, 0xFF, 0x7D, 0x7F, 0xFF};
    ImuReading reading = reading_from_burst(burst);
    TEST_ASSERT_EQUAL_INT16(4096, reading.axes[kAx]);
    TEST_ASSERT_EQUAL_INT16(-2, reading.axes[kAy]);
    TEST_ASSERT_EQUAL_INT16(1, reading.axes[kAz]);
    TEST_ASSERT_EQUAL_INT16(131, reading.axes[kGx]);
    TEST_ASSERT_EQUAL_INT16(-131, reading.axes[kGy]);
    TEST_ASSERT_EQUAL_INT16(32767, reading.axes[kGz]);
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_four_readings_make_one_rounded_sample_at_the_slot_centre);
    RUN_TEST(test_sums_cover_every_raw_reading_and_wrap_for_negatives);
    RUN_TEST(test_second_sample_keeps_cumulative_sums_and_its_own_time);
    RUN_TEST(test_rounding_is_half_away_from_zero);
    RUN_TEST(test_ticks_fall_due_on_the_5_ms_grid);
    RUN_TEST(test_ticks_due_survive_a_millis_wrap);
    RUN_TEST(test_failed_read_repeats_the_previous_reading_and_sums_advance_four_per_block);
    RUN_TEST(test_skipped_slots_keep_the_samples_on_the_20_ms_grid);
    RUN_TEST(test_a_long_stall_is_fed_over_several_steps_without_moving_the_grid);
    RUN_TEST(test_genuine_mpu6050_gets_five_writes_without_accel_config2);
    RUN_TEST(test_clone_also_gets_accel_config2);
    RUN_TEST(test_who_am_i_tolerates_clones_but_not_bus_errors);
    RUN_TEST(test_burst_is_big_endian_and_skips_temperature);
    return UNITY_END();
}
```

Worked values: gx 10+11+12+13 = 46 → 11.5 → 12; ax 1+2+3+4 = 10 → 2.5 → 3; gy −1−2−2−2 = −7 → −1.75 → −2; four readings of −5 sum to −20 = 0xFFFFFFEC. Repeats: readings 8, 8 (repeat), 8 (repeat), 16 → mean 10, sum 40, 2 repeats. Skipped slots: the step at 1032 feeds ticks 1005-1030 (5 repeats, the fresh reading at 1030); the slots are [1000, 1020) → t 1010 and [1020, 1040) → t 1030. Long stall: tick 0, then 199 due ticks fed as 64 + 64 + 64 + 7; 200 readings make 50 samples at t = 10, 30, …, 990 and only the last tick uses the fresh reading (198 repeats).

- [ ] **Step 2: Run the suite to verify it fails**

Run (from `firmware/`): `python tools/run_native_tests.py test_imu_math`
Expected: the build stops with `fatal error: 'imu_accumulator.h' file not found` and the runner ends with `build failed` and `0 suites passed, 1 failed`.

- [ ] **Step 3: Write `firmware/lib/imu_math/imu_accumulator.h`**

```cpp
#pragma once

#include <stddef.h>
#include <stdint.h>

enum ImuAxis : uint8_t { kAx, kAy, kAz, kGx, kGy, kGz, kImuAxisCount };

constexpr uint8_t kReadingsPerSample = 4;
constexpr uint32_t kImuTickPeriodMs = 5;
constexpr uint32_t kImuSamplePeriodMs = kImuTickPeriodMs * kReadingsPerSample;
constexpr uint32_t kImuSampleCentreOffsetMs = kImuSamplePeriodMs / 2;
constexpr uint32_t kMaxTicksPerStep = 64;
constexpr size_t kMaxSamplesPerStep = kMaxTicksPerStep / kReadingsPerSample;

struct ImuReading {
    int16_t axes[kImuAxisCount];
};

struct ImuRead {
    bool ok;
    ImuReading reading;
};

struct GyroSums {
    uint32_t x;
    uint32_t y;
    uint32_t z;
};

struct ImuSample {
    uint32_t t_ms;
    ImuReading mean;
    GyroSums sums;
};

struct ImuAccumulator {
    int32_t totals[kImuAxisCount];
    uint8_t count;
    uint32_t slot_start_ms;
    GyroSums sums;
};

struct AccumulatorStep {
    ImuAccumulator accumulator;
    bool has_sample;
    ImuSample sample;
};

struct ImuChannel {
    ImuAccumulator accumulator;
    ImuReading last_reading;
    uint32_t next_tick_ms;
    uint32_t repeats;
};

struct ImuStep {
    ImuChannel channel;
    ImuSample samples[kMaxSamplesPerStep];
    size_t sample_count;
};

ImuAccumulator accumulator_start();
AccumulatorStep accumulator_add(const ImuAccumulator& accumulator, const ImuReading& reading, uint32_t tick_ms);
GyroSums sums_with(const GyroSums& sums, const ImuReading& reading);
int16_t rounded_quarter(int32_t total);
ImuChannel imu_channel_start(uint32_t first_tick_ms);
uint32_t imu_ticks_due(const ImuChannel& channel, uint32_t now_ms);
ImuStep imu_channel_step(const ImuChannel& channel, uint32_t now_ms, const ImuRead& fresh);
```

- [ ] **Step 4: Write `firmware/lib/imu_math/imu_accumulator.cpp`**

```cpp
#include "imu_accumulator.h"

namespace {

struct TickFeed {
    ImuChannel channel;
    bool has_sample;
    ImuSample sample;
};

uint32_t wrapped_add(uint32_t sum, int16_t value) {
    return sum + static_cast<uint32_t>(static_cast<int32_t>(value));
}

ImuAccumulator with_reading(const ImuAccumulator& accumulator, const ImuReading& reading, uint32_t tick_ms) {
    ImuAccumulator next = accumulator;
    if (next.count == 0) {
        next.slot_start_ms = tick_ms;
    }
    for (uint8_t axis = 0; axis < kImuAxisCount; ++axis) {
        next.totals[axis] += reading.axes[axis];
    }
    next.count = static_cast<uint8_t>(next.count + 1);
    next.sums = sums_with(accumulator.sums, reading);
    return next;
}

ImuSample sample_from(const ImuAccumulator& accumulator) {
    ImuSample sample{};
    sample.t_ms = accumulator.slot_start_ms + kImuSampleCentreOffsetMs;
    for (uint8_t axis = 0; axis < kImuAxisCount; ++axis) {
        sample.mean.axes[axis] = rounded_quarter(accumulator.totals[axis]);
    }
    sample.sums = accumulator.sums;
    return sample;
}

ImuAccumulator next_slot(const ImuAccumulator& accumulator) {
    ImuAccumulator next = accumulator_start();
    next.sums = accumulator.sums;
    return next;
}

TickFeed fed_one_tick(const ImuChannel& channel, const ImuRead& read) {
    ImuReading reading = read.ok ? read.reading : channel.last_reading;
    AccumulatorStep step = accumulator_add(channel.accumulator, reading, channel.next_tick_ms);
    TickFeed feed{};
    feed.channel = channel;
    feed.channel.accumulator = step.accumulator;
    feed.channel.last_reading = reading;
    feed.channel.next_tick_ms = channel.next_tick_ms + kImuTickPeriodMs;
    feed.channel.repeats = channel.repeats + (read.ok ? 0 : 1);
    feed.has_sample = step.has_sample;
    feed.sample = step.sample;
    return feed;
}

ImuStep with_tick(const ImuStep& step, const ImuRead& read) {
    TickFeed feed = fed_one_tick(step.channel, read);
    ImuStep next = step;
    next.channel = feed.channel;
    if (feed.has_sample) {
        next.samples[next.sample_count] = feed.sample;
        next.sample_count++;
    }
    return next;
}

}  // namespace

ImuAccumulator accumulator_start() {
    return ImuAccumulator{};
}

AccumulatorStep accumulator_add(const ImuAccumulator& accumulator, const ImuReading& reading, uint32_t tick_ms) {
    ImuAccumulator next = with_reading(accumulator, reading, tick_ms);
    if (next.count < kReadingsPerSample) {
        return AccumulatorStep{next, false, ImuSample{}};
    }
    return AccumulatorStep{next_slot(next), true, sample_from(next)};
}

GyroSums sums_with(const GyroSums& sums, const ImuReading& reading) {
    return GyroSums{wrapped_add(sums.x, reading.axes[kGx]), wrapped_add(sums.y, reading.axes[kGy]),
                    wrapped_add(sums.z, reading.axes[kGz])};
}

int16_t rounded_quarter(int32_t total) {
    int32_t magnitude = total < 0 ? -total : total;
    int32_t rounded = (magnitude + 2) / 4;
    return static_cast<int16_t>(total < 0 ? -rounded : rounded);
}

ImuChannel imu_channel_start(uint32_t first_tick_ms) {
    ImuChannel channel{};
    channel.next_tick_ms = first_tick_ms;
    return channel;
}

uint32_t imu_ticks_due(const ImuChannel& channel, uint32_t now_ms) {
    int32_t ahead_ms = static_cast<int32_t>(now_ms - channel.next_tick_ms);
    return ahead_ms < 0 ? 0 : static_cast<uint32_t>(ahead_ms) / kImuTickPeriodMs + 1;
}

ImuStep imu_channel_step(const ImuChannel& channel, uint32_t now_ms, const ImuRead& fresh) {
    uint32_t due = imu_ticks_due(channel, now_ms);
    uint32_t fed = due < kMaxTicksPerStep ? due : kMaxTicksPerStep;
    const ImuRead repeat{};
    ImuStep step{};
    step.channel = channel;
    for (uint32_t tick = 0; tick < fed; ++tick) {
        bool is_current_tick = tick + 1 == due;
        step = with_tick(step, is_current_tick ? fresh : repeat);
    }
    return step;
}
```

- [ ] **Step 5: Write `firmware/lib/imu_math/mpu6050_registers.h`**

```cpp
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "imu_accumulator.h"

constexpr uint8_t kMpuDefaultAddress = 0x68;
constexpr uint8_t kMpuAlternateAddress = 0x69;
constexpr uint8_t kMpuRegSampleRateDivider = 0x19;
constexpr uint8_t kMpuRegConfig = 0x1A;
constexpr uint8_t kMpuRegGyroConfig = 0x1B;
constexpr uint8_t kMpuRegAccelConfig = 0x1C;
constexpr uint8_t kMpuRegAccelConfig2 = 0x1D;
constexpr uint8_t kMpuRegAccelXoutH = 0x3B;
constexpr uint8_t kMpuRegPowerManagement1 = 0x6B;
constexpr uint8_t kMpuRegWhoAmI = 0x75;
constexpr uint8_t kMpuGenuineWhoAmI = 0x68;
constexpr uint8_t kMpuBurstSize = 14;
constexpr size_t kMaxMpuConfigWrites = 6;

struct RegisterWrite {
    uint8_t reg;
    uint8_t value;
};

struct MpuConfigPlan {
    RegisterWrite writes[kMaxMpuConfigWrites];
    uint8_t count;
};

bool who_am_i_is_usable(uint8_t who_am_i);
bool needs_accel_config2(uint8_t who_am_i);
MpuConfigPlan mpu_config_plan(uint8_t who_am_i);
ImuReading reading_from_burst(const uint8_t* burst);
```

- [ ] **Step 6: Write `firmware/lib/imu_math/mpu6050_registers.cpp`**

```cpp
#include "mpu6050_registers.h"

namespace {

constexpr uint8_t kWakeWithGyroClock = 0x01;
// 1 kHz internal rate so every 200 Hz poll reads a fresh DLPF-filtered value.
constexpr uint8_t kSampleRateDivider1kHz = 0x00;
constexpr uint8_t kDlpf42Hz = 0x03;
constexpr uint8_t kGyroRange500Dps = 0x08;
constexpr uint8_t kAccelRange8G = 0x10;
constexpr uint8_t kAccelDlpf42Hz = 0x03;
constexpr uint8_t kBusErrorLow = 0x00;
constexpr uint8_t kBusErrorHigh = 0xFF;
constexpr uint8_t kBurstOffsets[kImuAxisCount] = {0, 2, 4, 8, 10, 12};

MpuConfigPlan with_write(const MpuConfigPlan& plan, uint8_t reg, uint8_t value) {
    MpuConfigPlan next = plan;
    next.writes[next.count] = RegisterWrite{reg, value};
    next.count = static_cast<uint8_t>(next.count + 1);
    return next;
}

int16_t big_endian_i16(const uint8_t* bytes) {
    return static_cast<int16_t>(static_cast<uint16_t>((bytes[0] << 8) | bytes[1]));
}

}  // namespace

bool who_am_i_is_usable(uint8_t who_am_i) {
    return who_am_i != kBusErrorLow && who_am_i != kBusErrorHigh;
}

bool needs_accel_config2(uint8_t who_am_i) {
    return who_am_i != kMpuGenuineWhoAmI;
}

MpuConfigPlan mpu_config_plan(uint8_t who_am_i) {
    MpuConfigPlan plan{};
    plan = with_write(plan, kMpuRegPowerManagement1, kWakeWithGyroClock);
    plan = with_write(plan, kMpuRegSampleRateDivider, kSampleRateDivider1kHz);
    plan = with_write(plan, kMpuRegConfig, kDlpf42Hz);
    plan = with_write(plan, kMpuRegGyroConfig, kGyroRange500Dps);
    plan = with_write(plan, kMpuRegAccelConfig, kAccelRange8G);
    if (!needs_accel_config2(who_am_i)) {
        return plan;
    }
    return with_write(plan, kMpuRegAccelConfig2, kAccelDlpf42Hz);
}

ImuReading reading_from_burst(const uint8_t* burst) {
    ImuReading reading{};
    for (uint8_t axis = 0; axis < kImuAxisCount; ++axis) {
        reading.axes[axis] = big_endian_i16(burst + kBurstOffsets[axis]);
    }
    return reading;
}
```

- [ ] **Step 7: Run the suite to verify it passes**

Run (from `firmware/`): `python tools/run_native_tests.py test_imu_math`
Expected: `13 Tests 0 Failures 0 Ignored`.
Run: `python -m platformio test -e esp32dev -f test_imu_math --without-uploading --without-testing`
Expected: `esp32dev:test_imu_math [SKIPPED]`. CI is authoritative; board: `pio test -e esp32dev -f test_imu_math`.

- [ ] **Step 8: Commit**

```bash
git -C C:/personal/blindside add firmware/lib/imu_math/imu_accumulator.h firmware/lib/imu_math/imu_accumulator.cpp firmware/lib/imu_math/mpu6050_registers.h firmware/lib/imu_math/mpu6050_registers.cpp firmware/test/test_imu_math/test_main.cpp
git -C C:/personal/blindside commit -m "feat: IMU a 50 Hz sobre grilla fija de 5 ms con repetición de lecturas y plan de registros del MPU6050"
```

---

### Task 6: IMU health state machine (`imu_health`)

**Files:**
- Create: `firmware/lib/imu_math/imu_health.h`
- Create: `firmware/lib/imu_math/imu_health.cpp`
- Test: `firmware/test/test_imu_health/test_main.cpp`

**Interfaces:**
- Consumes: nothing.
- Produces (used by Task 13):
  - `constexpr uint8_t kImuFailuresBeforeDown = 5; kImuGoodReadsToRecover = 5; constexpr uint32_t kImuRecoveryPeriodMs = 1000;`
  - `struct ImuHealth { bool ok; bool down; uint8_t consecutive_failures; uint8_t consecutive_good; uint32_t last_recovery_ms; };` (`ok` is the `flags` bit; `down` stops reads until a recovery succeeds)
  - `ImuHealth imu_health_after_probe(bool found, uint32_t now_ms);` (boot and every recovery attempt)
  - `ImuHealth imu_health_after_read(const ImuHealth&, bool read_ok, uint32_t now_ms);`
  - `bool imu_recovery_due(const ImuHealth&, uint32_t now_ms);` (down and ≥ 1000 ms since the last attempt; wrap-safe)
  - `bool imu_should_read(const ImuHealth&);`

- [ ] **Step 1: Write the failing tests `firmware/test/test_imu_health/test_main.cpp`**

```cpp
#include <unity.h>

#include "../test_entry.h"
#include "imu_health.h"

namespace {

ImuHealth after_reads(const ImuHealth& health, bool read_ok, int count, uint32_t now_ms) {
    ImuHealth next = health;
    for (int i = 0; i < count; ++i) {
        next = imu_health_after_read(next, read_ok, now_ms);
    }
    return next;
}

ImuHealth healthy() {
    return after_reads(imu_health_after_probe(true, 0), true, 5, 0);
}

}  // namespace

void setUp() {}
void tearDown() {}

void test_a_found_imu_reports_ok_after_five_good_reads() {
    ImuHealth health = after_reads(imu_health_after_probe(true, 0), true, 4, 0);
    TEST_ASSERT_FALSE(health.ok);
    TEST_ASSERT_TRUE(imu_should_read(health));
    TEST_ASSERT_TRUE(imu_health_after_read(health, true, 0).ok);
}

void test_imu_goes_down_on_the_fifth_consecutive_error() {
    ImuHealth health = after_reads(healthy(), false, 4, 100);
    TEST_ASSERT_TRUE(health.ok);
    TEST_ASSERT_FALSE(health.down);
    health = imu_health_after_read(health, false, 120);
    TEST_ASSERT_FALSE(health.ok);
    TEST_ASSERT_TRUE(health.down);
    TEST_ASSERT_FALSE(imu_should_read(health));
}

void test_a_good_read_resets_the_error_streak() {
    ImuHealth health = after_reads(healthy(), false, 4, 100);
    health = imu_health_after_read(health, true, 105);
    health = after_reads(health, false, 4, 110);
    TEST_ASSERT_TRUE(health.ok);
    TEST_ASSERT_FALSE(health.down);
}

void test_recovery_is_attempted_once_per_second_while_down() {
    ImuHealth health = after_reads(healthy(), false, 5, 1000);
    TEST_ASSERT_FALSE(imu_recovery_due(health, 1999));
    TEST_ASSERT_TRUE(imu_recovery_due(health, 2000));
    health = imu_health_after_probe(false, 2000);
    TEST_ASSERT_FALSE(imu_recovery_due(health, 2999));
    TEST_ASSERT_TRUE(imu_recovery_due(health, 3000));
    TEST_ASSERT_FALSE(imu_recovery_due(healthy(), 5000));
}

void test_a_recovered_imu_is_ok_again_only_after_five_good_reads() {
    ImuHealth health = imu_health_after_probe(true, 3000);
    TEST_ASSERT_FALSE(health.down);
    health = after_reads(health, true, 4, 3005);
    TEST_ASSERT_FALSE(health.ok);
    health = imu_health_after_read(health, false, 3030);
    health = after_reads(health, true, 4, 3035);
    TEST_ASSERT_FALSE(health.ok);
    TEST_ASSERT_TRUE(imu_health_after_read(health, true, 3060).ok);
}

void test_a_missing_imu_starts_down_and_is_retried() {
    ImuHealth health = imu_health_after_probe(false, 0);
    TEST_ASSERT_TRUE(health.down);
    TEST_ASSERT_FALSE(health.ok);
    TEST_ASSERT_TRUE(imu_recovery_due(health, 1000));
}

void test_recovery_timing_survives_a_millis_wrap() {
    ImuHealth health = imu_health_after_probe(false, 0xFFFFFF00u);
    TEST_ASSERT_FALSE(imu_recovery_due(health, 0x000002E7u));
    TEST_ASSERT_TRUE(imu_recovery_due(health, 0x000002E8u));
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_a_found_imu_reports_ok_after_five_good_reads);
    RUN_TEST(test_imu_goes_down_on_the_fifth_consecutive_error);
    RUN_TEST(test_a_good_read_resets_the_error_streak);
    RUN_TEST(test_recovery_is_attempted_once_per_second_while_down);
    RUN_TEST(test_a_recovered_imu_is_ok_again_only_after_five_good_reads);
    RUN_TEST(test_a_missing_imu_starts_down_and_is_retried);
    RUN_TEST(test_recovery_timing_survives_a_millis_wrap);
    return UNITY_END();
}
```

(`0x000002E8` is 0x2E8 + 0x100 = 0x3E8 = 1000 ms after `0xFFFFFF00`.)

- [ ] **Step 2: Run the suite to verify it fails**

Run (from `firmware/`): `python tools/run_native_tests.py test_imu_health`
Expected: the build stops with `fatal error: 'imu_health.h' file not found` and the runner ends with `build failed` and `0 suites passed, 1 failed`.

- [ ] **Step 3: Write `firmware/lib/imu_math/imu_health.h`**

```cpp
#pragma once

#include <stdint.h>

constexpr uint8_t kImuFailuresBeforeDown = 5;
constexpr uint8_t kImuGoodReadsToRecover = 5;
constexpr uint32_t kImuRecoveryPeriodMs = 1000;

struct ImuHealth {
    bool ok;
    bool down;
    uint8_t consecutive_failures;
    uint8_t consecutive_good;
    uint32_t last_recovery_ms;
};

ImuHealth imu_health_after_probe(bool found, uint32_t now_ms);
ImuHealth imu_health_after_read(const ImuHealth& health, bool read_ok, uint32_t now_ms);
bool imu_recovery_due(const ImuHealth& health, uint32_t now_ms);
bool imu_should_read(const ImuHealth& health);
```

- [ ] **Step 4: Write `firmware/lib/imu_math/imu_health.cpp`**

```cpp
#include "imu_health.h"

namespace {

uint8_t saturating_increment(uint8_t value) {
    return value == UINT8_MAX ? value : static_cast<uint8_t>(value + 1);
}

ImuHealth down_since(uint32_t now_ms) {
    ImuHealth health{};
    health.down = true;
    health.last_recovery_ms = now_ms;
    return health;
}

ImuHealth after_good_read(const ImuHealth& health) {
    ImuHealth next = health;
    next.consecutive_failures = 0;
    next.consecutive_good = saturating_increment(health.consecutive_good);
    next.ok = health.ok || next.consecutive_good >= kImuGoodReadsToRecover;
    return next;
}

ImuHealth after_failed_read(const ImuHealth& health, uint32_t now_ms) {
    ImuHealth next = health;
    next.consecutive_good = 0;
    next.consecutive_failures = saturating_increment(health.consecutive_failures);
    return next.consecutive_failures >= kImuFailuresBeforeDown ? down_since(now_ms) : next;
}

}  // namespace

ImuHealth imu_health_after_probe(bool found, uint32_t now_ms) {
    return found ? ImuHealth{} : down_since(now_ms);
}

ImuHealth imu_health_after_read(const ImuHealth& health, bool read_ok, uint32_t now_ms) {
    return read_ok ? after_good_read(health) : after_failed_read(health, now_ms);
}

bool imu_recovery_due(const ImuHealth& health, uint32_t now_ms) {
    return health.down && now_ms - health.last_recovery_ms >= kImuRecoveryPeriodMs;
}

bool imu_should_read(const ImuHealth& health) {
    return !health.down;
}
```

- [ ] **Step 5: Run the suite to verify it passes**

Run (from `firmware/`): `python tools/run_native_tests.py test_imu_health`
Expected: `7 Tests 0 Failures 0 Ignored`.
Run: `python -m platformio test -e esp32dev -f test_imu_health --without-uploading --without-testing`
Expected: `esp32dev:test_imu_health [SKIPPED]`. CI is authoritative; board: `pio test -e esp32dev -f test_imu_health`.

- [ ] **Step 6: Commit**

```bash
git -C C:/personal/blindside add firmware/lib/imu_math/imu_health.h firmware/lib/imu_math/imu_health.cpp firmware/test/test_imu_health/test_main.cpp
git -C C:/personal/blindside commit -m "feat: estado de salud de cada IMU (caída a los 5 errores, recuperación cada segundo)"
```

---

### Task 7: TLV bundler — IMU-first fill order, LINK, splitting (`bundler`)

**Files:**
- Create: `firmware/lib/bundler/bundler.h`
- Create: `firmware/lib/bundler/bundler.cpp`
- Test: `firmware/test/test_bundler/test_main.cpp`

**Interfaces:**
- Consumes: `ImuSample`, `GyroSums`, `kImuAxisCount`, `kImuSamplePeriodMs` from Task 5 (`imu_accumulator.h`); `VEC_BUNDLE_ONE_RADAR`, `VEC_BUNDLE_TYPICAL`, `VEC_BUNDLE_WITH_LINK`, `VEC_LD2450_OFFICIAL_FRAME` from `test/vectors.h` (Task 1).
- Produces (used by Tasks 8, 9, 12, 13, 14, 16):
  - constants `kProtocolVersion 1`, `kPacketHeaderSize 8`, `kMaxPacketSize 244`, `kTlvRadar 0x01`, `kTlvImu 0x02`, `kTlvStatus 0x03`, `kTlvLink 0x04`, `kRadarSectionSize 31`, `kStatusSectionSize 12`, `kLinkSectionSize 8`, `kImuSectionFixedSize 20`, `kImuSampleWireSize 12`, `kMaxImuSamplesPerSection 18`, `kMinStreamPayload 40`, `kRadarCount 2`, `kImuCount 2`, `kRadarTargetsSize 24`, flag bits `kFlagRadarAAlive 0x01 … kFlagDataDropped 0x10`
  - `struct RadarFrame { uint8_t radar_id; uint32_t t_ms; uint8_t targets[24]; };`
  - `struct StatusEntry { uint8_t radar_id; uint16_t bad_frames; uint8_t restarts; uint8_t baud_index; };`
  - `struct LinkParams { uint16_t interval_units; uint16_t latency; uint16_t supervision_units; };`
  - `struct Packet { uint8_t bytes[244]; uint8_t length; };`
  - `struct CutInput { const RadarFrame* radar_frames; size_t radar_count; const ImuSample* imu_samples[2]; size_t imu_counts[2]; bool include_status; StatusEntry status[2]; bool include_link; LinkParams link; };`
  - `struct HeaderFields { uint8_t flags; uint16_t first_seq; uint32_t t_ms; };`
  - `struct BundleResult { size_t packet_count; size_t radar_consumed; size_t imu_consumed[2]; bool status_consumed; bool link_consumed; uint16_t next_seq; };`
  - `struct LinkHealth { bool radar_alive[2]; bool imu_ok[2]; bool data_dropped; };`
  - `size_t payload_limit_for_mtu(uint16_t mtu);`
  - `size_t imu_section_size(size_t sample_count);`
  - `uint8_t packet_flags(const LinkHealth& health);`
  - `CutInput with_status(const CutInput& input, const StatusEntry* status);` (`nullptr` leaves STATUS out)
  - `CutInput with_link(const CutInput& input, const LinkParams& link);`
  - `BundleResult bundle_cut(const CutInput& input, const HeaderFields& header, size_t payload_limit, Packet* packets, size_t max_packets);` — writes IMU 0, IMU 1, STATUS, LINK, then RADAR (frames must be sorted by `t_ms`); each stage starts only if the previous ones were fully written, so consumption is always "IMU/STATUS/LINK prefix, then a RADAR prefix"; nothing is written below a 40 B limit.

- [ ] **Step 1: Write the failing tests `firmware/test/test_bundler/test_main.cpp`**

```cpp
#include <string.h>
#include <unity.h>

#include "../test_entry.h"
#include "../vectors.h"
#include "bundler.h"

namespace {

constexpr size_t kTestPacketCapacity = 16;
Packet g_packets[kTestPacketCapacity];
RadarFrame g_frames[2];
ImuSample g_imu[2][19];

const int16_t kImuAxesA[5][6] = {{0, 0, 4096, 10, -5, 3}, {1, -1, 4095, 11, -5, 3}, {2, -2, 4094, 12, -4, 2},
                                 {-1, 1, 4097, 9, -6, 4}, {0, 0, 4096, 10, -5, 3}};
const int16_t kImuAxesB[5][6] = {{10, 20, 4090, -3, 0, 655}, {11, 21, 4091, -3, 1, 656}, {12, 22, 4092, -2, 1, 657},
                                 {13, 23, 4093, -2, 0, 658}, {14, 24, 4094, -1, 0, 659}};
const int16_t kFlatAxes[6] = {0, 0, 4096, 0, 0, 0};
const StatusEntry kTypicalStatus[2] = {StatusEntry{0, 2, 0, 7}, StatusEntry{1, 0, 1, 7}};

// Offsets of the RADAR targets inside the shared vector: header 8 + IMU 80 + IMU 80 + STATUS 12, then 7 B per RADAR.
constexpr size_t kTypicalRadarATargets = 187;
constexpr size_t kTypicalRadarBTargets = 218;

RadarFrame frame_with(uint8_t radar_id, uint32_t t_ms, const uint8_t* targets) {
    RadarFrame frame{};
    frame.radar_id = radar_id;
    frame.t_ms = t_ms;
    memcpy(frame.targets, targets, kRadarTargetsSize);
    return frame;
}

ImuSample sample_with(uint32_t t_ms, const int16_t* axes, const GyroSums& sums) {
    ImuSample sample{};
    sample.t_ms = t_ms;
    memcpy(sample.mean.axes, axes, sizeof(sample.mean.axes));
    sample.sums = sums;
    return sample;
}

void fill_imu(uint8_t imu_id, const int16_t axes[5][6], const GyroSums& last_sums) {
    for (uint32_t i = 0; i < 5; ++i) {
        GyroSums sums = i == 4 ? last_sums : GyroSums{i, i, i};
        g_imu[imu_id][i] = sample_with(123380 + 20 * i, axes[i], sums);
    }
}

CutInput typical_cut() {
    g_frames[0] = frame_with(0, 123400, VEC_BUNDLE_TYPICAL + kTypicalRadarATargets);
    g_frames[1] = frame_with(1, 123410, VEC_BUNDLE_TYPICAL + kTypicalRadarBTargets);
    fill_imu(0, kImuAxesA, GyroSums{100u, 0xFFFFFFFAu, 7u});
    fill_imu(1, kImuAxesB, GyroSums{0xFFFFB1E0u, 3u, 3000000000u});
    CutInput input{};
    input.radar_frames = g_frames;
    input.radar_count = 2;
    input.imu_samples[0] = g_imu[0];
    input.imu_samples[1] = g_imu[1];
    input.imu_counts[0] = 5;
    input.imu_counts[1] = 5;
    return with_status(input, kTypicalStatus);
}

HeaderFields typical_header() {
    return HeaderFields{0x0F, 65535, 123456};
}

bool sections_are_whole(const Packet& packet) {
    size_t offset = kPacketHeaderSize;
    while (offset + 2 <= packet.length) {
        offset += 2 + packet.bytes[offset + 1];
    }
    return offset == packet.length;
}

uint16_t packet_seq(const Packet& packet) {
    return static_cast<uint16_t>(packet.bytes[2] | (packet.bytes[3] << 8));
}

uint32_t u32_at(const uint8_t* bytes) {
    return static_cast<uint32_t>(bytes[0]) | (static_cast<uint32_t>(bytes[1]) << 8) |
           (static_cast<uint32_t>(bytes[2]) << 16) | (static_cast<uint32_t>(bytes[3]) << 24);
}

void assert_all_typical_content_consumed(const BundleResult& result) {
    TEST_ASSERT_EQUAL_UINT(2, result.radar_consumed);
    TEST_ASSERT_EQUAL_UINT(5, result.imu_consumed[0]);
    TEST_ASSERT_EQUAL_UINT(5, result.imu_consumed[1]);
    TEST_ASSERT_TRUE(result.status_consumed);
}

}  // namespace

void setUp() {
    memset(g_packets, 0, sizeof(g_packets));
}

void tearDown() {}

void test_one_radar_frame_matches_shared_vector() {
    g_frames[0] = frame_with(0, 995, VEC_LD2450_OFFICIAL_FRAME + 4);
    CutInput input{};
    input.radar_frames = g_frames;
    input.radar_count = 1;
    BundleResult result = bundle_cut(input, HeaderFields{0x0F, 1, 1000}, 244, g_packets, kTestPacketCapacity);
    TEST_ASSERT_EQUAL_UINT(1, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(VEC_BUNDLE_ONE_RADAR_LEN, g_packets[0].length);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(VEC_BUNDLE_ONE_RADAR, g_packets[0].bytes, VEC_BUNDLE_ONE_RADAR_LEN);
    TEST_ASSERT_EQUAL_UINT16(2, result.next_seq);
}

void test_typical_cut_matches_shared_vector_in_one_packet() {
    BundleResult result = bundle_cut(typical_cut(), typical_header(), 252, g_packets, kTestPacketCapacity);
    TEST_ASSERT_EQUAL_UINT(1, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(242, g_packets[0].length);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(VEC_BUNDLE_TYPICAL, g_packets[0].bytes, 242);
    TEST_ASSERT_EQUAL_UINT16(0, result.next_seq);
    assert_all_typical_content_consumed(result);
}

void test_small_mtu_sends_imu_first_without_cutting_sections() {
    BundleResult result = bundle_cut(typical_cut(), typical_header(), 100, g_packets, kTestPacketCapacity);
    TEST_ASSERT_EQUAL_UINT(3, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(88, g_packets[0].length);
    TEST_ASSERT_EQUAL_UINT(100, g_packets[1].length);
    TEST_ASSERT_EQUAL_UINT(70, g_packets[2].length);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(VEC_BUNDLE_TYPICAL + 8, g_packets[0].bytes + 8, 80);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(VEC_BUNDLE_TYPICAL + 88, g_packets[1].bytes + 8, 92);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(VEC_BUNDLE_TYPICAL + 180, g_packets[2].bytes + 8, 62);
    TEST_ASSERT_EQUAL_UINT16(65535, packet_seq(g_packets[0]));
    TEST_ASSERT_EQUAL_UINT16(0, packet_seq(g_packets[1]));
    TEST_ASSERT_EQUAL_UINT16(1, packet_seq(g_packets[2]));
    TEST_ASSERT_EQUAL_UINT16(2, result.next_seq);
    for (size_t i = 0; i < result.packet_count; ++i) {
        TEST_ASSERT_TRUE(sections_are_whole(g_packets[i]));
        TEST_ASSERT_EQUAL_UINT8_ARRAY(VEC_BUNDLE_TYPICAL + 4, g_packets[i].bytes + 4, 4);
    }
    assert_all_typical_content_consumed(result);
}

void test_minimum_payload_uses_single_sample_imu_sections() {
    BundleResult result = bundle_cut(typical_cut(), typical_header(), 40, g_packets, kTestPacketCapacity);
    TEST_ASSERT_EQUAL_UINT(13, result.packet_count);
    for (size_t i = 0; i < result.packet_count; ++i) {
        TEST_ASSERT_LESS_OR_EQUAL_UINT(40, g_packets[i].length);
        TEST_ASSERT_TRUE(sections_are_whole(g_packets[i]));
    }
    TEST_ASSERT_EQUAL_HEX8(0x02, g_packets[0].bytes[8]);
    TEST_ASSERT_EQUAL_UINT8(30, g_packets[0].bytes[9]);
    TEST_ASSERT_EQUAL_UINT8(1, g_packets[0].bytes[15]);
    TEST_ASSERT_EQUAL_HEX8(0x03, g_packets[10].bytes[8]);
    TEST_ASSERT_EQUAL_HEX8(0x01, g_packets[11].bytes[8]);
    TEST_ASSERT_EQUAL_HEX8(0x01, g_packets[12].bytes[8]);
    assert_all_typical_content_consumed(result);
}

void test_payload_below_minimum_sends_nothing() {
    BundleResult result = bundle_cut(typical_cut(), typical_header(), 39, g_packets, kTestPacketCapacity);
    TEST_ASSERT_EQUAL_UINT(0, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(0, result.radar_consumed);
    TEST_ASSERT_EQUAL_UINT16(65535, result.next_seq);
}

void test_packet_budget_consumes_only_a_prefix() {
    BundleResult result = bundle_cut(typical_cut(), typical_header(), 100, g_packets, 1);
    TEST_ASSERT_EQUAL_UINT(1, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(5, result.imu_consumed[0]);
    TEST_ASSERT_EQUAL_UINT(0, result.imu_consumed[1]);
    TEST_ASSERT_FALSE(result.status_consumed);
    TEST_ASSERT_EQUAL_UINT(0, result.radar_consumed);
    TEST_ASSERT_EQUAL_UINT16(0, result.next_seq);
}

void test_empty_cut_sends_a_heartbeat_header() {
    const uint8_t expected[8] = {0x01, 0x03, 0x07, 0x00, 0x88, 0x13, 0x00, 0x00};
    BundleResult result = bundle_cut(CutInput{}, HeaderFields{0x03, 7, 5000}, 244, g_packets, kTestPacketCapacity);
    TEST_ASSERT_EQUAL_UINT(1, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(8, g_packets[0].length);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(expected, g_packets[0].bytes, 8);
}

void test_imu_gap_starts_a_new_section() {
    const uint32_t times[5] = {0, 20, 40, 100, 120};
    for (size_t i = 0; i < 5; ++i) {
        g_imu[0][i] = sample_with(times[i], kFlatAxes, GyroSums{0, 0, 0});
    }
    CutInput input{};
    input.imu_samples[0] = g_imu[0];
    input.imu_counts[0] = 5;
    BundleResult result = bundle_cut(input, HeaderFields{0x0F, 0, 200}, 244, g_packets, kTestPacketCapacity);
    TEST_ASSERT_EQUAL_UINT(1, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(108, g_packets[0].length);
    TEST_ASSERT_EQUAL_UINT8(3, g_packets[0].bytes[15]);
    TEST_ASSERT_EQUAL_HEX8(0x02, g_packets[0].bytes[64]);
    TEST_ASSERT_EQUAL_UINT8(100, g_packets[0].bytes[67]);
    TEST_ASSERT_EQUAL_UINT8(2, g_packets[0].bytes[71]);
    TEST_ASSERT_EQUAL_UINT(5, result.imu_consumed[0]);
}

void test_nineteen_samples_split_into_eighteen_and_one() {
    for (uint32_t i = 0; i < 19; ++i) {
        g_imu[0][i] = sample_with(1000 + 20 * i, kFlatAxes, GyroSums{i, 0, 0});
    }
    CutInput input{};
    input.imu_samples[0] = g_imu[0];
    input.imu_counts[0] = 19;
    BundleResult result = bundle_cut(input, HeaderFields{0x0F, 0, 1400}, 244, g_packets, kTestPacketCapacity);
    TEST_ASSERT_EQUAL_UINT(2, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(244, g_packets[0].length);
    TEST_ASSERT_EQUAL_UINT8(18, g_packets[0].bytes[15]);
    TEST_ASSERT_EQUAL_UINT32(17, u32_at(g_packets[0].bytes + 232));
    TEST_ASSERT_EQUAL_UINT(40, g_packets[1].length);
    TEST_ASSERT_EQUAL_UINT32(1360, u32_at(g_packets[1].bytes + 11));
    TEST_ASSERT_EQUAL_UINT8(1, g_packets[1].bytes[15]);
    TEST_ASSERT_EQUAL_UINT(19, result.imu_consumed[0]);
}

void test_link_section_precedes_radar_and_matches_shared_vector() {
    g_frames[0] = frame_with(0, 6990, VEC_LD2450_OFFICIAL_FRAME + 4);
    CutInput input{};
    input.radar_frames = g_frames;
    input.radar_count = 1;
    BundleResult result = bundle_cut(with_link(input, LinkParams{36, 0, 500}), HeaderFields{0x0F, 9, 7000}, 244,
                                     g_packets, kTestPacketCapacity);
    TEST_ASSERT_TRUE(result.link_consumed);
    TEST_ASSERT_EQUAL_UINT(1, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(VEC_BUNDLE_WITH_LINK_LEN, g_packets[0].length);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(VEC_BUNDLE_WITH_LINK, g_packets[0].bytes, VEC_BUNDLE_WITH_LINK_LEN);
}

void test_link_that_does_not_fit_opens_a_new_packet() {
    CutInput input = typical_cut();
    input.imu_counts[1] = 0;
    input.radar_count = 1;
    input = with_link(input, LinkParams{24, 0, 400});
    BundleResult result = bundle_cut(input, typical_header(), 100, g_packets, kTestPacketCapacity);
    TEST_ASSERT_EQUAL_UINT(2, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(100, g_packets[0].length);
    TEST_ASSERT_EQUAL_UINT(47, g_packets[1].length);
    TEST_ASSERT_EQUAL_HEX8(0x04, g_packets[1].bytes[8]);
    TEST_ASSERT_EQUAL_HEX8(0x01, g_packets[1].bytes[16]);
    TEST_ASSERT_TRUE(result.link_consumed);
    BundleResult cramped = bundle_cut(input, typical_header(), 100, g_packets, 1);
    TEST_ASSERT_FALSE(cramped.link_consumed);
    TEST_ASSERT_EQUAL_UINT(0, cramped.radar_consumed);
}

void test_optional_sections_are_requested_through_helpers() {
    const StatusEntry status[2] = {StatusEntry{0, 1, 2, 7}, StatusEntry{1, 3, 4, 5}};
    CutInput input = with_link(with_status(CutInput{}, status), LinkParams{24, 1, 400});
    TEST_ASSERT_TRUE(input.include_status);
    TEST_ASSERT_EQUAL_UINT8(4, input.status[1].restarts);
    TEST_ASSERT_TRUE(input.include_link);
    TEST_ASSERT_EQUAL_UINT16(400, input.link.supervision_units);
    TEST_ASSERT_FALSE(with_status(CutInput{}, nullptr).include_status);
}

void test_flags_encode_health_bits() {
    LinkHealth health{};
    health.radar_alive[0] = true;
    health.imu_ok[1] = true;
    health.data_dropped = true;
    TEST_ASSERT_EQUAL_HEX8(0x19, packet_flags(health));
    TEST_ASSERT_EQUAL_HEX8(0x00, packet_flags(LinkHealth{}));
}

void test_payload_limit_follows_mtu_and_caps_at_244() {
    TEST_ASSERT_EQUAL_UINT(244, payload_limit_for_mtu(255));
    TEST_ASSERT_EQUAL_UINT(244, payload_limit_for_mtu(517));
    TEST_ASSERT_EQUAL_UINT(244, payload_limit_for_mtu(247));
    TEST_ASSERT_EQUAL_UINT(97, payload_limit_for_mtu(100));
    TEST_ASSERT_EQUAL_UINT(20, payload_limit_for_mtu(23));
    TEST_ASSERT_EQUAL_UINT(0, payload_limit_for_mtu(3));
    TEST_ASSERT_EQUAL_UINT(0, payload_limit_for_mtu(0));
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_one_radar_frame_matches_shared_vector);
    RUN_TEST(test_typical_cut_matches_shared_vector_in_one_packet);
    RUN_TEST(test_small_mtu_sends_imu_first_without_cutting_sections);
    RUN_TEST(test_minimum_payload_uses_single_sample_imu_sections);
    RUN_TEST(test_payload_below_minimum_sends_nothing);
    RUN_TEST(test_packet_budget_consumes_only_a_prefix);
    RUN_TEST(test_empty_cut_sends_a_heartbeat_header);
    RUN_TEST(test_imu_gap_starts_a_new_section);
    RUN_TEST(test_nineteen_samples_split_into_eighteen_and_one);
    RUN_TEST(test_link_section_precedes_radar_and_matches_shared_vector);
    RUN_TEST(test_link_that_does_not_fit_opens_a_new_packet);
    RUN_TEST(test_optional_sections_are_requested_through_helpers);
    RUN_TEST(test_flags_encode_health_bits);
    RUN_TEST(test_payload_limit_follows_mtu_and_caps_at_244);
    return UNITY_END();
}
```

Worked layout for `test_small_mtu_sends_imu_first_without_cutting_sections` (limit 100): packet 0 = header + IMU A = 8 + 80 = 88; IMU B (80) does not fit → packet 1 = 8 + 80 + STATUS 12 = 100; RADAR A does not fit → packet 2 = 8 + 31 + 31 = 70. In the shared vector IMU A is bytes 8-87, IMU B + STATUS bytes 88-179 and the two RADAR sections bytes 180-241. For the 40 B limit: 5 + 5 single-sample IMU sections (32 B each) take packets 0-9, STATUS takes packet 10 (20 B; RADAR does not fit after it) and each RADAR takes one packet (11, 12) = 13 packets. With a 1-packet budget at 100 B only IMU A fits, so nothing after it (IMU B, STATUS, RADAR) is consumed. The 19-sample run: 18 samples fill 8 + 20 + 216 = 244 B, the 19th goes alone (40 B) with `t_first` 1000 + 18·20 = 1360; the first section's `sum_gx` (bytes 232-235) is that of sample 17. The LINK case: IMU A + STATUS fill packet 0 (100 B), so LINK opens packet 1 and RADAR follows it (8 + 8 + 31 = 47).

- [ ] **Step 2: Run the suite to verify it fails**

Run (from `firmware/`): `python tools/run_native_tests.py test_bundler`
Expected: the build stops with `fatal error: 'bundler.h' file not found` and the runner ends with `build failed` and `0 suites passed, 1 failed`.

- [ ] **Step 3: Write `firmware/lib/bundler/bundler.h`**

```cpp
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "imu_accumulator.h"

constexpr uint8_t kProtocolVersion = 1;
constexpr size_t kPacketHeaderSize = 8;
constexpr size_t kMaxPacketSize = 244;
constexpr size_t kAttNotifyOverhead = 3;
constexpr uint8_t kTlvRadar = 0x01;
constexpr uint8_t kTlvImu = 0x02;
constexpr uint8_t kTlvStatus = 0x03;
constexpr uint8_t kTlvLink = 0x04;
constexpr size_t kTlvHeaderSize = 2;
constexpr size_t kRadarCount = 2;
constexpr size_t kImuCount = 2;
constexpr size_t kRadarTargetsSize = 24;
constexpr size_t kRadarSectionSize = kTlvHeaderSize + 1 + 4 + kRadarTargetsSize;
constexpr size_t kStatusEntrySize = 5;
constexpr size_t kStatusSectionSize = kTlvHeaderSize + kRadarCount * kStatusEntrySize;
constexpr size_t kLinkSectionSize = kTlvHeaderSize + 3 * 2;
constexpr size_t kImuSectionFixedSize = kTlvHeaderSize + 1 + 4 + 1 + 3 * 4;
constexpr size_t kImuSampleWireSize = 12;
constexpr size_t kMaxImuSamplesPerSection = 18;
constexpr size_t kMinStreamPayload = kPacketHeaderSize + kImuSectionFixedSize + kImuSampleWireSize;

constexpr uint8_t kFlagRadarAAlive = 0x01;
constexpr uint8_t kFlagRadarBAlive = 0x02;
constexpr uint8_t kFlagImuAOk = 0x04;
constexpr uint8_t kFlagImuBOk = 0x08;
constexpr uint8_t kFlagDataDropped = 0x10;

struct RadarFrame {
    uint8_t radar_id;
    uint32_t t_ms;
    uint8_t targets[kRadarTargetsSize];
};

struct StatusEntry {
    uint8_t radar_id;
    uint16_t bad_frames;
    uint8_t restarts;
    uint8_t baud_index;
};

struct LinkParams {
    uint16_t interval_units;
    uint16_t latency;
    uint16_t supervision_units;
};

struct Packet {
    uint8_t bytes[kMaxPacketSize];
    uint8_t length;
};

struct CutInput {
    const RadarFrame* radar_frames;
    size_t radar_count;
    const ImuSample* imu_samples[kImuCount];
    size_t imu_counts[kImuCount];
    bool include_status;
    StatusEntry status[kRadarCount];
    bool include_link;
    LinkParams link;
};

struct HeaderFields {
    uint8_t flags;
    uint16_t first_seq;
    uint32_t t_ms;
};

struct BundleResult {
    size_t packet_count;
    size_t radar_consumed;
    size_t imu_consumed[kImuCount];
    bool status_consumed;
    bool link_consumed;
    uint16_t next_seq;
};

struct LinkHealth {
    bool radar_alive[kRadarCount];
    bool imu_ok[kImuCount];
    bool data_dropped;
};

size_t payload_limit_for_mtu(uint16_t mtu);
size_t imu_section_size(size_t sample_count);
uint8_t packet_flags(const LinkHealth& health);
CutInput with_status(const CutInput& input, const StatusEntry* status);
CutInput with_link(const CutInput& input, const LinkParams& link);
BundleResult bundle_cut(const CutInput& input, const HeaderFields& header, size_t payload_limit, Packet* packets,
                        size_t max_packets);
```

- [ ] **Step 4: Write `firmware/lib/bundler/bundler.cpp`**

```cpp
#include "bundler.h"

#include <string.h>

namespace {

struct PacketWriter {
    Packet* packets;
    size_t max_packets;
    size_t count;
    size_t limit;
    uint8_t flags;
    uint32_t t_ms;
    uint16_t seq;
};

void put_u16(uint8_t* out, uint16_t value) {
    out[0] = static_cast<uint8_t>(value & 0xFF);
    out[1] = static_cast<uint8_t>(value >> 8);
}

void put_u32(uint8_t* out, uint32_t value) {
    for (uint8_t i = 0; i < 4; ++i) {
        out[i] = static_cast<uint8_t>((value >> (8 * i)) & 0xFF);
    }
}

uint8_t flag_if(bool condition, uint8_t flag) {
    return condition ? flag : 0;
}

bool current_packet_fits(const PacketWriter& writer, size_t size) {
    return writer.count > 0 && writer.packets[writer.count - 1].length + size <= writer.limit;
}

bool open_packet(PacketWriter& writer) {
    if (writer.count >= writer.max_packets) {
        return false;
    }
    Packet& packet = writer.packets[writer.count];
    packet.bytes[0] = kProtocolVersion;
    packet.bytes[1] = writer.flags;
    put_u16(packet.bytes + 2, writer.seq);
    put_u32(packet.bytes + 4, writer.t_ms);
    packet.length = kPacketHeaderSize;
    writer.count++;
    writer.seq = static_cast<uint16_t>(writer.seq + 1);
    return true;
}

uint8_t* reserve(PacketWriter& writer, size_t size) {
    bool has_room = current_packet_fits(writer, size) || (open_packet(writer) && current_packet_fits(writer, size));
    if (!has_room) {
        return nullptr;
    }
    Packet& packet = writer.packets[writer.count - 1];
    uint8_t* section = packet.bytes + packet.length;
    packet.length = static_cast<uint8_t>(packet.length + size);
    return section;
}

bool write_radar_section(PacketWriter& writer, const RadarFrame& frame) {
    uint8_t* out = reserve(writer, kRadarSectionSize);
    if (out == nullptr) {
        return false;
    }
    out[0] = kTlvRadar;
    out[1] = static_cast<uint8_t>(kRadarSectionSize - kTlvHeaderSize);
    out[2] = frame.radar_id;
    put_u32(out + 3, frame.t_ms);
    memcpy(out + 7, frame.targets, kRadarTargetsSize);
    return true;
}

uint8_t* put_imu_samples(uint8_t* out, const ImuSample* samples, size_t count) {
    for (size_t i = 0; i < count; ++i) {
        for (uint8_t axis = 0; axis < kImuAxisCount; ++axis) {
            put_u16(out, static_cast<uint16_t>(samples[i].mean.axes[axis]));
            out += 2;
        }
    }
    return out;
}

bool write_imu_section(PacketWriter& writer, uint8_t imu_id, const ImuSample* samples, size_t count) {
    size_t size = imu_section_size(count);
    uint8_t* out = reserve(writer, size);
    if (out == nullptr) {
        return false;
    }
    out[0] = kTlvImu;
    out[1] = static_cast<uint8_t>(size - kTlvHeaderSize);
    out[2] = imu_id;
    put_u32(out + 3, samples[0].t_ms);
    out[7] = static_cast<uint8_t>(count);
    uint8_t* sums = put_imu_samples(out + 8, samples, count);
    const GyroSums& last = samples[count - 1].sums;
    put_u32(sums, last.x);
    put_u32(sums + 4, last.y);
    put_u32(sums + 8, last.z);
    return true;
}

bool write_status_section(PacketWriter& writer, const StatusEntry* status) {
    uint8_t* out = reserve(writer, kStatusSectionSize);
    if (out == nullptr) {
        return false;
    }
    out[0] = kTlvStatus;
    out[1] = static_cast<uint8_t>(kStatusSectionSize - kTlvHeaderSize);
    for (size_t i = 0; i < kRadarCount; ++i) {
        uint8_t* entry = out + kTlvHeaderSize + i * kStatusEntrySize;
        entry[0] = status[i].radar_id;
        put_u16(entry + 1, status[i].bad_frames);
        entry[3] = status[i].restarts;
        entry[4] = status[i].baud_index;
    }
    return true;
}

bool write_link_section(PacketWriter& writer, const LinkParams& link) {
    uint8_t* out = reserve(writer, kLinkSectionSize);
    if (out == nullptr) {
        return false;
    }
    out[0] = kTlvLink;
    out[1] = static_cast<uint8_t>(kLinkSectionSize - kTlvHeaderSize);
    put_u16(out + 2, link.interval_units);
    put_u16(out + 4, link.latency);
    put_u16(out + 6, link.supervision_units);
    return true;
}

size_t samples_per_section(size_t limit) {
    size_t fit = (limit - kPacketHeaderSize - kImuSectionFixedSize) / kImuSampleWireSize;
    return fit < kMaxImuSamplesPerSection ? fit : kMaxImuSamplesPerSection;
}

bool follows_on_grid(const ImuSample& previous, const ImuSample& next) {
    return next.t_ms == previous.t_ms + kImuSamplePeriodMs;
}

size_t contiguous_run(const ImuSample* samples, size_t count, size_t max_run) {
    size_t run = 1;
    while (run < count && run < max_run && follows_on_grid(samples[run - 1], samples[run])) {
        run++;
    }
    return run;
}

size_t write_radar_frames(PacketWriter& writer, const RadarFrame* frames, size_t count) {
    size_t written = 0;
    while (written < count && write_radar_section(writer, frames[written])) {
        written++;
    }
    return written;
}

size_t write_imu_samples(PacketWriter& writer, uint8_t imu_id, const ImuSample* samples, size_t count) {
    size_t max_run = samples_per_section(writer.limit);
    size_t written = 0;
    while (written < count) {
        size_t run = contiguous_run(samples + written, count - written, max_run);
        if (!write_imu_section(writer, imu_id, samples + written, run)) {
            break;
        }
        written += run;
    }
    return written;
}

bool write_imu_group(PacketWriter& writer, const CutInput& input, BundleResult& result) {
    for (uint8_t imu = 0; imu < kImuCount; ++imu) {
        result.imu_consumed[imu] = write_imu_samples(writer, imu, input.imu_samples[imu], input.imu_counts[imu]);
        if (result.imu_consumed[imu] != input.imu_counts[imu]) {
            return false;
        }
    }
    return true;
}

bool write_optional_sections(PacketWriter& writer, const CutInput& input, BundleResult& result) {
    result.status_consumed = input.include_status && write_status_section(writer, input.status);
    if (input.include_status && !result.status_consumed) {
        return false;
    }
    result.link_consumed = input.include_link && write_link_section(writer, input.link);
    return !input.include_link || result.link_consumed;
}

PacketWriter writer_for(const HeaderFields& header, size_t payload_limit, Packet* packets, size_t max_packets) {
    PacketWriter writer{};
    writer.packets = packets;
    writer.max_packets = max_packets;
    writer.limit = payload_limit < kMaxPacketSize ? payload_limit : kMaxPacketSize;
    writer.flags = header.flags;
    writer.t_ms = header.t_ms;
    writer.seq = header.first_seq;
    return writer;
}

}  // namespace

size_t payload_limit_for_mtu(uint16_t mtu) {
    if (mtu <= kAttNotifyOverhead) {
        return 0;
    }
    size_t payload = mtu - kAttNotifyOverhead;
    return payload < kMaxPacketSize ? payload : kMaxPacketSize;
}

size_t imu_section_size(size_t sample_count) {
    return kImuSectionFixedSize + sample_count * kImuSampleWireSize;
}

uint8_t packet_flags(const LinkHealth& health) {
    return flag_if(health.radar_alive[0], kFlagRadarAAlive) | flag_if(health.radar_alive[1], kFlagRadarBAlive) |
           flag_if(health.imu_ok[0], kFlagImuAOk) | flag_if(health.imu_ok[1], kFlagImuBOk) |
           flag_if(health.data_dropped, kFlagDataDropped);
}

CutInput with_status(const CutInput& input, const StatusEntry* status) {
    CutInput next = input;
    next.include_status = status != nullptr;
    if (next.include_status) {
        memcpy(next.status, status, sizeof(next.status));
    }
    return next;
}

CutInput with_link(const CutInput& input, const LinkParams& link) {
    CutInput next = input;
    next.include_link = true;
    next.link = link;
    return next;
}

BundleResult bundle_cut(const CutInput& input, const HeaderFields& header, size_t payload_limit, Packet* packets,
                        size_t max_packets) {
    BundleResult result{};
    result.next_seq = header.first_seq;
    if (payload_limit < kMinStreamPayload || max_packets == 0) {
        return result;
    }
    PacketWriter writer = writer_for(header, payload_limit, packets, max_packets);
    // IMU, STATUS and LINK go before RADAR so a cut's IMU data never arrives after its frames (spec §4.2).
    bool radar_may_follow = write_imu_group(writer, input, result) && write_optional_sections(writer, input, result);
    result.radar_consumed = radar_may_follow ? write_radar_frames(writer, input.radar_frames, input.radar_count) : 0;
    if (writer.count == 0) {
        open_packet(writer);  // a cut with nothing to send still proves the link is alive
    }
    result.packet_count = writer.count;
    result.next_seq = writer.seq;
    return result;
}
```

- [ ] **Step 5: Run the suite to verify it passes**

Run (from `firmware/`): `python tools/run_native_tests.py test_bundler`
Expected: `14 Tests 0 Failures 0 Ignored`.
Run: `python -m platformio test -e esp32dev -f test_bundler --without-uploading --without-testing`
Expected: `esp32dev:test_bundler [SKIPPED]`. CI is authoritative; board: `pio test -e esp32dev -f test_bundler`.

- [ ] **Step 6: Commit**

```bash
git -C C:/personal/blindside add firmware/lib/bundler/bundler.h firmware/lib/bundler/bundler.cpp firmware/test/test_bundler/test_main.cpp
git -C C:/personal/blindside commit -m "feat: empaquetador TLV con IMU primero, sección LINK y partición sin cortar secciones"
```

---

### Task 8: Stream backlog, cut margin and the spec §9 bundler cases (`stream_backlog`)

**Files:**
- Create: `firmware/lib/bundler/stream_backlog.h`
- Create: `firmware/lib/bundler/stream_backlog.cpp`
- Test: `firmware/test/test_stream_backlog/test_main.cpp`
- Test: `firmware/test/test_bundler_spec_cases/test_main.cpp`

**Interfaces:**
- Consumes: from Task 7 `RadarFrame`, `StatusEntry`, `CutInput`, `BundleResult`, `HeaderFields`, `Packet`, `bundle_cut`, `with_status`, `payload_limit_for_mtu`, `kTlvRadar`, `kTlvImu`, `kPacketHeaderSize`, `kRadarSectionSize`, `kImuSampleWireSize`, `kImuCount`; from Task 5 `ImuSample`, `kAz`.
- Produces (used by Task 16):
  - `constexpr size_t kBacklogBudgetBytes = 2400;` (~1 s of stream), `constexpr uint32_t kCutGuardMs = 5;`, `kBacklogRadarCapacity` (78), `kBacklogImuCapacity` (201)
  - `struct StreamBacklog { RadarFrame radar[78]; size_t radar_count; ImuSample imu[2][201]; size_t imu_count[2]; uint32_t dropped_total; bool dropped_since_cut; };` (~16 KB: always a static/global object, never on the stack)
  - `void backlog_clear(StreamBacklog&);` (keeps `dropped_total`)
  - `void backlog_add_radar(StreamBacklog&, const RadarFrame&);` (keeps frames sorted by `t_ms`, enforces the budget)
  - `void backlog_add_imu(StreamBacklog&, uint8_t imu_id, const ImuSample&);`
  - `size_t backlog_pending_bytes(const StreamBacklog&);`
  - `size_t backlog_radar_ready(const StreamBacklog&, uint32_t cutoff_ms);` (frames with `t_ms` < cutoff, wrap-safe)
  - `uint32_t cut_cutoff_ms(uint32_t cut_ms);` (= cut − 5)
  - `CutInput backlog_cut_input(const StreamBacklog&, uint32_t cut_ms);` (frames before `cut_cutoff_ms(cut_ms)`, all IMU samples, no STATUS or LINK: add them with `with_status` / `with_link`)
  - `void backlog_consume(StreamBacklog&, const BundleResult&);` (removes the consumed prefixes; clears `dropped_since_cut` when at least one packet was produced)
  - `void backlog_note_upstream_drops(StreamBacklog&, uint32_t dropped);` (queue overflows upstream of the bundler count as drops and set the flag)

- [ ] **Step 1: Write the failing backlog tests `firmware/test/test_stream_backlog/test_main.cpp`**

```cpp
#include <string.h>
#include <unity.h>

#include "../test_entry.h"
#include "stream_backlog.h"

namespace {

StreamBacklog g_backlog;
Packet g_packets[6];

RadarFrame radar_at(uint8_t radar_id, uint32_t t_ms) {
    RadarFrame frame{};
    frame.radar_id = radar_id;
    frame.t_ms = t_ms;
    return frame;
}

ImuSample imu_at(uint32_t t_ms) {
    ImuSample sample{};
    sample.t_ms = t_ms;
    sample.mean.axes[kAz] = 4096;
    return sample;
}

BundleResult consumed(size_t radar, size_t imu0, size_t imu1, size_t packets) {
    BundleResult result{};
    result.radar_consumed = radar;
    result.imu_consumed[0] = imu0;
    result.imu_consumed[1] = imu1;
    result.packet_count = packets;
    return result;
}

}  // namespace

void setUp() {
    memset(&g_backlog, 0, sizeof(g_backlog));
    backlog_clear(g_backlog);
}

void tearDown() {}

void test_radar_frames_are_kept_in_time_order() {
    backlog_add_radar(g_backlog, radar_at(0, 300));
    backlog_add_radar(g_backlog, radar_at(1, 100));
    backlog_add_radar(g_backlog, radar_at(0, 200));
    TEST_ASSERT_EQUAL_UINT(3, g_backlog.radar_count);
    TEST_ASSERT_EQUAL_UINT32(100, g_backlog.radar[0].t_ms);
    TEST_ASSERT_EQUAL_UINT32(200, g_backlog.radar[1].t_ms);
    TEST_ASSERT_EQUAL_UINT32(300, g_backlog.radar[2].t_ms);
}

void test_only_frames_before_the_cutoff_are_ready() {
    backlog_add_radar(g_backlog, radar_at(0, 100));
    backlog_add_radar(g_backlog, radar_at(1, 200));
    backlog_add_radar(g_backlog, radar_at(0, 300));
    TEST_ASSERT_EQUAL_UINT(0, backlog_radar_ready(g_backlog, 100));
    TEST_ASSERT_EQUAL_UINT(2, backlog_radar_ready(g_backlog, 250));
    TEST_ASSERT_EQUAL_UINT(3, backlog_radar_ready(g_backlog, 301));
}

void test_cut_input_exposes_ready_frames_and_all_imu_samples() {
    backlog_add_radar(g_backlog, radar_at(0, 100));
    backlog_add_radar(g_backlog, radar_at(0, 300));
    backlog_add_imu(g_backlog, 0, imu_at(80));
    backlog_add_imu(g_backlog, 1, imu_at(80));
    backlog_add_imu(g_backlog, 1, imu_at(100));
    CutInput input = backlog_cut_input(g_backlog, 255);
    TEST_ASSERT_EQUAL_UINT(1, input.radar_count);
    TEST_ASSERT_EQUAL_UINT(1, input.imu_counts[0]);
    TEST_ASSERT_EQUAL_UINT(2, input.imu_counts[1]);
    TEST_ASSERT_FALSE(input.include_status);
    TEST_ASSERT_FALSE(input.include_link);
}

void test_cutoff_is_five_ms_before_the_cut() {
    TEST_ASSERT_EQUAL_UINT32(95, cut_cutoff_ms(100));
    TEST_ASSERT_EQUAL_UINT32(0xFFFFFFFDu, cut_cutoff_ms(2));
}

void test_consume_removes_the_bundled_prefixes() {
    backlog_add_radar(g_backlog, radar_at(0, 100));
    backlog_add_radar(g_backlog, radar_at(1, 200));
    backlog_add_radar(g_backlog, radar_at(0, 300));
    backlog_add_imu(g_backlog, 0, imu_at(80));
    backlog_add_imu(g_backlog, 0, imu_at(100));
    backlog_consume(g_backlog, consumed(2, 2, 0, 1));
    TEST_ASSERT_EQUAL_UINT(1, g_backlog.radar_count);
    TEST_ASSERT_EQUAL_UINT32(300, g_backlog.radar[0].t_ms);
    TEST_ASSERT_EQUAL_UINT(0, g_backlog.imu_count[0]);
}

void test_over_budget_drops_oldest_radar_frames_first() {
    for (uint32_t i = 0; i < 77; ++i) {
        backlog_add_radar(g_backlog, radar_at(0, i * 10));
    }
    TEST_ASSERT_EQUAL_UINT32(0, g_backlog.dropped_total);
    backlog_add_radar(g_backlog, radar_at(0, 770));
    TEST_ASSERT_EQUAL_UINT(77, g_backlog.radar_count);
    TEST_ASSERT_EQUAL_UINT32(10, g_backlog.radar[0].t_ms);
    TEST_ASSERT_EQUAL_UINT32(1, g_backlog.dropped_total);
    TEST_ASSERT_TRUE(g_backlog.dropped_since_cut);
    TEST_ASSERT_LESS_OR_EQUAL_UINT(kBacklogBudgetBytes, backlog_pending_bytes(g_backlog));
}

void test_radar_frames_are_dropped_before_imu_samples() {
    for (uint32_t i = 0; i < 200; ++i) {
        backlog_add_imu(g_backlog, 0, imu_at(i * 20));
    }
    backlog_add_radar(g_backlog, radar_at(0, 5000));
    TEST_ASSERT_EQUAL_UINT(0, g_backlog.radar_count);
    TEST_ASSERT_EQUAL_UINT(200, g_backlog.imu_count[0]);
    TEST_ASSERT_EQUAL_UINT32(1, g_backlog.dropped_total);
}

void test_without_radar_frames_the_oldest_imu_sample_is_dropped() {
    for (uint32_t i = 0; i < 200; ++i) {
        backlog_add_imu(g_backlog, 0, imu_at(i * 20));
    }
    backlog_add_imu(g_backlog, 1, imu_at(5000));
    TEST_ASSERT_EQUAL_UINT(199, g_backlog.imu_count[0]);
    TEST_ASSERT_EQUAL_UINT32(20, g_backlog.imu[0][0].t_ms);
    TEST_ASSERT_EQUAL_UINT(1, g_backlog.imu_count[1]);
    TEST_ASSERT_EQUAL_UINT32(1, g_backlog.dropped_total);
}

void test_dropped_flag_clears_only_after_a_packet_was_built() {
    for (uint32_t i = 0; i < 78; ++i) {
        backlog_add_radar(g_backlog, radar_at(0, i * 10));
    }
    backlog_consume(g_backlog, consumed(0, 0, 0, 0));
    TEST_ASSERT_TRUE(g_backlog.dropped_since_cut);
    backlog_consume(g_backlog, consumed(1, 0, 0, 1));
    TEST_ASSERT_FALSE(g_backlog.dropped_since_cut);
}

void test_clear_empties_but_keeps_the_drop_total() {
    for (uint32_t i = 0; i < 78; ++i) {
        backlog_add_radar(g_backlog, radar_at(0, i * 10));
    }
    backlog_clear(g_backlog);
    TEST_ASSERT_EQUAL_UINT(0, backlog_pending_bytes(g_backlog));
    TEST_ASSERT_EQUAL_UINT32(1, g_backlog.dropped_total);
    TEST_ASSERT_FALSE(g_backlog.dropped_since_cut);
}

void test_a_typical_cut_empties_the_backlog_in_one_packet() {
    StatusEntry status[2] = {StatusEntry{0, 0, 0, 7}, StatusEntry{1, 0, 0, 7}};
    backlog_add_radar(g_backlog, radar_at(0, 100));
    backlog_add_radar(g_backlog, radar_at(1, 110));
    for (uint32_t i = 0; i < 5; ++i) {
        backlog_add_imu(g_backlog, 0, imu_at(80 + 20 * i));
        backlog_add_imu(g_backlog, 1, imu_at(80 + 20 * i));
    }
    CutInput input = with_status(backlog_cut_input(g_backlog, 200), status);
    BundleResult result = bundle_cut(input, HeaderFields{0x0F, 0, 200}, 244, g_packets, 6);
    backlog_consume(g_backlog, result);
    TEST_ASSERT_EQUAL_UINT(1, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(242, g_packets[0].length);
    TEST_ASSERT_EQUAL_UINT(0, backlog_pending_bytes(g_backlog));
}

void test_upstream_drops_set_the_flag_and_the_total() {
    backlog_note_upstream_drops(g_backlog, 0);
    TEST_ASSERT_FALSE(g_backlog.dropped_since_cut);
    backlog_note_upstream_drops(g_backlog, 3);
    TEST_ASSERT_TRUE(g_backlog.dropped_since_cut);
    TEST_ASSERT_EQUAL_UINT32(3, g_backlog.dropped_total);
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_radar_frames_are_kept_in_time_order);
    RUN_TEST(test_only_frames_before_the_cutoff_are_ready);
    RUN_TEST(test_cut_input_exposes_ready_frames_and_all_imu_samples);
    RUN_TEST(test_cutoff_is_five_ms_before_the_cut);
    RUN_TEST(test_consume_removes_the_bundled_prefixes);
    RUN_TEST(test_over_budget_drops_oldest_radar_frames_first);
    RUN_TEST(test_radar_frames_are_dropped_before_imu_samples);
    RUN_TEST(test_without_radar_frames_the_oldest_imu_sample_is_dropped);
    RUN_TEST(test_dropped_flag_clears_only_after_a_packet_was_built);
    RUN_TEST(test_clear_empties_but_keeps_the_drop_total);
    RUN_TEST(test_a_typical_cut_empties_the_backlog_in_one_packet);
    RUN_TEST(test_upstream_drops_set_the_flag_and_the_total);
    return UNITY_END();
}
```

Budget arithmetic: 77 radar frames × 31 B = 2387 B ≤ 2400; the 78th makes 2418 B, so the oldest frame goes. 200 IMU samples × 12 B = 2400 B; one more item of either kind exceeds the budget.

- [ ] **Step 2: Write the failing spec §9 cases `firmware/test/test_bundler_spec_cases/test_main.cpp`**

```cpp
#include <string.h>
#include <unity.h>

#include "../test_entry.h"
#include "stream_backlog.h"

namespace {

constexpr size_t kTestPacketCapacity = 8;
StreamBacklog g_backlog;
Packet g_packets[kTestPacketCapacity];

struct CutWalk {
    size_t imu_sections;
    size_t radar_sections;
    bool imu_after_radar;
    bool radar_time_went_back;
    bool sections_whole;
    size_t longest_packet;
    uint32_t last_radar_t_ms;
};

RadarFrame radar_at(uint8_t radar_id, uint32_t t_ms) {
    RadarFrame frame{};
    frame.radar_id = radar_id;
    frame.t_ms = t_ms;
    return frame;
}

void add_imu_run(uint8_t imu_id, uint32_t first_t_ms, uint32_t count) {
    for (uint32_t i = 0; i < count; ++i) {
        ImuSample sample{};
        sample.t_ms = first_t_ms + 20 * i;
        backlog_add_imu(g_backlog, imu_id, sample);
    }
}

void add_radar_frames(const RadarFrame* frames, size_t count) {
    for (size_t i = 0; i < count; ++i) {
        backlog_add_radar(g_backlog, frames[i]);
    }
}

uint32_t u32_at(const uint8_t* bytes) {
    return static_cast<uint32_t>(bytes[0]) | (static_cast<uint32_t>(bytes[1]) << 8) |
           (static_cast<uint32_t>(bytes[2]) << 16) | (static_cast<uint32_t>(bytes[3]) << 24);
}

CutWalk walked_radar(const CutWalk& walk, const uint8_t* section) {
    CutWalk next = walk;
    uint32_t t_ms = u32_at(section + 3);
    next.radar_time_went_back = walk.radar_time_went_back || (walk.radar_sections > 0 && t_ms < walk.last_radar_t_ms);
    next.last_radar_t_ms = t_ms;
    next.radar_sections++;
    return next;
}

CutWalk walked_imu(const CutWalk& walk) {
    CutWalk next = walk;
    next.imu_after_radar = walk.imu_after_radar || walk.radar_sections > 0;
    next.imu_sections++;
    return next;
}

CutWalk walked_section(const CutWalk& walk, const uint8_t* section) {
    if (section[0] == kTlvRadar) {
        return walked_radar(walk, section);
    }
    return section[0] == kTlvImu ? walked_imu(walk) : walk;
}

CutWalk walked_packet(const CutWalk& walk, const Packet& packet) {
    CutWalk next = walk;
    next.longest_packet = packet.length > walk.longest_packet ? packet.length : walk.longest_packet;
    size_t offset = kPacketHeaderSize;
    while (offset + 2 <= packet.length) {
        next = walked_section(next, packet.bytes + offset);
        offset += 2 + packet.bytes[offset + 1];
    }
    next.sections_whole = next.sections_whole && offset == packet.length;
    return next;
}

CutWalk walk_packets(const BundleResult& result) {
    CutWalk walk{};
    walk.sections_whole = true;
    for (size_t i = 0; i < result.packet_count; ++i) {
        walk = walked_packet(walk, g_packets[i]);
    }
    return walk;
}

BundleResult cut_at(uint32_t cut_ms, size_t payload_limit) {
    CutInput input = backlog_cut_input(g_backlog, cut_ms);
    return bundle_cut(input, HeaderFields{0x0F, 0, cut_ms}, payload_limit, g_packets, kTestPacketCapacity);
}

void add_three_frames_and_imus(uint32_t imu_a_samples, uint32_t imu_b_samples) {
    const RadarFrame frames[] = {radar_at(0, 10), radar_at(1, 40), radar_at(0, 90)};
    add_radar_frames(frames, 3);
    add_imu_run(0, 10, imu_a_samples);
    add_imu_run(1, 10, imu_b_samples);
}

void assert_clean_split(const BundleResult& result, size_t payload_limit, size_t radar_sections) {
    CutWalk walk = walk_packets(result);
    TEST_ASSERT_TRUE(walk.sections_whole);
    TEST_ASSERT_LESS_OR_EQUAL_UINT(payload_limit, walk.longest_packet);
    TEST_ASSERT_EQUAL_UINT(radar_sections, walk.radar_sections);
    TEST_ASSERT_FALSE(walk.imu_after_radar);
    TEST_ASSERT_FALSE(walk.radar_time_went_back);
}

}  // namespace

void setUp() {
    memset(&g_backlog, 0, sizeof(g_backlog));
    memset(g_packets, 0, sizeof(g_packets));
    backlog_clear(g_backlog);
}

void tearDown() {}

void test_mtu_255_splits_249_bytes_of_content_without_passing_244() {
    add_three_frames_and_imus(4, 5);
    BundleResult result = cut_at(100, payload_limit_for_mtu(255));
    TEST_ASSERT_EQUAL_UINT(2, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(218, g_packets[0].length);
    TEST_ASSERT_EQUAL_UINT(39, g_packets[1].length);
    assert_clean_split(result, 244, 3);
}

void test_mtu_185_still_splits_without_breaking_sections() {
    add_three_frames_and_imus(4, 5);
    BundleResult result = cut_at(100, payload_limit_for_mtu(185));
    TEST_ASSERT_EQUAL_UINT(2, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(156, g_packets[0].length);
    TEST_ASSERT_EQUAL_UINT(101, g_packets[1].length);
    assert_clean_split(result, 182, 3);
}

void test_three_frames_and_two_seven_sample_imus_take_two_packets() {
    add_three_frames_and_imus(7, 7);
    BundleResult result = cut_at(100, 244);
    TEST_ASSERT_EQUAL_UINT(2, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(216, g_packets[0].length);
    TEST_ASSERT_EQUAL_UINT(101, g_packets[1].length);
    assert_clean_split(result, 244, 3);
}

void test_cut_margin_takes_the_frame_at_94_and_defers_the_one_at_96() {
    const RadarFrame frames[] = {radar_at(0, 94), radar_at(1, 96)};
    add_radar_frames(frames, 2);
    CutInput first = backlog_cut_input(g_backlog, 100);
    TEST_ASSERT_EQUAL_UINT(1, first.radar_count);
    TEST_ASSERT_EQUAL_UINT32(94, first.radar_frames[0].t_ms);
    backlog_consume(g_backlog, bundle_cut(first, HeaderFields{0x03, 0, 100}, 244, g_packets, kTestPacketCapacity));
    CutInput second = backlog_cut_input(g_backlog, 200);
    TEST_ASSERT_EQUAL_UINT(1, second.radar_count);
    TEST_ASSERT_EQUAL_UINT32(96, second.radar_frames[0].t_ms);
}

void assert_split_order(size_t payload_limit, size_t expected_packets) {
    setUp();
    const RadarFrame frames[] = {radar_at(0, 12), radar_at(0, 92), radar_at(1, 40)};
    add_radar_frames(frames, 3);
    add_imu_run(0, 10, 6);
    add_imu_run(1, 10, 6);
    BundleResult result = cut_at(100, payload_limit);
    TEST_ASSERT_EQUAL_UINT(expected_packets, result.packet_count);
    TEST_ASSERT_EQUAL_UINT(3, result.radar_consumed);
    assert_clean_split(result, payload_limit, 3);
}

void test_split_order_keeps_radar_time_order_and_imu_first() {
    assert_split_order(241, 2);
    assert_split_order(100, 4);
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_mtu_255_splits_249_bytes_of_content_without_passing_244);
    RUN_TEST(test_mtu_185_still_splits_without_breaking_sections);
    RUN_TEST(test_three_frames_and_two_seven_sample_imus_take_two_packets);
    RUN_TEST(test_cut_margin_takes_the_frame_at_94_and_defers_the_one_at_96);
    RUN_TEST(test_split_order_keeps_radar_time_order_and_imu_first);
    return UNITY_END();
}
```

These are the native `bundler` cases spec §9 "Firmware" lists by name, built through the backlog exactly as the bundler task builds a cut. Worked layouts (IMU first, then RADAR in `t_ms` order): MTU 255 → limit 244, content 8 + 68 + 80 + 3 × 31 = 249 B → [IMU 4, IMU 5, RADAR@10, RADAR@40] = 218 B and [RADAR@90] = 39 B. MTU 185 → limit 182 → [IMU 4, IMU 5] = 156 B and [3 × RADAR] = 101 B. Two IMUs with n = 7 (104 B each) at 244 → [IMU, IMU] = 216 B and [3 × RADAR] = 101 B. Split order with frames added as A@12, A@92, B@40 and two IMUs with n = 6 (92 B): at 241 → [IMU, IMU, RADAR@12] and [RADAR@40, RADAR@92]; at 100 → [IMU], [IMU], [RADAR@12, RADAR@40], [RADAR@92].

- [ ] **Step 3: Run both suites to verify they fail**

Run (from `firmware/`): `python tools/run_native_tests.py test_stream_backlog test_bundler_spec_cases`
Expected: both builds stop with `fatal error: 'stream_backlog.h' file not found` and the runner ends with `0 suites passed, 2 failed`.

- [ ] **Step 4: Write `firmware/lib/bundler/stream_backlog.h`**

```cpp
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "bundler.h"

constexpr size_t kBacklogBudgetBytes = 2400;
// A frame stamped in the last few ms may still have an older partner frame in flight on the other UART.
constexpr uint32_t kCutGuardMs = 5;
constexpr size_t kBacklogRadarCapacity = kBacklogBudgetBytes / kRadarSectionSize + 1;
constexpr size_t kBacklogImuCapacity = kBacklogBudgetBytes / kImuSampleWireSize + 1;

struct StreamBacklog {
    RadarFrame radar[kBacklogRadarCapacity];
    size_t radar_count;
    ImuSample imu[kImuCount][kBacklogImuCapacity];
    size_t imu_count[kImuCount];
    uint32_t dropped_total;
    bool dropped_since_cut;
};

void backlog_clear(StreamBacklog& backlog);
void backlog_add_radar(StreamBacklog& backlog, const RadarFrame& frame);
void backlog_add_imu(StreamBacklog& backlog, uint8_t imu_id, const ImuSample& sample);
size_t backlog_pending_bytes(const StreamBacklog& backlog);
size_t backlog_radar_ready(const StreamBacklog& backlog, uint32_t cutoff_ms);
uint32_t cut_cutoff_ms(uint32_t cut_ms);
CutInput backlog_cut_input(const StreamBacklog& backlog, uint32_t cut_ms);
void backlog_consume(StreamBacklog& backlog, const BundleResult& result);
void backlog_note_upstream_drops(StreamBacklog& backlog, uint32_t dropped);
```

- [ ] **Step 5: Write `firmware/lib/bundler/stream_backlog.cpp`**

```cpp
#include "stream_backlog.h"

#include <string.h>

namespace {

bool time_before(uint32_t a_ms, uint32_t b_ms) {
    return static_cast<int32_t>(a_ms - b_ms) < 0;
}

template <typename T>
void remove_front(T* items, size_t& count, size_t removed) {
    size_t kept = removed < count ? count - removed : 0;
    memmove(items, items + (count - kept), kept * sizeof(T));
    count = kept;
}

void mark_dropped(StreamBacklog& backlog) {
    backlog.dropped_total++;
    backlog.dropped_since_cut = true;
}

void drop_oldest_radar(StreamBacklog& backlog) {
    remove_front(backlog.radar, backlog.radar_count, 1);
    mark_dropped(backlog);
}

void drop_oldest_imu(StreamBacklog& backlog, uint8_t imu_id) {
    remove_front(backlog.imu[imu_id], backlog.imu_count[imu_id], 1);
    mark_dropped(backlog);
}

uint8_t imu_holding_oldest_sample(const StreamBacklog& backlog) {
    if (backlog.imu_count[0] == 0) {
        return 1;
    }
    if (backlog.imu_count[1] == 0) {
        return 0;
    }
    return time_before(backlog.imu[1][0].t_ms, backlog.imu[0][0].t_ms) ? 1 : 0;
}

void drop_one_item(StreamBacklog& backlog) {
    if (backlog.radar_count > 0) {
        drop_oldest_radar(backlog);
        return;
    }
    drop_oldest_imu(backlog, imu_holding_oldest_sample(backlog));
}

void enforce_budget(StreamBacklog& backlog) {
    while (backlog_pending_bytes(backlog) > kBacklogBudgetBytes) {
        drop_one_item(backlog);
    }
}

size_t radar_insert_position(const StreamBacklog& backlog, uint32_t t_ms) {
    size_t position = backlog.radar_count;
    while (position > 0 && time_before(t_ms, backlog.radar[position - 1].t_ms)) {
        position--;
    }
    return position;
}

void insert_radar_in_time_order(StreamBacklog& backlog, const RadarFrame& frame) {
    size_t position = radar_insert_position(backlog, frame.t_ms);
    size_t moved = backlog.radar_count - position;
    memmove(&backlog.radar[position + 1], &backlog.radar[position], moved * sizeof(RadarFrame));
    backlog.radar[position] = frame;
    backlog.radar_count++;
}

}  // namespace

void backlog_clear(StreamBacklog& backlog) {
    backlog.radar_count = 0;
    backlog.imu_count[0] = 0;
    backlog.imu_count[1] = 0;
    backlog.dropped_since_cut = false;
}

void backlog_add_radar(StreamBacklog& backlog, const RadarFrame& frame) {
    if (backlog.radar_count == kBacklogRadarCapacity) {
        drop_oldest_radar(backlog);
    }
    insert_radar_in_time_order(backlog, frame);
    enforce_budget(backlog);
}

void backlog_add_imu(StreamBacklog& backlog, uint8_t imu_id, const ImuSample& sample) {
    if (imu_id >= kImuCount) {
        return;
    }
    if (backlog.imu_count[imu_id] == kBacklogImuCapacity) {
        drop_oldest_imu(backlog, imu_id);
    }
    backlog.imu[imu_id][backlog.imu_count[imu_id]] = sample;
    backlog.imu_count[imu_id]++;
    enforce_budget(backlog);
}

size_t backlog_pending_bytes(const StreamBacklog& backlog) {
    size_t imu_samples = backlog.imu_count[0] + backlog.imu_count[1];
    return backlog.radar_count * kRadarSectionSize + imu_samples * kImuSampleWireSize;
}

size_t backlog_radar_ready(const StreamBacklog& backlog, uint32_t cutoff_ms) {
    size_t ready = 0;
    while (ready < backlog.radar_count && time_before(backlog.radar[ready].t_ms, cutoff_ms)) {
        ready++;
    }
    return ready;
}

uint32_t cut_cutoff_ms(uint32_t cut_ms) {
    return cut_ms - kCutGuardMs;
}

CutInput backlog_cut_input(const StreamBacklog& backlog, uint32_t cut_ms) {
    CutInput input{};
    input.radar_frames = backlog.radar;
    input.radar_count = backlog_radar_ready(backlog, cut_cutoff_ms(cut_ms));
    for (uint8_t imu = 0; imu < kImuCount; ++imu) {
        input.imu_samples[imu] = backlog.imu[imu];
        input.imu_counts[imu] = backlog.imu_count[imu];
    }
    return input;
}

void backlog_consume(StreamBacklog& backlog, const BundleResult& result) {
    remove_front(backlog.radar, backlog.radar_count, result.radar_consumed);
    for (uint8_t imu = 0; imu < kImuCount; ++imu) {
        remove_front(backlog.imu[imu], backlog.imu_count[imu], result.imu_consumed[imu]);
    }
    if (result.packet_count > 0) {
        backlog.dropped_since_cut = false;
    }
}

void backlog_note_upstream_drops(StreamBacklog& backlog, uint32_t dropped) {
    if (dropped == 0) {
        return;
    }
    backlog.dropped_total += dropped;
    backlog.dropped_since_cut = true;
}
```

- [ ] **Step 6: Run both suites to verify they pass**

Run (from `firmware/`): `python tools/run_native_tests.py test_stream_backlog test_bundler_spec_cases`
Expected: `12 Tests 0 Failures 0 Ignored`, `5 Tests 0 Failures 0 Ignored` and `2 suites passed, 0 failed`.
Run: `python -m platformio test -e esp32dev -f test_stream_backlog -f test_bundler_spec_cases --without-uploading --without-testing`
Expected: both suites `[SKIPPED]`. CI is authoritative; board: `pio test -e esp32dev -f test_stream_backlog -f test_bundler_spec_cases`.

- [ ] **Step 7: Commit**

```bash
git -C C:/personal/blindside add firmware/lib/bundler/stream_backlog.h firmware/lib/bundler/stream_backlog.cpp firmware/test/test_stream_backlog/test_main.cpp firmware/test/test_bundler_spec_cases/test_main.cpp
git -C C:/personal/blindside commit -m "feat: cola de envío acotada, margen de corte de 5 ms y casos del bundler de la spec"
```

---

### Task 9: Link rules — control writes, stream gate, `info` JSON, device name (`belt_rules`)

**Files:**
- Create: `firmware/lib/belt_rules/control_command.h`
- Create: `firmware/lib/belt_rules/control_command.cpp`
- Create: `firmware/lib/belt_rules/ble_rules.h`
- Create: `firmware/lib/belt_rules/ble_rules.cpp`
- Create: `firmware/lib/belt_rules/info_json.h`
- Create: `firmware/lib/belt_rules/info_json.cpp`
- Test: `firmware/test/test_belt_rules_link/test_main.cpp`

**Interfaces:**
- Consumes: Task 7 `kProtocolVersion`, `kRadarCount`, `kImuCount`, `LinkParams`; Task 3 `FirmwareText`.
- Produces (used by Tasks 14, 16, 17):
  - `enum class ControlKind : uint8_t { None, Invalid, RestartRadar, Identify, SessionActive };`
  - `struct ControlCommand { ControlKind kind; uint8_t argument; };`
  - `constexpr size_t kMaxControlSize = 4;`
  - `ControlCommand parse_control(const uint8_t* bytes, size_t length);` (never returns `None`)
  - `bool identify_allowed(bool session_active);`
  - `enum class AdvertisingSpeed : uint8_t { Fast, Slow };`, `constexpr uint32_t kFastAdvertisingWindowMs = 30000;`, `constexpr uint16_t kMinNotifyMtu = 247;`
  - `struct StreamGateInput { bool connected; bool subscribed; bool trusted; uint16_t mtu; };`
  - `bool stream_gate_open(const StreamGateInput&);` (connected && subscribed && trusted && mtu ≥ 247)
  - `AdvertisingSpeed advertising_speed(uint32_t advertising_for_ms);`
  - `struct DeviceName { char text[16]; };` `DeviceName device_name_from_mac(const uint8_t* mac);` (6-byte MAC, uses bytes 4 and 5)
  - `constexpr size_t kInfoJsonMaxBytes = 400; kInfoJsonBufferSize = 401;`
  - `struct RadarInfo { uint8_t id; FirmwareText firmware; uint32_t baud; };`
  - `struct ImuInfo { uint8_t id; uint8_t who_am_i; };`
  - `struct BeltInfo { const char* firmware_version; uint32_t boot_id; const char* reset_reason; uint16_t mtu; RadarInfo radars[2]; ImuInfo imus[2]; int8_t tx_power_dbm; LinkParams conn; uint32_t uptime_s; };`
  - `size_t format_info_json(const BeltInfo&, char* out, size_t out_size);` (0 when it does not fit; the contract's example byte for byte, 388 B; worst case 400 B)

- [ ] **Step 1: Write the failing tests `firmware/test/test_belt_rules_link/test_main.cpp`**

```cpp
#include <string.h>
#include <unity.h>

#include "../test_entry.h"
#include "ble_rules.h"
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

BeltInfo example_info() {
    BeltInfo info{};
    info.firmware_version = "0.1.0";
    info.boot_id = 0x9f3a12c4u;
    info.reset_reason = "POWERON";
    info.mtu = 255;
    info.radars[0] = RadarInfo{0, firmware_named("V2.04.23101915"), 256000};
    info.radars[1] = RadarInfo{1, firmware_named("V2.04.23101915"), 256000};
    info.imus[0] = ImuInfo{0, 104};
    info.imus[1] = ImuInfo{1, 112};
    info.tx_power_dbm = 9;
    info.conn = LinkParams{36, 0, 500};
    info.uptime_s = 42;
    return info;
}

BeltInfo longest_info() {
    BeltInfo info = example_info();
    info.reset_reason = "DEEPSLEEP";
    info.mtu = 517;
    info.radars[0].baud = 460800;
    info.radars[1].baud = 460800;
    info.imus[0].who_am_i = 255;
    info.imus[1].who_am_i = 255;
    info.conn = LinkParams{3200, 499, 3200};
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
        R"({"proto":1,"fw":"0.1.0","boot_id":"9f3a12c4","reset":"POWERON","mtu":255,)"
        R"("radars":[{"id":0,"fw":"V2.04.23101915","baud":256000},{"id":1,"fw":"V2.04.23101915","baud":256000}],)"
        R"("imus":[{"id":0,"who":104,"gyro_lsb_dps":65.5,"accel_lsb_g":4096},)"
        R"({"id":1,"who":112,"gyro_lsb_dps":65.5,"accel_lsb_g":4096}],)"
        R"("tx_power_dbm":9,"conn":{"interval_ms":45.0,"latency":0,"timeout_ms":5000},"uptime_s":42})";
    char json[kInfoJsonBufferSize];
    size_t length = format_info_json(example_info(), json, sizeof(json));
    TEST_ASSERT_EQUAL_STRING(expected, json);
    TEST_ASSERT_EQUAL_UINT(388, length);
}

void test_info_json_worst_case_still_fits_in_400_bytes() {
    char json[kInfoJsonBufferSize];
    size_t length = format_info_json(longest_info(), json, sizeof(json));
    TEST_ASSERT_EQUAL_UINT(400, length);
    TEST_ASSERT_NOT_NULL(strstr(json, R"("conn":{"interval_ms":4000.0,"latency":499,"timeout_ms":32000})"));
}

void test_info_json_rounds_the_interval_to_one_decimal() {
    BeltInfo info = example_info();
    info.conn = LinkParams{39, 0, 400};
    char json[kInfoJsonBufferSize];
    format_info_json(info, json, sizeof(json));
    TEST_ASSERT_NOT_NULL(strstr(json, R"("interval_ms":48.8,)"));
    TEST_ASSERT_NOT_NULL(strstr(json, R"("timeout_ms":4000})"));
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
    RUN_TEST(test_identify_is_refused_during_a_session);
    RUN_TEST(test_stream_gate_needs_subscribed_trusted_peer_and_mtu_247);
    RUN_TEST(test_advertising_is_fast_for_thirty_seconds);
    RUN_TEST(test_device_name_uses_last_two_mac_bytes);
    RUN_TEST(test_info_json_matches_the_contract_example);
    RUN_TEST(test_info_json_worst_case_still_fits_in_400_bytes);
    RUN_TEST(test_info_json_rounds_the_interval_to_one_decimal);
    RUN_TEST(test_info_json_reports_a_missing_radar);
    RUN_TEST(test_info_json_reports_zero_when_it_does_not_fit);
    return UNITY_END();
}
```

`longest_info()` is the worst case the firmware can produce: the longest reset name (`DEEPSLEEP`), 7-digit uptime (the largest `millis() / 1000`), 3-digit MTU and WHO_AM_I, 6-digit baud on both radars, the BLE maxima for the connection (4 s interval, latency 499, 32 s timeout) and the configured +9 dBm. It is exactly 400 B, so the per-IMU repeat counts cannot be added (they go to serial, see "Contract notes"). Interval 39 units = 48.75 ms, printed with one decimal rounded half up: `48.8`.

- [ ] **Step 2: Run the suite to verify it fails**

Run (from `firmware/`): `python tools/run_native_tests.py test_belt_rules_link`
Expected: the build stops with `fatal error: 'ble_rules.h' file not found` and the runner ends with `build failed` and `0 suites passed, 1 failed`.

- [ ] **Step 3: Write `firmware/lib/belt_rules/control_command.h`**

```cpp
#pragma once

#include <stddef.h>
#include <stdint.h>

constexpr uint8_t kControlRestartRadar = 0x01;
constexpr uint8_t kControlIdentify = 0x03;
constexpr uint8_t kControlSessionActive = 0x04;
constexpr size_t kMaxControlSize = 4;

enum class ControlKind : uint8_t { None, Invalid, RestartRadar, Identify, SessionActive };

struct ControlCommand {
    ControlKind kind;
    uint8_t argument;
};

ControlCommand parse_control(const uint8_t* bytes, size_t length);
bool identify_allowed(bool session_active);
```

- [ ] **Step 4: Write `firmware/lib/belt_rules/control_command.cpp`**

```cpp
#include "control_command.h"

namespace {

constexpr uint8_t kMaxBinaryArgument = 1;

ControlCommand invalid_command() {
    return ControlCommand{ControlKind::Invalid, 0};
}

ControlCommand command_with_binary_argument(ControlKind kind, const uint8_t* bytes, size_t length) {
    if (length != 2 || bytes[1] > kMaxBinaryArgument) {
        return invalid_command();
    }
    return ControlCommand{kind, bytes[1]};
}

ControlCommand identify_command(size_t length) {
    return length == 1 ? ControlCommand{ControlKind::Identify, 0} : invalid_command();
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
            return identify_command(length);
        case kControlSessionActive:
            return command_with_binary_argument(ControlKind::SessionActive, bytes, length);
        default:
            return invalid_command();
    }
}

bool identify_allowed(bool session_active) {
    return !session_active;
}
```

- [ ] **Step 5: Write `firmware/lib/belt_rules/ble_rules.h`**

```cpp
#pragma once

#include <stddef.h>
#include <stdint.h>

constexpr uint32_t kFastAdvertisingWindowMs = 30000;
constexpr uint16_t kMinNotifyMtu = 247;
constexpr size_t kDeviceNameSize = 16;

enum class AdvertisingSpeed : uint8_t { Fast, Slow };

struct DeviceName {
    char text[kDeviceNameSize];
};

struct StreamGateInput {
    bool connected;
    bool subscribed;
    bool trusted;
    uint16_t mtu;
};

bool stream_gate_open(const StreamGateInput& input);
AdvertisingSpeed advertising_speed(uint32_t advertising_for_ms);
DeviceName device_name_from_mac(const uint8_t* mac);
```

- [ ] **Step 6: Write `firmware/lib/belt_rules/ble_rules.cpp`**

```cpp
#include "ble_rules.h"

#include <stdio.h>

bool stream_gate_open(const StreamGateInput& input) {
    bool peer_ready = input.connected && input.subscribed && input.trusted;
    return peer_ready && input.mtu >= kMinNotifyMtu;
}

AdvertisingSpeed advertising_speed(uint32_t advertising_for_ms) {
    return advertising_for_ms < kFastAdvertisingWindowMs ? AdvertisingSpeed::Fast : AdvertisingSpeed::Slow;
}

DeviceName device_name_from_mac(const uint8_t* mac) {
    DeviceName name{};
    snprintf(name.text, sizeof(name.text), "Blindside-%02X%02X", mac[4], mac[5]);
    return name;
}
```

- [ ] **Step 7: Write `firmware/lib/belt_rules/info_json.h`**

```cpp
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "bundler.h"
#include "ld2450_commands.h"

constexpr size_t kInfoJsonMaxBytes = 400;
constexpr size_t kInfoJsonBufferSize = kInfoJsonMaxBytes + 1;

struct RadarInfo {
    uint8_t id;
    FirmwareText firmware;
    uint32_t baud;
};

struct ImuInfo {
    uint8_t id;
    uint8_t who_am_i;
};

struct BeltInfo {
    const char* firmware_version;
    uint32_t boot_id;
    const char* reset_reason;
    uint16_t mtu;
    RadarInfo radars[kRadarCount];
    ImuInfo imus[kImuCount];
    int8_t tx_power_dbm;
    LinkParams conn;
    uint32_t uptime_s;
};

size_t format_info_json(const BeltInfo& info, char* out, size_t out_size);
```

- [ ] **Step 8: Write `firmware/lib/belt_rules/info_json.cpp`**

```cpp
#include "info_json.h"

#include <stdio.h>

namespace {

constexpr size_t kObjectTextSize = 128;
constexpr uint32_t kIntervalHundredthsPerUnit = 125;
constexpr uint32_t kSupervisionMsPerUnit = 10;

struct ObjectText {
    char text[kObjectTextSize];
};

ObjectText radar_object(const RadarInfo& radar) {
    ObjectText object{};
    snprintf(object.text, sizeof(object.text), "{\"id\":%u,\"fw\":\"%s\",\"baud\":%lu}",
             static_cast<unsigned>(radar.id), radar.firmware.text, static_cast<unsigned long>(radar.baud));
    return object;
}

ObjectText imu_object(const ImuInfo& imu) {
    ObjectText object{};
    snprintf(object.text, sizeof(object.text), "{\"id\":%u,\"who\":%u,\"gyro_lsb_dps\":65.5,\"accel_lsb_g\":4096}",
             static_cast<unsigned>(imu.id), static_cast<unsigned>(imu.who_am_i));
    return object;
}

// The interval is a multiple of 1.25 ms; one decimal, rounded half up, is what the contract shows ("45.0").
ObjectText conn_object(const LinkParams& conn) {
    uint32_t tenths = (conn.interval_units * kIntervalHundredthsPerUnit + 5) / 10;
    ObjectText object{};
    snprintf(object.text, sizeof(object.text), "{\"interval_ms\":%lu.%lu,\"latency\":%u,\"timeout_ms\":%lu}",
             static_cast<unsigned long>(tenths / 10), static_cast<unsigned long>(tenths % 10),
             static_cast<unsigned>(conn.latency),
             static_cast<unsigned long>(conn.supervision_units * kSupervisionMsPerUnit));
    return object;
}

}  // namespace

size_t format_info_json(const BeltInfo& info, char* out, size_t out_size) {
    ObjectText radar_a = radar_object(info.radars[0]);
    ObjectText radar_b = radar_object(info.radars[1]);
    ObjectText imu_a = imu_object(info.imus[0]);
    ObjectText imu_b = imu_object(info.imus[1]);
    ObjectText conn = conn_object(info.conn);
    int written = snprintf(out, out_size,
                           "{\"proto\":%u,\"fw\":\"%s\",\"boot_id\":\"%08lx\",\"reset\":\"%s\",\"mtu\":%u,"
                           "\"radars\":[%s,%s],\"imus\":[%s,%s],\"tx_power_dbm\":%d,\"conn\":%s,\"uptime_s\":%lu}",
                           static_cast<unsigned>(kProtocolVersion), info.firmware_version,
                           static_cast<unsigned long>(info.boot_id), info.reset_reason,
                           static_cast<unsigned>(info.mtu), radar_a.text, radar_b.text, imu_a.text, imu_b.text,
                           static_cast<int>(info.tx_power_dbm), conn.text, static_cast<unsigned long>(info.uptime_s));
    bool fits = written > 0 && static_cast<size_t>(written) < out_size;
    return fits ? static_cast<size_t>(written) : 0;
}
```

- [ ] **Step 9: Run the suite to verify it passes**

Run (from `firmware/`): `python tools/run_native_tests.py test_belt_rules_link`
Expected: `11 Tests 0 Failures 0 Ignored`.
Run: `python -m platformio test -e esp32dev -f test_belt_rules_link --without-uploading --without-testing`
Expected: `esp32dev:test_belt_rules_link [SKIPPED]`. CI is authoritative; board: `pio test -e esp32dev -f test_belt_rules_link`.

- [ ] **Step 10: Commit**

```bash
git -C C:/personal/blindside add firmware/lib/belt_rules/control_command.h firmware/lib/belt_rules/control_command.cpp firmware/lib/belt_rules/ble_rules.h firmware/lib/belt_rules/ble_rules.cpp firmware/lib/belt_rules/info_json.h firmware/lib/belt_rules/info_json.cpp firmware/test/test_belt_rules_link/test_main.cpp
git -C C:/personal/blindside commit -m "feat: reglas del enlace (control, compuerta con MTU 247, JSON de info completo y nombre)"
```

---

### Task 10: Pairing rules, LED pattern and serial commands (`belt_rules`)

**Files:**
- Create: `firmware/lib/belt_rules/pairing_rules.h`
- Create: `firmware/lib/belt_rules/pairing_rules.cpp`
- Create: `firmware/lib/belt_rules/led_pattern.h`
- Create: `firmware/lib/belt_rules/led_pattern.cpp`
- Create: `firmware/lib/belt_rules/serial_command.h`
- Create: `firmware/lib/belt_rules/serial_command.cpp`
- Test: `firmware/test/test_belt_rules_pairing/test_main.cpp`

**Interfaces:**
- Consumes: nothing.
- Produces (used by Tasks 14, 15, 17):
  - `constexpr uint32_t kPairingWindowMs = 60000; kGestureWindowMs = 60000; kHoldToOpenWindowMs = 3000; kHoldToResetPairingMs = 10000; kPasskeyModulus = 1000000; kUnauthenticatedGraceMs = 5000;`
  - `enum class ButtonGesture : uint8_t { None, OpenPairingWindow, ResetPairing };`
  - `enum class AuthDecision : uint8_t { AcceptTrusted, AcceptNewBond, Reject };`
  - `struct PairingWindow { bool open; uint32_t opened_ms; };`
  - `bool gestures_still_counted(uint32_t now_ms);` (now ≤ 60000)
  - `ButtonGesture gesture_on_release(uint32_t hold_ms, uint32_t released_at_ms);` (None after the first 60 s or below 3 s; 3000-9999 opens the window; ≥ 10000 resets pairing)
  - `PairingWindow window_opened(uint32_t now_ms); PairingWindow window_closed(); PairingWindow initial_window(bool has_trusted_bond, uint32_t now_ms); PairingWindow window_after_tick(const PairingWindow&, uint32_t now_ms);`
  - `bool link_is_secure(bool encrypted, bool bonded, bool authenticated, bool require_mitm);`
  - `AuthDecision decide_authentication(bool link_secure, bool peer_is_trusted, bool pairing_allowed);`
  - `bool should_drop_at_connect(bool pairing_allowed, bool peer_is_trusted_identity);` (outside the window, an unbonded identity goes at once)
  - `bool should_drop_unauthenticated(uint32_t connected_ms, uint32_t now_ms, bool pairing_allowed);` (5 s, or 60 s while pairing is allowed)
  - `uint32_t passkey_from_random(uint32_t random_value);`
  - `constexpr uint32_t kBootLedMs = 2000; kPairingBlinkHalfPeriodMs = 250; kIdentifyBlinkHalfPeriodMs = 200; kIdentifyDurationMs = 1200;`
  - `struct LedInputs { uint32_t now_ms; bool pairing_window_open; bool identify_active; uint32_t identify_started_ms; };`
  - `bool led_on(const LedInputs&); bool identify_finished(uint32_t identify_started_ms, uint32_t now_ms);`
  - `enum class SerialCommand : uint8_t { None, ShowKey, NewKey };`
  - `SerialCommand parse_serial_line(const char* line, size_t length);` (blanks trimmed at both ends, then exact match of `key` or `key new`)

- [ ] **Step 1: Write the failing tests `firmware/test/test_belt_rules_pairing/test_main.cpp`**

```cpp
#include <unity.h>

#include "../test_entry.h"
#include "led_pattern.h"
#include "pairing_rules.h"
#include "serial_command.h"

namespace {

LedInputs plain(uint32_t now_ms) {
    return LedInputs{now_ms, false, false, 0};
}

LedInputs pairing(uint32_t now_ms) {
    return LedInputs{now_ms, true, false, 0};
}

LedInputs identify(uint32_t now_ms, uint32_t started_ms, bool pairing_open) {
    return LedInputs{now_ms, pairing_open, true, started_ms};
}

}  // namespace

void setUp() {}
void tearDown() {}

void test_releasing_boot_after_three_to_ten_seconds_opens_the_window() {
    TEST_ASSERT_TRUE(gesture_on_release(2999, 5000) == ButtonGesture::None);
    TEST_ASSERT_TRUE(gesture_on_release(3000, 5000) == ButtonGesture::OpenPairingWindow);
    TEST_ASSERT_TRUE(gesture_on_release(9999, 15000) == ButtonGesture::OpenPairingWindow);
}

void test_releasing_boot_after_ten_seconds_resets_pairing() {
    TEST_ASSERT_TRUE(gesture_on_release(10000, 15000) == ButtonGesture::ResetPairing);
    TEST_ASSERT_TRUE(gesture_on_release(25000, 40000) == ButtonGesture::ResetPairing);
}

void test_gestures_only_count_in_the_first_minute() {
    TEST_ASSERT_TRUE(gesture_on_release(3000, 60000) == ButtonGesture::OpenPairingWindow);
    TEST_ASSERT_TRUE(gesture_on_release(3000, 60001) == ButtonGesture::None);
    TEST_ASSERT_TRUE(gesture_on_release(12000, 65000) == ButtonGesture::None);
    TEST_ASSERT_TRUE(gestures_still_counted(60000));
    TEST_ASSERT_FALSE(gestures_still_counted(60001));
}

void test_window_lasts_sixty_seconds() {
    PairingWindow window = window_opened(1000);
    TEST_ASSERT_TRUE(window_after_tick(window, 60999).open);
    TEST_ASSERT_FALSE(window_after_tick(window, 61000).open);
    TEST_ASSERT_FALSE(window_after_tick(window_closed(), 5).open);
}

void test_window_opens_at_boot_only_without_a_bond() {
    TEST_ASSERT_TRUE(initial_window(false, 5).open);
    TEST_ASSERT_EQUAL_UINT32(5, initial_window(false, 5).opened_ms);
    TEST_ASSERT_FALSE(initial_window(true, 5).open);
}

void test_authentication_decisions() {
    TEST_ASSERT_TRUE(decide_authentication(false, true, true) == AuthDecision::Reject);
    TEST_ASSERT_TRUE(decide_authentication(true, true, false) == AuthDecision::AcceptTrusted);
    TEST_ASSERT_TRUE(decide_authentication(true, false, true) == AuthDecision::AcceptNewBond);
    TEST_ASSERT_TRUE(decide_authentication(true, false, false) == AuthDecision::Reject);
}

void test_secure_link_needs_mitm_only_when_required() {
    TEST_ASSERT_TRUE(link_is_secure(true, true, true, true));
    TEST_ASSERT_FALSE(link_is_secure(true, true, false, true));
    TEST_ASSERT_TRUE(link_is_secure(true, true, false, false));
    TEST_ASSERT_FALSE(link_is_secure(true, false, true, false));
    TEST_ASSERT_FALSE(link_is_secure(false, true, true, false));
}

void test_unknown_peers_are_dropped_at_once_outside_the_window() {
    TEST_ASSERT_TRUE(should_drop_at_connect(false, false));
    TEST_ASSERT_FALSE(should_drop_at_connect(false, true));
    TEST_ASSERT_FALSE(should_drop_at_connect(true, false));
    TEST_ASSERT_FALSE(should_drop_at_connect(true, true));
}

void test_unauthenticated_peers_get_five_seconds_or_the_pairing_window() {
    TEST_ASSERT_FALSE(should_drop_unauthenticated(1000, 5999, false));
    TEST_ASSERT_TRUE(should_drop_unauthenticated(1000, 6000, false));
    TEST_ASSERT_FALSE(should_drop_unauthenticated(1000, 60999, true));
    TEST_ASSERT_TRUE(should_drop_unauthenticated(1000, 61000, true));
}

void test_passkey_has_six_digits() {
    TEST_ASSERT_EQUAL_UINT32(0, passkey_from_random(0));
    TEST_ASSERT_EQUAL_UINT32(999999, passkey_from_random(999999));
    TEST_ASSERT_EQUAL_UINT32(0, passkey_from_random(1000000));
    TEST_ASSERT_EQUAL_UINT32(967295, passkey_from_random(0xFFFFFFFFu));
}

void test_led_is_on_for_the_first_two_seconds_after_boot() {
    TEST_ASSERT_TRUE(led_on(plain(0)));
    TEST_ASSERT_TRUE(led_on(plain(1999)));
    TEST_ASSERT_FALSE(led_on(plain(2000)));
}

void test_led_stays_off_in_game() {
    for (uint32_t t = 2000; t < 600000; t += 37) {
        TEST_ASSERT_FALSE(led_on(plain(t)));
    }
}

void test_pairing_window_blinks_every_250_ms() {
    TEST_ASSERT_TRUE(led_on(pairing(2000)));
    TEST_ASSERT_FALSE(led_on(pairing(2250)));
    TEST_ASSERT_TRUE(led_on(pairing(2500)));
}

void test_identify_blinks_three_times_then_stops() {
    const uint32_t start = 10000;
    TEST_ASSERT_TRUE(led_on(identify(start, start, false)));
    TEST_ASSERT_FALSE(led_on(identify(start + 200, start, false)));
    TEST_ASSERT_TRUE(led_on(identify(start + 400, start, false)));
    TEST_ASSERT_TRUE(led_on(identify(start + 999, start, false)));
    TEST_ASSERT_FALSE(led_on(identify(start + 1000, start, false)));
    TEST_ASSERT_FALSE(led_on(identify(start + 1200, start, false)));
    TEST_ASSERT_FALSE(identify_finished(start, start + 1199));
    TEST_ASSERT_TRUE(identify_finished(start, start + 1200));
}

void test_identify_pattern_wins_over_the_pairing_blink() {
    TEST_ASSERT_FALSE(led_on(identify(10200, 10000, true)));
}

void test_serial_key_commands_are_exact_after_trimming() {
    TEST_ASSERT_TRUE(parse_serial_line("key", 3) == SerialCommand::ShowKey);
    TEST_ASSERT_TRUE(parse_serial_line(" key \r\n", 7) == SerialCommand::ShowKey);
    TEST_ASSERT_TRUE(parse_serial_line("key new", 7) == SerialCommand::NewKey);
    TEST_ASSERT_TRUE(parse_serial_line("key new\r", 8) == SerialCommand::NewKey);
}

void test_other_serial_lines_are_ignored() {
    TEST_ASSERT_TRUE(parse_serial_line("key  new\r", 9) == SerialCommand::None);
    TEST_ASSERT_TRUE(parse_serial_line("keys", 4) == SerialCommand::None);
    TEST_ASSERT_TRUE(parse_serial_line("KEY", 3) == SerialCommand::None);
    TEST_ASSERT_TRUE(parse_serial_line("", 0) == SerialCommand::None);
    TEST_ASSERT_TRUE(parse_serial_line(nullptr, 0) == SerialCommand::None);
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_releasing_boot_after_three_to_ten_seconds_opens_the_window);
    RUN_TEST(test_releasing_boot_after_ten_seconds_resets_pairing);
    RUN_TEST(test_gestures_only_count_in_the_first_minute);
    RUN_TEST(test_window_lasts_sixty_seconds);
    RUN_TEST(test_window_opens_at_boot_only_without_a_bond);
    RUN_TEST(test_authentication_decisions);
    RUN_TEST(test_secure_link_needs_mitm_only_when_required);
    RUN_TEST(test_unknown_peers_are_dropped_at_once_outside_the_window);
    RUN_TEST(test_unauthenticated_peers_get_five_seconds_or_the_pairing_window);
    RUN_TEST(test_passkey_has_six_digits);
    RUN_TEST(test_led_is_on_for_the_first_two_seconds_after_boot);
    RUN_TEST(test_led_stays_off_in_game);
    RUN_TEST(test_pairing_window_blinks_every_250_ms);
    RUN_TEST(test_identify_blinks_three_times_then_stops);
    RUN_TEST(test_identify_pattern_wins_over_the_pairing_blink);
    RUN_TEST(test_serial_key_commands_are_exact_after_trimming);
    RUN_TEST(test_other_serial_lines_are_ignored);
    return UNITY_END();
}
```

(4294967295 mod 1000000 = 967295. In `test_identify_pattern_wins_over_the_pairing_blink` the pairing blink alone would be on at 10200 ms — 10200 / 250 = 40, even — while IDENTIFY is in its first "off" half-period. `"key  new\r"` has two spaces inside, so it stays `None` after trimming.)

- [ ] **Step 2: Run the suite to verify it fails**

Run (from `firmware/`): `python tools/run_native_tests.py test_belt_rules_pairing`
Expected: the build stops with `fatal error: 'led_pattern.h' file not found` and the runner ends with `build failed` and `0 suites passed, 1 failed`.

- [ ] **Step 3: Write `firmware/lib/belt_rules/pairing_rules.h`**

```cpp
#pragma once

#include <stdint.h>

constexpr uint32_t kPairingWindowMs = 60000;
constexpr uint32_t kGestureWindowMs = 60000;
constexpr uint32_t kHoldToOpenWindowMs = 3000;
constexpr uint32_t kHoldToResetPairingMs = 10000;
constexpr uint32_t kPasskeyModulus = 1000000;
constexpr uint32_t kUnauthenticatedGraceMs = 5000;

enum class ButtonGesture : uint8_t { None, OpenPairingWindow, ResetPairing };
enum class AuthDecision : uint8_t { AcceptTrusted, AcceptNewBond, Reject };

struct PairingWindow {
    bool open;
    uint32_t opened_ms;
};

bool gestures_still_counted(uint32_t now_ms);
ButtonGesture gesture_on_release(uint32_t hold_ms, uint32_t released_at_ms);
PairingWindow window_opened(uint32_t now_ms);
PairingWindow window_closed();
PairingWindow initial_window(bool has_trusted_bond, uint32_t now_ms);
PairingWindow window_after_tick(const PairingWindow& window, uint32_t now_ms);
bool link_is_secure(bool encrypted, bool bonded, bool authenticated, bool require_mitm);
AuthDecision decide_authentication(bool link_secure, bool peer_is_trusted, bool pairing_allowed);
bool should_drop_at_connect(bool pairing_allowed, bool peer_is_trusted_identity);
bool should_drop_unauthenticated(uint32_t connected_ms, uint32_t now_ms, bool pairing_allowed);
uint32_t passkey_from_random(uint32_t random_value);
```

- [ ] **Step 4: Write `firmware/lib/belt_rules/pairing_rules.cpp`**

```cpp
#include "pairing_rules.h"

namespace {

uint32_t unauthenticated_grace_ms(bool pairing_allowed) {
    return pairing_allowed ? kPairingWindowMs : kUnauthenticatedGraceMs;
}

ButtonGesture gesture_for_hold(uint32_t hold_ms) {
    if (hold_ms >= kHoldToResetPairingMs) {
        return ButtonGesture::ResetPairing;
    }
    return hold_ms >= kHoldToOpenWindowMs ? ButtonGesture::OpenPairingWindow : ButtonGesture::None;
}

}  // namespace

bool gestures_still_counted(uint32_t now_ms) {
    return now_ms <= kGestureWindowMs;
}

ButtonGesture gesture_on_release(uint32_t hold_ms, uint32_t released_at_ms) {
    return gestures_still_counted(released_at_ms) ? gesture_for_hold(hold_ms) : ButtonGesture::None;
}

PairingWindow window_opened(uint32_t now_ms) {
    return PairingWindow{true, now_ms};
}

PairingWindow window_closed() {
    return PairingWindow{false, 0};
}

PairingWindow initial_window(bool has_trusted_bond, uint32_t now_ms) {
    return has_trusted_bond ? window_closed() : window_opened(now_ms);
}

PairingWindow window_after_tick(const PairingWindow& window, uint32_t now_ms) {
    bool expired = window.open && now_ms - window.opened_ms >= kPairingWindowMs;
    return expired ? window_closed() : window;
}

bool link_is_secure(bool encrypted, bool bonded, bool authenticated, bool require_mitm) {
    return encrypted && bonded && (authenticated || !require_mitm);
}

AuthDecision decide_authentication(bool link_secure, bool peer_is_trusted, bool pairing_allowed) {
    if (!link_secure) {
        return AuthDecision::Reject;
    }
    if (peer_is_trusted) {
        return AuthDecision::AcceptTrusted;
    }
    return pairing_allowed ? AuthDecision::AcceptNewBond : AuthDecision::Reject;
}

bool should_drop_at_connect(bool pairing_allowed, bool peer_is_trusted_identity) {
    return !pairing_allowed && !peer_is_trusted_identity;
}

bool should_drop_unauthenticated(uint32_t connected_ms, uint32_t now_ms, bool pairing_allowed) {
    return now_ms - connected_ms >= unauthenticated_grace_ms(pairing_allowed);
}

uint32_t passkey_from_random(uint32_t random_value) {
    return random_value % kPasskeyModulus;
}
```

- [ ] **Step 5: Write `firmware/lib/belt_rules/led_pattern.h`**

```cpp
#pragma once

#include <stdint.h>

constexpr uint32_t kBootLedMs = 2000;
constexpr uint32_t kPairingBlinkHalfPeriodMs = 250;
constexpr uint32_t kIdentifyBlinkHalfPeriodMs = 200;
constexpr uint32_t kIdentifyBlinkCount = 3;
constexpr uint32_t kIdentifyDurationMs = 2 * kIdentifyBlinkHalfPeriodMs * kIdentifyBlinkCount;

struct LedInputs {
    uint32_t now_ms;
    bool pairing_window_open;
    bool identify_active;
    uint32_t identify_started_ms;
};

bool led_on(const LedInputs& inputs);
bool identify_finished(uint32_t identify_started_ms, uint32_t now_ms);
```

- [ ] **Step 6: Write `firmware/lib/belt_rules/led_pattern.cpp`**

```cpp
#include "led_pattern.h"

namespace {

bool in_lit_half(uint32_t elapsed_ms, uint32_t half_period_ms) {
    return (elapsed_ms / half_period_ms) % 2 == 0;
}

bool identify_showing(const LedInputs& inputs) {
    return inputs.identify_active && !identify_finished(inputs.identify_started_ms, inputs.now_ms);
}

}  // namespace

bool identify_finished(uint32_t identify_started_ms, uint32_t now_ms) {
    return now_ms - identify_started_ms >= kIdentifyDurationMs;
}

bool led_on(const LedInputs& inputs) {
    if (identify_showing(inputs)) {
        return in_lit_half(inputs.now_ms - inputs.identify_started_ms, kIdentifyBlinkHalfPeriodMs);
    }
    if (inputs.pairing_window_open) {
        return in_lit_half(inputs.now_ms, kPairingBlinkHalfPeriodMs);
    }
    return inputs.now_ms < kBootLedMs;
}
```

- [ ] **Step 7: Write `firmware/lib/belt_rules/serial_command.h`**

```cpp
#pragma once

#include <stddef.h>
#include <stdint.h>

enum class SerialCommand : uint8_t { None, ShowKey, NewKey };

SerialCommand parse_serial_line(const char* line, size_t length);
```

- [ ] **Step 8: Write `firmware/lib/belt_rules/serial_command.cpp`**

```cpp
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
```

- [ ] **Step 9: Run the suite to verify it passes**

Run (from `firmware/`): `python tools/run_native_tests.py test_belt_rules_pairing`
Expected: `17 Tests 0 Failures 0 Ignored`.
Run: `python -m platformio test -e esp32dev -f test_belt_rules_pairing --without-uploading --without-testing`
Expected: `esp32dev:test_belt_rules_pairing [SKIPPED]`. CI is authoritative; board: `pio test -e esp32dev -f test_belt_rules_pairing`.

- [ ] **Step 10: Commit**

```bash
git -C C:/personal/blindside add firmware/lib/belt_rules/pairing_rules.h firmware/lib/belt_rules/pairing_rules.cpp firmware/lib/belt_rules/led_pattern.h firmware/lib/belt_rules/led_pattern.cpp firmware/lib/belt_rules/serial_command.h firmware/lib/belt_rules/serial_command.cpp firmware/test/test_belt_rules_pairing/test_main.cpp
git -C C:/personal/blindside commit -m "feat: reglas de emparejamiento (gestos de BOOT al soltar), patrón del LED y comandos serie de la clave"
```

---

### Task 11: Cut schedule — cut action, STATUS cadence, outbox and retry window (`cut_schedule`)

**Files:**
- Create: `firmware/lib/bundler/cut_schedule.h`
- Create: `firmware/lib/bundler/cut_schedule.cpp`
- Test: `firmware/test/test_cut_schedule/test_main.cpp`

**Interfaces:**
- Consumes: nothing.
- Produces (used by Tasks 16, 17):
  - `constexpr uint32_t kCutPeriodMs = 100; kStatusPeriodMs = 1000; kNotifyRetryMs = 10;`
  - `enum class CutAction : uint8_t { DiscardAll, KeepBacklog, Bundle };`
  - `struct Outbox { size_t head; size_t count; uint32_t sent; uint32_t failures; };`
  - `bool deadline_reached(uint32_t now_ms, uint32_t deadline_ms);` (wrap-safe)
  - `bool status_due(uint32_t now_ms, uint32_t next_status_ms); uint32_t next_status_after(uint32_t sent_at_ms);`
  - `bool link_report_due(uint32_t link_generation, uint32_t reported_generation);`
  - `CutAction cut_action(bool gate_open, const Outbox&);` (gate closed → discard backlog and outbox; packets still waiting → skip this cut and keep the backlog; otherwise bundle)
  - `Outbox outbox_refilled(const Outbox&, size_t packet_count); Outbox outbox_cleared(const Outbox&); Outbox outbox_after_send(const Outbox&, bool sent); bool outbox_empty(const Outbox&);` (tallies survive refills and clears)
  - `bool retry_allowed(uint32_t cut_ms, uint32_t now_ms);` (a failed notify is retried every 10 ms until 10 ms before the next cut)
- The 100 ms cadence itself is the bundler task's `vTaskDelayUntil` (Task 16), so there is no "next cut" function.

- [ ] **Step 1: Write the failing tests `firmware/test/test_cut_schedule/test_main.cpp`**

```cpp
#include <unity.h>

#include "../test_entry.h"
#include "cut_schedule.h"

void setUp() {}
void tearDown() {}

void test_a_closed_gate_discards_everything() {
    TEST_ASSERT_TRUE(cut_action(false, Outbox{}) == CutAction::DiscardAll);
    TEST_ASSERT_TRUE(cut_action(false, outbox_refilled(Outbox{}, 3)) == CutAction::DiscardAll);
}

void test_pending_packets_skip_the_cut_and_keep_the_backlog() {
    TEST_ASSERT_TRUE(cut_action(true, outbox_refilled(Outbox{}, 1)) == CutAction::KeepBacklog);
}

void test_an_empty_outbox_bundles_the_cut() {
    TEST_ASSERT_TRUE(cut_action(true, Outbox{}) == CutAction::Bundle);
}

void test_the_outbox_advances_only_on_successful_sends() {
    Outbox outbox = outbox_refilled(Outbox{}, 3);
    outbox = outbox_after_send(outbox, false);
    TEST_ASSERT_EQUAL_UINT(0, outbox.head);
    TEST_ASSERT_EQUAL_UINT(3, outbox.count);
    TEST_ASSERT_EQUAL_UINT32(1, outbox.failures);
    outbox = outbox_after_send(outbox, true);
    TEST_ASSERT_EQUAL_UINT(1, outbox.head);
    TEST_ASSERT_EQUAL_UINT(2, outbox.count);
    TEST_ASSERT_EQUAL_UINT32(1, outbox.sent);
    TEST_ASSERT_FALSE(outbox_empty(outbox));
}

void test_clearing_the_outbox_keeps_the_tallies() {
    Outbox outbox = outbox_after_send(outbox_refilled(Outbox{}, 2), true);
    outbox = outbox_cleared(outbox_after_send(outbox, false));
    TEST_ASSERT_TRUE(outbox_empty(outbox));
    TEST_ASSERT_EQUAL_UINT(0, outbox.head);
    TEST_ASSERT_EQUAL_UINT32(1, outbox.sent);
    TEST_ASSERT_EQUAL_UINT32(1, outbox.failures);
}

void test_status_is_due_once_per_second() {
    TEST_ASSERT_FALSE(status_due(999, 1000));
    TEST_ASSERT_TRUE(status_due(1000, 1000));
    TEST_ASSERT_EQUAL_UINT32(2100, next_status_after(1100));
}

void test_deadlines_survive_a_millis_wrap() {
    TEST_ASSERT_TRUE(deadline_reached(0x00000010u, 0xFFFFFFF0u));
    TEST_ASSERT_FALSE(deadline_reached(0xFFFFFFE0u, 0xFFFFFFF0u));
    TEST_ASSERT_EQUAL_UINT32(0x000003D8u, next_status_after(0xFFFFFFF0u));
}

void test_retries_stop_before_the_next_cut() {
    TEST_ASSERT_TRUE(retry_allowed(1000, 1000));
    TEST_ASSERT_TRUE(retry_allowed(1000, 1089));
    TEST_ASSERT_FALSE(retry_allowed(1000, 1090));
    TEST_ASSERT_TRUE(retry_allowed(0xFFFFFFF0u, 0x00000040u));
}

void test_link_report_is_due_until_its_generation_is_sent() {
    TEST_ASSERT_FALSE(link_report_due(0, 0));
    TEST_ASSERT_TRUE(link_report_due(1, 0));
    TEST_ASSERT_FALSE(link_report_due(1, 1));
    TEST_ASSERT_TRUE(link_report_due(0, 0xFFFFFFFFu));
}

int run_all_tests() {
    UNITY_BEGIN();
    RUN_TEST(test_a_closed_gate_discards_everything);
    RUN_TEST(test_pending_packets_skip_the_cut_and_keep_the_backlog);
    RUN_TEST(test_an_empty_outbox_bundles_the_cut);
    RUN_TEST(test_the_outbox_advances_only_on_successful_sends);
    RUN_TEST(test_clearing_the_outbox_keeps_the_tallies);
    RUN_TEST(test_status_is_due_once_per_second);
    RUN_TEST(test_deadlines_survive_a_millis_wrap);
    RUN_TEST(test_retries_stop_before_the_next_cut);
    RUN_TEST(test_link_report_is_due_until_its_generation_is_sent);
    return UNITY_END();
}
```

(`0xFFFFFFF0 + 1000` wraps to `0x3D8`; from `0xFFFFFFF0` to `0x40` is 80 ms.)

- [ ] **Step 2: Run the suite to verify it fails**

Run (from `firmware/`): `python tools/run_native_tests.py test_cut_schedule`
Expected: the build stops with `fatal error: 'cut_schedule.h' file not found` and the runner ends with `build failed` and `0 suites passed, 1 failed`.

- [ ] **Step 3: Write `firmware/lib/bundler/cut_schedule.h`**

```cpp
#pragma once

#include <stddef.h>
#include <stdint.h>

constexpr uint32_t kCutPeriodMs = 100;
constexpr uint32_t kStatusPeriodMs = 1000;
constexpr uint32_t kNotifyRetryMs = 10;

enum class CutAction : uint8_t { DiscardAll, KeepBacklog, Bundle };

struct Outbox {
    size_t head;
    size_t count;
    uint32_t sent;
    uint32_t failures;
};

bool deadline_reached(uint32_t now_ms, uint32_t deadline_ms);
bool status_due(uint32_t now_ms, uint32_t next_status_ms);
uint32_t next_status_after(uint32_t sent_at_ms);
bool link_report_due(uint32_t link_generation, uint32_t reported_generation);
CutAction cut_action(bool gate_open, const Outbox& outbox);
Outbox outbox_refilled(const Outbox& outbox, size_t packet_count);
Outbox outbox_cleared(const Outbox& outbox);
Outbox outbox_after_send(const Outbox& outbox, bool sent);
bool outbox_empty(const Outbox& outbox);
bool retry_allowed(uint32_t cut_ms, uint32_t now_ms);
```

- [ ] **Step 4: Write `firmware/lib/bundler/cut_schedule.cpp`**

```cpp
#include "cut_schedule.h"

namespace {

Outbox after_success(const Outbox& outbox) {
    Outbox next = outbox;
    next.head++;
    next.count--;
    next.sent++;
    return next;
}

Outbox after_failure(const Outbox& outbox) {
    Outbox next = outbox;
    next.failures++;
    return next;
}

}  // namespace

bool deadline_reached(uint32_t now_ms, uint32_t deadline_ms) {
    return static_cast<int32_t>(now_ms - deadline_ms) >= 0;
}

bool status_due(uint32_t now_ms, uint32_t next_status_ms) {
    return deadline_reached(now_ms, next_status_ms);
}

uint32_t next_status_after(uint32_t sent_at_ms) {
    return sent_at_ms + kStatusPeriodMs;
}

bool link_report_due(uint32_t link_generation, uint32_t reported_generation) {
    return link_generation != reported_generation;
}

CutAction cut_action(bool gate_open, const Outbox& outbox) {
    if (!gate_open) {
        return CutAction::DiscardAll;
    }
    return outbox_empty(outbox) ? CutAction::Bundle : CutAction::KeepBacklog;
}

Outbox outbox_refilled(const Outbox& outbox, size_t packet_count) {
    Outbox next = outbox;
    next.head = 0;
    next.count = packet_count;
    return next;
}

Outbox outbox_cleared(const Outbox& outbox) {
    return outbox_refilled(outbox, 0);
}

Outbox outbox_after_send(const Outbox& outbox, bool sent) {
    return sent ? after_success(outbox) : after_failure(outbox);
}

bool outbox_empty(const Outbox& outbox) {
    return outbox.count == 0;
}

bool retry_allowed(uint32_t cut_ms, uint32_t now_ms) {
    return now_ms - cut_ms + kNotifyRetryMs < kCutPeriodMs;
}
```

- [ ] **Step 5: Run the suite to verify it passes**

Run (from `firmware/`): `python tools/run_native_tests.py test_cut_schedule`
Expected: `9 Tests 0 Failures 0 Ignored`.
Run: `python -m platformio test -e esp32dev -f test_cut_schedule --without-uploading --without-testing`
Expected: `esp32dev:test_cut_schedule [SKIPPED]`. CI is authoritative; board: `pio test -e esp32dev -f test_cut_schedule`.

- [ ] **Step 6: Commit**

```bash
git -C C:/personal/blindside add firmware/lib/bundler/cut_schedule.h firmware/lib/bundler/cut_schedule.cpp firmware/test/test_cut_schedule/test_main.cpp
git -C C:/personal/blindside commit -m "feat: calendario puro de cortes, STATUS, reintentos y bandeja de salida"
```

---

### Task 12: Radar UART glue and `radar_rx` tasks (`radar_port`)

Hardware glue: no native tests are possible (all decisions are in Tasks 2-4 and 7). The deliverable is a clean `esp32dev` build; behaviour is verified on the bench by checklist items H4-H6, H16 and H7b (written in Task 17).

**Files:**
- Create: `firmware/src/radar_port.h`
- Create: `firmware/src/radar_port.cpp`

**Interfaces:**
- Consumes: Task 2 `FrameParser`, `frame_parser_start`, `frame_parser_feed`, `ParserStep`, `FrameEvent`, `ParsedFrame`, `bad_frames_after_one_more`; Task 3 `enable_config_command`, `read_firmware_command`, `multi_target_command`, `bluetooth_off_command`, `restart_command`, `find_ack`, `Ack`, `CommandBytes`, `FirmwareText`, `firmware_text_from_ack`, `kBaudProbeOrder`, `kBaudProbeCount`, `baud_index_for`, `kCmd*`; Task 4 `RadarWatchdog`, `WatchdogStep`, `WatchdogAction`, `watchdog_after_boot_config`, `watchdog_saw_frame`, `watchdog_restarted`, `watchdog_check`, `watchdog_radar_alive`, `FrameGaps`, `frame_gaps_start`, `frame_gaps_with`, `frame_gaps_new_window`; Task 7 `RadarFrame`, `StatusEntry`, `kRadarCount`, `kRadarTargetsSize`; Task 1 `config::kRadarRxBufferBytes`, `config::kRadarAckTimeoutMs`, `config::kRadarRestartGapMs`, `config::kRadarReadTimeoutMs`, `config::kDiagnosticsPeriodMs`, `config::kTaskStackBytes`, `config::kRadarTaskPriority`, `config::kSensorCore`.
- Produces (used by Tasks 16, 17):
  - `struct RadarPort { uint8_t radar_id; HardwareSerial* serial; uart_port_t uart; int8_t rx_pin; int8_t tx_pin; uint32_t baud; FirmwareText firmware; FrameParser parser; RadarWatchdog watchdog; uint16_t bad_frames; uint32_t frames_ok; bool restart_pending; uint32_t restart_due_ms; FrameGaps gaps; FrameGaps finished_gaps; uint32_t gaps_started_ms; };` (owned by its task once started)
  - `struct RadarSnapshot { bool configured; FirmwareText firmware; uint32_t baud; RadarWatchdog watchdog; StatusEntry status; uint32_t frames_ok; FrameGaps last_window; };`
  - `RadarPort radar_port_create(uint8_t radar_id, HardwareSerial* serial, uart_port_t uart, int8_t rx_pin, int8_t tx_pin);`
  - `void radar_port_start_task(const RadarPort& port, QueueHandle_t frames);` (copies the port into the task, which opens the UART with a 2 KB RX buffer, probes the baud rates, runs the boot sequence, then blocks on `uart_read_bytes` and pushes every good frame to `frames`)
  - `void radar_port_request_restart(uint8_t radar_id);` (`control 01 <id>`, any task; the radar task sends enable, then restart 100 ms later)
  - `RadarSnapshot radar_port_snapshot(uint8_t radar_id);` (any task)
  - `bool radar_snapshot_alive(const RadarSnapshot&, uint32_t now_ms);`
  - `uint32_t radar_port_queue_overflows();`

- [ ] **Step 1: Write `firmware/src/radar_port.h`**

```cpp
#pragma once

#include <Arduino.h>
#include <driver/uart.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>

#include "bundler.h"
#include "frame_gaps.h"
#include "ld2450_commands.h"
#include "ld2450_frame.h"
#include "radar_watchdog.h"

struct RadarPort {
    uint8_t radar_id;
    HardwareSerial* serial;
    uart_port_t uart;
    int8_t rx_pin;
    int8_t tx_pin;
    uint32_t baud;
    FirmwareText firmware;
    FrameParser parser;
    RadarWatchdog watchdog;
    uint16_t bad_frames;
    uint32_t frames_ok;
    bool restart_pending;
    uint32_t restart_due_ms;
    FrameGaps gaps;
    FrameGaps finished_gaps;
    uint32_t gaps_started_ms;
};

struct RadarSnapshot {
    bool configured;
    FirmwareText firmware;
    uint32_t baud;
    RadarWatchdog watchdog;
    StatusEntry status;
    uint32_t frames_ok;
    FrameGaps last_window;
};

RadarPort radar_port_create(uint8_t radar_id, HardwareSerial* serial, uart_port_t uart, int8_t rx_pin, int8_t tx_pin);
void radar_port_start_task(const RadarPort& port, QueueHandle_t frames);
void radar_port_request_restart(uint8_t radar_id);
RadarSnapshot radar_port_snapshot(uint8_t radar_id);
bool radar_snapshot_alive(const RadarSnapshot& snapshot, uint32_t now_ms);
uint32_t radar_port_queue_overflows();
```

- [ ] **Step 2: Write `firmware/src/radar_port.cpp`**

```cpp
#include "radar_port.h"

#include <atomic>
#include <string.h>

#include "blindside_config.h"

namespace {

constexpr size_t kAckWindowSize = 96;
constexpr size_t kAckWindowKeep = 32;
constexpr size_t kReadChunkSize = 64;
const char* const kTaskNames[kRadarCount] = {"radar_rx_a", "radar_rx_b"};

struct RadarTask {
    RadarPort port;
    QueueHandle_t frames;
};

RadarTask g_tasks[kRadarCount];
portMUX_TYPE g_snapshot_lock = portMUX_INITIALIZER_UNLOCKED;
RadarSnapshot g_snapshots[kRadarCount];
std::atomic<bool> g_restart_requested[kRadarCount];
std::atomic<uint32_t> g_queue_overflows{0};

void send_command(HardwareSerial& serial, const CommandBytes& command) {
    serial.write(command.bytes, command.length);
}

void discard_input(HardwareSerial& serial) {
    while (serial.available() > 0) {
        serial.read();
    }
}

size_t keep_window_tail(uint8_t* window, size_t length) {
    memmove(window, window + length - kAckWindowKeep, kAckWindowKeep);
    return kAckWindowKeep;
}

size_t append_available(HardwareSerial& serial, uint8_t* window, size_t length) {
    while (serial.available() > 0) {
        if (length == kAckWindowSize) {
            length = keep_window_tail(window, length);
        }
        window[length++] = static_cast<uint8_t>(serial.read());
    }
    return length;
}

Ack await_ack(HardwareSerial& serial, uint16_t command_word) {
    uint8_t window[kAckWindowSize];
    size_t length = 0;
    uint32_t started_ms = millis();
    while (millis() - started_ms < config::kRadarAckTimeoutMs) {
        length = append_available(serial, window, length);
        Ack ack = find_ack(window, length, command_word);
        if (ack.found) {
            return ack;
        }
        delay(1);
    }
    return Ack{};
}

Ack exchange(RadarPort& port, const CommandBytes& command, uint16_t command_word) {
    send_command(*port.serial, command);
    return await_ack(*port.serial, command_word);
}

bool answers_at(RadarPort& port, uint32_t baud) {
    port.serial->updateBaudRate(baud);
    discard_input(*port.serial);
    return exchange(port, enable_config_command(), kCmdEnableConfig).found;
}

uint32_t detect_baud(RadarPort& port) {
    for (size_t i = 0; i < kBaudProbeCount; ++i) {
        if (answers_at(port, kBaudProbeOrder[i])) {
            return kBaudProbeOrder[i];
        }
    }
    port.serial->updateBaudRate(kBaudProbeOrder[0]);
    return 0;
}

void log_step(const RadarPort& port, const char* step, const Ack& ack) {
    Serial.printf("radar %u: %s %s\n", static_cast<unsigned>(port.radar_id), step, ack.success ? "ok" : "FAILED");
}

void run_boot_sequence(RadarPort& port) {
    Ack version = exchange(port, read_firmware_command(), kCmdReadFirmware);
    port.firmware = firmware_text_from_ack(version);
    log_step(port, "read-firmware", version);
    log_step(port, "multi-target", exchange(port, multi_target_command(), kCmdMultiTarget));
    log_step(port, "bluetooth-off", exchange(port, bluetooth_off_command(), kCmdBluetooth));
    log_step(port, "restart", exchange(port, restart_command(), kCmdRestart));
}

void open_uart(RadarPort& port) {
    port.serial->setRxBufferSize(config::kRadarRxBufferBytes);
    port.serial->begin(kBaudProbeOrder[0], SERIAL_8N1, port.rx_pin, port.tx_pin);
}

void configure_radar(RadarPort& port) {
    open_uart(port);
    port.baud = detect_baud(port);
    if (port.baud != 0) {
        run_boot_sequence(port);
    }
    Serial.printf("radar %u: baud=%lu fw=%s\n", static_cast<unsigned>(port.radar_id),
                  static_cast<unsigned long>(port.baud), port.firmware.text);
    uint32_t now_ms = millis();
    port.parser = frame_parser_start();
    port.watchdog = watchdog_after_boot_config(now_ms);
    port.gaps = frame_gaps_start();
    port.gaps_started_ms = now_ms;
}

RadarSnapshot snapshot_of(const RadarPort& port) {
    RadarSnapshot snapshot{};
    snapshot.configured = true;
    snapshot.firmware = port.firmware;
    snapshot.baud = port.baud;
    snapshot.watchdog = port.watchdog;
    snapshot.status = StatusEntry{port.radar_id, port.bad_frames, port.watchdog.restarts, baud_index_for(port.baud)};
    snapshot.frames_ok = port.frames_ok;
    snapshot.last_window = port.finished_gaps;
    return snapshot;
}

void publish_snapshot(const RadarPort& port) {
    RadarSnapshot snapshot = snapshot_of(port);
    taskENTER_CRITICAL(&g_snapshot_lock);
    g_snapshots[port.radar_id] = snapshot;
    taskEXIT_CRITICAL(&g_snapshot_lock);
}

RadarFrame radar_frame_from(uint8_t radar_id, const ParsedFrame& parsed) {
    RadarFrame frame{};
    frame.radar_id = radar_id;
    frame.t_ms = parsed.t_ms;
    memcpy(frame.targets, parsed.targets, kRadarTargetsSize);
    return frame;
}

void push_frame(QueueHandle_t frames, const RadarFrame& frame) {
    if (xQueueSend(frames, &frame, 0) != pdTRUE) {
        g_queue_overflows++;
    }
}

void record_step(RadarPort& port, const ParserStep& step, QueueHandle_t frames, uint32_t now_ms) {
    if (step.event == FrameEvent::FrameBad) {
        port.bad_frames = bad_frames_after_one_more(port.bad_frames);
        return;
    }
    if (step.event != FrameEvent::FrameOk) {
        return;
    }
    port.frames_ok++;
    port.watchdog = watchdog_saw_frame(port.watchdog, now_ms);
    port.gaps = frame_gaps_with(port.gaps, step.frame.t_ms);
    push_frame(frames, radar_frame_from(port.radar_id, step.frame));
}

void feed_chunk(RadarPort& port, const uint8_t* chunk, size_t length, QueueHandle_t frames, uint32_t now_ms) {
    for (size_t i = 0; i < length; ++i) {
        ParserStep step = frame_parser_feed(port.parser, chunk[i], now_ms);
        port.parser = step.parser;
        record_step(port, step, frames, now_ms);
    }
}

size_t read_chunk(const RadarPort& port, uint8_t* chunk) {
    int first = uart_read_bytes(port.uart, chunk, 1, pdMS_TO_TICKS(config::kRadarReadTimeoutMs));
    if (first <= 0) {
        return 0;
    }
    int rest = uart_read_bytes(port.uart, chunk + 1, kReadChunkSize - 1, 0);
    return 1 + (rest > 0 ? static_cast<size_t>(rest) : 0);
}

void begin_restart(RadarPort& port, uint32_t now_ms) {
    send_command(*port.serial, enable_config_command());
    port.restart_pending = true;
    port.restart_due_ms = now_ms + config::kRadarRestartGapMs;
}

bool restart_due(const RadarPort& port, uint32_t now_ms) {
    return port.restart_pending && static_cast<int32_t>(now_ms - port.restart_due_ms) >= 0;
}

void finish_restart_if_due(RadarPort& port, uint32_t now_ms) {
    if (!restart_due(port, now_ms)) {
        return;
    }
    send_command(*port.serial, restart_command());
    port.restart_pending = false;
    port.parser = frame_parser_start();
    Serial.printf("radar %u: restart sent (total %u)\n", static_cast<unsigned>(port.radar_id),
                  static_cast<unsigned>(port.watchdog.restarts));
}

void take_restart_request(RadarPort& port, uint32_t now_ms) {
    if (!g_restart_requested[port.radar_id].exchange(false)) {
        return;
    }
    port.watchdog = watchdog_restarted(port.watchdog, now_ms);
    begin_restart(port, now_ms);
}

void supervise(RadarPort& port, uint32_t now_ms) {
    take_restart_request(port, now_ms);
    finish_restart_if_due(port, now_ms);
    WatchdogStep step = watchdog_check(port.watchdog, now_ms);
    port.watchdog = step.watchdog;
    if (step.action == WatchdogAction::Restart) {
        begin_restart(port, now_ms);
    }
}

void roll_gap_window(RadarPort& port, uint32_t now_ms) {
    if (now_ms - port.gaps_started_ms < config::kDiagnosticsPeriodMs) {
        return;
    }
    port.finished_gaps = port.gaps;
    port.gaps = frame_gaps_new_window(port.gaps);
    port.gaps_started_ms = now_ms;
}

void radar_rx_task(void* argument) {
    RadarTask& task = *static_cast<RadarTask*>(argument);
    RadarPort& port = task.port;
    configure_radar(port);
    uint8_t chunk[kReadChunkSize];
    for (;;) {
        size_t length = read_chunk(port, chunk);
        // The stamp is taken as the bytes arrive, never in a loop that also touches I2C (spec §4.4).
        uint32_t now_ms = millis();
        feed_chunk(port, chunk, length, task.frames, now_ms);
        supervise(port, now_ms);
        roll_gap_window(port, now_ms);
        publish_snapshot(port);
    }
}

}  // namespace

RadarPort radar_port_create(uint8_t radar_id, HardwareSerial* serial, uart_port_t uart, int8_t rx_pin, int8_t tx_pin) {
    RadarPort port{};
    port.radar_id = radar_id;
    port.serial = serial;
    port.uart = uart;
    port.rx_pin = rx_pin;
    port.tx_pin = tx_pin;
    port.parser = frame_parser_start();
    return port;
}

void radar_port_start_task(const RadarPort& port, QueueHandle_t frames) {
    RadarTask& task = g_tasks[port.radar_id];
    task.port = port;
    task.frames = frames;
    xTaskCreatePinnedToCore(radar_rx_task, kTaskNames[port.radar_id], config::kTaskStackBytes, &task,
                            config::kRadarTaskPriority, nullptr, config::kSensorCore);
}

void radar_port_request_restart(uint8_t radar_id) {
    g_restart_requested[radar_id] = true;
}

RadarSnapshot radar_port_snapshot(uint8_t radar_id) {
    taskENTER_CRITICAL(&g_snapshot_lock);
    RadarSnapshot snapshot = g_snapshots[radar_id];
    taskEXIT_CRITICAL(&g_snapshot_lock);
    return snapshot;
}

bool radar_snapshot_alive(const RadarSnapshot& snapshot, uint32_t now_ms) {
    return snapshot.configured && watchdog_radar_alive(snapshot.watchdog, now_ms);
}

uint32_t radar_port_queue_overflows() {
    return g_queue_overflows.load();
}
```

Why the task owns every UART access: `HardwareSerial::readBytes` holds the port's mutex while it blocks, and a restart written from another task would wait behind it. Reading with the ESP-IDF `uart_read_bytes` (the driver Arduino 2.0.17 installs in `begin()`) and writing only from this task keeps one owner. `uart_read_bytes` with a 1-byte request returns as soon as the driver hands over the chunk; the rest of the chunk is read without waiting, and one `millis()` stamps it.

- [ ] **Step 3: Compile the firmware**

Run (from `firmware/`): `python -m platformio run -e esp32dev`
Expected: `SUCCESS`. (`src/*.cpp` is compiled even though the stub `main.cpp` does not call it yet.) CI job `firmware` runs the same build.

- [ ] **Step 4: Commit**

```bash
git -C C:/personal/blindside add firmware/src/radar_port.h firmware/src/radar_port.cpp
git -C C:/personal/blindside commit -m "feat: tareas radar_rx por UART con detección de baudios, arranque, watchdog y sello al llegar los bytes"
```

---

### Task 13: MPU6050 I2C driver and `imu_task` (`imu_mpu6050`, `imu_task`)

Hardware glue: all decisions are in Tasks 5 and 6. Verified by the `esp32dev` build and checklist items H7, H7b and H8.

**Files:**
- Create: `firmware/src/imu_mpu6050.h`
- Create: `firmware/src/imu_mpu6050.cpp`
- Create: `firmware/src/imu_task.h`
- Create: `firmware/src/imu_task.cpp`

**Interfaces:**
- Consumes: Task 5 `ImuReading`, `ImuRead`, `ImuChannel`, `ImuStep`, `imu_channel_start`, `imu_ticks_due`, `imu_channel_step`, `kImuTickPeriodMs`, `who_am_i_is_usable`, `mpu_config_plan`, `MpuConfigPlan`, `reading_from_burst`, `kMpuRegWhoAmI`, `kMpuRegAccelXoutH`, `kMpuDefaultAddress`, `kMpuAlternateAddress`, `kMpuBurstSize`; Task 6 `ImuHealth`, `imu_health_after_probe`, `imu_health_after_read`, `imu_recovery_due`, `imu_should_read`; Task 7 `kImuCount`; Task 1 `config::kI2cFrequencyHz`, `config::kI2cTimeoutMs`, `config::kImuGridLagMs`, `config::kTaskStackBytes`, `config::kImuTaskPriority`, `config::kSensorCore`.
- Produces (used by Tasks 16, 17):
  - `struct ImuDevice { uint8_t imu_id; TwoWire* wire; int sda_pin; int scl_pin; uint8_t address; uint8_t who_am_i; };`
  - `ImuDevice imu_create(uint8_t imu_id, TwoWire* wire, int sda_pin, int scl_pin);`
  - `bool imu_begin(ImuDevice&);` (starts the bus at 100 kHz with a 4 ms timeout, probes 0x68 then 0x69, writes the register plan)
  - `bool imu_recover(ImuDevice&);` (`end()`, then `imu_begin`)
  - `ImuRead imu_read(const ImuDevice&);` (one 14-byte burst)
  - `struct ImuSnapshot { bool ok; uint8_t who_am_i; uint32_t reads_ok; uint32_t read_failures; uint32_t repeats; uint32_t samples; uint32_t queue_overflows; };`
  - `void imu_task_start(const ImuDevice& device, QueueHandle_t samples);` (one task per IMU: 5 ms `vTaskDelayUntil`, reads only while healthy, feeds the grid with repeats, recovers once per second while down, pushes `ImuSample`s to `samples`)
  - `ImuSnapshot imu_task_snapshot(uint8_t imu_id);` (any task)

- [ ] **Step 1: Write `firmware/src/imu_mpu6050.h`**

```cpp
#pragma once

#include <Arduino.h>
#include <Wire.h>

#include "imu_accumulator.h"

struct ImuDevice {
    uint8_t imu_id;
    TwoWire* wire;
    int sda_pin;
    int scl_pin;
    uint8_t address;
    uint8_t who_am_i;
};

ImuDevice imu_create(uint8_t imu_id, TwoWire* wire, int sda_pin, int scl_pin);
bool imu_begin(ImuDevice& device);
bool imu_recover(ImuDevice& device);
ImuRead imu_read(const ImuDevice& device);
```

- [ ] **Step 2: Write `firmware/src/imu_mpu6050.cpp`**

```cpp
#include "imu_mpu6050.h"

#include "blindside_config.h"
#include "mpu6050_registers.h"

namespace {

struct Probe {
    uint8_t address;
    uint8_t who_am_i;
};

bool write_register(TwoWire& wire, uint8_t address, uint8_t reg, uint8_t value) {
    wire.beginTransmission(address);
    wire.write(reg);
    wire.write(value);
    return wire.endTransmission() == 0;
}

bool read_registers(TwoWire& wire, uint8_t address, uint8_t reg, uint8_t* out, uint8_t length) {
    wire.beginTransmission(address);
    wire.write(reg);
    if (wire.endTransmission(false) != 0) {
        return false;
    }
    if (wire.requestFrom(address, length) != length) {
        return false;
    }
    for (uint8_t i = 0; i < length; ++i) {
        out[i] = static_cast<uint8_t>(wire.read());
    }
    return true;
}

uint8_t read_who_am_i(TwoWire& wire, uint8_t address) {
    uint8_t who_am_i = 0;
    return read_registers(wire, address, kMpuRegWhoAmI, &who_am_i, 1) ? who_am_i : 0;
}

Probe probe(TwoWire& wire) {
    const uint8_t candidates[] = {kMpuDefaultAddress, kMpuAlternateAddress};
    for (uint8_t address : candidates) {
        uint8_t who_am_i = read_who_am_i(wire, address);
        if (who_am_i_is_usable(who_am_i)) {
            return Probe{address, who_am_i};
        }
    }
    return Probe{0, 0};
}

bool apply_config(TwoWire& wire, uint8_t address, uint8_t who_am_i) {
    MpuConfigPlan plan = mpu_config_plan(who_am_i);
    for (uint8_t i = 0; i < plan.count; ++i) {
        if (!write_register(wire, address, plan.writes[i].reg, plan.writes[i].value)) {
            return false;
        }
    }
    return true;
}

void start_bus(const ImuDevice& device) {
    device.wire->begin(device.sda_pin, device.scl_pin, config::kI2cFrequencyHz);
    device.wire->setTimeOut(config::kI2cTimeoutMs);
}

bool initialize(ImuDevice& device) {
    Probe found = probe(*device.wire);
    device.address = found.address;
    if (found.address == 0) {
        return false;
    }
    device.who_am_i = found.who_am_i;
    return apply_config(*device.wire, found.address, found.who_am_i);
}

}  // namespace

ImuDevice imu_create(uint8_t imu_id, TwoWire* wire, int sda_pin, int scl_pin) {
    ImuDevice device{};
    device.imu_id = imu_id;
    device.wire = wire;
    device.sda_pin = sda_pin;
    device.scl_pin = scl_pin;
    return device;
}

bool imu_begin(ImuDevice& device) {
    start_bus(device);
    return initialize(device);
}

bool imu_recover(ImuDevice& device) {
    device.wire->end();
    return imu_begin(device);
}

ImuRead imu_read(const ImuDevice& device) {
    ImuRead result{};
    uint8_t burst[kMpuBurstSize];
    if (device.address == 0) {
        return result;
    }
    result.ok = read_registers(*device.wire, device.address, kMpuRegAccelXoutH, burst, kMpuBurstSize);
    if (result.ok) {
        result.reading = reading_from_burst(burst);
    }
    return result;
}
```

- [ ] **Step 3: Write `firmware/src/imu_task.h`**

```cpp
#pragma once

#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <stdint.h>

#include "imu_mpu6050.h"

struct ImuSnapshot {
    bool ok;
    uint8_t who_am_i;
    uint32_t reads_ok;
    uint32_t read_failures;
    uint32_t repeats;
    uint32_t samples;
    uint32_t queue_overflows;
};

void imu_task_start(const ImuDevice& device, QueueHandle_t samples);
ImuSnapshot imu_task_snapshot(uint8_t imu_id);
```

- [ ] **Step 4: Write `firmware/src/imu_task.cpp`**

```cpp
#include "imu_task.h"

#include "blindside_config.h"
#include "bundler.h"
#include "imu_health.h"

namespace {

const char* const kTaskNames[kImuCount] = {"imu_a", "imu_b"};

struct ImuTask {
    ImuDevice device;
    ImuHealth health;
    ImuChannel channel;
    QueueHandle_t samples;
    ImuSnapshot counters;
};

ImuTask g_tasks[kImuCount];
portMUX_TYPE g_snapshot_lock = portMUX_INITIALIZER_UNLOCKED;
ImuSnapshot g_snapshots[kImuCount];

ImuSnapshot snapshot_of(const ImuTask& task) {
    ImuSnapshot snapshot = task.counters;
    snapshot.ok = task.health.ok;
    snapshot.who_am_i = task.device.who_am_i;
    snapshot.repeats = task.channel.repeats;
    return snapshot;
}

void publish(const ImuTask& task) {
    ImuSnapshot snapshot = snapshot_of(task);
    taskENTER_CRITICAL(&g_snapshot_lock);
    g_snapshots[task.device.imu_id] = snapshot;
    taskEXIT_CRITICAL(&g_snapshot_lock);
}

void recover_if_due(ImuTask& task, uint32_t now_ms) {
    if (!imu_recovery_due(task.health, now_ms)) {
        return;
    }
    task.health = imu_health_after_probe(imu_recover(task.device), now_ms);
}

void count_read(ImuSnapshot& counters, bool read_ok) {
    counters.reads_ok += read_ok ? 1 : 0;
    counters.read_failures += read_ok ? 0 : 1;
}

ImuRead read_if_up(ImuTask& task, uint32_t now_ms) {
    if (!imu_should_read(task.health)) {
        return ImuRead{};
    }
    ImuRead read = imu_read(task.device);
    task.health = imu_health_after_read(task.health, read.ok, now_ms);
    count_read(task.counters, read.ok);
    return read;
}

void push_samples(ImuTask& task, const ImuStep& step) {
    for (size_t i = 0; i < step.sample_count; ++i) {
        bool queued = xQueueSend(task.samples, &step.samples[i], 0) == pdTRUE;
        task.counters.samples += queued ? 1 : 0;
        task.counters.queue_overflows += queued ? 0 : 1;
    }
}

void run_tick(ImuTask& task, uint32_t now_ms) {
    recover_if_due(task, now_ms);
    if (imu_ticks_due(task.channel, now_ms) == 0) {
        return;
    }
    ImuRead fresh = read_if_up(task, now_ms);
    ImuStep step = imu_channel_step(task.channel, now_ms, fresh);
    task.channel = step.channel;
    push_samples(task, step);
    publish(task);
}

void imu_task(void* argument) {
    ImuTask& task = *static_cast<ImuTask*>(argument);
    task.health = imu_health_after_probe(imu_begin(task.device), millis());
    publish(task);
    TickType_t wake = xTaskGetTickCount();
    task.channel = imu_channel_start(millis() + kImuTickPeriodMs - config::kImuGridLagMs);
    for (;;) {
        vTaskDelayUntil(&wake, pdMS_TO_TICKS(kImuTickPeriodMs));
        run_tick(task, millis());
    }
}

}  // namespace

void imu_task_start(const ImuDevice& device, QueueHandle_t samples) {
    ImuTask& task = g_tasks[device.imu_id];
    task = ImuTask{};
    task.device = device;
    task.samples = samples;
    xTaskCreatePinnedToCore(imu_task, kTaskNames[device.imu_id], config::kTaskStackBytes, &task,
                            config::kImuTaskPriority, nullptr, config::kSensorCore);
}

ImuSnapshot imu_task_snapshot(uint8_t imu_id) {
    taskENTER_CRITICAL(&g_snapshot_lock);
    ImuSnapshot snapshot = g_snapshots[imu_id];
    taskEXIT_CRITICAL(&g_snapshot_lock);
    return snapshot;
}
```

Why the first tick is `now + 5 − 2` ms, read right after `xTaskGetTickCount()`: the first `vTaskDelayUntil` wake comes 5 ms after that reference, so the first tick is already due with 2 ms of margin and every later wake finds exactly one due tick (`config::kImuGridLagMs`). Probing the IMU happens before the reference, so a slow or missing IMU does not shift the grid.

- [ ] **Step 5: Compile the firmware**

Run (from `firmware/`): `python -m platformio run -e esp32dev`
Expected: `SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git -C C:/personal/blindside add firmware/src/imu_mpu6050.h firmware/src/imu_mpu6050.cpp firmware/src/imu_task.h firmware/src/imu_task.cpp
git -C C:/personal/blindside commit -m "feat: driver del MPU6050 y una tarea FreeRTOS por IMU con grilla de 5 ms y recuperación del bus"
```

---

### Task 14: NimBLE link (`ble_link`)

Hardware glue: verified by the `esp32dev` build and checklist items H9-H11, H14, H19 and H20.

**Files:**
- Create: `firmware/src/ble_link.h`
- Create: `firmware/src/ble_link.cpp`
- Modify (only if Step 1 finds a different name): `firmware/platformio.ini`

**Interfaces:**
- Consumes: Task 9 `ControlCommand`, `ControlKind`, `parse_control`, `kMaxControlSize`, `AdvertisingSpeed`, `advertising_speed`; Task 10 `link_is_secure`; Task 7 `LinkParams`; Task 1 `config::k*Uuid`, `config::kConn*`, `config::kSupervisionTimeoutUnits`, `config::kDataLengthOctets`, `config::kPreferredMtu`, `config::kBleTxPowerDbm`, `config::k*AdvertisingM*Units`, `config::kRequireMitm`.
- Produces (used by Tasks 15, 16, 17):
  - `struct LinkSnapshot { bool connected; bool subscribed; bool trusted; bool peer_bonded; uint16_t mtu; uint32_t connected_at_ms; LinkParams params; int last_disconnect_reason; };` (`peer_bonded` = `NimBLEDevice::isBonded(getIdAddress())` at connect)
  - `struct PeerSecurity { bool valid; bool secure; NimBLEAddress identity; };` (`secure` = `link_is_secure(encrypted, bonded, authenticated, config::kRequireMitm)`)
  - `void ble_link_begin(const char* device_name);`
  - `void ble_link_set_passkey(uint32_t passkey);`
  - `void ble_link_start_advertising(bool whitelist_only, uint32_t now_ms);`
  - `void ble_link_poll_advertising(bool whitelist_only, uint32_t now_ms);` (fast → slow after 30 s, applies filter-policy changes, restarts advertising if it stopped)
  - `LinkSnapshot ble_link_snapshot();`
  - `uint32_t ble_link_report_generation();` (+1 on every `onConnParamsUpdate` and on every `onSubscribe` that enables notifications)
  - `int8_t ble_link_tx_power();` (`NimBLEDevice::getPower()`)
  - `bool ble_link_take_auth_event();` (true once per authentication-complete event)
  - `PeerSecurity ble_link_peer_security();`
  - `void ble_link_set_trusted(bool trusted);`
  - `ControlCommand ble_link_take_control();` (`ControlKind::None` when nothing was written)
  - `bool ble_link_notify(const uint8_t* bytes, size_t length);` (false when the NimBLE queue is full or nobody is connected)
  - `void ble_link_update_info(const char* json, size_t length);`
  - `void ble_link_disconnect();`

- [ ] **Step 1: Verify the NimBLE internals this task relies on**

`lib_deps = h2zero/NimBLE-Arduino@^2` floats to the newest 2.x, and two things here are not public API: the `CONFIG_BT_NIMBLE_MSYS1_BLOCK_COUNT` build flag (silently ignored if the name is wrong) and `ble_gatts_set_clt_cfg_perm_flags()` (only a link error would tell).

Run (from `firmware/`): `python -m platformio pkg install -e esp32dev && grep -n "^version" .pio/libdeps/esp32dev/NimBLE-Arduino/library.properties && grep -n "MSYS1_BLOCK_COUNT\|MSYS_1_BLOCK_COUNT" .pio/libdeps/esp32dev/NimBLE-Arduino/src/nimconfig.h && grep -rn "ble_gatts_set_clt_cfg_perm_flags" .pio/libdeps/esp32dev/NimBLE-Arduino/src`
Expected (NimBLE-Arduino 2.5.1, resolved on 2026-09-30):
- `version=2.5.1`
- `#ifdef CONFIG_BT_NIMBLE_MSYS1_BLOCK_COUNT // backward compatibility` followed by `#define MYNEWT_VAL_MSYS_1_BLOCK_COUNT CONFIG_BT_NIMBLE_MSYS1_BLOCK_COUNT` (and `#define MYNEWT_VAL_MSYS_1_BLOCK_COUNT 12` in the `#else`), so the flag in `platformio.ini` is honoured.
- `.../nimble/host/src/ble_gatts.c:183:void ble_gatts_set_clt_cfg_perm_flags(uint8_t flags)` and its declaration in `.../nimble/host/src/ble_gatt_priv.h`.

If a newer version shows a different macro inside the `#ifdef`/`#ifndef` that sets `MYNEWT_VAL_MSYS_1_BLOCK_COUNT`, replace the `-D CONFIG_BT_NIMBLE_MSYS1_BLOCK_COUNT=24` line in `platformio.ini` with that exact macro name. If `ble_gatts_set_clt_cfg_perm_flags` is gone, delete the `extern` declaration and its call in Step 3 (the stream gate still requires a trusted encrypted peer) and change `protocol/PROTOCOL.md` §1 to "the CCCD write itself is accepted; no data flows until the link is trusted". Record the resolved version and both findings in the task report.

- [ ] **Step 2: Write `firmware/src/ble_link.h`**

```cpp
#pragma once

#include <NimBLEDevice.h>
#include <stddef.h>
#include <stdint.h>

#include "bundler.h"
#include "control_command.h"

struct LinkSnapshot {
    bool connected;
    bool subscribed;
    bool trusted;
    bool peer_bonded;
    uint16_t mtu;
    uint32_t connected_at_ms;
    LinkParams params;
    int last_disconnect_reason;
};

struct PeerSecurity {
    bool valid;
    bool secure;
    NimBLEAddress identity;
};

void ble_link_begin(const char* device_name);
void ble_link_set_passkey(uint32_t passkey);
void ble_link_start_advertising(bool whitelist_only, uint32_t now_ms);
void ble_link_poll_advertising(bool whitelist_only, uint32_t now_ms);
LinkSnapshot ble_link_snapshot();
uint32_t ble_link_report_generation();
int8_t ble_link_tx_power();
bool ble_link_take_auth_event();
PeerSecurity ble_link_peer_security();
void ble_link_set_trusted(bool trusted);
ControlCommand ble_link_take_control();
bool ble_link_notify(const uint8_t* bytes, size_t length);
void ble_link_update_info(const char* json, size_t length);
void ble_link_disconnect();
```

- [ ] **Step 3: Write `firmware/src/ble_link.cpp`**

```cpp
#include "ble_link.h"

#include <Arduino.h>

#include <atomic>

#include "ble_rules.h"
#include "blindside_config.h"
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

ServerCallbacks g_server_callbacks;
StreamCallbacks g_stream_callbacks;
ControlCallbacks g_control_callbacks;

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

void ble_link_begin(const char* device_name) {
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

void ble_link_update_info(const char* json, size_t length) {
    g_info->setValue(reinterpret_cast<const uint8_t*>(json), length);
}

void ble_link_disconnect() {
    uint16_t handle = g_conn_handle.load();
    if (handle != BLE_HS_CONN_HANDLE_NONE) {
        g_server->disconnect(handle);
    }
}
```

`config::kRequireMitm` selects everything the S12 plan B changes in one place: `setSecurityAuth(bond, MITM, SC)`, the IO capability (DisplayOnly vs NoInputNoOutput), the `control` permission (WRITE_AUTHEN vs WRITE_ENC, same handles, so the frozen GATT table does not move) and whether `secure` requires an authenticated link.

- [ ] **Step 4: Compile the firmware**

Run (from `firmware/`): `python -m platformio run -e esp32dev`
Expected: `SUCCESS`, no `undefined reference`.

- [ ] **Step 5: Commit**

```bash
git -C C:/personal/blindside add firmware/src/ble_link.h firmware/src/ble_link.cpp firmware/platformio.ini
git -C C:/personal/blindside commit -m "feat: servidor GATT NimBLE con características cifradas, LINK por generación y plan B de S12 por configuración"
```

---

### Task 15: Pairing glue, serial commands and status LED (`pairing`, `status_led`)

Hardware glue: all decisions are in Task 10. Verified by the `esp32dev` build and checklist items H9-H13, H18 and H19.

**Files:**
- Create: `firmware/src/pairing.h`
- Create: `firmware/src/pairing.cpp`
- Create: `firmware/src/status_led.h`
- Create: `firmware/src/status_led.cpp`

**Interfaces:**
- Consumes: Task 10 `PairingWindow`, `ButtonGesture`, `AuthDecision`, `gestures_still_counted`, `gesture_on_release`, `window_opened`, `window_closed`, `initial_window`, `window_after_tick`, `decide_authentication`, `should_drop_at_connect`, `should_drop_unauthenticated`, `passkey_from_random`, `kPasskeyModulus`, `SerialCommand`, `parse_serial_line`, `LedInputs`, `led_on`; Task 14 `ble_link_set_passkey`, `ble_link_snapshot`, `LinkSnapshot`, `ble_link_take_auth_event`, `ble_link_peer_security`, `PeerSecurity`, `ble_link_set_trusted`, `ble_link_disconnect`; NimBLE `NimBLEDevice::getNumBonds/getBondedAddress/deleteBond/deleteAllBonds/whiteListAdd/whiteListRemove/getWhiteListCount/getWhiteListAddress`, `NimBLEAddress::isNull/toString`; Arduino `Preferences`; `esp_random()`; Task 1 `config::kBootButtonPin`, `config::kStatusLedPin`, `config::kPreferencesNamespace`, `config::kPasskeyKey`, `config::kConnectWhitelistOnly`.
- Produces (used by Task 17):
  - `constexpr size_t kSerialLineCapacity = 24;`
  - `struct PairingState { PairingWindow window; NimBLEAddress trusted; uint32_t passkey; bool button_was_pressed; uint32_t button_pressed_ms; bool connection_seen; bool pairing_allowed_for_connection; bool drop_requested; char serial_line[24]; size_t serial_length; };`
  - `PairingState pairing_begin(uint32_t now_ms);` (call after `ble_link_begin`: loads or creates the passkey, prints it, loads the single trusted bond, refreshes the whitelist, opens the window when there is no bond)
  - `void pairing_poll(PairingState& state, uint32_t now_ms);` (BOOT gestures on release in the first 60 s, serial `key` / `key new`, window expiry, authentication decisions, dropping unknown or unauthenticated peers)
  - `bool pairing_whitelist_only(const PairingState& state);`
  - `void status_led_begin();` `void status_led_show(const LedInputs& inputs);`

- [ ] **Step 1: Write `firmware/src/pairing.h`**

```cpp
#pragma once

#include <NimBLEDevice.h>
#include <stddef.h>
#include <stdint.h>

#include "pairing_rules.h"

constexpr size_t kSerialLineCapacity = 24;

struct PairingState {
    PairingWindow window;
    NimBLEAddress trusted;
    uint32_t passkey;
    bool button_was_pressed;
    uint32_t button_pressed_ms;
    bool connection_seen;
    bool pairing_allowed_for_connection;
    bool drop_requested;
    char serial_line[kSerialLineCapacity];
    size_t serial_length;
};

PairingState pairing_begin(uint32_t now_ms);
void pairing_poll(PairingState& state, uint32_t now_ms);
bool pairing_whitelist_only(const PairingState& state);
```

- [ ] **Step 2: Write `firmware/src/pairing.cpp`**

```cpp
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
```

Peer drops, in order of what `peer_must_go` checks: outside the pairing window a peer whose identity is not bonded is dropped on the first `loop()` pass after it connects (spec §4.3 plan B: "verificar el bond en el host y desconectar al instante"; spec §9: a rival reconnecting in a loop must not delay the watch); the bonded watch gets 5 s to encrypt; a peer that connected while the window was open gets 60 s to bond. `drop_requested` makes each connection print one line instead of one per pass.

- [ ] **Step 3: Write `firmware/src/status_led.h`**

```cpp
#pragma once

#include "led_pattern.h"

void status_led_begin();
void status_led_show(const LedInputs& inputs);
```

- [ ] **Step 4: Write `firmware/src/status_led.cpp`**

```cpp
#include "status_led.h"

#include <Arduino.h>

#include "blindside_config.h"

void status_led_begin() {
    pinMode(config::kStatusLedPin, OUTPUT);
    digitalWrite(config::kStatusLedPin, LOW);
}

void status_led_show(const LedInputs& inputs) {
    digitalWrite(config::kStatusLedPin, led_on(inputs) ? HIGH : LOW);
}
```

- [ ] **Step 5: Compile the firmware**

Run (from `firmware/`): `python -m platformio run -e esp32dev`
Expected: `SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git -C C:/personal/blindside add firmware/src/pairing.h firmware/src/pairing.cpp firmware/src/status_led.h firmware/src/status_led.cpp
git -C C:/personal/blindside commit -m "feat: emparejamiento con clave en NVS, gestos de BOOT al soltar, comandos key y LED de estado"
```

---

### Task 16: Bundler task (`stream_sender`)

Hardware glue around the pure Tasks 7, 8 and 11. Verified by the `esp32dev` build and checklist items H14, H15 and H7b.

**Files:**
- Create: `firmware/src/stream_sender.h`
- Create: `firmware/src/stream_sender.cpp`

**Interfaces:**
- Consumes: Task 8 `StreamBacklog`, `backlog_clear`, `backlog_add_radar`, `backlog_add_imu`, `backlog_cut_input`, `backlog_consume`, `backlog_note_upstream_drops`; Task 7 `bundle_cut`, `with_status`, `with_link`, `packet_flags`, `payload_limit_for_mtu`, `HeaderFields`, `LinkHealth`, `Packet`, `StatusEntry`, `RadarFrame`, `kRadarCount`, `kImuCount`; Task 11 `CutAction`, `cut_action`, `Outbox`, `outbox_refilled`, `outbox_cleared`, `outbox_after_send`, `outbox_empty`, `retry_allowed`, `status_due`, `next_status_after`, `link_report_due`, `kCutPeriodMs`, `kNotifyRetryMs`; Task 9 `stream_gate_open`, `StreamGateInput`; Task 12 `radar_port_snapshot`, `radar_snapshot_alive`, `radar_port_queue_overflows`; Task 13 `imu_task_snapshot`, `ImuSample`; Task 14 `ble_link_snapshot`, `ble_link_report_generation`, `ble_link_notify`; Task 1 `config::kTaskStackBytes`, `config::kBundlerTaskPriority`, `config::kSensorCore`.
- Produces (used by Task 17):
  - `struct SenderSources { QueueHandle_t radar_frames; QueueHandle_t imu_samples[2]; };`
  - `struct SenderStats { uint32_t packets_sent; uint32_t notify_failures; uint32_t dropped_total; uint32_t skipped_cuts; };`
  - `void stream_sender_start(const SenderSources& sources);` (starts the `bundler` task: every 100 ms drain the queues into the backlog, cut at `millis()`, bundle into a 6-packet outbox and notify, retrying a full NimBLE queue every 10 ms until 10 ms before the next cut)
  - `SenderStats stream_sender_stats();` (any task)

- [ ] **Step 1: Write `firmware/src/stream_sender.h`**

```cpp
#pragma once

#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <stdint.h>

#include "bundler.h"

struct SenderSources {
    QueueHandle_t radar_frames;
    QueueHandle_t imu_samples[kImuCount];
};

struct SenderStats {
    uint32_t packets_sent;
    uint32_t notify_failures;
    uint32_t dropped_total;
    uint32_t skipped_cuts;
};

void stream_sender_start(const SenderSources& sources);
SenderStats stream_sender_stats();
```

- [ ] **Step 2: Write `firmware/src/stream_sender.cpp`**

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
    Outbox outbox;
    uint16_t next_seq;
    uint32_t next_status_ms;
    uint32_t reported_generation;
    uint32_t upstream_drops_seen;
    uint32_t skipped_cuts;
};

struct StatusPair {
    StatusEntry entries[kRadarCount];
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

CutInput cut_input_for(const SenderState& state, uint32_t cut_ms, uint32_t generation) {
    CutInput input = backlog_cut_input(g_backlog, cut_ms);
    if (status_due(cut_ms, state.next_status_ms)) {
        input = with_status(input, current_status().entries);
    }
    if (link_report_due(generation, state.reported_generation)) {
        input = with_link(input, ble_link_snapshot().params);
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

void bundle(SenderState& state, uint32_t cut_ms, uint16_t mtu) {
    uint32_t generation = ble_link_report_generation();
    CutInput input = cut_input_for(state, cut_ms, generation);
    HeaderFields header{packet_flags(current_health(cut_ms)), state.next_seq, cut_ms};
    BundleResult result = bundle_cut(input, header, payload_limit_for_mtu(mtu), g_packets, kOutboxCapacity);
    backlog_consume(g_backlog, result);
    state.outbox = outbox_refilled(state.outbox, result.packet_count);
    state.next_seq = result.next_seq;
    remember_optional_sections(state, result, cut_ms, generation);
}

void make_cut(SenderState& state, uint32_t cut_ms) {
    LinkSnapshot link = ble_link_snapshot();
    bool gate_open = stream_gate_open(StreamGateInput{link.connected, link.subscribed, link.trusted, link.mtu});
    switch (cut_action(gate_open, state.outbox)) {
        case CutAction::DiscardAll:
            backlog_clear(g_backlog);
            state.outbox = outbox_cleared(state.outbox);
            break;
        case CutAction::KeepBacklog:
            state.skipped_cuts++;
            break;
        case CutAction::Bundle:
            bundle(state, cut_ms, link.mtu);
            break;
    }
}

bool send_head(SenderState& state) {
    const Packet& packet = g_packets[state.outbox.head];
    bool sent = ble_link_notify(packet.bytes, packet.length);
    state.outbox = outbox_after_send(state.outbox, sent);
    return sent;
}

void flush_outbox(SenderState& state, uint32_t cut_ms) {
    while (!outbox_empty(state.outbox)) {
        if (send_head(state)) {
            continue;
        }
        if (!retry_allowed(cut_ms, millis())) {
            return;
        }
        vTaskDelay(pdMS_TO_TICKS(kNotifyRetryMs));
    }
}

void publish_stats(const SenderState& state) {
    SenderStats stats{state.outbox.sent, state.outbox.failures, g_backlog.dropped_total, state.skipped_cuts};
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
        drain_sources(g_state);
        make_cut(g_state, cut_ms);
        flush_outbox(g_state, cut_ms);
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

The LINK report: the generation is read before the cut and remembered only when `link_consumed`, so a LINK that did not fit (or a gate that was closed) is offered again on the next cut, and an update that lands mid-cut changes the generation and is reported once more.

- [ ] **Step 3: Compile the firmware**

Run (from `firmware/`): `python -m platformio run -e esp32dev`
Expected: `SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git -C C:/personal/blindside add firmware/src/stream_sender.h firmware/src/stream_sender.cpp
git -C C:/personal/blindside commit -m "feat: tarea del bundler con cortes de 100 ms, LINK, STATUS y reintento de notificaciones"
```

---

### Task 17: Diagnostics, `setup()`/`loop()` and the hardware checklist

**Files:**
- Create: `firmware/src/diagnostics.h`
- Create: `firmware/src/diagnostics.cpp`
- Modify: `firmware/src/main.cpp` (replace the Task 1 stub entirely)
- Create: `firmware/HARDWARE_CHECKLIST.md`

**Interfaces:**
- Consumes: everything above. Exact names: `radar_port_create`, `radar_port_start_task`, `radar_port_request_restart`, `radar_port_snapshot`, `radar_snapshot_alive`, `RadarSnapshot`, `imu_create`, `imu_task_start`, `imu_task_snapshot`, `ImuSnapshot`, `stream_sender_start`, `stream_sender_stats`, `SenderSources`, `SenderStats`, `ble_link_begin`, `ble_link_start_advertising`, `ble_link_poll_advertising`, `ble_link_snapshot`, `ble_link_take_control`, `ble_link_tx_power`, `ble_link_update_info`, `LinkSnapshot`, `pairing_begin`, `pairing_poll`, `pairing_whitelist_only`, `PairingState`, `status_led_begin`, `status_led_show`, `LedInputs`, `identify_allowed`, `identify_finished`, `ControlKind`, `ControlCommand`, `device_name_from_mac`, `format_info_json`, `BeltInfo`, `RadarInfo`, `ImuInfo`, `kInfoJsonBufferSize`, `deadline_reached`, `RadarFrame`, `ImuSample`, `kRadarCount`, `kImuCount`.
- Produces: the complete firmware image; `firmware/HARDWARE_CHECKLIST.md`.
  - `struct DiagnosticsMemory { ImuSnapshot window_start[2]; bool imu_ok_seen[2]; };`
  - `const char* reset_reason_name(esp_reset_reason_t reason);`
  - `DiagnosticsMemory diagnostics_report_imu_changes(const DiagnosticsMemory&, uint32_t now_ms);` (prints `imu N: DOWN at …` / `imu N: ok at …` on every transition)
  - `DiagnosticsMemory diagnostics_print(const DiagnosticsMemory&, uint32_t now_ms);` (the 5 s line; `reads` and `smp` are per-window deltas)

- [ ] **Step 1: Write `firmware/src/diagnostics.h`**

```cpp
#pragma once

#include <esp_system.h>
#include <stdint.h>

#include "bundler.h"
#include "imu_task.h"

struct DiagnosticsMemory {
    ImuSnapshot window_start[kImuCount];
    bool imu_ok_seen[kImuCount];
};

const char* reset_reason_name(esp_reset_reason_t reason);
DiagnosticsMemory diagnostics_report_imu_changes(const DiagnosticsMemory& memory, uint32_t now_ms);
DiagnosticsMemory diagnostics_print(const DiagnosticsMemory& memory, uint32_t now_ms);
```

- [ ] **Step 2: Write `firmware/src/diagnostics.cpp`**

```cpp
#include "diagnostics.h"

#include <Arduino.h>

#include "ble_link.h"
#include "radar_port.h"
#include "stream_sender.h"

namespace {

// Indexed by esp_reset_reason_t (ESP-IDF 4.4 values 0-10); anything newer reports UNKNOWN.
const char* const kResetReasonNames[] = {"UNKNOWN",  "POWERON", "EXT",       "SW",       "PANIC", "INT_WDT",
                                         "TASK_WDT", "WDT",     "DEEPSLEEP", "BROWNOUT", "SDIO"};
constexpr size_t kResetReasonCount = sizeof(kResetReasonNames) / sizeof(kResetReasonNames[0]);

void print_radar(uint8_t radar_id, uint32_t now_ms) {
    RadarSnapshot radar = radar_port_snapshot(radar_id);
    Serial.printf(" radar%u[alive=%d ok=%lu bad=%u rst=%u baud=%lu gap=%lu..%lu]", static_cast<unsigned>(radar_id),
                  radar_snapshot_alive(radar, now_ms) ? 1 : 0, static_cast<unsigned long>(radar.frames_ok),
                  static_cast<unsigned>(radar.status.bad_frames), static_cast<unsigned>(radar.status.restarts),
                  static_cast<unsigned long>(radar.baud), static_cast<unsigned long>(radar.last_window.min_gap_ms),
                  static_cast<unsigned long>(radar.last_window.max_gap_ms));
}

void print_imu(uint8_t imu_id, const ImuSnapshot& now, const ImuSnapshot& before) {
    Serial.printf(" imu%u[ok=%d who=0x%02X reads=%lu fail=%lu rep=%lu smp=%lu]", static_cast<unsigned>(imu_id),
                  now.ok ? 1 : 0, static_cast<unsigned>(now.who_am_i),
                  static_cast<unsigned long>(now.reads_ok - before.reads_ok),
                  static_cast<unsigned long>(now.read_failures), static_cast<unsigned long>(now.repeats),
                  static_cast<unsigned long>(now.samples - before.samples));
}

void print_link() {
    LinkSnapshot link = ble_link_snapshot();
    Serial.printf(" link[conn=%d sub=%d trusted=%d mtu=%u itvl=%u lat=%u sup=%u disc=%d]", link.connected ? 1 : 0,
                  link.subscribed ? 1 : 0, link.trusted ? 1 : 0, static_cast<unsigned>(link.mtu),
                  static_cast<unsigned>(link.params.interval_units), static_cast<unsigned>(link.params.latency),
                  static_cast<unsigned>(link.params.supervision_units), link.last_disconnect_reason);
}

void print_sender() {
    SenderStats stats = stream_sender_stats();
    Serial.printf(" tx[sent=%lu fail=%lu dropped=%lu skipped=%lu]\n", static_cast<unsigned long>(stats.packets_sent),
                  static_cast<unsigned long>(stats.notify_failures), static_cast<unsigned long>(stats.dropped_total),
                  static_cast<unsigned long>(stats.skipped_cuts));
}

void report_imu_change(uint8_t imu_id, const ImuSnapshot& imu, uint32_t now_ms) {
    Serial.printf("imu %u: %s at %lu ms (who=0x%02X)\n", static_cast<unsigned>(imu_id), imu.ok ? "ok" : "DOWN",
                  static_cast<unsigned long>(now_ms), static_cast<unsigned>(imu.who_am_i));
}

}  // namespace

const char* reset_reason_name(esp_reset_reason_t reason) {
    size_t index = static_cast<size_t>(reason);
    return index < kResetReasonCount ? kResetReasonNames[index] : kResetReasonNames[0];
}

DiagnosticsMemory diagnostics_report_imu_changes(const DiagnosticsMemory& memory, uint32_t now_ms) {
    DiagnosticsMemory next = memory;
    for (uint8_t i = 0; i < kImuCount; ++i) {
        ImuSnapshot imu = imu_task_snapshot(i);
        if (imu.ok != memory.imu_ok_seen[i]) {
            report_imu_change(i, imu, now_ms);
        }
        next.imu_ok_seen[i] = imu.ok;
    }
    return next;
}

DiagnosticsMemory diagnostics_print(const DiagnosticsMemory& memory, uint32_t now_ms) {
    DiagnosticsMemory next = memory;
    Serial.printf("diag up=%lus", static_cast<unsigned long>(now_ms / 1000));
    for (uint8_t i = 0; i < kRadarCount; ++i) {
        print_radar(i, now_ms);
    }
    for (uint8_t i = 0; i < kImuCount; ++i) {
        next.window_start[i] = imu_task_snapshot(i);
        print_imu(i, next.window_start[i], memory.window_start[i]);
    }
    print_link();
    print_sender();
    return next;
}
```

- [ ] **Step 3: Replace `firmware/src/main.cpp` with `setup()` and `loop()`**

```cpp
#include <Arduino.h>
#include <Wire.h>
#include <esp_mac.h>
#include <esp_random.h>
#include <esp_system.h>

#include "ble_link.h"
#include "ble_rules.h"
#include "blindside_config.h"
#include "control_command.h"
#include "cut_schedule.h"
#include "diagnostics.h"
#include "imu_task.h"
#include "info_json.h"
#include "led_pattern.h"
#include "pairing.h"
#include "radar_port.h"
#include "status_led.h"
#include "stream_sender.h"

namespace {

PairingState g_pairing;
DiagnosticsMemory g_diagnostics;
uint32_t g_boot_id = 0;
uint32_t g_next_info_ms = 0;
uint32_t g_next_diagnostics_ms = 0;
bool g_session_active = false;
bool g_identify_active = false;
uint32_t g_identify_started_ms = 0;

void print_banner() {
    Serial.printf("blindside fw %s boot_id=%08lx reset=%s\n", config::kFirmwareVersion,
                  static_cast<unsigned long>(g_boot_id), reset_reason_name(esp_reset_reason()));
}

void start_ble() {
    uint8_t mac[6] = {0};
    esp_read_mac(mac, ESP_MAC_BT);
    ble_link_begin(device_name_from_mac(mac).text);
}

void start_radar_tasks(QueueHandle_t frames) {
    radar_port_start_task(radar_port_create(0, &Serial2, UART_NUM_2, config::kRadarARxPin, config::kRadarATxPin),
                          frames);
    radar_port_start_task(radar_port_create(1, &Serial1, UART_NUM_1, config::kRadarBRxPin, config::kRadarBTxPin),
                          frames);
}

void start_imu_tasks(const SenderSources& sources) {
    imu_task_start(imu_create(0, &Wire1, config::kImuASdaPin, config::kImuASclPin), sources.imu_samples[0]);
    imu_task_start(imu_create(1, &Wire, config::kImuBSdaPin, config::kImuBSclPin), sources.imu_samples[1]);
}

SenderSources create_queues() {
    SenderSources sources{};
    sources.radar_frames = xQueueCreate(config::kRadarQueueDepth, sizeof(RadarFrame));
    for (uint8_t imu = 0; imu < kImuCount; ++imu) {
        sources.imu_samples[imu] = xQueueCreate(config::kImuQueueDepth, sizeof(ImuSample));
    }
    return sources;
}

void start_tasks() {
    SenderSources sources = create_queues();
    start_radar_tasks(sources.radar_frames);
    start_imu_tasks(sources);
    stream_sender_start(sources);
}

void start_identify(uint32_t now_ms) {
    if (!identify_allowed(g_session_active)) {
        return;
    }
    g_identify_active = true;
    g_identify_started_ms = now_ms;
}

void handle_control(uint32_t now_ms) {
    ControlCommand command = ble_link_take_control();
    switch (command.kind) {
        case ControlKind::RestartRadar:
            radar_port_request_restart(command.argument);
            break;
        case ControlKind::Identify:
            start_identify(now_ms);
            break;
        case ControlKind::SessionActive:
            g_session_active = command.argument == 1;
            break;
        case ControlKind::Invalid:
            Serial.println("control: ignored invalid write");
            break;
        case ControlKind::None:
            break;
    }
}

RadarInfo radar_info(uint8_t radar_id) {
    RadarSnapshot radar = radar_port_snapshot(radar_id);
    return RadarInfo{radar_id, radar.firmware, radar.baud};
}

BeltInfo current_info(uint32_t now_ms) {
    LinkSnapshot link = ble_link_snapshot();
    BeltInfo info{};
    info.firmware_version = config::kFirmwareVersion;
    info.boot_id = g_boot_id;
    info.reset_reason = reset_reason_name(esp_reset_reason());
    info.mtu = link.mtu;
    for (uint8_t i = 0; i < kRadarCount; ++i) {
        info.radars[i] = radar_info(i);
    }
    for (uint8_t i = 0; i < kImuCount; ++i) {
        info.imus[i] = ImuInfo{i, imu_task_snapshot(i).who_am_i};
    }
    info.tx_power_dbm = ble_link_tx_power();
    info.conn = link.params;
    info.uptime_s = now_ms / 1000;
    return info;
}

void refresh_info_if_due(uint32_t now_ms) {
    if (!deadline_reached(now_ms, g_next_info_ms)) {
        return;
    }
    char json[kInfoJsonBufferSize];
    size_t length = format_info_json(current_info(now_ms), json, sizeof(json));
    if (length > 0) {
        ble_link_update_info(json, length);
    }
    g_next_info_ms = now_ms + config::kInfoRefreshMs;
}

void show_led(uint32_t now_ms) {
    if (g_identify_active && identify_finished(g_identify_started_ms, now_ms)) {
        g_identify_active = false;
    }
    status_led_show(LedInputs{now_ms, g_pairing.window.open, g_identify_active, g_identify_started_ms});
}

void print_diagnostics_if_due(uint32_t now_ms) {
    g_diagnostics = diagnostics_report_imu_changes(g_diagnostics, now_ms);
    if (!deadline_reached(now_ms, g_next_diagnostics_ms)) {
        return;
    }
    g_diagnostics = diagnostics_print(g_diagnostics, now_ms);
    g_next_diagnostics_ms = now_ms + config::kDiagnosticsPeriodMs;
}

}  // namespace

void setup() {
    Serial.setTxBufferSize(config::kSerialTxBufferBytes);
    Serial.begin(config::kSerialBaud);
    status_led_begin();
    status_led_show(LedInputs{millis(), false, false, 0});
    pinMode(config::kBootButtonPin, INPUT_PULLUP);
    start_ble();
    // esp_random() is only truly random once the radio is on, which start_ble() just did.
    g_boot_id = esp_random();
    print_banner();
    g_pairing = pairing_begin(millis());
    ble_link_start_advertising(pairing_whitelist_only(g_pairing), millis());
    start_tasks();
    g_next_info_ms = millis();
    g_next_diagnostics_ms = millis() + config::kDiagnosticsPeriodMs;
}

void loop() {
    uint32_t now_ms = millis();
    pairing_poll(g_pairing, now_ms);
    ble_link_poll_advertising(pairing_whitelist_only(g_pairing), now_ms);
    handle_control(now_ms);
    refresh_info_if_due(now_ms);
    show_led(now_ms);
    print_diagnostics_if_due(now_ms);
    delay(config::kLoopPeriodMs);
}
```

Why this shape: `setup()` only starts things, so the LED rules hold from the first 2 s (each radar configures itself inside its own task, up to ~5 s when a radar is missing). `loop()` runs at the lowest priority every 5 ms and touches neither a UART nor an I2C bus. The serial TX buffer (1 KB) keeps a diagnostics line from blocking any task.

- [ ] **Step 4: Write `firmware/HARDWARE_CHECKLIST.md`**

````markdown
# Blindside firmware: checklist de hardware (Santiago)

Los agentes no tienen hardware. Estos pasos son los únicos que validan el firmware de verdad.
Comandos desde `firmware/`. Monitor serie: `pio device monitor -b 115200`.
Línea de diagnóstico cada 5 s (`reads` y `smp` cuentan solo los últimos 5 s; `gap` es el Δt mínimo..máximo entre tramas en esa ventana):
`diag up=… radar0[alive ok bad rst baud gap=min..max] radar1[…] imu0[ok who reads fail rep smp] imu1[…] link[conn sub trusted mtu itvl lat sup disc] tx[sent fail dropped skipped]`.
Además, cada cambio de estado de un IMU sale en su propia línea: `imu N: DOWN at <ms>` / `imu N: ok at <ms>`.

## Preparación

- [ ] **H0. CI.** El remoto `origin` ya existe (`https://github.com/santiquiroz/blindside.git`). Solo falta autorizar el push: `unset GITHUB_TOKEN` (en este PC apunta a la cuenta de trabajo) y `git push -u origin main`. Los jobs `vectors` y `firmware` deben quedar en verde; `radar-core` y `wear-app` en verde con aviso de "skipping" hasta que existan los planes 01 y 03. Sin compilador local, `python firmware/tools/run_native_tests.py` (con `python -m pip install --user ziglang`) corre las mismas suites antes del push.
- [ ] **H1. Flasheo.** `pio run -e esp32dev -t upload`. En el monitor: `blindside fw 0.1.0 boot_id=… reset=POWERON` y `blindside passkey: NNNNNN`. **Anotar la clave en una etiqueta del cinturón.**
- [ ] **H2. Pruebas en la placa.** `pio test -e esp32dev`: todas las suites terminan en `0 Failures`.
- [ ] **H3. Cableado (spec §2.2).** Radar A: RX2 GPIO16 ← TX del radar, TX2 GPIO17 → RX del radar. Radar B: GPIO26 ← TX, GPIO27 → RX. IMU A: SDA GPIO32, SCL GPIO33. IMU B: SDA GPIO21, SCL GPIO22. IMUs a 3V3, radares a 5V. GPIO12 libre. Arrancar **sin** presionar BOOT.

## Radares

- [ ] **H4. Arranque.** Para cada radar: `read-firmware ok`, `multi-target ok`, `bluetooth-off ok`, `restart ok` y `radar N: baud=256000 fw=V2.…`. Con HLKRadarTool, después de este arranque el LD2450 ya no aparece por Bluetooth (§10.3).
- [ ] **H5. Tramas.** `radar0[ok=…]` y `radar1[ok=…]` suben ~50 entre dos líneas `diag` (10 tramas/s, haya o no personas al frente) y `gap` queda cerca de `100..100`. Con el cinturón quieto 5 min, `bad` no sube.
- [ ] **H6. Watchdog.** Desconectar 5 s el hilo TX del radar B con todo encendido: `radar1[alive=0]` y, ~2 s después de la última trama, `radar 1: restart sent (total 1)`. Reconectar: `alive=1` (inmediato si el módulo no se colgó; si no, en el siguiente reintento, ≤ 30 s).
- [ ] **H16. Reinicio por control.** Desde la app (o nRF Connect ya emparejado, escribiendo `01 00` en `control`): `radar 0: restart sent` y las tramas vuelven.

## IMUs

- [ ] **H7. Detección y caída.** `imu0[ok=1 who=0x68 …]` (un clon con 0x70/0x71/0x98 también debe decir `ok=1`). Desconectar el SDA de la IMU A: sale `imu 0: DOWN at …`; reconectar: `imu 0: ok at …` en ≤ 2 s.
- [ ] **H7b. Cable de IMU en falla (spec §9).** Con los radares transmitiendo, mantener el SDA de la IMU A a GND 10 s y soltarlo. Pasa si se cumplen las cuatro:
  - `gap` de los dos radares no cambia más de ±2 ms frente a la corrida sana (H5);
  - la IMU B sigue con `reads` ≥ 995 y `smp` = 250 por línea, es decir ≥ 199 lecturas/s y n = 5 por corte;
  - el bit2 cae en ≤ 100 ms mientras el bit3 sigue en 1: `imu 0: DOWN at T1` aparece ≤ 100 ms después de poner el SDA a GND (cronometrar contra el video del monitor o un segundo observador), y `imu1[ok=1]` no cambia;
  - la IMU A vuelve sola: `imu 0: ok at T2` con T2 ≤ 2 s después de soltar el SDA.
- [ ] **H8. Ritmo.** Durante 10 min con los dos radares y las dos IMUs: `reads` ≥ 995 y `smp` = 250 por línea en cada IMU (≥ 199 lecturas/s, 50 muestras/s), y `rep` no sube.

## BLE, emparejamiento y seguridad

- [ ] **H9. Primer emparejamiento.** Sin bond, el LED parpadea 60 s. Con la app del reloj (plan 03) o nRF Connect: conectar, escribir la clave, ver `pairing: bonded …` y que el LED deja de parpadear.
- [ ] **H10. Spike S10 (decisión pendiente de Santiago).** Con `config::kConnectWhitelistOnly = false` (por defecto): apagar y encender el ESP32 y medir la reconexión del reloj (≤ 5 s); repetir tras > 15 min (rotación de la dirección privada). Opcional: poner `true`, recompilar y repetir; si el reloj ya no reconecta fuera de la ventana, volver a `false` (plan B) y anotarlo en la spec. Si con `false` el reloj emparejado sale desconectado con `pairing: dropped an unknown or unauthenticated peer`, NimBLE no resolvió su dirección: anotarlo, es un fallo de S10.
- [ ] **H11. Extraños.** Fuera de la ventana, un teléfono con nRF Connect no logra datos: con el filtro apagado conecta y queda desconectado de inmediato (`pairing: dropped …`, < 0,5 s); con el filtro encendido no conecta. Dentro de la ventana conecta, pero suscribirse a `stream` o leer `info` pide la clave y falla sin ella.
- [ ] **H19. Rival en bucle.** Con el reloj emparejado desconectado (Bluetooth del reloj apagado), poner un teléfono con nRF Connect a reconectarse en bucle al cinturón. Encender el Bluetooth del reloj: reconecta en ≤ 5 s igual que sin el rival.
- [ ] **H12. Botón BOOT** (solo cuenta en los primeros 60 s tras encender, y actúa al soltar). Sostener 3-10 s y soltar: `pairing window open (60 s)` y parpadeo. Sostener ≥ 10 s y soltar: `pairing: bonds erased`, la ventana se abre y la clave **no** cambia; el reloj anterior debe olvidar el cinturón y emparejarse otra vez. Pasados los 60 s, sostener y soltar no hace nada.
- [ ] **H18. Comandos serie.** Escribir `key` + Enter en el monitor: imprime `blindside passkey: NNNNNN`. `key new`: imprime una clave nueva (reimprimir la etiqueta y volver a emparejar el reloj).
- [ ] **H20. Spike S12.** Con `config::kRequireMitm = true` (por defecto): el reloj pide la clave al emparejar y después la app escribe en `control` (por ejemplo IDENTIFY sin sesión). Si el GW7 no muestra el teclado o la escritura falla, poner `kRequireMitm = false` (plan B: Just Works solo dentro de la ventana, `control` con WRITE_ENC), recompilar, olvidar el cinturón en el reloj, emparejar de nuevo y anotarlo en la spec.
- [ ] **H14. Enlace sano.** Con el reloj conectado: `link[conn=1 sub=1 trusted=1 mtu=255 itvl=24…40]`, `tx[sent]` sube ~50-60 por línea, `fail`, `dropped` y `skipped` cerca de 0 con el reloj al lado.
- [ ] **H15. Enlace bloqueado.** Tapar el pouch con el cuerpo o alejarse hasta casi perder el enlace: `dropped` y `skipped` pueden subir, pero el ESP32 no se reinicia y al volver los datos fluyen solos.

## Luz y resistencia

- [ ] **H13. Disciplina de luz.** A oscuras, con la cámara del teléfono (ve infrarrojo cercano): el LED se apaga a los 2 s del arranque; con la sesión activa (`SESSION_ACTIVE=1`) IDENTIFY no enciende nada; sin sesión, IDENTIFY parpadea 3 veces. Tapar o desoldar el LED de power de la DevKit, el del GY-521, los de la placa de borneras y el del power bank.
- [ ] **H17. Resistencia (S8).** 5 h con el power bank UGREEN y el reloj conectado. Al final, `info` sigue con `reset=POWERON` y el mismo `boot_id`. Si aparece `BROWNOUT`, anotarlo (condensadores de reserva, v1.5).
````

- [ ] **Step 5: Compile the complete firmware, build every suite for the board and run every suite natively**

Run (from `firmware/`): `python -m platformio run -e esp32dev`
Expected: `SUCCESS`, static RAM ≈ 16 % (52 KB of 320 KB, backlog and outbox included; task stacks and queues come from the heap at run time).
Run: `python -m platformio test -e esp32dev --without-uploading --without-testing`
Expected: all thirteen suites `[SKIPPED]`, none `[ERRORED]`.
Run: `python tools/run_native_tests.py`
Expected: `13 suites passed, 0 failed` (`test_belt_rules_link` 11, `test_belt_rules_pairing` 17, `test_bundler` 14, `test_bundler_spec_cases` 5, `test_cut_schedule` 9, `test_frame_gaps` 4, `test_imu_health` 7, `test_imu_math` 13, `test_ld2450_commands` 12, `test_ld2450_frame` 8, `test_radar_watchdog` 8, `test_stream_backlog` 12, `test_vectors_sanity` 1).

- [ ] **Step 6: Commit**

```bash
git -C C:/personal/blindside add firmware/src/diagnostics.h firmware/src/diagnostics.cpp firmware/src/main.cpp firmware/HARDWARE_CHECKLIST.md
git -C C:/personal/blindside commit -m "feat: setup y loop del firmware, diagnóstico serie y checklist de hardware"
```

- [ ] **Step 7: CI (only with authorisation)**

If Santiago authorised pushing in this session: `unset GITHUB_TOKEN`, `git -C C:/personal/blindside push origin main`, and confirm with `gh run watch` that job `firmware` reports every native suite with `0 Failures` and a successful `esp32dev` build. Otherwise record "CI pending: checklist H0" and hand the checklist to Santiago.

---

## Self-review notes (for the executor)

- Spec §12 "Entra" for firmware is fully covered: LD2450 boot with Bluetooth off (Tasks 3, 12), raw frames (2, 7, 12), two IMUs with one FreeRTOS task each (5, 6, 13), TLV bundler that splits packets, with LINK (7, 8, 11, 16), NimBLE with pairing window + per-device key (DisplayOnly) + whitelist + TX at 9 dBm (9, 10, 14, 15), command watchdog (4, 12), and the S10 / S12 plans B (`kConnectWhitelistOnly`, `kRequireMitm`, checklist H10 and H20).
- Spec §9 "Firmware" native tests: parser (Task 2), STATUS table (Task 3 baud table, Task 7 encoding) and the five named `bundler` cases (Task 8, `test_bundler_spec_cases`). Spec §9 bench test "cable de IMU en falla": checklist H7b with its four pass criteria. Spec §9 "Seguridad": H10, H11, H19.
- Not implemented on purpose (spec §12 "Queda para la v1 completa" / D17): `SET_ZONES`, MOSFET power cut, a polished S10 plan B (only the host-side bond check is present).
- Every code block in this plan was built with `pio run -e esp32dev` (NimBLE-Arduino 2.5.1, Arduino core 2.0.17) and every native suite passed with `tools/run_native_tests.py` on 2026-10-01, from a tree extracted from this very document. If a later NimBLE-Arduino 2.x release renames an API used in Task 14, CI's `pio run -e esp32dev` fails; pin `lib_deps` to the last green version only with Santiago's approval (the contract says `@^2`).
