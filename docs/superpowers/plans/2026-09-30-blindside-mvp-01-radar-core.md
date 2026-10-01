# Blindside MVP — Plan 01: radar-core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `watch/radar-core`, the pure Kotlin/JVM brain of Blindside that turns raw BLE packets from the belt into a `RadarScene` and vibration events, plus the watch Gradle root that plan 03 builds on.

**Architecture:** Everything is pure functions over immutable `data class` state. `RadarPipeline` is the only stateful object: it holds one `PipelineState` and replaces it on every input (decode → IMU yaw, bias witness and motion → frame filter → windowed GNN/Kalman tracker with the "detenerse y escanear" gate → alert limiter). Each stage lives in its own sub-package (`.protocol`, `.geometry`, `.clock`, `.imu`, `.tracking`, `.scene`, `.alerts`, `.replay`, `.sim`, `.config`) and is unit-tested alone. A deterministic simulator (`.sim`) produces real BLE packets and watch-gyro events, so the spec §9 scenarios run end to end through `RadarPipeline`; `.replay` feeds `.bsrec` recordings back through the same pipeline and sweeps τ.

**Tech Stack:** Kotlin 2.2.21 (JVM, toolchain 17), Gradle 8.11.1 wrapper, JUnit 5.13.1 (jupiter), `org.json` 20231013 (tests only, to read the shared vectors). No runtime dependency: the two JSON documents the core touches (the belt `info` and the config in the `.bsrec` header) go through a small reader in `.protocol` (Task 4c).

**Spec:** [`docs/superpowers/specs/2026-09-30-blindside-v1-design.md`](../specs/2026-09-30-blindside-v1-design.md). Binding contracts: [`2026-09-30-blindside-mvp-00-contracts.md`](2026-09-30-blindside-mvp-00-contracts.md) (if this plan contradicts it, the contract wins: flag the conflict instead of improvising; the conflict found so far, the sector pause, is resolved in the contracts and recorded under "Decisions").

## Global Constraints

- **Repo:** `C:/personal/blindside`, branch `main`, AGPL-3.0. This plan owns `watch/radar-core/` and the watch Gradle root files (`watch/settings.gradle.kts`, `watch/build.gradle.kts`, `watch/gradle.properties`, `watch/gradle/libs.versions.toml`, `watch/gradlew`, `watch/gradlew.bat`, `watch/gradle/wrapper/`). Never touch `firmware/`, `protocol/` or `watch/wear-app/`.
- **Commits:** conventional prefixes (`feat:`, `fix:`, `test:`, `chore:`, `docs:`) with the message in Spanish. Author is already set in the repo config (`Santiago Quiroz upegui <santiqupgui@gmail.com>`). **Never** add a `Co-Authored-By` line.
- **Code style (owner rules, mandatory):** atomic functions whose name says what they do; low cyclomatic complexity (extract branches into named functions); **no doc comments**, only a one-line comment when the *why* is not obvious; pure functions with explicit dependencies; immutable data (`data class` + `val`, `copy()` instead of mutation); files 200-400 lines typical, 800 max. `RadarPipeline` is the single place allowed to hold a `var`.
- **Toolchain (do not upgrade):** Gradle **8.11.1** wrapper, AGP **8.10.1**, Kotlin **2.2.21**, JDK **17**, JUnit **5**; for plan 03 the catalog also pins Compose BOM `2025.05.00`, activity-compose `1.10.1`, Wear Compose `1.4.0`, coroutines `1.9.0`, datastore-preferences `1.1.4`.
- **Package:** `io.github.santiquiroz.blindside.core` and its sub-packages `.protocol`, `.geometry`, `.imu`, `.clock`, `.tracking`, `.scene`, `.alerts`, `.replay`, `.sim`, `.config`. Contract names are exact (contracts §"radar-core public API").
- **Shared vectors:** tests read `protocol/vectors/vectors.json` through the `blindside.vectors` system property. **Never edit** `protocol/vectors/vectors.json` or `firmware/test/vectors.h` by hand.
- **MVP scope = spec §12 "Entra"** for radar-core: decode with plausibility, geometry with nominal and measured angles, near-field exclusion 0.8 m, GNN + Kalman, 100 ms M-of-N windows, context-dependent coasting, IMU-compensated rotation (τ = 100 ms by default), "detenerse y escanear", `.bsrec` recording and replay. **Do not** implement: full ego-velocity (rule 1 with Σ_ego, and its 0.6 m/s turning floor), ghost rules 2 (shadow) and 3 (cross-radar consistency) — spec §6.6 says the MVP gate replaces rules 1-3, and only rule 4 (stale targets) and the 0.8 m exclusion stay —, Doppler ego-velocity, the self-noise map, tactical posture / "cero", calibration wizards, a persisted gyro bias, or the "aviso cercano".
- **Bearings:** degrees, 0 = straight ahead (hip front, +y), positive = clockwise (to the right), range [−180, 180). Body frame: X right, Y forward, origin at the buckle.
- **Time:** the app passes `SystemClock.elapsedRealtimeNanos()` (or `SensorEvent.timestamp`) nanos. Inside the core, radar/IMU time is the ESP32 `t_ms`; all M-of-N windows are on the ESP32 grid `[100k, 100(k+1))` ms (spec §6.5). An IMU sample stamped `t` covers `[t − 10, t + 10)` ms (contracts: `t` is the centre of its block).
- **Spec values (copied from spec §5.5, §6.1-§6.6 and notes algoritmos §7):** σ_r 0.2 m, σ_θ 3.5°/cos θ with cos θ ≥ cos 70°, R ×1.5 in the overlap, q = 0.4 m²/s³ (CWNA), v0 = 0 with σ_v0 = 1.5 m/s, gate χ²₂ 9.21 + 1.5 m (confirmed) / 1.0 m (tentative), confirm 3 of the last 5 evaluable windows, tentative deleted after 2 evaluable misses, coast 6 s lost still (|v| < 0.3 m/s) / 1.5 s lost moving / 0.3 s + 5 s out of view leaving the cone, coast gate σ ≤ 0.7 m and v·e^(−dt/1 s), ID inheritance < 1 m and < 2 s; **stop and scan:** while walking or turning and for 0.5 s after, no tentative track is promoted, then 3 hits in evaluable windows that start after that tail; turning |ω_v| > 20 °/s filtered 0.2 s (from the watch gyroscope when both box IMUs are down), walking σ(|a|) > 0.08 g in 1 s or a step in the last 1.2 s; **bias:** boot window 2 s with every axis within 3 °/s of the window mean, σ(|a|) ≤ 0.02 g, |mean| ≤ 45 °/s per axis, no step and the watch gyroscope ≤ 3 °/s; rest recalibration on a ≥ 3 s window with the same criteria: |ω − b| < 3 °/s → EMA τ = 20 s, 3 ≤ |ω − b| < 45 °/s → re-seed b with the window mean only if the watch confirms stillness, otherwise mark it "sin verificar"; gravity LPF τ = 1 s skipped beyond 0.15 g, prone > 60°; τ (radar → IMU) = 100 ms by default; clock min-filter windows of 10 s + linear drift, reset on (re)connection, `boot_id` change or `t_ms` going backwards; **limiter:** first alert immediate, **no merge**, every later confirmed contact waits as pending, one vibration per ≥ 1 s slot for the highest-priority pending contact still confirmed (centre > sides, then nearest), its side recomputed at fire time; sector pause 5 s: a new track born < 1.5 m from a contact of the same sector that already alerted and was lost < 5 s ago inherits "ya alertó"; cap 10 contact vibrations in any rolling 60 s, then pending contacts become screen-only; system alerts fire at once, never count, never move the 1 s gap, and a contact due during a system pattern waits for it to end; one alert per display id; centre = ±20°.
- **Threads:** radar-core is single-threaded; the app calls it from one dispatcher.
- **Commands:** every command runs from the repo root `C:/personal/blindside` in Git Bash. Gradle runs in a subshell, `(cd watch && ./gradlew ...)`, so the `git add` paths that follow stay valid. In PowerShell use `Push-Location watch; .\gradlew.bat <same arguments>; Pop-Location`.

## Review Focus

1. **Saturated radar while walking (all 3 LD2450 slots full every frame):** no window is evaluable, so the spec's "2 evaluable misses" never deletes a tentative track and tentatives would pile up for hours, slowing the exact GNN. Expected: a tentative track with no hit for 1 s is dropped (`TrackingParams.tentativeMaxSilenceMs`). Pinned by Task 11 (`LifecycleTest`) and Task 12 (`TrackLifecycleTest`).
2. **Session started while walking or fidgeting (no still 2 s window at boot):** expected: radar contacts are still confirmed and alerted once the player stops (stop-and-scan gate), without yaw compensation, `YAW_UNCALIBRATED` shows until the first still 2 s window, then clears. Pinned by Task 16c (`RobustnessTest`).
3. **The two box IMUs sample on different phases** (plan 02 runs one FreeRTOS task per IMU, so their 20 ms blocks are a few ms apart and the two streams never share a timestamp): expected: the streams are paired on a shared 20 ms grid and averaged, never summed; a 45° turn reads 45 ± 1° and a still object stays unconfirmed while turning. Pinned by Task 8 (`YawTest`), Task 16a (`RadarPipelineTest`) and Task 16c (`RobustnessTest`); the simulator defaults to phases [0, 7] ms (Task 15).
4. **A ~2 s BLE dropout mid-game with the same ESP32 boot:** expected: lost packets are counted, the turn is rebuilt from the cumulative gyro sums, and a rival walking straight through the gap keeps one display id and vibrates once. Pinned by Task 16a (`RadarPipelineTest`) and Task 16c (`RobustnessTest`).
5. **The 5-hour Manizales session:** `seq` wraps 65535 → 0 about every 1.8 h, and per-session collections must stay bounded. Expected: no false losses or resets at the wrap; the display-id graveyard, the limiter history and lost-contact memory, the watch-gyro witness and the corruption marks are pruned. Pinned by Task 16c (`RobustnessTest`), Task 11 (`DisplayIdsTest`), Task 13 (`AlertLimiterTest`) and Task 7 (`WatchWitnessTest`).

---

## File map

All Kotlin sources live under `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/` (written `MAIN/` below) and tests under `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/` (`TEST/`). Every file is created by exactly one task; `MAIN/RadarPipeline.kt` is then replaced whole by Tasks 16b and 16c.

| File | Responsibility | Task |
|---|---|---|
| `watch/settings.gradle.kts`, `watch/build.gradle.kts`, `watch/gradle.properties`, `watch/gradle/libs.versions.toml`, wrapper | Watch Gradle root shared with plan 03 | 1 |
| `watch/radar-core/build.gradle.kts`, `TEST/SharedVectors.kt`, `TEST/BuildSmokeTest.kt` | Kotlin/JVM module, JUnit 5, access to `vectors.json` | 1 |
| `MAIN/config/TuningParams.kt`, `MAIN/config/Mounts.kt` | Every threshold (spec §6.9); mounts per handedness (spec §2.3, §6.8) | 2 |
| `MAIN/scene/RadarScene.kt`, `MAIN/alerts/PipelineEvent.kt` | Contract output types | 2 |
| `MAIN/protocol/LittleEndian.kt`, `MAIN/protocol/Ld2450Codec.kt` | LE byte helpers; LD2450 sign-magnitude targets | 3 |
| `MAIN/protocol/Bundle.kt`, `BundleDecoder.kt`, `BundleEncoder.kt`, `SeqTracker.kt` | BLE packet model with RADAR/IMU/STATUS/LINK, TLV decode, encode in the contract fill order, seq gaps (spec §4.2) | 4 |
| `MAIN/replay/Bsrec.kt`, `MAIN/replay/BsrecPayloads.kt` | `.bsrec` writer/reader, the 10 record types and their payloads (spec §6.10) | 4b |
| `MAIN/protocol/MiniJson.kt`, `MAIN/protocol/BeltInfo.kt` | Dependency-free JSON reader/writer; `boot_id` and IMU scales from `info` | 4c |
| `MAIN/config/JsonFields.kt`, `MAIN/config/ConfigJson.kt` | `PipelineConfig` ↔ JSON for the `.bsrec` header | 4d |
| `MAIN/geometry/Point2.kt`, `BodyGeometry.kt`, `FrameFilter.kt` | Frames and cones; plausibility, stale, near field (spec §6.1-§6.2) | 5 |
| `MAIN/clock/ClockMapper.kt` | ESP32 `t_ms` ↔ elapsedRealtime nanos (spec §6.3) | 6 |
| `MAIN/imu/Vec3.kt`, `WatchWitness.kt`, `BiasWindows.kt`, `ImuChannel.kt` | Watch witness, boot bias, rest recalibration and re-seed, gravity, yaw rate, prone, scales (spec §6.3) | 7 |
| `MAIN/imu/YawIncrements.kt`, `YawTracker.kt` | Yaw integration, gap rebuild, two-phase IMU merge, extrapolation (spec §3.1, §6.3) | 8 |
| `MAIN/imu/MotionDetector.kt` | Turning (box or watch) / walking / prone, belt and watch steps (spec §6.4, §6.6) | 9 |
| `MAIN/tracking/Matrix.kt`, `CvKalman.kt`, `Gnn.kt` | CV Kalman CWNA, polar R, exact GNN (spec §6.1-§6.2) | 10 |
| `MAIN/tracking/Track.kt`, `ConfirmationPolicy.kt`, `Lifecycle.kt`, `DisplayIds.kt` | Track model, stop-and-scan confirmation gate, coasting, ID inheritance (spec §6.5-§6.6) | 11 |
| `MAIN/tracking/Association.kt`, `Tracker.kt`, `TEST/tracking/TrackerFixtures.kt` | 100 ms window grid, per-frame association, NIS per update | 12 |
| `MAIN/alerts/AlertLimiter.kt` | Vibration limiter, reacquired contacts, system-pattern hold, sides (spec §5.5) | 13 |
| `MAIN/scene/SceneBuilder.kt` | Logical-frame blips, confidence, coverage (spec §6.7) | 14 |
| `MAIN/sim/Scenario.kt`, `PlayerPose.kt`, `SensorModels.kt`, `Simulator.kt`, `Scenarios.kt` | Scenario DSL, packet and watch-gyro simulator with radar latency and IMU phases (spec §6.11, §9) | 15 |
| `MAIN/PipelineState.kt`, `SensorHealth.kt`, `PacketIngest.kt`, `WatchInputs.kt`, `RadarPipeline.kt`, `TEST/ScenarioRun.kt` | Packet ingest, watch inputs (`onWatchGyro`, `onBeltInfo`), the contract facade | 16a |
| `MAIN/Warnings.kt`, `MAIN/SystemAlerts.kt` | Screen warnings and system alerts | 16b |
| `MAIN/AlertStage.kt` | Confirmed tracks → limiter → `ContactAlert`, eliminated mode | 16c |
| `MAIN/replay/Replay.kt`, `MAIN/replay/TauSweep.kt` | Deterministic replay of every record type, config from the header, τ sweep by NIS (spec §6.3, §6.10) | 17 |
| `TEST/ScenarioTest.kt` | Spec §9 scenarios end to end, including the four MVP stop-and-scan cases | 18 |

## Decisions where the spec is silent or the MVP reduces it

Read these before Task 7. Each one is already encoded in the code and tests below.

1. **Stop and scan is a confirmation gate (spec §6.6, contracts).** `MotionContext(moving, gateOpenFromMs)`: `moving` means walking or turning (box IMUs, or the watch gyroscope when both box IMUs are down); `PipelineState.lastMovingMs` is the ESP32 time of the last packet that found the player moving, and `gateOpenFromMs = lastMovingMs + 500 ms` (`TrackingParams.stopScanTailMs`). No tentative track is promoted while moving or before the gate opens; after that, a track needs 3 hits among its last 5 evaluable windows that **started** at or after `gateOpenFromMs` (`Track.outcomes` keeps `WindowMark(startMs, hit)`). Tracks confirmed earlier keep their id and status. The alert stage needs no gate of its own: only confirmed tracks are candidates, so walking toward a wall confirms nothing and vibrates nothing (spec §9).
2. **Window closing.** A window closes when the first frame of a later window is processed; after each packet the tracker also advances to `header.t_ms − 100 ms`, so tracks expire even if frames stop. Confirmation is checked eagerly on each hit (a hit is final), which removes up to one packet of latency.
3. **Tentative silence timeout (new parameter `tentativeMaxSilenceMs = 1000`).** See Review Focus 1.
4. **Bias witness and verification (spec §6.3).** Watch gyroscope events (`onWatchGyro`, rad/s) are mapped to ESP32 time and kept for 5 s. A box window (2 s at boot, the last 3 s at rest) is judged MOVING if a step fell inside it or any watch sample inside it exceeds 3 °/s; VERIFIED if at least 5 watch samples fall inside it; UNVERIFIED otherwise (watch without gyroscope, or its events have not arrived yet). A boot bias accepted while UNVERIFIED is "sin verificar". At rest, a still window 3-45 °/s away from `b` re-seeds `b` only when VERIFIED; otherwise `b` is kept and flagged "sin verificar". `Warning.YAW_UNCALIBRATED` covers "no bias yet" and "unverified bias", since the UI asks the player to stand still in both cases. The MVP persists no bias between sessions (spec §12 does not list it), so the spec §9 "sesgo guardado" cases reduce to "no stored bias".
5. **A DEFECTIVE IMU keeps trying.** A still window whose mean exceeds 45 °/s marks the IMU DEFECTIVE (shown as down, not used), but later windows are still evaluated, so a session that starts during a fast steady turn recovers.
6. **Two IMU phases (Review Focus 3).** Each 50 Hz increment covers `[t − 10, t + 10)`; a rebuilt gap is split into 20 ms pieces; increments of both IMUs are paired on a shared 20 ms grid by their end time rounded up, and averaged. A grid cell that already reached the yaw history is never counted again.
7. **Reacquired contacts (spec §5.5 sector pause).** The limiter remembers, for 5 s, every contact that already alerted and stops being a confirmed candidate, with its position in the yaw-compensated tracking frame. A new confirmed track born < 1.5 m from one of them, in the same sector judged with the current yaw, inherits "ya alertó" and does not vibrate. The remembered position is the last one seen while the player was still, because positions measured during a turn carry the τ error. Any other new track vibrates.
8. **System alerts and the limiter.** `SystemAlert(LINK_LOST)` on `onLinkState(false)` after being connected; `SystemAlert(RADAR_DOWN)` once on an alive → down flag edge. Each system alert holds contact vibrations for `AlertParams.systemPatternMs = 1200` (plan 03's `SYSTEM_BUZZ_MS`) without moving the 1 s gap. Losing an IMU is a screen warning only (spec §8).
9. **Eliminated mode.** Contacts confirmed while eliminated are marked handled and never vibrate later; the scene has no blips while eliminated. Tracking and recording keep running (spec §10.1).
10. **ESP32 reboot (`t_ms` goes backwards, or `info` brings a new `boot_id`).** Clock mapping, seq, IMU sums, yaw history, motion, the watch witness, stale memory and tracks restart (`PipelineState.withRestartedTimeReferences`); display-id numbering and the limiter's handled set continue, so nobody re-vibrates.
11. **Scales from `info`.** `onBeltInfo` reads `imus[i].gyro_lsb_dps` and `accel_lsb_g` and uses them in the readings and the gap rebuild; a changed scale restarts that IMU's calibration. Missing fields keep the contract's ±500 °/s (65.5 LSB/(°/s)) and ±8 g (4096 LSB/g).
12. **τ sweep (spec §6.3, §12 calendar 5-7 oct).** The tracker returns the NIS of every update of a confirmed track; `PipelineCounters` sums the ones made while turning. `sweepTau` replays a recording for τ = 0-200 ms in 10 ms steps and returns the mean turning NIS per τ; `bestTau` picks the minimum.
13. **Not in the MVP:** loading `TuningParams` from a separate file for sweeps (the header round trip covers replays), persisted gyro bias, belt-flexion logging (recoverable offline from the raw packets in `.bsrec`), "aviso cercano", ghost rules 1-3.

**Conflicts with the contracts (resolved on 2026-10-01):**
- **Sector pause.** The contracts used to say "per-sector pause of 5 s, except when the contact count in that sector increases". They now carry the spec §5.5 rule that this plan implements (decision 7): a new track born < 1.5 m from a same-sector contact that already alerted and was lost < 5 s ago inherits "ya alertó"; any other new track vibrates (spec §9: "Y nace a la izquierda a más de 1,5 m de X antes de 5 s: Y vibra").

## Notes for plans 02 and 03

- **IMU timing:** radar-core treats sample `i` of a batch as covering `[t − 10, t + 10)` with `t = t_first_ms + 20·i` (contracts). `sum_g*` must include every raw reading up to the end of the batch's last block. The watch rebuilds a gap as `(Σ_now − Σ_prev) − 4·Σ(batch samples)`. The two IMUs may run on different phases (decision 6).
- **Radar frame order:** frames of later packets must not be older than frames already sent (the bundler's time cut guarantees it). Older frames are counted (`PipelineCounters.outOfOrderFrames`) and dropped.
- **Fill order and shared vectors:** `BundleEncoder` writes the contract fill order (IMU 0, IMU 1, STATUS, LINK, then RADAR by `t_ms`), the same order as plan 02's bundler. Plan 02 Task 1 regenerates `bundle_typical` in that order and adds `bundle_with_link`; Task 4 here reads both and starts after that commit (Task 4 Step 1 checks it).
- **Plan 03 calls:** `onLinkState(true, now)` on every (re)connection (it resets the clock mapping, spec §6.3) and `onLinkState(false, now)` on every disconnection; `onBeltInfo(json, now)` after every read of `info` (it arrives before the CCCD write, so scales and `boot_id` precede the first packet); `onWatchGyro` for every `TYPE_GYROSCOPE` event (rad/s; 10 Hz is enough, the witness wants ≥ 5 samples per 2 s window); `onWatchStep` per `TYPE_STEP_DETECTOR` event; `scene(now)` whenever it draws; `onWatchGravity` is accepted but unused in the MVP (posture is out of scope).
- **System pattern length:** `AlertParams.systemPatternMs = 1200` mirrors plan 03's `SYSTEM_BUZZ_MS`; change both together.
- **`.bsrec` header:** put `"config": <PipelineConfig.toJson()>` (Task 4d) in the header JSON so `pipelineConfigFromHeader` rebuilds the session's exact config in replays and τ sweeps (Task 17). Plan 03 Task 4 writes exactly that (no reflection-based dump of `TuningParams`). Other header keys are free.
- **`.bsrec` payload conventions:** mode change payload = `SessionMode.name` (`ELIMINATED`, `STEALTH`, `VIEW`) or `LINK_UP`/`LINK_DOWN` (`BsrecPayloads.linkChange`); vibration-started side byte = `Side.ordinal` (LEFT 0, CENTER 1, RIGHT 2); the watch gyroscope uses the gravity layout and reads back as `GravitySample`; all integers little-endian.
- **Extra public API beyond the contract (safe to use):** `RadarPipeline.counters(): PipelineCounters` (with `lastLink` and `meanTurningNis`); `nominalYaws`, `mountsFromMeasuredYaws`, `RADAR_A`, `RADAR_B`, `PipelineConfig.toJson()`, `pipelineConfigFromJson` (`.config`); `LinkParams`, `parseBeltInfo` (`.protocol`); `Scenarios` (including the four MVP stop-and-scan scenarios), `simulateWatchGyro`, `simEspMs`/`simArrivalNanos` (`.sim`; the minimal "demo" source is `simulate(Scenarios.crossing())`); `SessionMode`, `GravitySample`, `BsrecPayloads`, `LINK_UP_MODE`/`LINK_DOWN_MODE`, `replayRecording`, `pipelineConfigFromHeader`, `sweepTau`, `bestTau` (`.replay`).
- **Task numbers that plan 03 cites:** the `.replay` format types (`RecordType` with all 10 codes, `BsrecWriter`, `BsrecReader`, `BsrecPayloads` with `watchGyro`, `rssi`, `info`, `linkChange`, `SessionMode`, `GravitySample`, `LINK_UP_MODE`/`LINK_DOWN_MODE`) arrive in **Task 4b**, right after Task 4, and `PipelineConfig.toJson()`/`pipelineConfigFromJson` (the `.bsrec` header's `"config"`) arrive in **Task 4d**, so plan 03 Task 4 can start right after Task 4d. The former "Task 19, contract additions" is folded in: record types and payloads → Task 4b; `onWatchGyro` and `onBeltInfo` → Task 16a; replay of records 8-9 and of link changes → Task 17. The former Task 16 is now 16a-16c; plan 03 needs 16a for the full contract signature and 16c for vibrations.
- **Version catalog:** plan 03 may **append** library entries to `watch/gradle/libs.versions.toml`; it must not change existing versions. `settings.gradle.kts` includes `:wear-app` automatically once `watch/wear-app/build.gradle.kts` exists.
- **CI (plan 02 owns `ci.yml`):** the radar-core job needs JDK 17 and runs `(cd watch && ./gradlew :radar-core:test)` from a full checkout (tests read `../protocol/vectors/vectors.json`).

## Spec coverage map

| Spec | Where |
|---|---|
| §4.2 packet, TLV (RADAR, IMU, STATUS, LINK), truncation, unknown types, seq wrap | Tasks 3-4, 16a |
| §6.1 decode, plausibility, empty slots, stale, near field 0.8 m, radar→body, ordering, out-of-order drop | Tasks 3, 5, 12, 16a |
| §6.2 CV Kalman CWNA, polar R, overlap ×1.5, v0 | Task 10 |
| §6.3 two box IMUs, boot bias with watch witness, rest recalibration and re-seed, scales from `info`, gravity, prone, clock mapping and its resets (`boot_id`), gap rebuild, τ = 100 ms and its NIS sweep | Tasks 4c, 6-8, 16a, 17 |
| §3.1 yaw extrapolation (≤ 150 ms) and 50 ms blend | Tasks 8, 14, 16a |
| §6.4 turning, walking, belt and watch steps | Tasks 9, 16a |
| §6.5 windows, hit/miss/not evaluable, lifecycle, coasting by context, ID inheritance | Tasks 11-12 |
| §6.6 "detenerse y escanear" (MVP gate that replaces rules 1-3), rule 4 (stale), watch gyroscope for "girando" when both IMUs fail | Tasks 5, 9, 11, 12, 16a, 18 |
| §6.7 scene, sides in the logical frame | Tasks 13-14, 16c |
| §5.5 limiter (no merge, 1 s slots, priority, reacquire within 1.5 m / 5 s, 10 per 60 s, system alerts), one alert per id, eliminated mode | Tasks 13, 16b, 16c |
| §8 link lost, radar down, corrupt frames, seq gaps, IMU loss, both IMUs lost, prone | Tasks 16a, 16b, 16c |
| §6.9 `TuningParams` (and its JSON form) | Tasks 2, 4d |
| §6.10 `.bsrec` (10 record types, header with config) + deterministic replay | Tasks 4b, 4d, 17 |
| §6.11 simulator and demo scenarios (radar latency, IMU phases, watch gyroscope) | Task 15 |
| §9 unit tests (decoder, TLV, transforms, Kalman, GNN, stop and scan, 8 bias cases, limiter cases, clock, gap) and scenarios (classic set and the four MVP cases with τ off by 100 ms) | Tasks 3-18 |

## Tasks

### Task 1: Watch Gradle root and radar-core module

Creates the Gradle root that plan 03 needs to start (contracts §"Plan ownership"), the empty `radar-core` Kotlin/JVM module and a smoke test that proves the shared vectors are reachable.

**Files:**
- Create: `watch/settings.gradle.kts`
- Create: `watch/build.gradle.kts`
- Create: `watch/gradle.properties`
- Create: `watch/gradle/libs.versions.toml`
- Create: `watch/radar-core/build.gradle.kts`
- Create (generated): `watch/gradlew`, `watch/gradlew.bat`, `watch/gradle/wrapper/gradle-wrapper.jar`, `watch/gradle/wrapper/gradle-wrapper.properties`
- Modify: `.gitattributes` (append three lines)
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/SharedVectors.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/BuildSmokeTest.kt`

**Interfaces:**
- Consumes: `protocol/vectors/vectors.json` (exists).
- Produces: Gradle project `:radar-core` (plugin `org.jetbrains.kotlin.jvm`, JVM toolchain 17, JUnit Platform). Test helper `object SharedVectors { val json: JSONObject; fun hex(key: String): ByteArray; fun hexToBytes(hex: String): ByteArray }` in package `io.github.santiquiroz.blindside.core` (test source set). Catalog aliases for plan 03: plugins `libs.plugins.android.application`, `libs.plugins.kotlin.android`, `libs.plugins.kotlin.compose`; libraries `libs.compose.bom`, `libs.compose.ui`, `libs.compose.ui.tooling.preview`, `libs.activity.compose`, `libs.wear.compose.material`, `libs.wear.compose.foundation`, `libs.coroutines.android`, `libs.coroutines.test`, `libs.datastore.preferences`, `libs.junit5.bom`, `libs.junit5.jupiter`, `libs.junit5.launcher`, `libs.org.json`.

- [ ] **Step 1: Write the smoke test and its vectors helper**

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/SharedVectors.kt`:

```kotlin
package io.github.santiquiroz.blindside.core

import org.json.JSONObject
import java.io.File

object SharedVectors {
    val json: JSONObject by lazy { JSONObject(File(vectorsPath()).readText()) }

    fun hex(key: String): ByteArray = hexToBytes(json.getJSONObject(key).getString("hex"))

    fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun vectorsPath(): String =
        System.getProperty("blindside.vectors") ?: error("blindside.vectors system property is not set")
}
```

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/BuildSmokeTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BuildSmokeTest {
    @Test
    fun `shared vectors are reachable from radar-core tests`() {
        val official = SharedVectors.hex("ld2450_official_frame")

        assertEquals(30, official.size)
    }
}
```

- [ ] **Step 2: Write the Gradle root and the module build**

Create `watch/settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "blindside-watch"

include(":radar-core")
// Plan 03 creates wear-app in parallel; including a missing directory breaks the build.
if (file("wear-app/build.gradle.kts").exists()) include(":wear-app")
```

Create `watch/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
```

Create `watch/gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

Create `watch/gradle/libs.versions.toml`:

```toml
[versions]
agp = "8.10.1"
kotlin = "2.2.21"
composeBom = "2025.05.00"
activityCompose = "1.10.1"
wearCompose = "1.4.0"
coroutines = "1.9.0"
datastore = "1.1.4"
junit5 = "5.13.1"
orgJson = "20231013"

[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
wear-compose-material = { group = "androidx.wear.compose", name = "compose-material", version.ref = "wearCompose" }
wear-compose-foundation = { group = "androidx.wear.compose", name = "compose-foundation", version.ref = "wearCompose" }
coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
junit5-bom = { group = "org.junit", name = "junit-bom", version.ref = "junit5" }
junit5-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter" }
junit5-launcher = { group = "org.junit.platform", name = "junit-platform-launcher" }
org-json = { group = "org.json", name = "json", version.ref = "orgJson" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
```

Create `watch/radar-core/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.launcher)
    testImplementation(libs.org.json)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("blindside.vectors", rootProject.file("../protocol/vectors/vectors.json").absolutePath)
}
```

- [ ] **Step 3: Generate the Gradle 8.11.1 wrapper with the cached distribution**

Run (Git Bash, from the repo root):

```bash
(cd watch && "C:/Users/santi/.gradle/wrapper/dists/gradle-8.11.1-bin/bpt9gzteqjrbo1mjrsomdt32c/gradle-8.11.1/bin/gradle" wrapper --gradle-version 8.11.1 --distribution-type bin)
```

Expected: `BUILD SUCCESSFUL`, and `watch/gradlew`, `watch/gradlew.bat`, `watch/gradle/wrapper/gradle-wrapper.jar` and `watch/gradle/wrapper/gradle-wrapper.properties` now exist. `gradle-wrapper.properties` must contain `distributionUrl=https\://services.gradle.org/distributions/gradle-8.11.1-bin.zip`. The first run resolves AGP 8.10.1 and the Kotlin 2.2.21 plugins (declared `apply false` in the root build) and can take about a minute.

- [ ] **Step 4: Run the smoke test**

Run: `(cd watch && ./gradlew :radar-core:test)`

Expected: `BUILD SUCCESSFUL`; `watch/radar-core/build/test-results/test/TEST-io.github.santiquiroz.blindside.core.BuildSmokeTest.xml` reports `tests="1" failures="0"`. If it fails with `blindside.vectors system property is not set`, the `tasks.test` block of `radar-core/build.gradle.kts` was not copied.

- [ ] **Step 5: Keep the wrapper script runnable on CI (Linux) and Windows**

Append to `.gitattributes`:

```
watch/gradlew text eol=lf
*.bat text eol=crlf
*.jar binary
```

- [ ] **Step 6: Commit (with the executable bit on `gradlew`)**

```bash
git add .gitattributes watch/settings.gradle.kts watch/build.gradle.kts watch/gradle.properties watch/gradle/libs.versions.toml watch/gradle/wrapper/gradle-wrapper.jar watch/gradle/wrapper/gradle-wrapper.properties watch/gradlew watch/gradlew.bat watch/radar-core/build.gradle.kts watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/SharedVectors.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/BuildSmokeTest.kt
git update-index --chmod=+x watch/gradlew
git commit -m "chore: raíz Gradle del reloj y módulo radar-core con prueba de humo"
```

### Task 2: Public API types: configuration, scene and events

Puts every contract type in place first, so plan 03 can compile against the real classes, and fixes every tuning default in one immutable `TuningParams` (spec §6.9; values from notes algoritmos §7 and ideas_creativas_ux §3, except τ = 100 ms, spec §6.3 and §6.9). Mounts follow spec §2.3 (±0.15 m, yaw −40/+20 right-handed, −20/+40 left-handed, −30/+30 switcher); `mountsFromMeasuredYaws` implements the spec §6.8 "sin rumbo absoluto" rule for the measured angles of the MVP settings. `TuningParams` has no field for ghost rules 1-3 (out of the MVP, Global Constraints).

**Files:**
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/config/TuningParams.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/config/Mounts.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/scene/RadarScene.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/alerts/PipelineEvent.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/config/ConfigTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces (`io.github.santiquiroz.blindside.core.config`): `data class TuningParams(decode: DecodeParams, imu: ImuParams, motion: MotionParams, tracking: TrackingParams, alerts: AlertParams, clock: ClockParams, status: StatusParams)` with every field defaulted (see the code for each name; later tasks use `ImuParams.radarImuDelayMs`, `stillMaxSpreadDps`, `stillMaxAccelStdG`, `reseedMinOffsetDps`, `witnessMaxDps`, `witnessMinSamples`, `witnessHistoryMs`, `MotionParams.watchMaxGapMs`, `TrackingParams.stopScanTailMs`, `AlertParams.sectorPauseMs`, `reacquireDistanceM`, `systemPatternMs`); `enum class Handedness { RIGHT, LEFT, SWITCHER }`; `data class RadarMount(radarId: Int, xM: Double, yM: Double, yawDeg: Double, flipX: Boolean = false, speedSign: Int = 1)`; `data class PipelineConfig(tuning: TuningParams = TuningParams(), mounts: List<RadarMount> = defaultMounts(Handedness.RIGHT))`; `fun defaultMounts(handedness: Handedness): List<RadarMount>`; `fun nominalYaws(handedness: Handedness): Pair<Double, Double>`; `fun mountsFromMeasuredYaws(handedness: Handedness, measuredYawADeg: Double, measuredYawBDeg: Double): List<RadarMount>`; `const val RADAR_A = 0`, `const val RADAR_B = 1`.
- Produces (`io.github.santiquiroz.blindside.core.scene`): `Confidence`, `Side`, `Blip`, `SensorStatus`, `MotionState`, `Warning`, `CoverageSector`, `RadarScene`, exactly as in the contract.
- Produces (`io.github.santiquiroz.blindside.core.alerts`): `sealed interface PipelineEvent { val tNanos: Long }`, `ContactAlert(displayId, side, tNanos)`, `TrackConfirmed(displayId, tNanos)`, `SystemAlert(kind: Warning, tNanos)`.

- [ ] **Step 1: Write the failing tests**

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/config/ConfigTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConfigTest {
    @Test
    fun `tuning defaults carry the spec values`() {
        val tuning = TuningParams()

        assertEquals(0.8, tuning.decode.nearFieldM)
        assertEquals(0.4, tuning.tracking.processNoise)
        assertEquals(9.21, tuning.tracking.gateChi2)
        assertEquals(3, tuning.tracking.confirmHits)
        assertEquals(5, tuning.tracking.confirmWindows)
        assertEquals(500L, tuning.tracking.stopScanTailMs)
        assertEquals(6000L, tuning.tracking.coastStillMs)
        assertEquals(1500L, tuning.tracking.coastMovingMs)
        assertEquals(1000L, tuning.alerts.minGapMs)
        assertEquals(10, tuning.alerts.maxPerMinute)
        assertEquals(20.0, tuning.motion.turningRateDps)
    }

    @Test
    fun `the radar to imu delay defaults to 100 ms, not the 0 of the notes`() {
        assertEquals(100L, TuningParams().imu.radarImuDelayMs)
    }

    @Test
    fun `right handed mounts open minus 40 and plus 20`() {
        val mounts = defaultMounts(Handedness.RIGHT)

        assertEquals(listOf(-40.0, 20.0), mounts.map { it.yawDeg })
        assertEquals(listOf(-0.15, 0.15), mounts.map { it.xM })
        assertEquals(listOf(RADAR_A, RADAR_B), mounts.map { it.radarId })
    }

    @Test
    fun `left handed and switcher mounts follow the profile table`() {
        assertEquals(listOf(-20.0, 40.0), defaultMounts(Handedness.LEFT).map { it.yawDeg })
        assertEquals(listOf(-30.0, 30.0), defaultMounts(Handedness.SWITCHER).map { it.yawDeg })
    }

    @Test
    fun `measured yaws keep the nominal mean heading and the measured spread`() {
        val mounts = mountsFromMeasuredYaws(Handedness.RIGHT, measuredYawADeg = -50.0, measuredYawBDeg = 20.0)

        assertEquals(listOf(-45.0, 25.0), mounts.map { it.yawDeg })
    }

    @Test
    fun `pipeline config defaults to the right handed profile`() {
        assertEquals(defaultMounts(Handedness.RIGHT), PipelineConfig().mounts)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.config.*")`

Expected: FAIL. `:radar-core:compileTestKotlin` reports `Unresolved reference` for `TuningParams`, `defaultMounts`, `Handedness`.

- [ ] **Step 3: Write the implementation**

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/config/TuningParams.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.config

data class DecodeParams(
    val maxAbsAngleDeg: Double = 65.0,
    val maxSpeedMps: Double = 10.0,
    val validResolutionsMm: Set<Int> = setOf(320, 360),
    val staleRepeatFrames: Int = 3,
    val nearFieldM: Double = 0.8,
    val coneHalfAngleDeg: Double = 60.0,
    val maxRangeM: Double = 6.0,
)

data class ImuParams(
    val bootWindowSamples: Int = 100,
    val restWindowSamples: Int = 150,
    val stillMaxSpreadDps: Double = 3.0,
    val stillMaxAccelStdG: Double = 0.02,
    val defectiveMeanDps: Double = 45.0,
    val reseedMinOffsetDps: Double = 3.0,
    val restBiasTauS: Double = 20.0,
    val witnessMaxDps: Double = 3.0,
    val witnessMinSamples: Int = 5,
    val witnessHistoryMs: Long = 5000,
    val gravityTauS: Double = 1.0,
    val gravityRejectG: Double = 0.15,
    val proneAngleDeg: Double = 60.0,
    val gyroScale: Double = 1.0,
    val radarImuDelayMs: Long = 100,
    val yawHistoryMs: Long = 3000,
    val yawExtrapolationMaxMs: Long = 150,
    val yawRateSamplesForExtrapolation: Int = 3,
    val yawBlendTauMs: Double = 50.0,
)

data class MotionParams(
    val turningRateDps: Double = 20.0,
    val turningTauS: Double = 0.2,
    val walkingAccelStdG: Double = 0.08,
    val walkingWindowMs: Long = 1000,
    val stepHoldMs: Long = 1200,
    val stepRiseG: Double = 0.12,
    val stepResetG: Double = 0.03,
    val stepMinIntervalMs: Long = 250,
    val watchMaxGapMs: Long = 500,
)

data class TrackingParams(
    val windowMs: Long = 100,
    val processNoise: Double = 0.4,
    val sigmaRangeM: Double = 0.2,
    val sigmaAngleDeg: Double = 3.5,
    val maxAngleForNoiseDeg: Double = 70.0,
    val overlapNoiseFactor: Double = 1.5,
    val sigmaInitialSpeedMps: Double = 1.5,
    val gateChi2: Double = 9.21,
    val gateConfirmedM: Double = 1.5,
    val gateTentativeM: Double = 1.0,
    val confirmHits: Int = 3,
    val confirmWindows: Int = 5,
    val stopScanTailMs: Long = 500,
    val tentativeMaxMisses: Int = 2,
    val tentativeMaxSilenceMs: Long = 1000,
    val stillSpeedMps: Double = 0.3,
    val coastStillMs: Long = 6000,
    val coastMovingMs: Long = 1500,
    val coastExitMs: Long = 300,
    val outOfViewMs: Long = 5000,
    val coastGateSigmaM: Double = 0.7,
    val coastSpeedTauS: Double = 1.0,
    val inheritDistanceM: Double = 1.0,
    val inheritMaxAgeMs: Long = 2000,
)

data class AlertParams(
    val centerHalfWidthDeg: Double = 20.0,
    val minGapMs: Long = 1000,
    val sectorPauseMs: Long = 5000,
    val reacquireDistanceM: Double = 1.5,
    val maxPerMinute: Int = 10,
    val systemPatternMs: Long = 1200,
)

data class ClockParams(
    val windowMs: Long = 10_000,
    val maxWindows: Int = 6,
    val maxDriftPpm: Double = 200.0,
)

data class StatusParams(
    val linkStaleMs: Long = 1000,
    val corruptWindowMs: Long = 10_000,
    val corruptEventsThreshold: Int = 20,
)

data class TuningParams(
    val decode: DecodeParams = DecodeParams(),
    val imu: ImuParams = ImuParams(),
    val motion: MotionParams = MotionParams(),
    val tracking: TrackingParams = TrackingParams(),
    val alerts: AlertParams = AlertParams(),
    val clock: ClockParams = ClockParams(),
    val status: StatusParams = StatusParams(),
)
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/config/Mounts.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.config

enum class Handedness { RIGHT, LEFT, SWITCHER }

data class RadarMount(
    val radarId: Int,
    val xM: Double,
    val yM: Double,
    val yawDeg: Double,
    val flipX: Boolean = false,
    val speedSign: Int = 1,
)

data class PipelineConfig(
    val tuning: TuningParams = TuningParams(),
    val mounts: List<RadarMount> = defaultMounts(Handedness.RIGHT),
)

const val RADAR_A = 0
const val RADAR_B = 1
private const val MOUNT_OFFSET_M = 0.15

fun nominalYaws(handedness: Handedness): Pair<Double, Double> = when (handedness) {
    Handedness.RIGHT -> -40.0 to 20.0
    Handedness.LEFT -> -20.0 to 40.0
    Handedness.SWITCHER -> -30.0 to 30.0
}

fun defaultMounts(handedness: Handedness): List<RadarMount> {
    val (yawA, yawB) = nominalYaws(handedness)
    return mountsWithYaws(yawA, yawB)
}

// Spec §6.8: without an absolute heading calibration keep the profile's nominal mean and apply only the measured spread.
fun mountsFromMeasuredYaws(handedness: Handedness, measuredYawADeg: Double, measuredYawBDeg: Double): List<RadarMount> {
    val (nominalA, nominalB) = nominalYaws(handedness)
    val nominalMean = (nominalA + nominalB) / 2.0
    val halfSpread = (measuredYawBDeg - measuredYawADeg) / 2.0
    return mountsWithYaws(nominalMean - halfSpread, nominalMean + halfSpread)
}

private fun mountsWithYaws(yawADeg: Double, yawBDeg: Double): List<RadarMount> = listOf(
    RadarMount(radarId = RADAR_A, xM = -MOUNT_OFFSET_M, yM = 0.0, yawDeg = yawADeg),
    RadarMount(radarId = RADAR_B, xM = MOUNT_OFFSET_M, yM = 0.0, yawDeg = yawBDeg),
)
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/scene/RadarScene.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.scene

enum class Confidence { BOTH, SINGLE, COASTING }

enum class Side { LEFT, CENTER, RIGHT }

data class Blip(
    val displayId: Int,
    val bearingDeg: Double,
    val rangeM: Double,
    val confidence: Confidence,
    val ageMs: Long,
    val outOfView: Boolean,
)

data class SensorStatus(val id: Int, val alive: Boolean)

enum class MotionState { STILL, TURNING, WALKING, PRONE }

enum class Warning { LINK_LOST, RADAR_DOWN, IMU_DOWN, NO_IMU_COMPENSATION, PRONE, CORRUPT_FRAMES, ALERT_OVERFLOW, YAW_UNCALIBRATED }

data class CoverageSector(val fromDeg: Double, val toDeg: Double)

data class RadarScene(
    val blips: List<Blip>,
    val coverage: List<CoverageSector>,
    val linkUp: Boolean,
    val radars: List<SensorStatus>,
    val imus: List<SensorStatus>,
    val motion: MotionState,
    val warnings: Set<Warning>,
    val eliminated: Boolean,
)
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/alerts/PipelineEvent.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.alerts

import io.github.santiquiroz.blindside.core.scene.Side
import io.github.santiquiroz.blindside.core.scene.Warning

sealed interface PipelineEvent {
    val tNanos: Long
}

data class ContactAlert(val displayId: Int, val side: Side, override val tNanos: Long) : PipelineEvent

data class TrackConfirmed(val displayId: Int, override val tNanos: Long) : PipelineEvent

data class SystemAlert(val kind: Warning, override val tNanos: Long) : PipelineEvent
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.config.*")`

Expected: PASS, 6 tests, 0 failures. Then run the whole module (`(cd watch && ./gradlew :radar-core:test)`) and confirm every earlier test still passes.

- [ ] **Step 5: Commit**

```bash
git add watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/config/TuningParams.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/config/Mounts.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/scene/RadarScene.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/alerts/PipelineEvent.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/config/ConfigTest.kt
git commit -m "feat: tipos públicos de radar-core (configuración, escena y eventos)"
```

### Task 3: LD2450 sign-magnitude codec

Spec §6.1: X, Y and speed use sign-magnitude where bit 15 = 1 means **positive** (`0x86B1` = +1713 mm). Tested against the shared `sign_magnitude` and `ld2450_official_frame` vectors (−782 mm / +1713 mm / −16 cm/s, spec §9). The encoder is needed later by the bundle encoder and the simulator.

**Files:**
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/protocol/LittleEndian.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/protocol/Ld2450Codec.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/protocol/Ld2450CodecTest.kt`

**Interfaces:**
- Consumes: `SharedVectors` (Task 1).
- Produces (`io.github.santiquiroz.blindside.core.protocol`): little-endian readers `ByteArray.u8(at)`, `u16le(at)`, `i16le(at)`, `u32le(at): Long`, `i32le(at)`, `i64le(at)`, `f32le(at)`; writers `ByteArrayOutputStream.putU8/putU16le/putU32le(Long)/putI64le/putF32le`; `fun buildBytes(block: ByteArrayOutputStream.() -> Unit): ByteArray`; `data class RawTarget(xMm: Int, yMm: Int, speedCms: Int, resolutionMm: Int) { val isEmpty }`; `object Ld2450Codec { TARGET_COUNT = 3; TARGET_BYTES = 8; BLOCK_BYTES = 24; fun decodeSignMagnitude(raw: Int): Int; fun encodeSignMagnitude(value: Int): Int; fun decodeTargets(bytes: ByteArray, offset: Int): List<RawTarget>; fun encodeTargets(targets: List<RawTarget>): ByteArray }`.

- [ ] **Step 1: Write the failing tests**

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/protocol/Ld2450CodecTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.protocol

import io.github.santiquiroz.blindside.core.SharedVectors
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class Ld2450CodecTest {
    @Test
    fun `sign magnitude decoding matches every shared vector`() {
        val cases = SharedVectors.json.getJSONArray("sign_magnitude")

        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            assertEquals(case.getInt("value"), Ld2450Codec.decodeSignMagnitude(case.getInt("raw")), "raw=${case.getInt("raw")}")
        }
    }

    @Test
    fun `official manual frame decodes to minus 782 mm, plus 1713 mm, minus 16 cm per s`() {
        val frame = SharedVectors.hex("ld2450_official_frame")

        val targets = Ld2450Codec.decodeTargets(frame, offset = 4)

        assertEquals(RawTarget(-782, 1713, -16, 320), targets[0])
        assertTrue(targets[1].isEmpty)
        assertTrue(targets[2].isEmpty)
    }

    @Test
    fun `encoding the official target reproduces the manual bytes`() {
        val frame = SharedVectors.hex("ld2450_official_frame")

        val block = Ld2450Codec.encodeTargets(listOf(RawTarget(-782, 1713, -16, 320)))

        assertArrayEquals(frame.copyOfRange(4, 28), block)
    }

    @Test
    fun `encoding rejects magnitudes above 15 bits`() {
        assertThrows<IllegalArgumentException> { Ld2450Codec.encodeSignMagnitude(40_000) }
    }

    @Test
    fun `both encodings of zero decode to an empty slot`() {
        assertEquals(0, Ld2450Codec.decodeSignMagnitude(0x8000))
        assertEquals(0, Ld2450Codec.decodeSignMagnitude(0x0000))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.protocol.Ld2450CodecTest")`

Expected: FAIL. `:radar-core:compileTestKotlin` reports `Unresolved reference` for `Ld2450Codec` and `RawTarget`.

- [ ] **Step 3: Write the implementation**

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/protocol/LittleEndian.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.protocol

import java.io.ByteArrayOutputStream

fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

fun ByteArray.u16le(at: Int): Int = u8(at) or (u8(at + 1) shl 8)

fun ByteArray.i16le(at: Int): Int = u16le(at).toShort().toInt()

fun ByteArray.u32le(at: Int): Long = u16le(at).toLong() or (u16le(at + 2).toLong() shl 16)

fun ByteArray.i32le(at: Int): Int = u32le(at).toInt()

fun ByteArray.i64le(at: Int): Long = u32le(at) or (u32le(at + 4) shl 32)

fun ByteArray.f32le(at: Int): Float = Float.fromBits(i32le(at))

fun ByteArrayOutputStream.putU8(value: Int) = write(value and 0xFF)

fun ByteArrayOutputStream.putU16le(value: Int) {
    putU8(value)
    putU8(value shr 8)
}

fun ByteArrayOutputStream.putU32le(value: Long) {
    putU16le((value and 0xFFFF).toInt())
    putU16le(((value shr 16) and 0xFFFF).toInt())
}

fun ByteArrayOutputStream.putI64le(value: Long) {
    putU32le(value and 0xFFFFFFFFL)
    putU32le((value ushr 32) and 0xFFFFFFFFL)
}

fun ByteArrayOutputStream.putF32le(value: Float) = putU32le(value.toRawBits().toLong() and 0xFFFFFFFFL)

fun buildBytes(block: ByteArrayOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().apply(block).toByteArray()
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/protocol/Ld2450Codec.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.protocol

import kotlin.math.abs

data class RawTarget(val xMm: Int, val yMm: Int, val speedCms: Int, val resolutionMm: Int) {
    val isEmpty: Boolean get() = xMm == 0 && yMm == 0 && speedCms == 0
}

object Ld2450Codec {
    const val TARGET_COUNT = 3
    const val TARGET_BYTES = 8
    const val BLOCK_BYTES = TARGET_COUNT * TARGET_BYTES
    private const val SIGN_BIT = 0x8000
    private const val MAGNITUDE_MASK = 0x7FFF

    // LD2450 quirk: bit 15 set means POSITIVE (sign-magnitude, not two's complement).
    fun decodeSignMagnitude(raw: Int): Int {
        val magnitude = raw and MAGNITUDE_MASK
        return if (raw and SIGN_BIT != 0) magnitude else -magnitude
    }

    fun encodeSignMagnitude(value: Int): Int {
        require(abs(value) <= MAGNITUDE_MASK) { "magnitude out of range: $value" }
        return if (value > 0) SIGN_BIT or value else -value
    }

    fun decodeTargets(bytes: ByteArray, offset: Int): List<RawTarget> =
        List(TARGET_COUNT) { index -> decodeTarget(bytes, offset + index * TARGET_BYTES) }

    fun encodeTargets(targets: List<RawTarget>): ByteArray {
        require(targets.size <= TARGET_COUNT) { "at most $TARGET_COUNT targets" }
        val padded = targets + List(TARGET_COUNT - targets.size) { RawTarget(0, 0, 0, 0) }
        return buildBytes { padded.forEach { putTarget(it) } }
    }

    private fun decodeTarget(bytes: ByteArray, at: Int) = RawTarget(
        xMm = decodeSignMagnitude(bytes.u16le(at)),
        yMm = decodeSignMagnitude(bytes.u16le(at + 2)),
        speedCms = decodeSignMagnitude(bytes.u16le(at + 4)),
        resolutionMm = bytes.u16le(at + 6),
    )

    private fun java.io.ByteArrayOutputStream.putTarget(target: RawTarget) {
        putU16le(encodeSignMagnitude(target.xMm))
        putU16le(encodeSignMagnitude(target.yMm))
        putU16le(encodeSignMagnitude(target.speedCms))
        putU16le(target.resolutionMm)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.protocol.Ld2450CodecTest")`

Expected: PASS, 5 tests, 0 failures. Then run the whole module (`(cd watch && ./gradlew :radar-core:test)`) and confirm every earlier test still passes.

- [ ] **Step 5: Commit**

```bash
git add watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/protocol/LittleEndian.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/protocol/Ld2450Codec.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/protocol/Ld2450CodecTest.kt
git commit -m "feat: códec signo-magnitud del LD2450 probado con los vectores compartidos"
```

### Task 4: BLE bundle decoder, encoder and sequence tracking

Spec §4.2 / contracts §"BLE packet": 8-byte header + TLV sections (`0x01` RADAR len 29, `0x02` IMU len 18 + 12n, `0x03` STATUS len 10, `0x04` LINK len 6). Unknown types are skipped by length; a length past the end drops the rest and marks the bundle truncated; known types with a wrong length are skipped and counted. LINK carries the effective connection interval (×1.25 ms), latency and supervision timeout (×10 ms) that NimBLE reported; the pipeline exposes the last one in its counters (Task 16a). `SeqTracker` counts losses as `((seq − prev) mod 65536) − 1` and restarts when `t_ms` goes backwards. The encoder writes the contract fill order (contracts §"BLE packet", spec §4.2): IMU batches by `imu_id` (IMU 0, then IMU 1), then STATUS, then LINK, then the RADAR frames sorted by `t_ms` (both sorts are stable). It is the exact inverse of the decoder for every packet already in that order, which covers the simulator, the tests and the shared vectors, and it must reproduce the shared vector bytes.

**Shared vectors (plan 02 Task 1).** Plan 02 Task 1 regenerates `protocol/vectors/vectors.json` in the contract fill order and adds one vector:
- `bundle_typical` (still 242 B): header at bytes 0-7, IMU 0 at 8-87, IMU 1 at 88-167, STATUS at 168-179, RADAR 0 (t 123400) at 180-210, RADAR 1 (t 123410) at 211-241. The old file had the two RADAR sections first.
- `bundle_with_link` (47 B): header `01 0F 09 00 58 1B 00 00` (seq 9, t 7000), LINK `04 06 24 00 00 00 F4 01` (interval 36 units, latency 0, supervision 500 units), then RADAR 0 at t 6990 with the official target. Its `expected.link` is `{"interval_units": 36, "latency": 0, "supervision_units": 500}`.

The decoder tests only compare decoded values (header fields, frames, batches and statuses by id and value, never byte offsets or section order), so the `bundle_typical` decode test passes on either layout. The byte-exact encoder test of `bundle_typical` and both `bundle_with_link` tests need the regenerated file, so this task starts after plan 02 Task 1 is committed (Step 1 checks it). This plan never edits the vectors or the generator.

**Files:**
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/protocol/Bundle.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/protocol/BundleDecoder.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/protocol/BundleEncoder.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/protocol/SeqTracker.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/protocol/BundleDecoderTest.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/protocol/BundleEncoderTest.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/protocol/SeqTrackerTest.kt`

**Interfaces:**
- Consumes: `RawTarget`, `Ld2450Codec`, LE helpers (Task 3); the shared vectors `bundle_one_radar`, `bundle_typical`, `bundle_with_link`, `bundle_unknown_tlv` and `bundle_truncated` as regenerated by plan 02 Task 1.
- Produces (`io.github.santiquiroz.blindside.core.protocol`): `data class RadarFrame(radarId: Int, tMs: Long, targets: List<RawTarget>)`; `data class ImuSample(ax, ay, az, gx, gy, gz: Int)`; `data class ImuBatch(imuId: Int, tFirstMs: Long, samples: List<ImuSample>, gyroSums: List<Long>) { fun sampleTimeMs(index: Int): Long; val tLastMs: Long }`; `data class RadarStatus(radarId, badFrames, restarts, baudIndex: Int)`; `data class LinkParams(intervalUnits: Int, latency: Int, timeoutUnits: Int) { val intervalMs: Double; val timeoutMs: Int }`; `data class Bundle(version, flags, seq: Int, tMs: Long, radarFrames, imuBatches, statuses, links: List<LinkParams> = emptyList(), truncated: Boolean = false, skippedSections: Int = 0) { fun radarAlive(radarId: Int); fun imuOk(imuId: Int); val dataDropped }`; constants `PROTOCOL_VERSION`, `HEADER_BYTES`, `TLV_RADAR`, `TLV_IMU`, `TLV_STATUS`, `TLV_LINK`, `IMU_SAMPLE_PERIOD_MS = 20L`, `RAW_READINGS_PER_SAMPLE = 4`, `RAW_GYRO_RATE_HZ = 200.0`, `GYRO_LSB_PER_DPS = 65.5`, `ACCEL_LSB_PER_G = 4096.0`; `object BundleDecoder { fun decode(bytes: ByteArray): Bundle? }` (null for < 8 bytes or version ≠ 1); `object BundleEncoder { fun encode(bundle: Bundle): ByteArray }` (contract fill order: IMU by id, STATUS, LINK, RADAR by `t_ms`); `data class SeqTracker(lastSeq, lastTMs, lostPackets: Long, resets: Int) { fun observe(seq: Int, tMs: Long): SeqTracker; fun isBackwards(tMs: Long): Boolean }`; `fun lostBetween(previousSeq: Int, nextSeq: Int): Int`.

- [ ] **Step 1: Check that the shared vectors are in the contract fill order**

Run (from the repo root): `python -c "import json; v = json.load(open('protocol/vectors/vectors.json')); t = bytes.fromhex(v['bundle_typical']['hex']); print('bundle_with_link' in v, len(t), t[8], t[168], t[180])"`

Expected: `True 242 2 3 1` (`bundle_with_link` exists; the sections of `bundle_typical` at bytes 8, 168 and 180 are IMU, STATUS and RADAR). If it prints `False 242 1 …`, plan 02 Task 1 has not landed yet: stop and wait for it. Do not edit `protocol/vectors/vectors.json`, `firmware/test/vectors.h` or `protocol/tools/make_vectors.py` from this plan.

- [ ] **Step 2: Write the failing tests**

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/protocol/BundleDecoderTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.protocol

import io.github.santiquiroz.blindside.core.SharedVectors
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BundleDecoderTest {
    @Test
    fun `one radar bundle matches the shared expectation`() {
        val vector = SharedVectors.json.getJSONObject("bundle_one_radar")

        val bundle = BundleDecoder.decode(SharedVectors.hex("bundle_one_radar"))!!

        assertHeader(vector.getJSONObject("expected"), bundle)
        assertEquals(listOf(RadarFrame(0, 995, listOf(RawTarget(-782, 1713, -16, 320), empty(), empty()))), bundle.radarFrames)
        assertTrue(bundle.imuBatches.isEmpty())
    }

    @Test
    fun `typical 242 byte bundle decodes radars, imus and statuses`() {
        val expected = SharedVectors.json.getJSONObject("bundle_typical").getJSONObject("expected")

        val bundle = BundleDecoder.decode(SharedVectors.hex("bundle_typical"))!!

        assertHeader(expected, bundle)
        assertRadarFrameIds(expected, bundle)
        assertEquals(RawTarget(500, 3000, 25, 360), bundle.radarFrames[1].targets[0])
        assertImuBatches(expected, bundle)
        assertEquals(listOf(RadarStatus(0, 2, 0, 7), RadarStatus(1, 0, 1, 7)), bundle.statuses)
        assertTrue(bundle.links.isEmpty())
    }

    @Test
    fun `a LINK section ahead of the radar decodes from the shared vector`() {
        val expected = SharedVectors.json.getJSONObject("bundle_with_link").getJSONObject("expected")
        val link = expected.getJSONObject("link")

        val bundle = BundleDecoder.decode(SharedVectors.hex("bundle_with_link"))!!

        assertHeader(expected, bundle)
        assertEquals(listOf(LinkParams(link.getInt("interval_units"), link.getInt("latency"), link.getInt("supervision_units"))), bundle.links)
        assertEquals(listOf(RadarFrame(0, 6990, listOf(RawTarget(-782, 1713, -16, 320), empty(), empty()))), bundle.radarFrames)
        assertEquals(0, bundle.skippedSections)
    }

    @Test
    fun `unknown section types are skipped by length`() {
        val bundle = BundleDecoder.decode(SharedVectors.hex("bundle_unknown_tlv"))!!

        assertEquals(listOf(1), bundle.radarFrames.map { it.radarId })
        assertEquals(1, bundle.skippedSections)
        assertFalse(bundle.truncated)
    }

    @Test
    fun `length past the end drops the rest and marks the bundle truncated`() {
        val bundle = BundleDecoder.decode(SharedVectors.hex("bundle_truncated"))!!

        assertTrue(bundle.truncated)
        assertTrue(bundle.radarFrames.isEmpty())
    }

    @Test
    fun `a single dangling byte after the header is a truncation`() {
        val bytes = SharedVectors.hex("bundle_one_radar").copyOfRange(0, HEADER_BYTES + 1)

        assertTrue(BundleDecoder.decode(bytes)!!.truncated)
    }

    @Test
    fun `short buffers and other protocol versions are rejected`() {
        assertNull(BundleDecoder.decode(ByteArray(5)))
        assertNull(BundleDecoder.decode(byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun `flags expose radar, imu and dropped bits`() {
        val bundle = Bundle(1, 0b10110, 0, 0, emptyList(), emptyList(), emptyList())

        assertFalse(bundle.radarAlive(0))
        assertTrue(bundle.radarAlive(1))
        assertTrue(bundle.imuOk(0))
        assertFalse(bundle.imuOk(1))
        assertTrue(bundle.dataDropped)
    }

    private fun assertHeader(expected: JSONObject, bundle: Bundle) {
        assertEquals(expected.getInt("version"), bundle.version)
        assertEquals(expected.getInt("flags"), bundle.flags)
        assertEquals(expected.getInt("seq"), bundle.seq)
        assertEquals(expected.getLong("t_ms"), bundle.tMs)
        assertEquals(expected.getBoolean("truncated"), bundle.truncated)
    }

    private fun assertRadarFrameIds(expected: JSONObject, bundle: Bundle) {
        val frames = expected.getJSONArray("radar_frames")
        val ids = List(frames.length()) { frames.getJSONObject(it).let { f -> f.getInt("radar_id") to f.getLong("t_ms") } }
        assertEquals(ids, bundle.radarFrames.map { it.radarId to it.tMs })
    }

    private fun assertImuBatches(expected: JSONObject, bundle: Bundle) {
        val batches = expected.getJSONArray("imu_batches")
        assertEquals(batches.length(), bundle.imuBatches.size)
        for (i in 0 until batches.length()) {
            val batch = batches.getJSONObject(i)
            val actual = bundle.imuBatches[i]
            assertEquals(batch.getInt("imu_id"), actual.imuId)
            assertEquals(batch.getLong("t_first_ms"), actual.tFirstMs)
            val sums = batch.getJSONArray("gyro_sums_u32")
            assertEquals(List(3) { sums.getLong(it) }, actual.gyroSums)
            val samples = batch.getJSONArray("samples")
            assertEquals(samples.length(), actual.samples.size)
            val first = samples.getJSONArray(0)
            assertEquals(ImuSample(first.getInt(0), first.getInt(1), first.getInt(2), first.getInt(3), first.getInt(4), first.getInt(5)), actual.samples[0])
        }
    }

    private fun empty() = RawTarget(0, 0, 0, 0)
}
```

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/protocol/BundleEncoderTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.protocol

import io.github.santiquiroz.blindside.core.SharedVectors
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BundleEncoderTest {
    @Test
    fun `encoding the decoded typical bundle reproduces the shared bytes`() {
        val bytes = SharedVectors.hex("bundle_typical")

        assertArrayEquals(bytes, BundleEncoder.encode(BundleDecoder.decode(bytes)!!))
    }

    @Test
    fun `encoding the decoded one radar bundle reproduces the shared bytes`() {
        val bytes = SharedVectors.hex("bundle_one_radar")

        assertArrayEquals(bytes, BundleEncoder.encode(BundleDecoder.decode(bytes)!!))
    }

    @Test
    fun `encoding the decoded link bundle reproduces the shared bytes`() {
        val bytes = SharedVectors.hex("bundle_with_link")

        assertArrayEquals(bytes, BundleEncoder.encode(BundleDecoder.decode(bytes)!!))
    }

    @Test
    fun `sections follow the contract fill order whatever order the bundle lists them in`() {
        val imu = { id: Int -> ImuBatch(id, 100, listOf(ImuSample(0, 0, 4096, 0, 0, 0)), listOf(0L, 0L, 0L)) }
        val radarB = RadarFrame(1, 120, listOf(RawTarget(500, 3000, 25, 360)))
        val radarA = RadarFrame(0, 110, listOf(RawTarget(-782, 1713, -16, 320)))
        val bundle = Bundle(1, 0x0F, 3, 130, listOf(radarB, radarA), listOf(imu(1), imu(0)), listOf(RadarStatus(0, 0, 0, 7)), links = listOf(LinkParams(36, 0, 500)))

        val bytes = BundleEncoder.encode(bundle)
        val decoded = BundleDecoder.decode(bytes)!!

        assertEquals(listOf(TLV_IMU, TLV_IMU, TLV_STATUS, TLV_LINK, TLV_RADAR, TLV_RADAR), sectionTypes(bytes))
        assertEquals(listOf(0, 1), decoded.imuBatches.map { it.imuId })
        assertEquals(listOf(110L, 120L), decoded.radarFrames.map { it.tMs })
    }

    @Test
    fun `a typical bundle stays within the 244 byte packet budget`() {
        assertEquals(242, BundleEncoder.encode(BundleDecoder.decode(SharedVectors.hex("bundle_typical"))!!).size)
    }

    @Test
    fun `negative gyro sums are written as wrapped u32`() {
        val batch = ImuBatch(0, 10, listOf(ImuSample(0, 0, 4096, 0, 0, -655)), listOf(-6L and 0xFFFFFFFFL, 0, 0))

        val decoded = BundleDecoder.decode(BundleEncoder.encode(Bundle(1, 0x0F, 1, 20, emptyList(), listOf(batch), emptyList())))!!

        assertEquals(batch, decoded.imuBatches.single())
    }

    @Test
    fun `a LINK section round trips and is not counted as skipped`() {
        val bundle = Bundle(1, 0x0F, 7, 1_000, emptyList(), emptyList(), emptyList(), links = listOf(LinkParams(36, 0, 500)))

        val decoded = BundleDecoder.decode(BundleEncoder.encode(bundle))!!

        assertEquals(listOf(LinkParams(36, 0, 500)), decoded.links)
        assertEquals(0, decoded.skippedSections)
        assertEquals(45.0, decoded.links.single().intervalMs, 1e-9)
        assertEquals(5_000, decoded.links.single().timeoutMs)
    }

    private tailrec fun sectionTypes(bytes: ByteArray, at: Int = HEADER_BYTES, found: List<Int> = emptyList()): List<Int> =
        if (at >= bytes.size) found else sectionTypes(bytes, at + 2 + bytes.u8(at + 1), found + bytes.u8(at))
}
```

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/protocol/SeqTrackerTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SeqTrackerTest {
    @Test
    fun `consecutive sequence numbers lose nothing`() {
        assertEquals(0, lostBetween(41, 42))
    }

    @Test
    fun `a gap counts the missing packets`() {
        assertEquals(3, lostBetween(10, 14))
    }

    @Test
    fun `wrap around 65536 is not a gap`() {
        assertEquals(0, lostBetween(65535, 0))
        assertEquals(1, lostBetween(65535, 1))
    }

    @Test
    fun `tracker accumulates losses across packets`() {
        val tracker = SeqTracker().observe(1, 100).observe(2, 200).observe(5, 500)

        assertEquals(2, tracker.lostPackets)
    }

    @Test
    fun `time going backwards resets the reference without counting a gap`() {
        val tracker = SeqTracker().observe(500, 90_000).observe(0, 50)

        assertEquals(0, tracker.lostPackets)
        assertEquals(1, tracker.resets)
        assertTrue(SeqTracker().observe(1, 1000).isBackwards(999))
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.protocol.*")`

Expected: FAIL. `:radar-core:compileTestKotlin` reports `Unresolved reference` for `BundleDecoder`, `BundleEncoder`, `Bundle`, `LinkParams`, `SeqTracker`, `lostBetween`.

- [ ] **Step 4: Write the implementation**

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/protocol/Bundle.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.protocol

data class RadarFrame(val radarId: Int, val tMs: Long, val targets: List<RawTarget>)

data class ImuSample(val ax: Int, val ay: Int, val az: Int, val gx: Int, val gy: Int, val gz: Int)

data class ImuBatch(val imuId: Int, val tFirstMs: Long, val samples: List<ImuSample>, val gyroSums: List<Long>) {
    fun sampleTimeMs(index: Int): Long = tFirstMs + index * IMU_SAMPLE_PERIOD_MS
    val tLastMs: Long get() = sampleTimeMs(samples.size - 1)
}

data class RadarStatus(val radarId: Int, val badFrames: Int, val restarts: Int, val baudIndex: Int)

data class LinkParams(val intervalUnits: Int, val latency: Int, val timeoutUnits: Int) {
    val intervalMs: Double get() = intervalUnits * 1.25
    val timeoutMs: Int get() = timeoutUnits * 10
}

data class Bundle(
    val version: Int,
    val flags: Int,
    val seq: Int,
    val tMs: Long,
    val radarFrames: List<RadarFrame>,
    val imuBatches: List<ImuBatch>,
    val statuses: List<RadarStatus>,
    val links: List<LinkParams> = emptyList(),
    val truncated: Boolean = false,
    val skippedSections: Int = 0,
) {
    fun radarAlive(radarId: Int): Boolean = flags and (1 shl radarId) != 0
    fun imuOk(imuId: Int): Boolean = flags and (1 shl (IMU_FLAG_SHIFT + imuId)) != 0
    val dataDropped: Boolean get() = flags and FLAG_DATA_DROPPED != 0
}

const val PROTOCOL_VERSION = 1
const val HEADER_BYTES = 8
const val TLV_RADAR = 0x01
const val TLV_IMU = 0x02
const val TLV_STATUS = 0x03
const val TLV_LINK = 0x04
const val RADAR_PAYLOAD_BYTES = 29
const val IMU_FIXED_BYTES = 18
const val IMU_SAMPLE_BYTES = 12
const val STATUS_ENTRY_BYTES = 5
const val LINK_PAYLOAD_BYTES = 6
const val IMU_SAMPLE_PERIOD_MS = 20L
const val RAW_READINGS_PER_SAMPLE = 4
const val RAW_GYRO_RATE_HZ = 200.0
const val GYRO_LSB_PER_DPS = 65.5
const val ACCEL_LSB_PER_G = 4096.0
private const val IMU_FLAG_SHIFT = 2
private const val FLAG_DATA_DROPPED = 0x10
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/protocol/BundleDecoder.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.protocol

object BundleDecoder {
    fun decode(bytes: ByteArray): Bundle? {
        if (bytes.size < HEADER_BYTES || bytes.u8(0) != PROTOCOL_VERSION) return null
        val scan = scanSections(bytes, HEADER_BYTES, SectionScan())
        return Bundle(
            version = bytes.u8(0),
            flags = bytes.u8(1),
            seq = bytes.u16le(2),
            tMs = bytes.u32le(4),
            radarFrames = scan.sections.filterIsInstance<Section.Radar>().map { it.frame },
            imuBatches = scan.sections.filterIsInstance<Section.Imu>().map { it.batch },
            statuses = scan.sections.filterIsInstance<Section.Status>().flatMap { it.entries },
            links = scan.sections.filterIsInstance<Section.Link>().map { it.params },
            truncated = scan.truncated,
            skippedSections = scan.skipped,
        )
    }

    private sealed interface Section {
        data class Radar(val frame: RadarFrame) : Section
        data class Imu(val batch: ImuBatch) : Section
        data class Status(val entries: List<RadarStatus>) : Section
        data class Link(val params: LinkParams) : Section
    }

    private data class SectionScan(
        val sections: List<Section> = emptyList(),
        val skipped: Int = 0,
        val truncated: Boolean = false,
    )

    private tailrec fun scanSections(bytes: ByteArray, offset: Int, scan: SectionScan): SectionScan {
        if (offset == bytes.size) return scan
        if (offset + 2 > bytes.size) return scan.copy(truncated = true)
        val length = bytes.u8(offset + 1)
        val payloadStart = offset + 2
        if (payloadStart + length > bytes.size) return scan.copy(truncated = true)
        val section = parseSection(bytes.u8(offset), bytes.copyOfRange(payloadStart, payloadStart + length))
        val next = if (section == null) scan.copy(skipped = scan.skipped + 1) else scan.copy(sections = scan.sections + section)
        return scanSections(bytes, payloadStart + length, next)
    }

    private fun parseSection(type: Int, payload: ByteArray): Section? = when (type) {
        TLV_RADAR -> parseRadar(payload)
        TLV_IMU -> parseImu(payload)
        TLV_STATUS -> parseStatus(payload)
        TLV_LINK -> parseLink(payload)
        else -> null
    }

    private fun parseRadar(payload: ByteArray): Section? {
        if (payload.size != RADAR_PAYLOAD_BYTES) return null
        val frame = RadarFrame(
            radarId = payload.u8(0),
            tMs = payload.u32le(1),
            targets = Ld2450Codec.decodeTargets(payload, 5),
        )
        return Section.Radar(frame)
    }

    private fun parseImu(payload: ByteArray): Section? {
        if (payload.size < IMU_FIXED_BYTES) return null
        val count = payload.u8(5)
        if (payload.size != IMU_FIXED_BYTES + IMU_SAMPLE_BYTES * count) return null
        val sumsAt = 6 + IMU_SAMPLE_BYTES * count
        val batch = ImuBatch(
            imuId = payload.u8(0),
            tFirstMs = payload.u32le(1),
            samples = List(count) { parseSample(payload, 6 + it * IMU_SAMPLE_BYTES) },
            gyroSums = List(3) { payload.u32le(sumsAt + it * 4) },
        )
        return Section.Imu(batch)
    }

    private fun parseSample(payload: ByteArray, at: Int) = ImuSample(
        ax = payload.i16le(at),
        ay = payload.i16le(at + 2),
        az = payload.i16le(at + 4),
        gx = payload.i16le(at + 6),
        gy = payload.i16le(at + 8),
        gz = payload.i16le(at + 10),
    )

    private fun parseStatus(payload: ByteArray): Section? {
        if (payload.isEmpty() || payload.size % STATUS_ENTRY_BYTES != 0) return null
        val entries = List(payload.size / STATUS_ENTRY_BYTES) { index ->
            val at = index * STATUS_ENTRY_BYTES
            RadarStatus(payload.u8(at), payload.u16le(at + 1), payload.u8(at + 3), payload.u8(at + 4))
        }
        return Section.Status(entries)
    }

    private fun parseLink(payload: ByteArray): Section? {
        if (payload.size != LINK_PAYLOAD_BYTES) return null
        return Section.Link(LinkParams(payload.u16le(0), payload.u16le(2), payload.u16le(4)))
    }
}
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/protocol/BundleEncoder.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.protocol

import java.io.ByteArrayOutputStream

object BundleEncoder {
    // Contract fill order (spec §4.2): IMU, STATUS and LINK first, then RADAR sorted by t_ms, as the belt sends it.
    fun encode(bundle: Bundle): ByteArray = buildBytes {
        putU8(bundle.version)
        putU8(bundle.flags)
        putU16le(bundle.seq and 0xFFFF)
        putU32le(bundle.tMs and 0xFFFFFFFFL)
        bundle.imuBatches.sortedBy { it.imuId }.forEach { putSection(TLV_IMU, imuPayload(it)) }
        if (bundle.statuses.isNotEmpty()) putSection(TLV_STATUS, statusPayload(bundle.statuses))
        bundle.links.forEach { putSection(TLV_LINK, linkPayload(it)) }
        bundle.radarFrames.sortedBy { it.tMs }.forEach { putSection(TLV_RADAR, radarPayload(it)) }
    }

    fun radarPayload(frame: RadarFrame): ByteArray = buildBytes {
        putU8(frame.radarId)
        putU32le(frame.tMs and 0xFFFFFFFFL)
        write(Ld2450Codec.encodeTargets(frame.targets.filterNot { it.isEmpty }))
    }

    fun imuPayload(batch: ImuBatch): ByteArray = buildBytes {
        putU8(batch.imuId)
        putU32le(batch.tFirstMs and 0xFFFFFFFFL)
        putU8(batch.samples.size)
        batch.samples.forEach { s -> listOf(s.ax, s.ay, s.az, s.gx, s.gy, s.gz).forEach { putU16le(it and 0xFFFF) } }
        batch.gyroSums.forEach { putU32le(it and 0xFFFFFFFFL) }
    }

    fun statusPayload(statuses: List<RadarStatus>): ByteArray = buildBytes {
        statuses.forEach { s ->
            putU8(s.radarId)
            putU16le(s.badFrames)
            putU8(s.restarts)
            putU8(s.baudIndex)
        }
    }

    fun linkPayload(link: LinkParams): ByteArray = buildBytes {
        putU16le(link.intervalUnits)
        putU16le(link.latency)
        putU16le(link.timeoutUnits)
    }

    private fun ByteArrayOutputStream.putSection(type: Int, payload: ByteArray) {
        putU8(type)
        putU8(payload.size)
        write(payload)
    }
}
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/protocol/SeqTracker.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.protocol

data class SeqTracker(
    val lastSeq: Int? = null,
    val lastTMs: Long? = null,
    val lostPackets: Long = 0,
    val resets: Int = 0,
) {
    fun observe(seq: Int, tMs: Long): SeqTracker = when {
        lastSeq == null || lastTMs == null -> copy(lastSeq = seq, lastTMs = tMs)
        tMs < lastTMs -> copy(lastSeq = seq, lastTMs = tMs, resets = resets + 1)
        else -> copy(lastSeq = seq, lastTMs = tMs, lostPackets = lostPackets + lostBetween(lastSeq, seq))
    }

    fun isBackwards(tMs: Long): Boolean = lastTMs != null && tMs < lastTMs
}

fun lostBetween(previousSeq: Int, nextSeq: Int): Int =
    (((nextSeq - previousSeq) and 0xFFFF) - 1).coerceAtLeast(0)
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.protocol.*")`

Expected: PASS, 20 tests, 0 failures (decoder 8, encoder 7, seq 5). If the only failures are the byte-exact `bundle_typical` encoder test and the two `bundle_with_link` tests, the vectors are still the old RADAR-first file: go back to Step 1. Then run the whole module (`(cd watch && ./gradlew :radar-core:test)`) and confirm every earlier test still passes.

- [ ] **Step 6: Commit**

```bash
git add watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/protocol/Bundle.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/protocol/BundleDecoder.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/protocol/BundleEncoder.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/protocol/SeqTracker.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/protocol/BundleDecoderTest.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/protocol/BundleEncoderTest.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/protocol/SeqTrackerTest.kt
git commit -m "feat: decodificador y codificador TLV del paquete BLE (con LINK y el orden de llenado del contrato) y seguimiento de seq"
```

### Task 4b: `.bsrec` format and record payloads

Spec §6.10 / contracts §".bsrec": `"BSREC"` + u8 version 1 + u32 header length + UTF-8 JSON header, then records `u8 type | u32 t_ms_since_start | u16 payload_len | payload`, little-endian. The 10 record types of the contract and every payload helper live here, so the app (plan 03 Task 4) can record from 2 oct on without waiting for the pipeline; the replay itself comes in Task 17. A partial record at the end (crash) ends the sequence quietly; unknown record types are skipped. Payloads: gravity and watch gyroscope 3 × f32 + i64 event nanos, step i64, track confirmed i32, vibration i32 + u8 side, mode change UTF-8 (`SessionMode` name, or `LINK_UP`/`LINK_DOWN` for link transitions, contracts), info UTF-8 JSON, RSSI i16 dBm.

**Files:**
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/replay/Bsrec.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/replay/BsrecPayloads.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/replay/BsrecTest.kt`

**Interfaces:**
- Consumes: LE helpers `buildBytes`, `putU8`, `putU16le`, `putU32le`, `putI64le`, `putF32le`, `u16le`, `u32le`, `i16le`, `i32le`, `i64le`, `f32le` (Task 3); `Side` (Task 2).
- Produces (`io.github.santiquiroz.blindside.core.replay`): `enum class RecordType(val code: Int) { BLE_PACKET(1), WATCH_GRAVITY(2), WATCH_STEP(3), MANUAL_MARKER(4), TRACK_CONFIRMED(5), VIBRATION_STARTED(6), MODE_CHANGE(7), INFO_REREAD(8), WATCH_GYRO(9), RSSI(10); companion fun fromCode(code: Int): RecordType? }`; `data class BsrecRecord(type: RecordType, tMsSinceStart: Long, payload: ByteArray)`; `class BsrecWriter(out: OutputStream, headerJson: String) { fun write(record: BsrecRecord); fun close() }`; `class BsrecReader(input: InputStream) { val headerJson: String; fun records(): Sequence<BsrecRecord> }`; `enum class SessionMode { ELIMINATED, STEALTH, VIEW }`; `data class GravitySample(x, y, z: Float, eventNanos: Long)`; `const val LINK_UP_MODE = "LINK_UP"`, `const val LINK_DOWN_MODE = "LINK_DOWN"`; `object BsrecPayloads { gravity(x, y, z, eventNanos); readGravity(payload): GravitySample; watchGyro(x, y, z, eventNanos); readWatchGyro(payload): GravitySample; step(eventNanos); readStep(payload): Long; trackConfirmed(displayId); readTrackConfirmed(payload): Int; vibrationStarted(displayId, side); modeChange(mode); readModeChange(payload): SessionMode?; linkChange(connected: Boolean); readLinkChange(payload): Boolean?; info(json: String); readInfo(payload): String; rssi(dbm: Int); readRssi(payload): Int }`.

- [ ] **Step 1: Write the failing tests**

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/replay/BsrecTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.replay

import io.github.santiquiroz.blindside.core.scene.Side
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BsrecTest {
    @Test
    fun `header and records round trip`() {
        val bytes = record(
            BsrecRecord(RecordType.BLE_PACKET, 10, byteArrayOf(1, 2, 3)),
            BsrecRecord(RecordType.MANUAL_MARKER, 20, ByteArray(0)),
        )

        val reader = BsrecReader(ByteArrayInputStream(bytes))
        val records = reader.records().toList()

        assertEquals("""{"proto":1}""", reader.headerJson)
        assertEquals(listOf(RecordType.BLE_PACKET, RecordType.MANUAL_MARKER), records.map { it.type })
        assertEquals(listOf(10L, 20L), records.map { it.tMsSinceStart })
        assertArrayEquals(byteArrayOf(1, 2, 3), records[0].payload)
    }

    @Test
    fun `record types 8 to 10 survive a round trip`() {
        val bytes = record(
            BsrecRecord(RecordType.INFO_REREAD, 1, BsrecPayloads.info("""{"boot_id":"9f3a12c4"}""")),
            BsrecRecord(RecordType.WATCH_GYRO, 2, BsrecPayloads.watchGyro(0.1f, 0.2f, 0.3f, 99L)),
            BsrecRecord(RecordType.RSSI, 3, BsrecPayloads.rssi(-71)),
        )

        val records = BsrecReader(ByteArrayInputStream(bytes)).records().toList()

        assertEquals(listOf(8, 9, 10), records.map { it.type.code })
    }

    @Test
    fun `the file starts with BSREC, version 1 and the header length`() {
        val bytes = record()

        assertEquals("BSREC", String(bytes, 0, 5, Charsets.US_ASCII))
        assertEquals(1, bytes[5].toInt())
        assertEquals(11, bytes[6].toInt())
    }

    @Test
    fun `a record cut by a crash ends the sequence without failing`() {
        val bytes = record(BsrecRecord(RecordType.BLE_PACKET, 10, byteArrayOf(1, 2, 3)), BsrecRecord(RecordType.BLE_PACKET, 20, ByteArray(40)))

        val records = BsrecReader(ByteArrayInputStream(bytes.copyOfRange(0, bytes.size - 10))).records().toList()

        assertEquals(1, records.size)
    }

    @Test
    fun `an unknown record type is skipped`() {
        val known = record(BsrecRecord(RecordType.MANUAL_MARKER, 5, ByteArray(0)))
        val unknown = byteArrayOf(99, 6, 0, 0, 0, 2, 0, 7, 7)

        val records = BsrecReader(ByteArrayInputStream(known + unknown + recordBytes(RecordType.WATCH_STEP, 7))).records().toList()

        assertEquals(listOf(RecordType.MANUAL_MARKER, RecordType.WATCH_STEP), records.map { it.type })
    }

    @Test
    fun `a file that is not a recording is rejected`() {
        assertThrows<IllegalArgumentException> { BsrecReader(ByteArrayInputStream("NOPE!\u0001\u0000\u0000\u0000\u0000".toByteArray())) }
    }

    @Test
    fun `sensor payload helpers round trip`() {
        val gravity = BsrecPayloads.readGravity(BsrecPayloads.gravity(0.1f, -9.7f, 1.2f, 123_456_789_000L))
        val gyro = BsrecPayloads.readWatchGyro(BsrecPayloads.watchGyro(0.5f, -0.25f, 1.5f, 987_654_321_000L))

        assertEquals(GravitySample(0.1f, -9.7f, 1.2f, 123_456_789_000L), gravity)
        assertEquals(GravitySample(0.5f, -0.25f, 1.5f, 987_654_321_000L), gyro)
        assertEquals(42L, BsrecPayloads.readStep(BsrecPayloads.step(42L)))
    }

    @Test
    fun `event and link payload helpers round trip`() {
        assertEquals(7, BsrecPayloads.readTrackConfirmed(BsrecPayloads.trackConfirmed(7)))
        assertEquals(5, BsrecPayloads.vibrationStarted(7, Side.RIGHT).size)
        assertEquals(SessionMode.ELIMINATED, BsrecPayloads.readModeChange(BsrecPayloads.modeChange(SessionMode.ELIMINATED)))
        assertEquals("""{"mtu":255}""", BsrecPayloads.readInfo(BsrecPayloads.info("""{"mtu":255}""")))
        assertEquals(listOf(0xBA.toByte(), 0xFF.toByte()), BsrecPayloads.rssi(-70).toList())
        assertEquals(-71, BsrecPayloads.readRssi(BsrecPayloads.rssi(-71)))
    }

    @Test
    fun `link changes are mode changes named LINK_UP and LINK_DOWN`() {
        assertEquals(LINK_UP_MODE, String(BsrecPayloads.linkChange(true), Charsets.UTF_8))
        assertEquals(false, BsrecPayloads.readLinkChange(BsrecPayloads.linkChange(false)))
        assertNull(BsrecPayloads.readLinkChange(BsrecPayloads.modeChange(SessionMode.VIEW)))
        assertNull(BsrecPayloads.readModeChange(BsrecPayloads.linkChange(true)))
    }

    private fun record(vararg records: BsrecRecord): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = BsrecWriter(out, """{"proto":1}""")
        records.forEach { writer.write(it) }
        writer.close()
        return out.toByteArray()
    }

    private fun recordBytes(type: RecordType, tMs: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = BsrecWriter(out, "")
        writer.write(BsrecRecord(type, tMs, BsrecPayloads.step(1L)))
        return out.toByteArray().copyOfRange(10, out.size())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.replay.BsrecTest")`

Expected: FAIL. `:radar-core:compileTestKotlin` reports `Unresolved reference` for `BsrecWriter`, `BsrecReader`, `BsrecRecord`, `RecordType`, `BsrecPayloads`.

- [ ] **Step 3: Write the implementation**

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/replay/Bsrec.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.replay

import io.github.santiquiroz.blindside.core.protocol.buildBytes
import io.github.santiquiroz.blindside.core.protocol.putU16le
import io.github.santiquiroz.blindside.core.protocol.putU32le
import io.github.santiquiroz.blindside.core.protocol.putU8
import io.github.santiquiroz.blindside.core.protocol.u16le
import io.github.santiquiroz.blindside.core.protocol.u32le
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

enum class RecordType(val code: Int) {
    BLE_PACKET(1),
    WATCH_GRAVITY(2),
    WATCH_STEP(3),
    MANUAL_MARKER(4),
    TRACK_CONFIRMED(5),
    VIBRATION_STARTED(6),
    MODE_CHANGE(7),
    INFO_REREAD(8),
    WATCH_GYRO(9),
    RSSI(10),
    ;

    companion object {
        fun fromCode(code: Int): RecordType? = entries.firstOrNull { it.code == code }
    }
}

data class BsrecRecord(val type: RecordType, val tMsSinceStart: Long, val payload: ByteArray)

const val BSREC_MAGIC = "BSREC"
const val BSREC_FORMAT_VERSION = 1
private const val RECORD_HEADER_BYTES = 7

class BsrecWriter(private val out: OutputStream, headerJson: String) {
    init {
        val json = headerJson.toByteArray(Charsets.UTF_8)
        out.write(buildBytes {
            write(BSREC_MAGIC.toByteArray(Charsets.US_ASCII))
            putU8(BSREC_FORMAT_VERSION)
            putU32le(json.size.toLong())
            write(json)
        })
    }

    fun write(record: BsrecRecord) {
        require(record.payload.size <= 0xFFFF) { "payload too large: ${record.payload.size}" }
        out.write(buildBytes {
            putU8(record.type.code)
            putU32le(record.tMsSinceStart and 0xFFFFFFFFL)
            putU16le(record.payload.size)
            write(record.payload)
        })
    }

    fun close() = out.close()
}

class BsrecReader(input: InputStream) {
    private val data = DataInputStream(input)
    val headerJson: String = readHeader()

    // A recording cut by a crash ends with a partial record; reading stops there instead of failing.
    fun records(): Sequence<BsrecRecord> = generateSequence { readRecord() }

    private fun readHeader(): String {
        val fixed = ByteArray(BSREC_MAGIC.length + 5).also { data.readFully(it) }
        require(String(fixed, 0, BSREC_MAGIC.length, Charsets.US_ASCII) == BSREC_MAGIC) { "not a .bsrec file" }
        require(fixed[BSREC_MAGIC.length].toInt() == BSREC_FORMAT_VERSION) { "unsupported .bsrec version ${fixed[BSREC_MAGIC.length]}" }
        val length = fixed.u32le(BSREC_MAGIC.length + 1).toInt()
        return String(ByteArray(length).also { data.readFully(it) }, Charsets.UTF_8)
    }

    private fun readRecord(): BsrecRecord? = try {
        val head = ByteArray(RECORD_HEADER_BYTES).also { data.readFully(it) }
        val payload = ByteArray(head.u16le(5)).also { data.readFully(it) }
        val type = RecordType.fromCode(head[0].toInt() and 0xFF)
        if (type == null) readRecord() else BsrecRecord(type, head.u32le(1), payload)
    } catch (_: EOFException) {
        null
    }
}
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/replay/BsrecPayloads.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.replay

import io.github.santiquiroz.blindside.core.protocol.buildBytes
import io.github.santiquiroz.blindside.core.protocol.f32le
import io.github.santiquiroz.blindside.core.protocol.i16le
import io.github.santiquiroz.blindside.core.protocol.i32le
import io.github.santiquiroz.blindside.core.protocol.i64le
import io.github.santiquiroz.blindside.core.protocol.putF32le
import io.github.santiquiroz.blindside.core.protocol.putI64le
import io.github.santiquiroz.blindside.core.protocol.putU16le
import io.github.santiquiroz.blindside.core.protocol.putU32le
import io.github.santiquiroz.blindside.core.protocol.putU8
import io.github.santiquiroz.blindside.core.scene.Side

enum class SessionMode { ELIMINATED, STEALTH, VIEW }

// Gravity and the watch gyroscope share the contract layout 3 × f32 + i64 event nanos.
data class GravitySample(val x: Float, val y: Float, val z: Float, val eventNanos: Long)

const val LINK_UP_MODE = "LINK_UP"
const val LINK_DOWN_MODE = "LINK_DOWN"

object BsrecPayloads {
    fun gravity(x: Float, y: Float, z: Float, eventNanos: Long): ByteArray = vector(x, y, z, eventNanos)

    fun readGravity(payload: ByteArray): GravitySample = GravitySample(payload.f32le(0), payload.f32le(4), payload.f32le(8), payload.i64le(12))

    fun watchGyro(x: Float, y: Float, z: Float, eventNanos: Long): ByteArray = vector(x, y, z, eventNanos)

    fun readWatchGyro(payload: ByteArray): GravitySample = readGravity(payload)

    fun step(eventNanos: Long): ByteArray = buildBytes { putI64le(eventNanos) }

    fun readStep(payload: ByteArray): Long = payload.i64le(0)

    fun trackConfirmed(displayId: Int): ByteArray = buildBytes { putU32le(displayId.toLong() and 0xFFFFFFFFL) }

    fun readTrackConfirmed(payload: ByteArray): Int = payload.i32le(0)

    fun vibrationStarted(displayId: Int, side: Side): ByteArray = buildBytes {
        putU32le(displayId.toLong() and 0xFFFFFFFFL)
        putU8(side.ordinal)
    }

    fun modeChange(mode: SessionMode): ByteArray = mode.name.toByteArray(Charsets.UTF_8)

    fun readModeChange(payload: ByteArray): SessionMode? =
        SessionMode.entries.firstOrNull { it.name == String(payload, Charsets.UTF_8) }

    fun linkChange(connected: Boolean): ByteArray = (if (connected) LINK_UP_MODE else LINK_DOWN_MODE).toByteArray(Charsets.UTF_8)

    fun readLinkChange(payload: ByteArray): Boolean? = when (String(payload, Charsets.UTF_8)) {
        LINK_UP_MODE -> true
        LINK_DOWN_MODE -> false
        else -> null
    }

    fun info(json: String): ByteArray = json.toByteArray(Charsets.UTF_8)

    fun readInfo(payload: ByteArray): String = String(payload, Charsets.UTF_8)

    fun rssi(dbm: Int): ByteArray = buildBytes { putU16le(dbm.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()) and 0xFFFF) }

    fun readRssi(payload: ByteArray): Int = payload.i16le(0)

    private fun vector(x: Float, y: Float, z: Float, eventNanos: Long): ByteArray = buildBytes {
        putF32le(x)
        putF32le(y)
        putF32le(z)
        putI64le(eventNanos)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.replay.BsrecTest")`

Expected: PASS, 9 tests, 0 failures. Then run the whole module (`(cd watch && ./gradlew :radar-core:test)`) and confirm every earlier test still passes.

- [ ] **Step 5: Commit**

```bash
git add watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/replay/Bsrec.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/replay/BsrecPayloads.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/replay/BsrecTest.kt
git commit -m "feat: formato .bsrec con los 10 tipos de registro y sus payloads"
```

### Task 4c: Dependency-free JSON reader and the belt `info`

radar-core has no runtime dependencies (Global Constraints), yet it must read the belt `info` JSON (spec §4.2, §5.2: `boot_id` restarts the clock mapping, spec §6.3; `imus[i].gyro_lsb_dps` and `accel_lsb_g` are the IMU scales) and the config stored in the `.bsrec` header (Task 4d). `MiniJson` is a small recursive-descent reader (objects, arrays, strings with escapes, numbers as `Double`, booleans, null) plus three writer helpers. `parseBeltInfo` returns `null` for anything that is not a JSON object; missing scales fall back to the contract's ±500 °/s and ±8 g.

**Files:**
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/protocol/MiniJson.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/protocol/BeltInfo.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/protocol/MiniJsonTest.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/protocol/BeltInfoTest.kt`

**Interfaces:**
- Consumes: `GYRO_LSB_PER_DPS`, `ACCEL_LSB_PER_G` (Task 4).
- Produces (`io.github.santiquiroz.blindside.core.protocol`): `object MiniJson { fun parse(text: String): Any?; fun parseOrNull(text: String): Any?; fun quote(value: String): String; fun obj(fields: List<Pair<String, String>>): String; fun array(items: List<String>): String }` (objects are `Map<String, Any?>`, arrays `List<Any?>`, numbers `Double`; `parse` throws `IllegalArgumentException` on malformed text); `data class ImuScale(imuId: Int, gyroLsbPerDps: Double, accelLsbPerG: Double)`; `data class BeltInfo(bootId: String?, imuScales: List<ImuScale>)`; `fun parseBeltInfo(json: String): BeltInfo?`.

- [ ] **Step 1: Write the failing tests**

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/protocol/MiniJsonTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MiniJsonTest {
    @Test
    fun `objects, arrays, numbers, literals and nesting parse`() {
        val parsed = MiniJson.parse("""{ "a": [1, -2.5, 3e2], "b": {"c": true, "d": null}, "e": false }""")

        assertEquals(mapOf("a" to listOf(1.0, -2.5, 300.0), "b" to mapOf("c" to true, "d" to null), "e" to false), parsed)
    }

    @Test
    fun `strings keep escapes and unicode`() {
        assertEquals("a\"b\\c/d\neé", MiniJson.parse("\"a\\\"b\\\\c\\/d\\ne\\u00e9\""))
    }

    @Test
    fun `empty containers parse`() {
        assertEquals(mapOf<String, Any?>(), MiniJson.parse("{}"))
        assertEquals(listOf<Any?>(), MiniJson.parse("[ ]"))
    }

    @Test
    fun `malformed text is rejected and parseOrNull returns null`() {
        assertThrows<IllegalArgumentException> { MiniJson.parse("""{"a":1""") }
        assertThrows<IllegalArgumentException> { MiniJson.parse("""{"a":1} x""") }
        assertNull(MiniJson.parseOrNull("not json"))
    }

    @Test
    fun `written objects read back`() {
        val text = MiniJson.obj(listOf("name" to MiniJson.quote("say \"hi\"\n"), "list" to MiniJson.array(listOf("1", "2.5"))))

        assertEquals(mapOf("name" to "say \"hi\"\n", "list" to listOf(1.0, 2.5)), MiniJson.parse(text))
    }
}
```

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/protocol/BeltInfoTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BeltInfoTest {
    private val contractExample = """
        {"proto":1,"fw":"0.1.0","boot_id":"9f3a12c4","reset":"POWERON","mtu":255,
         "radars":[{"id":0,"fw":"V2.04.23101915","baud":256000},{"id":1,"fw":"V2.04.23101915","baud":256000}],
         "imus":[{"id":0,"who":104,"gyro_lsb_dps":65.5,"accel_lsb_g":4096,"repeats":0},{"id":1,"who":112,"gyro_lsb_dps":65.5,"accel_lsb_g":4096,"repeats":3}],
         "tx_power_dbm":9,"conn":{"interval_ms":45.0,"latency":0,"timeout_ms":5000},"uptime_s":42}
    """.trimIndent()

    @Test
    fun `the contract example yields the boot id and both imu scales`() {
        val info = parseBeltInfo(contractExample)!!

        assertEquals("9f3a12c4", info.bootId)
        assertEquals(listOf(ImuScale(0, 65.5, 4096.0), ImuScale(1, 65.5, 4096.0)), info.imuScales)
    }

    @Test
    fun `missing scales fall back to the protocol defaults`() {
        val info = parseBeltInfo("""{"boot_id":"01","imus":[{"id":1,"gyro_lsb_dps":32.8}]}""")!!

        assertEquals(listOf(ImuScale(1, 32.8, ACCEL_LSB_PER_G)), info.imuScales)
    }

    @Test
    fun `info without a boot id or imus still parses`() {
        assertEquals(BeltInfo(null, emptyList()), parseBeltInfo("""{"proto":1}"""))
    }

    @Test
    fun `text that is not a json object is ignored`() {
        assertNull(parseBeltInfo("garbage"))
        assertNull(parseBeltInfo("[1,2]"))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.protocol.MiniJsonTest" --tests "io.github.santiquiroz.blindside.core.protocol.BeltInfoTest")`

Expected: FAIL. `:radar-core:compileTestKotlin` reports `Unresolved reference` for `MiniJson`, `parseBeltInfo`, `ImuScale`, `BeltInfo`.

- [ ] **Step 3: Write the implementation**

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/protocol/MiniJson.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.protocol

// radar-core has no runtime dependencies, so the few JSON documents it touches (belt info, config) use this reader.
object MiniJson {
    fun parse(text: String): Any? {
        val parsed = readValue(text, skipSpaces(text, 0))
        val end = skipSpaces(text, parsed.next)
        require(end == text.length) { "unexpected '${text[end]}' at $end" }
        return parsed.value
    }

    fun parseOrNull(text: String): Any? = runCatching { parse(text) }.getOrNull()

    fun quote(value: String): String = buildString {
        append('"')
        value.forEach { append(escaped(it)) }
        append('"')
    }

    fun obj(fields: List<Pair<String, String>>): String =
        fields.joinToString(",", "{", "}") { (key, value) -> "${quote(key)}:$value" }

    fun array(items: List<String>): String = items.joinToString(",", "[", "]")
}

private data class Parsed(val value: Any?, val next: Int)

private fun readValue(text: String, at: Int): Parsed {
    require(at < text.length) { "unexpected end of JSON" }
    return when (text[at]) {
        '{' -> readObject(text, skipSpaces(text, at + 1), emptyMap())
        '[' -> readArray(text, skipSpaces(text, at + 1), emptyList())
        '"' -> readString(text, at + 1, StringBuilder())
        't' -> readLiteral(text, at, "true", true)
        'f' -> readLiteral(text, at, "false", false)
        'n' -> readLiteral(text, at, "null", null)
        else -> readNumber(text, at)
    }
}

private tailrec fun readObject(text: String, at: Int, fields: Map<String, Any?>): Parsed {
    if (text.getOrNull(at) == '}') return Parsed(fields, at + 1)
    require(text.getOrNull(at) == '"') { "expected a key at $at" }
    val key = readString(text, at + 1, StringBuilder())
    val colon = skipSpaces(text, key.next)
    require(text.getOrNull(colon) == ':') { "expected ':' at $colon" }
    val value = readValue(text, skipSpaces(text, colon + 1))
    val next = skipSpaces(text, value.next)
    val updated = fields + (key.value as String to value.value)
    return when (text.getOrNull(next)) {
        ',' -> readObject(text, skipSpaces(text, next + 1), updated)
        '}' -> Parsed(updated, next + 1)
        else -> throw IllegalArgumentException("expected ',' or '}' at $next")
    }
}

private tailrec fun readArray(text: String, at: Int, items: List<Any?>): Parsed {
    if (text.getOrNull(at) == ']') return Parsed(items, at + 1)
    val value = readValue(text, at)
    val next = skipSpaces(text, value.next)
    return when (text.getOrNull(next)) {
        ',' -> readArray(text, skipSpaces(text, next + 1), items + value.value)
        ']' -> Parsed(items + value.value, next + 1)
        else -> throw IllegalArgumentException("expected ',' or ']' at $next")
    }
}

private tailrec fun readString(text: String, at: Int, out: StringBuilder): Parsed {
    require(at < text.length) { "unterminated string" }
    return when (val c = text[at]) {
        '"' -> Parsed(out.toString(), at + 1)
        '\\' -> readString(text, at + escapeLength(text, at), out.append(unescape(text, at)))
        else -> readString(text, at + 1, out.append(c))
    }
}

private fun escapeLength(text: String, at: Int): Int = if (text.getOrNull(at + 1) == 'u') 6 else 2

private fun unescape(text: String, at: Int): Char = when (val code = text.getOrNull(at + 1)) {
    '"', '\\', '/' -> code
    'b' -> '\b'
    'f' -> '\u000C'
    'n' -> '\n'
    'r' -> '\r'
    't' -> '\t'
    'u' -> text.substring(at + 2, at + 6).toInt(16).toChar()
    else -> throw IllegalArgumentException("bad escape at $at")
}

private fun readLiteral(text: String, at: Int, word: String, value: Any?): Parsed {
    require(text.startsWith(word, at)) { "expected $word at $at" }
    return Parsed(value, at + word.length)
}

private fun readNumber(text: String, at: Int): Parsed {
    val end = (at until text.length).firstOrNull { text[it] !in NUMBER_CHARS } ?: text.length
    val number = text.substring(at, end).toDoubleOrNull() ?: throw IllegalArgumentException("bad number at $at")
    return Parsed(number, end)
}

private tailrec fun skipSpaces(text: String, at: Int): Int =
    if (at < text.length && text[at].isWhitespace()) skipSpaces(text, at + 1) else at

private fun escaped(c: Char): String = when {
    c == '"' -> "\\\""
    c == '\\' -> "\\\\"
    c == '\n' -> "\\n"
    c == '\r' -> "\\r"
    c == '\t' -> "\\t"
    c < ' ' -> "\\u%04x".format(c.code)
    else -> c.toString()
}

private const val NUMBER_CHARS = "+-0123456789.eE"
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/protocol/BeltInfo.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.protocol

data class ImuScale(val imuId: Int, val gyroLsbPerDps: Double, val accelLsbPerG: Double)

data class BeltInfo(val bootId: String?, val imuScales: List<ImuScale>)

fun parseBeltInfo(json: String): BeltInfo? {
    val root = MiniJson.parseOrNull(json) as? Map<*, *> ?: return null
    val imus = (root["imus"] as? List<*>).orEmpty().mapNotNull { imuScaleOf(it) }
    return BeltInfo(bootId = root["boot_id"] as? String, imuScales = imus)
}

private fun imuScaleOf(entry: Any?): ImuScale? {
    val imu = entry as? Map<*, *> ?: return null
    val id = (imu["id"] as? Double)?.toInt() ?: return null
    val gyro = imu["gyro_lsb_dps"] as? Double ?: GYRO_LSB_PER_DPS
    val accel = imu["accel_lsb_g"] as? Double ?: ACCEL_LSB_PER_G
    return ImuScale(id, gyro, accel)
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.protocol.MiniJsonTest" --tests "io.github.santiquiroz.blindside.core.protocol.BeltInfoTest")`

Expected: PASS, 9 tests, 0 failures. Then run the whole module (`(cd watch && ./gradlew :radar-core:test)`) and confirm every earlier test still passes.

- [ ] **Step 5: Commit**

```bash
git add watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/protocol/MiniJson.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/protocol/BeltInfo.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/protocol/MiniJsonTest.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/protocol/BeltInfoTest.kt
git commit -m "feat: lector JSON sin dependencias y lectura del info del cinturón (boot_id y escalas)"
```

### Task 4d: `PipelineConfig` to and from JSON

Spec §6.10: the `.bsrec` header carries `TuningParams` and the calibration (mounts), so a replay can rebuild the exact config of the session (Task 17) and the τ sweep can vary one field of it. Hand-written, no reflection and no runtime dependency: each parameter group lists its fields once (`FieldSet`), and the same list writes and reads them. Missing keys keep their defaults, so recordings made before a new threshold existed still replay; unreadable JSON gives the default config.

**Files:**
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/config/JsonFields.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/config/ConfigJson.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/config/ConfigJsonTest.kt`

**Interfaces:**
- Consumes: `TuningParams` and its groups, `RadarMount`, `PipelineConfig`, `defaultMounts`, `mountsFromMeasuredYaws` (Task 2); `MiniJson` (Task 4c).
- Produces (`io.github.santiquiroz.blindside.core.config`): `fun PipelineConfig.toJson(): String` (`{"tuning":{"decode":{…},"imu":{…},"motion":{…},"tracking":{…},"alerts":{…},"clock":{…},"status":{…}},"mounts":[…]}`, keys = Kotlin property names); `fun TuningParams.toJson(): String`; `fun pipelineConfigFromJson(json: String): PipelineConfig`; `fun pipelineConfigFromValue(value: Any?): PipelineConfig` (takes an already parsed `MiniJson` value; used by Task 17 for the header). Internal: `Field<P>`, `FieldSet<P>`, `fieldsOf`.

- [ ] **Step 1: Write the failing tests**

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/config/ConfigJsonTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.config

import io.github.santiquiroz.blindside.core.protocol.MiniJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigJsonTest {
    @Test
    fun `the default config round trips`() {
        val config = PipelineConfig()

        assertEquals(config, pipelineConfigFromJson(config.toJson()))
    }

    @Test
    fun `a tuned config with flipped mounts round trips`() {
        val tuning = TuningParams(imu = ImuParams(radarImuDelayMs = 40), alerts = AlertParams(minGapMs = 1_200))
        val mounts = mountsFromMeasuredYaws(Handedness.LEFT, -25.0, 37.5).map { it.copy(flipX = true, speedSign = -1) }
        val config = PipelineConfig(tuning, mounts)

        assertEquals(config, pipelineConfigFromJson(config.toJson()))
    }

    @Test
    fun `the json is a plain object with one key per parameter group`() {
        val root = MiniJson.parse(PipelineConfig().toJson()) as Map<*, *>
        val tuning = root["tuning"] as Map<*, *>

        assertEquals(setOf("decode", "imu", "motion", "tracking", "alerts", "clock", "status"), tuning.keys)
        assertEquals(100.0, (tuning["imu"] as Map<*, *>)["radarImuDelayMs"])
        assertEquals(listOf(320.0, 360.0), (tuning["decode"] as Map<*, *>)["validResolutionsMm"])
    }

    @Test
    fun `every property of every parameter group is written`() {
        val tuning = (MiniJson.parse(PipelineConfig().toJson()) as Map<*, *>)["tuning"] as Map<*, *>
        val groups = mapOf(
            "decode" to DecodeParams::class.java, "imu" to ImuParams::class.java, "motion" to MotionParams::class.java,
            "tracking" to TrackingParams::class.java, "alerts" to AlertParams::class.java, "clock" to ClockParams::class.java,
            "status" to StatusParams::class.java,
        )

        groups.forEach { (name, type) -> assertEquals(type.declaredFields.size, (tuning[name] as Map<*, *>).size, name) }
    }

    @Test
    fun `missing keys keep their defaults`() {
        val config = pipelineConfigFromJson("""{"tuning":{"imu":{"radarImuDelayMs":70}}}""")

        assertEquals(PipelineConfig(TuningParams(imu = ImuParams(radarImuDelayMs = 70))), config)
    }

    @Test
    fun `unreadable json gives the default config`() {
        assertEquals(PipelineConfig(), pipelineConfigFromJson("{broken"))
        assertTrue(pipelineConfigFromValue(null).mounts.isNotEmpty())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.config.ConfigJsonTest")`

Expected: FAIL. `:radar-core:compileTestKotlin` reports `Unresolved reference` for `toJson`, `pipelineConfigFromJson`, `pipelineConfigFromValue`.

- [ ] **Step 3: Write the implementation**

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/config/JsonFields.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.config

internal class Field<P>(
    val name: String,
    val write: (P) -> String,
    val read: (P, Any) -> P,
)

internal class FieldSet<P> {
    fun double(name: String, get: (P) -> Double, set: (P, Double) -> P): Field<P> =
        Field<P>(name, { get(it).toString() }) { p, v -> (v as? Double)?.let { set(p, it) } ?: p }

    fun long(name: String, get: (P) -> Long, set: (P, Long) -> P): Field<P> =
        Field<P>(name, { get(it).toString() }) { p, v -> (v as? Double)?.let { set(p, it.toLong()) } ?: p }

    fun int(name: String, get: (P) -> Int, set: (P, Int) -> P): Field<P> =
        Field<P>(name, { get(it).toString() }) { p, v -> (v as? Double)?.let { set(p, it.toInt()) } ?: p }

    fun boolean(name: String, get: (P) -> Boolean, set: (P, Boolean) -> P): Field<P> =
        Field<P>(name, { get(it).toString() }) { p, v -> (v as? Boolean)?.let { set(p, it) } ?: p }

    fun intSet(name: String, get: (P) -> Set<Int>, set: (P, Set<Int>) -> P): Field<P> =
        Field<P>(name, { get(it).sorted().joinToString(",", "[", "]") }) { p, v ->
            (v as? List<*>)?.let { items -> set(p, items.mapNotNull { (it as? Double)?.toInt() }.toSet()) } ?: p
        }
}

internal fun <P> fieldsOf(block: FieldSet<P>.() -> List<Field<P>>): List<Field<P>> = FieldSet<P>().block()
```

When a later change adds a field to a parameter group in `TuningParams.kt`, add its line to the matching list in `ConfigJson.kt`; `every property of every parameter group is written` fails until you do.

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/config/ConfigJson.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.config

import io.github.santiquiroz.blindside.core.protocol.MiniJson

fun PipelineConfig.toJson(): String = MiniJson.obj(
    listOf(
        "tuning" to tuning.toJson(),
        "mounts" to MiniJson.array(mounts.map { writeSection(it, MOUNT_FIELDS) }),
    ),
)

fun TuningParams.toJson(): String = MiniJson.obj(
    listOf(
        "decode" to writeSection(decode, DECODE_FIELDS),
        "imu" to writeSection(imu, IMU_FIELDS),
        "motion" to writeSection(motion, MOTION_FIELDS),
        "tracking" to writeSection(tracking, TRACKING_FIELDS),
        "alerts" to writeSection(alerts, ALERT_FIELDS),
        "clock" to writeSection(clock, CLOCK_FIELDS),
        "status" to writeSection(status, STATUS_FIELDS),
    ),
)

fun pipelineConfigFromJson(json: String): PipelineConfig = pipelineConfigFromValue(MiniJson.parseOrNull(json))

// Missing keys keep their defaults, so recordings made with an older TuningParams still replay.
fun pipelineConfigFromValue(value: Any?): PipelineConfig {
    val root = value as? Map<*, *> ?: return PipelineConfig()
    val mounts = (root["mounts"] as? List<*>).orEmpty().map { readSection(EMPTY_MOUNT, it, MOUNT_FIELDS) }
    return PipelineConfig(tuning = tuningFrom(root["tuning"]), mounts = mounts.ifEmpty { defaultMounts(Handedness.RIGHT) })
}

private fun tuningFrom(value: Any?): TuningParams {
    val root = value as? Map<*, *> ?: return TuningParams()
    val defaults = TuningParams()
    return TuningParams(
        decode = readSection(defaults.decode, root["decode"], DECODE_FIELDS),
        imu = readSection(defaults.imu, root["imu"], IMU_FIELDS),
        motion = readSection(defaults.motion, root["motion"], MOTION_FIELDS),
        tracking = readSection(defaults.tracking, root["tracking"], TRACKING_FIELDS),
        alerts = readSection(defaults.alerts, root["alerts"], ALERT_FIELDS),
        clock = readSection(defaults.clock, root["clock"], CLOCK_FIELDS),
        status = readSection(defaults.status, root["status"], STATUS_FIELDS),
    )
}

private fun <P> writeSection(params: P, fields: List<Field<P>>): String =
    MiniJson.obj(fields.map { it.name to it.write(params) })

private fun <P> readSection(defaults: P, value: Any?, fields: List<Field<P>>): P {
    val map = value as? Map<*, *> ?: return defaults
    return fields.fold(defaults) { params, field -> map[field.name]?.let { field.read(params, it) } ?: params }
}

private val EMPTY_MOUNT = RadarMount(radarId = 0, xM = 0.0, yM = 0.0, yawDeg = 0.0)

private val DECODE_FIELDS: List<Field<DecodeParams>> = fieldsOf {
    listOf(
        double("maxAbsAngleDeg", { it.maxAbsAngleDeg }) { p, v -> p.copy(maxAbsAngleDeg = v) },
        double("maxSpeedMps", { it.maxSpeedMps }) { p, v -> p.copy(maxSpeedMps = v) },
        intSet("validResolutionsMm", { it.validResolutionsMm }) { p, v -> p.copy(validResolutionsMm = v) },
        int("staleRepeatFrames", { it.staleRepeatFrames }) { p, v -> p.copy(staleRepeatFrames = v) },
        double("nearFieldM", { it.nearFieldM }) { p, v -> p.copy(nearFieldM = v) },
        double("coneHalfAngleDeg", { it.coneHalfAngleDeg }) { p, v -> p.copy(coneHalfAngleDeg = v) },
        double("maxRangeM", { it.maxRangeM }) { p, v -> p.copy(maxRangeM = v) },
    )
}

private val IMU_FIELDS: List<Field<ImuParams>> = fieldsOf {
    listOf(
        int("bootWindowSamples", { it.bootWindowSamples }) { p, v -> p.copy(bootWindowSamples = v) },
        int("restWindowSamples", { it.restWindowSamples }) { p, v -> p.copy(restWindowSamples = v) },
        double("stillMaxSpreadDps", { it.stillMaxSpreadDps }) { p, v -> p.copy(stillMaxSpreadDps = v) },
        double("stillMaxAccelStdG", { it.stillMaxAccelStdG }) { p, v -> p.copy(stillMaxAccelStdG = v) },
        double("defectiveMeanDps", { it.defectiveMeanDps }) { p, v -> p.copy(defectiveMeanDps = v) },
        double("reseedMinOffsetDps", { it.reseedMinOffsetDps }) { p, v -> p.copy(reseedMinOffsetDps = v) },
        double("restBiasTauS", { it.restBiasTauS }) { p, v -> p.copy(restBiasTauS = v) },
        double("witnessMaxDps", { it.witnessMaxDps }) { p, v -> p.copy(witnessMaxDps = v) },
        int("witnessMinSamples", { it.witnessMinSamples }) { p, v -> p.copy(witnessMinSamples = v) },
        long("witnessHistoryMs", { it.witnessHistoryMs }) { p, v -> p.copy(witnessHistoryMs = v) },
        double("gravityTauS", { it.gravityTauS }) { p, v -> p.copy(gravityTauS = v) },
        double("gravityRejectG", { it.gravityRejectG }) { p, v -> p.copy(gravityRejectG = v) },
        double("proneAngleDeg", { it.proneAngleDeg }) { p, v -> p.copy(proneAngleDeg = v) },
        double("gyroScale", { it.gyroScale }) { p, v -> p.copy(gyroScale = v) },
        long("radarImuDelayMs", { it.radarImuDelayMs }) { p, v -> p.copy(radarImuDelayMs = v) },
        long("yawHistoryMs", { it.yawHistoryMs }) { p, v -> p.copy(yawHistoryMs = v) },
        long("yawExtrapolationMaxMs", { it.yawExtrapolationMaxMs }) { p, v -> p.copy(yawExtrapolationMaxMs = v) },
        int("yawRateSamplesForExtrapolation", { it.yawRateSamplesForExtrapolation }) { p, v -> p.copy(yawRateSamplesForExtrapolation = v) },
        double("yawBlendTauMs", { it.yawBlendTauMs }) { p, v -> p.copy(yawBlendTauMs = v) },
    )
}

private val MOTION_FIELDS: List<Field<MotionParams>> = fieldsOf {
    listOf(
        double("turningRateDps", { it.turningRateDps }) { p, v -> p.copy(turningRateDps = v) },
        double("turningTauS", { it.turningTauS }) { p, v -> p.copy(turningTauS = v) },
        double("walkingAccelStdG", { it.walkingAccelStdG }) { p, v -> p.copy(walkingAccelStdG = v) },
        long("walkingWindowMs", { it.walkingWindowMs }) { p, v -> p.copy(walkingWindowMs = v) },
        long("stepHoldMs", { it.stepHoldMs }) { p, v -> p.copy(stepHoldMs = v) },
        double("stepRiseG", { it.stepRiseG }) { p, v -> p.copy(stepRiseG = v) },
        double("stepResetG", { it.stepResetG }) { p, v -> p.copy(stepResetG = v) },
        long("stepMinIntervalMs", { it.stepMinIntervalMs }) { p, v -> p.copy(stepMinIntervalMs = v) },
        long("watchMaxGapMs", { it.watchMaxGapMs }) { p, v -> p.copy(watchMaxGapMs = v) },
    )
}

private val TRACKING_FIELDS: List<Field<TrackingParams>> = fieldsOf {
    listOf(
        long("windowMs", { it.windowMs }) { p, v -> p.copy(windowMs = v) },
        double("processNoise", { it.processNoise }) { p, v -> p.copy(processNoise = v) },
        double("sigmaRangeM", { it.sigmaRangeM }) { p, v -> p.copy(sigmaRangeM = v) },
        double("sigmaAngleDeg", { it.sigmaAngleDeg }) { p, v -> p.copy(sigmaAngleDeg = v) },
        double("maxAngleForNoiseDeg", { it.maxAngleForNoiseDeg }) { p, v -> p.copy(maxAngleForNoiseDeg = v) },
        double("overlapNoiseFactor", { it.overlapNoiseFactor }) { p, v -> p.copy(overlapNoiseFactor = v) },
        double("sigmaInitialSpeedMps", { it.sigmaInitialSpeedMps }) { p, v -> p.copy(sigmaInitialSpeedMps = v) },
        double("gateChi2", { it.gateChi2 }) { p, v -> p.copy(gateChi2 = v) },
        double("gateConfirmedM", { it.gateConfirmedM }) { p, v -> p.copy(gateConfirmedM = v) },
        double("gateTentativeM", { it.gateTentativeM }) { p, v -> p.copy(gateTentativeM = v) },
        int("confirmHits", { it.confirmHits }) { p, v -> p.copy(confirmHits = v) },
        int("confirmWindows", { it.confirmWindows }) { p, v -> p.copy(confirmWindows = v) },
        long("stopScanTailMs", { it.stopScanTailMs }) { p, v -> p.copy(stopScanTailMs = v) },
        int("tentativeMaxMisses", { it.tentativeMaxMisses }) { p, v -> p.copy(tentativeMaxMisses = v) },
        long("tentativeMaxSilenceMs", { it.tentativeMaxSilenceMs }) { p, v -> p.copy(tentativeMaxSilenceMs = v) },
        double("stillSpeedMps", { it.stillSpeedMps }) { p, v -> p.copy(stillSpeedMps = v) },
        long("coastStillMs", { it.coastStillMs }) { p, v -> p.copy(coastStillMs = v) },
        long("coastMovingMs", { it.coastMovingMs }) { p, v -> p.copy(coastMovingMs = v) },
        long("coastExitMs", { it.coastExitMs }) { p, v -> p.copy(coastExitMs = v) },
        long("outOfViewMs", { it.outOfViewMs }) { p, v -> p.copy(outOfViewMs = v) },
        double("coastGateSigmaM", { it.coastGateSigmaM }) { p, v -> p.copy(coastGateSigmaM = v) },
        double("coastSpeedTauS", { it.coastSpeedTauS }) { p, v -> p.copy(coastSpeedTauS = v) },
        double("inheritDistanceM", { it.inheritDistanceM }) { p, v -> p.copy(inheritDistanceM = v) },
        long("inheritMaxAgeMs", { it.inheritMaxAgeMs }) { p, v -> p.copy(inheritMaxAgeMs = v) },
    )
}

private val ALERT_FIELDS: List<Field<AlertParams>> = fieldsOf {
    listOf(
        double("centerHalfWidthDeg", { it.centerHalfWidthDeg }) { p, v -> p.copy(centerHalfWidthDeg = v) },
        long("minGapMs", { it.minGapMs }) { p, v -> p.copy(minGapMs = v) },
        long("sectorPauseMs", { it.sectorPauseMs }) { p, v -> p.copy(sectorPauseMs = v) },
        double("reacquireDistanceM", { it.reacquireDistanceM }) { p, v -> p.copy(reacquireDistanceM = v) },
        int("maxPerMinute", { it.maxPerMinute }) { p, v -> p.copy(maxPerMinute = v) },
        long("systemPatternMs", { it.systemPatternMs }) { p, v -> p.copy(systemPatternMs = v) },
    )
}

private val CLOCK_FIELDS: List<Field<ClockParams>> = fieldsOf {
    listOf(
        long("windowMs", { it.windowMs }) { p, v -> p.copy(windowMs = v) },
        int("maxWindows", { it.maxWindows }) { p, v -> p.copy(maxWindows = v) },
        double("maxDriftPpm", { it.maxDriftPpm }) { p, v -> p.copy(maxDriftPpm = v) },
    )
}

private val STATUS_FIELDS: List<Field<StatusParams>> = fieldsOf {
    listOf(
        long("linkStaleMs", { it.linkStaleMs }) { p, v -> p.copy(linkStaleMs = v) },
        long("corruptWindowMs", { it.corruptWindowMs }) { p, v -> p.copy(corruptWindowMs = v) },
        int("corruptEventsThreshold", { it.corruptEventsThreshold }) { p, v -> p.copy(corruptEventsThreshold = v) },
    )
}

private val MOUNT_FIELDS: List<Field<RadarMount>> = fieldsOf {
    listOf(
        int("radarId", { it.radarId }) { p, v -> p.copy(radarId = v) },
        double("xM", { it.xM }) { p, v -> p.copy(xM = v) },
        double("yM", { it.yM }) { p, v -> p.copy(yM = v) },
        double("yawDeg", { it.yawDeg }) { p, v -> p.copy(yawDeg = v) },
        boolean("flipX", { it.flipX }) { p, v -> p.copy(flipX = v) },
        int("speedSign", { it.speedSign }) { p, v -> p.copy(speedSign = v) },
    )
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.config.ConfigJsonTest")`

Expected: PASS, 6 tests, 0 failures. Then run the whole module (`(cd watch && ./gradlew :radar-core:test)`) and confirm every earlier test still passes.

- [ ] **Step 5: Commit**

```bash
git add watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/config/JsonFields.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/config/ConfigJson.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/config/ConfigJsonTest.kt
git commit -m "feat: PipelineConfig a JSON y de vuelta para la cabecera del .bsrec"
```

### Task 5: Geometry: frames, cones and the per-frame filter

Spec §6.1 steps 1-3 and §3.1. Plausibility drops a target if Y ≤ 0, |X| > Y·tan 65°, |v| > 10 m/s or the resolution is not 320/360 mm; empty `(0, 0, 0)` slots are dropped without counting as implausible; a target whose `(x, y, v)` equals any slot of the previous frame of the same radar for ≥ 3 consecutive frames is stale; detections closer than 0.8 m to the radar are excluded. `canReportMiss` is the spec §6.5 "valid frame with at least one `(0, 0, 0)` slot" used to blame a track for a miss; stale, implausible and near-field targets keep their slot occupied but never stop the frame from reporting a miss. Rotations use the project bearing convention (clockwise positive).

**Files:**
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/geometry/Point2.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/geometry/BodyGeometry.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/geometry/FrameFilter.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/geometry/BodyGeometryTest.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/geometry/FrameFilterTest.kt`

**Interfaces:**
- Consumes: `RadarMount`, `DecodeParams`, `defaultMounts` (Task 2); `RadarFrame`, `RawTarget`, `Ld2450Codec` (Tasks 3-4).
- Produces (`io.github.santiquiroz.blindside.core.geometry`): `data class Point2(x, y) { plus, minus, times(Double), norm, bearingDeg, fun rotateClockwise(angleDeg: Double): Point2; companion ZERO, fromPolar(rangeM, bearingDeg) }`; `fun wrapDeg(angleDeg: Double): Double`; `fun RadarMount.position(): Point2`; `fun radarToBody(radarPoint, mount): Point2`; `fun bodyToRadar(bodyPoint, mount): Point2`; `fun bodyToTracking(bodyPoint, yawDeg): Point2`; `fun trackingToBody(trackingPoint, yawDeg): Point2`; `fun isInCone(bodyPoint, mount, params: DecodeParams, marginDeg = 0.0): Boolean`; `fun coveringRadars(bodyPoint, mounts, params, marginDeg = 0.0): Set<Int>`; `data class Detection(radarId, tMs, radarPoint, bodyPoint, radialSpeedMps) { radarRangeM, radarBearingDeg }`; `data class StaleMemory(previous: List<SeenTarget>)`; `data class FilteredFrame(radarId, tMs, detections, occupiedSlots, implausible, stale, nearField) { val canReportMiss }`; `data class FrameFilterResult(frame: FilteredFrame, memory: StaleMemory)`; `fun isPlausible(target, params): Boolean`; `fun toDetection(target, radarId, tMs, mount): Detection`; `fun filterFrame(frame: RadarFrame, mount, memory: StaleMemory, params: DecodeParams): FrameFilterResult`.

- [ ] **Step 1: Write the failing tests**

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/geometry/BodyGeometryTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.geometry

import io.github.santiquiroz.blindside.core.config.DecodeParams
import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.RadarMount
import io.github.santiquiroz.blindside.core.config.defaultMounts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BodyGeometryTest {
    private val params = DecodeParams()

    @Test
    fun `bearing is clockwise from straight ahead`() {
        assertEquals(0.0, Point2(0.0, 2.0).bearingDeg, 1e-9)
        assertEquals(90.0, Point2(2.0, 0.0).bearingDeg, 1e-9)
        assertEquals(-45.0, Point2(-1.0, 1.0).bearingDeg, 1e-9)
    }

    @Test
    fun `a radar yawed 90 degrees right sees its boresight on the body right`() {
        val mount = RadarMount(0, 0.0, 0.0, yawDeg = 90.0)

        val body = radarToBody(Point2(0.0, 1.0), mount)

        assertEquals(1.0, body.x, 1e-9)
        assertEquals(0.0, body.y, 1e-9)
    }

    @Test
    fun `radar to body adds the mount position and round trips`() {
        val mount = RadarMount(1, 0.15, 0.0, yawDeg = 20.0)
        val radarPoint = Point2(0.3, 2.5)

        val back = bodyToRadar(radarToBody(radarPoint, mount), mount)

        assertEquals(radarPoint.x, back.x, 1e-9)
        assertEquals(radarPoint.y, back.y, 1e-9)
    }

    @Test
    fun `tracking frame rotates by yaw and back`() {
        val body = Point2(0.0, 3.0)

        val tracking = bodyToTracking(body, yawDeg = 90.0)

        assertEquals(90.0, tracking.bearingDeg, 1e-9)
        assertEquals(0.0, trackingToBody(tracking, 90.0).bearingDeg, 1e-9)
    }

    @Test
    fun `right handed cones cover minus 100 to plus 80 degrees`() {
        val mounts = defaultMounts(Handedness.RIGHT)

        assertEquals(setOf(0), coveringRadars(Point2.fromPolar(3.0, -90.0), mounts, params))
        assertEquals(setOf(0, 1), coveringRadars(Point2.fromPolar(3.0, -10.0), mounts, params))
        assertEquals(setOf(1), coveringRadars(Point2.fromPolar(3.0, 70.0), mounts, params))
        assertTrue(coveringRadars(Point2.fromPolar(3.0, 120.0), mounts, params).isEmpty())
        assertTrue(coveringRadars(Point2.fromPolar(7.0, 0.0), mounts, params).isEmpty())
    }

    @Test
    fun `a margin shrinks the cone`() {
        val mount = RadarMount(0, 0.0, 0.0, yawDeg = 0.0)

        assertTrue(isInCone(Point2.fromPolar(3.0, 55.0), mount, params))
        assertFalse(isInCone(Point2.fromPolar(3.0, 55.0), mount, params, marginDeg = 10.0))
    }

    @Test
    fun `wrapDeg folds into minus 180 to 180`() {
        assertEquals(-170.0, wrapDeg(190.0), 1e-9)
        assertEquals(-180.0, wrapDeg(180.0), 1e-9)
        assertEquals(10.0, wrapDeg(-350.0), 1e-9)
    }
}
```

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/geometry/FrameFilterTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.geometry

import io.github.santiquiroz.blindside.core.config.DecodeParams
import io.github.santiquiroz.blindside.core.config.RadarMount
import io.github.santiquiroz.blindside.core.protocol.RadarFrame
import io.github.santiquiroz.blindside.core.protocol.RawTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrameFilterTest {
    private val params = DecodeParams()
    private val mount = RadarMount(0, 0.0, 0.0, yawDeg = 0.0)
    private val empty = RawTarget(0, 0, 0, 0)

    @Test
    fun `plausibility rejects behind, too wide, too fast and unknown resolution`() {
        assertTrue(isPlausible(RawTarget(500, 3000, 25, 360), params))
        assertFalse(isPlausible(RawTarget(500, -10, 25, 360), params))
        assertFalse(isPlausible(RawTarget(3000, 1000, 25, 360), params))
        assertFalse(isPlausible(RawTarget(0, 3000, 1200, 360), params))
        assertFalse(isPlausible(RawTarget(0, 3000, 10, 500), params))
    }

    @Test
    fun `empty slots are dropped and do not count as implausible`() {
        val result = filter(listOf(RawTarget(0, 2000, -30, 360), empty, empty))

        assertEquals(1, result.frame.detections.size)
        assertEquals(1, result.frame.occupiedSlots)
        assertEquals(0, result.frame.implausible)
        assertTrue(result.frame.canReportMiss)
    }

    @Test
    fun `implausible targets are counted and still occupy their slot`() {
        val result = filter(listOf(RawTarget(0, 2000, -30, 360), RawTarget(0, -5, 0, 360), RawTarget(0, 3000, 2000, 360)))

        assertEquals(1, result.frame.detections.size)
        assertEquals(2, result.frame.implausible)
        assertEquals(3, result.frame.occupiedSlots)
        assertFalse(result.frame.canReportMiss)
    }

    @Test
    fun `a target repeated in three consecutive frames goes stale in any slot and its frame can still report a miss`() {
        val stuck = RawTarget(-400, 2500, 12, 360)
        val first = filter(listOf(stuck, empty, empty))
        val second = filter(listOf(empty, stuck, empty), first.memory)
        val third = filter(listOf(RawTarget(100, 1500, -40, 360), empty, stuck), second.memory)

        assertEquals(1, second.frame.detections.size)
        assertEquals(1, third.frame.detections.size)
        assertEquals(1, third.frame.stale)
        assertEquals(1500.0 / 1000.0, third.frame.detections.single().radarPoint.y, 1e-9)
        assertTrue(third.frame.canReportMiss)
    }

    @Test
    fun `a changing target never goes stale`() {
        val first = filter(listOf(RawTarget(0, 2000, -30, 360), empty, empty))
        val second = filter(listOf(RawTarget(0, 1970, -30, 360), empty, empty), first.memory)
        val third = filter(listOf(RawTarget(0, 1940, -30, 360), empty, empty), second.memory)

        assertEquals(0, third.frame.stale)
    }

    @Test
    fun `near field targets closer than 0_8 m are excluded`() {
        val result = filter(listOf(RawTarget(100, 700, -30, 360), RawTarget(0, 900, -30, 360), empty))

        assertEquals(1, result.frame.nearField)
        assertEquals(listOf(0.9), result.frame.detections.map { it.radarPoint.y })
    }

    @Test
    fun `flipX and speedSign are applied when converting to a detection`() {
        val flipped = RadarMount(1, 0.15, 0.0, yawDeg = 0.0, flipX = true, speedSign = -1)

        val detection = toDetection(RawTarget(500, 2000, 25, 360), 1, 10, flipped)

        assertEquals(-0.5, detection.radarPoint.x, 1e-9)
        assertEquals(-0.25, detection.radialSpeedMps, 1e-9)
        assertEquals(-0.35, detection.bodyPoint.x, 1e-9)
    }

    private fun filter(targets: List<RawTarget>, memory: StaleMemory = StaleMemory()) =
        filterFrame(RadarFrame(0, 100, targets), mount, memory, params)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.geometry.*")`

Expected: FAIL. `:radar-core:compileTestKotlin` reports `Unresolved reference` for `Point2`, `radarToBody`, `filterFrame`, `isPlausible`.

- [ ] **Step 3: Write the implementation**

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/geometry/Point2.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.geometry

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class Point2(val x: Double, val y: Double) {
    operator fun plus(other: Point2) = Point2(x + other.x, y + other.y)
    operator fun minus(other: Point2) = Point2(x - other.x, y - other.y)
    operator fun times(factor: Double) = Point2(x * factor, y * factor)
    val norm: Double get() = hypot(x, y)

    // Bearing convention of the whole project: 0 = straight ahead (+y), positive = clockwise (to the right).
    val bearingDeg: Double get() = Math.toDegrees(atan2(x, y))

    fun rotateClockwise(angleDeg: Double): Point2 {
        val a = Math.toRadians(angleDeg)
        return Point2(x * cos(a) + y * sin(a), -x * sin(a) + y * cos(a))
    }

    companion object {
        val ZERO = Point2(0.0, 0.0)

        fun fromPolar(rangeM: Double, bearingDeg: Double): Point2 {
            val a = Math.toRadians(bearingDeg)
            return Point2(rangeM * sin(a), rangeM * cos(a))
        }
    }
}

fun wrapDeg(angleDeg: Double): Double {
    val wrapped = (angleDeg + 180.0) % 360.0
    return (if (wrapped < 0) wrapped + 360.0 else wrapped) - 180.0
}
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/geometry/BodyGeometry.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.geometry

import io.github.santiquiroz.blindside.core.config.DecodeParams
import io.github.santiquiroz.blindside.core.config.RadarMount
import kotlin.math.abs

fun RadarMount.position(): Point2 = Point2(xM, yM)

fun radarToBody(radarPoint: Point2, mount: RadarMount): Point2 =
    mount.position() + radarPoint.rotateClockwise(mount.yawDeg)

fun bodyToRadar(bodyPoint: Point2, mount: RadarMount): Point2 =
    (bodyPoint - mount.position()).rotateClockwise(-mount.yawDeg)

fun bodyToTracking(bodyPoint: Point2, yawDeg: Double): Point2 = bodyPoint.rotateClockwise(yawDeg)

fun trackingToBody(trackingPoint: Point2, yawDeg: Double): Point2 = trackingPoint.rotateClockwise(-yawDeg)

fun isInCone(bodyPoint: Point2, mount: RadarMount, params: DecodeParams, marginDeg: Double = 0.0): Boolean {
    val local = bodyToRadar(bodyPoint, mount)
    return local.y > 0 && local.norm <= params.maxRangeM && abs(local.bearingDeg) <= params.coneHalfAngleDeg - marginDeg
}

fun coveringRadars(bodyPoint: Point2, mounts: List<RadarMount>, params: DecodeParams, marginDeg: Double = 0.0): Set<Int> =
    mounts.filter { isInCone(bodyPoint, it, params, marginDeg) }.map { it.radarId }.toSet()
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/geometry/FrameFilter.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.geometry

import io.github.santiquiroz.blindside.core.config.DecodeParams
import io.github.santiquiroz.blindside.core.config.RadarMount
import io.github.santiquiroz.blindside.core.protocol.Ld2450Codec
import io.github.santiquiroz.blindside.core.protocol.RadarFrame
import io.github.santiquiroz.blindside.core.protocol.RawTarget
import kotlin.math.abs
import kotlin.math.tan

data class Detection(
    val radarId: Int,
    val tMs: Long,
    val radarPoint: Point2,
    val bodyPoint: Point2,
    val radialSpeedMps: Double,
) {
    val radarRangeM: Double get() = radarPoint.norm
    val radarBearingDeg: Double get() = radarPoint.bearingDeg
}

data class SeenTarget(val xMm: Int, val yMm: Int, val speedCms: Int, val repeats: Int)

data class StaleMemory(val previous: List<SeenTarget> = emptyList())

data class FilteredFrame(
    val radarId: Int,
    val tMs: Long,
    val detections: List<Detection>,
    val occupiedSlots: Int,
    val implausible: Int,
    val stale: Int,
    val nearField: Int,
) {
    // Spec §6.5: only an empty (0, 0, 0) slot is free; stale, implausible and excluded targets still occupy theirs.
    val canReportMiss: Boolean get() = occupiedSlots < Ld2450Codec.TARGET_COUNT
}

data class FrameFilterResult(val frame: FilteredFrame, val memory: StaleMemory)

fun isPlausible(target: RawTarget, params: DecodeParams): Boolean =
    target.yMm > 0 &&
        abs(target.xMm) <= target.yMm * tan(Math.toRadians(params.maxAbsAngleDeg)) &&
        abs(target.speedCms) <= params.maxSpeedMps * 100.0 &&
        target.resolutionMm in params.validResolutionsMm

fun markRepeats(targets: List<RawTarget>, memory: StaleMemory): List<SeenTarget> = targets.map { target ->
    val previous = memory.previous.firstOrNull { it.matches(target) }
    SeenTarget(target.xMm, target.yMm, target.speedCms, (previous?.repeats ?: 0) + 1)
}

fun toDetection(target: RawTarget, radarId: Int, tMs: Long, mount: RadarMount): Detection {
    val sign = if (mount.flipX) -1.0 else 1.0
    val radarPoint = Point2(sign * target.xMm / 1000.0, target.yMm / 1000.0)
    return Detection(radarId, tMs, radarPoint, radarToBody(radarPoint, mount), mount.speedSign * target.speedCms / 100.0)
}

fun filterFrame(frame: RadarFrame, mount: RadarMount, memory: StaleMemory, params: DecodeParams): FrameFilterResult {
    val occupied = frame.targets.filterNot { it.isEmpty }
    val plausible = occupied.filter { isPlausible(it, params) }
    val seen = markRepeats(plausible, memory)
    val fresh = plausible.filterIndexed { index, _ -> seen[index].repeats < params.staleRepeatFrames }
    val detections = fresh.map { toDetection(it, frame.radarId, frame.tMs, mount) }
    val kept = detections.filter { it.radarRangeM >= params.nearFieldM }
    val filtered = FilteredFrame(
        radarId = frame.radarId,
        tMs = frame.tMs,
        detections = kept,
        occupiedSlots = occupied.size,
        implausible = occupied.size - plausible.size,
        stale = plausible.size - fresh.size,
        nearField = detections.size - kept.size,
    )
    return FrameFilterResult(filtered, StaleMemory(seen))
}

private fun SeenTarget.matches(target: RawTarget) =
    xMm == target.xMm && yMm == target.yMm && speedCms == target.speedCms
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.geometry.*")`

Expected: PASS, 14 tests, 0 failures. Then run the whole module (`(cd watch && ./gradlew :radar-core:test)`) and confirm every earlier test still passes.

- [ ] **Step 5: Commit**

```bash
git add watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/geometry/Point2.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/geometry/BodyGeometry.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/geometry/FrameFilter.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/geometry/BodyGeometryTest.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/geometry/FrameFilterTest.kt
git commit -m "feat: geometría radar-cuerpo, plausibilidad, objetivos rancios y campo cercano"
```

### Task 6: Clock mapping ESP32 → watch

Spec §6.3 "Mapeo de relojes": offset = arrival − t_esp; the minimum per 10 s window removes the variable BLE delay; the slope between the oldest and newest window minima (up to 6 windows, clamped to ±200 ppm) gives the linear drift. The pipeline replaces the mapper with a fresh one on every (re)connection, when `t_ms` goes backwards and when `info` brings a new `boot_id` (Task 16a).

**Files:**
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/clock/ClockMapper.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/clock/ClockMapperTest.kt`

**Interfaces:**
- Consumes: `ClockParams` (Task 2).
- Produces (`io.github.santiquiroz.blindside.core.clock`): `data class OffsetPoint(tMs: Long, offsetNanos: Long)`; `data class ClockMapper(params: ClockParams = ClockParams(), completed, current, currentWindow) { val isReady; fun observe(tMs: Long, arrivalNanos: Long): ClockMapper; fun toNanos(tMs: Long): Long?; fun toEspMs(nanos: Long): Long?; fun driftNanosPerMs(): Double; companion NANOS_PER_MS }`.

- [ ] **Step 1: Write the failing tests**

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/clock/ClockMapperTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.clock

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

class ClockMapperTest {
    private val trueOffsetNanos = 7_000_000_000_000L
    private val ms = ClockMapper.NANOS_PER_MS

    @Test
    fun `an empty mapper cannot map`() {
        val mapper = ClockMapper()

        assertFalse(mapper.isReady)
        assertNull(mapper.toNanos(100))
        assertNull(mapper.toEspMs(100))
    }

    @Test
    fun `minimum filter removes variable BLE delay`() {
        val random = Random(7)
        val mapper = (0 until 300).fold(ClockMapper()) { acc, i ->
            val tMs = 1_000L + i * 100
            val delayMs = 5 + random.nextInt(55)
            acc.observe(tMs, tMs * ms + trueOffsetNanos + delayMs * ms)
        }

        val errorMs = (mapper.toNanos(31_000)!! - (31_000 * ms + trueOffsetNanos)) / ms.toDouble()

        assertTrue(errorMs in 4.0..7.0, "error was $errorMs ms")
    }

    @Test
    fun `linear drift of 100 ppm is followed`() {
        val random = Random(3)
        val mapper = (0 until 700).fold(ClockMapper()) { acc, i ->
            val tMs = 1_000L + i * 100
            acc.observe(tMs, phoneNanos(tMs, driftPpm = 100.0) + (5 + random.nextInt(35)) * ms)
        }

        val errorMs = (mapper.toNanos(71_000)!! - phoneNanos(71_000, 100.0)) / ms.toDouble()

        assertTrue(abs(errorMs - 5.0) < 2.0, "error was $errorMs ms")
        assertEquals(100.0, mapper.driftNanosPerMs(), 10.0)
    }

    @Test
    fun `esp time and phone time convert back and forth`() {
        val mapper = ClockMapper().observe(5_000, 5_000 * ms + trueOffsetNanos)

        val nanos = mapper.toNanos(5_250)!!

        assertEquals(5_250L, mapper.toEspMs(nanos))
    }

    @Test
    fun `a sample from an older window is ignored`() {
        val mapper = ClockMapper().observe(25_000, 25_000 * ms + trueOffsetNanos)

        assertEquals(mapper, mapper.observe(1_000, 0))
    }

    private fun phoneNanos(tMs: Long, driftPpm: Double): Long =
        trueOffsetNanos + (tMs * ms * (1.0 + driftPpm * 1e-6)).toLong()
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.clock.*")`

Expected: FAIL. `:radar-core:compileTestKotlin` reports `Unresolved reference` for `ClockMapper`.

- [ ] **Step 3: Write the implementation**

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/clock/ClockMapper.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.clock

import io.github.santiquiroz.blindside.core.config.ClockParams
import kotlin.math.roundToLong

data class OffsetPoint(val tMs: Long, val offsetNanos: Long)

data class ClockMapper(
    val params: ClockParams = ClockParams(),
    val completed: List<OffsetPoint> = emptyList(),
    val current: OffsetPoint? = null,
    val currentWindow: Long? = null,
) {
    val isReady: Boolean get() = current != null

    fun observe(tMs: Long, arrivalNanos: Long): ClockMapper {
        val point = OffsetPoint(tMs, arrivalNanos - tMs * NANOS_PER_MS)
        val window = tMs / params.windowMs
        return when {
            current == null || currentWindow == null -> copy(current = point, currentWindow = window)
            window == currentWindow -> copy(current = minOf(current, point))
            window > currentWindow -> copy(completed = (completed + current).takeLast(params.maxWindows), current = point, currentWindow = window)
            else -> this
        }
    }

    fun toNanos(tMs: Long): Long? {
        val anchor = anchor() ?: return null
        return tMs * NANOS_PER_MS + anchor.offsetNanos + (driftNanosPerMs() * (tMs - anchor.tMs)).roundToLong()
    }

    fun toEspMs(nanos: Long): Long? {
        val anchor = anchor() ?: return null
        val drift = driftNanosPerMs()
        return ((nanos - anchor.offsetNanos + drift * anchor.tMs) / (NANOS_PER_MS + drift)).toLong()
    }

    fun driftNanosPerMs(): Double {
        if (completed.size < 2) return 0.0
        val first = completed.first()
        val last = completed.last()
        val slope = (last.offsetNanos - first.offsetNanos).toDouble() / (last.tMs - first.tMs)
        return slope.coerceIn(-params.maxDriftPpm, params.maxDriftPpm)
    }

    private fun anchor(): OffsetPoint? {
        val now = current ?: return null
        val drift = driftNanosPerMs()
        return listOfNotNull(completed.lastOrNull(), now).minBy { it.offsetNanos + drift * (now.tMs - it.tMs) }
    }

    private fun minOf(a: OffsetPoint, b: OffsetPoint) = if (b.offsetNanos < a.offsetNanos) b else a

    companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.clock.*")`

Expected: PASS, 5 tests, 0 failures. Then run the whole module (`(cd watch && ./gradlew :radar-core:test)`) and confirm every earlier test still passes.

- [ ] **Step 5: Commit**

```bash
git add watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/clock/ClockMapper.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/clock/ClockMapperTest.kt
git commit -m "feat: mapeo de relojes ESP32-reloj con mínimo móvil y deriva"
```

### Task 7: IMU channel: watch witness, boot bias, rest recalibration, gravity, yaw rate

Spec §6.3 and the eight bias tests of spec §9. **Witness** (decision 4): the watch gyroscope, already drift-compensated by Android, tells a slow steady turn from a gyro offset; `RestEvidence.judge` returns MOVING (a step, or a watch sample > 3 °/s, inside the window), VERIFIED (≥ 5 still watch samples inside it) or UNVERIFIED (no watch data). **Boot bias:** a 2 s window (100 samples at 50 Hz) is accepted when every axis stays within 3 °/s of the window mean (spread, not magnitude, because the raw offset reaches ±20 °/s), σ(|a|) ≤ 0.02 g and the judge does not say MOVING; a still window whose mean exceeds 45 °/s on some axis marks the IMU DEFECTIVE, and later windows are still tried (decision 5). Accepted without the watch, the bias is "sin verificar". **Rest recalibration** on the rolling last 3 s (150 samples) with the same stillness criteria: |mean − b| < 3 °/s → EMA towards the mean with τ = 20 s; 3 ≤ |mean − b| < 45 °/s → re-seed `b` with the mean only if VERIFIED (counted in `reseeds`), otherwise keep `b` and flag it unverified. **Gravity:** LPF τ = 1 s, skipped when ||a| − 1 g| > 0.15 g. **Yaw rate:** `−ĝ·(ω − b)·scale`, positive = turning right, independent of how the chip sits in its box. **Prone:** gravity > 60° from the standing reference captured when the bias is accepted. **Scales:** the channel carries the LSB scales from `info` (decision 11); a new scale restarts the calibration.

**Files:**
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/imu/Vec3.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/imu/WatchWitness.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/imu/BiasWindows.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/imu/ImuChannel.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/imu/WatchWitnessTest.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/imu/ImuChannelTest.kt`

**Interfaces:**
- Consumes: `ImuParams` (Task 2); `ImuBatch`, `ImuSample`, `GYRO_LSB_PER_DPS`, `ACCEL_LSB_PER_G`, `IMU_SAMPLE_PERIOD_MS` (Task 4).
- Produces (`io.github.santiquiroz.blindside.core.imu`): `data class Vec3(x, y, z) { plus, minus, times, div, dot, norm, normalized(), maxAbs(), angleDegTo(o); companion ZERO }`; `data class ImuReading(tMs: Long, gyroDps: Vec3, accelG: Vec3)`; `data class TimedValue(tMs: Long, value: Double)`; `fun ImuBatch.readings(gyroLsbPerDps: Double = GYRO_LSB_PER_DPS, accelLsbPerG: Double = ACCEL_LSB_PER_G): List<ImuReading>`; `fun List<Vec3>.mean(): Vec3`; `fun List<Double>.standardDeviation(): Double`; `enum class Stillness { VERIFIED, UNVERIFIED, MOVING }`; `data class WatchWitness(samples: List<TimedValue>) { fun withSample(tMs, rateDps, params): WatchWitness; fun isMoving(fromMs, toMs, params): Boolean; fun hasCoverage(fromMs, toMs, params): Boolean }`; `data class RestEvidence(watch: WatchWitness = WatchWitness(), lastStepMs: Long? = null) { fun judge(fromMs, toMs, params): Stillness }`; `data class WindowStats(mean, spreadDps, accelStdG)`; `sealed interface BootVerdict { Accepted(bias, verified), Moving, Defective }`; `sealed interface RestVerdict { NotResting, Refine(mean, verified), Reseed(mean), Unverified }`; `fun windowStats(window): WindowStats`; `fun isStillWindow(stats, params): Boolean`; `fun evaluateBootWindow(window, stillness, params): BootVerdict`; `fun evaluateRestWindow(window, bias, stillness, params): RestVerdict`; `enum class BiasStatus { CALIBRATING, READY, DEFECTIVE }`; `data class ImuChannel(status, bias: Vec3?, biasVerified: Boolean, window: List<ImuReading>, gravity, standingUp, reseeds: Int, lastSums: List<Long>?, lastSampleMs: Long?, gyroLsbPerDps: Double, accelLsbPerG: Double) { val isReady; fun ingest(reading, evidence: RestEvidence, params): ImuChannel; fun withScales(gyroLsbPerDps, accelLsbPerG): ImuChannel; fun yawRateDps(gyroDps: Vec3, params): Double?; fun isProne(params): Boolean }`.

- [ ] **Step 1: Write the failing tests**

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/imu/WatchWitnessTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.config.ImuParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WatchWitnessTest {
    private val params = ImuParams()
    private val still = WatchWitness((0L..2_000L step 100).map { TimedValue(it, 0.4) })

    @Test
    fun `a still watch covering the window verifies it`() {
        assertEquals(Stillness.VERIFIED, RestEvidence(still).judge(0, 2_000, params))
    }

    @Test
    fun `one watch sample above 3 deg per s means the player moved`() {
        val turned = still.copy(samples = still.samples + TimedValue(1_050, 3.5))

        assertEquals(Stillness.MOVING, RestEvidence(turned).judge(0, 2_000, params))
    }

    @Test
    fun `no watch samples in the window leaves it unverified`() {
        assertEquals(Stillness.UNVERIFIED, RestEvidence().judge(0, 2_000, params))
        assertEquals(Stillness.UNVERIFIED, RestEvidence(still).judge(5_000, 7_000, params))
    }

    @Test
    fun `a step inside the window means the player moved`() {
        assertEquals(Stillness.MOVING, RestEvidence(still, lastStepMs = 1_500).judge(0, 2_000, params))
        assertEquals(Stillness.VERIFIED, RestEvidence(still, lastStepMs = -10).judge(0, 2_000, params))
    }

    @Test
    fun `the witness keeps only the last 5 s`() {
        val long = (0L..10_000L step 100).fold(WatchWitness()) { w, t -> w.withSample(t, 0.1, params) }

        assertEquals(5_000L, long.samples.first().tMs)
    }
}
```

The spec §9 bias cases map to these tests: offset (8, −5, 3) with sway → `still signal with offset…`; 5 °/s with the watch feeling it → `a constant 5 deg per s turn that the watch also feels…`; no watch gyro (with or without a stored bias, decision 4) → `without a watch gyro the boot bias is accepted but unverified`; slow 10 °/s at rest without watch → `…never re-seeds and is flagged unverified`; b₀ 5 °/s off → `…re-seeded in the first still 3 s window…`; a bias 3 °/s off → `…after a temperature change still recalibrates`; recalibration with the 8 °/s offset → `rest recalibration fires with the 8 deg per s raw offset…`.

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/imu/ImuChannelTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.config.ImuParams
import io.github.santiquiroz.blindside.core.protocol.ImuBatch
import io.github.santiquiroz.blindside.core.protocol.ImuSample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin

class ImuChannelTest {
    private val params = ImuParams()
    private val offset = Vec3(8.0, -5.0, 3.0)
    private val up = Vec3(0.0, 0.0, 1.0)
    private val watchStill = watchAt(0.5)
    private val noWatch = RestEvidence()

    @Test
    fun `still signal with offset and 1 deg per s sway is accepted within 0_1 deg per s`() {
        val channel = feed(ImuChannel(), 0 until 100) { t -> offset + sway(t) }

        assertEquals(BiasStatus.READY, channel.status)
        assertTrue(channel.biasVerified)
        val bias = channel.bias!!
        assertEquals(offset.x, bias.x, 0.1)
        assertEquals(offset.y, bias.y, 0.1)
        assertEquals(offset.z, bias.z, 0.1)
    }

    @Test
    fun `a window where a slow 5 deg per s turn starts is rejected`() {
        val channel = feed(ImuChannel(), 0 until 100) { t -> if (t < 500) offset else offset + Vec3(0.0, 0.0, 5.0) }

        assertEquals(BiasStatus.CALIBRATING, channel.status)
        assertNull(channel.bias)
    }

    @Test
    fun `a constant 5 deg per s turn that the watch also feels is rejected`() {
        val channel = feed(ImuChannel(), 0 until 100, evidence = watchAt(5.0)) { offset + Vec3(0.0, 0.0, 5.0) }

        assertEquals(BiasStatus.CALIBRATING, channel.status)
    }

    @Test
    fun `a step inside the boot window rejects it`() {
        val channel = feed(ImuChannel(), 0 until 100, evidence = watchStill.copy(lastStepMs = 1_000)) { offset }

        assertEquals(BiasStatus.CALIBRATING, channel.status)
    }

    @Test
    fun `without a watch gyro the boot bias is accepted but unverified`() {
        val channel = feed(ImuChannel(), 0 until 100, evidence = noWatch) { offset }

        assertEquals(BiasStatus.READY, channel.status)
        assertFalse(channel.biasVerified)
    }

    @Test
    fun `a still mean above 45 deg per s marks the sensor defective, and a later good window recovers it`() {
        val defective = feed(ImuChannel(), 0 until 100, evidence = noWatch) { Vec3(50.0, 0.0, 0.0) }
        val recovered = feed(defective, 100 until 200) { offset }

        assertEquals(BiasStatus.DEFECTIVE, defective.status)
        assertEquals(BiasStatus.READY, recovered.status)
    }

    @Test
    fun `rest recalibration fires with the 8 deg per s raw offset because it uses the corrected rate`() {
        val booted = feed(ImuChannel(), 0 until 100) { offset }
        val drifted = feed(booted, 100 until 350) { offset + Vec3(0.5, 0.0, 0.0) }

        assertTrue(drifted.bias!!.x > offset.x + 0.02, "bias was ${drifted.bias}")
    }

    @Test
    fun `a bias 5 deg per s off is re-seeded in the first still 3 s window and the yaw stops drifting`() {
        val wrong = ImuChannel(status = BiasStatus.READY, bias = offset + Vec3(0.0, 0.0, 5.0), biasVerified = true, gravity = up)

        val fixed = feed(wrong, 0 until 150) { offset }

        assertEquals(1, fixed.reseeds)
        assertEquals(0.0, fixed.yawRateDps(offset, params)!!, 1e-6)
    }

    @Test
    fun `a bias 3 deg per s off after a temperature change still recalibrates`() {
        val warm = ImuChannel(status = BiasStatus.READY, bias = offset + Vec3(0.0, 0.0, 3.0), biasVerified = true, gravity = up)

        val fixed = feed(warm, 0 until 150) { offset }

        assertEquals(offset.z, fixed.bias!!.z, 1e-6)
    }

    @Test
    fun `without a watch gyro a slow 10 deg per s turn at rest never re-seeds and is flagged unverified`() {
        val booted = feed(ImuChannel(), 0 until 100) { offset }

        val turned = feed(booted, 100 until 400, evidence = noWatch) { offset + Vec3(0.0, 0.0, 10.0) }

        assertEquals(offset, turned.bias)
        assertEquals(0, turned.reseeds)
        assertFalse(turned.biasVerified)
    }

    @Test
    fun `rest recalibration does not fire while the watch feels a turn`() {
        val booted = feed(ImuChannel(), 0 until 100) { offset }

        val turning = feed(booted, 100 until 350, evidence = watchAt(4.0)) { offset + Vec3(4.0, 0.0, 0.0) }

        assertEquals(offset, turning.bias)
    }

    @Test
    fun `yaw rate is minus the projection on up so a right turn is positive`() {
        val booted = feed(ImuChannel(), 0 until 100) { offset }

        assertEquals(-10.0, booted.yawRateDps(offset + Vec3(0.0, 0.0, 10.0), params)!!, 1e-6)
    }

    @Test
    fun `yaw rate is independent of how the chip is mounted`() {
        val sideways = Vec3(1.0, 0.0, 0.0)
        val booted = feed(ImuChannel(), 0 until 100, accel = sideways) { offset }

        assertEquals(-10.0, booted.yawRateDps(offset + Vec3(10.0, 0.0, 0.0), params)!!, 1e-6)
    }

    @Test
    fun `a 32_8 LSB per deg per s scale doubles the rate read from the same raw counts`() {
        val batch = ImuBatch(0, 1_000, listOf(ImuSample(0, 0, 4096, 0, 0, 655)), listOf(0L, 0L, 0L))

        assertEquals(10.0, batch.readings().single().gyroDps.z, 1e-9)
        assertEquals(655 / 32.8, batch.readings(gyroLsbPerDps = 32.8).single().gyroDps.z, 1e-9)
    }

    @Test
    fun `a new scale from info restarts the calibration`() {
        val booted = feed(ImuChannel(), 0 until 100) { offset }

        assertEquals(booted, booted.withScales(booted.gyroLsbPerDps, booted.accelLsbPerG))
        assertEquals(BiasStatus.CALIBRATING, booted.withScales(32.8, 4096.0).status)
    }

    @Test
    fun `gravity tilting past 60 degrees from standing means prone`() {
        val booted = feed(ImuChannel(), 0 until 100) { offset }
        val lying = feed(booted, 100 until 400, accel = Vec3(1.0, 0.0, 0.0)) { offset }

        assertFalse(booted.isProne(params))
        assertTrue(lying.isProne(params))
    }

    @Test
    fun `strong accelerations are skipped by the gravity filter`() {
        val booted = feed(ImuChannel(), 0 until 100) { offset }
        val shaken = feed(booted, 100 until 110, accel = Vec3(0.0, 1.0, 1.0)) { offset }

        assertEquals(booted.gravity, shaken.gravity)
    }

    private fun sway(sample: Long): Vec3 {
        val s = sin(2 * PI * sample / 1000.0)
        return Vec3(s, s, s)
    }

    private fun watchAt(rateDps: Double) = RestEvidence(WatchWitness((0L..20_000L step 100).map { TimedValue(it, rateDps) }))

    private fun feed(
        start: ImuChannel,
        samples: IntRange,
        accel: Vec3 = up,
        evidence: RestEvidence = watchStill,
        gyro: (Long) -> Vec3,
    ): ImuChannel =
        samples.fold(start) { channel, i ->
            val tMs = i * 20L
            channel.ingest(ImuReading(tMs, gyro(tMs), accel), evidence, params)
        }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.imu.WatchWitnessTest" --tests "io.github.santiquiroz.blindside.core.imu.ImuChannelTest")`

Expected: FAIL. `:radar-core:compileTestKotlin` reports `Unresolved reference` for `ImuChannel`, `ImuReading`, `Vec3`, `BiasStatus`, `RestEvidence`, `WatchWitness`, `Stillness`.

- [ ] **Step 3: Write the implementation**

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/imu/Vec3.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.protocol.ACCEL_LSB_PER_G
import io.github.santiquiroz.blindside.core.protocol.GYRO_LSB_PER_DPS
import io.github.santiquiroz.blindside.core.protocol.ImuBatch
import kotlin.math.acos
import kotlin.math.sqrt

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    operator fun div(k: Double) = Vec3(x / k, y / k, z / k)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    val norm: Double get() = sqrt(this dot this)
    fun normalized(): Vec3 = this / norm
    fun maxAbs(): Double = maxOf(kotlin.math.abs(x), kotlin.math.abs(y), kotlin.math.abs(z))

    fun angleDegTo(o: Vec3): Double = Math.toDegrees(acos(((this dot o) / (norm * o.norm)).coerceIn(-1.0, 1.0)))

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}

data class ImuReading(val tMs: Long, val gyroDps: Vec3, val accelG: Vec3)

data class TimedValue(val tMs: Long, val value: Double)

fun ImuBatch.readings(gyroLsbPerDps: Double = GYRO_LSB_PER_DPS, accelLsbPerG: Double = ACCEL_LSB_PER_G): List<ImuReading> =
    samples.mapIndexed { index, s ->
        ImuReading(
            tMs = sampleTimeMs(index),
            gyroDps = Vec3(s.gx.toDouble(), s.gy.toDouble(), s.gz.toDouble()) / gyroLsbPerDps,
            accelG = Vec3(s.ax.toDouble(), s.ay.toDouble(), s.az.toDouble()) / accelLsbPerG,
        )
    }

fun List<Vec3>.mean(): Vec3 = fold(Vec3.ZERO) { acc, v -> acc + v } / size.toDouble()

fun List<Double>.standardDeviation(): Double {
    if (size < 2) return 0.0
    val mean = average()
    return sqrt(sumOf { (it - mean) * (it - mean) } / size)
}
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/imu/WatchWitness.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.config.ImuParams

enum class Stillness { VERIFIED, UNVERIFIED, MOVING }

// Spec §6.3: the belt boxes cannot tell a slow steady turn from a gyro offset; the watch gyro (drift-compensated by Android) can.
data class WatchWitness(val samples: List<TimedValue> = emptyList()) {
    fun withSample(tMs: Long, rateDps: Double, params: ImuParams): WatchWitness =
        copy(samples = (samples + TimedValue(tMs, rateDps)).filter { it.tMs >= tMs - params.witnessHistoryMs })

    fun isMoving(fromMs: Long, toMs: Long, params: ImuParams): Boolean =
        inside(fromMs, toMs).any { it.value > params.witnessMaxDps }

    fun hasCoverage(fromMs: Long, toMs: Long, params: ImuParams): Boolean =
        inside(fromMs, toMs).size >= params.witnessMinSamples

    private fun inside(fromMs: Long, toMs: Long) = samples.filter { it.tMs in fromMs..toMs }
}

data class RestEvidence(val watch: WatchWitness = WatchWitness(), val lastStepMs: Long? = null) {
    fun judge(fromMs: Long, toMs: Long, params: ImuParams): Stillness = when {
        steppedSince(fromMs) || watch.isMoving(fromMs, toMs, params) -> Stillness.MOVING
        watch.hasCoverage(fromMs, toMs, params) -> Stillness.VERIFIED
        else -> Stillness.UNVERIFIED
    }

    private fun steppedSince(fromMs: Long): Boolean = lastStepMs != null && lastStepMs >= fromMs
}
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/imu/BiasWindows.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.config.ImuParams

data class WindowStats(val mean: Vec3, val spreadDps: Double, val accelStdG: Double)

sealed interface BootVerdict {
    data class Accepted(val bias: Vec3, val verified: Boolean) : BootVerdict
    data object Moving : BootVerdict
    data object Defective : BootVerdict
}

sealed interface RestVerdict {
    data object NotResting : RestVerdict
    data class Refine(val mean: Vec3, val verified: Boolean) : RestVerdict
    data class Reseed(val mean: Vec3) : RestVerdict
    data object Unverified : RestVerdict
}

fun windowStats(window: List<ImuReading>): WindowStats {
    val mean = window.map { it.gyroDps }.mean()
    return WindowStats(
        mean = mean,
        spreadDps = window.maxOf { (it.gyroDps - mean).maxAbs() },
        accelStdG = window.map { it.accelG.norm }.standardDeviation(),
    )
}

// Spec §6.3: stillness is judged by spread, not magnitude, because the raw offset reaches ±20 °/s.
fun isStillWindow(stats: WindowStats, params: ImuParams): Boolean =
    stats.spreadDps <= params.stillMaxSpreadDps && stats.accelStdG <= params.stillMaxAccelStdG

fun evaluateBootWindow(window: List<ImuReading>, stillness: Stillness, params: ImuParams): BootVerdict {
    val stats = windowStats(window)
    if (stillness == Stillness.MOVING || !isStillWindow(stats, params)) return BootVerdict.Moving
    if (stats.mean.maxAbs() > params.defectiveMeanDps) return BootVerdict.Defective
    return BootVerdict.Accepted(stats.mean, verified = stillness == Stillness.VERIFIED)
}

// Spec §6.3: a still window whose mean is 3-45 °/s away from b means b absorbed a turn; only the watch can confirm it.
fun evaluateRestWindow(window: List<ImuReading>, bias: Vec3, stillness: Stillness, params: ImuParams): RestVerdict {
    val stats = windowStats(window)
    if (stillness == Stillness.MOVING || !isStillWindow(stats, params)) return RestVerdict.NotResting
    val offset = (stats.mean - bias).maxAbs()
    return when {
        offset < params.reseedMinOffsetDps -> RestVerdict.Refine(stats.mean, verified = stillness == Stillness.VERIFIED)
        offset >= params.defectiveMeanDps -> RestVerdict.NotResting
        stillness == Stillness.VERIFIED -> RestVerdict.Reseed(stats.mean)
        else -> RestVerdict.Unverified
    }
}
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/imu/ImuChannel.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.config.ImuParams
import io.github.santiquiroz.blindside.core.protocol.ACCEL_LSB_PER_G
import io.github.santiquiroz.blindside.core.protocol.GYRO_LSB_PER_DPS
import io.github.santiquiroz.blindside.core.protocol.IMU_SAMPLE_PERIOD_MS
import kotlin.math.abs

enum class BiasStatus { CALIBRATING, READY, DEFECTIVE }

data class ImuChannel(
    val status: BiasStatus = BiasStatus.CALIBRATING,
    val bias: Vec3? = null,
    val biasVerified: Boolean = false,
    val window: List<ImuReading> = emptyList(),
    val gravity: Vec3? = null,
    val standingUp: Vec3? = null,
    val reseeds: Int = 0,
    val lastSums: List<Long>? = null,
    val lastSampleMs: Long? = null,
    val gyroLsbPerDps: Double = GYRO_LSB_PER_DPS,
    val accelLsbPerG: Double = ACCEL_LSB_PER_G,
) {
    val isReady: Boolean get() = status == BiasStatus.READY

    fun ingest(reading: ImuReading, evidence: RestEvidence, params: ImuParams): ImuChannel {
        val withGravity = withGravity(reading, params)
        return when (status) {
            BiasStatus.READY -> withGravity.withRestReading(reading, evidence, params)
            BiasStatus.CALIBRATING, BiasStatus.DEFECTIVE -> withGravity.withBootReading(reading, evidence, params)
        }
    }

    // A new scale means the bias was measured in the wrong units: calibrate again from scratch.
    fun withScales(gyroLsbPerDps: Double, accelLsbPerG: Double): ImuChannel =
        if (gyroLsbPerDps == this.gyroLsbPerDps && accelLsbPerG == this.accelLsbPerG) this
        else ImuChannel(gyroLsbPerDps = gyroLsbPerDps, accelLsbPerG = accelLsbPerG)

    // The accelerometer reads +1 g pointing up, so a clockwise (rightward) turn is negative about "up".
    fun yawRateDps(gyroDps: Vec3, params: ImuParams): Double? {
        val b = bias ?: return null
        val g = gravity ?: return null
        if (!isReady) return null
        return -(g.normalized() dot (gyroDps - b)) * params.gyroScale
    }

    fun isProne(params: ImuParams): Boolean {
        val up = standingUp ?: return false
        val g = gravity ?: return false
        return up.angleDegTo(g) > params.proneAngleDeg
    }

    private fun withGravity(reading: ImuReading, params: ImuParams): ImuChannel {
        if (abs(reading.accelG.norm - 1.0) > params.gravityRejectG) return this
        val current = gravity ?: return copy(gravity = reading.accelG)
        val alpha = IMU_SAMPLE_PERIOD_MS / 1000.0 / params.gravityTauS
        return copy(gravity = current + (reading.accelG - current) * alpha)
    }

    private fun withBootReading(reading: ImuReading, evidence: RestEvidence, params: ImuParams): ImuChannel {
        val window = window + reading
        if (window.size < params.bootWindowSamples) return copy(window = window)
        val stillness = evidence.judge(window.first().tMs, window.last().tMs, params)
        return when (val verdict = evaluateBootWindow(window, stillness, params)) {
            is BootVerdict.Accepted -> copy(
                status = BiasStatus.READY, bias = verdict.bias, biasVerified = verdict.verified, window = emptyList(), standingUp = gravity,
            )
            BootVerdict.Moving -> copy(window = emptyList())
            BootVerdict.Defective -> copy(status = BiasStatus.DEFECTIVE, window = emptyList())
        }
    }

    private fun withRestReading(reading: ImuReading, evidence: RestEvidence, params: ImuParams): ImuChannel {
        val b = bias ?: return this
        val window = (window + reading).takeLast(params.restWindowSamples)
        if (window.size < params.restWindowSamples) return copy(window = window)
        val stillness = evidence.judge(window.first().tMs, window.last().tMs, params)
        return applyRest(evaluateRestWindow(window, b, stillness, params), b, window, params)
    }

    private fun applyRest(verdict: RestVerdict, b: Vec3, window: List<ImuReading>, params: ImuParams): ImuChannel = when (verdict) {
        RestVerdict.NotResting -> copy(window = window)
        is RestVerdict.Refine -> copy(
            window = window,
            bias = b + (verdict.mean - b) * (IMU_SAMPLE_PERIOD_MS / 1000.0 / params.restBiasTauS),
            biasVerified = biasVerified || verdict.verified,
        )
        is RestVerdict.Reseed -> copy(window = emptyList(), bias = verdict.mean, biasVerified = true, reseeds = reseeds + 1)
        RestVerdict.Unverified -> copy(window = window, biasVerified = false)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.imu.WatchWitnessTest" --tests "io.github.santiquiroz.blindside.core.imu.ImuChannelTest")`

Expected: PASS, 22 tests, 0 failures. Then run the whole module (`(cd watch && ./gradlew :radar-core:test)`) and confirm every earlier test still passes.

- [ ] **Step 5: Commit**

```bash
git add watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/imu/Vec3.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/imu/WatchWitness.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/imu/BiasWindows.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/imu/ImuChannel.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/imu/WatchWitnessTest.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/imu/ImuChannelTest.kt
git commit -m "feat: canal IMU con testigo del reloj, sesgo por dispersión, re-siembra en reposo y escalas del info"
```

### Task 8: Yaw integration, gap reconstruction, two-phase merge and extrapolation

Spec §6.3 and §3.1. Each 50 Hz sample stamped `t` becomes a 20 ms `YawIncrement` covering `[t − 10, t + 10)` (contracts: `t` is the block centre), using the channel's scale from `info`. When samples are missing between two batches, the lost turn is rebuilt from the cumulative raw sums: Δψ ≈ −ĝᵀ·((ΣΔ − 4·Σbatch)/scale − N·b)/200 with N = 4·missing (u32 sums, difference read as int32), as one increment ending at `t_first − 10`. The two box IMUs run on independent phases (decision 6, Review Focus 3): `mergeIncrements` splits rebuilt gaps into 20 ms pieces, pairs both IMUs on a shared 20 ms grid by end time (rounded up) and averages them; one IMU alone passes through, and a grid cell already in the history is never added twice. `YawTracker` keeps 3 s of history, interpolates for radar frame times, extrapolates at most 150 ms with the mean of the last 3 rates, and blends each new packet's correction away with τ = 50 ms for display.

**Files:**
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/imu/YawIncrements.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/imu/YawTracker.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/imu/YawTest.kt`

**Interfaces:**
- Consumes: `ImuChannel`, `ImuReading`, `RestEvidence`, `Vec3`, `readings` (Task 7); `ImuBatch`, protocol constants (Task 4); `ImuParams` (Task 2).
- Produces (`io.github.santiquiroz.blindside.core.imu`): `data class YawIncrement(tEndMs: Long, durationMs: Long, deltaDeg: Double) { val rateDps }`; `data class ChannelUpdate(channel: ImuChannel, increments: List<YawIncrement>, readings: List<ImuReading>)`; `fun ImuChannel.ingestBatch(batch: ImuBatch, evidence: RestEvidence, params: ImuParams): ChannelUpdate`; `fun missingSamples(lastSampleMs, firstSampleMs): Int`; `fun ImuChannel.gapIncrement(batch, params): YawIncrement?`; `fun mergeIncrements(perImu: List<List<YawIncrement>>): List<YawIncrement>`; `fun gridEndMs(tEndMs: Long): Long`; `fun wrappedDelta(now: Long, previous: Long): Double`; `data class YawPoint(tMs, yawDeg)`; `data class YawTracker(history, recentRates, blendOffsetDeg, blendAnchorMs) { val lastPoint; fun apply(increments, params): YawTracker; fun yawAt(tMs, params): Double; fun displayYawAt(tMs, params): Double; fun extrapolationRate(): Double }`.

- [ ] **Step 1: Write the failing tests**

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/imu/YawTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.config.ImuParams
import io.github.santiquiroz.blindside.core.protocol.ImuBatch
import io.github.santiquiroz.blindside.core.protocol.ImuSample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YawTest {
    private val params = ImuParams()
    private val ready = ImuChannel(status = BiasStatus.READY, bias = Vec3.ZERO, gravity = Vec3(0.0, 0.0, 1.0))
    private val evidence = RestEvidence()

    @Test
    fun `a right turn at 10 deg per s adds 1 degree over five samples`() {
        val update = ready.ingestBatch(batch(tFirst = 1_000, gzLsb = -655, sums = sums(0)), evidence, params)

        assertEquals(5, update.increments.size)
        assertEquals(1.0, update.increments.sumOf { it.deltaDeg }, 1e-6)
        assertEquals(1_080L, update.channel.lastSampleMs)
    }

    @Test
    fun `each increment covers the 20 ms block centred on its sample`() {
        val update = ready.ingestBatch(batch(tFirst = 1_000, gzLsb = -655, sums = sums(0)), evidence, params)

        assertEquals(listOf(1_010L, 1_030L, 1_050L, 1_070L, 1_090L), update.increments.map { it.tEndMs })
    }

    @Test
    fun `a lost packet is rebuilt from the cumulative sums with the bias removed`() {
        val bias = Vec3(0.0, 0.0, 2.0)
        val gapLsb = -1834
        val batchLsb = -1834
        val previous = ready.copy(bias = bias, lastSums = sums(0), lastSampleMs = 1_080)
        val afterGap = 4L * 10 * gapLsb + 4L * 5 * batchLsb

        val update = previous.ingestBatch(batch(tFirst = 1_300, gzLsb = batchLsb, sums = sums(afterGap)), evidence, params)

        val gap = update.increments.first()
        assertEquals(1_290L, gap.tEndMs)
        assertEquals(200L, gap.durationMs)
        assertEquals(6.0, gap.deltaDeg, 1e-6)
    }

    @Test
    fun `the gap uses the channel gyro scale from info`() {
        val previous = ready.copy(lastSums = sums(0), lastSampleMs = 1_080, gyroLsbPerDps = 32.8)
        val afterGap = 4L * 10 * -328

        val update = previous.ingestBatch(batch(tFirst = 1_300, gzLsb = 0, sums = sums(afterGap)), evidence, params)

        assertEquals(2.0, update.increments.first().deltaDeg, 1e-6)
    }

    @Test
    fun `sums that wrap past 2 to the 32 still give the right difference`() {
        assertEquals(-40.0, wrappedDelta(now = 0xFFFFFFD8L, previous = 0L), 0.0)
        assertEquals(32.0, wrappedDelta(now = 16L, previous = 0xFFFFFFF0L), 0.0)
    }

    @Test
    fun `an uncalibrated channel yields no increments but remembers its sums`() {
        val update = ImuChannel().ingestBatch(batch(tFirst = 1_000, gzLsb = -655, sums = sums(123)), evidence, params)

        assertTrue(update.increments.isEmpty())
        assertEquals(sums(123), update.channel.lastSums)
    }

    @Test
    fun `two imus are averaged on the shared 20 ms grid and a single imu passes through`() {
        val a = listOf(YawIncrement(20, 20, 1.0), YawIncrement(40, 20, 1.0))
        val b = listOf(YawIncrement(27, 20, 3.0))

        val merged = mergeIncrements(listOf(a, b))

        assertEquals(listOf(20L to 1.0, 40L to 2.0), merged.map { it.tEndMs to it.deltaDeg })
    }

    @Test
    fun `two imus 7 ms apart turning 1 degree per sample read 50 degrees, not 100`() {
        val a = (1..50).map { YawIncrement(1_000L + it * 20, 20, 1.0) }
        val b = (1..50).map { YawIncrement(1_007L + it * 20, 20, 1.0) }

        val tracker = (0 until 10).fold(YawTracker()) { t, chunk ->
            val slice = { list: List<YawIncrement> -> list.subList(chunk * 5, chunk * 5 + 5) }
            t.apply(mergeIncrements(listOf(slice(a), slice(b))), params)
        }

        assertEquals(50.0, tracker.yawAt(2_000, params), 0.5)
    }

    @Test
    fun `a rebuilt gap is spread over the grid so it averages with the other imu sample by sample`() {
        val gap = listOf(YawIncrement(100, 100, 5.0))
        val other = (1..5).map { YawIncrement(it * 20L, 20, 1.0) }

        val merged = mergeIncrements(listOf(gap, other))

        assertEquals(List(5) { 1.0 }, merged.map { it.deltaDeg })
    }

    @Test
    fun `yaw interpolates between samples and extrapolates at most 150 ms`() {
        val tracker = YawTracker().apply((1..5).map { YawIncrement(1_000L + it * 20, 20, 0.2) }, params)

        assertEquals(0.1, tracker.yawAt(1_010, params), 1e-9)
        assertEquals(1.0, tracker.yawAt(1_100, params), 1e-9)
        assertEquals(1.5, tracker.yawAt(1_150, params), 1e-9)
        assertEquals(2.5, tracker.yawAt(1_400, params), 1e-9)
    }

    @Test
    fun `history keeps only the last 3 seconds`() {
        val tracker = YawTracker().apply((1..300).map { YawIncrement(it * 20L, 20, 0.0) }, params)

        assertTrue(tracker.history.first().tMs >= 6_000 - 3_000)
    }

    @Test
    fun `display yaw blends a correction away with a 50 ms time constant`() {
        val steady = YawTracker().apply((1..5).map { YawIncrement(it * 20L, 20, 0.0) }, params)
        val jumped = steady.apply(listOf(YawIncrement(300, 200, 10.0)), params)

        assertEquals(0.0, jumped.displayYawAt(300, params), 1e-9)
        assertEquals(10.0, jumped.displayYawAt(700, params), 0.01)
    }
}

private fun sums(gz: Long) = listOf(0L, 0L, gz and 0xFFFFFFFFL)

private fun batch(tFirst: Long, gzLsb: Int, sums: List<Long>) =
    ImuBatch(0, tFirst, List(5) { ImuSample(0, 0, 4096, 0, 0, gzLsb) }, sums)
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.imu.YawTest")`

Expected: FAIL. `:radar-core:compileTestKotlin` reports `Unresolved reference` for `YawIncrement`, `ingestBatch`, `wrappedDelta`, `mergeIncrements`, `YawTracker`.

- [ ] **Step 3: Write the implementation**

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/imu/YawIncrements.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.config.ImuParams
import io.github.santiquiroz.blindside.core.protocol.IMU_SAMPLE_PERIOD_MS
import io.github.santiquiroz.blindside.core.protocol.ImuBatch
import io.github.santiquiroz.blindside.core.protocol.RAW_GYRO_RATE_HZ
import io.github.santiquiroz.blindside.core.protocol.RAW_READINGS_PER_SAMPLE

data class YawIncrement(val tEndMs: Long, val durationMs: Long, val deltaDeg: Double) {
    val rateDps: Double get() = deltaDeg * 1000.0 / durationMs
}

data class ChannelUpdate(val channel: ImuChannel, val increments: List<YawIncrement>, val readings: List<ImuReading>)

private const val HALF_SAMPLE_MS = IMU_SAMPLE_PERIOD_MS / 2

fun ImuChannel.ingestBatch(batch: ImuBatch, evidence: RestEvidence, params: ImuParams): ChannelUpdate {
    val readings = batch.readings(gyroLsbPerDps, accelLsbPerG).filter { lastSampleMs == null || it.tMs > lastSampleMs }
    if (readings.isEmpty()) return ChannelUpdate(this, emptyList(), emptyList())
    val gap = gapIncrement(batch, params)
    val start = ChannelUpdate(this, listOfNotNull(gap), emptyList())
    val folded = readings.fold(start) { acc, reading -> acc.withReading(reading, evidence, params) }
    return folded.copy(channel = folded.channel.copy(lastSums = batch.gyroSums, lastSampleMs = batch.tLastMs))
}

fun missingSamples(lastSampleMs: Long, firstSampleMs: Long): Int =
    ((firstSampleMs - lastSampleMs + HALF_SAMPLE_MS) / IMU_SAMPLE_PERIOD_MS - 1).toInt()

// Spec §6.3: Δψ ≈ ĝᵀ·(ΔΣω − N·b)·scale/200, using the firmware's cumulative raw 200 Hz sums.
fun ImuChannel.gapIncrement(batch: ImuBatch, params: ImuParams): YawIncrement? {
    val previousSums = lastSums ?: return null
    val previousSampleMs = lastSampleMs ?: return null
    val missing = missingSamples(previousSampleMs, batch.tFirstMs)
    val b = bias ?: return null
    val g = gravity ?: return null
    if (missing <= 0 || !isReady) return null
    val sumsDelta = Vec3(wrappedDelta(batch.gyroSums[0], previousSums[0]), wrappedDelta(batch.gyroSums[1], previousSums[1]), wrappedDelta(batch.gyroSums[2], previousSums[2]))
    val gapRaw = sumsDelta - batchRawSum(batch)
    val rawReadings = (missing * RAW_READINGS_PER_SAMPLE).toDouble()
    val angle = (gapRaw / gyroLsbPerDps - b * rawReadings) / RAW_GYRO_RATE_HZ
    val delta = -(g.normalized() dot angle) * params.gyroScale
    return YawIncrement(batch.tFirstMs - HALF_SAMPLE_MS, missing * IMU_SAMPLE_PERIOD_MS, delta)
}

// Each IMU task samples on its own phase, so increments are paired on a shared 20 ms grid before averaging.
fun mergeIncrements(perImu: List<List<YawIncrement>>): List<YawIncrement> =
    perImu.flatten()
        .flatMap { splitIntoSamples(it) }
        .groupBy { gridEndMs(it.tEndMs) }
        .map { (gridEnd, group) -> YawIncrement(gridEnd, IMU_SAMPLE_PERIOD_MS, group.map { it.deltaDeg }.average()) }
        .sortedBy { it.tEndMs }

fun gridEndMs(tEndMs: Long): Long = Math.floorDiv(tEndMs + IMU_SAMPLE_PERIOD_MS - 1, IMU_SAMPLE_PERIOD_MS) * IMU_SAMPLE_PERIOD_MS

// The firmware sums wrap as u32; the difference read as int32 survives one wrap.
fun wrappedDelta(now: Long, previous: Long): Double = ((now - previous) and 0xFFFFFFFFL).toInt().toDouble()

private fun splitIntoSamples(increment: YawIncrement): List<YawIncrement> {
    val pieces = (increment.durationMs / IMU_SAMPLE_PERIOD_MS).coerceAtLeast(1)
    return (0 until pieces).map { k ->
        YawIncrement(increment.tEndMs - k * IMU_SAMPLE_PERIOD_MS, IMU_SAMPLE_PERIOD_MS, increment.deltaDeg / pieces)
    }
}

private fun batchRawSum(batch: ImuBatch): Vec3 = batch.samples.fold(Vec3.ZERO) { acc, s ->
    acc + Vec3(s.gx.toDouble(), s.gy.toDouble(), s.gz.toDouble()) * RAW_READINGS_PER_SAMPLE.toDouble()
}

private fun ChannelUpdate.withReading(reading: ImuReading, evidence: RestEvidence, params: ImuParams): ChannelUpdate {
    val next = channel.ingest(reading, evidence, params)
    val rate = next.yawRateDps(reading.gyroDps, params)
    val increment = rate?.let { YawIncrement(reading.tMs + HALF_SAMPLE_MS, IMU_SAMPLE_PERIOD_MS, it * IMU_SAMPLE_PERIOD_MS / 1000.0) }
    return ChannelUpdate(next, increments + listOfNotNull(increment), readings + reading)
}
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/imu/YawTracker.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.config.ImuParams
import io.github.santiquiroz.blindside.core.protocol.IMU_SAMPLE_PERIOD_MS
import kotlin.math.exp

data class YawPoint(val tMs: Long, val yawDeg: Double)

data class YawTracker(
    val history: List<YawPoint> = emptyList(),
    val recentRates: List<Double> = emptyList(),
    val blendOffsetDeg: Double = 0.0,
    val blendAnchorMs: Long = 0,
) {
    val lastPoint: YawPoint? get() = history.lastOrNull()

    fun apply(increments: List<YawIncrement>, params: ImuParams): YawTracker {
        val previous = lastPoint
        val fresh = increments.filter { previous == null || it.tEndMs > previous.tMs }.sortedBy { it.tEndMs }
        if (fresh.isEmpty()) return this
        val start = previous ?: YawPoint(fresh.first().tEndMs - fresh.first().durationMs, 0.0)
        val added = fresh.runningFold(start) { acc, inc -> YawPoint(inc.tEndMs, acc.yawDeg + inc.deltaDeg) }.drop(1)
        val newest = added.last()
        val merged = (history.ifEmpty { listOf(start) } + added).filter { it.tMs >= newest.tMs - params.yawHistoryMs }
        val rates = (recentRates + fresh.filter { it.durationMs == IMU_SAMPLE_PERIOD_MS }.map { it.rateDps })
            .takeLast(params.yawRateSamplesForExtrapolation)
        val offset = if (history.isEmpty()) 0.0 else displayYawAt(newest.tMs, params) - newest.yawDeg
        return YawTracker(merged, rates, offset, newest.tMs)
    }

    fun yawAt(tMs: Long, params: ImuParams): Double {
        val last = lastPoint ?: return 0.0
        if (tMs >= last.tMs) return last.yawDeg + extrapolationRate() * minOf(tMs - last.tMs, params.yawExtrapolationMaxMs) / 1000.0
        if (tMs <= history.first().tMs) return history.first().yawDeg
        return interpolate(tMs)
    }

    fun displayYawAt(tMs: Long, params: ImuParams): Double {
        val elapsed = (tMs - blendAnchorMs).coerceAtLeast(0)
        return yawAt(tMs, params) + blendOffsetDeg * exp(-elapsed / params.yawBlendTauMs)
    }

    fun extrapolationRate(): Double = if (recentRates.isEmpty()) 0.0 else recentRates.average()

    private fun interpolate(tMs: Long): Double {
        val index = history.indexOfFirst { it.tMs >= tMs }
        val after = history[index]
        val before = history[index - 1]
        val fraction = (tMs - before.tMs).toDouble() / (after.tMs - before.tMs)
        return before.yawDeg + (after.yawDeg - before.yawDeg) * fraction
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.imu.YawTest")`

Expected: PASS, 12 tests, 0 failures. Then run the whole module (`(cd watch && ./gradlew :radar-core:test)`) and confirm every earlier test still passes.

- [ ] **Step 5: Commit**

```bash
git add watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/imu/YawIncrements.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/imu/YawTracker.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/imu/YawTest.kt
git commit -m "feat: integración de yaw con reconstrucción de huecos, fusión de dos IMUs desfasados y extrapolación"
```

### Task 9: Motion detector: turning, walking, prone and steps

Spec §6.4 and §6.6. Turning: EMA of |ω_v| with τ = 0.2 s above 20 °/s, fed by the merged box increments; when both box IMUs are unusable the pipeline switches the source to the watch gyroscope (`withTurnSource(fromWatch = true)`, spec §6.6 and §8), whose events arrive at ≤ 10 Hz with uneven spacing (the gap is capped at 0.5 s; the first sample only seeds the filter). Walking: σ(|a|) of the belt over the last 1 s above 0.08 g, or a step in the last 1.2 s. The belt step detector is primary (rise above 1 g + 0.12 g, re-armed below 1 g + 0.03 g, ≥ 250 ms apart); the watch step only confirms. `isMoving` = walking or turning, the input of the stop-and-scan gate. Prone wins over everything.

**Files:**
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/imu/MotionDetector.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/imu/MotionDetectorTest.kt`

**Interfaces:**
- Consumes: `YawIncrement` (Task 8), `TimedValue`, `standardDeviation` (Task 7), `MotionParams`, `MotionState` (Task 2).
- Produces (`io.github.santiquiroz.blindside.core.imu`): `data class MotionDetector(turnRateEmaDps, watchTurnEmaDps, lastWatchMs, turnFromWatch, accelNorms, stepArmed, lastStepMs, prone) { fun withYawIncrement(increment, params): MotionDetector; fun withWatchRate(tMs, rateDps, params): MotionDetector; fun withTurnSource(fromWatch: Boolean): MotionDetector; fun withAccelNorm(tMs, normG, params): MotionDetector; fun withStep(tMs): MotionDetector; fun withProne(isProne): MotionDetector; fun isTurning(params): Boolean; fun isWalking(tMs, params): Boolean; fun isMoving(tMs, params): Boolean; fun state(tMs, params): MotionState }`.

- [ ] **Step 1: Write the failing tests**

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/imu/MotionDetectorTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.config.MotionParams
import io.github.santiquiroz.blindside.core.scene.MotionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin

class MotionDetectorTest {
    private val params = MotionParams()

    @Test
    fun `a sustained 60 deg per s turn is turning, a 10 deg per s sway is not`() {
        val turning = (1..20).fold(MotionDetector()) { m, i -> m.withYawIncrement(YawIncrement(i * 20L, 20, 1.2), params) }
        val swaying = (1..20).fold(MotionDetector()) { m, i -> m.withYawIncrement(YawIncrement(i * 20L, 20, 0.2), params) }

        assertEquals(MotionState.TURNING, turning.state(400, params))
        assertEquals(MotionState.STILL, swaying.state(400, params))
    }

    @Test
    fun `turning is filtered so a single spike does not count`() {
        val spike = MotionDetector().withYawIncrement(YawIncrement(20, 20, 1.2), params)

        assertFalse(spike.isTurning(params))
    }

    @Test
    fun `walking bob of 0_25 g is walking and still accel is not`() {
        val walking = walk(MotionDetector(), 0 until 50, amplitudeG = 0.25)
        val still = walk(MotionDetector(), 0 until 50, amplitudeG = 0.0)

        assertEquals(MotionState.WALKING, walking.state(980, params))
        assertEquals(MotionState.STILL, still.state(980, params))
    }

    @Test
    fun `the belt step detector keeps walking for 1_2 s after the last step`() {
        val walked = walk(MotionDetector(), 0 until 50, amplitudeG = 0.25)
        val stopped = walk(walked, 50 until 150, amplitudeG = 0.0)

        assertTrue(walked.lastStepMs != null)
        assertTrue(stopped.isWalking(walked.lastStepMs!! + 1_100, params))
        assertFalse(stopped.isWalking(walked.lastStepMs!! + 1_300, params))
    }

    @Test
    fun `a watch step alone keeps walking for 1_2 s`() {
        val stepped = MotionDetector().withStep(5_000)

        assertTrue(stepped.isWalking(6_000, params))
        assertFalse(stepped.isWalking(6_300, params))
    }

    @Test
    fun `with the box imus down a 30 deg per s watch rotation is turning`() {
        val watch = (1..10).fold(MotionDetector().withTurnSource(fromWatch = true)) { m, i -> m.withWatchRate(i * 100L, 30.0, params) }
        val belt = (1..10).fold(MotionDetector()) { m, i -> m.withWatchRate(i * 100L, 30.0, params) }

        assertEquals(MotionState.TURNING, watch.state(1_000, params))
        assertEquals(MotionState.STILL, belt.state(1_000, params))
    }

    @Test
    fun `a single watch spike is filtered out`() {
        val spike = MotionDetector().withTurnSource(fromWatch = true).withWatchRate(100, 30.0, params).withWatchRate(200, 0.0, params)

        assertFalse(spike.isTurning(params))
    }

    @Test
    fun `moving means walking or turning`() {
        val stepped = MotionDetector().withStep(5_000)

        assertTrue(stepped.isMoving(5_500, params))
        assertFalse(stepped.isMoving(7_000, params))
    }

    @Test
    fun `prone wins over every other state`() {
        val prone = walk(MotionDetector(), 0 until 50, amplitudeG = 0.25).withProne(true)

        assertEquals(MotionState.PRONE, prone.state(980, params))
    }

    private fun walk(start: MotionDetector, samples: IntRange, amplitudeG: Double): MotionDetector =
        samples.fold(start) { m, i ->
            val tMs = i * 20L
            m.withAccelNorm(tMs, 1.0 + amplitudeG * sin(2 * PI * 2.0 * tMs / 1000.0), params)
        }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.imu.MotionDetectorTest")`

Expected: FAIL. `:radar-core:compileTestKotlin` reports `Unresolved reference` for `MotionDetector`.

- [ ] **Step 3: Write the implementation**

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/imu/MotionDetector.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.config.MotionParams
import io.github.santiquiroz.blindside.core.scene.MotionState
import kotlin.math.abs

data class MotionDetector(
    val turnRateEmaDps: Double = 0.0,
    val watchTurnEmaDps: Double = 0.0,
    val lastWatchMs: Long? = null,
    val turnFromWatch: Boolean = false,
    val accelNorms: List<TimedValue> = emptyList(),
    val stepArmed: Boolean = true,
    val lastStepMs: Long? = null,
    val prone: Boolean = false,
) {
    fun withYawIncrement(increment: YawIncrement, params: MotionParams): MotionDetector =
        copy(turnRateEmaDps = smoothed(turnRateEmaDps, abs(increment.rateDps), increment.durationMs / 1000.0, params))

    fun withWatchRate(tMs: Long, rateDps: Double, params: MotionParams): MotionDetector {
        val gapMs = lastWatchMs?.let { (tMs - it).coerceIn(0, params.watchMaxGapMs) } ?: 0L
        return copy(watchTurnEmaDps = smoothed(watchTurnEmaDps, rateDps, gapMs / 1000.0, params), lastWatchMs = tMs)
    }

    // Spec §6.6: with both box IMUs down, "girando" comes from the watch gyroscope.
    fun withTurnSource(fromWatch: Boolean): MotionDetector = copy(turnFromWatch = fromWatch)

    fun withAccelNorm(tMs: Long, normG: Double, params: MotionParams): MotionDetector {
        val window = (accelNorms + TimedValue(tMs, normG)).filter { it.tMs > tMs - params.walkingWindowMs }
        return copy(accelNorms = window).withBeltStep(tMs, normG, params)
    }

    fun withStep(tMs: Long): MotionDetector = copy(lastStepMs = maxOf(tMs, lastStepMs ?: tMs))

    fun withProne(isProne: Boolean): MotionDetector = copy(prone = isProne)

    fun isTurning(params: MotionParams): Boolean =
        (if (turnFromWatch) watchTurnEmaDps else turnRateEmaDps) > params.turningRateDps

    fun isWalking(tMs: Long, params: MotionParams): Boolean =
        accelSpreadG() > params.walkingAccelStdG || steppedRecently(tMs, params)

    fun isMoving(tMs: Long, params: MotionParams): Boolean = isWalking(tMs, params) || isTurning(params)

    fun state(tMs: Long, params: MotionParams): MotionState = when {
        prone -> MotionState.PRONE
        isWalking(tMs, params) -> MotionState.WALKING
        isTurning(params) -> MotionState.TURNING
        else -> MotionState.STILL
    }

    private fun accelSpreadG(): Double = accelNorms.map { it.value }.standardDeviation()

    private fun steppedRecently(tMs: Long, params: MotionParams): Boolean {
        val last = lastStepMs ?: return false
        return tMs - last <= params.stepHoldMs
    }

    private fun withBeltStep(tMs: Long, normG: Double, params: MotionParams): MotionDetector {
        val excess = normG - 1.0
        if (!stepArmed) return if (excess < params.stepResetG) copy(stepArmed = true) else this
        val farEnough = lastStepMs == null || tMs - lastStepMs >= params.stepMinIntervalMs
        return if (excess > params.stepRiseG && farEnough) copy(stepArmed = false, lastStepMs = tMs) else this
    }
}

private fun smoothed(ema: Double, value: Double, dtS: Double, params: MotionParams): Double {
    val alpha = dtS / (params.turningTauS + dtS)
    return ema + (value - ema) * alpha
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.imu.MotionDetectorTest")`

Expected: PASS, 9 tests, 0 failures. Then run the whole module (`(cd watch && ./gradlew :radar-core:test)`) and confirm every earlier test still passes.

- [ ] **Step 5: Commit**

```bash
git add watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/imu/MotionDetector.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/imu/MotionDetectorTest.kt
git commit -m "feat: detector de movimiento (girando por cajas o reloj, caminando, tendido y pasos)"
```

### Task 10: Constant-velocity Kalman (CWNA) and exact GNN

Spec §6.2 and §6.1 step 5. State `[x, y, vx, vy]` relative to the ground (tracking frame = body rotated by yaw), CWNA process noise q = 0.4 m²/s³, polar measurement noise σ_r = 0.2 m and σ_θ = 3.5°/cos θ (cos θ ≥ cos 70°), ×1.5 in the overlap, v0 = 0 with σ_v0 = 1.5 m/s, Joseph-form update. `coast` is the coasting prediction with v·e^(−dt/τ). The GNN enumerates every assignment (≤ 3 detections per frame), with the cost of a new track equal to the χ² gate (9.21).

**Files:**
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/Matrix.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/CvKalman.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/Gnn.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/tracking/KalmanTest.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/tracking/GnnTest.kt`

**Interfaces:**
- Consumes: `TrackingParams` (Task 2), `Point2` (Task 5).
- Produces (`io.github.santiquiroz.blindside.core.tracking`): `class Matrix(rows, cols, values) { get(r, c), plus, minus, times(Double), times(Matrix), transpose(), inverse2x2(); companion of(rows, cols, vararg), build(rows, cols, cell), identity(n), column(vararg) }`; `data class KalmanState(x: Matrix, p: Matrix) { position, velocity, speed }`; `data class Innovation(residual: Point2, covariance: Matrix) { val mahalanobis2 }`; `object CvKalman { init(position, r, sigmaSpeedMps); predict(state, dtS, q); coast(state, dtS, q, speedTauS); freezeVelocity(state); innovation(state, z, r, positionVarianceCap: Double? = null); update(state, z, r); measurementNoise(rangeM, angleOffBoresightDeg, bearingInFrameDeg, overlap, params): Matrix }`; `data class Assignment(trackIndexByDetection: List<Int?>, cost: Double)`; `fun assignExact(detectionCount, trackCount, newTrackCost, cost: (detection, track) -> Double?): Assignment`.

- [ ] **Step 1: Write the failing tests**

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/tracking/KalmanTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.TrackingParams
import io.github.santiquiroz.blindside.core.geometry.Point2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KalmanTest {
    private val params = TrackingParams()
    private val r = CvKalman.measurementNoise(3.0, 0.0, 0.0, overlap = false, params = params)

    @Test
    fun `matrix product, transpose and 2x2 inverse`() {
        val a = Matrix.of(2, 2, 4.0, 7.0, 2.0, 6.0)

        val product = a * a.inverse2x2()

        assertEquals(1.0, product[0, 0], 1e-12)
        assertEquals(0.0, product[0, 1], 1e-12)
        assertEquals(7.0, a.transpose()[1, 0], 0.0)
    }

    @Test
    fun `a new track starts still with the configured speed uncertainty`() {
        val state = CvKalman.init(Point2(1.0, 3.0), r, 1.5)

        assertEquals(Point2(1.0, 3.0), state.position)
        assertEquals(0.0, state.speed, 0.0)
        assertEquals(2.25, state.p[2, 2], 1e-12)
    }

    @Test
    fun `measurement noise is radial sigma along the line of sight and angular across it`() {
        val ahead = CvKalman.measurementNoise(3.0, 0.0, 0.0, overlap = false, params = params)

        assertEquals(0.04, ahead[1, 1], 1e-12)
        assertEquals(Math.pow(3.0 * Math.toRadians(3.5), 2.0), ahead[0, 0], 1e-12)
    }

    @Test
    fun `angular noise grows with 1 over cos theta and is capped at 70 degrees`() {
        val wide = CvKalman.measurementNoise(3.0, 80.0, 0.0, overlap = false, params = params)
        val at70 = CvKalman.measurementNoise(3.0, 70.0, 0.0, overlap = false, params = params)

        assertEquals(at70[0, 0], wide[0, 0], 1e-12)
        assertTrue(at70[0, 0] > r[0, 0] * 8)
    }

    @Test
    fun `overlap inflates the noise by 1_5`() {
        val overlap = CvKalman.measurementNoise(3.0, 0.0, 0.0, overlap = true, params = params)

        assertEquals(r[1, 1] * 1.5, overlap[1, 1], 1e-12)
    }

    @Test
    fun `a target walking at 1_2 m per s is tracked within 0_1 m per s`() {
        val final = (1..30).fold(CvKalman.init(Point2(-2.0, 3.0), r, 1.5)) { s, k ->
            val predicted = CvKalman.predict(s, 0.1, params.processNoise)
            CvKalman.update(predicted, Point2(-2.0 + 0.12 * k, 3.0), r)
        }

        assertEquals(1.2, final.velocity.x, 0.1)
        assertEquals(0.0, final.velocity.y, 0.1)
    }

    @Test
    fun `coasting decays velocity with a 1 s time constant`() {
        val moving = CvKalman.init(Point2(0.0, 3.0), r, 1.5).copy(x = Matrix.column(0.0, 3.0, 1.0, 0.0))

        val coasted = CvKalman.coast(moving, 1.0, params.processNoise, 1.0)

        assertEquals(Math.exp(-1.0), coasted.velocity.x, 1e-9)
        assertEquals(1.0 - Math.exp(-1.0), coasted.position.x, 1e-9)
    }

    @Test
    fun `mahalanobis distance of a residual equal to one sigma is one`() {
        val state = CvKalman.init(Point2(0.0, 3.0), Matrix.of(2, 2, 0.0, 0.0, 0.0, 0.0), 1.5)
        val unit = Matrix.of(2, 2, 1.0, 0.0, 0.0, 1.0)

        assertEquals(1.0, CvKalman.innovation(state, Point2(1.0, 3.0), unit).mahalanobis2, 1e-12)
    }

    @Test
    fun `the gating covariance can be capped for coasting tracks`() {
        val wide = CvKalman.init(Point2(0.0, 3.0), Matrix.of(2, 2, 4.0, 0.0, 0.0, 4.0), 1.5)
        val zero = Matrix.of(2, 2, 0.0, 0.0, 0.0, 0.0)

        val capped = CvKalman.innovation(wide, Point2(0.0, 3.0), zero, positionVarianceCap = 0.49)

        assertEquals(0.49, capped.covariance[0, 0], 1e-12)
    }
}
```

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/tracking/GnnTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.tracking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GnnTest {
    @Test
    fun `global optimum beats greedy nearest neighbour`() {
        val costs = mapOf((0 to 0) to 1.0, (0 to 1) to 2.0, (1 to 0) to 2.0)

        val assignment = assignExact(2, 2, newTrackCost = 9.21) { d, t -> costs[d to t] }

        assertEquals(listOf(1, 0), assignment.trackIndexByDetection)
        assertEquals(4.0, assignment.cost, 1e-12)
    }

    @Test
    fun `a detection outside every gate starts a new track`() {
        val assignment = assignExact(2, 1, newTrackCost = 9.21) { d, _ -> if (d == 0) 0.5 else null }

        assertEquals(listOf(0, null), assignment.trackIndexByDetection)
    }

    @Test
    fun `two detections never share one track`() {
        val assignment = assignExact(2, 1, newTrackCost = 9.21) { _, _ -> 1.0 }

        assertEquals(1, assignment.trackIndexByDetection.count { it == 0 })
    }

    @Test
    fun `no tracks means every detection is new`() {
        val assignment = assignExact(3, 0, newTrackCost = 9.21) { _, _ -> 0.0 }

        assertEquals(listOf(null, null, null), assignment.trackIndexByDetection)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.tracking.KalmanTest" --tests "io.github.santiquiroz.blindside.core.tracking.GnnTest")`

Expected: FAIL. `:radar-core:compileTestKotlin` reports `Unresolved reference` for `Matrix`, `CvKalman`, `assignExact`.

- [ ] **Step 3: Write the implementation**

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/Matrix.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.tracking

class Matrix(val rows: Int, val cols: Int, private val values: DoubleArray) {
    init {
        require(values.size == rows * cols) { "expected ${rows * cols} values, got ${values.size}" }
    }

    operator fun get(row: Int, col: Int): Double = values[row * cols + col]

    operator fun plus(other: Matrix): Matrix = elementwise(other) { a, b -> a + b }

    operator fun minus(other: Matrix): Matrix = elementwise(other) { a, b -> a - b }

    operator fun times(factor: Double): Matrix = Matrix(rows, cols, DoubleArray(values.size) { values[it] * factor })

    operator fun times(other: Matrix): Matrix {
        require(cols == other.rows) { "shape mismatch ${rows}x$cols * ${other.rows}x${other.cols}" }
        return build(rows, other.cols) { r, c -> (0 until cols).sumOf { k -> this[r, k] * other[k, c] } }
    }

    fun transpose(): Matrix = build(cols, rows) { r, c -> this[c, r] }

    fun inverse2x2(): Matrix {
        require(rows == 2 && cols == 2) { "inverse2x2 needs a 2x2 matrix" }
        val det = this[0, 0] * this[1, 1] - this[0, 1] * this[1, 0]
        return of(2, 2, this[1, 1] / det, -this[0, 1] / det, -this[1, 0] / det, this[0, 0] / det)
    }

    private fun elementwise(other: Matrix, op: (Double, Double) -> Double): Matrix {
        require(rows == other.rows && cols == other.cols) { "shape mismatch" }
        return Matrix(rows, cols, DoubleArray(values.size) { op(values[it], other.values[it]) })
    }

    companion object {
        fun of(rows: Int, cols: Int, vararg values: Double) = Matrix(rows, cols, values.copyOf())

        fun build(rows: Int, cols: Int, cell: (Int, Int) -> Double) =
            Matrix(rows, cols, DoubleArray(rows * cols) { cell(it / cols, it % cols) })

        fun identity(size: Int) = build(size, size) { r, c -> if (r == c) 1.0 else 0.0 }

        fun column(vararg values: Double) = Matrix(values.size, 1, values.copyOf())
    }
}
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/CvKalman.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.TrackingParams
import io.github.santiquiroz.blindside.core.geometry.Point2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

data class KalmanState(val x: Matrix, val p: Matrix) {
    val position: Point2 get() = Point2(x[0, 0], x[1, 0])
    val velocity: Point2 get() = Point2(x[2, 0], x[3, 0])
    val speed: Double get() = velocity.norm
}

data class Innovation(val residual: Point2, val covariance: Matrix) {
    val mahalanobis2: Double
        get() {
            val nu = Matrix.column(residual.x, residual.y)
            return (nu.transpose() * covariance.inverse2x2() * nu)[0, 0]
        }
}

object CvKalman {
    private val H = Matrix.of(2, 4, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    fun init(position: Point2, r: Matrix, sigmaSpeedMps: Double): KalmanState {
        val v = sigmaSpeedMps * sigmaSpeedMps
        val p = Matrix.build(4, 4) { row, col ->
            when {
                row < 2 && col < 2 -> r[row, col]
                row == col -> v
                else -> 0.0
            }
        }
        return KalmanState(Matrix.column(position.x, position.y, 0.0, 0.0), p)
    }

    fun predict(state: KalmanState, dtS: Double, q: Double): KalmanState =
        propagate(state, transition(dtS, 1.0), dtS, q)

    fun coast(state: KalmanState, dtS: Double, q: Double, speedTauS: Double): KalmanState {
        val decay = exp(-dtS / speedTauS)
        return propagate(state, transition(speedTauS * (1.0 - decay), decay), dtS, q)
    }

    fun freezeVelocity(state: KalmanState): KalmanState =
        state.copy(x = Matrix.column(state.x[0, 0], state.x[1, 0], 0.0, 0.0))

    fun innovation(state: KalmanState, z: Point2, r: Matrix, positionVarianceCap: Double? = null): Innovation {
        val residual = z - state.position
        val pPos = capped(H * state.p * H.transpose(), positionVarianceCap)
        return Innovation(residual, pPos + r)
    }

    fun update(state: KalmanState, z: Point2, r: Matrix): KalmanState {
        val s = H * state.p * H.transpose() + r
        val k = state.p * H.transpose() * s.inverse2x2()
        val nu = Matrix.column(z.x - state.x[0, 0], z.y - state.x[1, 0])
        val i = Matrix.identity(4)
        val joseph = (i - k * H) * state.p * (i - k * H).transpose() + k * r * k.transpose()
        return KalmanState(state.x + k * nu, joseph)
    }

    fun measurementNoise(rangeM: Double, angleOffBoresightDeg: Double, bearingInFrameDeg: Double, overlap: Boolean, params: TrackingParams): Matrix {
        val cosTheta = max(cos(Math.toRadians(angleOffBoresightDeg)), cos(Math.toRadians(params.maxAngleForNoiseDeg)))
        val sigmaT = rangeM * Math.toRadians(params.sigmaAngleDeg) / cosTheta
        val phi = Math.toRadians(bearingInFrameDeg)
        val radial = Matrix.column(sin(phi), cos(phi))
        val tangential = Matrix.column(cos(phi), -sin(phi))
        val r = radial * radial.transpose() * (params.sigmaRangeM * params.sigmaRangeM) +
            tangential * tangential.transpose() * (sigmaT * sigmaT)
        return if (overlap) r * params.overlapNoiseFactor else r
    }

    private fun transition(positionGain: Double, velocityGain: Double) = Matrix.of(
        4, 4,
        1.0, 0.0, positionGain, 0.0,
        0.0, 1.0, 0.0, positionGain,
        0.0, 0.0, velocityGain, 0.0,
        0.0, 0.0, 0.0, velocityGain,
    )

    private fun propagate(state: KalmanState, f: Matrix, dtS: Double, q: Double): KalmanState =
        KalmanState(f * state.x, f * state.p * f.transpose() + processNoise(dtS, q))

    private fun processNoise(dtS: Double, q: Double): Matrix {
        val dt2 = dtS * dtS / 2.0
        val dt3 = dtS * dtS * dtS / 3.0
        return Matrix.of(
            4, 4,
            dt3, 0.0, dt2, 0.0,
            0.0, dt3, 0.0, dt2,
            dt2, 0.0, dtS, 0.0,
            0.0, dt2, 0.0, dtS,
        ) * q
    }

    private fun capped(pPos: Matrix, cap: Double?): Matrix {
        if (cap == null) return pPos
        val largest = max(pPos[0, 0], pPos[1, 1])
        return if (largest <= cap) pPos else pPos * (cap / largest)
    }
}
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/Gnn.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.tracking

data class Assignment(val trackIndexByDetection: List<Int?>, val cost: Double)

// Exact GNN by enumeration: at most 3 detections per LD2450 frame, so the search space stays tiny.
fun assignExact(detectionCount: Int, trackCount: Int, newTrackCost: Double, cost: (detection: Int, track: Int) -> Double?): Assignment =
    search(0, emptySet(), detectionCount, trackCount, newTrackCost, cost)

private fun search(
    detection: Int,
    used: Set<Int>,
    detectionCount: Int,
    trackCount: Int,
    newTrackCost: Double,
    cost: (Int, Int) -> Double?,
): Assignment {
    if (detection == detectionCount) return Assignment(emptyList(), 0.0)
    val options = (0 until trackCount).filter { it !in used }.mapNotNull { track ->
        cost(detection, track)?.let { c -> prepend(track, c, search(detection + 1, used + track, detectionCount, trackCount, newTrackCost, cost)) }
    }
    val unassigned = prepend(null, newTrackCost, search(detection + 1, used, detectionCount, trackCount, newTrackCost, cost))
    return (options + unassigned).minBy { it.cost }
}

private fun prepend(track: Int?, cost: Double, rest: Assignment) =
    Assignment(listOf(track) + rest.trackIndexByDetection, cost + rest.cost)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.tracking.KalmanTest" --tests "io.github.santiquiroz.blindside.core.tracking.GnnTest")`

Expected: PASS, 13 tests, 0 failures. Then run the whole module (`(cd watch && ./gradlew :radar-core:test)`) and confirm every earlier test still passes.

- [ ] **Step 5: Commit**

```bash
git add watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/Matrix.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/CvKalman.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/Gnn.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/tracking/KalmanTest.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/tracking/GnnTest.kt
git commit -m "feat: Kalman de velocidad constante CWNA y asignación GNN exacta"
```

### Task 11: Track model, stop-and-scan gate, lifecycle and display ids

Spec §6.5 and §6.6 as pure functions on one `Track`. Outcomes keep only evaluable windows, each with its start time (`WindowMark`, last 5). Confirmation (decision 1): never while the player moves; otherwise 3 hits among the last 5 evaluable windows that started at or after `gateOpenFromMs` (the end of the 0.5 s tail; `Long.MIN_VALUE` when the player has not moved). This gate replaces ghost rules 1-3 in the MVP; rule 4 (stale targets) and the 0.8 m exclusion live in the frame filter (Task 5). Window outcome: HIT if a radar associated a detection; MISS if an alive radar whose cone covers the prediction delivered a frame with a free `(0, 0, 0)` slot; otherwise not evaluable. Lifecycle: a tentative track dies after 2 evaluable misses **or 1 s of silence** (Review Focus 1); a confirmed track without a hit starts coasting (frozen if |v| < 0.3 m/s); coasting lasts 6 s still / 1.5 s moving, or 0.3 s then 5 s out of view once it leaves every alive cone. Display ids are inherited from a track deleted < 2 s ago whose prediction is < 1 m away; burying prunes old entries (Review Focus 5).

**Files:**
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/Track.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/ConfirmationPolicy.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/Lifecycle.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/DisplayIds.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/tracking/LifecycleTest.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/tracking/ConfirmationPolicyTest.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/tracking/DisplayIdsTest.kt`

**Interfaces:**
- Consumes: `KalmanState`, `CvKalman`, `Matrix` (Task 10); `Point2` (Task 5); `TrackingParams` (Task 2).
- Produces (`io.github.santiquiroz.blindside.core.tracking`): `enum class TrackStatus { TENTATIVE, CONFIRMED, COASTING, OUT_OF_VIEW }`; `data class WindowMark(startMs: Long, hit: Boolean)`; `data class Track(id, displayId, kalman, stateMs, bornMs, lastHitMs, status = TENTATIVE, outcomes: List<WindowMark>, windowRadars: Set<Int>, previousWindowRadars: Set<Int>, lostStill: Boolean) { hitThisWindow, isLost, isDisplayed, recentRadars, fun predictedTo(tMs, params): Track }`; `data class MotionContext(moving: Boolean, gateOpenFromMs: Long) { companion STILL }`; `enum class WindowOutcome { HIT, MISS, NOT_EVALUABLE }`; `fun canConfirm(track, outcomes: List<WindowMark>, motion, params): Boolean`; `fun hitsAfterGate(outcomes, gateOpenFromMs, params): Int`; `fun windowOutcome(track, evidenceRadars, coveringRadars): WindowOutcome`; `fun Track.closeWindow(outcome, covered: Boolean, windowEndMs, params): Track?` (null = delete; the recorded mark starts at `windowEndMs − windowMs`); `fun Track.reacquired(): Track`; `fun Track.coastLimitMs(params): Long`; `data class DeadTrack(displayId, position, velocity, diedMs)`; `data class DisplayIds(next = 1, graveyard) { fun assign(position, tMs, params): Pair<DisplayIds, Int>; fun bury(track, tMs, params): DisplayIds }`.

- [ ] **Step 1: Write the failing tests**

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/tracking/LifecycleTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.TrackingParams
import io.github.santiquiroz.blindside.core.geometry.Point2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LifecycleTest {
    private val params = TrackingParams()

    @Test
    fun `two evaluable misses in a row delete a tentative track`() {
        val once = track(TrackStatus.TENTATIVE, outcomes = listOf(WindowMark(900, true))).closeWindow(WindowOutcome.MISS, true, 1_100, params)!!

        assertNull(once.closeWindow(WindowOutcome.MISS, true, 1_200, params))
    }

    @Test
    fun `a tentative track silent for more than 1 s is deleted even without evaluable windows`() {
        assertNotNull(track(TrackStatus.TENTATIVE).closeWindow(WindowOutcome.NOT_EVALUABLE, true, 2_000, params))
        assertNull(track(TrackStatus.TENTATIVE).closeWindow(WindowOutcome.NOT_EVALUABLE, true, 2_100, params))
    }

    @Test
    fun `only evaluable windows are recorded with their start and at most five are kept`() {
        val full = track(TrackStatus.TENTATIVE, outcomes = List(5) { WindowMark(500L + it * 100, true) })

        val afterHit = full.closeWindow(WindowOutcome.HIT, true, 1_100, params)!!
        val afterSkip = full.closeWindow(WindowOutcome.NOT_EVALUABLE, true, 1_100, params)!!

        assertEquals(5, afterHit.outcomes.size)
        assertEquals(WindowMark(1_000, true), afterHit.outcomes.last())
        assertEquals(full.outcomes, afterSkip.outcomes)
    }

    @Test
    fun `a confirmed track hit this window stays confirmed and rolls its radars`() {
        val closed = track(TrackStatus.CONFIRMED, windowRadars = setOf(0)).closeWindow(WindowOutcome.HIT, true, 1_100, params)!!

        assertEquals(TrackStatus.CONFIRMED, closed.status)
        assertEquals(setOf(0), closed.previousWindowRadars)
        assertTrue(closed.windowRadars.isEmpty())
    }

    @Test
    fun `a confirmed track without a hit starts coasting and is frozen if it was slow`() {
        val moving = track(TrackStatus.CONFIRMED, speed = 1.0).closeWindow(WindowOutcome.MISS, true, 1_100, params)!!
        val slow = track(TrackStatus.CONFIRMED, speed = 0.1).closeWindow(WindowOutcome.MISS, true, 1_100, params)!!

        assertEquals(TrackStatus.COASTING, moving.status)
        assertFalse(moving.lostStill)
        assertTrue(slow.lostStill)
        assertEquals(0.0, slow.kalman.speed, 0.0)
    }

    @Test
    fun `coasting lasts 1_5 s when lost moving and 6 s when lost still`() {
        val moving = track(TrackStatus.COASTING)
        val still = track(TrackStatus.COASTING).copy(lostStill = true)

        assertNotNull(moving.closeWindow(WindowOutcome.NOT_EVALUABLE, true, 2_500, params))
        assertNull(moving.closeWindow(WindowOutcome.NOT_EVALUABLE, true, 2_600, params))
        assertNotNull(still.closeWindow(WindowOutcome.NOT_EVALUABLE, true, 7_000, params))
        assertNull(still.closeWindow(WindowOutcome.NOT_EVALUABLE, true, 7_100, params))
    }

    @Test
    fun `leaving the cone coasts 0_3 s, then shows out of view for 5 s`() {
        val lost = track(TrackStatus.COASTING)

        assertEquals(TrackStatus.COASTING, lost.closeWindow(WindowOutcome.NOT_EVALUABLE, false, 1_300, params)?.status)
        assertEquals(TrackStatus.OUT_OF_VIEW, lost.closeWindow(WindowOutcome.NOT_EVALUABLE, false, 1_400, params)?.status)
        assertEquals(TrackStatus.OUT_OF_VIEW, lost.closeWindow(WindowOutcome.NOT_EVALUABLE, false, 6_300, params)?.status)
        assertNull(lost.closeWindow(WindowOutcome.NOT_EVALUABLE, false, 6_400, params))
    }

    @Test
    fun `a hit reacquires a lost track as confirmed`() {
        val back = track(TrackStatus.OUT_OF_VIEW).copy(lostStill = true).reacquired()

        assertEquals(TrackStatus.CONFIRMED, back.status)
        assertFalse(back.lostStill)
    }

    private fun track(
        status: TrackStatus,
        speed: Double = 1.0,
        outcomes: List<WindowMark> = emptyList(),
        windowRadars: Set<Int> = emptySet(),
    ): Track {
        val r = Matrix.of(2, 2, 0.04, 0.0, 0.0, 0.04)
        val kalman = CvKalman.init(Point2(0.0, 3.0), r, 1.5).copy(x = Matrix.column(0.0, 3.0, speed, 0.0))
        return Track(1, 1, kalman, stateMs = 1_000, bornMs = 500, lastHitMs = 1_000, status = status, outcomes = outcomes, windowRadars = windowRadars)
    }
}
```

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/tracking/ConfirmationPolicyTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.TrackingParams
import io.github.santiquiroz.blindside.core.geometry.Point2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfirmationPolicyTest {
    private val params = TrackingParams()
    private val threeOfFour = listOf(WindowMark(1_000, true), WindowMark(1_100, false), WindowMark(1_200, true), WindowMark(1_300, true))

    @Test
    fun `three hits in the last five evaluable windows confirm when still`() {
        assertTrue(canConfirm(track(), threeOfFour, MotionContext.STILL, params))
        assertFalse(canConfirm(track(), threeOfFour.drop(1), MotionContext.STILL, params))
    }

    @Test
    fun `nothing is promoted while the player moves`() {
        assertFalse(canConfirm(track(), threeOfFour, MotionContext(moving = true, gateOpenFromMs = Long.MIN_VALUE), params))
    }

    @Test
    fun `windows that started before the end of the tail do not count`() {
        val gate = MotionContext(moving = false, gateOpenFromMs = 1_100)

        assertEquals(2, hitsAfterGate(threeOfFour, gate.gateOpenFromMs, params))
        assertFalse(canConfirm(track(), threeOfFour, gate, params))
        assertTrue(canConfirm(track(), threeOfFour + WindowMark(1_400, true), gate, params))
    }

    @Test
    fun `a track that is already confirmed is not confirmed again`() {
        assertFalse(canConfirm(track().copy(status = TrackStatus.CONFIRMED), threeOfFour, MotionContext.STILL, params))
    }

    @Test
    fun `window outcome follows hits, evidence and coverage`() {
        val hit = track().copy(windowRadars = setOf(0))

        assertEquals(WindowOutcome.HIT, windowOutcome(hit, setOf(0), setOf(0)))
        assertEquals(WindowOutcome.MISS, windowOutcome(track(), setOf(0), setOf(0)))
        assertEquals(WindowOutcome.NOT_EVALUABLE, windowOutcome(track(), setOf(1), setOf(0)))
        assertEquals(WindowOutcome.NOT_EVALUABLE, windowOutcome(track(), emptySet(), setOf(0)))
    }

    private fun track(): Track {
        val r = Matrix.of(2, 2, 0.04, 0.0, 0.0, 0.04)
        return Track(1, 1, CvKalman.init(Point2(0.0, 3.0), r, 1.5), stateMs = 1_000, bornMs = 1_000, lastHitMs = 1_000)
    }
}
```

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/tracking/DisplayIdsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.TrackingParams
import io.github.santiquiroz.blindside.core.geometry.Point2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DisplayIdsTest {
    private val params = TrackingParams()
    private val dead = DeadTrack(displayId = 7, position = Point2(0.0, 3.0), velocity = Point2(1.0, 0.0), diedMs = 10_000)

    @Test
    fun `a new track near the prediction of a recently deleted one inherits its id`() {
        val (_, id) = DisplayIds(next = 8, graveyard = listOf(dead)).assign(Point2(1.2, 3.1), 11_000, params)

        assertEquals(7, id)
    }

    @Test
    fun `an inherited id leaves the graveyard`() {
        val (ids, _) = DisplayIds(next = 8, graveyard = listOf(dead)).assign(Point2(1.0, 3.0), 11_000, params)

        assertEquals(emptyList<DeadTrack>(), ids.graveyard)
    }

    @Test
    fun `too far or too old gets a fresh id`() {
        val ids = DisplayIds(next = 8, graveyard = listOf(dead))

        assertEquals(8, ids.assign(Point2(3.0, 3.0), 11_000, params).second)
        assertEquals(8, ids.assign(Point2(2.0, 3.0), 12_100, params).second)
    }

    @Test
    fun `burying prunes entries too old to be inherited`() {
        val track = Track(3, 9, CvKalman.init(Point2(0.0, 3.0), Matrix.of(2, 2, 0.04, 0.0, 0.0, 0.04), 1.5), 0, 0, 0)

        val ids = DisplayIds(graveyard = listOf(dead)).bury(track, 12_500, params)

        assertEquals(listOf(9), ids.graveyard.map { it.displayId })
    }

    @Test
    fun `fresh ids increase`() {
        val (first, a) = DisplayIds().assign(Point2(0.0, 3.0), 0, params)
        val (_, b) = first.assign(Point2(5.0, 3.0), 0, params)

        assertEquals(listOf(1, 2), listOf(a, b))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.tracking.LifecycleTest" --tests "io.github.santiquiroz.blindside.core.tracking.ConfirmationPolicyTest" --tests "io.github.santiquiroz.blindside.core.tracking.DisplayIdsTest")`

Expected: FAIL. `:radar-core:compileTestKotlin` reports `Unresolved reference` for `Track`, `TrackStatus`, `WindowMark`, `WindowOutcome`, `MotionContext`, `DisplayIds`, `DeadTrack`.

- [ ] **Step 3: Write the implementation**

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/Track.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.TrackingParams

enum class TrackStatus { TENTATIVE, CONFIRMED, COASTING, OUT_OF_VIEW }

data class WindowMark(val startMs: Long, val hit: Boolean)

data class Track(
    val id: Int,
    val displayId: Int,
    val kalman: KalmanState,
    val stateMs: Long,
    val bornMs: Long,
    val lastHitMs: Long,
    val status: TrackStatus = TrackStatus.TENTATIVE,
    val outcomes: List<WindowMark> = emptyList(),
    val windowRadars: Set<Int> = emptySet(),
    val previousWindowRadars: Set<Int> = emptySet(),
    val lostStill: Boolean = false,
) {
    val hitThisWindow: Boolean get() = windowRadars.isNotEmpty()
    val isLost: Boolean get() = status == TrackStatus.COASTING || status == TrackStatus.OUT_OF_VIEW
    val isDisplayed: Boolean get() = status != TrackStatus.TENTATIVE
    val recentRadars: Set<Int> get() = windowRadars + previousWindowRadars

    fun predictedTo(tMs: Long, params: TrackingParams): Track {
        val dtS = (tMs - stateMs) / 1000.0
        if (dtS <= 0.0) return this
        val next = if (isLost) {
            CvKalman.coast(kalman, dtS, params.processNoise, params.coastSpeedTauS)
        } else {
            CvKalman.predict(kalman, dtS, params.processNoise)
        }
        return copy(kalman = next, stateMs = tMs)
    }
}
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/ConfirmationPolicy.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.TrackingParams

data class MotionContext(val moving: Boolean, val gateOpenFromMs: Long) {
    companion object {
        val STILL = MotionContext(moving = false, gateOpenFromMs = Long.MIN_VALUE)
    }
}

enum class WindowOutcome { HIT, MISS, NOT_EVALUABLE }

// Spec §6.6 MVP "detenerse y escanear": nothing is promoted while moving or in the 0.5 s tail; only later windows count.
fun canConfirm(track: Track, outcomes: List<WindowMark>, motion: MotionContext, params: TrackingParams): Boolean {
    if (track.status != TrackStatus.TENTATIVE || motion.moving) return false
    return hitsAfterGate(outcomes, motion.gateOpenFromMs, params) >= params.confirmHits
}

fun hitsAfterGate(outcomes: List<WindowMark>, gateOpenFromMs: Long, params: TrackingParams): Int =
    outcomes.filter { it.startMs >= gateOpenFromMs }.takeLast(params.confirmWindows).count { it.hit }

fun windowOutcome(track: Track, evidenceRadars: Set<Int>, coveringRadars: Set<Int>): WindowOutcome = when {
    track.hitThisWindow -> WindowOutcome.HIT
    coveringRadars.any { it in evidenceRadars } -> WindowOutcome.MISS
    else -> WindowOutcome.NOT_EVALUABLE
}
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/Lifecycle.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.TrackingParams

fun Track.closeWindow(outcome: WindowOutcome, covered: Boolean, windowEndMs: Long, params: TrackingParams): Track? {
    val rolled = recordOutcome(outcome, windowEndMs - params.windowMs, params).copy(previousWindowRadars = windowRadars, windowRadars = emptySet())
    return when (status) {
        TrackStatus.TENTATIVE -> if (rolled.isStaleTentative(windowEndMs, params)) null else rolled
        TrackStatus.CONFIRMED -> if (hitThisWindow) rolled else rolled.startCoasting(params)
        TrackStatus.COASTING, TrackStatus.OUT_OF_VIEW -> rolled.coastOrExpire(covered, windowEndMs, params)
    }
}

fun Track.reacquired(): Track = if (isLost) copy(status = TrackStatus.CONFIRMED, lostStill = false) else this

fun Track.coastLimitMs(params: TrackingParams): Long = if (lostStill) params.coastStillMs else params.coastMovingMs

private fun Track.recordOutcome(outcome: WindowOutcome, windowStartMs: Long, params: TrackingParams): Track = when (outcome) {
    WindowOutcome.NOT_EVALUABLE -> this
    else -> copy(outcomes = (outcomes + WindowMark(windowStartMs, outcome == WindowOutcome.HIT)).takeLast(params.confirmWindows))
}

private fun Track.missedLast(count: Int): Boolean =
    outcomes.size >= count && outcomes.takeLast(count).none { it.hit }

// Saturated frames are never evaluable, so a silence timeout keeps tentatives from piling up while walking.
private fun Track.isStaleTentative(windowEndMs: Long, params: TrackingParams): Boolean =
    missedLast(params.tentativeMaxMisses) || windowEndMs - lastHitMs > params.tentativeMaxSilenceMs

private fun Track.startCoasting(params: TrackingParams): Track {
    val still = kalman.speed < params.stillSpeedMps
    val frozen = if (still) CvKalman.freezeVelocity(kalman) else kalman
    return copy(status = TrackStatus.COASTING, lostStill = still, kalman = frozen)
}

private fun Track.coastOrExpire(covered: Boolean, windowEndMs: Long, params: TrackingParams): Track? {
    val elapsed = windowEndMs - lastHitMs
    if (covered) return if (elapsed > coastLimitMs(params)) null else copy(status = TrackStatus.COASTING)
    return when {
        elapsed > params.coastExitMs + params.outOfViewMs -> null
        elapsed > params.coastExitMs -> copy(status = TrackStatus.OUT_OF_VIEW)
        else -> copy(status = TrackStatus.COASTING)
    }
}
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/DisplayIds.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.TrackingParams
import io.github.santiquiroz.blindside.core.geometry.Point2

data class DeadTrack(val displayId: Int, val position: Point2, val velocity: Point2, val diedMs: Long) {
    fun predictedAt(tMs: Long): Point2 = position + velocity * ((tMs - diedMs) / 1000.0)
}

data class DisplayIds(val next: Int = 1, val graveyard: List<DeadTrack> = emptyList()) {
    fun assign(position: Point2, tMs: Long, params: TrackingParams): Pair<DisplayIds, Int> {
        val recent = graveyard.filter { tMs - it.diedMs < params.inheritMaxAgeMs }
        val heir = recent
            .map { it to (it.predictedAt(tMs) - position).norm }
            .filter { (_, distance) -> distance < params.inheritDistanceM }
            .minByOrNull { (_, distance) -> distance }
            ?.first
        if (heir != null) return copy(graveyard = recent - heir) to heir.displayId
        return DisplayIds(next + 1, recent) to next
    }

    fun bury(track: Track, tMs: Long, params: TrackingParams): DisplayIds {
        val recent = graveyard.filter { tMs - it.diedMs < params.inheritMaxAgeMs }
        return copy(graveyard = recent + DeadTrack(track.displayId, track.kalman.position, track.kalman.velocity, tMs))
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.tracking.LifecycleTest" --tests "io.github.santiquiroz.blindside.core.tracking.ConfirmationPolicyTest" --tests "io.github.santiquiroz.blindside.core.tracking.DisplayIdsTest")`

Expected: PASS, 18 tests, 0 failures. Then run the whole module (`(cd watch && ./gradlew :radar-core:test)`) and confirm every earlier test still passes.

- [ ] **Step 5: Commit**

```bash
git add watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/Track.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/ConfirmationPolicy.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/Lifecycle.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/DisplayIds.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/tracking/LifecycleTest.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/tracking/ConfirmationPolicyTest.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/tracking/DisplayIdsTest.kt
git commit -m "feat: ciclo de vida de pistas, compuerta detenerse y escanear e IDs de pantalla"
```

### Task 12: Tracker: 100 ms window grid and per-frame association

Spec §6.1 steps 4-7 and §6.5 windows. Per radar frame, in `t_ms` order: drop it if older than the last processed frame; close the open window when the frame belongs to a later one (outcome per track from hits, the radars that delivered a frame with a free slot and the alive radars covering the predicted position; confirmation with the motion context at the window end); predict every track; rotate detections into the tracking frame with ψ(t − τ); run the exact GNN with the coast-capped gate; update matched tracks (a hit on a lost track reacquires it); spawn tentatives (inherited display id); confirm eagerly with the current window counted as a hit (decision 2). Every update of a confirmed track also reports its NIS (`TrackerStep.nis`), which the pipeline sums while turning for the τ sweep (decision 12). `advanceTo` lets the pipeline close windows when frames stop.

**Files:**
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/Association.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/Tracker.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/tracking/TrackerFixtures.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/tracking/TrackLifecycleTest.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/tracking/StopAndScanTest.kt`

**Interfaces:**
- Consumes: everything from Task 11; `CvKalman`, `assignExact` (Task 10); `Detection`, `FilteredFrame`, geometry helpers (Task 5); `RadarMount`, `TuningParams`, `defaultMounts` (Task 2).
- Produces (`io.github.santiquiroz.blindside.core.tracking`): `data class Measurement(detection, position, r)`; `fun toMeasurement(detection, yawDeg, mounts, tuning): Measurement`; `fun gateCost(track, measurement, params): Double?`; `fun associate(tracks, measurements, params): List<Int?>`; `fun normalizedInnovation(track, measurement): Double`; `data class TrackerContext(mounts, aliveRadars: Set<Int>, yawAt: (Long) -> Double, motionAt: (Long) -> MotionContext)`; `data class TrackerState(tracks, nextTrackId, ids: DisplayIds, windowIndex: Long?, evidenceRadars: Set<Int>, lastFrameMs: Long?)`; `data class TrackerStep(state: TrackerState, confirmed: List<Track>, nis: List<Double>) { fun then(next) }`; `object Tracker { fun isOutOfOrder(state, tMs): Boolean; fun onFrame(state, frame: FilteredFrame, ctx, tuning): TrackerStep; fun advanceTo(state, tMs, ctx, tuning): TrackerStep }`.
- Test fixtures (test source set, reused by nothing else): `FORWARD_RADAR`, `testContext`, `frameOf`, `replay`, `walkingTarget`, `emptyFrames`, `Replayed`.

- [ ] **Step 1: Write the failing tests**

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/tracking/TrackerFixtures.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.RadarMount
import io.github.santiquiroz.blindside.core.config.TuningParams
import io.github.santiquiroz.blindside.core.geometry.Detection
import io.github.santiquiroz.blindside.core.geometry.FilteredFrame
import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.geometry.bodyToRadar

val FORWARD_RADAR = listOf(RadarMount(0, 0.0, 0.0, yawDeg = 0.0))

fun testContext(
    mounts: List<RadarMount> = FORWARD_RADAR,
    motion: (Long) -> MotionContext = { MotionContext.STILL },
) = TrackerContext(mounts, mounts.map { it.radarId }.toSet(), { 0.0 }, motion)

fun frameOf(
    radarId: Int,
    tMs: Long,
    points: List<Point2>,
    speedMps: Double = -0.5,
    mounts: List<RadarMount> = FORWARD_RADAR,
    occupiedSlots: Int = points.size,
): FilteredFrame {
    val mount = mounts.first { it.radarId == radarId }
    val detections = points.map { Detection(radarId, tMs, bodyToRadar(it, mount), it, speedMps) }
    return FilteredFrame(radarId, tMs, detections, occupiedSlots, implausible = 0, stale = 0, nearField = 0)
}

data class Replayed(val states: List<TrackerState>, val confirmations: List<Pair<Long, Track>>) {
    val last: TrackerState get() = states.last()
}

fun replay(frames: List<FilteredFrame>, ctx: TrackerContext, tuning: TuningParams = TuningParams()): Replayed {
    val start = Replayed(listOf(TrackerState()), emptyList())
    return frames.fold(start) { acc, frame ->
        val step = Tracker.onFrame(acc.last, frame, ctx, tuning)
        Replayed(acc.states + step.state, acc.confirmations + step.confirmed.map { frame.tMs to it })
    }
}

fun walkingTarget(fromMs: Long, frames: Int, start: Point2, velocity: Point2, radarId: Int = 0, phaseMs: Long = 5): List<FilteredFrame> =
    (0 until frames).map { k ->
        val tMs = fromMs + phaseMs + k * 100L
        frameOf(radarId, tMs, listOf(start + velocity * (k * 0.1)))
    }

fun emptyFrames(fromMs: Long, frames: Int, radarId: Int = 0, phaseMs: Long = 5, mounts: List<RadarMount> = FORWARD_RADAR): List<FilteredFrame> =
    (0 until frames).map { k -> frameOf(radarId, fromMs + phaseMs + k * 100L, emptyList(), mounts = mounts) }
```

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/tracking/TrackLifecycleTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.TuningParams
import io.github.santiquiroz.blindside.core.geometry.Point2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrackLifecycleTest {
    private val ctx = testContext()

    @Test
    fun `three hits in consecutive windows confirm on the third hit`() {
        val run = replay(walkingTarget(1_000, 5, Point2(-1.0, 3.0), Point2(1.0, 0.0)), ctx)

        assertEquals(listOf(1_205L), run.confirmations.map { it.first })
        assertEquals(1, run.last.tracks.size)
        assertEquals(TrackStatus.CONFIRMED, run.last.tracks.single().status)
    }

    @Test
    fun `a tentative track is deleted after two evaluable misses`() {
        val frames = walkingTarget(1_000, 1, Point2(-1.0, 3.0), Point2(1.0, 0.0)) + emptyFrames(1_100, 3)

        val run = replay(frames, ctx)

        assertTrue(run.last.tracks.isEmpty())
        assertTrue(run.confirmations.isEmpty())
    }

    @Test
    fun `saturated frames are not evaluable and do not delete a tentative track`() {
        val saturated = (0 until 3).map { k -> frameOf(0, 1_105 + k * 100L, emptyList(), occupiedSlots = 3) }

        val run = replay(walkingTarget(1_000, 1, Point2(-1.0, 3.0), Point2(1.0, 0.0)) + saturated, ctx)

        assertEquals(1, run.last.tracks.size)
    }

    @Test
    fun `a tentative track silent for more than 1 s is dropped even when nothing is evaluable`() {
        val saturated = (0 until 15).map { k -> frameOf(0, 1_105 + k * 100L, emptyList(), occupiedSlots = 3) }

        val run = replay(walkingTarget(1_000, 1, Point2(-1.0, 3.0), Point2(1.0, 0.0)) + saturated, ctx)

        assertEquals(1, run.states[1 + 9].tracks.size)
        assertTrue(run.last.tracks.isEmpty())
    }

    @Test
    fun `a confirmed track lost while moving coasts 1_5 s then disappears`() {
        val frames = walkingTarget(1_000, 4, Point2(-1.0, 3.0), Point2(1.0, 0.0)) + emptyFrames(1_400, 20)

        val run = replay(frames, ctx)

        val at1_4s = run.states[4 + 14]
        assertEquals(TrackStatus.COASTING, at1_4s.tracks.single().status)
        assertTrue(run.last.tracks.isEmpty())
    }

    @Test
    fun `a confirmed track lost while still keeps its position for 6 s`() {
        val frames = walkingTarget(1_000, 6, Point2(0.0, 3.0), Point2(0.05, 0.0)) + emptyFrames(1_600, 64)

        val run = replay(frames, ctx)

        val at5_8s = run.states[6 + 58].tracks.single()
        assertTrue(at5_8s.lostStill)
        assertEquals(at5_8s.kalman.position, run.states[6 + 10].tracks.single().kalman.position)
        assertTrue(run.last.tracks.isEmpty())
    }

    @Test
    fun `a track leaving the cone becomes out of view, then is removed after 5_3 s`() {
        val frames = walkingTarget(1_000, 9, Point2(2.0, 2.0), Point2(1.5, 0.0)) + emptyFrames(1_900, 58)

        val run = replay(frames, ctx)

        assertEquals(TrackStatus.OUT_OF_VIEW, run.states[9 + 6].tracks.single().status)
        assertEquals(TrackStatus.OUT_OF_VIEW, run.states[9 + 50].tracks.single().status)
        assertTrue(run.last.tracks.isEmpty())
    }

    @Test
    fun `a hit while coasting reacquires the same track`() {
        val frames = walkingTarget(1_000, 4, Point2(-1.0, 3.0), Point2(1.0, 0.0)) +
            emptyFrames(1_400, 3) +
            listOf(frameOf(0, 1_705, listOf(Point2(-1.0 + 0.7 * 0.85, 3.0))))

        val run = replay(frames, ctx)

        val track = run.last.tracks.single()
        assertEquals(TrackStatus.CONFIRMED, track.status)
        assertEquals(1, run.confirmations.size)
    }

    @Test
    fun `a frame older than the last processed one is ignored`() {
        val run = replay(walkingTarget(1_000, 2, Point2(-1.0, 3.0), Point2(1.0, 0.0)), ctx)

        val step = Tracker.onFrame(run.last, frameOf(0, 1_050, listOf(Point2(3.0, 3.0))), ctx, TuningParams())

        assertEquals(run.last, step.state)
    }
}
```

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/tracking/StopAndScanTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.TuningParams
import io.github.santiquiroz.blindside.core.geometry.Point2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StopAndScanTest {
    private val movingUntil1500 = testContext(motion = { t -> MotionContext(moving = t < 1_500, gateOpenFromMs = 2_000) })

    @Test
    fun `while the player moves no new track is confirmed`() {
        val alwaysMoving = testContext(motion = { MotionContext(moving = true, gateOpenFromMs = Long.MAX_VALUE) })

        val run = replay(walkingTarget(1_000, 15, Point2(-2.0, 3.0), Point2(1.5, 0.0)), alwaysMoving)

        assertTrue(run.confirmations.isEmpty())
    }

    @Test
    fun `after the movement a new track needs three hits in windows after the 0_5 s tail`() {
        val run = replay(walkingTarget(1_000, 16, Point2(-2.0, 3.0), Point2(0.8, 0.0)), movingUntil1500)

        assertEquals(listOf(2_205L), run.confirmations.map { it.first })
    }

    @Test
    fun `a track confirmed before the movement keeps its id and is not confirmed again`() {
        val stillThenMoving = testContext(motion = { t -> MotionContext(moving = t >= 1_500, gateOpenFromMs = Long.MIN_VALUE) })

        val run = replay(walkingTarget(1_000, 12, Point2(-2.0, 3.0), Point2(0.8, 0.0)), stillThenMoving)

        assertEquals(listOf(1_205L), run.confirmations.map { it.first })
        assertEquals(TrackStatus.CONFIRMED, run.last.tracks.single().status)
        assertEquals(1, run.last.tracks.single().displayId)
    }

    @Test
    fun `a static reflector seen only while turning never confirms`() {
        val frames = (0 until 5).map { k -> frameOf(0, 1_005 + k * 100L, listOf(Point2(0.5, 3.0))) } + emptyFrames(1_500, 15)

        val run = replay(frames, movingUntil1500, TuningParams())

        assertTrue(run.confirmations.isEmpty())
        assertTrue(run.last.tracks.isEmpty())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.tracking.TrackLifecycleTest" --tests "io.github.santiquiroz.blindside.core.tracking.StopAndScanTest")`

Expected: FAIL. `:radar-core:compileTestKotlin` reports `Unresolved reference` for `Tracker`, `TrackerContext`, `TrackerState`, `TrackerStep`.

- [ ] **Step 3: Write the implementation**

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/Association.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.RadarMount
import io.github.santiquiroz.blindside.core.config.TrackingParams
import io.github.santiquiroz.blindside.core.config.TuningParams
import io.github.santiquiroz.blindside.core.geometry.Detection
import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.geometry.bodyToTracking
import io.github.santiquiroz.blindside.core.geometry.coveringRadars

data class Measurement(val detection: Detection, val position: Point2, val r: Matrix)

fun toMeasurement(detection: Detection, yawDeg: Double, mounts: List<RadarMount>, tuning: TuningParams): Measurement {
    val mount = mounts.first { it.radarId == detection.radarId }
    val overlap = coveringRadars(detection.bodyPoint, mounts, tuning.decode).size >= 2
    val bearingInFrame = mount.yawDeg + detection.radarBearingDeg + yawDeg
    val r = CvKalman.measurementNoise(detection.radarRangeM, detection.radarBearingDeg, bearingInFrame, overlap, tuning.tracking)
    return Measurement(detection, bodyToTracking(detection.bodyPoint, yawDeg), r)
}

fun gateCost(track: Track, measurement: Measurement, params: TrackingParams): Double? {
    val cap = if (track.isLost) params.coastGateSigmaM * params.coastGateSigmaM else null
    val innovation = CvKalman.innovation(track.kalman, measurement.position, measurement.r, cap)
    val limit = if (track.status == TrackStatus.TENTATIVE) params.gateTentativeM else params.gateConfirmedM
    val d2 = innovation.mahalanobis2
    return if (d2 <= params.gateChi2 && innovation.residual.norm <= limit) d2 else null
}

fun associate(tracks: List<Track>, measurements: List<Measurement>, params: TrackingParams): List<Int?> =
    assignExact(measurements.size, tracks.size, params.gateChi2) { d, t -> gateCost(tracks[t], measurements[d], params) }
        .trackIndexByDetection

fun normalizedInnovation(track: Track, measurement: Measurement): Double =
    CvKalman.innovation(track.kalman, measurement.position, measurement.r).mahalanobis2
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/Tracker.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.RadarMount
import io.github.santiquiroz.blindside.core.config.TuningParams
import io.github.santiquiroz.blindside.core.geometry.FilteredFrame
import io.github.santiquiroz.blindside.core.geometry.coveringRadars
import io.github.santiquiroz.blindside.core.geometry.trackingToBody

data class TrackerContext(
    val mounts: List<RadarMount>,
    val aliveRadars: Set<Int>,
    val yawAt: (Long) -> Double,
    val motionAt: (Long) -> MotionContext,
)

data class TrackerState(
    val tracks: List<Track> = emptyList(),
    val nextTrackId: Int = 1,
    val ids: DisplayIds = DisplayIds(),
    val windowIndex: Long? = null,
    val evidenceRadars: Set<Int> = emptySet(),
    val lastFrameMs: Long? = null,
)

data class TrackerStep(val state: TrackerState, val confirmed: List<Track> = emptyList(), val nis: List<Double> = emptyList()) {
    fun then(next: (TrackerState) -> TrackerStep): TrackerStep {
        val step = next(state)
        return TrackerStep(step.state, confirmed + step.confirmed, nis + step.nis)
    }
}

object Tracker {
    fun isOutOfOrder(state: TrackerState, tMs: Long): Boolean = state.lastFrameMs != null && tMs < state.lastFrameMs

    fun onFrame(state: TrackerState, frame: FilteredFrame, ctx: TrackerContext, tuning: TuningParams): TrackerStep {
        if (isOutOfOrder(state, frame.tMs)) return TrackerStep(state)
        return TrackerStep(state)
            .then { closeWindowsBefore(it, frame.tMs, ctx, tuning) }
            .then { TrackerStep(withEvidence(it, frame)) }
            .then { TrackerStep(predictAll(it, frame.tMs, tuning)) }
            .then { associateFrame(it, frame, ctx, tuning) }
    }

    fun advanceTo(state: TrackerState, tMs: Long, ctx: TrackerContext, tuning: TuningParams): TrackerStep =
        closeWindowsBefore(state, tMs, ctx, tuning)

    private fun withEvidence(state: TrackerState, frame: FilteredFrame): TrackerState =
        if (frame.canReportMiss) state.copy(evidenceRadars = state.evidenceRadars + frame.radarId) else state

    private fun predictAll(state: TrackerState, tMs: Long, tuning: TuningParams): TrackerState =
        state.copy(tracks = state.tracks.map { it.predictedTo(tMs, tuning.tracking) })

    private fun closeWindowsBefore(state: TrackerState, tMs: Long, ctx: TrackerContext, tuning: TuningParams): TrackerStep {
        val window = tMs / tuning.tracking.windowMs
        val open = state.windowIndex ?: return TrackerStep(state.copy(windowIndex = window))
        if (window <= open) return TrackerStep(state)
        val closed = closeWindow(state, (open + 1) * tuning.tracking.windowMs, ctx, tuning)
        return closed.copy(state = closed.state.copy(windowIndex = window, evidenceRadars = emptySet()))
    }

    private fun closeWindow(state: TrackerState, endMs: Long, ctx: TrackerContext, tuning: TuningParams): TrackerStep {
        val motion = ctx.motionAt(endMs)
        val yaw = ctx.yawAt(endMs)
        val results = state.tracks.map { it to closeTrack(it, state.evidenceRadars, endMs, yaw, ctx, tuning) }
        val dead = results.filter { it.second == null }.map { it.first.predictedTo(endMs, tuning.tracking) }
        val survivors = results.mapNotNull { it.second }
        val confirmed = survivors.filter { canConfirm(it, it.outcomes, motion, tuning.tracking) }.map { it.copy(status = TrackStatus.CONFIRMED) }
        val tracks = survivors.map { s -> confirmed.firstOrNull { it.id == s.id } ?: s }
        val ids = dead.fold(state.ids) { acc, track -> acc.bury(track, endMs, tuning.tracking) }
        return TrackerStep(state.copy(tracks = tracks, ids = ids), confirmed)
    }

    private fun closeTrack(track: Track, evidence: Set<Int>, endMs: Long, yaw: Double, ctx: TrackerContext, tuning: TuningParams): Track? {
        val body = trackingToBody(track.predictedTo(endMs, tuning.tracking).kalman.position, yaw)
        val covering = coveringRadars(body, ctx.mounts.filter { it.radarId in ctx.aliveRadars }, tuning.decode)
        val outcome = windowOutcome(track, evidence, covering)
        return track.closeWindow(outcome, covering.isNotEmpty(), endMs, tuning.tracking)
    }

    private fun associateFrame(state: TrackerState, frame: FilteredFrame, ctx: TrackerContext, tuning: TuningParams): TrackerStep {
        val yaw = ctx.yawAt(frame.tMs - tuning.imu.radarImuDelayMs)
        val measurements = frame.detections.map { toMeasurement(it, yaw, ctx.mounts, tuning) }
        val assignment = associate(state.tracks, measurements, tuning.tracking)
        val pairs = assignment.mapIndexedNotNull { d, t -> t?.let { state.tracks[it] to measurements[d] } }
        val updated = state.tracks.map { track -> pairs.firstOrNull { it.first.id == track.id }?.let { hit(track, it.second, frame) } ?: track }
        val spawned = measurements.filterIndexed { i, _ -> assignment[i] == null }
            .fold(state.copy(tracks = updated, lastFrameMs = frame.tMs)) { acc, m -> spawn(acc, m, frame, tuning) }
        val windowStart = frame.tMs / tuning.tracking.windowMs * tuning.tracking.windowMs
        val nis = pairs.filter { it.first.status == TrackStatus.CONFIRMED }.map { (track, m) -> normalizedInnovation(track, m) }
        return confirmEagerly(spawned, pairs.map { it.first.id }.toSet(), windowStart, ctx.motionAt(frame.tMs), tuning).copy(nis = nis)
    }

    private fun hit(track: Track, measurement: Measurement, frame: FilteredFrame): Track =
        track.copy(
            kalman = CvKalman.update(track.kalman, measurement.position, measurement.r),
            lastHitMs = frame.tMs,
            windowRadars = track.windowRadars + frame.radarId,
        ).reacquired()

    private fun spawn(state: TrackerState, m: Measurement, frame: FilteredFrame, tuning: TuningParams): TrackerState {
        val (ids, displayId) = state.ids.assign(m.position, frame.tMs, tuning.tracking)
        val track = Track(
            id = state.nextTrackId,
            displayId = displayId,
            kalman = CvKalman.init(m.position, m.r, tuning.tracking.sigmaInitialSpeedMps),
            stateMs = frame.tMs,
            bornMs = frame.tMs,
            lastHitMs = frame.tMs,
            windowRadars = setOf(frame.radarId),
        )
        return state.copy(tracks = state.tracks + track, nextTrackId = state.nextTrackId + 1, ids = ids)
    }

    // A hit is final, so confirming on it instead of at the window close saves up to one packet of latency.
    private fun confirmEagerly(state: TrackerState, hitIds: Set<Int>, windowStartMs: Long, motion: MotionContext, tuning: TuningParams): TrackerStep {
        val current = WindowMark(windowStartMs, hit = true)
        val confirmed = state.tracks
            .filter { it.id in hitIds && canConfirm(it, it.outcomes + current, motion, tuning.tracking) }
            .map { it.copy(status = TrackStatus.CONFIRMED) }
        val tracks = state.tracks.map { t -> confirmed.firstOrNull { it.id == t.id } ?: t }
        return TrackerStep(state.copy(tracks = tracks), confirmed)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.tracking.TrackLifecycleTest" --tests "io.github.santiquiroz.blindside.core.tracking.StopAndScanTest")`

Expected: PASS, 13 tests, 0 failures. Then run the whole module (`(cd watch && ./gradlew :radar-core:test)`) and confirm every earlier test still passes.

- [ ] **Step 5: Commit**

```bash
git add watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/Association.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/tracking/Tracker.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/tracking/TrackerFixtures.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/tracking/TrackLifecycleTest.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/tracking/StopAndScanTest.kt
git commit -m "feat: tracker por ventanas de 100 ms con la compuerta detenerse y escanear y NIS por actualización"
```

### Task 13: Alert limiter

Spec §5.5 (contracts §"Alert limiter"; the sector rule follows the spec, see the flagged conflict). The first alert fires immediately; after that every confirmed contact that has not alerted waits as **pending** — no merge, nothing is dropped for arriving next to another. When ≥ 1 s has passed since the last contact vibration (and no system pattern is playing), one vibration fires for the highest-priority pending contact that is still a confirmed candidate: centre first, then the nearest, then the lowest id; its side is the one passed in at that moment. A pending contact that is no longer a candidate when a slot opens becomes screen-only for good. **Reacquired contacts** (decision 7): a contact that already alerted and stops being a candidate is remembered for 5 s at the position it had the last time the player was still; a new candidate born < 1.5 m from it, in the same sector judged with the current yaw, inherits "ya alertó". **Cap:** 10 contact vibrations in any rolling 60 s; at the cap, every pending contact becomes screen-only (`ALERT_OVERFLOW`). **System alerts** never count and fire elsewhere (Task 16b); `withSystemAlert` only holds contact vibrations until the 1.2 s pattern ends, without moving the 1 s gap. Sides split at ±20°.

**Files:**
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/alerts/AlertLimiter.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/alerts/AlertLimiterTest.kt`

**Interfaces:**
- Consumes: `ContactAlert` (Task 2), `Side` (Task 2), `AlertParams` (Task 2); `Point2`, `trackingToBody` (Task 5).
- Produces (`io.github.santiquiroz.blindside.core.alerts`): `data class AlertCandidate(displayId: Int, side: Side, rangeM: Double, position: Point2)` (position in the tracking frame); `data class LostContact(displayId, position, lostNanos)`; `data class AlertFrame(candidates: List<AlertCandidate>, nowNanos: Long, yawDeg: Double = 0.0, playerMoving: Boolean = false)`; `data class LimiterOutcome(limiter, fired: ContactAlert?)`; `data class AlertLimiter(handled: Set<Int>, pending: Set<Int>, visible: Map<Int, AlertCandidate>, lost: List<LostContact>, lastFiredNanos, firedNanos, systemBusyUntilNanos) { fun step(frame: AlertFrame, params): LimiterOutcome; fun silence(displayIds): AlertLimiter; fun withSystemAlert(nowNanos, params): AlertLimiter; fun isSaturated(nowNanos, params): Boolean }`; `fun sideOf(bearingDeg, params): Side`.

- [ ] **Step 1: Write the failing tests**

The spec §9 limiter cases map to these tests: first alert not delayed; ~2 s and ~4 s losses with a new id < 1.5 m away → one alert; Y born > 1.5 m from X before 5 s → Y vibrates; system alert at 0.5 s → the pending contact waits for the pattern; L 0 + C 0.25 → C at 1 s; L 0, C 0.25, R 0.5 → C at 1 s and R at 2 s; the 11th alert in 60 s does not vibrate (eliminated mode is tested in Task 16c).

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/alerts/AlertLimiterTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.alerts

import io.github.santiquiroz.blindside.core.config.AlertParams
import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.scene.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlertLimiterTest {
    private val params = AlertParams()
    private val ms = 1_000_000L
    private val t0 = 50_000L * ms

    @Test
    fun `the first alert fires immediately`() {
        val outcome = AlertLimiter().step(AlertFrame(listOf(left(1)), t0), params)

        assertEquals(ContactAlert(1, Side.LEFT, t0), outcome.fired)
    }

    @Test
    fun `left at 0 and center at 0_25 s vibrate the center at 1 s`() {
        val fired = run(
            0L to listOf(left(1)),
            250L to listOf(left(1), center(2)),
            500L to listOf(left(1), center(2)),
            1_000L to listOf(left(1), center(2)),
        )

        assertEquals(listOf(0L to 1, 1_000L to 2), fired)
    }

    @Test
    fun `left at 0, center at 0_25 s and right at 0_5 s vibrate center at 1 s and right at 2 s`() {
        val all = listOf(left(1), center(2), right(3))
        val fired = run(
            0L to listOf(left(1)),
            250L to listOf(left(1), center(2)),
            500L to all,
            1_000L to all,
            1_500L to all,
            2_000L to all,
        )

        assertEquals(listOf(0L to 1, 1_000L to 2, 2_000L to 3), fired)
    }

    @Test
    fun `a pending contact gone before its slot only shows on screen, even if it comes back`() {
        val fired = run(
            0L to listOf(left(1)),
            500L to listOf(left(1), right(2)),
            1_000L to listOf(left(1)),
            1_500L to listOf(left(1), right(2)),
            2_500L to listOf(left(1), right(2)),
        )

        assertEquals(listOf(0L to 1), fired)
    }

    @Test
    fun `a person lost for 2 s who reappears with a new id vibrates once`() {
        val fired = run(
            0L to listOf(left(1)),
            100L to emptyList(),
            2_000L to listOf(left(9, at = Point2(-2.5, 3.2))),
        )

        assertEquals(listOf(0L to 1), fired)
    }

    @Test
    fun `a person lost for 4 s who reappears less than 1_5 m away vibrates once`() {
        val fired = run(
            0L to listOf(left(1)),
            100L to emptyList(),
            4_000L to listOf(left(9, at = Point2(-3.0, 3.5))),
        )

        assertEquals(listOf(0L to 1), fired)
    }

    @Test
    fun `someone else born on the same side more than 1_5 m away before 5 s vibrates`() {
        val fired = run(
            0L to listOf(left(1)),
            100L to emptyList(),
            3_000L to listOf(left(9, at = Point2(-2.5, 5.0))),
        )

        assertEquals(listOf(0L to 1, 3_000L to 9), fired)
    }

    @Test
    fun `a contact back after the 5 s sector pause vibrates again as someone new`() {
        val fired = run(
            0L to listOf(left(1)),
            100L to emptyList(),
            5_200L to listOf(left(9)),
        )

        assertEquals(listOf(0L to 1, 5_200L to 9), fired)
    }

    @Test
    fun `the lost contact's sector is judged with the current yaw, so a turn does not hide a reacquired contact`() {
        val before = AlertLimiter().step(AlertFrame(listOf(right(1)), t0), params).limiter
        val lost = before.step(AlertFrame(emptyList(), t0 + 100 * ms), params).limiter

        val afterTurn = lost.step(AlertFrame(listOf(AlertCandidate(5, Side.LEFT, 3.0, Point2(2.7, 2.2))), t0 + 3_000 * ms, yawDeg = 90.0), params)

        assertNull(afterTurn.fired)
    }

    @Test
    fun `while the player moves a contact keeps the position it had when the player was still`() {
        val still = AlertLimiter().step(AlertFrame(listOf(left(1)), t0), params).limiter
        val dragged = still.step(AlertFrame(listOf(left(1, at = Point2(-0.5, 3.0))), t0 + 100 * ms, playerMoving = true), params).limiter
        val lost = dragged.step(AlertFrame(emptyList(), t0 + 200 * ms), params).limiter

        val reborn = lost.step(AlertFrame(listOf(left(7, at = Point2(-2.8, 2.6))), t0 + 1_500 * ms), params)

        assertNull(reborn.fired)
    }

    @Test
    fun `a system alert at 0_5 s does not move the gap and the pending contact waits for the pattern to end`() {
        val first = AlertLimiter().step(AlertFrame(listOf(left(1)), t0), params).limiter
        val queued = first.step(AlertFrame(listOf(left(1), center(2)), t0 + 250 * ms), params).limiter
        val buzzing = queued.withSystemAlert(t0 + 500 * ms, params)

        val atOneSecond = buzzing.step(AlertFrame(listOf(left(1), center(2)), t0 + 1_000 * ms), params)
        val afterPattern = atOneSecond.limiter.step(AlertFrame(listOf(left(1), center(2)), t0 + 1_700 * ms), params)

        assertNull(atOneSecond.fired)
        assertEquals(ContactAlert(2, Side.CENTER, t0 + 1_700 * ms), afterPattern.fired)
        assertEquals(t0 + 1_700 * ms, afterPattern.limiter.lastFiredNanos)
    }

    @Test
    fun `the 11th alert in a rolling minute does not vibrate and the limiter is saturated`() {
        val fired = (0 until 11).fold(AlertLimiter() to 0) { (limiter, count), i ->
            val outcome = limiter.step(AlertFrame(listOf(left(100 + i, at = Point2(-3.0 - i * 2.0, 3.0))), t0 + i * 5_100L * ms), params)
            outcome.limiter to count + (if (outcome.fired != null) 1 else 0)
        }

        assertEquals(10, fired.second)
        assertTrue(fired.first.isSaturated(t0 + 51_000 * ms, params))
        assertFalse(fired.first.isSaturated(t0 + 120_000 * ms, params))
    }

    @Test
    fun `one alert per display id`() {
        val first = AlertLimiter().step(AlertFrame(listOf(left(1)), t0), params).limiter

        val again = first.step(AlertFrame(listOf(left(1)), t0 + 10_000 * ms), params)

        assertNull(again.fired)
    }

    @Test
    fun `center wins over the sides and the nearest wins within a side`() {
        val candidates = listOf(left(1, range = 1.5), center(2, range = 5.0), right(3, range = 1.0))

        assertEquals(2, AlertLimiter().step(AlertFrame(candidates, t0), params).fired?.displayId)
        assertEquals(3, AlertLimiter().step(AlertFrame(listOf(left(1, range = 2.5), right(3, range = 1.0)), t0), params).fired?.displayId)
    }

    @Test
    fun `sides split at plus and minus 20 degrees`() {
        assertEquals(Side.CENTER, sideOf(-20.0, params))
        assertEquals(Side.LEFT, sideOf(-21.0, params))
        assertEquals(Side.RIGHT, sideOf(35.0, params))
    }

    private fun run(vararg steps: Pair<Long, List<AlertCandidate>>): List<Pair<Long, Int>> =
        steps.fold(AlertLimiter() to emptyList<Pair<Long, Int>>()) { (limiter, fired), (atMs, candidates) ->
            val outcome = limiter.step(AlertFrame(candidates, t0 + atMs * ms), params)
            outcome.limiter to fired + listOfNotNull(outcome.fired?.let { atMs to it.displayId })
        }.second

    private fun left(id: Int, range: Double = 3.0, at: Point2 = Point2(-2.5, 2.5)) = AlertCandidate(id, Side.LEFT, range, at)

    private fun center(id: Int, range: Double = 3.0) = AlertCandidate(id, Side.CENTER, range, Point2(0.0, range))

    private fun right(id: Int, range: Double = 3.0) = AlertCandidate(id, Side.RIGHT, range, Point2(2.5, 2.5))
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.alerts.*")`

Expected: FAIL. `:radar-core:compileTestKotlin` reports `Unresolved reference` for `AlertLimiter`, `AlertCandidate`, `AlertFrame`, `sideOf`.

- [ ] **Step 3: Write the implementation**

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/alerts/AlertLimiter.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.alerts

import io.github.santiquiroz.blindside.core.config.AlertParams
import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.geometry.trackingToBody
import io.github.santiquiroz.blindside.core.scene.Side
import kotlin.math.abs

data class AlertCandidate(val displayId: Int, val side: Side, val rangeM: Double, val position: Point2)

data class LostContact(val displayId: Int, val position: Point2, val lostNanos: Long)

data class AlertFrame(val candidates: List<AlertCandidate>, val nowNanos: Long, val yawDeg: Double = 0.0, val playerMoving: Boolean = false)

data class LimiterOutcome(val limiter: AlertLimiter, val fired: ContactAlert?)

data class AlertLimiter(
    val handled: Set<Int> = emptySet(),
    val pending: Set<Int> = emptySet(),
    val visible: Map<Int, AlertCandidate> = emptyMap(),
    val lost: List<LostContact> = emptyList(),
    val lastFiredNanos: Long? = null,
    val firedNanos: List<Long> = emptyList(),
    val systemBusyUntilNanos: Long = Long.MIN_VALUE,
) {
    fun step(frame: AlertFrame, params: AlertParams): LimiterOutcome {
        val outcome = withLosses(frame.candidates, frame.nowNanos, params)
            .withReacquired(frame.candidates, frame.yawDeg, params)
            .withPending(frame.candidates)
            .fireIfDue(frame.candidates, frame.nowNanos, params)
        return outcome.copy(limiter = outcome.limiter.withVisible(frame.candidates, frame.playerMoving))
    }

    fun silence(displayIds: Collection<Int>): AlertLimiter = copy(handled = handled + displayIds, pending = pending - displayIds.toSet())

    // Spec §5.5: a system pattern fires at once and never moves the 1 s gap; contacts wait until it ends.
    fun withSystemAlert(nowNanos: Long, params: AlertParams): AlertLimiter =
        copy(systemBusyUntilNanos = maxOf(systemBusyUntilNanos, nowNanos + params.systemPatternMs * NANOS_PER_MS))

    fun isSaturated(nowNanos: Long, params: AlertParams): Boolean =
        firedNanos.count { nowNanos - it < MINUTE_NANOS } >= params.maxPerMinute

    private fun withLosses(candidates: List<AlertCandidate>, nowNanos: Long, params: AlertParams): AlertLimiter {
        val present = candidates.ids().toSet()
        val gone = visible.values.filter { it.displayId !in present }.map { LostContact(it.displayId, it.position, nowNanos) }
        val recent = (lost + gone).filter { it.displayId !in present && nowNanos - it.lostNanos < params.sectorPauseMs * NANOS_PER_MS }
        return copy(lost = recent)
    }

    // Spec §5.5 sector pause: a new track born < 1.5 m from a contact of the same sector that already alerted and was lost < 5 s ago is that contact.
    private fun withReacquired(candidates: List<AlertCandidate>, yawDeg: Double, params: AlertParams): AlertLimiter =
        candidates.filter { isNew(it) }.fold(this) { limiter, candidate -> limiter.inheritIfReacquired(candidate, yawDeg, params) }

    // Positions live in the yaw-compensated tracking frame, so the lost contact's sector is recomputed with today's yaw.
    private fun inheritIfReacquired(candidate: AlertCandidate, yawDeg: Double, params: AlertParams): AlertLimiter {
        val match = lost.firstOrNull { sideNow(it.position, yawDeg, params) == candidate.side && (it.position - candidate.position).norm < params.reacquireDistanceM }
            ?: return this
        return copy(handled = handled + candidate.displayId, lost = lost - match)
    }

    private fun withPending(candidates: List<AlertCandidate>): AlertLimiter =
        copy(pending = pending + candidates.filter { it.displayId !in handled }.ids())

    private fun fireIfDue(candidates: List<AlertCandidate>, nowNanos: Long, params: AlertParams): LimiterOutcome {
        if (pending.isEmpty() || !isSlotOpen(nowNanos, params)) return LimiterOutcome(this, null)
        val waiting = candidates.filter { it.displayId in pending }
        val cleared = silence(pending - waiting.ids().toSet())
        if (cleared.isSaturated(nowNanos, params)) return LimiterOutcome(cleared.silence(cleared.pending), null)
        val chosen = waiting.minWithOrNull(PRIORITY) ?: return LimiterOutcome(cleared, null)
        return LimiterOutcome(cleared.fire(chosen, nowNanos, params), ContactAlert(chosen.displayId, chosen.side, nowNanos))
    }

    private fun isSlotOpen(nowNanos: Long, params: AlertParams): Boolean {
        if (nowNanos < systemBusyUntilNanos) return false
        val last = lastFiredNanos ?: return true
        return nowNanos - last >= params.minGapMs * NANOS_PER_MS
    }

    private fun fire(chosen: AlertCandidate, nowNanos: Long, params: AlertParams) = copy(
        handled = handled + chosen.displayId,
        pending = pending - chosen.displayId,
        lastFiredNanos = nowNanos,
        firedNanos = (firedNanos + nowNanos).filter { nowNanos - it < MINUTE_NANOS }.takeLast(params.maxPerMinute),
    )

    // Positions measured while the player turns or walks carry the τ and ego-motion errors, so the last still one is kept.
    private fun withVisible(candidates: List<AlertCandidate>, playerMoving: Boolean): AlertLimiter =
        copy(visible = candidates.filter { it.displayId in handled }.associate { it.displayId to referenceOf(it, playerMoving) })

    private fun referenceOf(candidate: AlertCandidate, playerMoving: Boolean): AlertCandidate =
        if (playerMoving) visible[candidate.displayId] ?: candidate else candidate

    private fun isNew(candidate: AlertCandidate): Boolean = candidate.displayId !in handled && candidate.displayId !in pending

    private fun List<AlertCandidate>.ids() = map { it.displayId }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val MINUTE_NANOS = 60_000L * NANOS_PER_MS
        val PRIORITY = compareBy<AlertCandidate>({ it.side != Side.CENTER }, { it.rangeM }, { it.displayId })
    }
}

private fun sideNow(trackingPosition: Point2, yawDeg: Double, params: AlertParams): Side =
    sideOf(trackingToBody(trackingPosition, yawDeg).bearingDeg, params)

fun sideOf(bearingDeg: Double, params: AlertParams): Side = when {
    abs(bearingDeg) <= params.centerHalfWidthDeg -> Side.CENTER
    bearingDeg < 0 -> Side.LEFT
    else -> Side.RIGHT
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.alerts.*")`

Expected: PASS, 15 tests, 0 failures. Then run the whole module (`(cd watch && ./gradlew :radar-core:test)`) and confirm every earlier test still passes.

- [ ] **Step 5: Commit**

```bash
git add watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/alerts/AlertLimiter.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/alerts/AlertLimiterTest.kt
git commit -m "feat: limitador de alertas sin fusión, con cola por prioridad, re-adquisición y tope por minuto"
```

### Task 14: Scene builder

Spec §6.7, §3.1 and §5.3. Every displayed track (not tentative) is predicted to "now" and rotated by ψ(now) into the logical frame (normal posture only in the MVP: logical = body). Confidence: COASTING when lost, BOTH when both radars hit it in the current or previous window, else SINGLE. Coverage: one sector per alive radar (yaw ± 60°). Eliminated mode returns no blips.

**Files:**
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/scene/SceneBuilder.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/scene/SceneBuilderTest.kt`

**Interfaces:**
- Consumes: `Track`, `TrackStatus` (Task 11); geometry (Task 5); scene types and params (Task 2).
- Produces (`io.github.santiquiroz.blindside.core.scene`): `data class SceneInputs(nowMs, tracks, yawDeg, mounts, radars, imus, motion, warnings, linkUp, eliminated)`; `fun buildScene(inputs, tracking: TrackingParams, decode: DecodeParams): RadarScene`; `fun logicalPosition(track, nowMs, yawDeg, tracking): Point2`; `fun blipOf(track, nowMs, yawDeg, tracking): Blip`; `fun confidenceOf(track): Confidence`; `fun coverageOf(mounts, aliveRadars, decode): List<CoverageSector>`.

- [ ] **Step 1: Write the failing tests**

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/scene/SceneBuilderTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.scene

import io.github.santiquiroz.blindside.core.config.DecodeParams
import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.TrackingParams
import io.github.santiquiroz.blindside.core.config.defaultMounts
import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.tracking.CvKalman
import io.github.santiquiroz.blindside.core.tracking.Matrix
import io.github.santiquiroz.blindside.core.tracking.Track
import io.github.santiquiroz.blindside.core.tracking.TrackStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SceneBuilderTest {
    private val tracking = TrackingParams()
    private val decode = DecodeParams()
    private val mounts = defaultMounts(Handedness.RIGHT)
    private val bothAlive = listOf(SensorStatus(0, true), SensorStatus(1, true))

    @Test
    fun `a confirmed track is drawn at its predicted logical bearing`() {
        val track = track(Point2(0.0, 3.0), Point2(1.0, 0.0), TrackStatus.CONFIRMED, radars = setOf(0))

        val blip = buildScene(inputs(listOf(track), nowMs = 2_000), tracking, decode).blips.single()

        assertEquals(Math.toDegrees(Math.atan2(1.0, 3.0)), blip.bearingDeg, 0.5)
        assertEquals(Confidence.SINGLE, blip.confidence)
        assertEquals(1_000L, blip.ageMs)
    }

    @Test
    fun `yaw rotates the scene so a right turn moves contacts left`() {
        val track = track(Point2(0.0, 3.0), Point2.ZERO, TrackStatus.CONFIRMED)

        val blip = buildScene(inputs(listOf(track), nowMs = 1_000, yawDeg = 30.0), tracking, decode).blips.single()

        assertEquals(-30.0, blip.bearingDeg, 1e-9)
    }

    @Test
    fun `tentative tracks are hidden and coasting ones are marked`() {
        val tentative = track(Point2(0.0, 3.0), Point2.ZERO, TrackStatus.TENTATIVE)
        val coasting = track(Point2(1.0, 3.0), Point2.ZERO, TrackStatus.COASTING).copy(displayId = 2)
        val out = track(Point2(3.0, 0.5), Point2.ZERO, TrackStatus.OUT_OF_VIEW).copy(displayId = 3)

        val blips = buildScene(inputs(listOf(tentative, coasting, out), nowMs = 1_000), tracking, decode).blips

        assertEquals(listOf(2, 3), blips.map { it.displayId })
        assertEquals(Confidence.COASTING, blips[0].confidence)
        assertTrue(blips[1].outOfView)
    }

    @Test
    fun `both radars in the last two windows means BOTH confidence`() {
        val track = track(Point2(0.0, 3.0), Point2.ZERO, TrackStatus.CONFIRMED, radars = setOf(0)).copy(previousWindowRadars = setOf(1))

        assertEquals(Confidence.BOTH, confidenceOf(track))
    }

    @Test
    fun `coverage lists one sector per alive radar`() {
        val scene = buildScene(inputs(emptyList(), 0).copy(radars = listOf(SensorStatus(0, true), SensorStatus(1, false))), tracking, decode)

        assertEquals(listOf(CoverageSector(-100.0, 20.0)), scene.coverage)
    }

    @Test
    fun `eliminated mode shows no contacts`() {
        val track = track(Point2(0.0, 3.0), Point2.ZERO, TrackStatus.CONFIRMED)

        val scene = buildScene(inputs(listOf(track), 1_000).copy(eliminated = true), tracking, decode)

        assertTrue(scene.blips.isEmpty())
        assertTrue(scene.eliminated)
    }

    private fun inputs(tracks: List<Track>, nowMs: Long, yawDeg: Double = 0.0) = SceneInputs(
        nowMs = nowMs, tracks = tracks, yawDeg = yawDeg, mounts = mounts, radars = bothAlive, imus = bothAlive,
        motion = MotionState.STILL, warnings = emptySet(), linkUp = true, eliminated = false,
    )

    private fun track(position: Point2, velocity: Point2, status: TrackStatus, radars: Set<Int> = setOf(0, 1)): Track {
        val r = Matrix.of(2, 2, 0.04, 0.0, 0.0, 0.04)
        val kalman = CvKalman.init(position, r, 1.5).copy(x = Matrix.column(position.x, position.y, velocity.x, velocity.y))
        return Track(1, 1, kalman, stateMs = 1_000, bornMs = 500, lastHitMs = 1_000, status = status, windowRadars = radars)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.scene.*")`

Expected: FAIL. `:radar-core:compileTestKotlin` reports `Unresolved reference` for `SceneInputs`, `buildScene`, `confidenceOf`.

- [ ] **Step 3: Write the implementation**

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/scene/SceneBuilder.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.scene

import io.github.santiquiroz.blindside.core.config.DecodeParams
import io.github.santiquiroz.blindside.core.config.RadarMount
import io.github.santiquiroz.blindside.core.config.TrackingParams
import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.geometry.trackingToBody
import io.github.santiquiroz.blindside.core.geometry.wrapDeg
import io.github.santiquiroz.blindside.core.tracking.Track
import io.github.santiquiroz.blindside.core.tracking.TrackStatus

data class SceneInputs(
    val nowMs: Long,
    val tracks: List<Track>,
    val yawDeg: Double,
    val mounts: List<RadarMount>,
    val radars: List<SensorStatus>,
    val imus: List<SensorStatus>,
    val motion: MotionState,
    val warnings: Set<Warning>,
    val linkUp: Boolean,
    val eliminated: Boolean,
)

fun buildScene(inputs: SceneInputs, tracking: TrackingParams, decode: DecodeParams): RadarScene = RadarScene(
    blips = if (inputs.eliminated) emptyList() else inputs.tracks.filter { it.isDisplayed }.map { blipOf(it, inputs.nowMs, inputs.yawDeg, tracking) },
    coverage = coverageOf(inputs.mounts, inputs.radars.filter { it.alive }.map { it.id }.toSet(), decode),
    linkUp = inputs.linkUp,
    radars = inputs.radars,
    imus = inputs.imus,
    motion = inputs.motion,
    warnings = inputs.warnings,
    eliminated = inputs.eliminated,
)

fun logicalPosition(track: Track, nowMs: Long, yawDeg: Double, tracking: TrackingParams): Point2 =
    trackingToBody(track.predictedTo(nowMs, tracking).kalman.position, yawDeg)

fun blipOf(track: Track, nowMs: Long, yawDeg: Double, tracking: TrackingParams): Blip {
    val position = logicalPosition(track, nowMs, yawDeg, tracking)
    return Blip(
        displayId = track.displayId,
        bearingDeg = wrapDeg(position.bearingDeg),
        rangeM = position.norm,
        confidence = confidenceOf(track),
        ageMs = (nowMs - track.lastHitMs).coerceAtLeast(0),
        outOfView = track.status == TrackStatus.OUT_OF_VIEW,
    )
}

fun confidenceOf(track: Track): Confidence = when {
    track.isLost -> Confidence.COASTING
    track.recentRadars.size >= 2 -> Confidence.BOTH
    else -> Confidence.SINGLE
}

fun coverageOf(mounts: List<RadarMount>, aliveRadars: Set<Int>, decode: DecodeParams): List<CoverageSector> =
    mounts.filter { it.radarId in aliveRadars }
        .map { CoverageSector(it.yawDeg - decode.coneHalfAngleDeg, it.yawDeg + decode.coneHalfAngleDeg) }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.scene.*")`

Expected: PASS, 6 tests, 0 failures. Then run the whole module (`(cd watch && ./gradlew :radar-core:test)`) and confirm every earlier test still passes.

- [ ] **Step 5: Commit**

```bash
git add watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/scene/SceneBuilder.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/scene/SceneBuilderTest.kt
git commit -m "feat: constructor de escena en el marco lógico con confianza y cobertura"
```

### Task 15: Scenario DSL and packet simulator

Spec §6.11 and the §9 scenario list. A scenario is plain immutable data: player segments (`Stand`, `Turn`, `Walk`) and world-frame targets (piecewise-linear waypoints; helpers `walker`, `stillObject`, `marcher`). The radar model reports a target only inside the ±60° / 6 m cone and only if it moves relative to the radar (> 0.1 m/s over 50 ms), so a still person disappears and a wall "moves" while the player turns or walks; that is also how the spec §9 "static noise only while turning" appears. Up to 3 nearest; inverse geometry, `flipX`/`speedSign` and sign-magnitude via the real encoder. A frame stamped `t` describes the world `radarLatencyMs` earlier (default 100 ms, the MVP τ). IMUs: chip z up, gyro z = bias − the true average yaw rate over the 20 ms block, 0.25 g walking bob at 2 Hz, cumulative raw sums; each IMU runs on its own phase (`imuPhaseOffsetMs`, default [0, 7] ms, Review Focus 3) and stamps its block centres; a batch carries the blocks that ended inside its 100 ms cut. The watch gyroscope is simulated at 10 Hz (rad/s, no BLE delay); `watchGyroPeriodMs = null` models a watch without one. One bundle per 100 ms cut (radar A at +5 ms, B at +55 ms). `Scenarios` holds the spec §9 list, including the four MVP stop-and-scan cases (90° right turn in 0.5 s in front of a wall 3 m away, a walker confirmed before the turn, a walker who appears during it, a rival who appears while the player walks), and is also the app's demo source.

**Files:**
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/sim/Scenario.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/sim/PlayerPose.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/sim/SensorModels.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/sim/Simulator.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/sim/Scenarios.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/sim/SimulatorTest.kt`

**Interfaces:**
- Consumes: `BundleEncoder`, `Bundle`, `RadarFrame`, `ImuBatch`, `ImuSample`, `RawTarget`, constants (Tasks 3-4); `Point2`, `bodyToRadar` (Task 5); `Vec3` (Task 7); `RadarMount`, `defaultMounts`, `Handedness` (Task 2).
- Produces (`io.github.santiquiroz.blindside.core.sim`): `sealed interface PlayerSegment { durationMs }` with `Stand(durationMs)`, `Turn(durationMs, rateDps)`, `Walk(durationMs, speedMps)`; `data class Waypoint(tMs, x, y)`; `data class SimTarget(waypoints) { fun positionAt(tMs): Point2? }`; `data class Scenario(name, player, targets = emptyList(), mounts = defaultMounts(RIGHT), gyroBiasDps: List<Vec3>, bleDelayMs = 20, radarLatencyMs = 100, imuPhaseOffsetMs = listOf(0, 7), watchGyroPeriodMs: Long? = 100, droppedSeqs: Set<Int> = emptySet(), radarDownFromMs: Map<Int, Long> = emptyMap()) { val durationMs }`; `fun walker(fromMs, toMs, start, velocityMps)`, `fun stillObject(fromMs, toMs, at)`, `fun marcher(fromMs, toMs, center, amplitudeM = 0.15, halfPeriodMs = 500)`; `data class PlayerPose(position, headingDeg, yawRateDps, walking)`; `fun poseAt(segments, tMs)`; `fun worldToBody(world, pose)`; `fun worldToRadar(world, pose, mount)`; `fun radarTargets(scenario, mount, tMs): List<RawTarget>`; `fun imuSample(scenario, imuId, centreMs): ImuSample`; `fun blockYawRateDps(scenario, centreMs): Double`; `data class SimPacket(bytes: ByteArray, arrivalNanos: Long)` (contract); `data class SimWatchGyro(x, y, z: Float, eventNanos: Long)`; `fun simulate(scenario: Scenario): List<SimPacket>` (contract); `fun simulateWatchGyro(scenario): List<SimWatchGyro>`; `fun simEspMs(scenarioMs): Long`; `fun simArrivalNanos(scenarioMs, bleDelayMs = 20): Long`; constants `SIM_ESP_START_MS = 10_000`, `SIM_PHONE_START_NANOS = 1_000_000_000_000`; `object Scenarios { WARMUP_MS = 2_500; TURN_START_MS; TURN_END_MS; crossing(), turningWithStillTarget(), turningWithMarcher(), walkingTowardWall(), headOnRival(), twoPeopleSameRange(), personStops(), targetExitsCone(), wallDuringTurn(), walkerConfirmedBeforeTurn(), walkerAppearsDuringTurn(), rivalWhileWalking(); val all }`. Packet `seq` starts at 1 for the first 100 ms cut.

- [ ] **Step 1: Write the failing tests**

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/sim/SimulatorTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.sim

import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.imu.wrappedDelta
import io.github.santiquiroz.blindside.core.protocol.BundleDecoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SimulatorTest {
    @Test
    fun `the player pose integrates turns and walks`() {
        val segments = listOf(Stand(1_000), Turn(1_000, rateDps = 90.0), Walk(1_000, speedMps = 1.0))

        val end = poseAt(segments, 3_000)

        assertEquals(90.0, end.headingDeg, 1e-9)
        assertEquals(1.0, end.position.x, 1e-9)
        assertEquals(0.0, end.position.y, 1e-9)
        assertTrue(poseAt(segments, 2_500).walking)
        assertEquals(90.0, poseAt(segments, 1_500).yawRateDps, 1e-9)
    }

    @Test
    fun `after a right turn of 90 degrees a point ahead appears on the left`() {
        val pose = PlayerPose(Point2.ZERO, 90.0, 0.0, walking = false)

        val body = worldToBody(Point2(0.0, 3.0), pose)

        assertEquals(-3.0, body.x, 1e-9)
    }

    @Test
    fun `packets decode, are at most 244 bytes and arrive every 100 ms`() {
        val packets = simulate(Scenarios.crossing())

        assertEquals(85, packets.size)
        assertTrue(packets.all { it.bytes.size <= 244 })
        assertEquals(100_000_000L, packets[1].arrivalNanos - packets[0].arrivalNanos)
        val bundle = BundleDecoder.decode(packets[30].bytes)!!
        assertEquals(31, bundle.seq)
        assertEquals(simEspMs(3_100), bundle.tMs)
        assertEquals(0x0F, bundle.flags)
        assertEquals(listOf(0, 1), bundle.radarFrames.map { it.radarId })
        assertEquals(listOf(5, 5), bundle.imuBatches.map { it.samples.size })
    }

    @Test
    fun `a walking person is reported by the radar that covers it`() {
        val bundle = BundleDecoder.decode(simulate(Scenarios.crossing())[30].bytes)!!

        val radarA = bundle.radarFrames.first { it.radarId == 0 }
        assertEquals(1, radarA.targets.count { !it.isEmpty })
        assertTrue(radarA.targets.first().speedCms != 0)
    }

    @Test
    fun `a still object is not reported while the player stands`() {
        val bundle = BundleDecoder.decode(simulate(Scenarios.turningWithStillTarget())[20].bytes)!!

        assertTrue(bundle.radarFrames.all { frame -> frame.targets.all { it.isEmpty } })
    }

    @Test
    fun `gyro sums advance by four raw readings per sample`() {
        val packets = simulate(Scenarios.crossing()).map { BundleDecoder.decode(it.bytes)!! }
        val first = packets[0].imuBatches[0]
        val second = packets[1].imuBatches[0]

        val delta = wrappedDelta(second.gyroSums[2], first.gyroSums[2])

        assertEquals(second.samples.sumOf { it.gz * 4.0 }, delta, 0.0)
    }

    @Test
    fun `each imu is stamped at its block centres on its own phase`() {
        val first = BundleDecoder.decode(simulate(Scenarios.crossing())[0].bytes)!!

        assertEquals(listOf(simEspMs(10), simEspMs(17)), first.imuBatches.map { it.tFirstMs })
        assertEquals(listOf(5, 4), first.imuBatches.map { it.samples.size })
    }

    @Test
    fun `a radar frame describes the world 100 ms before its stamp`() {
        val scenario = Scenarios.crossing()
        val mount = scenario.mounts.first()

        assertEquals(radarTargets(scenario.copy(radarLatencyMs = 0), mount, 4_405), radarTargets(scenario, mount, 4_505))
    }

    @Test
    fun `the watch gyro reports the turn rate in rad per s at 10 Hz`() {
        val samples = simulateWatchGyro(Scenarios.turningWithMarcher())
        val turning = samples.filter { it.eventNanos == SIM_PHONE_START_NANOS + 5_250L * 1_000_000L }

        assertEquals(Scenarios.turningWithMarcher().durationMs / 100, samples.size.toLong())
        assertEquals(Math.toRadians(60.0), -turning.single().z.toDouble(), 1e-6)
        assertTrue(simulateWatchGyro(Scenarios.crossing().copy(watchGyroPeriodMs = null)).isEmpty())
    }

    @Test
    fun `dropped sequence numbers are not sent and a downed radar clears its flag`() {
        val scenario = Scenarios.crossing().copy(droppedSeqs = setOf(10), radarDownFromMs = mapOf(1 to 3_000L))

        val bundles = simulate(scenario).map { BundleDecoder.decode(it.bytes)!! }

        assertTrue(bundles.none { it.seq == 10 })
        val late = bundles.first { it.tMs == simEspMs(4_000) }
        assertEquals(listOf(0), late.radarFrames.map { it.radarId })
        assertEquals(0x0D, late.flags)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.sim.*")`

Expected: FAIL. `:radar-core:compileTestKotlin` reports `Unresolved reference` for `Scenario`, `Stand`, `Turn`, `Walk`, `poseAt`, `simulate`, `simulateWatchGyro`, `Scenarios`.

- [ ] **Step 3: Write the implementation**

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/sim/Scenario.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.sim

import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.RadarMount
import io.github.santiquiroz.blindside.core.config.defaultMounts
import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.imu.Vec3

sealed interface PlayerSegment {
    val durationMs: Long
}

data class Stand(override val durationMs: Long) : PlayerSegment

data class Turn(override val durationMs: Long, val rateDps: Double) : PlayerSegment

data class Walk(override val durationMs: Long, val speedMps: Double) : PlayerSegment

data class Waypoint(val tMs: Long, val x: Double, val y: Double)

data class SimTarget(val waypoints: List<Waypoint>) {
    init {
        require(waypoints.size >= 2) { "a target needs at least two waypoints" }
    }

    fun positionAt(tMs: Long): Point2? {
        if (tMs < waypoints.first().tMs || tMs > waypoints.last().tMs) return null
        val after = waypoints.first { it.tMs >= tMs }
        val before = waypoints.last { it.tMs <= tMs }
        if (after.tMs == before.tMs) return Point2(before.x, before.y)
        val f = (tMs - before.tMs).toDouble() / (after.tMs - before.tMs)
        return Point2(before.x + (after.x - before.x) * f, before.y + (after.y - before.y) * f)
    }
}

data class Scenario(
    val name: String,
    val player: List<PlayerSegment>,
    val targets: List<SimTarget> = emptyList(),
    val mounts: List<RadarMount> = defaultMounts(Handedness.RIGHT),
    val gyroBiasDps: List<Vec3> = listOf(Vec3(1.5, -0.8, 0.6), Vec3(-1.0, 0.5, -0.7)),
    val bleDelayMs: Long = 20,
    val radarLatencyMs: Long = 100,
    val imuPhaseOffsetMs: List<Long> = listOf(0, 7),
    val watchGyroPeriodMs: Long? = 100,
    val droppedSeqs: Set<Int> = emptySet(),
    val radarDownFromMs: Map<Int, Long> = emptyMap(),
) {
    val durationMs: Long get() = player.sumOf { it.durationMs }
}

fun walker(fromMs: Long, toMs: Long, start: Point2, velocityMps: Point2): SimTarget {
    val end = start + velocityMps * ((toMs - fromMs) / 1000.0)
    return SimTarget(listOf(Waypoint(fromMs, start.x, start.y), Waypoint(toMs, end.x, end.y)))
}

fun stillObject(fromMs: Long, toMs: Long, at: Point2): SimTarget =
    SimTarget(listOf(Waypoint(fromMs, at.x, at.y), Waypoint(toMs, at.x, at.y)))

fun marcher(fromMs: Long, toMs: Long, center: Point2, amplitudeM: Double = 0.15, halfPeriodMs: Long = 500): SimTarget {
    val steps = ((toMs - fromMs) / halfPeriodMs).toInt()
    val waypoints = (0..steps).map { i ->
        val offset = if (i % 2 == 0) -amplitudeM else amplitudeM
        Waypoint(fromMs + i * halfPeriodMs, center.x, center.y + offset)
    }
    return SimTarget(waypoints)
}
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/sim/PlayerPose.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.sim

import io.github.santiquiroz.blindside.core.config.RadarMount
import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.geometry.bodyToRadar

data class PlayerPose(val position: Point2, val headingDeg: Double, val yawRateDps: Double, val walking: Boolean)

private val START_POSE = PlayerPose(Point2.ZERO, 0.0, 0.0, walking = false)

fun poseAt(segments: List<PlayerSegment>, tMs: Long): PlayerPose = poseFrom(segments, tMs, START_POSE)

fun worldToBody(world: Point2, pose: PlayerPose): Point2 = (world - pose.position).rotateClockwise(-pose.headingDeg)

fun worldToRadar(world: Point2, pose: PlayerPose, mount: RadarMount): Point2 = bodyToRadar(worldToBody(world, pose), mount)

private tailrec fun poseFrom(remaining: List<PlayerSegment>, elapsedMs: Long, pose: PlayerPose): PlayerPose {
    val segment = remaining.firstOrNull() ?: return pose.copy(yawRateDps = 0.0, walking = false)
    if (elapsedMs < segment.durationMs) return advance(pose, segment, elapsedMs)
    return poseFrom(remaining.drop(1), elapsedMs - segment.durationMs, advance(pose, segment, segment.durationMs))
}

private fun advance(pose: PlayerPose, segment: PlayerSegment, elapsedMs: Long): PlayerPose {
    val dtS = elapsedMs / 1000.0
    return when (segment) {
        is Stand -> pose.copy(yawRateDps = 0.0, walking = false)
        is Turn -> pose.copy(headingDeg = pose.headingDeg + segment.rateDps * dtS, yawRateDps = segment.rateDps, walking = false)
        is Walk -> pose.copy(
            position = pose.position + Point2.fromPolar(segment.speedMps * dtS, pose.headingDeg),
            yawRateDps = 0.0,
            walking = true,
        )
    }
}
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/sim/SensorModels.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.sim

import io.github.santiquiroz.blindside.core.config.RadarMount
import io.github.santiquiroz.blindside.core.imu.Vec3
import io.github.santiquiroz.blindside.core.protocol.ACCEL_LSB_PER_G
import io.github.santiquiroz.blindside.core.protocol.GYRO_LSB_PER_DPS
import io.github.santiquiroz.blindside.core.protocol.IMU_SAMPLE_PERIOD_MS
import io.github.santiquiroz.blindside.core.protocol.ImuSample
import io.github.santiquiroz.blindside.core.protocol.Ld2450Codec
import io.github.santiquiroz.blindside.core.protocol.RawTarget
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

const val SIM_CONE_HALF_ANGLE_DEG = 60.0
const val SIM_MAX_RANGE_M = 6.0
const val SIM_MIN_APPARENT_SPEED_MPS = 0.1
const val SIM_RESOLUTION_MM = 360
private const val SPEED_PROBE_MS = 50L
private const val WALK_BOB_G = 0.25
private const val STEP_HZ = 2.0

// The LD2450 only reports what moves relative to it, so a still person vanishes and a wall "moves" while you turn or walk.
// A frame stamped tMs describes the world radarLatencyMs earlier (the module's internal latency, spec §6.3).
fun radarTargets(scenario: Scenario, mount: RadarMount, tMs: Long): List<RawTarget> {
    val seenMs = tMs - scenario.radarLatencyMs
    val pose = poseAt(scenario.player, seenMs)
    val earlier = poseAt(scenario.player, seenMs - SPEED_PROBE_MS)
    return scenario.targets.mapNotNull { target ->
        val now = target.positionAt(seenMs) ?: return@mapNotNull null
        val before = target.positionAt(seenMs - SPEED_PROBE_MS) ?: now
        val local = worldToRadar(now, pose, mount)
        val localBefore = worldToRadar(before, earlier, mount)
        val apparentSpeed = (local - localBefore).norm * 1000.0 / SPEED_PROBE_MS
        if (!isVisible(local.x, local.y) || apparentSpeed <= SIM_MIN_APPARENT_SPEED_MPS) return@mapNotNull null
        val radialSpeed = (local.norm - localBefore.norm) * 1000.0 / SPEED_PROBE_MS
        local.norm to rawTarget(local.x, local.y, radialSpeed, mount)
    }.sortedBy { it.first }.take(Ld2450Codec.TARGET_COUNT).map { it.second }
}

// One 50 Hz sample is the average over its 20 ms block, centred on centreMs (contracts, "IMU timing").
fun imuSample(scenario: Scenario, imuId: Int, centreMs: Long): ImuSample {
    val pose = poseAt(scenario.player, centreMs)
    val bias = scenario.gyroBiasDps[imuId]
    val bob = if (pose.walking) WALK_BOB_G * sin(2 * PI * STEP_HZ * centreMs / 1000.0) else 0.0
    // Chip z axis up: a clockwise (rightward) turn is a negative rate about z.
    val gyro = Vec3(bias.x, bias.y, bias.z - blockYawRateDps(scenario, centreMs))
    return ImuSample(
        ax = 0,
        ay = 0,
        az = ((1.0 + bob) * ACCEL_LSB_PER_G).roundToInt(),
        gx = (gyro.x * GYRO_LSB_PER_DPS).roundToInt(),
        gy = (gyro.y * GYRO_LSB_PER_DPS).roundToInt(),
        gz = (gyro.z * GYRO_LSB_PER_DPS).roundToInt(),
    )
}

fun blockYawRateDps(scenario: Scenario, centreMs: Long): Double {
    val half = IMU_SAMPLE_PERIOD_MS / 2
    val turned = poseAt(scenario.player, centreMs + half).headingDeg - poseAt(scenario.player, centreMs - half).headingDeg
    return turned * 1000.0 / IMU_SAMPLE_PERIOD_MS
}

private fun isVisible(x: Double, y: Double): Boolean {
    val range = kotlin.math.hypot(x, y)
    val bearing = Math.toDegrees(kotlin.math.atan2(x, y))
    return y > 0 && range <= SIM_MAX_RANGE_M && abs(bearing) <= SIM_CONE_HALF_ANGLE_DEG
}

private fun rawTarget(xM: Double, yM: Double, radialSpeedMps: Double, mount: RadarMount): RawTarget {
    val xSign = if (mount.flipX) -1 else 1
    return RawTarget(
        xMm = (xSign * xM * 1000).roundToInt(),
        yMm = (yM * 1000).roundToInt(),
        speedCms = (mount.speedSign * radialSpeedMps * 100).roundToInt(),
        resolutionMm = SIM_RESOLUTION_MM,
    )
}
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/sim/Simulator.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.sim

import io.github.santiquiroz.blindside.core.protocol.Bundle
import io.github.santiquiroz.blindside.core.protocol.BundleEncoder
import io.github.santiquiroz.blindside.core.protocol.IMU_SAMPLE_PERIOD_MS
import io.github.santiquiroz.blindside.core.protocol.ImuBatch
import io.github.santiquiroz.blindside.core.protocol.ImuSample
import io.github.santiquiroz.blindside.core.protocol.PROTOCOL_VERSION
import io.github.santiquiroz.blindside.core.protocol.RAW_READINGS_PER_SAMPLE
import io.github.santiquiroz.blindside.core.protocol.RadarFrame

data class SimPacket(val bytes: ByteArray, val arrivalNanos: Long)

data class SimWatchGyro(val x: Float, val y: Float, val z: Float, val eventNanos: Long)

const val SIM_ESP_START_MS = 10_000L
const val SIM_PHONE_START_NANOS = 1_000_000_000_000L
const val SIM_PACKET_PERIOD_MS = 100L
private const val NANOS_PER_MS = 1_000_000L
private val RADAR_PHASE_MS = mapOf(0 to 5L, 1 to 55L)
private val IMU_IDS = listOf(0, 1)

fun simEspMs(scenarioMs: Long): Long = SIM_ESP_START_MS + scenarioMs

fun simArrivalNanos(scenarioMs: Long, bleDelayMs: Long = 20): Long = SIM_PHONE_START_NANOS + (scenarioMs + bleDelayMs) * NANOS_PER_MS

fun simulate(scenario: Scenario): List<SimPacket> {
    val samples = IMU_IDS.associateWith { imuId -> imuTimeline(scenario, imuId) }
    val cuts = (1..scenario.durationMs / SIM_PACKET_PERIOD_MS).map { it * SIM_PACKET_PERIOD_MS }
    return cuts.mapIndexedNotNull { index, cutMs ->
        val seq = (index + 1) and 0xFFFF
        if (seq in scenario.droppedSeqs) return@mapIndexedNotNull null
        val bundle = bundleAt(scenario, cutMs, seq, samples)
        SimPacket(BundleEncoder.encode(bundle), simArrivalNanos(cutMs, scenario.bleDelayMs))
    }
}

// TYPE_GYROSCOPE on the watch: rad/s, delivered without the BLE delay.
fun simulateWatchGyro(scenario: Scenario): List<SimWatchGyro> {
    val period = scenario.watchGyroPeriodMs ?: return emptyList()
    return (period / 2 until scenario.durationMs step period).map { tMs ->
        val rate = Math.toRadians(poseAt(scenario.player, tMs).yawRateDps).toFloat()
        SimWatchGyro(0f, 0f, -rate, SIM_PHONE_START_NANOS + tMs * NANOS_PER_MS)
    }
}

private data class TimedSample(val centreMs: Long, val sample: ImuSample, val sums: List<Long>) {
    val blockEndMs: Long get() = centreMs + IMU_SAMPLE_PERIOD_MS / 2
}

// Each IMU task runs on its own phase: block k covers [phase + 20k, phase + 20k + 20) and is stamped at its centre.
private fun imuTimeline(scenario: Scenario, imuId: Int): List<TimedSample> {
    val phase = scenario.imuPhaseOffsetMs[imuId]
    val centres = (phase + IMU_SAMPLE_PERIOD_MS / 2..scenario.durationMs - IMU_SAMPLE_PERIOD_MS / 2 step IMU_SAMPLE_PERIOD_MS).toList()
    val raw = centres.map { imuSample(scenario, imuId, it) }
    val sums = raw.runningFold(listOf(0L, 0L, 0L)) { acc, s ->
        listOf(acc[0] + s.gx * RAW_READINGS_PER_SAMPLE, acc[1] + s.gy * RAW_READINGS_PER_SAMPLE, acc[2] + s.gz * RAW_READINGS_PER_SAMPLE)
    }.drop(1)
    return centres.indices.map { TimedSample(centres[it], raw[it], sums[it].map { v -> v and 0xFFFFFFFFL }) }
}

private fun bundleAt(scenario: Scenario, cutMs: Long, seq: Int, samples: Map<Int, List<TimedSample>>): Bundle {
    val fromMs = cutMs - SIM_PACKET_PERIOD_MS
    val radarsUp = scenario.mounts.map { it.radarId }.filter { isRadarUp(scenario, it, cutMs) }
    val frames = scenario.mounts.filter { it.radarId in radarsUp }.map { mount ->
        val tMs = fromMs + (RADAR_PHASE_MS[mount.radarId] ?: 0L)
        RadarFrame(mount.radarId, simEspMs(tMs), radarTargets(scenario, mount, tMs))
    }
    val batches = IMU_IDS.mapNotNull { imuId -> batchBetween(imuId, samples.getValue(imuId), fromMs, cutMs) }
    val flags = radarsUp.fold(0b1100) { acc, id -> acc or (1 shl id) }
    return Bundle(PROTOCOL_VERSION, flags, seq, simEspMs(cutMs), frames.sortedBy { it.tMs }, batches, emptyList())
}

private fun batchBetween(imuId: Int, timeline: List<TimedSample>, fromMs: Long, toMs: Long): ImuBatch? {
    val inside = timeline.filter { it.blockEndMs > fromMs && it.blockEndMs <= toMs }
    if (inside.isEmpty()) return null
    return ImuBatch(imuId, simEspMs(inside.first().centreMs), inside.map { it.sample }, inside.last().sums)
}

private fun isRadarUp(scenario: Scenario, radarId: Int, cutMs: Long): Boolean {
    val downFrom = scenario.radarDownFromMs[radarId] ?: return true
    return cutMs < downFrom
}
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/sim/Scenarios.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.sim

import io.github.santiquiroz.blindside.core.geometry.Point2

// Spec §9 scenario list. Every scenario starts standing still so the gyro boot bias (2 s) is accepted.
object Scenarios {
    const val WARMUP_MS = 2_500L

    fun crossing() = Scenario(
        name = "crossing",
        player = listOf(Stand(WARMUP_MS + 6_000)),
        targets = listOf(walker(WARMUP_MS, WARMUP_MS + 5_000, start = Point2(-3.0, 3.0), velocityMps = Point2(1.2, 0.0))),
    )

    fun turningWithStillTarget() = Scenario(
        name = "turning-with-still-target",
        player = listOf(Stand(WARMUP_MS + 1_000), Turn(1_500, rateDps = 60.0), Stand(1_500)),
        targets = listOf(stillObject(0, WARMUP_MS + 4_000, at = Point2(0.5, 3.0))),
    )

    fun turningWithMarcher() = Scenario(
        name = "turning-with-marcher",
        player = listOf(Stand(WARMUP_MS + 2_500), Turn(750, rateDps = 60.0), Stand(2_000)),
        targets = listOf(marcher(WARMUP_MS, WARMUP_MS + 5_250, center = Point2(0.0, 3.0))),
    )

    fun walkingTowardWall() = Scenario(
        name = "walking-toward-wall",
        player = listOf(Stand(WARMUP_MS), Walk(3_000, speedMps = 1.2), Stand(3_000)),
        targets = listOf(stillObject(0, WARMUP_MS + 6_000, at = Point2(0.3, 5.5))),
    )

    fun headOnRival() = Scenario(
        name = "head-on-rival",
        player = listOf(Stand(WARMUP_MS), Walk(1_500, speedMps = 1.2), Stand(4_000)),
        targets = listOf(walker(WARMUP_MS, WARMUP_MS + 5_500, start = Point2(0.2, 7.5), velocityMps = Point2(0.0, -0.5))),
    )

    fun twoPeopleSameRange() = Scenario(
        name = "two-people-same-range",
        player = listOf(Stand(WARMUP_MS + 4_000)),
        targets = listOf(
            walker(WARMUP_MS, WARMUP_MS + 4_000, start = Point2(-2.0, 3.5), velocityMps = Point2(0.0, -0.6)),
            walker(WARMUP_MS, WARMUP_MS + 4_000, start = Point2(2.0, 3.5), velocityMps = Point2(0.0, -0.6)),
        ),
    )

    fun personStops() = Scenario(
        name = "person-stops",
        player = listOf(Stand(WARMUP_MS + 8_000)),
        targets = listOf(
            SimTarget(
                listOf(
                    Waypoint(WARMUP_MS, -2.5, 3.0),
                    Waypoint(WARMUP_MS + 1_700, -0.5, 3.0),
                    Waypoint(WARMUP_MS + 2_200, -0.2, 3.0),
                    Waypoint(WARMUP_MS + 3_200, 0.0, 3.0),
                    Waypoint(WARMUP_MS + 5_200, 0.0, 3.0),
                    Waypoint(WARMUP_MS + 7_000, 1.8, 3.0),
                ),
            ),
        ),
    )

    fun targetExitsCone() = Scenario(
        name = "target-exits-cone",
        player = listOf(Stand(WARMUP_MS + 10_500)),
        targets = listOf(walker(WARMUP_MS, WARMUP_MS + 5_500, start = Point2(0.5, 1.0), velocityMps = Point2(1.2, 0.0))),
    )

    // Spec §9 MVP set: a 90° right turn in 0.5 s in front of a wall 3 m away that only shows up while turning.
    const val TURN_START_MS = WARMUP_MS + 2_000
    const val TURN_END_MS = TURN_START_MS + 500

    fun wallDuringTurn() = Scenario(
        name = "wall-during-turn",
        player = turnInFrontOfWall(),
        targets = wall(),
    )

    fun walkerConfirmedBeforeTurn() = Scenario(
        name = "walker-confirmed-before-turn",
        player = turnInFrontOfWall(),
        targets = wall() + walker(WARMUP_MS, TURN_END_MS + 3_000, start = Point2(1.0, 2.5), velocityMps = Point2(0.3, 0.0)),
    )

    fun walkerAppearsDuringTurn() = Scenario(
        name = "walker-appears-during-turn",
        player = turnInFrontOfWall(),
        targets = wall() + walker(TURN_START_MS + 250, TURN_END_MS + 3_000, start = Point2(2.5, 1.0), velocityMps = Point2(0.0, 0.6)),
    )

    fun rivalWhileWalking() = Scenario(
        name = "rival-while-walking",
        player = listOf(Stand(WARMUP_MS), Walk(2_000, speedMps = 1.0), Stand(4_000)),
        targets = listOf(
            stillObject(0, WARMUP_MS + 6_000, at = Point2(0.5, 5.0)),
            walker(WARMUP_MS + 500, WARMUP_MS + 6_000, start = Point2(-2.5, 4.5), velocityMps = Point2(0.6, 0.0)),
        ),
    )

    val all: List<Scenario>
        get() = listOf(
            crossing(), turningWithStillTarget(), turningWithMarcher(), walkingTowardWall(),
            headOnRival(), twoPeopleSameRange(), personStops(), targetExitsCone(),
            wallDuringTurn(), walkerConfirmedBeforeTurn(), walkerAppearsDuringTurn(), rivalWhileWalking(),
        )

    private fun turnInFrontOfWall() = listOf(Stand(TURN_START_MS), Turn(TURN_END_MS - TURN_START_MS, rateDps = 180.0), Stand(3_000))

    private fun wall() = listOf(-1.0, 0.0, 1.0).map { x -> stillObject(0, TURN_END_MS + 3_000, at = Point2(x, 3.0)) }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.sim.*")`

Expected: PASS, 10 tests, 0 failures. Then run the whole module (`(cd watch && ./gradlew :radar-core:test)`) and confirm every earlier test still passes.

- [ ] **Step 5: Commit**

```bash
git add watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/sim/Scenario.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/sim/PlayerPose.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/sim/SensorModels.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/sim/Simulator.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/sim/Scenarios.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/sim/SimulatorTest.kt
git commit -m "feat: simulador de escenarios con latencia del radar, IMUs desfasados y giroscopio del reloj"
```

### Task 16a: RadarPipeline core: packet ingest, watch inputs and the scenario harness

The contract `RadarPipeline` with every method, minus warnings, system alerts and contact alerts (Tasks 16b and 16c). Per packet: count it; reject malformed bytes; restart time references if `t_ms` went backwards (decision 10); observe the clock and `seq`; keep the last LINK section; ingest the batches of IMUs flagged OK with the watch witness and the last step as rest evidence (Task 7), merge their yaw increments, update motion and choose the turning source (watch gyroscope when no box IMU is usable); mark `lastMovingMs` when the player moves (decision 1); count STATUS bad-frame growth as corruption; filter and track every radar frame in `t_ms` order, summing the NIS of turning updates; advance the tracker to `header.t_ms − 100`. Watch inputs: `onWatchGyro` (rad/s → °/s, ESP32 time) feeds the witness and the watch turning filter; `onBeltInfo` restarts the time references on a new `boot_id` and applies the IMU scales (decision 11); `onWatchStep` marks a step; `onLinkState(true)` resets the clock mapping. `scene(now)` maps `now` to ESP32 time and uses the display yaw. `ScenarioRun` feeds simulated packets and watch-gyro events in time order and is reused by Tasks 16b-18.

**Files:**
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/PipelineState.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/SensorHealth.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/PacketIngest.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/WatchInputs.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/RadarPipeline.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/ScenarioRun.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/RadarPipelineTest.kt`

**Interfaces:**
- Consumes: every earlier task (`BundleDecoder`, `LinkParams`, `parseBeltInfo`, `ClockMapper`, `filterFrame`, `ImuChannel`, `RestEvidence`, `WatchWitness`, `ingestBatch`, `mergeIncrements`, `YawTracker`, `MotionDetector`, `Tracker`, `MotionContext`, `AlertLimiter`, `buildScene`, the simulator).
- Produces (`io.github.santiquiroz.blindside.core`): `class RadarPipeline(config: PipelineConfig) { fun onBlePacket(bytes: ByteArray, arrivalNanos: Long): List<PipelineEvent>; fun onWatchGravity(x: Float, y: Float, z: Float, eventNanos: Long); fun onWatchStep(eventNanos: Long); fun onWatchGyro(x: Float, y: Float, z: Float, eventNanos: Long); fun onBeltInfo(json: String, nowNanos: Long); fun onLinkState(connected: Boolean, nowNanos: Long): List<PipelineEvent>; fun setEliminated(on: Boolean); fun scene(nowNanos: Long): RadarScene; fun counters(): PipelineCounters }` (contract plus `counters`); `data class PipelineCounters(packets, malformedPackets, truncatedPackets, lostPackets, implausibleTargets, staleTargets, nearFieldTargets, outOfOrderFrames: Long, espResets: Int, lastLink: LinkParams?, turningNisSum: Double, turningNisCount: Long) { val meanTurningNis: Double? }`. Internal (used by 16b-16c): `PipelineState` (with `withRestartedTimeReferences()`, `markCorrupt`, `corruptCount`, `NANOS_PER_MS`), `Stage`, `ingestPacket`, `trackerContext`, `motionContext`, `withWatchStep`, `withWatchGyro`, `withBeltInfo`, `withLinkState`, `IMU_IDS`, `flagBit`, `isLinkUp`, `aliveRadars`, `isImuUsable`, `usableImus`, `radarStatuses`, `imuStatuses`.
- Test helpers (test source set, reused by Tasks 16b-18): `sealed interface SimInput { nanos }`, `PacketInput(bytes, nanos)`, `GyroInput(sample)`; `data class ScenarioRun(events, scenes, pipeline) { alerts, confirmations, fun sceneAt(scenarioMs): RadarScene }`; `fun scenarioMsOf(nanos): Long`; `fun scenarioInputs(scenario, rewrite: (Bundle) -> Bundle = { it }): List<SimInput>`; `fun runScenario(scenario, config = PipelineConfig(mounts = scenario.mounts), rewrite = { it }): ScenarioRun`.

- [ ] **Step 1: Write the failing tests**

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/ScenarioRun.kt`:

```kotlin
package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.alerts.PipelineEvent
import io.github.santiquiroz.blindside.core.alerts.TrackConfirmed
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.protocol.Bundle
import io.github.santiquiroz.blindside.core.protocol.BundleDecoder
import io.github.santiquiroz.blindside.core.protocol.BundleEncoder
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.sim.SIM_PHONE_START_NANOS
import io.github.santiquiroz.blindside.core.sim.Scenario
import io.github.santiquiroz.blindside.core.sim.SimWatchGyro
import io.github.santiquiroz.blindside.core.sim.simulate
import io.github.santiquiroz.blindside.core.sim.simulateWatchGyro

sealed interface SimInput {
    val nanos: Long
}

data class PacketInput(val bytes: ByteArray, override val nanos: Long) : SimInput

data class GyroInput(val sample: SimWatchGyro) : SimInput {
    override val nanos: Long get() = sample.eventNanos
}

data class ScenarioRun(val events: List<PipelineEvent>, val scenes: List<Pair<Long, RadarScene>>, val pipeline: RadarPipeline) {
    val alerts: List<ContactAlert> get() = events.filterIsInstance<ContactAlert>()
    val confirmations: List<TrackConfirmed> get() = events.filterIsInstance<TrackConfirmed>()

    fun sceneAt(scenarioMs: Long): RadarScene = scenes.last { it.first <= scenarioMs }.second
}

fun scenarioMsOf(nanos: Long): Long = (nanos - SIM_PHONE_START_NANOS) / 1_000_000L

fun scenarioInputs(scenario: Scenario, rewrite: (Bundle) -> Bundle = { it }): List<SimInput> {
    val packets = simulate(scenario).map { PacketInput(BundleEncoder.encode(rewrite(BundleDecoder.decode(it.bytes)!!)), it.arrivalNanos) }
    return (packets + simulateWatchGyro(scenario).map { GyroInput(it) }).sortedBy { it.nanos }
}

fun runScenario(
    scenario: Scenario,
    config: PipelineConfig = PipelineConfig(mounts = scenario.mounts),
    rewrite: (Bundle) -> Bundle = { it },
): ScenarioRun {
    val pipeline = RadarPipeline(config)
    val inputs = scenarioInputs(scenario, rewrite)
    pipeline.onLinkState(true, inputs.first().nanos)
    return inputs.fold(ScenarioRun(emptyList(), emptyList(), pipeline)) { run, input -> run.after(input) }
}

private fun ScenarioRun.after(input: SimInput): ScenarioRun = when (input) {
    is GyroInput -> also { pipeline.onWatchGyro(input.sample.x, input.sample.y, input.sample.z, input.nanos) }
    is PacketInput -> {
        val produced = pipeline.onBlePacket(input.bytes, input.nanos)
        copy(events = events + produced, scenes = scenes + (scenarioMsOf(input.nanos) to pipeline.scene(input.nanos)))
    }
}
```

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/RadarPipelineTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.protocol.Bundle
import io.github.santiquiroz.blindside.core.protocol.BundleDecoder
import io.github.santiquiroz.blindside.core.protocol.BundleEncoder
import io.github.santiquiroz.blindside.core.protocol.LinkParams
import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.sim.Scenario
import io.github.santiquiroz.blindside.core.sim.Scenarios
import io.github.santiquiroz.blindside.core.sim.Stand
import io.github.santiquiroz.blindside.core.sim.Turn
import io.github.santiquiroz.blindside.core.sim.simArrivalNanos
import io.github.santiquiroz.blindside.core.sim.simEspMs
import io.github.santiquiroz.blindside.core.sim.simulate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RadarPipelineTest {
    private val config = PipelineConfig()

    @Test
    fun `a crossing person is confirmed once`() {
        val run = runScenario(Scenarios.crossing())

        assertEquals(1, run.confirmations.size)
    }

    @Test
    fun `a 45 degree turn with the two imus 7 ms apart reads 45 degrees`() {
        val state = ingestAll(Scenarios.turningWithMarcher())

        assertEquals(45.0, state.yaw.yawAt(simEspMs(7_000), config.tuning.imu), 1.0)
    }

    @Test
    fun `lost packets are counted and the turn is rebuilt across the gap`() {
        val run = runScenario(Scenarios.turningWithMarcher().copy(droppedSeqs = (51..58).toSet()))

        assertEquals(8L, run.pipeline.counters().lostPackets)
        assertEquals(-45.0, run.sceneAt(7_600).blips.single().bearingDeg, 8.0)
    }

    @Test
    fun `an info scale of 32_8 LSB per deg per s doubles the turn read from the same counts`() {
        val info = """{"boot_id":"a1","imus":[{"id":0,"gyro_lsb_dps":32.8},{"id":1,"gyro_lsb_dps":32.8}]}"""

        val state = ingestAll(Scenarios.turningWithMarcher(), info)

        assertEquals(90.0, state.yaw.yawAt(simEspMs(7_000), config.tuning.imu), 2.0)
    }

    @Test
    fun `a new boot id restarts the time references, the same one does not`() {
        val running = ingestAll(Scenarios.crossing(), """{"boot_id":"a1"}""")

        val reread = withBeltInfo(running, """{"boot_id":"a1"}""")
        val rebooted = withBeltInfo(running, """{"boot_id":"b2"}""")

        assertEquals(0, reread.counters.espResets)
        assertTrue(reread.clock.isReady)
        assertEquals(1, rebooted.counters.espResets)
        assertFalse(rebooted.clock.isReady)
        assertEquals("b2", rebooted.bootId)
    }

    @Test
    fun `t_ms going backwards restarts the time references`() {
        val run = runScenario(Scenarios.crossing())
        val restarted = simulate(Scenarios.crossing()).take(10).map { packet ->
            BundleEncoder.encode(rebased(BundleDecoder.decode(packet.bytes)!!, -9_000)) to packet.arrivalNanos + 9_000_000_000L
        }

        restarted.forEach { (bytes, nanos) -> run.pipeline.onBlePacket(bytes, nanos) }

        assertEquals(1, run.pipeline.counters().espResets)
    }

    @Test
    fun `with both box imus down the watch gyroscope says the player is turning`() {
        val turn = Scenario("watch-turn", player = listOf(Stand(Scenarios.WARMUP_MS), Turn(1_500, rateDps = 30.0), Stand(1_000)))

        val run = runScenario(turn) { it.copy(flags = it.flags and 0x03) }

        assertEquals(MotionState.TURNING, run.sceneAt(Scenarios.WARMUP_MS + 1_000).motion)
        assertEquals(MotionState.STILL, run.sceneAt(Scenarios.WARMUP_MS - 500).motion)
    }

    @Test
    fun `a watch step marks the player as walking for 1_2 s`() {
        val run = runScenario(Scenarios.crossing())

        run.pipeline.onWatchStep(simArrivalNanos(8_480, 0))

        assertEquals(MotionState.WALKING, run.pipeline.scene(simArrivalNanos(8_500)).motion)
    }

    @Test
    fun `no packets for more than 1 s means the link is not up`() {
        val run = runScenario(Scenarios.crossing())

        assertTrue(run.pipeline.scene(simArrivalNanos(8_500)).linkUp)
        assertFalse(run.pipeline.scene(simArrivalNanos(9_700)).linkUp)
    }

    @Test
    fun `eliminated mode hides every contact`() {
        val run = runScenario(Scenarios.crossing())

        run.pipeline.setEliminated(true)

        assertTrue(run.pipeline.scene(simArrivalNanos(5_000)).eliminated)
        assertTrue(run.pipeline.scene(simArrivalNanos(5_000)).blips.isEmpty())
    }

    @Test
    fun `a LINK section updates the link counters`() {
        val run = runScenario(Scenarios.crossing()) { if (it.seq == 5) it.copy(links = listOf(LinkParams(36, 0, 500))) else it }

        assertEquals(LinkParams(36, 0, 500), run.pipeline.counters().lastLink)
    }

    @Test
    fun `malformed and truncated packets are counted`() {
        val pipeline = RadarPipeline(config)
        pipeline.onLinkState(true, simArrivalNanos(0))
        val good = simulate(Scenarios.crossing())[0]
        pipeline.onBlePacket(good.bytes, good.arrivalNanos)

        repeat(10) { pipeline.onBlePacket(byteArrayOf(9, 9, 9), good.arrivalNanos + it) }
        repeat(10) { pipeline.onBlePacket(good.bytes.copyOfRange(0, good.bytes.size - 3), good.arrivalNanos + 100 + it) }

        assertEquals(10, pipeline.counters().malformedPackets)
        assertEquals(10, pipeline.counters().truncatedPackets)
    }

    @Test
    fun `updates made while turning feed the NIS used to tune tau`() {
        val counters = runScenario(Scenarios.turningWithMarcher()).pipeline.counters()

        assertTrue(counters.turningNisCount > 0)
        assertTrue(counters.meanTurningNis!! > 0.0)
    }

    private fun ingestAll(scenario: Scenario, infoJson: String? = null): PipelineState {
        val start = withLinkState(PipelineState.initial(config), connected = true)
        val withInfo = infoJson?.let { withBeltInfo(start, it) } ?: start
        return scenarioInputs(scenario).fold(withInfo) { state, input ->
            when (input) {
                is PacketInput -> ingestPacket(state, input.bytes, input.nanos, config).state
                is GyroInput -> withWatchGyro(state, input.sample.x, input.sample.y, input.sample.z, input.nanos, config)
            }
        }
    }

    private fun rebased(bundle: Bundle, deltaMs: Long): Bundle = bundle.copy(
        tMs = bundle.tMs + deltaMs,
        radarFrames = bundle.radarFrames.map { it.copy(tMs = it.tMs + deltaMs) },
        imuBatches = bundle.imuBatches.map { it.copy(tFirstMs = it.tFirstMs + deltaMs) },
    )
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.RadarPipelineTest")`

Expected: FAIL. `:radar-core:compileTestKotlin` reports `Unresolved reference` for `RadarPipeline`, `PipelineState`, `ingestPacket`, `withBeltInfo`.

- [ ] **Step 3: Write the implementation**

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/PipelineState.kt`:

```kotlin
package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.alerts.AlertLimiter
import io.github.santiquiroz.blindside.core.alerts.PipelineEvent
import io.github.santiquiroz.blindside.core.clock.ClockMapper
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.geometry.StaleMemory
import io.github.santiquiroz.blindside.core.imu.ImuChannel
import io.github.santiquiroz.blindside.core.imu.MotionDetector
import io.github.santiquiroz.blindside.core.imu.Vec3
import io.github.santiquiroz.blindside.core.imu.WatchWitness
import io.github.santiquiroz.blindside.core.imu.YawTracker
import io.github.santiquiroz.blindside.core.protocol.LinkParams
import io.github.santiquiroz.blindside.core.protocol.SeqTracker
import io.github.santiquiroz.blindside.core.tracking.TrackerState

data class PipelineCounters(
    val packets: Long = 0,
    val malformedPackets: Long = 0,
    val truncatedPackets: Long = 0,
    val lostPackets: Long = 0,
    val implausibleTargets: Long = 0,
    val staleTargets: Long = 0,
    val nearFieldTargets: Long = 0,
    val outOfOrderFrames: Long = 0,
    val espResets: Int = 0,
    val lastLink: LinkParams? = null,
    val turningNisSum: Double = 0.0,
    val turningNisCount: Long = 0,
) {
    val meanTurningNis: Double? get() = if (turningNisCount == 0L) null else turningNisSum / turningNisCount
}

internal data class TimedCount(val nanos: Long, val count: Int)

internal data class PipelineState(
    val clock: ClockMapper,
    val seq: SeqTracker = SeqTracker(),
    val lastHeaderMs: Long? = null,
    val flags: Int? = null,
    val bootId: String? = null,
    val imus: Map<Int, ImuChannel> = mapOf(0 to ImuChannel(), 1 to ImuChannel()),
    val yaw: YawTracker = YawTracker(),
    val motion: MotionDetector = MotionDetector(),
    val lastMovingMs: Long? = null,
    val watchGyro: WatchWitness = WatchWitness(),
    val stale: Map<Int, StaleMemory> = emptyMap(),
    val tracker: TrackerState = TrackerState(),
    val limiter: AlertLimiter = AlertLimiter(),
    val connected: Boolean = false,
    val lastPacketNanos: Long? = null,
    val corruption: List<TimedCount> = emptyList(),
    val lastBadFrames: Map<Int, Int> = emptyMap(),
    val eliminated: Boolean = false,
    val watchGravity: Vec3? = null,
    val counters: PipelineCounters = PipelineCounters(),
) {
    fun markCorrupt(nanos: Long, count: Int, windowMs: Long): PipelineState {
        if (count <= 0) return this
        val kept = corruption.filter { nanos - it.nanos < windowMs * NANOS_PER_MS }
        return copy(corruption = kept + TimedCount(nanos, count))
    }

    fun corruptCount(nowNanos: Long, windowMs: Long): Int =
        corruption.filter { nowNanos - it.nanos < windowMs * NANOS_PER_MS }.sumOf { it.count }

    // Spec §6.3 and §4.2: after an ESP32 reboot every time reference restarts; display ids and handled alerts carry on.
    fun withRestartedTimeReferences(): PipelineState = copy(
        clock = ClockMapper(clock.params),
        seq = SeqTracker(),
        lastHeaderMs = null,
        imus = imus.mapValues { (_, channel) -> channel.copy(lastSums = null, lastSampleMs = null) },
        yaw = YawTracker(),
        motion = MotionDetector(),
        lastMovingMs = null,
        watchGyro = WatchWitness(),
        stale = emptyMap(),
        tracker = TrackerState(ids = tracker.ids.copy(graveyard = emptyList())),
        lastBadFrames = emptyMap(),
        counters = counters.copy(espResets = counters.espResets + 1),
    )

    companion object {
        const val NANOS_PER_MS = 1_000_000L

        fun initial(config: PipelineConfig) = PipelineState(clock = ClockMapper(config.tuning.clock))
    }
}

internal data class Stage(val state: PipelineState, val events: List<PipelineEvent> = emptyList()) {
    fun then(next: (PipelineState) -> Stage): Stage {
        val stage = next(state)
        return Stage(stage.state, events + stage.events)
    }
}
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/SensorHealth.kt`:

```kotlin
package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.imu.BiasStatus
import io.github.santiquiroz.blindside.core.scene.SensorStatus

internal val IMU_IDS = listOf(0, 1)

internal fun flagBit(flags: Int?, bit: Int): Boolean = flags != null && flags and (1 shl bit) != 0

internal fun isLinkUp(state: PipelineState, nowNanos: Long, config: PipelineConfig): Boolean {
    val last = state.lastPacketNanos ?: return false
    return state.connected && nowNanos - last <= config.tuning.status.linkStaleMs * PipelineState.NANOS_PER_MS
}

internal fun aliveRadars(state: PipelineState, config: PipelineConfig): Set<Int> =
    config.mounts.map { it.radarId }.filter { flagBit(state.flags, it) }.toSet()

internal fun isImuUsable(state: PipelineState, imuId: Int): Boolean =
    flagBit(state.flags, 2 + imuId) && state.imus[imuId]?.status != BiasStatus.DEFECTIVE

internal fun usableImus(state: PipelineState): List<Int> = IMU_IDS.filter { isImuUsable(state, it) }

internal fun radarStatuses(state: PipelineState, linkUp: Boolean, config: PipelineConfig): List<SensorStatus> =
    config.mounts.map { SensorStatus(it.radarId, linkUp && flagBit(state.flags, it.radarId)) }

internal fun imuStatuses(state: PipelineState, linkUp: Boolean): List<SensorStatus> =
    IMU_IDS.map { SensorStatus(it, linkUp && isImuUsable(state, it)) }
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/PacketIngest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.alerts.TrackConfirmed
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.geometry.StaleMemory
import io.github.santiquiroz.blindside.core.geometry.filterFrame
import io.github.santiquiroz.blindside.core.imu.ChannelUpdate
import io.github.santiquiroz.blindside.core.imu.ImuChannel
import io.github.santiquiroz.blindside.core.imu.MotionDetector
import io.github.santiquiroz.blindside.core.imu.RestEvidence
import io.github.santiquiroz.blindside.core.imu.ingestBatch
import io.github.santiquiroz.blindside.core.imu.mergeIncrements
import io.github.santiquiroz.blindside.core.protocol.Bundle
import io.github.santiquiroz.blindside.core.protocol.BundleDecoder
import io.github.santiquiroz.blindside.core.protocol.RadarFrame
import io.github.santiquiroz.blindside.core.tracking.MotionContext
import io.github.santiquiroz.blindside.core.tracking.Tracker
import io.github.santiquiroz.blindside.core.tracking.TrackerContext
import io.github.santiquiroz.blindside.core.tracking.TrackerStep

internal fun ingestPacket(state: PipelineState, bytes: ByteArray, arrivalNanos: Long, config: PipelineConfig): Stage {
    val received = state.copy(lastPacketNanos = arrivalNanos, counters = state.counters.copy(packets = state.counters.packets + 1))
    val bundle = BundleDecoder.decode(bytes) ?: return Stage(malformed(received, arrivalNanos, config))
    return Stage(received)
        .then { Stage(resetIfEspRebooted(it, bundle)) }
        .then { Stage(withHeader(it, bundle, arrivalNanos, config)) }
        .then { Stage(withImus(it, bundle, config)) }
        .then { Stage(withMovingMark(it, bundle.tMs, config)) }
        .then { Stage(withStatuses(it, bundle, arrivalNanos, config)) }
        .then { withFrames(it, bundle, arrivalNanos, config) }
        .then { advanceTracker(it, bundle.tMs - config.tuning.tracking.windowMs, arrivalNanos, config) }
}

internal fun trackerContext(state: PipelineState, config: PipelineConfig): TrackerContext {
    val tuning = config.tuning
    return TrackerContext(
        mounts = config.mounts,
        aliveRadars = aliveRadars(state, config),
        yawAt = { tMs -> state.yaw.yawAt(tMs, tuning.imu) },
        motionAt = { tMs -> motionContext(state, tMs, config) },
    )
}

// Spec §6.6 "detenerse y escanear": the gate stays shut while moving and for the 0.5 s tail after the last moving packet.
internal fun motionContext(state: PipelineState, tMs: Long, config: PipelineConfig): MotionContext {
    val gateOpenFromMs = state.lastMovingMs?.let { it + config.tuning.tracking.stopScanTailMs } ?: Long.MIN_VALUE
    return MotionContext(moving = state.motion.isMoving(tMs, config.tuning.motion), gateOpenFromMs = gateOpenFromMs)
}

private fun malformed(state: PipelineState, nowNanos: Long, config: PipelineConfig): PipelineState =
    state.copy(counters = state.counters.copy(malformedPackets = state.counters.malformedPackets + 1))
        .markCorrupt(nowNanos, 1, config.tuning.status.corruptWindowMs)

private fun resetIfEspRebooted(state: PipelineState, bundle: Bundle): PipelineState {
    val last = state.lastHeaderMs ?: return state
    return if (bundle.tMs < last) state.withRestartedTimeReferences() else state
}

private fun withHeader(state: PipelineState, bundle: Bundle, arrivalNanos: Long, config: PipelineConfig): PipelineState {
    val seq = state.seq.observe(bundle.seq, bundle.tMs)
    val truncated = if (bundle.truncated) 1 else 0
    return state.copy(
        clock = state.clock.observe(bundle.tMs, arrivalNanos),
        seq = seq,
        lastHeaderMs = bundle.tMs,
        flags = bundle.flags,
        counters = state.counters.copy(
            lostPackets = seq.lostPackets,
            truncatedPackets = state.counters.truncatedPackets + truncated,
            lastLink = bundle.links.lastOrNull() ?: state.counters.lastLink,
        ),
    ).markCorrupt(arrivalNanos, truncated, config.tuning.status.corruptWindowMs)
}

private fun withImus(state: PipelineState, bundle: Bundle, config: PipelineConfig): PipelineState {
    val imuParams = config.tuning.imu
    val evidence = RestEvidence(state.watchGyro, state.motion.lastStepMs)
    val updates = bundle.imuBatches.filter { bundle.imuOk(it.imuId) }.associate { batch ->
        batch.imuId to (state.imus[batch.imuId] ?: ImuChannel()).ingestBatch(batch, evidence, imuParams)
    }
    val imus = state.imus + updates.mapValues { it.value.channel }
    val increments = mergeIncrements(updates.values.map { it.increments })
    val prone = imus.values.any { it.isReady && it.isProne(imuParams) }
    val motion = increments.fold(state.motion) { m, inc -> m.withYawIncrement(inc, config.tuning.motion) }
        .let { withAccelNorms(it, updates.values, config) }
        .withProne(prone)
    val next = state.copy(imus = imus, yaw = state.yaw.apply(increments, imuParams))
    return next.copy(motion = motion.withTurnSource(fromWatch = usableImus(next).isEmpty()))
}

private fun withAccelNorms(motion: MotionDetector, updates: Collection<ChannelUpdate>, config: PipelineConfig): MotionDetector =
    updates.flatMap { it.readings }
        .groupBy { it.tMs }
        .toSortedMap()
        .entries
        .fold(motion) { m, (tMs, readings) -> m.withAccelNorm(tMs, readings.map { it.accelG.norm }.average(), config.tuning.motion) }

private fun withMovingMark(state: PipelineState, tMs: Long, config: PipelineConfig): PipelineState =
    if (state.motion.isMoving(tMs, config.tuning.motion)) state.copy(lastMovingMs = tMs) else state

private fun withStatuses(state: PipelineState, bundle: Bundle, arrivalNanos: Long, config: PipelineConfig): PipelineState =
    bundle.statuses.fold(state) { acc, status ->
        val previous = acc.lastBadFrames[status.radarId] ?: status.badFrames
        acc.copy(lastBadFrames = acc.lastBadFrames + (status.radarId to status.badFrames))
            .markCorrupt(arrivalNanos, status.badFrames - previous, config.tuning.status.corruptWindowMs)
    }

private fun withFrames(state: PipelineState, bundle: Bundle, arrivalNanos: Long, config: PipelineConfig): Stage =
    bundle.radarFrames
        .sortedWith(compareBy({ it.tMs }, { it.radarId }))
        .fold(Stage(state)) { stage, frame -> stage.then { withFrame(it, frame, arrivalNanos, config) } }

private fun withFrame(state: PipelineState, frame: RadarFrame, arrivalNanos: Long, config: PipelineConfig): Stage {
    val mount = config.mounts.firstOrNull { it.radarId == frame.radarId } ?: return Stage(state)
    if (Tracker.isOutOfOrder(state.tracker, frame.tMs)) {
        return Stage(state.copy(counters = state.counters.copy(outOfOrderFrames = state.counters.outOfOrderFrames + 1)))
    }
    val tuning = config.tuning
    val filtered = filterFrame(frame, mount, state.stale[frame.radarId] ?: StaleMemory(), tuning.decode)
    val counters = state.counters.copy(
        implausibleTargets = state.counters.implausibleTargets + filtered.frame.implausible,
        staleTargets = state.counters.staleTargets + filtered.frame.stale,
        nearFieldTargets = state.counters.nearFieldTargets + filtered.frame.nearField,
    )
    val step = Tracker.onFrame(state.tracker, filtered.frame, trackerContext(state, config), tuning)
    val next = state.copy(stale = state.stale + (frame.radarId to filtered.memory), counters = withNis(counters, step, state, config))
        .markCorrupt(arrivalNanos, filtered.frame.implausible, tuning.status.corruptWindowMs)
    return confirmationStage(next, step, arrivalNanos)
}

// Spec §6.3: τ is tuned by minimising the NIS while turning, so only turning updates are summed.
private fun withNis(counters: PipelineCounters, step: TrackerStep, state: PipelineState, config: PipelineConfig): PipelineCounters {
    if (step.nis.isEmpty() || !state.motion.isTurning(config.tuning.motion)) return counters
    return counters.copy(turningNisSum = counters.turningNisSum + step.nis.sum(), turningNisCount = counters.turningNisCount + step.nis.size)
}

private fun advanceTracker(state: PipelineState, tMs: Long, arrivalNanos: Long, config: PipelineConfig): Stage =
    confirmationStage(state, Tracker.advanceTo(state.tracker, tMs, trackerContext(state, config), config.tuning), arrivalNanos)

private fun confirmationStage(state: PipelineState, step: TrackerStep, arrivalNanos: Long): Stage =
    Stage(state.copy(tracker = step.state), step.confirmed.map { TrackConfirmed(it.displayId, arrivalNanos) })
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/WatchInputs.kt`:

```kotlin
package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.clock.ClockMapper
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.imu.ImuChannel
import io.github.santiquiroz.blindside.core.protocol.ImuScale
import io.github.santiquiroz.blindside.core.protocol.parseBeltInfo
import kotlin.math.sqrt

internal fun withWatchStep(state: PipelineState, eventNanos: Long): PipelineState {
    val espMs = state.clock.toEspMs(eventNanos) ?: return state
    return state.copy(motion = state.motion.withStep(espMs))
}

// Android delivers TYPE_GYROSCOPE in rad/s; the core works in °/s on the ESP32 clock.
internal fun withWatchGyro(state: PipelineState, x: Float, y: Float, z: Float, eventNanos: Long, config: PipelineConfig): PipelineState {
    val espMs = state.clock.toEspMs(eventNanos) ?: return state
    val rateDps = Math.toDegrees(sqrt((x * x + y * y + z * z).toDouble()))
    return state.copy(
        watchGyro = state.watchGyro.withSample(espMs, rateDps, config.tuning.imu),
        motion = state.motion.withWatchRate(espMs, rateDps, config.tuning.motion),
    )
}

// Spec §6.3: a different boot_id means the ESP32 rebooted even if t_ms did not go backwards.
internal fun withBeltInfo(state: PipelineState, json: String): PipelineState {
    val info = parseBeltInfo(json) ?: return state
    val rebooted = state.bootId != null && info.bootId != null && info.bootId != state.bootId
    val reset = if (rebooted) state.withRestartedTimeReferences() else state
    return reset.copy(bootId = info.bootId ?: state.bootId, imus = withScales(reset.imus, info.imuScales))
}

// Spec §6.3: the clock mapping restarts on every (re)connection.
internal fun withLinkState(state: PipelineState, connected: Boolean): PipelineState =
    if (connected) state.copy(connected = true, clock = ClockMapper(state.clock.params)) else state.copy(connected = false)

private fun withScales(imus: Map<Int, ImuChannel>, scales: List<ImuScale>): Map<Int, ImuChannel> =
    imus.mapValues { (id, channel) ->
        scales.firstOrNull { it.imuId == id }?.let { channel.withScales(it.gyroLsbPerDps, it.accelLsbPerG) } ?: channel
    }
```

Tasks 16b and 16c replace `RadarPipeline.kt` whole; this first version has no warnings, no system alerts and no contact alerts.

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/RadarPipeline.kt`:

```kotlin
package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.alerts.PipelineEvent
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.imu.Vec3
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.SceneInputs
import io.github.santiquiroz.blindside.core.scene.buildScene

class RadarPipeline(private val config: PipelineConfig) {
    private var state: PipelineState = PipelineState.initial(config)

    fun onBlePacket(bytes: ByteArray, arrivalNanos: Long): List<PipelineEvent> = commit(ingestPacket(state, bytes, arrivalNanos, config))

    fun onWatchGravity(x: Float, y: Float, z: Float, eventNanos: Long) {
        state = state.copy(watchGravity = Vec3(x.toDouble(), y.toDouble(), z.toDouble()))
    }

    fun onWatchStep(eventNanos: Long) {
        state = withWatchStep(state, eventNanos)
    }

    fun onWatchGyro(x: Float, y: Float, z: Float, eventNanos: Long) {
        state = withWatchGyro(state, x, y, z, eventNanos, config)
    }

    fun onBeltInfo(json: String, nowNanos: Long) {
        state = withBeltInfo(state, json)
    }

    fun onLinkState(connected: Boolean, nowNanos: Long): List<PipelineEvent> {
        state = withLinkState(state, connected)
        return emptyList()
    }

    fun setEliminated(on: Boolean) {
        state = state.copy(eliminated = on)
    }

    fun scene(nowNanos: Long): RadarScene {
        val linkUp = isLinkUp(state, nowNanos, config)
        val nowMs = state.clock.toEspMs(nowNanos) ?: state.lastHeaderMs ?: 0L
        val inputs = SceneInputs(
            nowMs = nowMs,
            tracks = state.tracker.tracks,
            yawDeg = state.yaw.displayYawAt(nowMs, config.tuning.imu),
            mounts = config.mounts,
            radars = radarStatuses(state, linkUp, config),
            imus = imuStatuses(state, linkUp),
            motion = state.motion.state(nowMs, config.tuning.motion),
            warnings = emptySet(),
            linkUp = linkUp,
            eliminated = state.eliminated,
        )
        return buildScene(inputs, config.tuning.tracking, config.tuning.decode)
    }

    fun counters(): PipelineCounters = state.counters

    private fun commit(stage: Stage): List<PipelineEvent> {
        state = stage.state
        return stage.events
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.RadarPipelineTest")`

Expected: PASS, 13 tests, 0 failures. Then run the whole module (`(cd watch && ./gradlew :radar-core:test)`) and confirm every earlier test still passes.

- [ ] **Step 5: Commit**

```bash
git add watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/PipelineState.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/SensorHealth.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/PacketIngest.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/WatchInputs.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/RadarPipeline.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/ScenarioRun.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/RadarPipelineTest.kt
git commit -m "feat: RadarPipeline con ingesta de paquetes, giroscopio del reloj e info del cinturón"
```

### Task 16b: Screen warnings and system alerts

Spec §8 and §5.5. `warningsAt` computes `LINK_LOST` (not connected or no packet for 1 s), `RADAR_DOWN`, `IMU_DOWN`, `NO_IMU_COMPENSATION`, `YAW_UNCALIBRATED` (decision 4), `PRONE`, `CORRUPT_FRAMES` (≥ 20 malformed/truncated packets, implausible targets or new bad frames in 10 s) and `ALERT_OVERFLOW`. System alerts: `RADAR_DOWN` once on an alive → down flag edge, `LINK_LOST` on a connected → disconnected transition; every system alert marks the limiter busy for the pattern length (decision 8). `RadarPipeline.kt` is replaced whole: packets now raise radar-down alerts, `onLinkState(false)` raises `LINK_LOST`, and `scene` carries the warnings.

**Files:**
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/Warnings.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/SystemAlerts.kt`
- Modify: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/RadarPipeline.kt` (whole file)
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/WarningsTest.kt`

**Interfaces:**
- Consumes: `PipelineState`, `Stage`, `isLinkUp`, `aliveRadars`, `usableImus`, `flagBit` (Task 16a); `AlertLimiter.withSystemAlert`, `isSaturated` (Task 13); `runScenario`, `scenarioMsOf` (Task 16a, tests).
- Produces (`io.github.santiquiroz.blindside.core`, internal): `fun warningsAt(state, nowNanos, config): Set<Warning>`; `fun radarDownAlerts(previousFlags: Int?, flags: Int?, config, nowNanos): List<SystemAlert>`; `fun linkLostAlerts(wasConnected, connected, nowNanos): List<SystemAlert>`; `fun withSystemAlerts(state, alerts, config): Stage`.

- [ ] **Step 1: Write the failing tests**

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/WarningsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.alerts.SystemAlert
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.protocol.BundleDecoder
import io.github.santiquiroz.blindside.core.protocol.BundleEncoder
import io.github.santiquiroz.blindside.core.scene.Warning
import io.github.santiquiroz.blindside.core.sim.Scenarios
import io.github.santiquiroz.blindside.core.sim.simArrivalNanos
import io.github.santiquiroz.blindside.core.sim.simulate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WarningsTest {
    @Test
    fun `a dropped link raises one LINK_LOST system alert and greys the scene`() {
        val run = runScenario(Scenarios.crossing())

        val events = run.pipeline.onLinkState(false, simArrivalNanos(8_600))
        val again = run.pipeline.onLinkState(false, simArrivalNanos(8_700))

        assertEquals(listOf(Warning.LINK_LOST), events.filterIsInstance<SystemAlert>().map { it.kind })
        assertTrue(again.isEmpty())
        val scene = run.pipeline.scene(simArrivalNanos(8_700))
        assertFalse(scene.linkUp)
        assertTrue(Warning.LINK_LOST in scene.warnings)
    }

    @Test
    fun `a radar going down raises RADAR_DOWN once and shrinks the coverage`() {
        val run = runScenario(Scenarios.crossing().copy(radarDownFromMs = mapOf(1 to 4_000L)))

        assertEquals(1, run.events.filterIsInstance<SystemAlert>().count { it.kind == Warning.RADAR_DOWN })
        val scene = run.sceneAt(5_000)
        assertTrue(Warning.RADAR_DOWN in scene.warnings)
        assertEquals(1, scene.coverage.size)
    }

    @Test
    fun `the gyro is uncalibrated until the first still 2 s window`() {
        val run = runScenario(Scenarios.crossing())

        assertTrue(Warning.YAW_UNCALIBRATED in run.sceneAt(1_500).warnings)
        assertFalse(Warning.YAW_UNCALIBRATED in run.sceneAt(2_500).warnings)
    }

    @Test
    fun `without a watch gyroscope the bias stays unverified and the prompt stays up`() {
        val run = runScenario(Scenarios.crossing().copy(watchGyroPeriodMs = null))

        assertTrue(Warning.YAW_UNCALIBRATED in run.sceneAt(8_000).warnings)
    }

    @Test
    fun `one imu flag down warns IMU_DOWN, both down warns NO_IMU_COMPENSATION`() {
        val oneDown = runScenario(Scenarios.crossing()) { it.copy(flags = 0x0B) }
        val bothDown = runScenario(Scenarios.crossing()) { it.copy(flags = 0x03) }

        assertTrue(Warning.IMU_DOWN in oneDown.sceneAt(3_000).warnings)
        assertTrue(Warning.NO_IMU_COMPENSATION in bothDown.sceneAt(3_000).warnings)
    }

    @Test
    fun `malformed and truncated packets raise CORRUPT_FRAMES`() {
        val pipeline = RadarPipeline(PipelineConfig())
        pipeline.onLinkState(true, simArrivalNanos(0))
        val good = simulate(Scenarios.crossing())[0]
        pipeline.onBlePacket(good.bytes, good.arrivalNanos)

        repeat(10) { pipeline.onBlePacket(byteArrayOf(9, 9, 9), good.arrivalNanos + it) }
        repeat(10) { pipeline.onBlePacket(good.bytes.copyOfRange(0, good.bytes.size - 3), good.arrivalNanos + 100 + it) }

        assertTrue(Warning.CORRUPT_FRAMES in pipeline.scene(good.arrivalNanos + 200).warnings)
    }

    @Test
    fun `a system alert keeps contact alerts waiting for the length of its pattern`() {
        val config = PipelineConfig()
        val alert = SystemAlert(Warning.LINK_LOST, 5_000_000_000L)

        val stage = withSystemAlerts(PipelineState.initial(config), listOf(alert), config)

        assertEquals(listOf(alert), stage.events)
        assertEquals(5_000_000_000L + config.tuning.alerts.systemPatternMs * 1_000_000L, stage.state.limiter.systemBusyUntilNanos)
    }

    @Test
    fun `radar down is only reported on an alive to down edge`() {
        val config = PipelineConfig()
        val bundle = BundleDecoder.decode(simulate(Scenarios.crossing())[0].bytes)!!
        val down = BundleDecoder.decode(BundleEncoder.encode(bundle.copy(flags = 0x0D)))!!

        assertEquals(1, radarDownAlerts(0x0F, down.flags, config, 1L).size)
        assertTrue(radarDownAlerts(0x0D, down.flags, config, 1L).isEmpty())
        assertTrue(radarDownAlerts(null, down.flags, config, 1L).isEmpty())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.WarningsTest")`

Expected: FAIL. `:radar-core:compileTestKotlin` reports `Unresolved reference` for `withSystemAlerts` and `radarDownAlerts`.

- [ ] **Step 3: Write the implementation**

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/Warnings.kt`:

```kotlin
package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.scene.Warning

internal fun warningsAt(state: PipelineState, nowNanos: Long, config: PipelineConfig): Set<Warning> {
    val linkUp = isLinkUp(state, nowNanos, config)
    return linkWarnings(linkUp) + sensorWarnings(state, linkUp, config) + sessionWarnings(state, nowNanos, config)
}

private fun linkWarnings(linkUp: Boolean): Set<Warning> = if (linkUp) emptySet() else setOf(Warning.LINK_LOST)

private fun sensorWarnings(state: PipelineState, linkUp: Boolean, config: PipelineConfig): Set<Warning> {
    if (!linkUp) return emptySet()
    val usable = usableImus(state)
    return buildSet {
        if (aliveRadars(state, config).size < config.mounts.size) add(Warning.RADAR_DOWN)
        if (usable.size == 1) add(Warning.IMU_DOWN)
        if (usable.isEmpty()) add(Warning.NO_IMU_COMPENSATION)
        if (usable.isNotEmpty() && usable.none { isYawCalibrated(state, it) }) add(Warning.YAW_UNCALIBRATED)
    }
}

private fun sessionWarnings(state: PipelineState, nowNanos: Long, config: PipelineConfig): Set<Warning> {
    val status = config.tuning.status
    return buildSet {
        if (state.motion.prone) add(Warning.PRONE)
        if (state.corruptCount(nowNanos, status.corruptWindowMs) >= status.corruptEventsThreshold) add(Warning.CORRUPT_FRAMES)
        if (state.limiter.isSaturated(nowNanos, config.tuning.alerts)) add(Warning.ALERT_OVERFLOW)
    }
}

// Spec §6.3: an unverified bias ("sin verificar") keeps the "stay still" prompt up, same as no bias at all.
private fun isYawCalibrated(state: PipelineState, imuId: Int): Boolean {
    val channel = state.imus[imuId] ?: return false
    return channel.isReady && channel.biasVerified
}
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/SystemAlerts.kt`:

```kotlin
package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.alerts.SystemAlert
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.scene.Warning

internal fun radarDownAlerts(previousFlags: Int?, flags: Int?, config: PipelineConfig, nowNanos: Long): List<SystemAlert> {
    if (previousFlags == null) return emptyList()
    val wentDown = config.mounts.map { it.radarId }.any { flagBit(previousFlags, it) && !flagBit(flags, it) }
    return if (wentDown) listOf(SystemAlert(Warning.RADAR_DOWN, nowNanos)) else emptyList()
}

internal fun linkLostAlerts(wasConnected: Boolean, connected: Boolean, nowNanos: Long): List<SystemAlert> =
    if (wasConnected && !connected) listOf(SystemAlert(Warning.LINK_LOST, nowNanos)) else emptyList()

// Spec §5.5: a system pattern holds back pending contact alerts until it has finished.
internal fun withSystemAlerts(state: PipelineState, alerts: List<SystemAlert>, config: PipelineConfig): Stage {
    val last = alerts.maxOfOrNull { it.tNanos } ?: return Stage(state)
    return Stage(state.copy(limiter = state.limiter.withSystemAlert(last, config.tuning.alerts)), alerts)
}
```

Replace the contents of `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/RadarPipeline.kt` with:

```kotlin
package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.alerts.PipelineEvent
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.imu.Vec3
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.SceneInputs
import io.github.santiquiroz.blindside.core.scene.buildScene

class RadarPipeline(private val config: PipelineConfig) {
    private var state: PipelineState = PipelineState.initial(config)

    fun onBlePacket(bytes: ByteArray, arrivalNanos: Long): List<PipelineEvent> {
        val previousFlags = state.flags
        return commit(
            ingestPacket(state, bytes, arrivalNanos, config)
                .then { withSystemAlerts(it, radarDownAlerts(previousFlags, it.flags, config, arrivalNanos), config) },
        )
    }

    fun onWatchGravity(x: Float, y: Float, z: Float, eventNanos: Long) {
        state = state.copy(watchGravity = Vec3(x.toDouble(), y.toDouble(), z.toDouble()))
    }

    fun onWatchStep(eventNanos: Long) {
        state = withWatchStep(state, eventNanos)
    }

    fun onWatchGyro(x: Float, y: Float, z: Float, eventNanos: Long) {
        state = withWatchGyro(state, x, y, z, eventNanos, config)
    }

    fun onBeltInfo(json: String, nowNanos: Long) {
        state = withBeltInfo(state, json)
    }

    fun onLinkState(connected: Boolean, nowNanos: Long): List<PipelineEvent> {
        val alerts = linkLostAlerts(state.connected, connected, nowNanos)
        return commit(withSystemAlerts(withLinkState(state, connected), alerts, config))
    }

    fun setEliminated(on: Boolean) {
        state = state.copy(eliminated = on)
    }

    fun scene(nowNanos: Long): RadarScene {
        val linkUp = isLinkUp(state, nowNanos, config)
        val nowMs = state.clock.toEspMs(nowNanos) ?: state.lastHeaderMs ?: 0L
        val inputs = SceneInputs(
            nowMs = nowMs,
            tracks = state.tracker.tracks,
            yawDeg = state.yaw.displayYawAt(nowMs, config.tuning.imu),
            mounts = config.mounts,
            radars = radarStatuses(state, linkUp, config),
            imus = imuStatuses(state, linkUp),
            motion = state.motion.state(nowMs, config.tuning.motion),
            warnings = warningsAt(state, nowNanos, config),
            linkUp = linkUp,
            eliminated = state.eliminated,
        )
        return buildScene(inputs, config.tuning.tracking, config.tuning.decode)
    }

    fun counters(): PipelineCounters = state.counters

    private fun commit(stage: Stage): List<PipelineEvent> {
        state = stage.state
        return stage.events
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.WarningsTest")`

Expected: PASS, 8 tests, 0 failures. Then run the whole module (`(cd watch && ./gradlew :radar-core:test)`) and confirm every earlier test still passes.

- [ ] **Step 5: Commit**

```bash
git add watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/Warnings.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/SystemAlerts.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/RadarPipeline.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/WarningsTest.kt
git commit -m "feat: avisos de pantalla y alertas de sistema que retienen las vibraciones de contacto"
```

### Task 16c: Alert stage, eliminated mode and robustness

Spec §5.5, §6.7 and §10.1. After every packet, every confirmed track is a candidate (the stop-and-scan gate already kept unconfirmed whatever appeared while moving, decision 1): its side comes from ψ(now) in the logical frame, its position from the tracking frame, and the limiter (Task 13) decides whether one vibration starts. Eliminated mode marks every confirmed contact handled and emits no `ContactAlert`. `RadarPipeline.kt` is replaced whole with its final version. The Review Focus tests 2-5 live here.

**Files:**
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/AlertStage.kt`
- Modify: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/RadarPipeline.kt` (whole file)
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/AlertStageTest.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/RobustnessTest.kt`

**Interfaces:**
- Consumes: `PipelineState`, `Stage` (Task 16a); `AlertLimiter`, `AlertFrame`, `AlertCandidate`, `sideOf` (Task 13); `logicalPosition` (Task 14); `withSystemAlerts`, `radarDownAlerts`, `linkLostAlerts`, `warningsAt` (Task 16b); `runScenario`, `scenarioMsOf` (tests).
- Produces (`io.github.santiquiroz.blindside.core`, internal): `fun alertStage(state, nowMs, nowNanos, config): Stage`. `RadarPipeline` keeps the Task 16a signature.

- [ ] **Step 1: Write the failing tests**

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/AlertStageTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.protocol.Bundle
import io.github.santiquiroz.blindside.core.protocol.BundleDecoder
import io.github.santiquiroz.blindside.core.protocol.BundleEncoder
import io.github.santiquiroz.blindside.core.scene.Side
import io.github.santiquiroz.blindside.core.sim.Scenarios
import io.github.santiquiroz.blindside.core.sim.simulate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlertStageTest {
    @Test
    fun `a crossing person vibrates once, on the left`() {
        val run = runScenario(Scenarios.crossing())

        assertEquals(listOf(Side.LEFT), run.alerts.map { it.side })
    }

    @Test
    fun `eliminated mode never vibrates for contacts`() {
        val pipeline = RadarPipeline(PipelineConfig())
        pipeline.setEliminated(true)
        val packets = simulate(Scenarios.crossing())
        pipeline.onLinkState(true, packets.first().arrivalNanos - 1)

        val events = packets.flatMap { pipeline.onBlePacket(it.bytes, it.arrivalNanos) }

        assertTrue(events.none { it is ContactAlert })
    }

    @Test
    fun `contacts present while eliminated do not vibrate after coming back`() {
        val pipeline = RadarPipeline(PipelineConfig())
        val packets = simulate(Scenarios.crossing())
        pipeline.onLinkState(true, packets.first().arrivalNanos - 1)
        pipeline.setEliminated(true)
        val before = packets.take(40).flatMap { pipeline.onBlePacket(it.bytes, it.arrivalNanos) }
        pipeline.setEliminated(false)

        val after = packets.drop(40).flatMap { pipeline.onBlePacket(it.bytes, it.arrivalNanos) }

        assertTrue((before + after).none { it is ContactAlert })
    }

    @Test
    fun `an esp32 reboot does not make a known contact vibrate again`() {
        val run = runScenario(Scenarios.crossing())
        val restarted = simulate(Scenarios.crossing()).take(10).map { packet ->
            BundleEncoder.encode(rebased(BundleDecoder.decode(packet.bytes)!!, -9_000)) to packet.arrivalNanos + 9_000_000_000L
        }

        val events = restarted.flatMap { (bytes, nanos) -> run.pipeline.onBlePacket(bytes, nanos) }

        assertTrue(events.none { it is ContactAlert })
    }

    @Test
    fun `two people at the same range vibrate one after the other, about 1 s apart`() {
        val run = runScenario(Scenarios.twoPeopleSameRange())

        assertEquals(2, run.alerts.map { it.displayId }.toSet().size)
        val gapMs = scenarioMsOf(run.alerts[1].tNanos) - scenarioMsOf(run.alerts[0].tNanos)
        assertTrue(gapMs in 1_000..1_100, "gap was $gapMs ms")
    }

    private fun rebased(bundle: Bundle, deltaMs: Long): Bundle = bundle.copy(
        tMs = bundle.tMs + deltaMs,
        radarFrames = bundle.radarFrames.map { it.copy(tMs = it.tMs + deltaMs) },
        imuBatches = bundle.imuBatches.map { it.copy(tFirstMs = it.tFirstMs + deltaMs) },
    )
}
```

The Review Focus tests 2-5 go in `RobustnessTest`.

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/RobustnessTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.scene.Warning
import io.github.santiquiroz.blindside.core.sim.Scenario
import io.github.santiquiroz.blindside.core.sim.Scenarios
import io.github.santiquiroz.blindside.core.sim.Stand
import io.github.santiquiroz.blindside.core.sim.Walk
import io.github.santiquiroz.blindside.core.sim.walker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RobustnessTest {
    @Test
    fun `a session started while walking alerts after the stop, before the gyro is calibrated`() {
        val scenario = Scenario(
            name = "started-walking",
            player = listOf(Walk(3_000, speedMps = 1.2), Stand(4_000)),
            targets = listOf(walker(3_200, 6_000, start = Point2(-3.0, 5.0), velocityMps = Point2(1.2, 0.0))),
        )

        val run = runScenario(scenario)

        assertTrue(Warning.YAW_UNCALIBRATED in run.sceneAt(2_500).warnings)
        assertEquals(1, run.alerts.size)
        assertTrue(Warning.YAW_UNCALIBRATED in run.sceneAt(scenarioMsOf(run.alerts.single().tNanos)).warnings)
        assertFalse(Warning.YAW_UNCALIBRATED in run.sceneAt(6_900).warnings)
    }

    @Test
    fun `with both imu cables down contacts still alert and the loss is shown`() {
        val run = runScenario(Scenarios.crossing()) { it.copy(flags = it.flags and 0x03) }

        assertEquals(1, run.alerts.size)
        assertTrue(Warning.NO_IMU_COMPENSATION in run.sceneAt(5_000).warnings)
    }

    @Test
    fun `a 2 s ble dropout keeps one id for a rival walking straight through it`() {
        val run = runScenario(Scenarios.crossing().copy(droppedSeqs = (40..59).toSet()))

        assertEquals(20L, run.pipeline.counters().lostPackets)
        assertEquals(1, run.confirmations.size)
        assertEquals(1, run.alerts.size)
    }

    @Test
    fun `sequence numbers wrapping past 65535 are not losses or resets`() {
        val run = runScenario(Scenarios.crossing()) { it.copy(seq = (it.seq + 65_500) and 0xFFFF) }

        assertEquals(0L, run.pipeline.counters().lostPackets)
        assertEquals(0, run.pipeline.counters().espResets)
        assertEquals(1, run.alerts.size)
    }

    @Test
    fun `two box imus sampling 7 ms apart do not turn a still object into a contact while turning`() {
        val run = runScenario(Scenarios.turningWithStillTarget().copy(imuPhaseOffsetMs = listOf(0, 7)))

        assertTrue(run.confirmations.isEmpty())
        assertTrue(run.alerts.isEmpty())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.AlertStageTest" --tests "io.github.santiquiroz.blindside.core.RobustnessTest")`

Expected: FAIL. The tests compile (they only use Task 16a's API), but every test that expects a `ContactAlert` fails with `expected: <1> but was: <0>`, because nothing emits contact alerts yet.

- [ ] **Step 3: Write the implementation**

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/AlertStage.kt`:

```kotlin
package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.alerts.AlertCandidate
import io.github.santiquiroz.blindside.core.alerts.AlertFrame
import io.github.santiquiroz.blindside.core.alerts.sideOf
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.scene.logicalPosition
import io.github.santiquiroz.blindside.core.tracking.Track
import io.github.santiquiroz.blindside.core.tracking.TrackStatus

// Stop and scan already lives in the tracker (nothing is confirmed while moving), so every confirmed track is a candidate.
internal fun alertStage(state: PipelineState, nowMs: Long, nowNanos: Long, config: PipelineConfig): Stage {
    val confirmed = state.tracker.tracks.filter { it.status == TrackStatus.CONFIRMED }
    if (state.eliminated) return Stage(state.copy(limiter = state.limiter.silence(confirmed.map { it.displayId })))
    val yaw = state.yaw.yawAt(nowMs, config.tuning.imu)
    val candidates = confirmed.map { candidateOf(it, nowMs, yaw, config) }
    val frame = AlertFrame(candidates, nowNanos, yaw, playerMoving = state.motion.isMoving(nowMs, config.tuning.motion))
    val outcome = state.limiter.step(frame, config.tuning.alerts)
    return Stage(state.copy(limiter = outcome.limiter), listOfNotNull(outcome.fired))
}

private fun candidateOf(track: Track, nowMs: Long, yawDeg: Double, config: PipelineConfig): AlertCandidate {
    val tracking = config.tuning.tracking
    val logical = logicalPosition(track, nowMs, yawDeg, tracking)
    return AlertCandidate(
        displayId = track.displayId,
        side = sideOf(logical.bearingDeg, config.tuning.alerts),
        rangeM = logical.norm,
        position = track.predictedTo(nowMs, tracking).kalman.position,
    )
}
```

Replace the contents of `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/RadarPipeline.kt` with:

```kotlin
package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.alerts.PipelineEvent
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.imu.Vec3
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.SceneInputs
import io.github.santiquiroz.blindside.core.scene.buildScene

class RadarPipeline(private val config: PipelineConfig) {
    private var state: PipelineState = PipelineState.initial(config)

    fun onBlePacket(bytes: ByteArray, arrivalNanos: Long): List<PipelineEvent> {
        val previousFlags = state.flags
        return commit(
            ingestPacket(state, bytes, arrivalNanos, config)
                .then { withSystemAlerts(it, radarDownAlerts(previousFlags, it.flags, config, arrivalNanos), config) }
                .then { alertStage(it, it.lastHeaderMs ?: 0L, arrivalNanos, config) },
        )
    }

    fun onWatchGravity(x: Float, y: Float, z: Float, eventNanos: Long) {
        state = state.copy(watchGravity = Vec3(x.toDouble(), y.toDouble(), z.toDouble()))
    }

    fun onWatchStep(eventNanos: Long) {
        state = withWatchStep(state, eventNanos)
    }

    fun onWatchGyro(x: Float, y: Float, z: Float, eventNanos: Long) {
        state = withWatchGyro(state, x, y, z, eventNanos, config)
    }

    fun onBeltInfo(json: String, nowNanos: Long) {
        state = withBeltInfo(state, json)
    }

    fun onLinkState(connected: Boolean, nowNanos: Long): List<PipelineEvent> {
        val alerts = linkLostAlerts(state.connected, connected, nowNanos)
        return commit(withSystemAlerts(withLinkState(state, connected), alerts, config))
    }

    fun setEliminated(on: Boolean) {
        state = state.copy(eliminated = on)
    }

    fun scene(nowNanos: Long): RadarScene {
        val linkUp = isLinkUp(state, nowNanos, config)
        val nowMs = state.clock.toEspMs(nowNanos) ?: state.lastHeaderMs ?: 0L
        val inputs = SceneInputs(
            nowMs = nowMs,
            tracks = state.tracker.tracks,
            yawDeg = state.yaw.displayYawAt(nowMs, config.tuning.imu),
            mounts = config.mounts,
            radars = radarStatuses(state, linkUp, config),
            imus = imuStatuses(state, linkUp),
            motion = state.motion.state(nowMs, config.tuning.motion),
            warnings = warningsAt(state, nowNanos, config),
            linkUp = linkUp,
            eliminated = state.eliminated,
        )
        return buildScene(inputs, config.tuning.tracking, config.tuning.decode)
    }

    fun counters(): PipelineCounters = state.counters

    private fun commit(stage: Stage): List<PipelineEvent> {
        state = stage.state
        return stage.events
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.AlertStageTest" --tests "io.github.santiquiroz.blindside.core.RobustnessTest")`

Expected: PASS, 10 tests, 0 failures. Then run the whole module (`(cd watch && ./gradlew :radar-core:test)`) and confirm every earlier test still passes. If a Review Focus test fails, fix the pipeline code, not the test.

- [ ] **Step 5: Commit**

```bash
git add watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/AlertStage.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/RadarPipeline.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/AlertStageTest.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/RobustnessTest.kt
git commit -m "feat: etapa de alertas de contacto, modo eliminado y pruebas de robustez del pipeline"
```

### Task 17: Deterministic replay and the τ sweep

Spec §6.10 and §6.3. `replayRecording` feeds a recording into a `RadarPipeline` with the recording's own clock: BLE packets, watch gravity, watch gyroscope (record 9, which keeps the bias witness deterministic), watch steps, `info` re-reads (record 8: scales and `boot_id`), mode changes (eliminated on/off) and link changes (`LINK_UP`/`LINK_DOWN` mode changes → `onLinkState`, contracts). Markers, track-confirmed, vibration-started and RSSI records are output data and are skipped. `pipelineConfigFromHeader` rebuilds the session config from the header's `"config"` key (Task 4d). `sweepTau` replays the same records with τ = 0-200 ms in 10 ms steps and returns the mean NIS of confirmed-track updates made while turning; `bestTau` picks the minimum (decision 12; spec §12 calendar 5-7 oct uses it on the 4-oct recording).

**Files:**
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/replay/Replay.kt`
- Create: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/replay/TauSweep.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/replay/ReplayTest.kt`
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/replay/TauSweepTest.kt`

**Interfaces:**
- Consumes: `RadarPipeline`, `PipelineCounters.meanTurningNis` (Task 16c); `BsrecRecord`, `RecordType`, `BsrecPayloads`, `SessionMode`, `BsrecWriter`, `BsrecReader` (Task 4b); `pipelineConfigFromValue`, `toJson` (Task 4d); `MiniJson` (Task 4c); `scenarioInputs`, `runScenario`, `PacketInput`, `GyroInput` (Task 16a, tests).
- Produces (`io.github.santiquiroz.blindside.core.replay`): `fun replayRecording(records: Sequence<BsrecRecord>, pipeline: RadarPipeline, startNanos: Long = 0): List<PipelineEvent>`; `fun pipelineConfigFromHeader(headerJson: String): PipelineConfig`; `val DEFAULT_TAU_SWEEP_MS: List<Long>`; `fun sweepTau(records: List<BsrecRecord>, config: PipelineConfig, startNanos: Long, tausMs: List<Long> = DEFAULT_TAU_SWEEP_MS): Map<Long, Double?>`; `fun bestTau(sweep: Map<Long, Double?>): Long?`.

- [ ] **Step 1: Write the failing tests**

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/replay/ReplayTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.replay

import io.github.santiquiroz.blindside.core.GyroInput
import io.github.santiquiroz.blindside.core.PacketInput
import io.github.santiquiroz.blindside.core.RadarPipeline
import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.alerts.SystemAlert
import io.github.santiquiroz.blindside.core.config.ImuParams
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.config.TuningParams
import io.github.santiquiroz.blindside.core.config.toJson
import io.github.santiquiroz.blindside.core.runScenario
import io.github.santiquiroz.blindside.core.scene.Warning
import io.github.santiquiroz.blindside.core.scenarioInputs
import io.github.santiquiroz.blindside.core.sim.Scenario
import io.github.santiquiroz.blindside.core.sim.Scenarios
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ReplayTest {
    @Test
    fun `replaying a recording with watch gyro records gives the same events as the live run`() {
        val scenario = Scenarios.turningWithMarcher()
        val live = runScenario(scenario)

        val (header, records) = readBack(recordingOf(scenario, PipelineConfig()))
        val replayed = replayRecording(records.asSequence(), RadarPipeline(pipelineConfigFromHeader(header)), startOf(scenario))

        assertTrue(live.events.isNotEmpty())
        assertEquals(live.events, replayed)
    }

    @Test
    fun `an info record in the recording reaches the pipeline`() {
        val scenario = Scenarios.crossing()
        val start = startOf(scenario)
        val info = BsrecRecord(RecordType.INFO_REREAD, 0, BsrecPayloads.info("""{"boot_id":"a1"}"""))
        val reboot = BsrecRecord(RecordType.INFO_REREAD, 8_000, BsrecPayloads.info("""{"boot_id":"b2"}"""))
        val pipeline = RadarPipeline(PipelineConfig())

        replayRecording((listOf(info) + recordsOf(scenario, start) + reboot).asSequence(), pipeline, start)

        assertEquals(1, pipeline.counters().espResets)
    }

    @Test
    fun `a recorded link drop replays as a link loss`() {
        val drop = BsrecRecord(RecordType.MODE_CHANGE, 500, BsrecPayloads.linkChange(false))

        val events = replayRecording(sequenceOf(drop), RadarPipeline(PipelineConfig()), startNanos = 0L)

        assertEquals(listOf(SystemAlert(Warning.LINK_LOST, 500_000_000L)), events)
    }

    @Test
    fun `a mode change to eliminated silences the replay`() {
        val scenario = Scenarios.crossing()
        val start = startOf(scenario)
        val mode = BsrecRecord(RecordType.MODE_CHANGE, 0, BsrecPayloads.modeChange(SessionMode.ELIMINATED))

        val events = replayRecording((listOf(mode) + recordsOf(scenario, start)).asSequence(), RadarPipeline(PipelineConfig()), start)

        assertEquals(0, events.filterIsInstance<ContactAlert>().size)
    }

    @Test
    fun `the pipeline config is rebuilt from the recording header`() {
        val config = PipelineConfig(TuningParams(imu = ImuParams(radarImuDelayMs = 40)))

        assertEquals(config, pipelineConfigFromHeader("""{"proto":1,"config":${config.toJson()}}"""))
        assertEquals(PipelineConfig(), pipelineConfigFromHeader("""{"proto":1}"""))
    }

    private fun startOf(scenario: Scenario): Long = scenarioInputs(scenario).first().nanos

    private fun recordsOf(scenario: Scenario, start: Long): List<BsrecRecord> = scenarioInputs(scenario).map { input ->
        val tMs = (input.nanos - start) / 1_000_000L
        when (input) {
            is PacketInput -> BsrecRecord(RecordType.BLE_PACKET, tMs, input.bytes)
            is GyroInput -> BsrecRecord(RecordType.WATCH_GYRO, tMs, BsrecPayloads.watchGyro(input.sample.x, input.sample.y, input.sample.z, input.nanos))
        }
    }

    private fun recordingOf(scenario: Scenario, config: PipelineConfig): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = BsrecWriter(out, """{"proto":1,"config":${config.toJson()}}""")
        recordsOf(scenario, startOf(scenario)).forEach { writer.write(it) }
        writer.close()
        return out.toByteArray()
    }

    private fun readBack(bytes: ByteArray): Pair<String, List<BsrecRecord>> {
        val reader = BsrecReader(ByteArrayInputStream(bytes))
        return reader.headerJson to reader.records().toList()
    }
}
```

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/replay/TauSweepTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.replay

import io.github.santiquiroz.blindside.core.GyroInput
import io.github.santiquiroz.blindside.core.PacketInput
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.scenarioInputs
import io.github.santiquiroz.blindside.core.sim.Scenario
import io.github.santiquiroz.blindside.core.sim.Scenarios
import io.github.santiquiroz.blindside.core.sim.Stand
import io.github.santiquiroz.blindside.core.sim.Turn
import io.github.santiquiroz.blindside.core.sim.walker
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TauSweepTest {
    // Spec §12, 4-oct recording: a friend paces slowly in front while the player turns one way and the other.
    private val pacing = Scenario(
        name = "tau-sweep",
        player = listOf(Stand(Scenarios.WARMUP_MS + 1_500)) + List(4) { i -> Turn(1_000, rateDps = if (i % 2 == 0) 90.0 else -90.0) } + Stand(1_000),
        targets = listOf(walker(Scenarios.WARMUP_MS, Scenarios.WARMUP_MS + 7_500, start = Point2(1.0, 2.8), velocityMps = Point2(0.3, 0.0))),
        radarLatencyMs = 100,
    )

    @Test
    fun `the sweep over a recording with 100 ms of radar latency bottoms out at 100 ms`() {
        val inputs = scenarioInputs(pacing)
        val start = inputs.first().nanos
        val records = inputs.map { input ->
            val tMs = (input.nanos - start) / 1_000_000L
            when (input) {
                is PacketInput -> BsrecRecord(RecordType.BLE_PACKET, tMs, input.bytes)
                is GyroInput -> BsrecRecord(RecordType.WATCH_GYRO, tMs, BsrecPayloads.watchGyro(input.sample.x, input.sample.y, input.sample.z, input.nanos))
            }
        }

        val sweep = sweepTau(records, PipelineConfig(), start)

        val best = bestTau(sweep)!!
        assertTrue(best in 80L..120L, "best tau was $best ms, sweep $sweep")
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.replay.ReplayTest" --tests "io.github.santiquiroz.blindside.core.replay.TauSweepTest")`

Expected: FAIL. `:radar-core:compileTestKotlin` reports `Unresolved reference` for `replayRecording`, `pipelineConfigFromHeader`, `sweepTau`, `bestTau`.

- [ ] **Step 3: Write the implementation**

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/replay/Replay.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.replay

import io.github.santiquiroz.blindside.core.RadarPipeline
import io.github.santiquiroz.blindside.core.alerts.PipelineEvent
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.config.pipelineConfigFromValue
import io.github.santiquiroz.blindside.core.protocol.MiniJson

// The app writes the session's PipelineConfig.toJson() under "config" in the .bsrec header (spec §6.10).
fun pipelineConfigFromHeader(headerJson: String): PipelineConfig =
    pipelineConfigFromValue((MiniJson.parseOrNull(headerJson) as? Map<*, *>)?.get("config"))

// Deterministic replay: the recording's own clock drives the pipeline; the initial link-up only matters for recordings without link records.
fun replayRecording(records: Sequence<BsrecRecord>, pipeline: RadarPipeline, startNanos: Long = 0): List<PipelineEvent> {
    pipeline.onLinkState(true, startNanos)
    return records.flatMap { record -> replayRecord(record, pipeline, startNanos + record.tMsSinceStart * NANOS_PER_MS) }.toList()
}

private fun replayRecord(record: BsrecRecord, pipeline: RadarPipeline, nanos: Long): List<PipelineEvent> = when (record.type) {
    RecordType.BLE_PACKET -> pipeline.onBlePacket(record.payload, nanos)
    RecordType.MODE_CHANGE -> replayModeChange(record.payload, pipeline, nanos)
    else -> emptyList<PipelineEvent>().also { replayWatchInput(record, pipeline, nanos) }
}

// The app records link transitions as LINK_UP/LINK_DOWN mode changes; they go to onLinkState exactly as they did live.
private fun replayModeChange(payload: ByteArray, pipeline: RadarPipeline, nanos: Long): List<PipelineEvent> {
    BsrecPayloads.readLinkChange(payload)?.let { return pipeline.onLinkState(it, nanos) }
    BsrecPayloads.readModeChange(payload)?.let { pipeline.setEliminated(it == SessionMode.ELIMINATED) }
    return emptyList()
}

private fun replayWatchInput(record: BsrecRecord, pipeline: RadarPipeline, nanos: Long) {
    when (record.type) {
        RecordType.WATCH_GRAVITY -> BsrecPayloads.readGravity(record.payload).let { pipeline.onWatchGravity(it.x, it.y, it.z, it.eventNanos) }
        RecordType.WATCH_GYRO -> BsrecPayloads.readWatchGyro(record.payload).let { pipeline.onWatchGyro(it.x, it.y, it.z, it.eventNanos) }
        RecordType.WATCH_STEP -> pipeline.onWatchStep(BsrecPayloads.readStep(record.payload))
        RecordType.INFO_REREAD -> pipeline.onBeltInfo(BsrecPayloads.readInfo(record.payload), nanos)
        else -> Unit
    }
}

private const val NANOS_PER_MS = 1_000_000L
```

Create `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/replay/TauSweep.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.replay

import io.github.santiquiroz.blindside.core.RadarPipeline
import io.github.santiquiroz.blindside.core.config.PipelineConfig

val DEFAULT_TAU_SWEEP_MS: List<Long> = (0L..200L step 10).toList()

// Spec §6.3 / §12: τ (radar → IMU delay) is the value that minimises the mean NIS of the updates made while turning.
fun sweepTau(records: List<BsrecRecord>, config: PipelineConfig, startNanos: Long, tausMs: List<Long> = DEFAULT_TAU_SWEEP_MS): Map<Long, Double?> =
    tausMs.associateWith { tau -> meanTurningNis(records, withTau(config, tau), startNanos) }

fun bestTau(sweep: Map<Long, Double?>): Long? =
    sweep.entries.mapNotNull { (tau, nis) -> nis?.let { tau to it } }.minByOrNull { it.second }?.first

private fun meanTurningNis(records: List<BsrecRecord>, config: PipelineConfig, startNanos: Long): Double? {
    val pipeline = RadarPipeline(config)
    replayRecording(records.asSequence(), pipeline, startNanos)
    return pipeline.counters().meanTurningNis
}

private fun withTau(config: PipelineConfig, tauMs: Long): PipelineConfig =
    config.copy(tuning = config.tuning.copy(imu = config.tuning.imu.copy(radarImuDelayMs = tauMs)))
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.replay.ReplayTest" --tests "io.github.santiquiroz.blindside.core.replay.TauSweepTest")`

Expected: PASS, 6 tests, 0 failures (the sweep replays 21 times and takes a few seconds). Then run the whole module (`(cd watch && ./gradlew :radar-core:test)`) and confirm every earlier test still passes.

- [ ] **Step 5: Commit**

```bash
git add watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/replay/Replay.kt watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/replay/TauSweep.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/replay/ReplayTest.kt watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/replay/TauSweepTest.kt
git commit -m "feat: reproducción determinista de .bsrec y barrido de tau por NIS"
```

### Task 18: Spec §9 scenarios through RadarPipeline

The acceptance gate of the plan: every scenario of spec §9 runs from simulated BLE bytes and watch-gyro events through `RadarPipeline`. Classic set: crossing (confirmed ≤ 0.7 s after the person appears, criterion 1; one LEFT alert; one display id through the radar hand-off); turning with a still object (never confirmed: the gate is shut while turning and the object is invisible once the player stops); turning with a marching rival (one CENTER alert; the blip ends at −45° ± 8° after a 45° right turn); walking toward a wall (no confirmation and no vibration, spec §9); head-on rival while walking (confirmed and one CENTER alert after the player stops); two people at the same range (two ids, two vibrations, no merge); a person who stops (held as COASTING, reacquired with the same id, one alert); a target leaving the cone (one RIGHT alert, out of view, then gone). MVP set (spec §9 "detenerse y escanear"): 100 ms of radar latency, τ off by 100 ms (both τ = 0 and τ = 200 ms are run), static clutter that only shows while turning: wall during a 90° turn → 0 alerts; walker confirmed before the turn → no extra alert; walker who appears during the turn → exactly 1 alert between 0.5 s and 1.5 s after the turn ends (≤ 1 s after the end of the 0.5 s tail); rival who appears while the player walks → exactly 1 alert after the stop.

**Files:**
- Test: `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/ScenarioTest.kt`

**Interfaces:**
- Consumes: `runScenario`, `scenarioMsOf`, `ScenarioRun` (Task 16a); `Scenarios` (Task 15); `Side`, `Confidence`, `PipelineConfig`, `TuningParams`, `ImuParams` (Task 2).
- Produces: nothing new; this task only adds acceptance tests.

- [ ] **Step 1: Write the scenario tests**

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/ScenarioTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.config.ImuParams
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.config.TuningParams
import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.core.scene.Side
import io.github.santiquiroz.blindside.core.sim.Scenario
import io.github.santiquiroz.blindside.core.sim.Scenarios
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScenarioTest {
    @Test
    fun `crossing person is confirmed within 0_7 s, alerts once on the left and keeps one id`() {
        val run = runScenario(Scenarios.crossing())

        assertEquals(1, run.confirmations.size)
        assertTrue(scenarioMsOf(run.confirmations.single().tNanos) <= Scenarios.WARMUP_MS + 700)
        assertEquals(listOf(Side.LEFT), run.alerts.map { it.side })
        val ids = run.scenes.flatMap { (_, scene) -> scene.blips.map { it.displayId } }.toSet()
        assertEquals(1, ids.size)
        assertTrue(run.sceneAt(7_400).blips.single().bearingDeg > 20.0)
    }

    @Test
    fun `turning with a still object in view never confirms it`() {
        val run = runScenario(Scenarios.turningWithStillTarget())

        assertTrue(run.confirmations.isEmpty())
        assertTrue(run.alerts.isEmpty())
    }

    @Test
    fun `turning keeps a confirmed contact fixed in the world and does not re-alert`() {
        val run = runScenario(Scenarios.turningWithMarcher())

        assertEquals(listOf(Side.CENTER), run.alerts.map { it.side })
        assertEquals(-45.0, run.sceneAt(7_600).blips.single().bearingDeg, 8.0)
    }

    @Test
    fun `walking toward a wall confirms nothing`() {
        val run = runScenario(Scenarios.walkingTowardWall())

        assertTrue(run.confirmations.isEmpty())
        assertTrue(run.alerts.isEmpty())
    }

    @Test
    fun `a rival walking head on is confirmed and alerts once the player stops`() {
        val run = runScenario(Scenarios.headOnRival())

        assertEquals(1, run.confirmations.size)
        assertEquals(listOf(Side.CENTER), run.alerts.map { it.side })
        assertTrue(scenarioMsOf(run.alerts.single().tNanos) > Scenarios.WARMUP_MS + 1_500)
    }

    @Test
    fun `two people at the same range get two ids and two vibrations`() {
        val run = runScenario(Scenarios.twoPeopleSameRange())

        assertEquals(2, run.confirmations.map { it.displayId }.toSet().size)
        assertEquals(2, run.alerts.size)
    }

    @Test
    fun `a person who stops is held still for the pause and reacquired without a second alert`() {
        val run = runScenario(Scenarios.personStops())

        assertEquals(1, run.confirmations.size)
        assertEquals(1, run.alerts.size)
        val paused = run.sceneAt(Scenarios.WARMUP_MS + 4_800).blips.single()
        assertEquals(Confidence.COASTING, paused.confidence)
        assertEquals(1, run.sceneAt(Scenarios.WARMUP_MS + 6_500).blips.size)
    }

    @Test
    fun `a target leaving the cone shows as out of view, then disappears`() {
        val run = runScenario(Scenarios.targetExitsCone())

        assertEquals(listOf(Side.RIGHT), run.alerts.map { it.side })
        assertTrue(run.sceneAt(Scenarios.WARMUP_MS + 5_200).blips.single().outOfView)
        assertTrue(run.sceneAt(Scenarios.WARMUP_MS + 10_400).blips.isEmpty())
    }

    @Test
    fun `MVP - a 90 degree turn in 0_5 s in front of a wall gives no alert`() {
        tausOffBy100.forEach { config ->
            val run = runWith(Scenarios.wallDuringTurn(), config)

            assertTrue(run.alerts.isEmpty(), "tau ${config.tuning.imu.radarImuDelayMs}")
        }
    }

    @Test
    fun `MVP - a walker confirmed before the turn gives no extra alert`() {
        tausOffBy100.forEach { config ->
            val run = runWith(Scenarios.walkerConfirmedBeforeTurn(), config)

            assertEquals(1, run.alerts.size, "tau ${config.tuning.imu.radarImuDelayMs}")
            assertTrue(scenarioMsOf(run.alerts.single().tNanos) < Scenarios.TURN_START_MS)
        }
    }

    @Test
    fun `MVP - a walker who appears during the turn alerts once, within 1 s of the end of the tail`() {
        tausOffBy100.forEach { config ->
            val run = runWith(Scenarios.walkerAppearsDuringTurn(), config)

            assertEquals(1, run.alerts.size, "tau ${config.tuning.imu.radarImuDelayMs}")
            val alertMs = scenarioMsOf(run.alerts.single().tNanos)
            assertTrue(alertMs in Scenarios.TURN_END_MS + 500..Scenarios.TURN_END_MS + 1_500, "alert at $alertMs ms")
        }
    }

    @Test
    fun `MVP - a rival who appears while the player walks alerts once after the player stops`() {
        tausOffBy100.forEach { config ->
            val run = runWith(Scenarios.rivalWhileWalking(), config)

            assertEquals(1, run.alerts.size, "tau ${config.tuning.imu.radarImuDelayMs}")
            assertTrue(scenarioMsOf(run.alerts.single().tNanos) > Scenarios.WARMUP_MS + 2_000)
        }
    }

    private val tausOffBy100: List<PipelineConfig>
        get() = listOf(0L, 200L).map { tau -> PipelineConfig(TuningParams(imu = ImuParams(radarImuDelayMs = tau))) }

    private fun runWith(scenario: Scenario, config: PipelineConfig): ScenarioRun = runScenario(scenario, config.copy(mounts = scenario.mounts))
}
```

- [ ] **Step 2: Run them**

Run: `(cd watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.ScenarioTest")`

Expected: PASS, 12 tests, 0 failures. These are acceptance tests over code that already exists: if one fails, the defect is in an earlier task (use superpowers:systematic-debugging, find the stage that diverges by printing `run.events` and `run.scenes`), never loosen the assertion.

- [ ] **Step 3: Run the whole module and the code-rule checks**

Run: `(cd watch && ./gradlew :radar-core:clean :radar-core:test)`

Expected: `BUILD SUCCESSFUL`; 34 test classes, 239 tests, 0 failures.

Run: `grep -rn "/\*\*" watch/radar-core/src || echo "no doc comments"`

Expected: `no doc comments`.

Run: `find watch/radar-core/src -name "*.kt" | xargs wc -l | sort -n | tail -3`

Expected: no file above 400 lines (the largest is about 190).

- [ ] **Step 4: Commit**

```bash
git add watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/ScenarioTest.kt
git commit -m "test: escenarios del spec y casos del MVP detenerse y escanear a través de RadarPipeline"
```
