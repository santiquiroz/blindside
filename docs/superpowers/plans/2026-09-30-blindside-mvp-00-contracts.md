# Blindside MVP — Plan 00: Shared Contracts

> **For agentic workers:** this document is not a task list. It fixes the names, types, files and versions that plans 01 (radar-core), 02 (firmware) and 03 (wear-app) must all agree on. Every task in those plans implicitly includes this document. If a plan contradicts this file, this file wins; flag the conflict instead of improvising.

**Goal:** a playable MVP for the 5-hour game on **2026-10-11** (spec §12), built so that the three subsystems can be implemented in parallel.

**Spec:** [`docs/superpowers/specs/2026-09-30-blindside-v1-design.md`](../specs/2026-09-30-blindside-v1-design.md), approved on 2026-09-30.

## Global Constraints

- **Repo:** `C:/personal/blindside`, branch `main`, AGPL-3.0.
- **Commits:**
  - Conventional prefixes (`feat:`, `fix:`, `test:`, `docs:`, `chore:`), written in Spanish.
  - Author `Santiago Quiroz upegui <santiqupgui@gmail.com>`, already set in the repo config.
  - **Never** add a `Co-Authored-By` line.
- **Code style** (owner rules, mandatory):
  - Atomic functions whose name says what they do. Low cyclomatic complexity; extract branches into named functions.
  - **No doc comments.** Only a one-line comment when the *why* is not obvious.
  - Pure functions with explicit dependencies. Immutable data (`data class` + `val`, new copies instead of mutation).
  - Files of 200-400 lines typical, 800 max.
- **Watch toolchain:** these versions build on the owner's machine (taken from RevScope). Do not upgrade.
  - Gradle **8.11.1** (wrapper).
  - AGP **8.10.1**.
  - Kotlin **2.2.21**.
  - JDK **17**.
  - `compileSdk 36`, `targetSdk 36`, `minSdk 34`.
  - Wear Compose **1.4.0** (`androidx.wear.compose:compose-material` + `compose-foundation`).
  - Compose BOM `2025.05.00`, activity-compose `1.10.1`, coroutines `1.9.0`, datastore-preferences `1.1.4`.
  - JUnit **5** (jupiter) for `radar-core`.
- **Firmware toolchain:**
  - PlatformIO, `platform = espressif32`, `framework = arduino`, `board = esp32dev`.
  - `lib_deps = h2zero/NimBLE-Arduino@^2`.
  - Unit tests run in the `native` env with Unity. **CI is authoritative** (`pio test -e native`). There is no installed C/C++ compiler, so locally the same suites run with `python tools/run_native_tests.py [suite …]` from `firmware/` (plan 02 Task 1), which builds them with the Zig toolchain from PyPI (`python -m pip install --user ziglang`, once). A local pass does not replace a green CI run.
- **Shared test vectors:**
  - `python protocol/tools/make_vectors.py` regenerates `protocol/vectors/vectors.json` (Kotlin) and `firmware/test/vectors.h` (C++).
  - **Never edit those two outputs by hand.**
  - Plan 02 Task 1 owns the generator change that writes `bundle_typical` in the fill order of §"BLE packet" (IMU 0, IMU 1, STATUS, then RADAR by `t_ms`; still 242 B) and adds `bundle_with_link` (header, LINK 36/0/500, one RADAR; 47 B). Both encoders (plan 01 `BundleEncoder`, plan 02 bundler) must reproduce those bytes.
- **MVP scope** is exactly spec §12 "Entra". Items listed under "Queda para la v1 completa" must **not** be implemented now.

## Repository layout

```
protocol/
  PROTOCOL.md                  normative copy of spec §4.2 (byte layout, UUIDs, control commands)
  tools/make_vectors.py        vector generator (exists)
  vectors/vectors.json         generated
firmware/
  platformio.ini               envs: esp32dev, native
  include/blindside_config.h   pins, rates, UUIDs, timing constants
  lib/ld2450/                  ld2450_frame.{h,cpp} (pure frame sync) · ld2450_commands.{h,cpp} (pure byte builders)
  lib/bundler/                 bundler.{h,cpp} (pure TLV packer with splitting)
  lib/imu_math/                imu_accumulator.{h,cpp} (pure 200→50 Hz averaging + cumulative gyro sums)
  src/main.cpp                 scheduler loop only
  src/radar_port.{h,cpp}       UART glue, baud detection, boot configuration, watchdog
  src/imu_mpu6050.{h,cpp}      I2C driver (two instances)
  src/ble_link.{h,cpp}         NimBLE GATT server, notify gating, info JSON, control writes
  src/pairing.{h,cpp}          pairing window, per-device passkey in NVS, bond whitelist, BOOT button
  src/status_led.{h,cpp}
  test/vectors.h               generated
  test/test_ld2450_frame/ · test/test_ld2450_commands/ · test/test_bundler/ · test/test_imu_math/
  tools/run_native_tests.py    local runner for the native suites (Zig from PyPI); CI stays authoritative
watch/
  settings.gradle.kts · build.gradle.kts · gradle.properties · gradle/libs.versions.toml · gradlew(.bat) + gradle/wrapper/
  radar-core/                  Kotlin/JVM library (no Android)
  wear-app/                    Android application (Wear OS)
.github/workflows/ci.yml       jobs: radar-core tests · firmware native tests + esp32dev build · wear-app assembleDebug
```

## Identifiers

| Name | Value |
|---|---|
| Kotlin package (core) | `io.github.santiquiroz.blindside.core` |
| Kotlin package (app) | `io.github.santiquiroz.blindside.wear` |
| applicationId | `io.github.santiquiroz.blindside` |
| GATT service UUID | `569f3867-024f-4498-a979-90a762ad3593` |
| `stream` characteristic | `37869398-ecc2-4915-90a1-13d39d708ad5` (notify; CCCD write requires encryption) |
| `info` characteristic | `278b9369-d8ac-4eda-868b-7bfd0dea5dc6` (READ_ENC; UTF-8 JSON, ≤ 512 B, read long) |
| `control` characteristic | `725c9a6e-0c7b-45d2-bef6-48c03be7c092` (WRITE_AUTHEN) |
| BLE device name | `Blindside-XXXX` (last 2 bytes of the MAC in hex) |
| Radar / IMU ids | `0` = A (left box), `1` = B (right box) |

## BLE packet (normative; spec §4.2)

All integers are little-endian.

```
Header (8 B): u8 version=1 | u8 flags | u16 seq | u32 t_ms
  flags: bit0 radar A alive · bit1 radar B alive · bit2 IMU A ok · bit3 IMU B ok · bit4 data dropped
TLV sections: u8 type | u8 len | payload[len]
  0x01 RADAR  (len 29): u8 radar_id | u32 t_ms | 24 B raw LD2450 targets (3 × [u16 x | u16 y | u16 speed | u16 res], sign-magnitude for x/y/speed)
  0x02 IMU    (len 18+12n): u8 imu_id | u32 t_first_ms | u8 n | n × i16[ax, ay, az, gx, gy, gz] | u32 sum_gx | u32 sum_gy | u32 sum_gz
  0x03 STATUS (len 10): 2 × [u8 radar_id | u16 bad_frames | u8 restarts | u8 baud_index]
  0x04 LINK   (len 6): u16 interval (×1.25 ms) | u16 latency | u16 supervision_timeout (×10 ms) — sent after onConnParamsUpdate and once after CCCD subscription
  unknown types: skipped using len. If len > remaining bytes: drop the rest, mark the packet truncated.
```

- Samples within an IMU batch are 20 ms apart.
- Each sample is the average of 4 raw readings taken at 200 Hz.
- Gyro full scale is ±500 °/s (65.5 LSB per °/s). Accel full scale is ±8 g (4096 LSB per g).
- `sum_g*` are running sums of the **raw 200 Hz** gyro readings since boot. They wrap as u32; the watch takes the difference and reads it as int32.
- IMU timing: each 50 Hz sample is the average of one block of exactly 4 raw readings on a fixed 20 ms grid. The sample's `t` is the centre of its block, and `t_last = t_first_ms + (n−1)·20`. A failed or skipped raw read repeats the previous reading, so the sums advance exactly 4 readings per block. The sums cover up to the end of the section's last block.
- `max_payload = min(getPeerMTU() − 3, 244)`. The ESP32 does not notify until the CCCD is active and `getPeerMTU() ≥ 247`.
- **Fill order** in every packet, also when a cut is split: IMU sections first (IMU 0, then IMU 1), then STATUS, then LINK, then the RADAR frames of both radars sorted by `t_ms`. Every RADAR frame in packet k has `t_ms` ≤ those in packet k+1. A section is never split. The shared vectors `bundle_typical` and `bundle_with_link` are written in this order.

### `control` writes

| Bytes | Meaning |
|---|---|
| `01 <id>` | Restart radar `<id>`: enable config, then restart (`A3`) |
| `03` | IDENTIFY: blink the LED 3×. Ignored while a session is active |
| `04 <0/1>` | SESSION_ACTIVE: the watch sets 1 on start and 0 on stop |

### `info` JSON (example)

```json
{"proto":1,"fw":"0.1.0","boot_id":"9f3a12c4","reset":"POWERON","mtu":255,
 "radars":[{"id":0,"fw":"V2.04.23101915","baud":256000},{"id":1,"fw":"V2.04.23101915","baud":256000}],
 "imus":[{"id":0,"who":104,"gyro_lsb_dps":65.5,"accel_lsb_g":4096,"repeats":0},{"id":1,"who":112,"gyro_lsb_dps":65.5,"accel_lsb_g":4096,"repeats":3}],
 "tx_power_dbm":9,"conn":{"interval_ms":45.0,"latency":0,"timeout_ms":5000},"uptime_s":42}
```

- **Size limit: ≤ 512 B** (the ATT maximum attribute length). The value is longer than one ATT read at MTU 247, so the watch reads it long (ATT Read Blob; Android's `readCharacteristic` does it on its own, and NimBLE serves it). Readers must accept it in one piece of up to 512 B.
- `imus[i].repeats` (u32): raw 200 Hz readings repeated since boot because a read failed or its slot was skipped (spec §4.2, "Las repeticiones se cuentan por IMU en `info`"). With every field at its longest the document is 442 B (plan 02's 400 B worst case of the other fields plus `,"repeats":4294967295` twice, 21 B each), under the limit.
- Readers ignore fields they do not know.

## `radar-core` public API (consumed by `wear-app`)

These are exact names. Internal helpers are free. Everything lives in `io.github.santiquiroz.blindside.core` and its sub-packages: `.protocol`, `.geometry`, `.imu`, `.clock`, `.tracking`, `.scene`, `.alerts`, `.replay`, `.sim`, `.config`.

```kotlin
// .config
data class TuningParams(/* every threshold of spec §6, with spec/notes defaults */)
enum class Handedness { RIGHT, LEFT, SWITCHER }
data class RadarMount(val radarId: Int, val xM: Double, val yM: Double, val yawDeg: Double,
                      val flipX: Boolean = false, val speedSign: Int = 1)
fun defaultMounts(handedness: Handedness): List<RadarMount>   // ±0.15 m; yaw −40/+20 (RIGHT), −20/+40 (LEFT), −30/+30 (SWITCHER)
data class PipelineConfig(val tuning: TuningParams = TuningParams(),
                          val mounts: List<RadarMount> = defaultMounts(Handedness.RIGHT))
fun PipelineConfig.toJson(): String                       // the `.bsrec` header's "config" (plan 01 Task 4d)
fun pipelineConfigFromJson(json: String): PipelineConfig  // missing keys keep their defaults

// .scene
enum class Confidence { BOTH, SINGLE, COASTING }
enum class Side { LEFT, CENTER, RIGHT }
data class Blip(val displayId: Int, val bearingDeg: Double, val rangeM: Double,
                val confidence: Confidence, val ageMs: Long, val outOfView: Boolean)
data class SensorStatus(val id: Int, val alive: Boolean)
enum class MotionState { STILL, TURNING, WALKING, PRONE }
enum class Warning { LINK_LOST, RADAR_DOWN, IMU_DOWN, NO_IMU_COMPENSATION, PRONE, CORRUPT_FRAMES, ALERT_OVERFLOW, YAW_UNCALIBRATED }
data class CoverageSector(val fromDeg: Double, val toDeg: Double)
data class RadarScene(val blips: List<Blip>, val coverage: List<CoverageSector>, val linkUp: Boolean,
                      val radars: List<SensorStatus>, val imus: List<SensorStatus>,
                      val motion: MotionState, val warnings: Set<Warning>, val eliminated: Boolean)
// bearingDeg: logical display frame, 0 = up (hip front), positive = clockwise/right, range [−180, 180)

// .alerts
sealed interface PipelineEvent { val tNanos: Long }
data class ContactAlert(val displayId: Int, val side: Side, override val tNanos: Long) : PipelineEvent
data class TrackConfirmed(val displayId: Int, override val tNanos: Long) : PipelineEvent
data class SystemAlert(val kind: Warning, override val tNanos: Long) : PipelineEvent

// root package
class RadarPipeline(config: PipelineConfig) {
    fun onBlePacket(bytes: ByteArray, arrivalNanos: Long): List<PipelineEvent>
    fun onWatchGravity(x: Float, y: Float, z: Float, eventNanos: Long)
    fun onWatchStep(eventNanos: Long)
    fun onWatchGyro(x: Float, y: Float, z: Float, eventNanos: Long)   // rad/s, witness for the belt-bias calibration
    fun onBeltInfo(json: String, nowNanos: Long)                     // every (re-)read of `info`: scales, boot_id
    fun onLinkState(connected: Boolean, nowNanos: Long): List<PipelineEvent>
    fun setEliminated(on: Boolean)
    fun scene(nowNanos: Long): RadarScene
}

// .replay
enum class RecordType(val code: Int) { BLE_PACKET(1), WATCH_GRAVITY(2), WATCH_STEP(3), MANUAL_MARKER(4), TRACK_CONFIRMED(5), VIBRATION_STARTED(6), MODE_CHANGE(7), INFO_REREAD(8), WATCH_GYRO(9), RSSI(10) }
data class BsrecRecord(val type: RecordType, val tMsSinceStart: Long, val payload: ByteArray)
class BsrecWriter(out: java.io.OutputStream, headerJson: String) { fun write(record: BsrecRecord); fun close() }
class BsrecReader(input: java.io.InputStream) { val headerJson: String; fun records(): Sequence<BsrecRecord> }

// .sim
data class SimPacket(val bytes: ByteArray, val arrivalNanos: Long)
fun simulate(scenario: Scenario): List<SimPacket>   // Scenario is defined by plan 01 (DSL)
```

- **Time:** every timestamp the app passes in comes from `SystemClock.elapsedRealtimeNanos()` (or `SensorEvent.timestamp`).
- **Threads:** `radar-core` is single-threaded. The service calls it from one dispatcher (`Dispatchers.Default.limitedParallelism(1)`).

## `.bsrec` file format

```
"BSREC" (5 B) | u8 format_version=1 | u32 header_len | header_json (UTF-8)
records: u8 type | u32 t_ms_since_start | u16 payload_len | payload
```

- Header JSON (spec §6.10):
  - `"info"`: the belt's first `info` read, embedded verbatim, or `null` (demo, or no `info` within 10 s).
  - `"config"`: `PipelineConfig.toJson()` (plan 01 Task 4d), i.e. `TuningParams` and the mounts, the MVP's whole calibration. `pipelineConfigFromHeader` (plan 01 Task 17) reads it back, so replays and τ sweeps use the session's exact config. No reflection-based dump.
  - Any other key (clocks, device, app version, settings) is free metadata.

- Gravity payload: 3 × f32 + i64 event nanos.
- Step payload: i64 event nanos.
- Marker: empty.
- Track confirmed: i32 displayId.
- Vibration started: i32 displayId + u8 side.
- Mode change: UTF-8 mode name. `ELIMINATED`, `STEALTH` or `VIEW` for the screen/eliminated mode, and `LINK_UP` or `LINK_DOWN` on every link transition the app reports. `replayRecording` feeds the last two to `onLinkState(true/false)`, as the app does live (plan 01: payload helpers `BsrecPayloads.linkChange`/`readLinkChange` in Task 4b, `onLinkState` in Task 16a, the replay in Task 17).
- Info re-read: UTF-8 JSON.
- Watch gyro: 3 × f32 (rad/s) + i64 event nanos.
- RSSI: i16 dBm, read with `readRemoteRssi()` at 1 Hz.
- Watch gravity is recorded at ≤ 10 Hz in the MVP.

## Behaviour contracts that cross the core/app boundary

- **Alert limiter** (spec §5.5):
  - The first alert fires immediately.
  - After that, confirmed contacts become *pending*. When ≥ 1 s has passed since the last vibration, one vibration fires for the highest-priority pending contact that is still confirmed. Priority is centre > sides, then nearest. Its side is recomputed at fire time.
  - A pending contact that is no longer confirmed when its turn comes is shown on screen only.
  - Per-sector pause of 5 s (spec §5.5): a new track born < 1.5 m from a contact of the same sector that already alerted and was lost < 5 s ago is that contact reacquired. It inherits "already alerted" and does not vibrate. Any other new track vibrates, subject to the 1 s gap and the cap below. (This replaces an earlier count-based wording; spec §9: "Y nace a la izquierda a más de 1,5 m de X antes de 5 s: Y vibra".)
  - Cap: **10 contact alerts in any rolling 60 s window**. System alerts never count and are never silenced.
  - One alert per display ID.
  - Eliminated mode emits no contact alerts.
  - `radar-core` emits `ContactAlert` only when a vibration must start. The app never re-limits.
- **MVP gate "detenerse y escanear"** (spec §6.6): while the state is WALKING or TURNING, and for 0.5 s after, no tentative track is promoted. After that tail, promotion requires 3 hits in evaluable windows later than the tail. Tracks confirmed earlier keep their ID and don't re-alert. In the MVP this gate replaces ghost rules 1-3.

## Plan ownership and parallelism

| Plan | Directory owned | Can start | Depends on |
|---|---|---|---|
| 01 radar-core | `watch/radar-core/` + watch Gradle root files | immediately | vectors: Tasks 1-3 use the existing file; Task 4 (byte-exact encoder tests, `bundle_with_link`) waits for plan 02 Task 1 |
| 02 firmware | `firmware/` + `protocol/PROTOCOL.md` + the vector generator change | immediately | vectors (Task 1 regenerates them in fill order) |
| 03 wear-app | `watch/wear-app/` | after plan 01 Task 1 (Gradle root) | the radar-core API above. Use the real implementation once available; until then code against the signatures |
| CI | `.github/workflows/ci.yml` | owned by plan 02, Task 1 | — |

**Done for the MVP** means all of the following:
- `./gradlew :radar-core:test` passes.
- `./gradlew :wear-app:assembleDebug` builds.
- CI is green: firmware native tests pass and the `esp32dev` build succeeds (a local `run_native_tests.py` pass is a quicker check, not a substitute).
- The hardware steps of spec §12 (spikes, bench and field tests) are left for Santiago, with a checklist.
