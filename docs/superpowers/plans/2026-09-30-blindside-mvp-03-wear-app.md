# Blindside MVP — Plan 03: Wear OS app (`watch/wear-app`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a Wear OS app for the Galaxy Watch 7 that turns the belt's BLE stream into a radar display, side-coded vibrations and a complete `.bsrec` recording, playable at the 5-hour game on 2026-10-11 (spec §12).

**Architecture:**
- A foreground service (`BlindsideSessionService`) owns everything that must survive a dark screen: GATT, sensors, `radar-core`, the vibrator, the recorder and a partial wake lock.
- Every input (BLE packet, watch sensor event, link change, user action, scene tick) becomes a `SessionInput` on one unlimited channel. One coroutine on `Dispatchers.Default.limitedParallelism(1)` drains it through `SessionEngine`. That engine records the input, feeds `RadarPipeline`, vibrates on `ContactAlert`/`SystemAlert` and publishes `RadarScene`s.
- Everything that decides something is a pure function with a JVM unit test: side→rhythm, settings→`PipelineConfig`, scene→draw model, GATT op queue, reconnect policy, scan limiter, record encoding. Android classes are thin adapters that are verified by building and by Santiago's on-device checklist (Task 15).

**Tech Stack:** Kotlin 2.2.21, AGP 8.10.1, Gradle 8.11.1, JDK 17, Compose for Wear OS 1.4.0 (`compose-material`, `compose-foundation`, `compose-navigation`), Compose BOM 2025.05.00, activity-compose 1.10.1, coroutines 1.9.0, DataStore Preferences 1.1.4, `androidx.wear:wear` 1.3.0 (ambient), `androidx.wear:wear-ongoing` 1.0.0, `androidx.core:core-ktx` 1.13.1, lifecycle-runtime-compose 2.8.7, JUnit 5 (Jupiter) for the JVM tests, `radar-core` (plan 01).

**Spec:** [`docs/superpowers/specs/2026-09-30-blindside-v1-design.md`](../specs/2026-09-30-blindside-v1-design.md) (approved 2026-09-30). **Contracts (binding):** [`2026-09-30-blindside-mvp-00-contracts.md`](2026-09-30-blindside-mvp-00-contracts.md). When this plan and the contracts disagree, the contracts win: stop and flag the conflict instead of improvising.

## Global Constraints

- **Repo and branch:** `C:/personal/blindside`, branch `main`. This plan owns `watch/wear-app/` only. It touches plan 01's Gradle root files only additively (Task 1).
- **Commits:**
  - Conventional prefixes (`feat:`, `fix:`, `test:`, `docs:`, `chore:`), written in Spanish.
  - The author is already set in the repo config.
  - **Never** add a `Co-Authored-By` line.
- **Code style** (owner rules, mandatory):
  - Atomic functions whose name says what they do. Low cyclomatic complexity; extract branches into named functions.
  - **No doc comments.** Only a one-line comment when the *why* is not obvious.
  - Pure functions with explicit dependencies. Immutable data (`data class` + `val`, `copy` instead of mutation). Mutable state lives only in the Android adapter classes and `SessionStore`.
  - Files of 200-400 lines typical, 800 max.
- **Toolchain (contracts; do not upgrade):**
  - Gradle 8.11.1, AGP 8.10.1, Kotlin 2.2.21, JDK 17.
  - `compileSdk 36`, `targetSdk 36`, `minSdk 34`.
  - Wear Compose 1.4.0, Compose BOM `2025.05.00`, activity-compose `1.10.1`, coroutines `1.9.0`, datastore-preferences `1.1.4`.
  - Spec §5.1 mentions AGP 9.4.0 and Wear Compose 1.7.0; the contracts pin the versions above, and the contracts win.
  - The only extra AndroidX artifacts are the ones listed in Tech Stack. They are already compatible with this toolchain. Add no other third-party dependency.
  - `androidx.wear:wear-ongoing` stays at **1.0.0**, not spec §5.1's 1.1.0. Version 1.1.0 went stable on 23-sep-2026 together with `androidx.core` 1.19.x, and its compileSdk/AGP floor has not been checked against the pinned AGP 8.10.1. 1.0.0 already has the whole `OngoingActivity` API the MVP uses; Live Updates (Wear OS 7) are not in the MVP. Revisit in F6 with a build check.
- **Identifiers:**
  - Kotlin package `io.github.santiquiroz.blindside.wear`, which is also the Android `namespace`.
  - `applicationId` `io.github.santiquiroz.blindside`.
  - GATT UUIDs and control bytes exactly as in the contracts ("Identifiers", "`control` writes").
- **`radar-core` usage:**
  - Call the API exactly as in the contracts ("`radar-core` public API").
  - Every timestamp passed in comes from `SystemClock.elapsedRealtimeNanos()` or `SensorEvent.timestamp`.
  - `radar-core` is called from exactly one dispatcher: `Dispatchers.Default.limitedParallelism(1)`.
- **Alerts (contracts, "Behaviour contracts"):**
  - `radar-core` emits `ContactAlert` only when a vibration must start. **The app never re-limits**: it vibrates on every `ContactAlert` and every `SystemAlert`.
- **Haptic vocabulary (spec §5.5):**
  - Centre: one long pulse.
  - Left: short-short.
  - Right: short-long.
  - System: one continuous buzz, clearly longer than the centre pulse.
  - Always `vibrate(effect, VibrationAttributes.createForUsage(usage))` with an explicit usage (alarm by default, switchable in settings until spike S3 decides).
  - A contact vibration never starts while the 1.2 s system buzz is playing: it waits until the buzz ends (spec §5.5), because `vibrate()` cancels the vibration in progress. This is sequencing, not re-limiting.
- **Display (spec §5.3-5.4):**
  - Black background. The fan follows the real coverage, with a fixed 6 m scale and rings at 2 and 4 m.
  - Every blip has the same size: fill encodes confidence, opacity encodes age.
  - Gray plus `--` when the link is down (`RadarScene.linkUp == false`).
  - Sigilo is the default and never turns the screen on by itself.
  - Vista uses keep-screen-on with a 1-2 px burn-in shift every few minutes.
- **Recording:**
  - Every pipeline input, plus track confirmations, vibration starts, mode changes and link changes, goes to a `.bsrec` file through plan 01's `BsrecWriter`.
  - Watch gravity and the watch gyro are recorded at ≤ 10 Hz (spec §5.2, §6.10; contracts).
  - The header (spec §6.10, contracts "`.bsrec` file format") carries the belt's first `info` read verbatim, `"config": config.toJson()` (plan 01 Task 4d: `TuningParams` and the mounts, the MVP's whole calibration), the clocks and the device. The `info` field is `null` for the demo, or when the belt does not answer within 10 s. radar-core owns the config serialiser, so there is no reflection-based dump that could drift from `TuningParams`.
  - The pipeline receives exactly the records that are written. Plan 01 Task 17 replays every record type the app adds (8 `info`, 9 watch gyro, and the `LINK_UP`/`LINK_DOWN` mode changes) and rebuilds the session config from the header's `"config"` with `pipelineConfigFromHeader`, so replays are deterministic.
- **Permissions (spec §5.2):**
  - `BLUETOOTH_SCAN` (neverForLocation), `BLUETOOTH_CONNECT` and `ACTIVITY_RECOGNITION` are runtime permissions, requested by "Iniciar partida".
  - A denied Bluetooth permission blocks the session with an explanation.
  - A denied `ACTIVITY_RECOGNITION` only disables the watch step detector.
- **MVP scope = spec §12 "Entra".** Do **not** build any of these here:
  - tactical posture or the "cero" wizard;
  - calibration wizards;
  - contact colour choice;
  - "encender ante contacto";
  - fine burn-in work;
  - brightness control;
  - "aviso cercano";
  - battery-low suggestions;
  - IDENTIFY (`03`) from the app: spec §4.5 allows it only on configuration screens, and §12 "Entra" does not list it.
- **UI language:** Spanish user-facing strings. Code identifiers are English.
- **Commands:**
  - Run every Gradle command from `C:/personal/blindside/watch` with `./gradlew` (Git Bash; `.\gradlew.bat` in PowerShell). Unit tests use `:wear-app:testDebugUnitTest`.
  - When a step runs adb with a `/sdcard/...` path from Git Bash, prefix the command with `MSYS_NO_PATHCONV=1`.

## Review Focus

These are the failure modes the spec implies but no happy-path test exercises. Each one has a test in the task that owns the code.

1. **`radar-core` throws on one input** (a malformed packet, an edge case in the tracker). The session must keep running for the remaining hours: the engine counts the failure and processes the next input. Test: Task 5, `keeps processing inputs after the pipeline throws`.
2. **The eliminated flag persisted from the previous game** (Santiago stopped the session while eliminated). A new session must start in play. Otherwise it would start silently with no contact vibrations. Test: Task 2, `a new session never starts eliminated`.
3. **Repeated `DISCONNECTED` callbacks during one outage.** These come from the pending connection, direct attempts, status 133 and a Bluetooth toggle, and late callbacks of a connection that was already replaced must be ignored. They must produce exactly one link-down report, so that there is one system buzz and one `LINK_DOWN` record (spec §8). Test: Task 7, `one outage reports link down exactly once` (`LinkReporter` over three disconnects, a Bluetooth-off, another disconnect and the reconnection).
4. **The recording fails mid-game** (storage full, I/O error). The session, alerts and display must continue; the failure is reported once and shown on the home screen. Test: Task 4, `a failing sink reports once and the session keeps going`.
5. **Stored settings the app no longer understands** (an enum renamed by an update, an out-of-range speed sign). They must read as defaults, never crash at start. Test: Task 2, `unknown enum names fall back to defaults instead of crashing`.

---

## File structure

All paths are under `watch/wear-app/`. Production code lives in `src/main/kotlin/io/github/santiquiroz/blindside/wear/`, abbreviated `…/wear/` below. Tests mirror it in `src/test/kotlin/io/github/santiquiroz/blindside/wear/`.

| File | Responsibility | Task |
|---|---|---|
| `build.gradle.kts`, `src/main/AndroidManifest.xml`, `src/main/res/drawable/ic_radar.xml` | Module build, permissions, launcher activity, service declaration | 1, 10 |
| `…/wear/MainActivity.kt` | Activity: ambient observer + `setContent` | 1, 10b, 14 |
| `…/wear/SpikeScreen.kt` | Start/stop screen for the 2-3 oct spikes; kept by Task 14 under Ajustes → "Diagnóstico (spikes)" | 10b |
| `…/wear/permissions/SessionPermissions.kt` | Permission list and the pure start decision | 1 |
| `…/wear/settings/AppSettings.kt` | Settings model and pure edits | 2 |
| `…/wear/settings/PipelineConfigMapping.kt` | settings → `PipelineConfig` / mounts | 2 |
| `…/wear/settings/SettingsPreferences.kt` | DataStore keys and pure read/write mapping | 2 |
| `…/wear/settings/SettingsRepository.kt` | DataStore wrapper | 2 |
| `…/wear/haptics/HapticPattern.kt` | Rhythms, side→pattern, event→pattern, amplitudes | 3 |
| `…/wear/haptics/HapticRendering.kt` | `HapticSink`, renderer choice and the do-not-disturb check | 3 |
| `…/wear/haptics/HapticPlayer.kt` | `Vibrator` adapter (`HapticSink`) | 3 |
| `…/wear/haptics/HapticGate.kt` | Contacts wait for a running system buzz (pure) | 5 |
| `…/wear/session/SessionInput.kt` | Every input the session processes | 4 |
| `…/wear/recording/RecordEncoding.kt` | Input/event → `BsrecRecord` (pure) | 4 |
| `…/wear/recording/RecordingHeader.kt` | Header JSON (with `"config"` from radar-core's `PipelineConfig.toJson()`), metadata and file name (pure) | 4 |
| `…/wear/recording/RecordSink.kt` | `RecordSink`, file sink, failure-safe sink, header-waits-for-`info` sink, opener | 4 |
| `…/wear/recording/RecordingDirs.kt` | Recordings directory on the watch | 4 |
| `…/wear/session/PipelinePort.kt` | Port over `RadarPipeline` + adapter | 5 |
| `…/wear/session/SessionEngine.kt` | One input → record, pipeline, haptics, scene | 5 |
| `…/wear/session/ScenePacing.kt` | Scene tick period per screen state | 5 |
| `…/wear/sensors/SensorMath.kt` | Wake-up preference, rate gate, low-pass (pure) | 6 |
| `…/wear/sensors/WatchSensors.kt` | `SensorManager` adapter | 6 |
| `…/wear/ble/BleIds.kt` | UUIDs, control bytes, name check | 7 |
| `…/wear/ble/GattOps.kt` | Serial GATT op queue and setup rules (pure) | 7 |
| `…/wear/ble/ReconnectPolicy.kt` | When to try a direct connect or a scan (pure) | 7 |
| `…/wear/ble/ScanLimiter.kt` | ≤ 4 `startScan` per 30 s (pure) | 7 |
| `…/wear/ble/LinkState.kt` | `LinkReporter` (one report per transition), `BleStatus`, halted statuses | 7 |
| `…/wear/ble/BondHealth.kt` | Connection-attempt verdicts: pairing failed, lost bond (pure) | 7 |
| `…/wear/ble/MtuCheck.kt` | MTU sources and the MTU < 247 verdict (pure) | 7 |
| `…/wear/ble/BeltGatt.kt` | One `BluetoothGatt` connection | 8 |
| `…/wear/ble/BeltScanner.kt` | Filtered scan with the scan limiter | 8 |
| `…/wear/ble/BeltLink.kt` | Acquisition, pairing through the `info` read, reconnection supervision, halts | 8 |
| `…/wear/demo/DemoSource.kt`, `…/wear/demo/DemoScenario.kt` | Demo packets from `radar-core` sim | 9 |
| `…/wear/session/SessionStore.kt`, `SessionSource.kt`, `SessionCommands.kt` | UI-facing state, source policy, intents | 10 |
| `…/wear/session/SessionWakeLock.kt`, `SessionNotification.kt` | Wake lock, Ongoing Activity | 10 |
| `…/wear/session/InputAdapters.kt`, `RunningSession.kt`, `BlindsideSessionService.kt` | Wiring and FGS lifecycle | 10 |
| `…/wear/ui/radar/RadarGeometry.kt`, `RadarLabels.kt`, `…/wear/ui/ScreenPolicy.kt` | Scene → draw model, labels, screen policy (pure) | 11 |
| `…/wear/ui/Palette.kt`, `…/wear/ui/radar/RadarCanvas.kt`, `RadarScreen.kt`, `…/wear/ui/ScreenEffects.kt`, `…/wear/ui/AmbientObserver.kt` | Radar screen | 12 |
| `…/wear/practice/Quiz.kt`, `…/wear/ui/Labels.kt`, `Widgets.kt`, `PracticeScreen.kt` | Vibration quiz, UI copy, shared widgets | 13 |
| `…/wear/ui/Routes.kt`, `HomeScreen.kt`, `SettingsScreen.kt`, `BlindsideApp.kt` (+ more labels in `Labels.kt`) | Home, settings (with the "Diagnóstico (spikes)" entry), navigation | 14 |
| `README.md` | Build, install, pairing, `adb pull`, device checklist | 15 |

## Dependencies on plan 01 (`radar-core`)

Plan 03 starts after plan 01 Task 1 (the Gradle root and an empty `radar-core` module). Each task needs these parts of `radar-core` to compile and pass. Tasks 6, 7 and 8 need nothing from it, so they can run first.

| Task | Needs from `radar-core` (plan 01 task) |
|---|---|
| 1, 6, 7, 8 | Only the module and the version catalog (1) |
| 2 | `.config`: `Handedness`, `RadarMount`, `defaultMounts`, `PipelineConfig`, `RADAR_A`, `RADAR_B` (2) |
| 3, 11, 12, 13 | `.scene` types, `.alerts` events (2) |
| 4 | `.replay`: `RecordType` (all 10 codes), `BsrecRecord`, `BsrecWriter`, `BsrecReader`, `BsrecPayloads` (including `watchGyro`, `rssi`, `info`, `linkChange`), `SessionMode`, `GravitySample`, `LINK_DOWN_MODE` (4b); `.config`: `PipelineConfig.toJson()` for the header, and `pipelineConfigFromValue` in the header test (4d); `.protocol.MiniJson.parse` in the header test (4c) |
| 5, 10 | `RadarPipeline` with the full contract signature, including `onWatchGyro` and `onBeltInfo` (16a); `ContactAlert`s from the alert stage (16c) |
| 9 | `.sim`: `Scenario`, `Stand`, `walker`, `marcher`, `simulate`, `SimPacket`; `.geometry.Point2` (5, 15); a `RadarPipeline` that emits `ContactAlert`s (16a-16c) |

**Plan 01 has no Task 19 any more.** The contract additions it used to hold are folded into plan 01 Tasks 4b, 16a and 17. Plan 03 codes against the contracts: Task 4 does not compile before plan 01 Tasks 4b-4d are merged, and Tasks 5 and 10 not before Task 16a. Do not work around this in wear-app.
- **Plan 01 Task 4b (`.bsrec` payloads):** `RecordType.INFO_REREAD(8)`, `WATCH_GYRO(9)` and `RSSI(10)` in `fromCode`; `BsrecPayloads.watchGyro`/`readWatchGyro`, `rssi`/`readRssi`, `info`/`readInfo`, `linkChange`/`readLinkChange`; the constants `LINK_UP_MODE`/`LINK_DOWN_MODE`; `SessionMode`, `GravitySample`.
- **Plan 01 Task 16a (pipeline inputs):** `RadarPipeline.onWatchGyro(x, y, z, eventNanos)` (rad/s; the watch witness that voids a belt boot-bias window during a turn, spec §6.3) and `RadarPipeline.onBeltInfo(json, nowNanos)` (scales, and a new `boot_id` restarts the time references, spec §6.3).
- **Plan 01 Task 17 (replay):** `replayRecording` feeds record 8 to `onBeltInfo`, record 9 to `onWatchGyro` and the mode changes `LINK_UP`/`LINK_DOWN` to `onLinkState(true/false)`; `pipelineConfigFromHeader` rebuilds the session config from the header's `"config"` (written by Task 4 here with plan 01 Task 4d's `PipelineConfig.toJson()`).

**radar-core symbols used by this plan (re-checked against plan 01 on 2026-10-01, import by import; names, packages and signatures match):** `RadarPipeline(config)` and its eight contract methods (16a); `PipelineConfig(tuning, mounts)`, `RadarMount(radarId, xM, yM, yawDeg, flipX, speedSign)`, `Handedness`, `defaultMounts`, `RADAR_A`, `RADAR_B` (2); `PipelineConfig.toJson()` (4d, `.config`); tests only: `pipelineConfigFromValue(value: Any?)` (4d, `.config`) and `MiniJson.parse(text)` (4c, `.protocol`); `RadarScene`, `Blip`, `CoverageSector`, `SensorStatus`, `Confidence`, `Side`, `MotionState`, `Warning`, `PipelineEvent`, `ContactAlert`, `TrackConfirmed`, `SystemAlert` (2); `RecordType`, `BsrecRecord`, `BsrecWriter(out, headerJson)`, `BsrecReader(input)`, `BsrecPayloads.{gravity, readGravity, watchGyro, readWatchGyro, step, readStep, trackConfirmed, readTrackConfirmed, vibrationStarted(displayId, side), modeChange(SessionMode), readModeChange, linkChange(Boolean), readLinkChange, info, readInfo, rssi(Int), readRssi}`, `SessionMode`, `GravitySample`, `LINK_DOWN_MODE` (4b); `Scenario(name, player, targets, …)`, `Stand(durationMs)`, `walker(fromMs, toMs, start, velocityMps)`, `marcher(fromMs, toMs, center, …)`, `simulate`, `SimPacket(bytes, arrivalNanos)` (15); `Point2(x, y)` (5). If plan 01 renames any of them, fix this plan, not radar-core.

**Recording conventions agreed with plan 01 (its "Notes for plans 02 and 03", Tasks 4b, 4d and 17):**
- Payload integers and floats are little-endian.
- Every payload is built with plan 01's `BsrecPayloads`, so live recordings and `replayRecording` agree.
- The vibration side byte is `Side.ordinal`.
- The mode-change name is a `SessionMode`: `ELIMINATED` while eliminated, otherwise `STEALTH` (Sigilo) or `VIEW` (Vista).
- Link changes are mode changes named `LINK_UP`/`LINK_DOWN` (`BsrecPayloads.linkChange`). The contracts' `.bsrec` section lists them, and the replay feeds them to `onLinkState`, exactly as the app does live.
- The header's `"config"` is `toPipelineConfig(settings).toJson()`, the same config the session's `RadarPipeline` runs with, so `pipelineConfigFromHeader` reproduces it.

## Delivery order (spec §12 calendar)

- **Spike build, 2-3 oct: Tasks 1-10 and 10b.** Spec §12 runs spikes S1, S3, S10 and S12 on 2-3 oct with "una app mínima que ya usa el grabador real" (records 1, 5, 6 and 10) and the real NimBLE link, and S3 fixes the vibration usage before 5-7 oct. Task 10b gives that build a start/stop screen for the spikes. It needs plan 01 Tasks 1, 2, 4b, 4c, 4d, 5, 15 and 16a-16c.
- **Full MVP app, 5-7 oct: Tasks 11-15.** Radar screen, practice quiz, home and settings, README and the on-device checklist. Task 14 makes the home screen the start screen and keeps the spike screen reachable from Ajustes → "Diagnóstico (spikes)", so Santiago can still run S1, S3, S10 and S12 with the real app on 2-3 oct (and repeat them later) whichever build is installed.

---

### Task 1: Module scaffold, manifest and the session-start permission decision

Spec §5.1, §5.2 (permissions), contracts (toolchain, identifiers).

**Files:**
- Modify (append only, owned by plan 01): `watch/gradle/libs.versions.toml`
- Create: `watch/wear-app/build.gradle.kts`
- Create: `watch/wear-app/src/main/AndroidManifest.xml`
- Create: `watch/wear-app/src/main/res/drawable/ic_radar.xml`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/MainActivity.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/permissions/SessionPermissions.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/permissions/SessionPermissionsTest.kt`

**Interfaces:**
- Consumes: plan 01 Task 1 (`watch/` Gradle root with the `:radar-core` module).
- Produces:
  - `const val PERMISSION_BLUETOOTH_SCAN`, `PERMISSION_BLUETOOTH_CONNECT`, `PERMISSION_ACTIVITY_RECOGNITION`, `PERMISSION_POST_NOTIFICATIONS: String`
  - `val SESSION_PERMISSIONS: Array<String>`
  - `sealed interface StartDecision { data class Start(val watchSteps: Boolean); data object BlockedBluetoothDenied }`
  - `fun startDecision(grants: Map<String, Boolean>): StartDecision`
  - `fun bluetoothGranted(grants: Map<String, Boolean>): Boolean`
  - The catalog aliases appended in Step 1, used by every later task.

- [ ] **Step 1: Append the wear-app entries to the version catalog**

Plan 01 Task 1 already created the watch Gradle root that wear-app needs:
- `settings.gradle.kts` includes `:wear-app` as soon as `watch/wear-app/build.gradle.kts` exists, and both repository blocks list `google()`.
- `build.gradle.kts` declares `android-application`, `kotlin-android`, `kotlin-compose` and `kotlin-jvm` with `apply false`.
- `gradle.properties` sets `android.useAndroidX=true`.
- `libs.versions.toml` already has the versions `agp`, `kotlin`, `composeBom`, `activityCompose`, `wearCompose`, `coroutines`, `datastore` and `junit5`.
- It also has the aliases `compose-bom`, `compose-ui`, `activity-compose`, `wear-compose-material`, `wear-compose-foundation`, `coroutines-android`, `datastore-preferences`, `junit5-bom`, `junit5-jupiter` and `junit5-launcher`.

Read the four files and confirm those lines are there. If one is missing, or a pinned version differs from the contracts, stop and flag it: plan 01 owns these files.

Then append the entries wear-app adds. Per plan 01's notes, plan 03 may append entries but must not change existing versions. Put the version lines at the end of `[versions]` and the library lines at the end of `[libraries]`:

```toml
# append to [versions]
androidxWear = "1.3.0"
wearOngoing = "1.0.0"
androidxCore = "1.13.1"
lifecycle = "2.8.7"

# append to [libraries]
compose-foundation = { group = "androidx.compose.foundation", name = "foundation" }
wear-compose-navigation = { group = "androidx.wear.compose", name = "compose-navigation", version.ref = "wearCompose" }
androidx-wear = { group = "androidx.wear", name = "wear", version.ref = "androidxWear" }
androidx-wear-ongoing = { group = "androidx.wear", name = "wear-ongoing", version.ref = "wearOngoing" }
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "androidxCore" }
lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
```

`watch/local.properties` is gitignored. Create it if it is missing, with `sdk.dir=C:/Users/santi/AppData/Local/Android/Sdk`. CI uses `ANDROID_HOME` instead.

- [ ] **Step 2: Create the module build file**

`watch/wear-app/build.gradle.kts`:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.santiquiroz.blindside.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.santiquiroz.blindside"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":radar-core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(libs.androidx.wear)
    implementation(libs.androidx.wear.ongoing)
    implementation(libs.androidx.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.datastore.preferences)

    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.launcher)
}
```

- [ ] **Step 3: Create the manifest, icon and a first activity**

`watch/wear-app/src/main/AndroidManifest.xml`. `POST_NOTIFICATIONS` is added beyond the spec list: the Ongoing Activity is a notification, and on API 33+ it is hidden without that permission. It is requested but never blocks a session.

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature android:name="android.hardware.type.watch" />
    <uses-feature
        android:name="android.hardware.bluetooth_le"
        android:required="true" />

    <uses-permission
        android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
    <uses-permission android:name="android.permission.HIGH_SAMPLING_RATE_SENSORS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_HEALTH" />
    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:allowBackup="false"
        android:icon="@drawable/ic_radar"
        android:label="Blindside"
        android:theme="@android:style/Theme.DeviceDefault">

        <meta-data
            android:name="com.google.android.wearable.standalone"
            android:value="true" />

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`watch/wear-app/src/main/res/drawable/ic_radar.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#00000000"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.5"
        android:pathData="M12,3A9,9 0 1,1 11.99,3Z" />
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M12,12L12,3A9,9 0 0,1 20.5,9Z" />
</vector>
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/MainActivity.kt` (Task 10b, then Task 14, replace the body):

```kotlin
package io.github.santiquiroz.blindside.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Text("Blindside") } }
    }
}
```

- [ ] **Step 4: Write the failing test**

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/permissions/SessionPermissionsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.permissions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SessionPermissionsTest {
    private val allGranted = mapOf(
        PERMISSION_BLUETOOTH_SCAN to true,
        PERMISSION_BLUETOOTH_CONNECT to true,
        PERMISSION_ACTIVITY_RECOGNITION to true,
        PERMISSION_POST_NOTIFICATIONS to true,
    )

    @Test
    fun `starts with watch steps when everything is granted`() {
        assertEquals(StartDecision.Start(watchSteps = true), startDecision(allGranted))
    }

    @Test
    fun `starts without watch steps when activity recognition is denied`() {
        val grants = allGranted + (PERMISSION_ACTIVITY_RECOGNITION to false)
        assertEquals(StartDecision.Start(watchSteps = false), startDecision(grants))
    }

    @Test
    fun `starts even when notifications are denied`() {
        val grants = allGranted + (PERMISSION_POST_NOTIFICATIONS to false)
        assertEquals(StartDecision.Start(watchSteps = true), startDecision(grants))
    }

    @Test
    fun `blocks when bluetooth connect is denied`() {
        val grants = allGranted + (PERMISSION_BLUETOOTH_CONNECT to false)
        assertEquals(StartDecision.BlockedBluetoothDenied, startDecision(grants))
    }

    @Test
    fun `blocks when bluetooth scan is missing from the result`() {
        assertEquals(StartDecision.BlockedBluetoothDenied, startDecision(allGranted - PERMISSION_BLUETOOTH_SCAN))
    }

    @Test
    fun `requests every permission the session can use`() {
        val expected = setOf(
            PERMISSION_BLUETOOTH_SCAN,
            PERMISSION_BLUETOOTH_CONNECT,
            PERMISSION_ACTIVITY_RECOGNITION,
            PERMISSION_POST_NOTIFICATIONS,
        )
        assertEquals(expected, SESSION_PERMISSIONS.toSet())
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `./gradlew :wear-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.wear.permissions.SessionPermissionsTest"`
Expected: FAIL at `compileDebugUnitTestKotlin` with `Unresolved reference 'startDecision'`.

- [ ] **Step 6: Write the minimal implementation**

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/permissions/SessionPermissions.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.permissions

const val PERMISSION_BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
const val PERMISSION_BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
const val PERMISSION_ACTIVITY_RECOGNITION = "android.permission.ACTIVITY_RECOGNITION"
const val PERMISSION_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

val SESSION_PERMISSIONS: Array<String> = arrayOf(
    PERMISSION_BLUETOOTH_SCAN,
    PERMISSION_BLUETOOTH_CONNECT,
    PERMISSION_ACTIVITY_RECOGNITION,
    PERMISSION_POST_NOTIFICATIONS,
)

sealed interface StartDecision {
    data class Start(val watchSteps: Boolean) : StartDecision
    data object BlockedBluetoothDenied : StartDecision
}

fun startDecision(grants: Map<String, Boolean>): StartDecision =
    if (bluetoothGranted(grants)) {
        StartDecision.Start(watchSteps = grants[PERMISSION_ACTIVITY_RECOGNITION] == true)
    } else {
        StartDecision.BlockedBluetoothDenied
    }

fun bluetoothGranted(grants: Map<String, Boolean>): Boolean =
    grants[PERMISSION_BLUETOOTH_SCAN] == true && grants[PERMISSION_BLUETOOTH_CONNECT] == true
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew :wear-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.wear.permissions.SessionPermissionsTest"`
Expected: PASS (6 tests).

- [ ] **Step 8: Build the APK and check the merged manifest**

Run: `./gradlew :wear-app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, and `wear-app/build/outputs/apk/debug/wear-app-debug.apk` exists.

Run: `grep -h "uses-permission" $(find wear-app/build/intermediates -path "*merged_manifest*" -name AndroidManifest.xml | head -1)`
Expected: the 10 declared permissions, including `BLUETOOTH_SCAN` with `usesPermissionFlags="neverForLocation"`. There is also one extra line, `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which androidx.core adds.

- [ ] **Step 9: Commit**

```bash
git add watch/gradle/libs.versions.toml watch/wear-app
git commit -m "feat: andamiaje del módulo wear-app con manifiesto y decisión de permisos de sesión"
```

---

### Task 2: Settings model, mapping to `PipelineConfig`, DataStore

Spec §5.6 (basic settings: handedness, angles, signs, screen mode), §10.1 (eliminated), §2.3 (handedness profiles), §5.5 (practice is mandatory before Sigilo).

**Files:**
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/settings/AppSettings.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/settings/PipelineConfigMapping.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/settings/SettingsPreferences.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/settings/SettingsRepository.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/settings/AppSettingsTest.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/settings/PipelineConfigMappingTest.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/settings/SettingsPreferencesTest.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/settings/SettingsRepositoryTest.kt`

**Interfaces:**
- Consumes: `io.github.santiquiroz.blindside.core.config.{Handedness, RadarMount, defaultMounts, PipelineConfig, RADAR_A, RADAR_B}`.
- Produces:
  - `enum class ScreenMode { SIGILO, VISTA }`, `enum class VibrationUsage { ALARM, NOTIFICATION }`
  - `data class RadarSettings(radarId: Int, yawDegOverride: Double? = null, flipX: Boolean = false, speedSign: Int = 1)`
  - `data class AppSettings(handedness, radars: List<RadarSettings>, screenMode, vibrationUsage, eliminated: Boolean, beltAddress: String?, quizPassedAtEpochMs: Long?)`
  - `typealias SettingsTransform = (AppSettings) -> AppSettings`
  - `const val YAW_STEP_DEG = 5.0`, `YAW_LIMIT_DEG = 90.0`, `val DEFAULT_RADARS` (radar ids are `RADAR_A`/`RADAR_B` from `radar-core` `.config`)
  - Extensions: `AppSettings.radar(id)`, `.withRadar(r)`, `.withHandedness(h)`, `.forNewSession()`, `.withFlipXToggled(id)`, `.withSpeedSignFlipped(id)`, `.withYawNudged(id, deltaDeg)`
  - `nextHandedness(h)`, `stepYaw(current, delta)`, `toggledScreenMode(m)`, `toggledUsage(u)`
  - `toPipelineConfig(settings): PipelineConfig`, `mountsFor(settings): List<RadarMount>`, `applyRadarSettings(mount, radar): RadarMount`, `effectiveYawDeg(settings, id): Double`
  - `settingsFrom(prefs: Preferences): AppSettings`, `writeSettings(prefs: MutablePreferences, settings: AppSettings)`, `internal object Keys`
  - `class SettingsRepository(store: DataStore<Preferences>) { val settings: Flow<AppSettings>; suspend fun current(): AppSettings; suspend fun update(transform: SettingsTransform) }`
  - `fun Context.settingsRepository(): SettingsRepository`

- [ ] **Step 1: Write the failing tests**

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/settings/AppSettingsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.settings

import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppSettingsTest {
    @Test
    fun `a new session never starts eliminated`() {
        val leftOver = AppSettings(eliminated = true, screenMode = ScreenMode.VISTA, beltAddress = "AA:BB:CC:DD:EE:FF")
        val fresh = leftOver.forNewSession()
        assertFalse(fresh.eliminated)
        assertEquals(leftOver.copy(eliminated = false), fresh)
    }

    @Test
    fun `changing handedness clears yaw overrides`() {
        val tuned = AppSettings().withRadar(RadarSettings(RADAR_B, yawDegOverride = 33.0, flipX = true))
        val changed = tuned.withHandedness(Handedness.LEFT)
        assertEquals(Handedness.LEFT, changed.handedness)
        assertNull(changed.radar(RADAR_B).yawDegOverride)
        assertTrue(changed.radar(RADAR_B).flipX)
    }

    @Test
    fun `yaw steps are clamped to plus minus ninety degrees`() {
        assertEquals(90.0, stepYaw(88.0, 5.0), 1e-9)
        assertEquals(-90.0, stepYaw(-88.0, -5.0), 1e-9)
        assertEquals(-35.0, stepYaw(-40.0, 5.0), 1e-9)
    }

    @Test
    fun `handedness cycles through every profile`() {
        assertEquals(Handedness.LEFT, nextHandedness(Handedness.RIGHT))
        assertEquals(Handedness.SWITCHER, nextHandedness(Handedness.LEFT))
        assertEquals(Handedness.RIGHT, nextHandedness(Handedness.SWITCHER))
    }

    @Test
    fun `sign toggles touch only the requested radar`() {
        val toggled = AppSettings().withFlipXToggled(RADAR_A).withSpeedSignFlipped(RADAR_A)
        assertTrue(toggled.radar(RADAR_A).flipX)
        assertEquals(-1, toggled.radar(RADAR_A).speedSign)
        assertEquals(RadarSettings(RADAR_B), toggled.radar(RADAR_B))
    }

    @Test
    fun `screen mode and vibration usage toggle between their two values`() {
        assertEquals(ScreenMode.VISTA, toggledScreenMode(ScreenMode.SIGILO))
        assertEquals(ScreenMode.SIGILO, toggledScreenMode(ScreenMode.VISTA))
        assertEquals(VibrationUsage.NOTIFICATION, toggledUsage(VibrationUsage.ALARM))
        assertEquals(VibrationUsage.ALARM, toggledUsage(VibrationUsage.NOTIFICATION))
    }
}
```

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/settings/PipelineConfigMappingTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.settings

import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import io.github.santiquiroz.blindside.core.config.RadarMount
import io.github.santiquiroz.blindside.core.config.defaultMounts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PipelineConfigMappingTest {
    private fun mount(config: PipelineConfig, radarId: Int): RadarMount = config.mounts.single { it.radarId == radarId }

    @Test
    fun `default settings use the right-handed nominal yaws`() {
        val config = toPipelineConfig(AppSettings())
        assertEquals(-40.0, mount(config, RADAR_A).yawDeg, 1e-9)
        assertEquals(20.0, mount(config, RADAR_B).yawDeg, 1e-9)
    }

    @Test
    fun `a yaw override replaces only that radar's nominal yaw`() {
        val settings = AppSettings(handedness = Handedness.LEFT)
            .withRadar(RadarSettings(RADAR_B, yawDegOverride = 35.0))
        val config = toPipelineConfig(settings)
        assertEquals(-20.0, mount(config, RADAR_A).yawDeg, 1e-9)
        assertEquals(35.0, mount(config, RADAR_B).yawDeg, 1e-9)
    }

    @Test
    fun `sign settings reach the pipeline mounts`() {
        val settings = AppSettings().withFlipXToggled(RADAR_A).withSpeedSignFlipped(RADAR_B)
        val config = toPipelineConfig(settings)
        assertTrue(mount(config, RADAR_A).flipX)
        assertEquals(1, mount(config, RADAR_A).speedSign)
        assertFalse(mount(config, RADAR_B).flipX)
        assertEquals(-1, mount(config, RADAR_B).speedSign)
    }

    @Test
    fun `mount positions stay at the radar-core defaults`() {
        val expected = defaultMounts(Handedness.RIGHT).sortedBy { it.radarId }.map { it.xM to it.yM }
        val actual = toPipelineConfig(AppSettings()).mounts.sortedBy { it.radarId }.map { it.xM to it.yM }
        assertEquals(expected, actual)
    }

    @Test
    fun `nudging the yaw starts from the effective value`() {
        val nudged = AppSettings().withYawNudged(RADAR_B, YAW_STEP_DEG)
        assertEquals(25.0, effectiveYawDeg(nudged, RADAR_B), 1e-9)
        assertEquals(-40.0, effectiveYawDeg(nudged, RADAR_A), 1e-9)
    }
}
```

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/settings/SettingsPreferencesTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SettingsPreferencesTest {
    @Test
    fun `empty preferences give the defaults`() {
        assertEquals(AppSettings(), settingsFrom(emptyPreferences()))
    }

    @Test
    fun `every field survives a write and read`() {
        val original = AppSettings(
            handedness = Handedness.SWITCHER,
            radars = listOf(
                RadarSettings(RADAR_A, yawDegOverride = -35.0, flipX = true, speedSign = -1),
                RadarSettings(RADAR_B, yawDegOverride = null, flipX = false, speedSign = 1),
            ),
            screenMode = ScreenMode.VISTA,
            vibrationUsage = VibrationUsage.NOTIFICATION,
            eliminated = true,
            beltAddress = "AA:BB:CC:DD:EE:FF",
            quizPassedAtEpochMs = 1_760_000_000_000L,
        )
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, original)
        assertEquals(original, settingsFrom(prefs))
    }

    @Test
    fun `unknown enum names fall back to defaults instead of crashing`() {
        val prefs = preferencesOf(
            Keys.HANDEDNESS to "AMBIDEXTROUS",
            Keys.SCREEN_MODE to "NIGHT",
            Keys.VIBRATION_USAGE to "",
        )
        val settings = settingsFrom(prefs)
        assertEquals(Handedness.RIGHT, settings.handedness)
        assertEquals(ScreenMode.SIGILO, settings.screenMode)
        assertEquals(VibrationUsage.ALARM, settings.vibrationUsage)
    }

    @Test
    fun `a speed sign other than minus one reads as plus one`() {
        val prefs = preferencesOf(Keys.speedSign(RADAR_A) to 7)
        assertEquals(1, settingsFrom(prefs).radar(RADAR_A).speedSign)
    }

    @Test
    fun `clearing optional values removes their keys`() {
        val prefs = mutablePreferencesOf()
        writeSettings(prefs, AppSettings(beltAddress = "AA:BB:CC:DD:EE:FF").withRadar(RadarSettings(RADAR_B, 30.0)))
        writeSettings(prefs, AppSettings())
        assertNull(prefs[Keys.BELT_ADDRESS])
        assertNull(prefs[Keys.yaw(RADAR_B)])
    }
}
```

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/settings/SettingsRepositoryTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SettingsRepositoryTest {
    @Test
    fun `updates are visible to the next read`(@TempDir dir: File) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") }
        val repository = SettingsRepository(store)
        val read = runBlocking {
            repository.update { it.copy(screenMode = ScreenMode.VISTA, eliminated = true) }
            repository.current()
        }
        scope.cancel()
        assertEquals(ScreenMode.VISTA, read.screenMode)
        assertTrue(read.eliminated)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :wear-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.wear.settings.*"`
Expected: FAIL at compilation with `Unresolved reference 'AppSettings'`.

- [ ] **Step 3: Write the implementation**

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/settings/AppSettings.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.settings

import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B

enum class ScreenMode { SIGILO, VISTA }

enum class VibrationUsage { ALARM, NOTIFICATION }

data class RadarSettings(
    val radarId: Int,
    val yawDegOverride: Double? = null,
    val flipX: Boolean = false,
    val speedSign: Int = 1,
)

data class AppSettings(
    val handedness: Handedness = Handedness.RIGHT,
    val radars: List<RadarSettings> = DEFAULT_RADARS,
    val screenMode: ScreenMode = ScreenMode.SIGILO,
    val vibrationUsage: VibrationUsage = VibrationUsage.ALARM,
    val eliminated: Boolean = false,
    val beltAddress: String? = null,
    val quizPassedAtEpochMs: Long? = null,
)

typealias SettingsTransform = (AppSettings) -> AppSettings

const val YAW_STEP_DEG = 5.0
const val YAW_LIMIT_DEG = 90.0

val DEFAULT_RADARS: List<RadarSettings> = listOf(RadarSettings(RADAR_A), RadarSettings(RADAR_B))

fun AppSettings.radar(radarId: Int): RadarSettings =
    radars.firstOrNull { it.radarId == radarId } ?: RadarSettings(radarId)

fun AppSettings.withRadar(updated: RadarSettings): AppSettings =
    copy(radars = radars.map { if (it.radarId == updated.radarId) updated else it })

fun AppSettings.withHandedness(newHandedness: Handedness): AppSettings =
    copy(handedness = newHandedness, radars = radars.map { it.copy(yawDegOverride = null) })

fun AppSettings.forNewSession(): AppSettings = copy(eliminated = false)

fun AppSettings.withFlipXToggled(radarId: Int): AppSettings =
    withRadar(radar(radarId).let { it.copy(flipX = !it.flipX) })

fun AppSettings.withSpeedSignFlipped(radarId: Int): AppSettings =
    withRadar(radar(radarId).let { it.copy(speedSign = -it.speedSign) })

fun nextHandedness(current: Handedness): Handedness =
    Handedness.entries[(current.ordinal + 1) % Handedness.entries.size]

fun stepYaw(currentDeg: Double, deltaDeg: Double): Double =
    (currentDeg + deltaDeg).coerceIn(-YAW_LIMIT_DEG, YAW_LIMIT_DEG)

fun toggledScreenMode(mode: ScreenMode): ScreenMode =
    if (mode == ScreenMode.SIGILO) ScreenMode.VISTA else ScreenMode.SIGILO

fun toggledUsage(usage: VibrationUsage): VibrationUsage =
    if (usage == VibrationUsage.ALARM) VibrationUsage.NOTIFICATION else VibrationUsage.ALARM
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/settings/PipelineConfigMapping.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.settings

import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.config.RadarMount
import io.github.santiquiroz.blindside.core.config.defaultMounts

fun toPipelineConfig(settings: AppSettings): PipelineConfig = PipelineConfig(mounts = mountsFor(settings))

fun mountsFor(settings: AppSettings): List<RadarMount> =
    defaultMounts(settings.handedness).map { applyRadarSettings(it, settings.radar(it.radarId)) }

fun applyRadarSettings(mount: RadarMount, radar: RadarSettings): RadarMount =
    mount.copy(yawDeg = radar.yawDegOverride ?: mount.yawDeg, flipX = radar.flipX, speedSign = radar.speedSign)

fun effectiveYawDeg(settings: AppSettings, radarId: Int): Double =
    mountsFor(settings).firstOrNull { it.radarId == radarId }?.yawDeg ?: 0.0

fun AppSettings.withYawNudged(radarId: Int, deltaDeg: Double): AppSettings =
    withRadar(radar(radarId).copy(yawDegOverride = stepYaw(effectiveYawDeg(this, radarId), deltaDeg)))
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/settings/SettingsPreferences.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

internal object Keys {
    val HANDEDNESS = stringPreferencesKey("handedness")
    val SCREEN_MODE = stringPreferencesKey("screen_mode")
    val VIBRATION_USAGE = stringPreferencesKey("vibration_usage")
    val ELIMINATED = booleanPreferencesKey("eliminated")
    val BELT_ADDRESS = stringPreferencesKey("belt_address")
    val QUIZ_PASSED_AT = longPreferencesKey("quiz_passed_at_epoch_ms")

    fun yaw(radarId: Int) = doublePreferencesKey("radar${radarId}_yaw_deg")
    fun flipX(radarId: Int) = booleanPreferencesKey("radar${radarId}_flip_x")
    fun speedSign(radarId: Int) = intPreferencesKey("radar${radarId}_speed_sign")
}

fun settingsFrom(prefs: Preferences): AppSettings {
    val defaults = AppSettings()
    return AppSettings(
        handedness = enumOrDefault(prefs[Keys.HANDEDNESS], defaults.handedness),
        radars = DEFAULT_RADARS.map { radarFrom(prefs, it.radarId) },
        screenMode = enumOrDefault(prefs[Keys.SCREEN_MODE], defaults.screenMode),
        vibrationUsage = enumOrDefault(prefs[Keys.VIBRATION_USAGE], defaults.vibrationUsage),
        eliminated = prefs[Keys.ELIMINATED] ?: defaults.eliminated,
        beltAddress = prefs[Keys.BELT_ADDRESS],
        quizPassedAtEpochMs = prefs[Keys.QUIZ_PASSED_AT],
    )
}

fun writeSettings(prefs: MutablePreferences, settings: AppSettings) {
    prefs[Keys.HANDEDNESS] = settings.handedness.name
    prefs[Keys.SCREEN_MODE] = settings.screenMode.name
    prefs[Keys.VIBRATION_USAGE] = settings.vibrationUsage.name
    prefs[Keys.ELIMINATED] = settings.eliminated
    writeOptional(prefs, Keys.BELT_ADDRESS, settings.beltAddress)
    writeOptional(prefs, Keys.QUIZ_PASSED_AT, settings.quizPassedAtEpochMs)
    settings.radars.forEach { writeRadar(prefs, it) }
}

fun parseSpeedSign(stored: Int?): Int = if (stored == -1) -1 else 1

inline fun <reified E : Enum<E>> enumOrDefault(stored: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == stored } ?: default

private fun radarFrom(prefs: Preferences, radarId: Int) = RadarSettings(
    radarId = radarId,
    yawDegOverride = prefs[Keys.yaw(radarId)],
    flipX = prefs[Keys.flipX(radarId)] ?: false,
    speedSign = parseSpeedSign(prefs[Keys.speedSign(radarId)]),
)

private fun writeRadar(prefs: MutablePreferences, radar: RadarSettings) {
    writeOptional(prefs, Keys.yaw(radar.radarId), radar.yawDegOverride)
    prefs[Keys.flipX(radar.radarId)] = radar.flipX
    prefs[Keys.speedSign(radar.radarId)] = radar.speedSign
}

private fun <T> writeOptional(prefs: MutablePreferences, key: Preferences.Key<T>, value: T?) {
    if (value == null) prefs.remove(key) else prefs[key] = value
}
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/settings/SettingsRepository.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "blindside_settings")

fun Context.settingsRepository(): SettingsRepository = SettingsRepository(applicationContext.settingsDataStore)

class SettingsRepository(private val store: DataStore<Preferences>) {
    val settings: Flow<AppSettings> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map(::settingsFrom)

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: SettingsTransform) {
        store.edit { prefs -> writeSettings(prefs, transform(settingsFrom(prefs))) }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :wear-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.wear.settings.*"`
Expected: PASS (17 tests).

- [ ] **Step 5: Commit**

```bash
git add watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/settings watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/settings
git commit -m "feat: ajustes del reloj en DataStore y traducción a PipelineConfig"
```

---

### Task 3: Haptic vocabulary, capability fallback and do-not-disturb check

Spec §5.5 (vocabulary, system buzz, practice warning), §5.2 `haptics` (explicit usage, capability query), §8 (motor without primitives).

**Files:**
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/haptics/HapticPattern.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/haptics/HapticRendering.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/haptics/HapticPlayer.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/haptics/HapticPatternTest.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/haptics/HapticRenderingTest.kt`

**Interfaces:**
- Consumes: `.scene.Side`, `.alerts.{PipelineEvent, ContactAlert, SystemAlert}`, `.scene.Warning`; `VibrationUsage` (Task 2).
- Produces:
  - `data class HapticPattern(val timingsMs: List<Long>)`, with timings alternating off, on, off, on…
  - `val CENTER_PATTERN`, `LEFT_PATTERN`, `RIGHT_PATTERN`, `SYSTEM_PATTERN`
  - `fun patternFor(side: Side): HapticPattern`, `fun hapticFor(event: PipelineEvent): HapticPattern?`
  - `fun pulsesMs(pattern): List<Long>`, `fun amplitudesFor(pattern): List<Int>`
  - `enum class HapticRenderer { AMPLITUDE_WAVEFORM, ON_OFF_WAVEFORM }`, `fun chooseRenderer(hasAmplitudeControl: Boolean): HapticRenderer`
  - `enum class InterruptionFilter { ALL, PRIORITY, ALARMS, NONE, UNKNOWN }`, `fun interruptionFilterFrom(code: Int)`, `fun dndMaySilence(filter, usage): Boolean`
  - `fun interface HapticSink { fun play(pattern: HapticPattern) }`
  - `class HapticPlayer(vibrator: Vibrator, usage: VibrationUsage) : HapticSink { fun hasAmplitudeControl(): Boolean; fun supportsPrimitives(): Boolean; companion fun create(context, usage) }`

The rhythm table follows the research notes (wear_os_ble §5, ideas_creativas_ux §3): pulses ≥ 80-100 ms with gaps ≥ 100-150 ms, coded by count and rhythm, never by intensity.

| Name | On/off timings (ms) |
|---|---|
| Centre | `0, 350` |
| Left | `0, 100, 150, 100` |
| Right | `0, 100, 150, 350` |
| System | `0, 1200` |

The pattern is always rendered as a waveform: with amplitude 255 when the motor has amplitude control, otherwise as plain on/off timings. Primitives are not used: the vocabulary is defined by durations, and a `CLICK` primitive lasts ~10-20 ms. The motor's support for primitives is still queried and written to the recording header, as data for spike S3.

- [ ] **Step 1: Write the failing tests**

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/haptics/HapticPatternTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.haptics

import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.alerts.SystemAlert
import io.github.santiquiroz.blindside.core.alerts.TrackConfirmed
import io.github.santiquiroz.blindside.core.scene.Side
import io.github.santiquiroz.blindside.core.scene.Warning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HapticPatternTest {
    @Test
    fun `the first pulse separates the centre from the sides`() {
        assertEquals(LONG_PULSE_MS, pulsesMs(CENTER_PATTERN).first())
        assertEquals(SHORT_PULSE_MS, pulsesMs(LEFT_PATTERN).first())
        assertEquals(SHORT_PULSE_MS, pulsesMs(RIGHT_PATTERN).first())
    }

    @Test
    fun `left and right differ only in their second pulse`() {
        assertEquals(listOf(SHORT_PULSE_MS, SHORT_PULSE_MS), pulsesMs(LEFT_PATTERN))
        assertEquals(listOf(SHORT_PULSE_MS, LONG_PULSE_MS), pulsesMs(RIGHT_PATTERN))
    }

    @Test
    fun `the system buzz is clearly longer than the centre pulse`() {
        assertTrue(pulsesMs(SYSTEM_PATTERN).single() >= 2 * pulsesMs(CENTER_PATTERN).single())
    }

    @Test
    fun `gaps are long enough to feel while moving`() {
        assertTrue(PULSE_GAP_MS >= 100L)
        assertTrue(SHORT_PULSE_MS >= 80L)
    }

    @Test
    fun `each side has its own rhythm`() {
        assertEquals(LEFT_PATTERN, patternFor(Side.LEFT))
        assertEquals(CENTER_PATTERN, patternFor(Side.CENTER))
        assertEquals(RIGHT_PATTERN, patternFor(Side.RIGHT))
        assertNotEquals(patternFor(Side.LEFT), patternFor(Side.RIGHT))
    }

    @Test
    fun `contact alerts vibrate their side rhythm`() {
        assertEquals(RIGHT_PATTERN, hapticFor(ContactAlert(displayId = 3, side = Side.RIGHT, tNanos = 0L)))
    }

    @Test
    fun `every system alert buzzes`() {
        Warning.entries.forEach { kind ->
            assertEquals(SYSTEM_PATTERN, hapticFor(SystemAlert(kind = kind, tNanos = 0L)))
        }
    }

    @Test
    fun `track confirmations do not vibrate`() {
        assertNull(hapticFor(TrackConfirmed(displayId = 3, tNanos = 0L)))
    }

    @Test
    fun `amplitudes are zero on gaps and full on pulses`() {
        assertEquals(listOf(0, FULL_AMPLITUDE, 0, FULL_AMPLITUDE), amplitudesFor(LEFT_PATTERN))
    }
}
```

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/haptics/HapticRenderingTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.haptics

import io.github.santiquiroz.blindside.wear.settings.VibrationUsage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HapticRenderingTest {
    @Test
    fun `motors without amplitude control fall back to on-off waveforms`() {
        assertEquals(HapticRenderer.ON_OFF_WAVEFORM, chooseRenderer(hasAmplitudeControl = false))
        assertEquals(HapticRenderer.AMPLITUDE_WAVEFORM, chooseRenderer(hasAmplitudeControl = true))
    }

    @Test
    fun `system interruption filter codes map to filters`() {
        assertEquals(InterruptionFilter.ALL, interruptionFilterFrom(1))
        assertEquals(InterruptionFilter.PRIORITY, interruptionFilterFrom(2))
        assertEquals(InterruptionFilter.NONE, interruptionFilterFrom(3))
        assertEquals(InterruptionFilter.ALARMS, interruptionFilterFrom(4))
        assertEquals(InterruptionFilter.UNKNOWN, interruptionFilterFrom(99))
    }

    @Test
    fun `nothing silences vibrations when do not disturb is off`() {
        assertFalse(dndMaySilence(InterruptionFilter.ALL, VibrationUsage.ALARM))
        assertFalse(dndMaySilence(InterruptionFilter.ALL, VibrationUsage.NOTIFICATION))
    }

    @Test
    fun `alarm usage passes priority and alarms-only modes`() {
        assertFalse(dndMaySilence(InterruptionFilter.PRIORITY, VibrationUsage.ALARM))
        assertFalse(dndMaySilence(InterruptionFilter.ALARMS, VibrationUsage.ALARM))
    }

    @Test
    fun `notification usage is at risk in any do not disturb mode`() {
        assertTrue(dndMaySilence(InterruptionFilter.PRIORITY, VibrationUsage.NOTIFICATION))
        assertTrue(dndMaySilence(InterruptionFilter.ALARMS, VibrationUsage.NOTIFICATION))
    }

    @Test
    fun `total silence and unknown modes always warn`() {
        assertTrue(dndMaySilence(InterruptionFilter.NONE, VibrationUsage.ALARM))
        assertTrue(dndMaySilence(InterruptionFilter.UNKNOWN, VibrationUsage.ALARM))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :wear-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.wear.haptics.*"`
Expected: FAIL at compilation with `Unresolved reference 'pulsesMs'`.

- [ ] **Step 3: Write the implementation**

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/haptics/HapticPattern.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.haptics

import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.alerts.PipelineEvent
import io.github.santiquiroz.blindside.core.alerts.SystemAlert
import io.github.santiquiroz.blindside.core.scene.Side

data class HapticPattern(val timingsMs: List<Long>)

const val SHORT_PULSE_MS = 100L
const val LONG_PULSE_MS = 350L
const val PULSE_GAP_MS = 150L
const val SYSTEM_BUZZ_MS = 1_200L
const val FULL_AMPLITUDE = 255

val CENTER_PATTERN = HapticPattern(listOf(0L, LONG_PULSE_MS))
val LEFT_PATTERN = HapticPattern(listOf(0L, SHORT_PULSE_MS, PULSE_GAP_MS, SHORT_PULSE_MS))
val RIGHT_PATTERN = HapticPattern(listOf(0L, SHORT_PULSE_MS, PULSE_GAP_MS, LONG_PULSE_MS))
val SYSTEM_PATTERN = HapticPattern(listOf(0L, SYSTEM_BUZZ_MS))

fun patternFor(side: Side): HapticPattern = when (side) {
    Side.LEFT -> LEFT_PATTERN
    Side.CENTER -> CENTER_PATTERN
    Side.RIGHT -> RIGHT_PATTERN
}

fun hapticFor(event: PipelineEvent): HapticPattern? = when (event) {
    is ContactAlert -> patternFor(event.side)
    is SystemAlert -> SYSTEM_PATTERN
    else -> null
}

fun pulsesMs(pattern: HapticPattern): List<Long> =
    pattern.timingsMs.filterIndexed { index, _ -> isPulseIndex(index) }

fun amplitudesFor(pattern: HapticPattern): List<Int> =
    pattern.timingsMs.indices.map { index -> if (isPulseIndex(index)) FULL_AMPLITUDE else 0 }

private fun isPulseIndex(index: Int): Boolean = index % 2 == 1
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/haptics/HapticRendering.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.haptics

import io.github.santiquiroz.blindside.wear.settings.VibrationUsage

enum class HapticRenderer { AMPLITUDE_WAVEFORM, ON_OFF_WAVEFORM }

enum class InterruptionFilter { ALL, PRIORITY, ALARMS, NONE, UNKNOWN }

fun interface HapticSink {
    fun play(pattern: HapticPattern)
}

fun chooseRenderer(hasAmplitudeControl: Boolean): HapticRenderer =
    if (hasAmplitudeControl) HapticRenderer.AMPLITUDE_WAVEFORM else HapticRenderer.ON_OFF_WAVEFORM

fun interruptionFilterFrom(code: Int): InterruptionFilter = when (code) {
    1 -> InterruptionFilter.ALL
    2 -> InterruptionFilter.PRIORITY
    3 -> InterruptionFilter.NONE
    4 -> InterruptionFilter.ALARMS
    else -> InterruptionFilter.UNKNOWN
}

fun dndMaySilence(filter: InterruptionFilter, usage: VibrationUsage): Boolean = when (filter) {
    InterruptionFilter.ALL -> false
    InterruptionFilter.PRIORITY, InterruptionFilter.ALARMS -> usage == VibrationUsage.NOTIFICATION
    InterruptionFilter.NONE, InterruptionFilter.UNKNOWN -> true
}
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/haptics/HapticPlayer.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.haptics

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.github.santiquiroz.blindside.wear.settings.VibrationUsage

class HapticPlayer(private val vibrator: Vibrator, usage: VibrationUsage) : HapticSink {
    private val renderer = chooseRenderer(vibrator.hasAmplitudeControl())
    private val attributes = VibrationAttributes.createForUsage(androidUsage(usage))

    override fun play(pattern: HapticPattern) {
        vibrator.vibrate(effectFor(pattern), attributes)
    }

    fun hasAmplitudeControl(): Boolean = renderer == HapticRenderer.AMPLITUDE_WAVEFORM

    fun supportsPrimitives(): Boolean = vibrator.areAllPrimitivesSupported(
        VibrationEffect.Composition.PRIMITIVE_CLICK,
        VibrationEffect.Composition.PRIMITIVE_THUD,
    )

    private fun effectFor(pattern: HapticPattern): VibrationEffect = when (renderer) {
        HapticRenderer.AMPLITUDE_WAVEFORM -> VibrationEffect.createWaveform(
            pattern.timingsMs.toLongArray(),
            amplitudesFor(pattern).toIntArray(),
            NO_REPEAT,
        )
        HapticRenderer.ON_OFF_WAVEFORM -> VibrationEffect.createWaveform(pattern.timingsMs.toLongArray(), NO_REPEAT)
    }

    companion object {
        private const val NO_REPEAT = -1

        fun create(context: Context, usage: VibrationUsage): HapticPlayer =
            HapticPlayer(context.getSystemService(VibratorManager::class.java).defaultVibrator, usage)
    }
}

private fun androidUsage(usage: VibrationUsage): Int = when (usage) {
    VibrationUsage.ALARM -> VibrationAttributes.USAGE_ALARM
    VibrationUsage.NOTIFICATION -> VibrationAttributes.USAGE_NOTIFICATION
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :wear-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.wear.haptics.*"`
Expected: PASS (15 tests).

- [ ] **Step 5: Build to compile the Android adapter**

Run: `./gradlew :wear-app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/haptics watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/haptics
git commit -m "feat: vocabulario háptico izq/centro/der/sistema con respaldo sin amplitud"
```

---

### Task 4: Session inputs and `.bsrec` recording

Spec §6.10 (record types, header, size), §1 criterion 1-2 (records 1, 5, 6 make latency measurable), contracts ("`.bsrec` file format", record types 1-10, gravity ≤ 10 Hz).

**Files:**
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/SessionInput.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/recording/RecordEncoding.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/recording/RecordingHeader.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/recording/RecordSink.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/recording/RecordingDirs.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/recording/RecordEncodingTest.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/recording/RecordingHeaderTest.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/recording/RecordSinkTest.kt`

**Interfaces:**
- Consumes:
  - `.replay.{RecordType, BsrecRecord, BsrecWriter, BsrecReader, BsrecPayloads, SessionMode, GravitySample, LINK_DOWN_MODE}` (plan 01 Task 4b), `.config.toJson` (plan 01 Task 4d: `fun PipelineConfig.toJson(): String`), `.scene.Side`, `.alerts.{ContactAlert, TrackConfirmed}`.
  - Tests only: `.protocol.MiniJson` (plan 01 Task 4c: `fun parse(text: String): Any?`) and `.config.pipelineConfigFromValue` (plan 01 Task 4d: `fun pipelineConfigFromValue(value: Any?): PipelineConfig`), the same pair plan 01 Task 17's `pipelineConfigFromHeader` uses to read the header back.
  - `AppSettings`, `ScreenMode`, `toPipelineConfig` (Task 2).
- Produces:
  - `sealed interface SessionInput` with `Packet(bytes, arrivalNanos)`, `Gravity(x, y, z, eventNanos)`, `Gyro(x, y, z, eventNanos)`, `Step(eventNanos)`, `BeltInfo(json, nowNanos)`, `Rssi(dbm, nowNanos)`, `Link(connected, nowNanos)`, `ModeChanged(eliminated, screenMode, nowNanos)`, `Marker(nowNanos)`, `Tick(nowNanos)`, `PlayDeferred(alert: ContactAlert, atNanos: Long)`, `data object Flush`
  - `fun recordFor(input: SessionInput, startNanos: Long): BsrecRecord?`
  - `fun trackConfirmedRecord(event: TrackConfirmed, startNanos: Long): BsrecRecord`
  - `fun vibrationStartedRecord(alert: ContactAlert, startedNanos: Long, startNanos: Long): BsrecRecord`
  - `fun msSinceStart(eventNanos, startNanos): Long`, `fun sessionMode(eliminated: Boolean, screenMode: ScreenMode): SessionMode`
  - `data class SessionClockStamp(epochMs: Long, elapsedNanos: Long)`
  - `data class RecordingMeta(appVersion, source, stamp, device, handedness, screenMode, vibrationUsage, configJson: String, amplitudeControl, primitives, infoJson: String? = null)`
  - `fun recordingMeta(settings, source: String, stamp, device: String, appVersion: String, amplitudeControl: Boolean, primitives: Boolean): RecordingMeta` (`configJson = toPipelineConfig(settings).toJson()`)
  - `fun headerJson(meta: RecordingMeta): String`, `fun jsonString(value: String): String`, `fun infoField(json: String?): String`
  - `fun recordingFileName(epochMs: Long, zone: ZoneId, source: String): String`
  - `interface RecordSink { fun record(record: BsrecRecord); fun flush(); fun close() }`, `class BsrecFileSink(out: OutputStream, headerJson: String)`, `class SafeRecordSink(inner: RecordSink, onFailure: (Exception) -> Unit)`, `object NoOpRecordSink`
  - `class InfoHeaderSink(open: (infoJson: String?) -> RecordSink) : RecordSink`, `const val INFO_WAIT_MS = 10_000L`, `MAX_RECORDS_BEFORE_INFO = 2_000`, `fun infoOf(record: BsrecRecord): String?`
  - `fun openRecordingSink(dir: File, fileName: String, headerJson: String, onFailure: (Exception) -> Unit): RecordSink`, `fun recordingsDir(context: Context): File`

Payload encodings. All are little-endian and every one comes from plan 01's `BsrecPayloads`, so live recordings and `replayRecording` agree:

| Input or event | Record type | Payload |
|---|---|---|
| `Packet` | `BLE_PACKET` | the raw notification bytes |
| `Gravity` | `WATCH_GRAVITY` | `BsrecPayloads.gravity`: `f32 x, f32 y, f32 z, i64 eventNanos` |
| `Gyro` | `WATCH_GYRO` | `BsrecPayloads.watchGyro`: same layout as gravity (rad/s) |
| `Step` | `WATCH_STEP` | `BsrecPayloads.step`: `i64 eventNanos` |
| `BeltInfo` | `INFO_REREAD` | `BsrecPayloads.info`: UTF-8 JSON |
| `Rssi` | `RSSI` | `BsrecPayloads.rssi`: `i16 dBm` |
| `Marker` | `MANUAL_MARKER` | empty |
| `Link` | `MODE_CHANGE` | `BsrecPayloads.linkChange`: `LINK_UP` / `LINK_DOWN` |
| `ModeChanged` | `MODE_CHANGE` | `BsrecPayloads.modeChange(sessionMode(...))`: `ELIMINATED`, `STEALTH` or `VIEW` |
| `TrackConfirmed` event | `TRACK_CONFIRMED` | `BsrecPayloads.trackConfirmed`: `i32 displayId` |
| vibration started | `VIBRATION_STARTED` | `BsrecPayloads.vibrationStarted`: `i32 displayId, u8 Side.ordinal` |
| `Tick`, `Flush`, `PlayDeferred` | not recorded | — |

The header follows spec §6.10:
- `info` is the belt's first `info` read, embedded verbatim as a JSON object. A BELT recording is therefore opened lazily by `InfoHeaderSink`: records wait in memory until the first `INFO_REREAD` record, which arrives within a second of the first connection. Only watch-sensor records can come before it, because the belt sends no packet before its `info` is read.
- If no `info` comes within 10 s of the session start (belt off, out of range), or 2 000 records pile up, the file opens with `"info": null`. The demo always writes `"info": null`.
- `config` is `toPipelineConfig(settings).toJson()` (plan 01 Task 4d, contracts "`.bsrec` file format"): `TuningParams` and the effective mounts, the MVP's whole calibration, in the exact form plan 01's `pipelineConfigFromHeader` (Task 17) reads back for replays and the τ sweep. radar-core owns this serialiser, so it cannot drift from `TuningParams` and needs no reflection.

- [ ] **Step 1: Write the failing tests**

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/recording/RecordEncodingTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.recording

import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.alerts.TrackConfirmed
import io.github.santiquiroz.blindside.core.replay.BsrecPayloads
import io.github.santiquiroz.blindside.core.replay.BsrecRecord
import io.github.santiquiroz.blindside.core.replay.GravitySample
import io.github.santiquiroz.blindside.core.replay.LINK_DOWN_MODE
import io.github.santiquiroz.blindside.core.replay.RecordType
import io.github.santiquiroz.blindside.core.replay.SessionMode
import io.github.santiquiroz.blindside.core.scene.Side
import io.github.santiquiroz.blindside.wear.session.SessionInput
import io.github.santiquiroz.blindside.wear.settings.ScreenMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RecordEncodingTest {
    private val start = 5_000_000_000L

    private fun at(ms: Long): Long = start + ms * 1_000_000L

    private fun utf8(record: BsrecRecord?) = record!!.payload.toString(Charsets.UTF_8)

    private fun modeOf(input: SessionInput): SessionMode? = BsrecPayloads.readModeChange(recordFor(input, start)!!.payload)

    @Test
    fun `packets are recorded raw with their offset from the session start`() {
        val bytes = byteArrayOf(1, 2, 3)
        val record = recordFor(SessionInput.Packet(bytes, at(250)), start)!!
        assertEquals(RecordType.BLE_PACKET, record.type)
        assertEquals(250L, record.tMsSinceStart)
        assertEquals(bytes.toList(), record.payload.toList())
    }

    @Test
    fun `events stamped before the session start are clamped to zero`() {
        val record = recordFor(SessionInput.Step(start - 3_000_000_000L), start)!!
        assertEquals(0L, record.tMsSinceStart)
    }

    @Test
    fun `gravity uses the radar-core payload layout`() {
        val record = recordFor(SessionInput.Gravity(0.5f, -9.8f, 1.25f, at(10)), start)!!
        assertEquals(RecordType.WATCH_GRAVITY, record.type)
        assertEquals(GravitySample(0.5f, -9.8f, 1.25f, at(10)), BsrecPayloads.readGravity(record.payload))
    }

    @Test
    fun `gyro uses its own record type with the gravity layout`() {
        val record = recordFor(SessionInput.Gyro(0.1f, 0.2f, 0.3f, at(5)), start)!!
        assertEquals(RecordType.WATCH_GYRO, record.type)
        assertEquals(GravitySample(0.1f, 0.2f, 0.3f, at(5)), BsrecPayloads.readWatchGyro(record.payload))
    }

    @Test
    fun `steps carry the event nanos`() {
        val record = recordFor(SessionInput.Step(at(30)), start)!!
        assertEquals(RecordType.WATCH_STEP, record.type)
        assertEquals(at(30), BsrecPayloads.readStep(record.payload))
    }

    @Test
    fun `belt info is recorded as utf8 json`() {
        val record = recordFor(SessionInput.BeltInfo("""{"proto":1}""", at(1)), start)
        assertEquals(RecordType.INFO_REREAD, record!!.type)
        assertEquals("""{"proto":1}""", BsrecPayloads.readInfo(record.payload))
    }

    @Test
    fun `rssi is a little-endian i16`() {
        val record = recordFor(SessionInput.Rssi(-70, at(1)), start)!!
        assertEquals(RecordType.RSSI, record.type)
        assertEquals(listOf(0xBA.toByte(), 0xFF.toByte()), record.payload.toList())
        assertEquals(-70, BsrecPayloads.readRssi(record.payload))
    }

    @Test
    fun `markers have an empty payload`() {
        val record = recordFor(SessionInput.Marker(at(2)), start)!!
        assertEquals(RecordType.MANUAL_MARKER, record.type)
        assertEquals(0, record.payload.size)
    }

    @Test
    fun `link changes are recorded as link mode names the replay understands`() {
        assertEquals(LINK_DOWN_MODE, utf8(recordFor(SessionInput.Link(false, at(1)), start)))
        assertEquals(true, BsrecPayloads.readLinkChange(recordFor(SessionInput.Link(true, at(1)), start)!!.payload))
        assertEquals(RecordType.MODE_CHANGE, recordFor(SessionInput.Link(true, at(1)), start)!!.type)
    }

    @Test
    fun `mode changes record the session mode the replay understands`() {
        assertEquals(SessionMode.ELIMINATED, modeOf(SessionInput.ModeChanged(eliminated = true, screenMode = ScreenMode.VISTA, nowNanos = at(1))))
        assertEquals(SessionMode.VIEW, modeOf(SessionInput.ModeChanged(eliminated = false, screenMode = ScreenMode.VISTA, nowNanos = at(1))))
        assertEquals(SessionMode.STEALTH, modeOf(SessionInput.ModeChanged(eliminated = false, screenMode = ScreenMode.SIGILO, nowNanos = at(1))))
    }

    @Test
    fun `ticks, flushes and deferred plays are not recorded`() {
        assertNull(recordFor(SessionInput.Tick(at(1)), start))
        assertNull(recordFor(SessionInput.Flush, start))
        assertNull(recordFor(SessionInput.PlayDeferred(ContactAlert(1, Side.LEFT, at(1)), at(2)), start))
    }

    @Test
    fun `vibration start records display id and side`() {
        val record = vibrationStartedRecord(ContactAlert(displayId = 7, side = Side.RIGHT, tNanos = at(1)), at(40), start)
        assertEquals(RecordType.VIBRATION_STARTED, record.type)
        assertEquals(40L, record.tMsSinceStart)
        assertEquals(listOf<Byte>(7, 0, 0, 0, 2), record.payload.toList())
    }

    @Test
    fun `track confirmations carry the display id at the event time`() {
        val record = trackConfirmedRecord(TrackConfirmed(displayId = 258, tNanos = at(90)), start)
        assertEquals(RecordType.TRACK_CONFIRMED, record.type)
        assertEquals(90L, record.tMsSinceStart)
        assertEquals(258, BsrecPayloads.readTrackConfirmed(record.payload))
    }
}
```


`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/recording/RecordingHeaderTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.recording

import io.github.santiquiroz.blindside.core.config.pipelineConfigFromValue
import io.github.santiquiroz.blindside.core.protocol.MiniJson
import io.github.santiquiroz.blindside.wear.settings.AppSettings
import io.github.santiquiroz.blindside.wear.settings.ScreenMode
import io.github.santiquiroz.blindside.wear.settings.toPipelineConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

class RecordingHeaderTest {
    private val meta = recordingMeta(
        settings = AppSettings(screenMode = ScreenMode.VISTA),
        source = "DEMO",
        stamp = SessionClockStamp(epochMs = 1_760_000_000_000L, elapsedNanos = 42L),
        device = "SM-L310",
        appVersion = "0.1.0",
        amplitudeControl = true,
        primitives = false,
    )

    @Test
    fun `header names the source, the clocks and the screen mode`() {
        val json = headerJson(meta)
        assertTrue(json.startsWith("{") && json.endsWith("}"))
        assertTrue(json.contains(""""source":"DEMO""""))
        assertTrue(json.contains(""""started_epoch_ms":1760000000000"""))
        assertTrue(json.contains(""""started_elapsed_ns":42"""))
        assertTrue(json.contains(""""screen_mode":"VISTA""""))
        assertTrue(json.contains(""""amplitude_control":true"""))
        assertTrue(json.contains(""""primitives":false"""))
    }

    @Test
    fun `header config is the session pipeline config and reads back the way the replay reads it`() {
        val json = headerJson(meta)
        val root = MiniJson.parse(json) as Map<*, *>
        assertTrue(json.contains(""""config":{"tuning":{"""))
        assertEquals(toPipelineConfig(AppSettings(screenMode = ScreenMode.VISTA)), pipelineConfigFromValue(root["config"]))
    }

    @Test
    fun `header embeds the first belt info verbatim`() {
        val info = """{"proto":1,"boot_id":"9f3a12c4","mtu":255}"""
        assertTrue(headerJson(meta.copy(infoJson = info)).contains(""""info":$info"""))
    }

    @Test
    fun `header without belt info says so`() {
        assertTrue(headerJson(meta).contains(""""info":null"""))
    }

    @Test
    fun `belt info that is not a json object is kept as a string`() {
        assertEquals("\"garbled\"", infoField("garbled"))
    }

    @Test
    fun `strings are escaped`() {
        assertEquals("\"a\\\"b\\\\c\\u000a\"", jsonString("a\"b\\c\n"))
    }

    @Test
    fun `file names sort by start time and name the source`() {
        assertEquals("blindside-belt-19700101-000000.bsrec", recordingFileName(0L, ZoneOffset.UTC, "BELT"))
    }
}
```

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/recording/RecordSinkTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.recording

import io.github.santiquiroz.blindside.core.replay.BsrecPayloads
import io.github.santiquiroz.blindside.core.replay.BsrecReader
import io.github.santiquiroz.blindside.core.replay.BsrecRecord
import io.github.santiquiroz.blindside.core.replay.RecordType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class RecordSinkTest {
    private val marker = BsrecRecord(RecordType.MANUAL_MARKER, 10L, ByteArray(0))

    @Test
    fun `file sink output reads back with the radar-core reader`() {
        val out = ByteArrayOutputStream()
        val sink = BsrecFileSink(out, """{"source":"TEST"}""")
        sink.record(marker)
        sink.record(BsrecRecord(RecordType.WATCH_STEP, 20L, BsrecPayloads.step(42L)))
        sink.close()

        val reader = BsrecReader(ByteArrayInputStream(out.toByteArray()))
        val read = reader.records().toList()
        assertEquals("""{"source":"TEST"}""", reader.headerJson)
        assertEquals(listOf(RecordType.MANUAL_MARKER to 10L, RecordType.WATCH_STEP to 20L), read.map { it.type to it.tMsSinceStart })
        assertEquals(BsrecPayloads.step(42L).toList(), read[1].payload.toList())
    }

    @Test
    fun `a failing sink reports once and the session keeps going`() {
        val failures = mutableListOf<Exception>()
        val sink = SafeRecordSink(ThrowingSink()) { failures += it }
        repeat(3) { sink.record(marker) }
        sink.flush()
        sink.close()
        assertEquals(1, failures.size)
    }

    @Test
    fun `opening a recording where no file can be created falls back to a no-op sink`(@TempDir dir: File) {
        val notADirectory = File(dir, "plain-file").apply { writeText("x") }
        val failures = mutableListOf<Exception>()
        val sink = openRecordingSink(notADirectory, "a.bsrec", "{}") { failures += it }
        sink.record(marker)
        assertSame(NoOpRecordSink, sink)
        assertEquals(1, failures.size)
    }

    @Test
    fun `opening a recording writes the header to disk`(@TempDir dir: File) {
        val sink = openRecordingSink(dir, "a.bsrec", "{}") { error -> throw AssertionError(error) }
        sink.record(marker)
        sink.close()
        assertTrue(File(dir, "a.bsrec").length() > 0)
    }

    @Test
    fun `a belt recording waits for the first info and writes the earlier records after it opens`() {
        val opened = mutableListOf<String?>()
        val inner = MemorySink()
        val sink = InfoHeaderSink { info -> opened += info; inner }
        val gravity = BsrecRecord(RecordType.WATCH_GRAVITY, 5L, BsrecPayloads.gravity(0f, 0f, 9.8f, 1L))
        val info = BsrecRecord(RecordType.INFO_REREAD, 300L, BsrecPayloads.info("""{"boot_id":"ab"}"""))
        sink.record(gravity)
        assertTrue(opened.isEmpty())
        sink.record(info)
        assertEquals(listOf<String?>("""{"boot_id":"ab"}"""), opened)
        assertEquals(listOf(RecordType.WATCH_GRAVITY, RecordType.INFO_REREAD), inner.items.map { it.type })
    }

    @Test
    fun `without belt info the recording opens after ten seconds with no info`() {
        val opened = mutableListOf<String?>()
        val sink = InfoHeaderSink { info -> opened += info; MemorySink() }
        sink.record(BsrecRecord(RecordType.WATCH_STEP, INFO_WAIT_MS - 1, BsrecPayloads.step(1L)))
        assertTrue(opened.isEmpty())
        sink.record(BsrecRecord(RecordType.WATCH_STEP, INFO_WAIT_MS, BsrecPayloads.step(2L)))
        assertEquals(listOf<String?>(null), opened)
    }

    @Test
    fun `closing before any info still writes the file`() {
        val inner = MemorySink()
        val sink = InfoHeaderSink { inner }
        sink.record(marker)
        sink.close()
        assertEquals(1, inner.items.size)
        assertTrue(inner.closed)
    }
}

private class ThrowingSink : RecordSink {
    override fun record(record: BsrecRecord) = throw IOException("disk full")
    override fun flush() = throw IOException("disk full")
    override fun close() = throw IOException("disk full")
}

private class MemorySink : RecordSink {
    val items = mutableListOf<BsrecRecord>()
    var closed = false

    override fun record(record: BsrecRecord) {
        items += record
    }

    override fun flush() = Unit

    override fun close() {
        closed = true
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :wear-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.wear.recording.*"`
Expected: FAIL at compilation with `Unresolved reference 'SessionInput'`.

- [ ] **Step 3: Write the implementation**

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/SessionInput.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.session

import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.wear.settings.ScreenMode

sealed interface SessionInput {
    class Packet(val bytes: ByteArray, val arrivalNanos: Long) : SessionInput
    data class Gravity(val x: Float, val y: Float, val z: Float, val eventNanos: Long) : SessionInput
    data class Gyro(val x: Float, val y: Float, val z: Float, val eventNanos: Long) : SessionInput
    data class Step(val eventNanos: Long) : SessionInput
    data class BeltInfo(val json: String, val nowNanos: Long) : SessionInput
    data class Rssi(val dbm: Int, val nowNanos: Long) : SessionInput
    data class Link(val connected: Boolean, val nowNanos: Long) : SessionInput
    data class ModeChanged(val eliminated: Boolean, val screenMode: ScreenMode, val nowNanos: Long) : SessionInput
    data class Marker(val nowNanos: Long) : SessionInput
    data class Tick(val nowNanos: Long) : SessionInput
    data class PlayDeferred(val alert: ContactAlert, val atNanos: Long) : SessionInput
    data object Flush : SessionInput
}
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/recording/RecordEncoding.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.recording

import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.alerts.TrackConfirmed
import io.github.santiquiroz.blindside.core.replay.BsrecPayloads
import io.github.santiquiroz.blindside.core.replay.BsrecRecord
import io.github.santiquiroz.blindside.core.replay.RecordType
import io.github.santiquiroz.blindside.core.replay.SessionMode
import io.github.santiquiroz.blindside.wear.session.SessionInput
import io.github.santiquiroz.blindside.wear.settings.ScreenMode

private const val NANOS_PER_MS = 1_000_000L

fun recordFor(input: SessionInput, startNanos: Long): BsrecRecord? = when (input) {
    is SessionInput.Packet -> record(RecordType.BLE_PACKET, input.arrivalNanos, startNanos, input.bytes.copyOf())
    is SessionInput.Gravity ->
        record(RecordType.WATCH_GRAVITY, input.eventNanos, startNanos, BsrecPayloads.gravity(input.x, input.y, input.z, input.eventNanos))
    is SessionInput.Gyro ->
        record(RecordType.WATCH_GYRO, input.eventNanos, startNanos, BsrecPayloads.watchGyro(input.x, input.y, input.z, input.eventNanos))
    is SessionInput.Step -> record(RecordType.WATCH_STEP, input.eventNanos, startNanos, BsrecPayloads.step(input.eventNanos))
    is SessionInput.BeltInfo -> record(RecordType.INFO_REREAD, input.nowNanos, startNanos, BsrecPayloads.info(input.json))
    is SessionInput.Rssi -> record(RecordType.RSSI, input.nowNanos, startNanos, BsrecPayloads.rssi(input.dbm))
    is SessionInput.Marker -> record(RecordType.MANUAL_MARKER, input.nowNanos, startNanos, ByteArray(0))
    is SessionInput.Link -> modeRecord(BsrecPayloads.linkChange(input.connected), input.nowNanos, startNanos)
    is SessionInput.ModeChanged ->
        modeRecord(BsrecPayloads.modeChange(sessionMode(input.eliminated, input.screenMode)), input.nowNanos, startNanos)
    is SessionInput.Tick, is SessionInput.PlayDeferred, SessionInput.Flush -> null
}

fun trackConfirmedRecord(event: TrackConfirmed, startNanos: Long): BsrecRecord =
    record(RecordType.TRACK_CONFIRMED, event.tNanos, startNanos, BsrecPayloads.trackConfirmed(event.displayId))

fun vibrationStartedRecord(alert: ContactAlert, startedNanos: Long, startNanos: Long): BsrecRecord =
    record(RecordType.VIBRATION_STARTED, startedNanos, startNanos, BsrecPayloads.vibrationStarted(alert.displayId, alert.side))

fun sessionMode(eliminated: Boolean, screenMode: ScreenMode): SessionMode = when {
    eliminated -> SessionMode.ELIMINATED
    screenMode == ScreenMode.VISTA -> SessionMode.VIEW
    else -> SessionMode.STEALTH
}

fun msSinceStart(eventNanos: Long, startNanos: Long): Long =
    ((eventNanos - startNanos) / NANOS_PER_MS).coerceAtLeast(0L)

private fun modeRecord(payload: ByteArray, nowNanos: Long, startNanos: Long): BsrecRecord =
    record(RecordType.MODE_CHANGE, nowNanos, startNanos, payload)

private fun record(type: RecordType, nanos: Long, startNanos: Long, payload: ByteArray): BsrecRecord =
    BsrecRecord(type, msSinceStart(nanos, startNanos), payload)
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/recording/RecordingHeader.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.recording

import io.github.santiquiroz.blindside.core.config.toJson
import io.github.santiquiroz.blindside.wear.settings.AppSettings
import io.github.santiquiroz.blindside.wear.settings.toPipelineConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class SessionClockStamp(val epochMs: Long, val elapsedNanos: Long)

data class RecordingMeta(
    val appVersion: String,
    val source: String,
    val stamp: SessionClockStamp,
    val device: String,
    val handedness: String,
    val screenMode: String,
    val vibrationUsage: String,
    val configJson: String,
    val amplitudeControl: Boolean,
    val primitives: Boolean,
    val infoJson: String? = null,
)

private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

fun recordingMeta(
    settings: AppSettings,
    source: String,
    stamp: SessionClockStamp,
    device: String,
    appVersion: String,
    amplitudeControl: Boolean,
    primitives: Boolean,
): RecordingMeta = RecordingMeta(
    appVersion = appVersion,
    source = source,
    stamp = stamp,
    device = device,
    handedness = settings.handedness.name,
    screenMode = settings.screenMode.name,
    vibrationUsage = settings.vibrationUsage.name,
    configJson = toPipelineConfig(settings).toJson(),
    amplitudeControl = amplitudeControl,
    primitives = primitives,
)

fun headerJson(meta: RecordingMeta): String = jsonObject(
    "format" to jsonString("blindside-wear"),
    "app_version" to jsonString(meta.appVersion),
    "source" to jsonString(meta.source),
    "started_epoch_ms" to meta.stamp.epochMs.toString(),
    "started_elapsed_ns" to meta.stamp.elapsedNanos.toString(),
    "device" to jsonString(meta.device),
    "handedness" to jsonString(meta.handedness),
    "screen_mode" to jsonString(meta.screenMode),
    "vibration_usage" to jsonString(meta.vibrationUsage),
    "config" to meta.configJson,
    "amplitude_control" to meta.amplitudeControl.toString(),
    "primitives" to meta.primitives.toString(),
    "info" to infoField(meta.infoJson),
)

fun recordingFileName(epochMs: Long, zone: ZoneId, source: String): String =
    "blindside-${source.lowercase()}-${FILE_STAMP.format(Instant.ofEpochMilli(epochMs).atZone(zone))}.bsrec"

fun jsonString(value: String): String = value.map(::escapeJsonChar).joinToString("", "\"", "\"")

// The belt's info is embedded as-is; anything that is not an object stays a string so the header stays valid JSON.
fun infoField(json: String?): String = when {
    json == null -> "null"
    looksLikeJsonObject(json) -> json.trim()
    else -> jsonString(json)
}

private fun looksLikeJsonObject(json: String): Boolean = json.trim().let { it.startsWith("{") && it.endsWith("}") }

private fun jsonObject(vararg fields: Pair<String, String>): String =
    fields.joinToString(",", "{", "}") { (name, value) -> "${jsonString(name)}:$value" }

private fun escapeJsonChar(c: Char): String = when {
    c == '"' -> "\\\""
    c == '\\' -> "\\\\"
    c < ' ' -> "\\u%04x".format(c.code)
    else -> c.toString()
}
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/recording/RecordSink.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.recording

import io.github.santiquiroz.blindside.core.replay.BsrecPayloads
import io.github.santiquiroz.blindside.core.replay.BsrecRecord
import io.github.santiquiroz.blindside.core.replay.BsrecWriter
import io.github.santiquiroz.blindside.core.replay.RecordType
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

private const val RECORDING_BUFFER_BYTES = 64 * 1024
const val INFO_WAIT_MS = 10_000L
const val MAX_RECORDS_BEFORE_INFO = 2_000

interface RecordSink {
    fun record(record: BsrecRecord)
    fun flush()
    fun close()
}

class BsrecFileSink(private val out: OutputStream, headerJson: String) : RecordSink {
    private val writer = BsrecWriter(out, headerJson)

    override fun record(record: BsrecRecord) = writer.write(record)

    override fun flush() = out.flush()

    override fun close() = writer.close()
}

class SafeRecordSink(
    private val inner: RecordSink,
    private val onFailure: (Exception) -> Unit,
) : RecordSink {
    private var failed = false

    override fun record(record: BsrecRecord) = guarded { inner.record(record) }

    override fun flush() = guarded { inner.flush() }

    override fun close() = guarded { inner.close() }

    // Recording must never stop a game: the first failure is reported and the sink goes quiet.
    private fun guarded(action: () -> Unit) {
        if (failed) return
        try {
            action()
        } catch (error: Exception) {
            failed = true
            onFailure(error)
        }
    }
}

object NoOpRecordSink : RecordSink {
    override fun record(record: BsrecRecord) = Unit
    override fun flush() = Unit
    override fun close() = Unit
}

// Spec §6.10: the header carries the belt's first info, which only exists after the first connection.
class InfoHeaderSink(private val open: (infoJson: String?) -> RecordSink) : RecordSink {
    private var inner: RecordSink? = null
    private val waiting = mutableListOf<BsrecRecord>()

    override fun record(record: BsrecRecord) {
        val target = inner
        if (target != null) target.record(record) else hold(record)
    }

    override fun flush() {
        inner?.flush()
    }

    override fun close() = (inner ?: openWith(null)).close()

    private fun hold(record: BsrecRecord) {
        waiting += record
        val info = infoOf(record)
        if (info != null || stopsWaiting(record)) openWith(info)
    }

    private fun stopsWaiting(record: BsrecRecord): Boolean =
        record.tMsSinceStart >= INFO_WAIT_MS || waiting.size >= MAX_RECORDS_BEFORE_INFO

    private fun openWith(info: String?): RecordSink {
        val opened = open(info)
        waiting.forEach(opened::record)
        waiting.clear()
        inner = opened
        return opened
    }
}

fun infoOf(record: BsrecRecord): String? =
    if (record.type == RecordType.INFO_REREAD) BsrecPayloads.readInfo(record.payload) else null

fun openRecordingSink(dir: File, fileName: String, headerJson: String, onFailure: (Exception) -> Unit): RecordSink =
    try {
        dir.mkdirs()
        val out = BufferedOutputStream(FileOutputStream(File(dir, fileName)), RECORDING_BUFFER_BYTES)
        SafeRecordSink(BsrecFileSink(out, headerJson), onFailure)
    } catch (error: Exception) {
        onFailure(error)
        NoOpRecordSink
    }
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/recording/RecordingDirs.kt`. The app-specific external directory (`/sdcard/Android/data/<app>/files/recordings`) is used because `adb pull` can read it on a debug build without root:

```kotlin
package io.github.santiquiroz.blindside.wear.recording

import android.content.Context
import java.io.File

private const val RECORDINGS_DIR = "recordings"

fun recordingsDir(context: Context): File =
    context.getExternalFilesDir(RECORDINGS_DIR) ?: File(context.filesDir, RECORDINGS_DIR)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :wear-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.wear.recording.*"`
Expected: PASS (27 tests: 13 encoding, 7 header, 7 sink).

- [ ] **Step 5: Commit**

```bash
git add watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/SessionInput.kt watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/recording watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/recording
git commit -m "feat: entradas de sesión y grabación .bsrec con info del cinturón en la cabecera, tolerante a fallos de disco"
```

---

### Task 5: Session engine (one input → record, pipeline, haptics, scene)

Spec §5.2 (`BlindsideSessionService` owns the pipeline, vibrator and recorder; publishes `StateFlow<RadarScene>`), §5.3 (30 fps only in Vista), §5.4 (no live contacts in ambient), §5.5 (a contact waits for a running system buzz), §7 (data flow), contracts (single-threaded `radar-core`, the app never re-limits).

**Files:**
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/haptics/HapticGate.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/PipelinePort.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/SessionEngine.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/ScenePacing.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/haptics/HapticGateTest.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/session/SessionEngineTest.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/session/ScenePacingTest.kt`

**Interfaces:**
- Consumes:
  - `RadarPipeline` (contract signature with `onWatchGyro` and `onBeltInfo`, plan 01 Task 16a; its `ContactAlert`s come from the alert stage of Task 16c), `.alerts.*`, `.scene.RadarScene`.
  - `SessionInput`, `RecordSink`, `recordFor`, `trackConfirmedRecord`, `vibrationStartedRecord` (Task 4).
  - `HapticSink`, `hapticFor`, `SYSTEM_BUZZ_MS` (Task 3), `ScreenMode` (Task 2).
- Produces:
  - `data class HapticGate(systemBusyUntilNanos: Long = Long.MIN_VALUE)`, `fun afterSystemBuzz(gate, nowNanos): HapticGate`, `fun contactStartNanos(gate, nowNanos): Long`, `fun systemFirst(events: List<PipelineEvent>): List<PipelineEvent>`, `fun millisUntil(atNanos: Long, nowNanos: Long): Long`, `const val SYSTEM_BUZZ_NANOS`
  - `interface PipelinePort` (same methods as `RadarPipeline`), `class RadarPipelineAdapter(pipeline: RadarPipeline) : PipelinePort`
  - `fun interface SceneSink { fun publish(scene: RadarScene) }`, `fun interface NanoClock { fun nowNanos(): Long }`, `fun interface DeferredPlayback { fun playAt(alert: ContactAlert, atNanos: Long) }`
  - `class SessionEngine(pipeline: PipelinePort, records: RecordSink, haptics: HapticSink, scenes: SceneSink, clock: NanoClock, startNanos: Long, deferred: DeferredPlayback, onError: (Throwable) -> Unit) { fun handle(input: SessionInput); fun close() }`
  - `const val VISTA_FRAME_MS = 33L`, `SIGILO_FRAME_MS = 100L`, `IDLE_TICK_POLL_MS = 500L`, `fun scenePeriodMs(radarVisible: Boolean, mode: ScreenMode, ambient: Boolean): Long?`

Order inside `handle(input)`:
1. Write the input's record.
2. Feed the pipeline.
3. Handle the returned events, system alerts first:
   - `TrackConfirmed`: record `TRACK_CONFIRMED`.
   - `SystemAlert`: buzz at once and mark the gate busy for `SYSTEM_BUZZ_MS`.
   - `ContactAlert`: if the gate is free, vibrate the side rhythm and record `VIBRATION_STARTED` at `clock.nowNanos()`. Otherwise hand it to `deferred.playAt(alert, busyUntil)`; the service sends it back as `SessionInput.PlayDeferred` when the buzz ends, and only then it vibrates and is recorded.
4. Track the eliminated flag from `ModeChanged`: a deferred contact that comes back while eliminated is dropped (spec §5.5, §10.1).
5. On `Tick`, publish `pipeline.scene(t)`. On `Flush`, flush the recorder.

Any exception goes to `onError`, and the next input is processed normally (Review Focus 1).

The gate exists because `Vibrator.vibrate()` cancels the vibration in progress: a contact pattern started 0.5 s into the 1.2 s system buzz would cut the buzz, and `[ContactAlert, SystemAlert]` in one list would cut the contact. A system alert is never delayed (spec §5.5); `radar-core` still decides every contact alert, and the engine only chooses when the motor starts it.

- [ ] **Step 1: Write the failing tests**

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/session/SessionEngineTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.session

import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.alerts.PipelineEvent
import io.github.santiquiroz.blindside.core.alerts.SystemAlert
import io.github.santiquiroz.blindside.core.alerts.TrackConfirmed
import io.github.santiquiroz.blindside.core.replay.BsrecPayloads
import io.github.santiquiroz.blindside.core.replay.BsrecRecord
import io.github.santiquiroz.blindside.core.replay.RecordType
import io.github.santiquiroz.blindside.core.replay.SessionMode
import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.Side
import io.github.santiquiroz.blindside.core.scene.Warning
import io.github.santiquiroz.blindside.wear.haptics.HapticPattern
import io.github.santiquiroz.blindside.wear.haptics.LEFT_PATTERN
import io.github.santiquiroz.blindside.wear.haptics.SYSTEM_BUZZ_MS
import io.github.santiquiroz.blindside.wear.haptics.SYSTEM_PATTERN
import io.github.santiquiroz.blindside.wear.recording.RecordSink
import io.github.santiquiroz.blindside.wear.settings.ScreenMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionEngineTest {
    private val start = 1_000_000_000L

    private fun at(ms: Long): Long = start + ms * 1_000_000L

    private var nowMs = 40L
    private val pipeline = FakePipeline()
    private val records = FakeRecords()
    private val played = mutableListOf<HapticPattern>()
    private val scenes = mutableListOf<RadarScene>()
    private val errors = mutableListOf<Throwable>()
    private val deferred = mutableListOf<Pair<ContactAlert, Long>>()
    private val engine = SessionEngine(
        pipeline = pipeline,
        records = records,
        haptics = { played += it },
        scenes = { scenes += it },
        clock = { at(nowMs) },
        startNanos = start,
        deferred = { alert, atNanos -> deferred += alert to atNanos },
        onError = { errors += it },
    )

    private val leftContact = ContactAlert(displayId = 4, side = Side.LEFT, tNanos = at(40))

    private fun vibrationRecords() = records.items.filter { it.type == RecordType.VIBRATION_STARTED }

    @Test
    fun `records the raw packet and feeds it to the pipeline`() {
        engine.handle(SessionInput.Packet(byteArrayOf(9, 8), at(250)))
        assertEquals(listOf("packet:2@${at(250)}"), pipeline.calls)
        assertEquals(RecordType.BLE_PACKET, records.items.single().type)
        assertEquals(250L, records.items.single().tMsSinceStart)
    }

    @Test
    fun `a left contact alert vibrates the left rhythm and records the vibration start`() {
        pipeline.packetEvents = listOf(ContactAlert(displayId = 4, side = Side.LEFT, tNanos = at(250)))
        engine.handle(SessionInput.Packet(byteArrayOf(1), at(250)))
        assertEquals(listOf(LEFT_PATTERN), played)
        val vibration = records.items.single { it.type == RecordType.VIBRATION_STARTED }
        assertEquals(40L, vibration.tMsSinceStart)
        assertEquals(listOf<Byte>(4, 0, 0, 0, 0), vibration.payload.toList())
    }

    @Test
    fun `a system alert buzzes without a vibration record`() {
        pipeline.linkEvents = listOf(SystemAlert(kind = Warning.LINK_LOST, tNanos = at(5)))
        engine.handle(SessionInput.Link(false, at(5)))
        assertEquals(listOf(SYSTEM_PATTERN), played)
        assertEquals(listOf("link:false"), pipeline.calls)
        assertEquals(false, BsrecPayloads.readLinkChange(records.items.single().payload))
    }

    @Test
    fun `a contact in the same input as a system alert waits for the buzz to end`() {
        pipeline.packetEvents = listOf(leftContact, SystemAlert(kind = Warning.RADAR_DOWN, tNanos = at(40)))
        engine.handle(SessionInput.Packet(byteArrayOf(1), at(40)))
        assertEquals(listOf(SYSTEM_PATTERN), played)
        assertEquals(listOf(leftContact to at(40 + SYSTEM_BUZZ_MS)), deferred)
        assertTrue(vibrationRecords().isEmpty())
    }

    @Test
    fun `a contact half a second into a system buzz waits for the rest of it`() {
        pipeline.linkEvents = listOf(SystemAlert(kind = Warning.LINK_LOST, tNanos = at(40)))
        engine.handle(SessionInput.Link(false, at(40)))
        nowMs = 540L
        pipeline.packetEvents = listOf(leftContact)
        engine.handle(SessionInput.Packet(byteArrayOf(1), at(540)))
        assertEquals(listOf(leftContact to at(1_240)), deferred)
    }

    @Test
    fun `a deferred contact vibrates and is recorded when its turn comes`() {
        nowMs = 1_240L
        engine.handle(SessionInput.PlayDeferred(leftContact, at(1_240)))
        assertEquals(listOf(LEFT_PATTERN), played)
        assertEquals(1_240L, vibrationRecords().single().tMsSinceStart)
    }

    @Test
    fun `a deferred contact is dropped once the player is eliminated`() {
        engine.handle(SessionInput.ModeChanged(eliminated = true, screenMode = ScreenMode.SIGILO, nowNanos = at(100)))
        engine.handle(SessionInput.PlayDeferred(leftContact, at(1_240)))
        assertTrue(played.isEmpty())
        assertTrue(vibrationRecords().isEmpty())
    }

    @Test
    fun `a track confirmation is recorded and does not vibrate`() {
        pipeline.packetEvents = listOf(TrackConfirmed(displayId = 2, tNanos = at(120)))
        engine.handle(SessionInput.Packet(byteArrayOf(1), at(100)))
        assertTrue(played.isEmpty())
        assertEquals(120L, records.items.single { it.type == RecordType.TRACK_CONFIRMED }.tMsSinceStart)
    }

    @Test
    fun `watch and belt side inputs reach the pipeline`() {
        engine.handle(SessionInput.Gravity(0f, 0f, 9.8f, at(1)))
        engine.handle(SessionInput.Gyro(0.1f, 0f, 0f, at(2)))
        engine.handle(SessionInput.Step(at(3)))
        engine.handle(SessionInput.BeltInfo("{}", at(4)))
        assertEquals(listOf("gravity@${at(1)}", "gyro@${at(2)}", "step@${at(3)}", "info:{}"), pipeline.calls)
        assertEquals(4, records.items.size)
    }

    @Test
    fun `a mode change reaches the pipeline and is recorded`() {
        engine.handle(SessionInput.ModeChanged(eliminated = true, screenMode = ScreenMode.SIGILO, nowNanos = at(7)))
        assertEquals(listOf("eliminated:true"), pipeline.calls)
        assertEquals(SessionMode.ELIMINATED, BsrecPayloads.readModeChange(records.items.single().payload))
    }

    @Test
    fun `a tick publishes the scene for that instant without recording`() {
        engine.handle(SessionInput.Tick(at(33)))
        assertEquals(listOf("scene@${at(33)}"), pipeline.calls)
        assertEquals(listOf(pipeline.scene), scenes)
        assertTrue(records.items.isEmpty())
    }

    @Test
    fun `a flush input flushes the recorder`() {
        engine.handle(SessionInput.Flush)
        assertEquals(1, records.flushes)
    }

    @Test
    fun `keeps processing inputs after the pipeline throws`() {
        pipeline.failOnPacket = true
        engine.handle(SessionInput.Packet(byteArrayOf(1), at(10)))
        engine.handle(SessionInput.Step(at(11)))
        assertEquals(1, errors.size)
        assertTrue("step@${at(11)}" in pipeline.calls)
    }

    @Test
    fun `closing the engine closes the recorder`() {
        engine.close()
        assertTrue(records.closed)
    }
}

private class FakePipeline : PipelinePort {
    val calls = mutableListOf<String>()
    var packetEvents: List<PipelineEvent> = emptyList()
    var linkEvents: List<PipelineEvent> = emptyList()
    var failOnPacket = false
    val scene = RadarScene(
        blips = emptyList(),
        coverage = emptyList(),
        linkUp = true,
        radars = emptyList(),
        imus = emptyList(),
        motion = MotionState.STILL,
        warnings = emptySet(),
        eliminated = false,
    )

    override fun onBlePacket(bytes: ByteArray, arrivalNanos: Long): List<PipelineEvent> {
        calls += "packet:${bytes.size}@$arrivalNanos"
        check(!failOnPacket) { "decoder blew up" }
        return packetEvents
    }

    override fun onWatchGravity(x: Float, y: Float, z: Float, eventNanos: Long) {
        calls += "gravity@$eventNanos"
    }

    override fun onWatchGyro(x: Float, y: Float, z: Float, eventNanos: Long) {
        calls += "gyro@$eventNanos"
    }

    override fun onWatchStep(eventNanos: Long) {
        calls += "step@$eventNanos"
    }

    override fun onBeltInfo(json: String, nowNanos: Long) {
        calls += "info:$json"
    }

    override fun onLinkState(connected: Boolean, nowNanos: Long): List<PipelineEvent> {
        calls += "link:$connected"
        return linkEvents
    }

    override fun setEliminated(on: Boolean) {
        calls += "eliminated:$on"
    }

    override fun scene(nowNanos: Long): RadarScene {
        calls += "scene@$nowNanos"
        return scene
    }
}

private class FakeRecords : RecordSink {
    val items = mutableListOf<BsrecRecord>()
    var flushes = 0
    var closed = false

    override fun record(record: BsrecRecord) {
        items += record
    }

    override fun flush() {
        flushes += 1
    }

    override fun close() {
        closed = true
    }
}
```

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/session/ScenePacingTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.session

import io.github.santiquiroz.blindside.wear.settings.ScreenMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ScenePacingTest {
    @Test
    fun `no scenes are computed while the radar is not on screen`() {
        assertNull(scenePeriodMs(radarVisible = false, mode = ScreenMode.VISTA, ambient = false))
    }

    @Test
    fun `no scenes are computed in ambient, where contacts are hidden`() {
        assertNull(scenePeriodMs(radarVisible = true, mode = ScreenMode.SIGILO, ambient = true))
    }

    @Test
    fun `vista draws at thirty frames per second`() {
        assertEquals(VISTA_FRAME_MS, scenePeriodMs(radarVisible = true, mode = ScreenMode.VISTA, ambient = false))
    }

    @Test
    fun `sigilo draws at the belt data rate`() {
        assertEquals(SIGILO_FRAME_MS, scenePeriodMs(radarVisible = true, mode = ScreenMode.SIGILO, ambient = false))
    }
}
```

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/haptics/HapticGateTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.haptics

import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.alerts.SystemAlert
import io.github.santiquiroz.blindside.core.alerts.TrackConfirmed
import io.github.santiquiroz.blindside.core.scene.Side
import io.github.santiquiroz.blindside.core.scene.Warning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HapticGateTest {
    private val second = 1_000_000_000L

    @Test
    fun `a contact plays at once when no system buzz is running`() {
        assertEquals(5 * second, contactStartNanos(HapticGate(), 5 * second))
    }

    @Test
    fun `a contact during a system buzz starts when the buzz ends`() {
        val busy = afterSystemBuzz(HapticGate(), 0L)
        assertEquals(SYSTEM_BUZZ_NANOS, contactStartNanos(busy, second / 2))
        assertEquals(2 * second, contactStartNanos(busy, 2 * second))
    }

    @Test
    fun `system alerts are handled before the other events of the same input`() {
        val contact = ContactAlert(1, Side.LEFT, 0L)
        val confirmed = TrackConfirmed(1, 0L)
        val system = SystemAlert(Warning.RADAR_DOWN, 0L)
        assertEquals(listOf(system, contact, confirmed), systemFirst(listOf(contact, system, confirmed)))
    }

    @Test
    fun `waiting times are whole milliseconds and never negative`() {
        assertEquals(700L, millisUntil(atNanos = 1_240_000_000L, nowNanos = 540_000_000L))
        assertEquals(0L, millisUntil(atNanos = 0L, nowNanos = second))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :wear-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.wear.session.*" --tests "io.github.santiquiroz.blindside.wear.haptics.HapticGateTest"`
Expected: FAIL at compilation with `Unresolved reference 'PipelinePort'`.

- [ ] **Step 3: Write the implementation**

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/haptics/HapticGate.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.haptics

import io.github.santiquiroz.blindside.core.alerts.PipelineEvent
import io.github.santiquiroz.blindside.core.alerts.SystemAlert

private const val NANOS_PER_MS = 1_000_000L
const val SYSTEM_BUZZ_NANOS = SYSTEM_BUZZ_MS * NANOS_PER_MS

data class HapticGate(val systemBusyUntilNanos: Long = Long.MIN_VALUE)

fun afterSystemBuzz(gate: HapticGate, nowNanos: Long): HapticGate = gate.copy(systemBusyUntilNanos = nowNanos + SYSTEM_BUZZ_NANOS)

fun contactStartNanos(gate: HapticGate, nowNanos: Long): Long = maxOf(nowNanos, gate.systemBusyUntilNanos)

fun systemFirst(events: List<PipelineEvent>): List<PipelineEvent> = events.sortedBy { it !is SystemAlert }

fun millisUntil(atNanos: Long, nowNanos: Long): Long = ((atNanos - nowNanos) / NANOS_PER_MS).coerceAtLeast(0L)
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/PipelinePort.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.session

import io.github.santiquiroz.blindside.core.RadarPipeline
import io.github.santiquiroz.blindside.core.alerts.PipelineEvent
import io.github.santiquiroz.blindside.core.scene.RadarScene

interface PipelinePort {
    fun onBlePacket(bytes: ByteArray, arrivalNanos: Long): List<PipelineEvent>
    fun onWatchGravity(x: Float, y: Float, z: Float, eventNanos: Long)
    fun onWatchGyro(x: Float, y: Float, z: Float, eventNanos: Long)
    fun onWatchStep(eventNanos: Long)
    fun onBeltInfo(json: String, nowNanos: Long)
    fun onLinkState(connected: Boolean, nowNanos: Long): List<PipelineEvent>
    fun setEliminated(on: Boolean)
    fun scene(nowNanos: Long): RadarScene
}

class RadarPipelineAdapter(private val pipeline: RadarPipeline) : PipelinePort {
    override fun onBlePacket(bytes: ByteArray, arrivalNanos: Long) = pipeline.onBlePacket(bytes, arrivalNanos)

    override fun onWatchGravity(x: Float, y: Float, z: Float, eventNanos: Long) =
        pipeline.onWatchGravity(x, y, z, eventNanos)

    override fun onWatchGyro(x: Float, y: Float, z: Float, eventNanos: Long) = pipeline.onWatchGyro(x, y, z, eventNanos)

    override fun onWatchStep(eventNanos: Long) = pipeline.onWatchStep(eventNanos)

    override fun onBeltInfo(json: String, nowNanos: Long) = pipeline.onBeltInfo(json, nowNanos)

    override fun onLinkState(connected: Boolean, nowNanos: Long) = pipeline.onLinkState(connected, nowNanos)

    override fun setEliminated(on: Boolean) = pipeline.setEliminated(on)

    override fun scene(nowNanos: Long) = pipeline.scene(nowNanos)
}
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/SessionEngine.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.session

import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.alerts.PipelineEvent
import io.github.santiquiroz.blindside.core.alerts.SystemAlert
import io.github.santiquiroz.blindside.core.alerts.TrackConfirmed
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.wear.haptics.HapticGate
import io.github.santiquiroz.blindside.wear.haptics.HapticSink
import io.github.santiquiroz.blindside.wear.haptics.afterSystemBuzz
import io.github.santiquiroz.blindside.wear.haptics.contactStartNanos
import io.github.santiquiroz.blindside.wear.haptics.hapticFor
import io.github.santiquiroz.blindside.wear.haptics.systemFirst
import io.github.santiquiroz.blindside.wear.recording.RecordSink
import io.github.santiquiroz.blindside.wear.recording.recordFor
import io.github.santiquiroz.blindside.wear.recording.trackConfirmedRecord
import io.github.santiquiroz.blindside.wear.recording.vibrationStartedRecord

fun interface SceneSink {
    fun publish(scene: RadarScene)
}

fun interface NanoClock {
    fun nowNanos(): Long
}

fun interface DeferredPlayback {
    fun playAt(alert: ContactAlert, atNanos: Long)
}

class SessionEngine(
    private val pipeline: PipelinePort,
    private val records: RecordSink,
    private val haptics: HapticSink,
    private val scenes: SceneSink,
    private val clock: NanoClock,
    private val startNanos: Long,
    private val deferred: DeferredPlayback,
    private val onError: (Throwable) -> Unit,
) {
    private var gate = HapticGate()
    private var eliminated = false

    fun handle(input: SessionInput) {
        try {
            process(input)
        } catch (error: Exception) {
            onError(error)
        }
    }

    fun close() = records.close()

    private fun process(input: SessionInput) {
        recordFor(input, startNanos)?.let(records::record)
        systemFirst(feed(input)).forEach(::react)
        trackEliminated(input)
        replayDeferred(input)
        publishSceneOnTick(input)
        flushOnRequest(input)
    }

    private fun feed(input: SessionInput): List<PipelineEvent> = when (input) {
        is SessionInput.Packet -> pipeline.onBlePacket(input.bytes, input.arrivalNanos)
        is SessionInput.Link -> pipeline.onLinkState(input.connected, input.nowNanos)
        else -> feedWithoutEvents(input)
    }

    private fun feedWithoutEvents(input: SessionInput): List<PipelineEvent> {
        when (input) {
            is SessionInput.Gravity -> pipeline.onWatchGravity(input.x, input.y, input.z, input.eventNanos)
            is SessionInput.Gyro -> pipeline.onWatchGyro(input.x, input.y, input.z, input.eventNanos)
            is SessionInput.Step -> pipeline.onWatchStep(input.eventNanos)
            is SessionInput.BeltInfo -> pipeline.onBeltInfo(input.json, input.nowNanos)
            is SessionInput.ModeChanged -> pipeline.setEliminated(input.eliminated)
            else -> Unit
        }
        return emptyList()
    }

    private fun react(event: PipelineEvent) {
        when (event) {
            is TrackConfirmed -> records.record(trackConfirmedRecord(event, startNanos))
            is SystemAlert -> buzzSystem(event)
            is ContactAlert -> playOrDefer(event)
        }
    }

    private fun buzzSystem(alert: SystemAlert) {
        hapticFor(alert)?.let(haptics::play)
        gate = afterSystemBuzz(gate, clock.nowNanos())
    }

    private fun playOrDefer(alert: ContactAlert) {
        val now = clock.nowNanos()
        val startAt = contactStartNanos(gate, now)
        if (startAt <= now) playContact(alert, now) else deferred.playAt(alert, startAt)
    }

    private fun playContact(alert: ContactAlert, nowNanos: Long) {
        hapticFor(alert)?.let(haptics::play)
        records.record(vibrationStartedRecord(alert, nowNanos, startNanos))
    }

    private fun trackEliminated(input: SessionInput) {
        if (input is SessionInput.ModeChanged) eliminated = input.eliminated
    }

    private fun replayDeferred(input: SessionInput) {
        if (input is SessionInput.PlayDeferred && !eliminated) playOrDefer(input.alert)
    }

    private fun publishSceneOnTick(input: SessionInput) {
        if (input is SessionInput.Tick) scenes.publish(pipeline.scene(input.nowNanos))
    }

    private fun flushOnRequest(input: SessionInput) {
        if (input == SessionInput.Flush) records.flush()
    }
}
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/ScenePacing.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.session

import io.github.santiquiroz.blindside.wear.settings.ScreenMode

const val VISTA_FRAME_MS = 33L
const val SIGILO_FRAME_MS = 100L
const val IDLE_TICK_POLL_MS = 500L

// Spec §5.4: ambient hides contacts and refreshes about once a minute, so ticking would only burn battery.
fun scenePeriodMs(radarVisible: Boolean, mode: ScreenMode, ambient: Boolean): Long? = when {
    !radarVisible || ambient -> null
    mode == ScreenMode.VISTA -> VISTA_FRAME_MS
    else -> SIGILO_FRAME_MS
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :wear-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.wear.session.*" --tests "io.github.santiquiroz.blindside.wear.haptics.HapticGateTest"`
Expected: PASS (22 tests: 14 engine, 4 pacing, 4 gate).

- [ ] **Step 5: Commit**

```bash
git add watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/session watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/haptics/HapticGate.kt watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/haptics/HapticGateTest.kt
git commit -m "feat: motor de sesión que graba, alimenta radar-core, vibra sin pisar el zumbido de sistema y publica la escena"
```

---

### Task 6: Watch sensors (gravity with fallback, gyro witness, step detector)

Spec §5.2 `sensors` (wake-up variant, bounded `maxReportLatencyUs`, low-pass fallback), §8 (no `TYPE_GRAVITY`, `ACTIVITY_RECOGNITION` denied), contracts (`onWatchGyro` in rad/s; gravity recorded at ≤ 10 Hz).

**Files:**
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/sensors/SensorMath.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/sensors/WatchSensors.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/sensors/SensorMathTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `data class Vec3(x: Float, y: Float, z: Float)`, `enum class GravitySource { GRAVITY_SENSOR, ACCELEROMETER_LOW_PASS, NONE }`
  - `const val GRAVITY_MIN_INTERVAL_NANOS = 100_000_000L`, `GYRO_MIN_INTERVAL_NANOS = 100_000_000L`, `ACCEL_LOW_PASS_TAU_NANOS = 300_000_000L`
  - `fun <T : Any> preferWakeUp(wakeUp: T?, regular: T?): T?`
  - `fun passesGate(lastPassedNanos: Long?, eventNanos: Long, minIntervalNanos: Long): Boolean`
  - `fun lowPass(previous: Vec3?, sample: Vec3, dtNanos: Long, tauNanos: Long): Vec3`
  - `fun gravitySourceFor(hasGravity: Boolean, hasAccelerometer: Boolean): GravitySource`
  - `interface WatchSensorListener { onGravity(x, y, z, eventNanos); onGyro(x, y, z, eventNanos); onStep(eventNanos) }`
  - `data class SensorAvailability(gravity: GravitySource, gyro: Boolean, steps: Boolean)`
  - `class WatchSensors(sensorManager: SensorManager, listener: WatchSensorListener) { fun start(stepsAllowed: Boolean): SensorAvailability; fun stop() }`

Rates:
- Gravity and the gyro are requested at 20 Hz and gated to ≤ 10 Hz (spec §5.2: "gravedad y giroscopio del reloj a ≤ 10 Hz", so the recording stays at ~5 MB each in 5 h, spec §6.10).
- 10 Hz still gives the belt-bias witness 20 samples per 2 s window, enough to see |ω| > 3 °/s.
- Batching is limited to 200 ms (`maxReportLatencyUs`).
- The step detector is unbatched, because its own latency is already up to 2 s.

- [ ] **Step 1: Write the failing test**

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/sensors/SensorMathTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.sensors

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SensorMathTest {
    @Test
    fun `the wake-up variant wins when it exists`() {
        assertEquals("wake", preferWakeUp("wake", "plain"))
        assertEquals("plain", preferWakeUp(null, "plain"))
        assertNull(preferWakeUp<String>(null, null))
    }

    @Test
    fun `the first event always passes the rate gate`() {
        assertTrue(passesGate(null, 5L, GRAVITY_MIN_INTERVAL_NANOS))
    }

    @Test
    fun `events closer than the minimum interval are dropped`() {
        assertFalse(passesGate(0L, 50_000_000L, GRAVITY_MIN_INTERVAL_NANOS))
        assertTrue(passesGate(0L, 100_000_000L, GRAVITY_MIN_INTERVAL_NANOS))
    }

    @Test
    fun `gravity and gyro are gated to at most ten hertz`() {
        assertEquals(100_000_000L, GRAVITY_MIN_INTERVAL_NANOS)
        assertEquals(100_000_000L, GYRO_MIN_INTERVAL_NANOS)
    }

    @Test
    fun `the low pass starts at the first sample`() {
        val sample = Vec3(1f, 2f, 9.8f)
        assertEquals(sample, lowPass(null, sample, 0L, ACCEL_LOW_PASS_TAU_NANOS))
    }

    @Test
    fun `the low pass moves halfway when dt equals tau`() {
        val result = lowPass(Vec3(0f, 0f, 0f), Vec3(2f, 4f, 6f), 300_000_000L, 300_000_000L)
        assertEquals(Vec3(1f, 2f, 3f), result)
    }

    @Test
    fun `an out of order sample leaves the filter unchanged`() {
        val previous = Vec3(0f, 0f, 9.8f)
        assertEquals(previous, lowPass(previous, Vec3(5f, 5f, 5f), -1_000L, ACCEL_LOW_PASS_TAU_NANOS))
    }

    @Test
    fun `gravity source prefers the real sensor and falls back to the accelerometer`() {
        assertEquals(GravitySource.GRAVITY_SENSOR, gravitySourceFor(hasGravity = true, hasAccelerometer = true))
        assertEquals(GravitySource.ACCELEROMETER_LOW_PASS, gravitySourceFor(hasGravity = false, hasAccelerometer = true))
        assertEquals(GravitySource.NONE, gravitySourceFor(hasGravity = false, hasAccelerometer = false))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :wear-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.wear.sensors.*"`
Expected: FAIL at compilation with `Unresolved reference 'preferWakeUp'`.

- [ ] **Step 3: Write the implementation**

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/sensors/SensorMath.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.sensors

data class Vec3(val x: Float, val y: Float, val z: Float)

enum class GravitySource { GRAVITY_SENSOR, ACCELEROMETER_LOW_PASS, NONE }

const val GRAVITY_MIN_INTERVAL_NANOS = 100_000_000L
const val GYRO_MIN_INTERVAL_NANOS = 100_000_000L
const val ACCEL_LOW_PASS_TAU_NANOS = 300_000_000L

fun <T : Any> preferWakeUp(wakeUp: T?, regular: T?): T? = wakeUp ?: regular

fun passesGate(lastPassedNanos: Long?, eventNanos: Long, minIntervalNanos: Long): Boolean =
    lastPassedNanos == null || eventNanos - lastPassedNanos >= minIntervalNanos

fun lowPass(previous: Vec3?, sample: Vec3, dtNanos: Long, tauNanos: Long): Vec3 {
    if (previous == null) return sample
    val dt = dtNanos.coerceAtLeast(0L).toFloat()
    val alpha = dt / (tauNanos + dt)
    return Vec3(blend(previous.x, sample.x, alpha), blend(previous.y, sample.y, alpha), blend(previous.z, sample.z, alpha))
}

fun gravitySourceFor(hasGravity: Boolean, hasAccelerometer: Boolean): GravitySource = when {
    hasGravity -> GravitySource.GRAVITY_SENSOR
    hasAccelerometer -> GravitySource.ACCELEROMETER_LOW_PASS
    else -> GravitySource.NONE
}

private fun blend(from: Float, to: Float, alpha: Float): Float = from + alpha * (to - from)
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/sensors/WatchSensors.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

interface WatchSensorListener {
    fun onGravity(x: Float, y: Float, z: Float, eventNanos: Long)
    fun onGyro(x: Float, y: Float, z: Float, eventNanos: Long)
    fun onStep(eventNanos: Long)
}

data class SensorAvailability(val gravity: GravitySource, val gyro: Boolean, val steps: Boolean)

class WatchSensors(
    private val sensorManager: SensorManager,
    private val listener: WatchSensorListener,
) : SensorEventListener {
    private var lastGravityNanos: Long? = null
    private var lastGyroNanos: Long? = null
    private var lastAccelNanos: Long? = null
    private var filteredAccel: Vec3? = null

    fun start(stepsAllowed: Boolean): SensorAvailability {
        val gravity = sensorOf(Sensor.TYPE_GRAVITY)
        val accelerometer = if (gravity == null) sensorOf(Sensor.TYPE_ACCELEROMETER) else null
        val gyro = sensorOf(Sensor.TYPE_GYROSCOPE)
        val steps = if (stepsAllowed) sensorOf(Sensor.TYPE_STEP_DETECTOR) else null
        listOfNotNull(gravity, accelerometer, gyro).forEach { register(it, MOTION_PERIOD_US, MAX_REPORT_LATENCY_US) }
        steps?.let { register(it, SensorManager.SENSOR_DELAY_NORMAL, NO_BATCHING_US) }
        return SensorAvailability(gravitySourceFor(gravity != null, accelerometer != null), gyro != null, steps != null)
    }

    fun stop() = sensorManager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> emitGravity(vectorOf(event), event.timestamp)
            Sensor.TYPE_ACCELEROMETER -> onAccelerometer(vectorOf(event), event.timestamp)
            Sensor.TYPE_GYROSCOPE -> emitGyro(vectorOf(event), event.timestamp)
            Sensor.TYPE_STEP_DETECTOR -> listener.onStep(event.timestamp)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun onAccelerometer(sample: Vec3, eventNanos: Long) {
        val dt = lastAccelNanos?.let { eventNanos - it } ?: 0L
        lastAccelNanos = eventNanos
        val filtered = lowPass(filteredAccel, sample, dt, ACCEL_LOW_PASS_TAU_NANOS)
        filteredAccel = filtered
        emitGravity(filtered, eventNanos)
    }

    private fun emitGravity(value: Vec3, eventNanos: Long) {
        if (!passesGate(lastGravityNanos, eventNanos, GRAVITY_MIN_INTERVAL_NANOS)) return
        lastGravityNanos = eventNanos
        listener.onGravity(value.x, value.y, value.z, eventNanos)
    }

    private fun emitGyro(value: Vec3, eventNanos: Long) {
        if (!passesGate(lastGyroNanos, eventNanos, GYRO_MIN_INTERVAL_NANOS)) return
        lastGyroNanos = eventNanos
        listener.onGyro(value.x, value.y, value.z, eventNanos)
    }

    private fun sensorOf(type: Int): Sensor? =
        preferWakeUp(sensorManager.getDefaultSensor(type, true), sensorManager.getDefaultSensor(type))

    private fun register(sensor: Sensor, periodUs: Int, maxLatencyUs: Int) {
        sensorManager.registerListener(this, sensor, periodUs, maxLatencyUs)
    }

    private companion object {
        const val MOTION_PERIOD_US = 50_000
        const val MAX_REPORT_LATENCY_US = 200_000
        const val NO_BATCHING_US = 0
    }
}

private fun vectorOf(event: SensorEvent) = Vec3(event.values[0], event.values[1], event.values[2])
```

- [ ] **Step 4: Run the test to verify it passes, then build**

Run: `./gradlew :wear-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.wear.sensors.*"`
Expected: PASS (8 tests).

Run: `./gradlew :wear-app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/sensors watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/sensors
git commit -m "feat: sensores del reloj con variante wake-up, pasabajos de respaldo y límite de tasa"
```

---

### Task 7: BLE decisions as pure functions (op queue, reconnect policy, scan limiter, link reports, bond health, MTU)

Spec §5.2 `ble`:
- serial GATT queue: discover → MTU → `info` → CCCD, the CCCD after `onMtuChanged` or 2 s;
- the `info` read is the first encrypted operation, so it waits up to 60 s for pairing;
- MTU < 247: reconnect once, then fail;
- bond lost after 2 failures in a row;
- HIGH only during setup, pending `autoConnect`, direct attempt after 10 s, scan only with a filter, ≤ 4 `startScan` per 30 s.

Spec §8 adds: link lost gives **one** system vibration; MTU; lost bond. Contracts: UUIDs, `04 <0/1>` SESSION_ACTIVE, the `mtu` field of `info`.

**Files:**
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ble/BleIds.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ble/GattOps.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ble/ReconnectPolicy.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ble/ScanLimiter.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ble/LinkState.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ble/BondHealth.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ble/MtuCheck.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ble/BleIdsTest.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ble/GattOpsTest.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ble/ReconnectPolicyTest.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ble/ScanLimiterTest.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ble/LinkStateTest.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ble/BondHealthTest.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ble/MtuCheckTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `val SERVICE_UUID`, `STREAM_UUID`, `INFO_UUID`, `CONTROL_UUID`, `CCCD_UUID: UUID`, `val REQUIRED_CHARACTERISTICS: List<UUID>`
  - `const val REQUESTED_MTU = 517`, `BLINDSIDE_NAME_PREFIX = "Blindside-"`
  - `fun sessionActiveCommand(active: Boolean): ByteArray`, `fun isBlindsideName(name: String?): Boolean`
  - `sealed interface GattOp { DiscoverServices; RequestMtu(mtu); ReadInfo; EnableStreamNotify; WriteSessionActive(active); ReadRssi }`
  - `const val GATT_SUCCESS_STATUS = 0`, `LOCAL_FAILURE_STATUS = -1`, `MTU_WAIT_MS = 2_000L`, `PAIRING_WAIT_MS = 60_000L`, `GATT_OP_TIMEOUT_MS = 5_000L`, `val BOND_FAILURE_STATUSES: Set<Int>` (5, 15, 137)
  - `data class OpQueue(inFlight: GattOp? = null, pending: List<GattOp> = emptyList())`, `data class QueueStep(queue: OpQueue, start: GattOp?)`
  - `fun enqueue(queue, op): QueueStep`, `fun completeInFlight(queue): QueueStep`, `fun shouldQueueRssi(queue): Boolean`
  - `fun setupOpsAfterDiscovery(): List<GattOp>`, `fun timeoutMsFor(op): Long`, `enum class SetupEffect { REPORT_LINK_UP, LOWER_PRIORITY, DISCONNECT }`, `fun effectsAfter(op: GattOp, status: Int): List<SetupEffect>`
  - `data class ReconnectState(lostAtMs: Long, lastDirectAtMs: Long? = null, lastScanAtMs: Long? = null)`, `enum class ReconnectAction { DIRECT_CONNECT, SCAN }`
  - `fun nextReconnectAction(state, nowMs): ReconnectAction?`, `fun recordAction(state, action, nowMs): ReconnectState`
  - `data class ScanHistory(startsMs: List<Long> = emptyList())`, `data class ScanPermit(allowed: Boolean, history: ScanHistory)`, `fun tryStartScan(history, nowMs): ScanPermit`
  - `enum class BleStatus { IDLE, BLUETOOTH_OFF, SEARCHING, PAIRING, PAIRING_FAILED, CONNECTING, STREAMING, RECONNECTING, BOND_LOST, MTU_TOO_LOW }`, `fun needsRetry(status: BleStatus): Boolean`
  - `enum class LinkEvent { STREAM_READY, DISCONNECTED, BLUETOOTH_OFF, HALTED }`, `data class LinkReporter(up: Boolean = false)`, `data class LinkReport(reporter: LinkReporter, change: Boolean?)`, `fun report(reporter: LinkReporter, event: LinkEvent): LinkReport`, `fun linkTransition(previouslyUp: Boolean, nowUp: Boolean): Boolean?`
  - `data class ConnectionAttempt(pairing: Boolean = false, connected: Boolean = false, subscribed: Boolean = false, endedByApp: Boolean = false)`, `enum class AttemptVerdict { SUBSCRIBED, PAIRING_FAILED, BOND_SUSPECT, IGNORED }`, `fun attemptVerdict(attempt): AttemptVerdict`
  - `data class BondHealth(consecutiveSuspects: Int = 0) { val isLost: Boolean }`, `const val BOND_LOST_AFTER = 2`, `fun nextBondHealth(health, verdict): BondHealth`
  - `const val MIN_STREAM_MTU = 247`, `enum class MtuAction { OK, RETRY_ONCE, FAIL }`, `fun mtuAction(mtu: Int?, retried: Boolean): MtuAction`, `fun effectiveMtu(callbackMtu: Int?, infoMtu: Int?): Int?`, `fun mtuFromInfo(json: String): Int?`

Reconnection timing:
- The pending `autoConnect` connection stays up the whole time.
- After 10 s without a link, try a direct connection, and repeat every 20 s. A direct attempt takes ~10 s on Samsung and then returns to the pending connection.
- After 30 s, run a filtered scan, and repeat every 30 s.
- When both are due, the scan wins, because it is the rarer one.

Pairing and bond health (spec §5.2):
- A connection attempt is *pairing* when its device had no bond when the attempt was opened. Its `info` read makes Android pair. If that attempt connects and then ends before the CCCD write, pairing failed: "clave incorrecta o ventana cerrada".
- For a bonded device, an attempt that connects and then ends before the CCCD write is a *bond suspect*. Every signal the spec lists ends that way: the `info` read or the CCCD write returning GATT 5, 15 or 137 (a bond-failure status on the `info` read ends the setup), or the link dropping before the CCCD write.
- Two suspects in a row mean the bond is lost. A subscription resets the count. An attempt that never connected, or that the app ended itself (the MTU retry), does not count.

MTU (spec §5.2, §8): the effective MTU is the smaller of the `onMtuChanged` value and the `mtu` field of `info`. Below 247, the link reconnects once and then fails. When neither source answered, the MTU is not judged: the ESP32 refuses to notify on its own, and the link simply stays down under the normal reconnection.

- [ ] **Step 1: Write the failing tests**

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ble/BleIdsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BleIdsTest {
    @Test
    fun `session active writes command four with the flag`() {
        assertEquals(listOf<Byte>(4, 1), sessionActiveCommand(true).toList())
        assertEquals(listOf<Byte>(4, 0), sessionActiveCommand(false).toList())
    }

    @Test
    fun `belt names start with Blindside dash`() {
        assertTrue(isBlindsideName("Blindside-3F2A"))
        assertFalse(isBlindsideName("Galaxy Buds"))
        assertFalse(isBlindsideName(null))
    }

    @Test
    fun `uuids match the contracts`() {
        assertEquals("569f3867-024f-4498-a979-90a762ad3593", SERVICE_UUID.toString())
        assertEquals("37869398-ecc2-4915-90a1-13d39d708ad5", STREAM_UUID.toString())
        assertEquals("278b9369-d8ac-4eda-868b-7bfd0dea5dc6", INFO_UUID.toString())
        assertEquals("725c9a6e-0c7b-45d2-bef6-48c03be7c092", CONTROL_UUID.toString())
        assertEquals("00002902-0000-1000-8000-00805f9b34fb", CCCD_UUID.toString())
    }
}
```

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ble/GattOpsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GattOpsTest {
    private val disconnect = listOf(SetupEffect.DISCONNECT)
    private val nothing = emptyList<SetupEffect>()

    @Test
    fun `setup requests the mtu before enabling notifications and activates the session last`() {
        val expected = listOf(
            GattOp.RequestMtu(REQUESTED_MTU),
            GattOp.ReadInfo,
            GattOp.EnableStreamNotify,
            GattOp.WriteSessionActive(true),
        )
        assertEquals(expected, setupOpsAfterDiscovery())
    }

    @Test
    fun `the mtu wait is two seconds and other ops five`() {
        assertEquals(2_000L, timeoutMsFor(GattOp.RequestMtu(REQUESTED_MTU)))
        assertEquals(5_000L, timeoutMsFor(GattOp.EnableStreamNotify))
        assertEquals(5_000L, timeoutMsFor(GattOp.WriteSessionActive(true)))
    }

    @Test
    fun `the info read waits for the whole pairing window`() {
        assertEquals(60_000L, timeoutMsFor(GattOp.ReadInfo))
    }

    @Test
    fun `a missing mtu callback does not disconnect`() {
        assertEquals(nothing, effectsAfter(GattOp.RequestMtu(REQUESTED_MTU), LOCAL_FAILURE_STATUS))
    }

    @Test
    fun `failed discovery or subscription disconnects`() {
        assertEquals(disconnect, effectsAfter(GattOp.DiscoverServices, LOCAL_FAILURE_STATUS))
        assertEquals(disconnect, effectsAfter(GattOp.EnableStreamNotify, 5))
    }

    @Test
    fun `a bond failure status on the info read ends the setup`() {
        BOND_FAILURE_STATUSES.forEach { status -> assertEquals(disconnect, effectsAfter(GattOp.ReadInfo, status)) }
        assertEquals(setOf(5, 15, 137), BOND_FAILURE_STATUSES)
    }

    @Test
    fun `an info read failing for another reason keeps going`() {
        assertEquals(nothing, effectsAfter(GattOp.ReadInfo, LOCAL_FAILURE_STATUS))
        assertEquals(nothing, effectsAfter(GattOp.ReadInfo, 133))
    }

    @Test
    fun `a failed control write still lowers the priority`() {
        assertEquals(listOf(SetupEffect.LOWER_PRIORITY), effectsAfter(GattOp.WriteSessionActive(true), 133))
    }

    @Test
    fun `subscribing reports the link up`() {
        assertEquals(listOf(SetupEffect.REPORT_LINK_UP), effectsAfter(GattOp.EnableStreamNotify, GATT_SUCCESS_STATUS))
    }

    @Test
    fun `activating the session lowers the connection priority`() {
        assertEquals(listOf(SetupEffect.LOWER_PRIORITY), effectsAfter(GattOp.WriteSessionActive(true), GATT_SUCCESS_STATUS))
        assertEquals(nothing, effectsAfter(GattOp.WriteSessionActive(false), GATT_SUCCESS_STATUS))
    }

    @Test
    fun `the first op starts at once and later ops wait`() {
        val first = enqueue(OpQueue(), GattOp.DiscoverServices)
        assertEquals(GattOp.DiscoverServices, first.start)
        val second = enqueue(first.queue, GattOp.ReadRssi)
        assertNull(second.start)
        assertEquals(listOf(GattOp.ReadRssi), second.queue.pending)
    }

    @Test
    fun `completing an op starts the next one in order`() {
        val queued = listOf(GattOp.RequestMtu(REQUESTED_MTU), GattOp.ReadInfo, GattOp.EnableStreamNotify)
            .fold(OpQueue()) { queue, op -> enqueue(queue, op).queue }
        val afterFirst = completeInFlight(queued)
        assertEquals(GattOp.ReadInfo, afterFirst.start)
        val afterSecond = completeInFlight(afterFirst.queue)
        assertEquals(GattOp.EnableStreamNotify, afterSecond.start)
        val idle = completeInFlight(afterSecond.queue)
        assertNull(idle.start)
        assertEquals(OpQueue(), idle.queue)
    }

    @Test
    fun `rssi reads never pile up behind a stuck op`() {
        val stuck = enqueue(OpQueue(), GattOp.EnableStreamNotify).queue
        assertTrue(shouldQueueRssi(stuck))
        assertFalse(shouldQueueRssi(enqueue(stuck, GattOp.ReadRssi).queue))
        assertFalse(shouldQueueRssi(enqueue(OpQueue(), GattOp.ReadRssi).queue))
    }
}
```

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ble/ReconnectPolicyTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReconnectPolicyTest {
    private val lost = ReconnectState(lostAtMs = 0L)

    @Test
    fun `the pending connection is left alone for the first ten seconds`() {
        assertNull(nextReconnectAction(lost, 9_999L))
    }

    @Test
    fun `a direct attempt follows after ten seconds`() {
        assertEquals(ReconnectAction.DIRECT_CONNECT, nextReconnectAction(lost, 10_000L))
    }

    @Test
    fun `direct attempts are spaced twenty seconds apart`() {
        val tried = recordAction(lost, ReconnectAction.DIRECT_CONNECT, 10_000L)
        assertNull(nextReconnectAction(tried, 29_999L))
    }

    @Test
    fun `a filtered scan is tried after thirty seconds`() {
        val tried = recordAction(lost, ReconnectAction.DIRECT_CONNECT, 10_000L)
        assertEquals(ReconnectAction.SCAN, nextReconnectAction(tried, 30_000L))
    }

    @Test
    fun `scans are spaced thirty seconds apart`() {
        val state = ReconnectState(lostAtMs = 0L, lastDirectAtMs = 50_000L, lastScanAtMs = 30_000L)
        assertNull(nextReconnectAction(state, 59_999L))
        assertEquals(ReconnectAction.SCAN, nextReconnectAction(state, 60_000L))
    }

    @Test
    fun `recording an action only touches its own timestamp`() {
        val scanned = recordAction(lost, ReconnectAction.SCAN, 31_000L)
        assertEquals(ReconnectState(lostAtMs = 0L, lastDirectAtMs = null, lastScanAtMs = 31_000L), scanned)
    }
}
```

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ble/ScanLimiterTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScanLimiterTest {
    private val full = ScanHistory(listOf(0L, 1_000L, 2_000L, 3_000L))

    @Test
    fun `four scans fit in thirty seconds and the fifth is refused`() {
        val history = (0 until 4).fold(ScanHistory()) { current, i ->
            val permit = tryStartScan(current, i * 1_000L)
            assertTrue(permit.allowed)
            permit.history
        }
        assertFalse(tryStartScan(history, 4_000L).allowed)
    }

    @Test
    fun `a slot frees up when the oldest scan leaves the window`() {
        assertFalse(tryStartScan(full, 29_999L).allowed)
        assertTrue(tryStartScan(full, 30_000L).allowed)
    }

    @Test
    fun `a refused scan does not use a slot`() {
        assertEquals(4, tryStartScan(full, 10_000L).history.startsMs.size)
    }
}
```

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ble/LinkStateTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LinkStateTest {
    @Test
    fun `only changes are reported`() {
        assertNull(linkTransition(previouslyUp = false, nowUp = false))
        assertNull(linkTransition(previouslyUp = true, nowUp = true))
        assertEquals(true, linkTransition(previouslyUp = false, nowUp = true))
        assertEquals(false, linkTransition(previouslyUp = true, nowUp = false))
    }

    @Test
    fun `one outage reports link down exactly once`() {
        val events = listOf(
            LinkEvent.DISCONNECTED,
            LinkEvent.DISCONNECTED,
            LinkEvent.DISCONNECTED,
            LinkEvent.BLUETOOTH_OFF,
            LinkEvent.DISCONNECTED,
            LinkEvent.STREAM_READY,
        )
        assertEquals(listOf(false, true), changesFor(LinkReporter(up = true), events))
    }

    @Test
    fun `the first subscription reports the link up and a halt reports it down`() {
        assertEquals(listOf(true, false), changesFor(LinkReporter(), listOf(LinkEvent.STREAM_READY, LinkEvent.HALTED)))
    }

    @Test
    fun `halted links offer a retry`() {
        listOf(BleStatus.PAIRING_FAILED, BleStatus.BOND_LOST, BleStatus.MTU_TOO_LOW).forEach { assertTrue(needsRetry(it)) }
        listOf(BleStatus.STREAMING, BleStatus.RECONNECTING, BleStatus.SEARCHING).forEach { assertFalse(needsRetry(it)) }
    }

    private fun changesFor(start: LinkReporter, events: List<LinkEvent>): List<Boolean> =
        events.fold(start to emptyList<Boolean>()) { (reporter, changes), event ->
            val step = report(reporter, event)
            step.reporter to (changes + listOfNotNull(step.change))
        }.second
}
```

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ble/BondHealthTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BondHealthTest {
    private val failedSetup = ConnectionAttempt(connected = true)
    private val subscribed = ConnectionAttempt(connected = true, subscribed = true)

    private fun after(vararg attempts: ConnectionAttempt): BondHealth =
        attempts.fold(BondHealth()) { health, attempt -> nextBondHealth(health, attemptVerdict(attempt)) }

    @Test
    fun `two failed setups in a row mean the bond is lost`() {
        assertFalse(after(failedSetup).isLost)
        assertTrue(after(failedSetup, failedSetup).isLost)
    }

    @Test
    fun `a subscription in between resets the count`() {
        assertFalse(after(failedSetup, subscribed, failedSetup).isLost)
    }

    @Test
    fun `a disconnect after the stream subscription does not count`() {
        assertEquals(AttemptVerdict.SUBSCRIBED, attemptVerdict(subscribed))
        assertEquals(BondHealth(), nextBondHealth(BondHealth(1), attemptVerdict(subscribed)))
    }

    @Test
    fun `a connection that never came up does not count`() {
        assertEquals(AttemptVerdict.IGNORED, attemptVerdict(ConnectionAttempt()))
        assertFalse(after(failedSetup, ConnectionAttempt(), ConnectionAttempt()).isLost)
    }

    @Test
    fun `a failed setup while pairing is a pairing failure, not a lost bond`() {
        val pairing = ConnectionAttempt(pairing = true, connected = true)
        assertEquals(AttemptVerdict.PAIRING_FAILED, attemptVerdict(pairing))
        assertFalse(after(pairing, pairing).isLost)
    }

    @Test
    fun `a connection the app ended itself does not count`() {
        assertEquals(AttemptVerdict.IGNORED, attemptVerdict(failedSetup.copy(endedByApp = true)))
    }
}
```

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ble/MtuCheckTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MtuCheckTest {
    @Test
    fun `an mtu of 247 or more streams`() {
        assertEquals(MtuAction.OK, mtuAction(255, retried = false))
        assertEquals(MtuAction.OK, mtuAction(247, retried = true))
    }

    @Test
    fun `a low mtu reconnects once and then fails`() {
        assertEquals(MtuAction.RETRY_ONCE, mtuAction(185, retried = false))
        assertEquals(MtuAction.FAIL, mtuAction(185, retried = true))
    }

    @Test
    fun `an unknown mtu is not judged`() {
        assertEquals(MtuAction.OK, mtuAction(null, retried = false))
    }

    @Test
    fun `the smaller of the two mtu sources wins`() {
        assertEquals(185, effectiveMtu(callbackMtu = 255, infoMtu = 185))
        assertEquals(255, effectiveMtu(callbackMtu = null, infoMtu = 255))
        assertNull(effectiveMtu(callbackMtu = null, infoMtu = null))
    }

    @Test
    fun `the mtu field is read from the info json`() {
        assertEquals(255, mtuFromInfo("""{"proto":1,"fw":"0.1.0","mtu":255,"radars":[]}"""))
        assertNull(mtuFromInfo("""{"proto":1}"""))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :wear-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.wear.ble.*"`
Expected: FAIL at compilation with `Unresolved reference 'sessionActiveCommand'`.

- [ ] **Step 3: Write the implementation**

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ble/BleIds.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ble

import java.util.UUID

val SERVICE_UUID: UUID = UUID.fromString("569f3867-024f-4498-a979-90a762ad3593")
val STREAM_UUID: UUID = UUID.fromString("37869398-ecc2-4915-90a1-13d39d708ad5")
val INFO_UUID: UUID = UUID.fromString("278b9369-d8ac-4eda-868b-7bfd0dea5dc6")
val CONTROL_UUID: UUID = UUID.fromString("725c9a6e-0c7b-45d2-bef6-48c03be7c092")
val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
val REQUIRED_CHARACTERISTICS: List<UUID> = listOf(STREAM_UUID, INFO_UUID, CONTROL_UUID)

const val REQUESTED_MTU = 517
const val BLINDSIDE_NAME_PREFIX = "Blindside-"

private const val CMD_SESSION_ACTIVE: Byte = 0x04

fun sessionActiveCommand(active: Boolean): ByteArray = byteArrayOf(CMD_SESSION_ACTIVE, if (active) 1 else 0)

fun isBlindsideName(name: String?): Boolean = name?.startsWith(BLINDSIDE_NAME_PREFIX) == true
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ble/GattOps.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ble

sealed interface GattOp {
    data object DiscoverServices : GattOp
    data class RequestMtu(val mtu: Int) : GattOp
    data object ReadInfo : GattOp
    data object EnableStreamNotify : GattOp
    data class WriteSessionActive(val active: Boolean) : GattOp
    data object ReadRssi : GattOp
}

enum class SetupEffect { REPORT_LINK_UP, LOWER_PRIORITY, DISCONNECT }

data class OpQueue(val inFlight: GattOp? = null, val pending: List<GattOp> = emptyList())

data class QueueStep(val queue: OpQueue, val start: GattOp?)

const val GATT_SUCCESS_STATUS = 0
const val LOCAL_FAILURE_STATUS = -1
const val MTU_WAIT_MS = 2_000L
const val PAIRING_WAIT_MS = 60_000L
const val GATT_OP_TIMEOUT_MS = 5_000L

// INSUFFICIENT_AUTHENTICATION, INSUFFICIENT_ENCRYPTION and GATT_AUTH_FAIL: the link could not be encrypted with the bond.
val BOND_FAILURE_STATUSES: Set<Int> = setOf(5, 15, 137)

fun setupOpsAfterDiscovery(): List<GattOp> = listOf(
    GattOp.RequestMtu(REQUESTED_MTU),
    GattOp.ReadInfo,
    GattOp.EnableStreamNotify,
    GattOp.WriteSessionActive(true),
)

// Spec §5.2: the info read is the first encrypted operation, so it may sit behind the passkey dialog for the whole window.
fun timeoutMsFor(op: GattOp): Long = when (op) {
    is GattOp.RequestMtu -> MTU_WAIT_MS
    GattOp.ReadInfo -> PAIRING_WAIT_MS
    else -> GATT_OP_TIMEOUT_MS
}

fun effectsAfter(op: GattOp, status: Int): List<SetupEffect> = when {
    status != GATT_SUCCESS_STATUS && endsSetupOnFailure(op, status) -> listOf(SetupEffect.DISCONNECT)
    op == GattOp.EnableStreamNotify -> listOf(SetupEffect.REPORT_LINK_UP)
    op == GattOp.WriteSessionActive(true) -> listOf(SetupEffect.LOWER_PRIORITY)
    else -> emptyList()
}

fun enqueue(queue: OpQueue, op: GattOp): QueueStep =
    if (queue.inFlight == null) QueueStep(OpQueue(op, queue.pending), op)
    else QueueStep(queue.copy(pending = queue.pending + op), null)

fun completeInFlight(queue: OpQueue): QueueStep {
    val next = queue.pending.firstOrNull()
    return QueueStep(OpQueue(next, queue.pending.drop(1)), next)
}

fun shouldQueueRssi(queue: OpQueue): Boolean =
    queue.inFlight != GattOp.ReadRssi && GattOp.ReadRssi !in queue.pending

private fun endsSetupOnFailure(op: GattOp, status: Int): Boolean =
    op == GattOp.DiscoverServices || op == GattOp.EnableStreamNotify || isBondFailureOnInfo(op, status)

private fun isBondFailureOnInfo(op: GattOp, status: Int): Boolean = op == GattOp.ReadInfo && status in BOND_FAILURE_STATUSES
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ble/ReconnectPolicy.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ble

data class ReconnectState(val lostAtMs: Long, val lastDirectAtMs: Long? = null, val lastScanAtMs: Long? = null)

enum class ReconnectAction { DIRECT_CONNECT, SCAN }

const val DIRECT_ATTEMPT_AFTER_MS = 10_000L
const val DIRECT_ATTEMPT_EVERY_MS = 20_000L
const val SCAN_AFTER_MS = 30_000L
const val SCAN_EVERY_MS = 30_000L

fun nextReconnectAction(state: ReconnectState, nowMs: Long): ReconnectAction? = when {
    isDue(state.lostAtMs, state.lastScanAtMs, nowMs, SCAN_AFTER_MS, SCAN_EVERY_MS) -> ReconnectAction.SCAN
    isDue(state.lostAtMs, state.lastDirectAtMs, nowMs, DIRECT_ATTEMPT_AFTER_MS, DIRECT_ATTEMPT_EVERY_MS) ->
        ReconnectAction.DIRECT_CONNECT
    else -> null
}

fun recordAction(state: ReconnectState, action: ReconnectAction, nowMs: Long): ReconnectState = when (action) {
    ReconnectAction.DIRECT_CONNECT -> state.copy(lastDirectAtMs = nowMs)
    ReconnectAction.SCAN -> state.copy(lastScanAtMs = nowMs)
}

private fun isDue(lostAtMs: Long, lastAtMs: Long?, nowMs: Long, afterMs: Long, everyMs: Long): Boolean =
    nowMs - lostAtMs >= afterMs && (lastAtMs == null || nowMs - lastAtMs >= everyMs)
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ble/ScanLimiter.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ble

data class ScanHistory(val startsMs: List<Long> = emptyList())

data class ScanPermit(val allowed: Boolean, val history: ScanHistory)

// Android silently drops the 5th startScan in 30 s; we stay one below that.
const val MAX_SCAN_STARTS = 4
const val SCAN_WINDOW_MS = 30_000L

fun tryStartScan(history: ScanHistory, nowMs: Long): ScanPermit {
    val recent = history.startsMs.filter { nowMs - it < SCAN_WINDOW_MS }
    val allowed = recent.size < MAX_SCAN_STARTS
    return ScanPermit(allowed, ScanHistory(if (allowed) recent + nowMs else recent))
}
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ble/LinkState.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ble

enum class BleStatus {
    IDLE,
    BLUETOOTH_OFF,
    SEARCHING,
    PAIRING,
    PAIRING_FAILED,
    CONNECTING,
    STREAMING,
    RECONNECTING,
    BOND_LOST,
    MTU_TOO_LOW,
}

enum class LinkEvent { STREAM_READY, DISCONNECTED, BLUETOOTH_OFF, HALTED }

data class LinkReporter(val up: Boolean = false)

data class LinkReport(val reporter: LinkReporter, val change: Boolean?)

private val HALTED_STATUSES = setOf(BleStatus.PAIRING_FAILED, BleStatus.BOND_LOST, BleStatus.MTU_TOO_LOW)

fun needsRetry(status: BleStatus): Boolean = status in HALTED_STATUSES

fun report(reporter: LinkReporter, event: LinkEvent): LinkReport {
    val nowUp = event == LinkEvent.STREAM_READY
    return LinkReport(LinkReporter(nowUp), linkTransition(reporter.up, nowUp))
}

fun linkTransition(previouslyUp: Boolean, nowUp: Boolean): Boolean? = if (previouslyUp == nowUp) null else nowUp
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ble/BondHealth.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ble

const val BOND_LOST_AFTER = 2

data class ConnectionAttempt(
    val pairing: Boolean = false,
    val connected: Boolean = false,
    val subscribed: Boolean = false,
    val endedByApp: Boolean = false,
)

enum class AttemptVerdict { SUBSCRIBED, PAIRING_FAILED, BOND_SUSPECT, IGNORED }

data class BondHealth(val consecutiveSuspects: Int = 0) {
    val isLost: Boolean get() = consecutiveSuspects >= BOND_LOST_AFTER
}

// Spec §5.2: with a lost bond every encrypted step fails, so the connection always ends before the CCCD write.
fun attemptVerdict(attempt: ConnectionAttempt): AttemptVerdict = when {
    attempt.subscribed -> AttemptVerdict.SUBSCRIBED
    !attempt.connected || attempt.endedByApp -> AttemptVerdict.IGNORED
    attempt.pairing -> AttemptVerdict.PAIRING_FAILED
    else -> AttemptVerdict.BOND_SUSPECT
}

fun nextBondHealth(health: BondHealth, verdict: AttemptVerdict): BondHealth = when (verdict) {
    AttemptVerdict.SUBSCRIBED -> BondHealth()
    AttemptVerdict.BOND_SUSPECT -> BondHealth(health.consecutiveSuspects + 1)
    AttemptVerdict.PAIRING_FAILED, AttemptVerdict.IGNORED -> health
}
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ble/MtuCheck.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ble

const val MIN_STREAM_MTU = 247

enum class MtuAction { OK, RETRY_ONCE, FAIL }

private val INFO_MTU = Regex("\"mtu\"\\s*:\\s*(\\d+)")

// Spec §4.2: the ESP32 refuses to notify below 247, so an unknown MTU is left to that rule instead of guessed.
fun mtuAction(mtu: Int?, retried: Boolean): MtuAction = when {
    mtu == null || mtu >= MIN_STREAM_MTU -> MtuAction.OK
    retried -> MtuAction.FAIL
    else -> MtuAction.RETRY_ONCE
}

fun effectiveMtu(callbackMtu: Int?, infoMtu: Int?): Int? = listOfNotNull(callbackMtu, infoMtu).minOrNull()

fun mtuFromInfo(json: String): Int? = INFO_MTU.find(json)?.groupValues?.get(1)?.toIntOrNull()
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :wear-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.wear.ble.*"`
Expected: PASS (40 tests).

- [ ] **Step 5: Commit**

```bash
git add watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ble watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ble
git commit -m "feat: reglas puras de BLE: cola GATT serial, reconexión, escaneo limitado, reporte de enlace, bond perdido y MTU"
```

---

### Task 8: BLE Android adapter (pairing through the `info` read, GATT setup, reconnection, halts)

Spec §5.2 `ble` (queue; pairing triggered by the first encrypted operation, never `createBond()`; bond lost; MTU insufficient; `onServiceChanged`; reconnection), §4.3 (pairing window, per-device passkey, bonded identity), §8 (link lost, MTU, lost bond, Bluetooth permission), §1 criterion 6 (data back ≤ 5 s after the ESP32 advertises again), §9 spike S12 (the read-triggered passkey dialog).

**Files:**
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ble/BeltListener.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ble/BeltGatt.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ble/BeltScanner.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ble/BeltLink.kt`

**Interfaces:**
- Consumes: everything from Task 7.
- Produces:
  - `interface BeltListener { onPacket(bytes, arrivalNanos); onBeltInfo(json, nowNanos); onRssi(dbm, nowNanos); onLinkChanged(connected, nowNanos); onStatus(status: BleStatus) }`
  - `interface BeltGattEvents { onConnected(source: BeltGatt); onStreamReady(source); onSetupFailed(source, op: GattOp, status: Int); onDisconnected(source, status: Int); acceptMtu(source, mtu: Int?): Boolean; onPacket(bytes, arrivalNanos); onInfo(source, json, nowNanos); onRssi(source, dbm, nowNanos) }`
  - `class BeltGatt(context: Context, device: BluetoothDevice, handler: Handler, events: BeltGattEvents) { val device; fun connect(autoConnect: Boolean); fun disconnect(); fun close(); fun requestRssi(); fun deactivateSession() }`
  - `class BeltLink(context: Context, listener: BeltListener, onBeltFound: (String) -> Unit) { fun start(savedAddress: String?); fun retry(); fun stop() }`

How the link behaves:
1. **Finding the belt.** Use the saved bonded address, or any bonded device named `Blindside-…`. If there is none, run a filtered scan (service UUID).
2. **Pairing (spec §5.2, spike S12).**
   - A scanned belt without a bond gets a direct connection (`autoConnect = false`) and the normal setup.
   - Its `info` read is the first encrypted operation. Android gets INSUFFICIENT_AUTHENTICATION/ENCRYPTION, pairs on its own and retries the read, which shows the system passkey dialog. Santiago types the 6 digits from the belt label.
   - The read waits up to 60 s, the length of the pairing window. `createBond()` is never called.
   - The address is saved at the first subscription, when the bond certainly exists.
   - If that attempt ends before the subscription, the status becomes `PAIRING_FAILED` ("Clave incorrecta o ventana cerrada") and the link waits for "Reintentar".
3. **Connecting.** `connectGatt(autoConnect = true, TRANSPORT_LE)`, a pending connection with no timeout.
4. **Setup.**
   - On connect, request priority HIGH and run discover → MTU 517 → read `info` → MTU verdict → enable `stream` notify → write `04 01`, one operation at a time. After the `04 01` write, drop to BALANCED.
   - Every setup result is logged (`adb logcat -s BeltGatt`), so S12 can see that the `control` write succeeds.
5. **MTU (spec §5.2, §8).**
   - Before the CCCD write, the smaller of the `onMtuChanged` value and the `mtu` field of `info` must be ≥ 247.
   - If it is not, the status shows `MTU_TOO_LOW` ("MTU insuficiente") and the link reconnects once, asking for 517 again. A second low MTU halts the link.
   - Mid-game the link is already down at that point: the screen stays gray, and the drop already gave its one system buzz.
   - At session start, the halt keeps the session open with the explanation and "Reintentar". No data flows and Santiago sees why, which is what spec §5.2 asks ("la sesión no arranca y la app lo explica").
6. **While streaming.** Read the RSSI every second through the same queue.
7. **Service changed (spec §5.2).** `onServiceChanged()` clears the queue and runs discovery again, which re-reads `info` and rewrites the CCCD and `04 01`.
8. **On disconnect.**
   - Report link down once (`LinkReporter`), then judge the attempt (Task 7).
   - A pairing failure halts. A second bond suspect in a row halts with `BOND_LOST` ("El cinturón olvidó este reloj…"). Anything else opens a new pending connection straight away.
   - The 1 s supervisor applies `nextReconnectAction`: a direct attempt gets 12 s before it falls back to the pending connection.
9. **Halts** (`PAIRING_FAILED`, `BOND_LOST`, `MTU_TOO_LOW`).
   - The connection is closed with no new `connectGatt`, scanning stops, and nothing reconnects until `retry()` (the "Reintentar" chip, Tasks 10b and 14).
   - The app cannot remove the bond itself (`removeBond()` is not public API). For `BOND_LOST`, Santiago forgets the belt in the watch's Bluetooth settings first.
10. **Stopping.** Write `04 00`, then disconnect and close after 500 ms.

Every event carries the `BeltGatt` that produced it, and `BeltLink` ignores events from a connection it has already replaced. A late `DISCONNECTED` from an old pending connection therefore cannot close the new one (Review Focus 3). All GATT calls and callbacks run on the main looper. Notifications are timestamped on arrival and forwarded straight from the binder thread.

- [ ] **Step 1: Create the listener and the single-connection class**

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ble/BeltListener.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ble

interface BeltListener {
    fun onPacket(bytes: ByteArray, arrivalNanos: Long)
    fun onBeltInfo(json: String, nowNanos: Long)
    fun onRssi(dbm: Int, nowNanos: Long)
    fun onLinkChanged(connected: Boolean, nowNanos: Long)
    fun onStatus(status: BleStatus)
}
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ble/BeltGatt.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import java.util.UUID

private const val TAG = "BeltGatt"

interface BeltGattEvents {
    fun onConnected(source: BeltGatt)
    fun onStreamReady(source: BeltGatt)
    fun onSetupFailed(source: BeltGatt, op: GattOp, status: Int)
    fun onDisconnected(source: BeltGatt, status: Int)
    fun acceptMtu(source: BeltGatt, mtu: Int?): Boolean
    fun onPacket(bytes: ByteArray, arrivalNanos: Long)
    fun onInfo(source: BeltGatt, json: String, nowNanos: Long)
    fun onRssi(source: BeltGatt, dbm: Int, nowNanos: Long)
}

@SuppressLint("MissingPermission")
class BeltGatt(
    private val context: Context,
    val device: BluetoothDevice,
    private val handler: Handler,
    private val events: BeltGattEvents,
) {
    @Volatile private var gatt: BluetoothGatt? = null
    private var queue = OpQueue()
    private var negotiatedMtu: Int? = null
    private var infoMtu: Int? = null
    private val timeoutToken = Any()

    fun connect(autoConnect: Boolean) {
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.disconnect()
    }

    fun close() {
        stopQueue()
        gatt?.close()
        gatt = null
    }

    fun requestRssi() {
        if (shouldQueueRssi(queue)) enqueueOp(GattOp.ReadRssi)
    }

    fun deactivateSession() = enqueueOp(GattOp.WriteSessionActive(false))

    private fun enqueueOp(op: GattOp) {
        val step = enqueue(queue, op)
        queue = step.queue
        step.start?.let(::execute)
    }

    private fun execute(op: GattOp) {
        if (subscriptionBlockedByMtu(op)) return stopQueue()
        handler.postAtTime({ finish(op, LOCAL_FAILURE_STATUS) }, timeoutToken, SystemClock.uptimeMillis() + timeoutMsFor(op))
        if (!start(op)) finish(op, LOCAL_FAILURE_STATUS)
    }

    // Spec §5.2: the ESP32 never notifies below MTU 247, so the CCCD write waits for the MTU verdict.
    private fun subscriptionBlockedByMtu(op: GattOp): Boolean =
        op == GattOp.EnableStreamNotify && !events.acceptMtu(this, effectiveMtu(negotiatedMtu, infoMtu))

    private fun start(op: GattOp): Boolean {
        val current = gatt ?: return false
        return when (op) {
            GattOp.DiscoverServices -> current.discoverServices()
            is GattOp.RequestMtu -> current.requestMtu(op.mtu)
            GattOp.ReadInfo -> characteristic(current, INFO_UUID)?.let { current.readCharacteristic(it) } ?: false
            GattOp.EnableStreamNotify -> enableStreamNotify(current)
            is GattOp.WriteSessionActive -> writeControl(current, sessionActiveCommand(op.active))
            GattOp.ReadRssi -> current.readRemoteRssi()
        }
    }

    private fun finish(op: GattOp, status: Int) {
        if (queue.inFlight != op) return
        handler.removeCallbacksAndMessages(timeoutToken)
        logResult(op, status)
        val effects = effectsAfter(op, status)
        if (SetupEffect.DISCONNECT in effects) return events.onSetupFailed(this, op, status)
        applyEffects(effects)
        advanceQueue()
    }

    private fun advanceQueue() {
        val step = completeInFlight(queue)
        queue = step.queue
        step.start?.let(::execute)
    }

    private fun stopQueue() {
        handler.removeCallbacksAndMessages(timeoutToken)
        queue = OpQueue()
    }

    private fun logResult(op: GattOp, status: Int) {
        if (op != GattOp.ReadRssi) Log.i(TAG, "$op -> status $status")
    }

    private fun applyEffects(effects: List<SetupEffect>) {
        if (SetupEffect.REPORT_LINK_UP in effects) events.onStreamReady(this)
        if (SetupEffect.LOWER_PRIORITY in effects) gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
    }

    private fun handleConnectionState(status: Int, newState: Int) {
        if (gatt == null) return
        val connected = newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS
        if (connected) handleConnected() else events.onDisconnected(this, status)
    }

    private fun handleConnected() {
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        events.onConnected(this)
        enqueueOp(GattOp.DiscoverServices)
    }

    private fun rediscover() {
        if (gatt == null) return
        stopQueue()
        enqueueOp(GattOp.DiscoverServices)
    }

    private fun handleDiscovery(status: Int) {
        if (queue.inFlight != GattOp.DiscoverServices) return
        val result = if (hasBlindsideService()) status else LOCAL_FAILURE_STATUS
        finish(GattOp.DiscoverServices, result)
        if (result == GATT_SUCCESS_STATUS) setupOpsAfterDiscovery().forEach(::enqueueOp)
    }

    private fun handleMtu(mtu: Int, status: Int) {
        if (status == GATT_SUCCESS_STATUS) negotiatedMtu = mtu
        finish(GattOp.RequestMtu(REQUESTED_MTU), status)
    }

    private fun handleInfoRead(value: ByteArray, status: Int, nowNanos: Long) {
        if (queue.inFlight != GattOp.ReadInfo) return
        if (status == GATT_SUCCESS_STATUS) onInfoJson(value.toString(Charsets.UTF_8), nowNanos)
        finish(GattOp.ReadInfo, status)
    }

    private fun onInfoJson(json: String, nowNanos: Long) {
        infoMtu = mtuFromInfo(json)
        events.onInfo(this, json, nowNanos)
    }

    private fun handleControlWrite(status: Int) {
        val op = queue.inFlight as? GattOp.WriteSessionActive ?: return
        finish(op, status)
    }

    private fun handleRssi(rssi: Int, status: Int, nowNanos: Long) {
        if (queue.inFlight != GattOp.ReadRssi) return
        if (status == GATT_SUCCESS_STATUS) events.onRssi(this, rssi, nowNanos)
        finish(GattOp.ReadRssi, status)
    }

    private fun enableStreamNotify(current: BluetoothGatt): Boolean {
        val stream = characteristic(current, STREAM_UUID) ?: return false
        val cccd = stream.getDescriptor(CCCD_UUID) ?: return false
        if (!current.setCharacteristicNotification(stream, true)) return false
        return current.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
    }

    private fun writeControl(current: BluetoothGatt, bytes: ByteArray): Boolean {
        val control = characteristic(current, CONTROL_UUID) ?: return false
        return current.writeCharacteristic(control, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
            BluetoothStatusCodes.SUCCESS
    }

    private fun characteristic(current: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? =
        current.getService(SERVICE_UUID)?.getCharacteristic(uuid)

    private fun hasBlindsideService(): Boolean {
        val current = gatt ?: return false
        return REQUIRED_CHARACTERISTICS.all { characteristic(current, it) != null }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post { handleConnectionState(status, newState) }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post { handleDiscovery(status) }
        }

        override fun onServiceChanged(g: BluetoothGatt) {
            handler.post { rediscover() }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post { handleMtu(mtu, status) }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            val now = SystemClock.elapsedRealtimeNanos()
            handler.post { handleInfoRead(value, status, now) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            handler.post { finish(GattOp.EnableStreamNotify, status) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post { handleControlWrite(status) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val arrival = SystemClock.elapsedRealtimeNanos()
            if (gatt != null && c.uuid == STREAM_UUID) events.onPacket(value.copyOf(), arrival)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            val now = SystemClock.elapsedRealtimeNanos()
            handler.post { handleRssi(rssi, status, now) }
        }
    }
}
```

- [ ] **Step 2: Create the scanner**

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ble/BeltScanner.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.ParcelUuid
import android.os.SystemClock

@SuppressLint("MissingPermission")
class BeltScanner(
    private val adapter: BluetoothAdapter?,
    private val handler: Handler,
    private val onFound: (BluetoothDevice) -> Unit,
) {
    private var history = ScanHistory()
    private val stopToken = Any()
    var isScanning = false
        private set

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post { onFound(result.device) }
        }
    }

    fun start(nowMs: Long) {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (isScanning) return
        val permit = tryStartScan(history, nowMs)
        history = permit.history
        if (!permit.allowed) return
        scanner.startScan(listOf(serviceFilter()), lowLatencySettings(), callback)
        isScanning = true
        handler.postAtTime({ stop() }, stopToken, SystemClock.uptimeMillis() + SCAN_DURATION_MS)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(stopToken)
        if (!isScanning) return
        isScanning = false
        adapter?.bluetoothLeScanner?.stopScan(callback)
    }

    private fun serviceFilter(): ScanFilter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()

    private fun lowLatencySettings(): ScanSettings =
        ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

    private companion object {
        const val SCAN_DURATION_MS = 10_000L
    }
}
```

- [ ] **Step 3: Create the link orchestrator**

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ble/BeltLink.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

private const val TAG = "BeltLink"

@SuppressLint("MissingPermission")
class BeltLink(
    private val context: Context,
    private val listener: BeltListener,
    private val onBeltFound: (String) -> Unit,
) : BeltGattEvents {
    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val scanner = BeltScanner(adapter, handler, ::onScanResult)
    private var savedAddress: String? = null
    private var device: BluetoothDevice? = null
    private var gatt: BeltGatt? = null
    private var attempt = ConnectionAttempt()
    private var bondHealth = BondHealth()
    private var reporter = LinkReporter()
    private var reconnect: ReconnectState? = null
    private var directDeadlineMs: Long? = null
    private var halted: BleStatus? = null
    private var mtuRetried = false
    private var everStreamed = false
    private var running = false

    private val supervisor = object : Runnable {
        override fun run() {
            if (!running) return
            supervise(SystemClock.elapsedRealtime())
            handler.postDelayed(this, SUPERVISE_EVERY_MS)
        }
    }

    fun start(address: String?) {
        running = true
        savedAddress = address
        acquire()
        handler.post(supervisor)
    }

    fun retry() {
        if (!running) return
        halted = null
        bondHealth = BondHealth()
        mtuRetried = false
        reconnect = null
        dropConnection()
        device = null
        acquire()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(supervisor)
        scanner.stop()
        gatt?.let(::closeGracefully)
        gatt = null
        listener.onStatus(BleStatus.IDLE)
    }

    override fun onConnected(source: BeltGatt) {
        if (source !== gatt) return
        directDeadlineMs = null
        attempt = attempt.copy(connected = true)
        listener.onStatus(if (attempt.pairing) BleStatus.PAIRING else connectingStatus())
    }

    override fun onStreamReady(source: BeltGatt) {
        if (source !== gatt) return
        attempt = attempt.copy(subscribed = true)
        bondHealth = nextBondHealth(bondHealth, AttemptVerdict.SUBSCRIBED)
        mtuRetried = false
        reconnect = null
        everStreamed = true
        rememberBelt(source.device)
        listener.onStatus(BleStatus.STREAMING)
        reportLink(LinkEvent.STREAM_READY)
    }

    override fun onSetupFailed(source: BeltGatt, op: GattOp, status: Int) {
        if (source === gatt) source.disconnect()
    }

    override fun onDisconnected(source: BeltGatt, status: Int) {
        if (source !== gatt || !running) return
        reportLink(LinkEvent.DISCONNECTED)
        val verdict = attemptVerdict(attempt)
        bondHealth = nextBondHealth(bondHealth, verdict)
        when {
            verdict == AttemptVerdict.PAIRING_FAILED -> halt(BleStatus.PAIRING_FAILED)
            bondHealth.isLost -> halt(BleStatus.BOND_LOST)
            else -> resumePending()
        }
    }

    override fun acceptMtu(source: BeltGatt, mtu: Int?): Boolean {
        if (source !== gatt) return false
        return when (mtuAction(mtu, mtuRetried)) {
            MtuAction.OK -> true
            MtuAction.RETRY_ONCE -> retryForMtu(source, mtu)
            MtuAction.FAIL -> haltForMtu(mtu)
        }
    }

    override fun onPacket(bytes: ByteArray, arrivalNanos: Long) = listener.onPacket(bytes, arrivalNanos)

    override fun onInfo(source: BeltGatt, json: String, nowNanos: Long) {
        if (source === gatt) listener.onBeltInfo(json, nowNanos)
    }

    override fun onRssi(source: BeltGatt, dbm: Int, nowNanos: Long) {
        if (source === gatt) listener.onRssi(dbm, nowNanos)
    }

    private fun supervise(nowMs: Long) {
        val known = device
        when {
            halted != null -> Unit
            adapter?.isEnabled != true -> onBluetoothOff(nowMs)
            known == null -> searchIfIdle(nowMs)
            gatt == null -> connectPending(known)
            else -> superviseConnection(nowMs)
        }
    }

    private fun superviseConnection(nowMs: Long) {
        if (reporter.up) gatt?.requestRssi() else reconnectIfDue(nowMs)
        expireDirectAttempt(nowMs)
    }

    private fun onBluetoothOff(nowMs: Long) {
        listener.onStatus(BleStatus.BLUETOOTH_OFF)
        dropConnection()
        reportLink(LinkEvent.BLUETOOTH_OFF)
        markLost(nowMs)
    }

    private fun acquire() {
        val bonded = bondedBelt(savedAddress)
        if (bonded != null) useDevice(bonded) else listener.onStatus(BleStatus.SEARCHING)
    }

    private fun bondedBelt(address: String?): BluetoothDevice? {
        val bonded = adapter?.bondedDevices.orEmpty()
        return bonded.firstOrNull { it.address == address } ?: bonded.firstOrNull { isBlindsideName(it.name) }
    }

    private fun searchIfIdle(nowMs: Long) {
        if (scanner.isScanning) return
        listener.onStatus(BleStatus.SEARCHING)
        scanner.start(nowMs)
    }

    private fun onScanResult(found: BluetoothDevice) {
        if (halted != null) return
        val known = device
        when {
            known == null -> adoptScanned(found)
            found.address == known.address && !reporter.up -> connectDirect(SystemClock.elapsedRealtime())
        }
    }

    private fun adoptScanned(found: BluetoothDevice) {
        scanner.stop()
        if (found.bondState == BluetoothDevice.BOND_BONDED) useDevice(found) else beginPairing(found)
    }

    // Spec §5.2: no explicit bonding call; the encrypted info read makes Android show the passkey dialog.
    private fun beginPairing(found: BluetoothDevice) {
        device = found
        connectDirect(SystemClock.elapsedRealtime())
    }

    private fun useDevice(found: BluetoothDevice) {
        device = found
        markLost(SystemClock.elapsedRealtime())
        connectPending(found)
    }

    private fun connectPending(target: BluetoothDevice) {
        openGatt(target, autoConnect = true)
        listener.onStatus(connectingStatus())
    }

    private fun connectDirect(nowMs: Long) {
        val target = device ?: return
        openGatt(target, autoConnect = false)
        directDeadlineMs = nowMs + DIRECT_ATTEMPT_TIMEOUT_MS
    }

    private fun openGatt(target: BluetoothDevice, autoConnect: Boolean) {
        gatt?.close()
        attempt = ConnectionAttempt(pairing = target.bondState != BluetoothDevice.BOND_BONDED)
        gatt = BeltGatt(context, target, handler, this).also { it.connect(autoConnect) }
    }

    private fun resumePending() {
        markLost(SystemClock.elapsedRealtime())
        directDeadlineMs = null
        device?.let(::connectPending)
    }

    private fun reconnectIfDue(nowMs: Long) {
        val state = reconnect ?: return
        if (directDeadlineMs != null) return
        val action = nextReconnectAction(state, nowMs) ?: return
        reconnect = recordAction(state, action, nowMs)
        if (action == ReconnectAction.DIRECT_CONNECT) connectDirect(nowMs) else scanner.start(nowMs)
    }

    private fun expireDirectAttempt(nowMs: Long) {
        val deadline = directDeadlineMs ?: return
        if (nowMs < deadline) return
        directDeadlineMs = null
        device?.let(::connectPending)
    }

    private fun retryForMtu(source: BeltGatt, mtu: Int?): Boolean {
        Log.w(TAG, "mtu $mtu below $MIN_STREAM_MTU, reconnecting once")
        mtuRetried = true
        attempt = attempt.copy(endedByApp = true)
        listener.onStatus(BleStatus.MTU_TOO_LOW)
        source.disconnect()
        return false
    }

    private fun haltForMtu(mtu: Int?): Boolean {
        Log.w(TAG, "mtu $mtu below $MIN_STREAM_MTU again, link halted")
        halt(BleStatus.MTU_TOO_LOW)
        return false
    }

    private fun halt(status: BleStatus) {
        halted = status
        scanner.stop()
        dropConnection()
        reportLink(LinkEvent.HALTED)
        listener.onStatus(status)
    }

    private fun dropConnection() {
        gatt?.close()
        gatt = null
        directDeadlineMs = null
    }

    private fun rememberBelt(found: BluetoothDevice) {
        if (found.address == savedAddress) return
        savedAddress = found.address
        onBeltFound(found.address)
    }

    private fun closeGracefully(current: BeltGatt) {
        current.deactivateSession()
        handler.postDelayed({ current.disconnect(); current.close() }, DEACTIVATE_GRACE_MS)
    }

    private fun markLost(nowMs: Long) {
        if (reconnect == null) reconnect = ReconnectState(lostAtMs = nowMs)
    }

    private fun reportLink(event: LinkEvent) {
        val step = report(reporter, event)
        reporter = step.reporter
        step.change?.let { listener.onLinkChanged(it, SystemClock.elapsedRealtimeNanos()) }
    }

    private fun connectingStatus(): BleStatus = if (everStreamed) BleStatus.RECONNECTING else BleStatus.CONNECTING

    private companion object {
        const val SUPERVISE_EVERY_MS = 1_000L
        const val DIRECT_ATTEMPT_TIMEOUT_MS = 12_000L
        const val DEACTIVATE_GRACE_MS = 500L
    }
}
```

- [ ] **Step 4: Build to compile the adapter**

Run: `./gradlew :wear-app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. Warnings about `MissingPermission` are suppressed; the session only starts after the Bluetooth permissions are granted (Task 10).

Run: `./gradlew :wear-app:testDebugUnitTest`
Expected: PASS. Every earlier test is still green.

Run: `grep -rn "createBond(" wear-app/src/main/kotlin || echo "no createBond"`
Expected: `no createBond` (spec §5.2).

- [ ] **Step 5: Commit**

```bash
git add watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ble
git commit -m "feat: enlace BLE con emparejamiento por la lectura cifrada de info, bond perdido, MTU y reconexión pendiente"
```

On-device verification of this task is in Task 10b (spikes S10 and S12) and in Task 15, checklist items 5, 6, 8, 14 and 16.

---

### Task 9: Demo source from the `radar-core` simulator

Spec §6.11 (the simulator is used as the "demo" source in the app). The demo lets Santiago try the whole screen, vibration and recording chain without the belt.

**Files:**
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/demo/DemoSource.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/demo/DemoScenario.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/demo/DemoSourceTest.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/demo/DemoScenarioTest.kt`

**Interfaces:**
- Consumes: `.sim.{Scenario, Stand, walker, marcher, simulate, SimPacket}`, `.geometry.Point2`, `RadarPipeline`, `.config.PipelineConfig`, `.alerts.ContactAlert`; `SessionInput` (Task 4).
- Produces:
  - `fun rebase(packets: List<SimPacket>, baseNanos: Long): List<SimPacket>`
  - `fun delayMsUntil(targetNanos: Long, nowNanos: Long): Long`
  - `class DemoSource(packets: List<SimPacket>, inputs: SendChannel<SessionInput>, clock: () -> Long) { suspend fun run(); fun announceLink(); suspend fun playOnce() }`
  - `fun demoScenario(): Scenario`, `fun demoPackets(): List<SimPacket>`

The demo loops the scenario with a 2 s pause between plays. Each packet is delivered with the real arrival time `clock()`, so the pipeline sees live timing. Every loop restarts the ESP32 `t_ms`/`seq`, which the pipeline treats as a belt reboot (spec §4.2).

The demo scenario lasts 60 s; the player stands still with both radars and both IMUs alive. Coordinates are in plan 01's simulator world frame: the player is at the origin facing +y, with x to the right, in metres.

| # | Who | Path | Time |
|---|---|---|---|
| A | crossing left to right | `walker` from (−4, 3) at 1.0 m/s along +x: bearing −53° → +53° | 3-11 s |
| B | approaching from the right | `walker` from (3.5, 4) at (−0.5, −0.5) m/s: +41°, 5.3 m → 2.9 m | 20-23.5 s |
| C | marching in place on the left | `marcher` centred on (−2.5, 1.5): −59°, 2.9 m | 35-50 s |
| D | walking straight in | `walker` from (0.3, 5.5) toward the player at 0.6 m/s: centre | 52-57 s |

Plan 01's notes name `simulate(Scenarios.crossing())` as the minimal demo. This scenario uses the same DSL to show every side and the 5 s sector pause.

- [ ] **Step 1: Write the failing tests**

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/demo/DemoSourceTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.demo

import io.github.santiquiroz.blindside.core.sim.SimPacket
import io.github.santiquiroz.blindside.wear.session.SessionInput
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DemoSourceTest {
    private val packets = listOf(
        SimPacket(byteArrayOf(1), 7_000_000L),
        SimPacket(byteArrayOf(2), 9_000_000L),
        SimPacket(byteArrayOf(3), 11_000_000L),
    )

    @Test
    fun `rebasing moves the first packet to the base and keeps the spacing`() {
        assertEquals(listOf(100L, 2_000_100L, 4_000_100L), rebase(packets, 100L).map { it.arrivalNanos })
    }

    @Test
    fun `rebasing an empty list gives an empty list`() {
        assertEquals(emptyList<SimPacket>(), rebase(emptyList(), 100L))
    }

    @Test
    fun `delays never go negative`() {
        assertEquals(0L, delayMsUntil(targetNanos = 1_000L, nowNanos = 5_000_000L))
        assertEquals(3L, delayMsUntil(targetNanos = 3_000_000L, nowNanos = 0L))
    }

    @Test
    fun `one play delivers every packet in order with real arrival times`() {
        val inputs = Channel<SessionInput>(Channel.UNLIMITED)
        runBlocking { DemoSource(packets, inputs, System::nanoTime).playOnce() }
        val delivered = generateSequence { inputs.tryReceive().getOrNull() }.toList().map { it as SessionInput.Packet }
        assertEquals(listOf(1, 2, 3), delivered.map { it.bytes.single().toInt() })
        assertTrue(delivered.zipWithNext().all { (a, b) -> a.arrivalNanos <= b.arrivalNanos })
    }

    @Test
    fun `the demo announces the link before any packet`() {
        val inputs = Channel<SessionInput>(Channel.UNLIMITED)
        DemoSource(packets, inputs, { 42L }).announceLink()
        assertEquals(SessionInput.Link(connected = true, nowNanos = 42L), inputs.tryReceive().getOrNull())
    }
}
```


`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/demo/DemoScenarioTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.demo

import io.github.santiquiroz.blindside.core.RadarPipeline
import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DemoScenarioTest {
    private val packets = demoPackets()

    @Test
    fun `the demo stream lasts about a minute and is in arrival order`() {
        val spanSeconds = (packets.last().arrivalNanos - packets.first().arrivalNanos) / 1e9
        assertTrue(spanSeconds in 55.0..65.0, "span was $spanSeconds s")
        assertEquals(packets.map { it.arrivalNanos }.sorted(), packets.map { it.arrivalNanos })
    }

    @Test
    fun `the demo makes the pipeline vibrate on more than one side`() {
        val pipeline = RadarPipeline(PipelineConfig())
        pipeline.onLinkState(true, packets.first().arrivalNanos)
        val sides = packets
            .flatMap { pipeline.onBlePacket(it.bytes, it.arrivalNanos) }
            .filterIsInstance<ContactAlert>()
            .map { it.side }
            .toSet()
        assertTrue(sides.size >= 2, "sides seen: $sides")
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :wear-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.wear.demo.*"`
Expected: FAIL at compilation with `Unresolved reference 'rebase'`.

- [ ] **Step 3: Write the demo source**

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/demo/DemoSource.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.demo

import io.github.santiquiroz.blindside.core.sim.SimPacket
import io.github.santiquiroz.blindside.wear.session.SessionInput
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val NANOS_PER_MS = 1_000_000L
private const val LOOP_PAUSE_MS = 2_000L

class DemoSource(
    private val packets: List<SimPacket>,
    private val inputs: SendChannel<SessionInput>,
    private val clock: () -> Long,
) {
    suspend fun run() {
        announceLink()
        while (currentCoroutineContext().isActive) {
            playOnce()
            delay(LOOP_PAUSE_MS)
        }
    }

    fun announceLink() {
        inputs.trySend(SessionInput.Link(connected = true, nowNanos = clock()))
    }

    suspend fun playOnce() {
        rebase(packets, clock()).forEach { deliver(it) }
    }

    private suspend fun deliver(packet: SimPacket) {
        delay(delayMsUntil(packet.arrivalNanos, clock()))
        inputs.trySend(SessionInput.Packet(packet.bytes, clock()))
    }
}

fun rebase(packets: List<SimPacket>, baseNanos: Long): List<SimPacket> {
    val first = packets.firstOrNull()?.arrivalNanos ?: return emptyList()
    return packets.map { it.copy(arrivalNanos = it.arrivalNanos - first + baseNanos) }
}

fun delayMsUntil(targetNanos: Long, nowNanos: Long): Long = ((targetNanos - nowNanos) / NANOS_PER_MS).coerceAtLeast(0L)
```

- [ ] **Step 4: Write the demo scenario**

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/demo/DemoScenario.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.demo

import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.sim.Scenario
import io.github.santiquiroz.blindside.core.sim.SimPacket
import io.github.santiquiroz.blindside.core.sim.Stand
import io.github.santiquiroz.blindside.core.sim.marcher
import io.github.santiquiroz.blindside.core.sim.simulate
import io.github.santiquiroz.blindside.core.sim.walker

private const val DEMO_DURATION_MS = 60_000L

fun demoPackets(): List<SimPacket> = simulate(demoScenario())

fun demoScenario(): Scenario = Scenario(
    name = "demo",
    player = listOf(Stand(DEMO_DURATION_MS)),
    targets = listOf(
        walker(fromMs = 3_000, toMs = 11_000, start = Point2(-4.0, 3.0), velocityMps = Point2(1.0, 0.0)),
        walker(fromMs = 20_000, toMs = 23_500, start = Point2(3.5, 4.0), velocityMps = Point2(-0.5, -0.5)),
        marcher(fromMs = 35_000, toMs = 50_000, center = Point2(-2.5, 1.5)),
        walker(fromMs = 52_000, toMs = 57_000, start = Point2(0.3, 5.5), velocityMps = Point2(0.0, -0.6)),
    ),
)
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :wear-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.wear.demo.*"`
Expected: PASS (7 tests).

If `the demo makes the pipeline vibrate on more than one side` fails while plan 01's own simulator tests pass, the scenario is the problem: widen the bearings. Do not change the pipeline.

- [ ] **Step 6: Commit**

```bash
git add watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/demo watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/demo
git commit -m "feat: fuente demo que reproduce el simulador de radar-core en tiempo real"
```

---

### Task 10: `BlindsideSessionService` (FGS, wake lock, Ongoing Activity, wiring)

Spec §5.2 (`BlindsideSessionService`: FGS `connectedDevice|health`, `PARTIAL_WAKE_LOCK` 10 min renewed, Ongoing Activity, all work in the service, release on stop), §5.4 (no scene ticks in ambient), §5.6 (eliminated quick action in the Ongoing Activity), §6.10 (header with the first `info`), §10.1 (eliminated keeps the ESP32 and the recording running), §8 (BLE permission denied), §9 spike S1 (with and without the wake lock).

**Files:**
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/SessionSource.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/SessionStore.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/SessionCommands.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/SessionWakeLock.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/SessionNotification.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/InputAdapters.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/RunningSession.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/BlindsideSessionService.kt`
- Modify: `watch/wear-app/src/main/AndroidManifest.xml` (add the `<service>` element inside `<application>`)
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/session/SessionSourceTest.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/session/SessionStoreTest.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/session/InputAdaptersTest.kt`

**Interfaces:**
- Consumes: Tasks 2-9.
- Produces:
  - `enum class SessionSource { BELT, DEMO }`, `enum class StartError { BLUETOOTH_PERMISSION_MISSING, BLUETOOTH_UNAVAILABLE }`
  - `fun foregroundTypesFor(source): Int`, `fun startBlocker(source, bluetoothGranted, hasAdapter): StartError?`, `fun sourceFrom(name: String?): SessionSource`, `fun ongoingStatus(eliminated, source): String`
  - `data class SessionUiState(running, source, ble: BleStatus, scene: RadarScene?, watchSteps, recordingName, recordingFailed, lastRecordingName, startError)`
  - `object SessionStore { val state: StateFlow<SessionUiState>; val radarVisible: StateFlow<Boolean>; val ambient: StateFlow<Boolean>; fun update(transform); fun setRadarVisible(visible); fun setAmbient(ambient) }`
  - `fun startedState(previous, source)`, `fun stoppedState(previous)`, `fun blockedState(previous, error)`
  - `object SessionCommands { fun start(context, source, wakeLock: Boolean = true); fun stop(context); fun marker(context); fun retryLink(context); fun toggleEliminatedIntent(context): Intent }`
  - `class RunningSession(context, source, settings: SettingsRepository, scope, useWakeLock: Boolean) { suspend fun start(); fun mark(); fun retryLink(); suspend fun stop() }`
  - `class BlindsideSessionService : Service`

Session lifecycle:
1. **`ACTION_START`.**
   - Check the source's start blocker. A blocked start still calls `startForeground` (type `health`, which `HIGH_SAMPLING_RATE_SENSORS` covers without any Bluetooth permission) and then removes it at once. `ACTION_START` always arrives through `startForegroundService()`, and Android kills an app whose service never answers it with `startForeground()`.
   - Otherwise call `startForeground(…, foregroundTypesFor(source))` synchronously inside `onStartCommand`.
   - Then start `RunningSession` on a coroutine:
     1. reset `eliminated` (`forNewSession`);
     2. open the recording: a BELT recording waits in `InfoHeaderSink` for the belt's first `info` (Task 4); a DEMO recording opens at once;
     3. launch the single-thread consumer, the wake lock and its renewal (unless the spike build turned it off), the scene ticker, a flush every 2 s and the forwarder of eliminated/screen-mode changes;
     4. start the sensors and then the source.
2. **`ACTION_STOP`.**
   - Stop the source and the sensors, and cancel the loops.
   - Close the channel and wait for the consumer to drain and close the recorder.
   - Release the wake lock, `stopForeground(STOP_FOREGROUND_REMOVE)` and `stopSelf()`.
3. **Other actions.**
   - `ACTION_TOGGLE_ELIMINATED` (from the Ongoing Activity) flips `eliminated` in DataStore. The running session sees the change through its settings forwarder.
   - `ACTION_MARKER` enqueues a manual marker.
   - `ACTION_RETRY_LINK` ("Reintentar") calls `BeltLink.retry()` after a pairing failure, a lost bond or a low MTU (Task 8).
4. **Deferred contacts.** The engine's `DeferredPlayback` waits on the service scope until the system buzz ends, then sends `SessionInput.PlayDeferred` back through the channel (Task 5).
5. **Scene ticks** follow `scenePeriodMs(radarVisible, screenMode, ambient)`; the activity reports ambient through `SessionStore.setAmbient` (Task 12).
6. **`START_NOT_STICKY`.** A system restart cannot call `startForeground` from the background on Android 14+, so the service is not restarted automatically. If the app dies, Santiago relaunches it.

- [ ] **Step 1: Write the failing tests**

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/session/SessionSourceTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SessionSourceTest {
    @Test
    fun `belt sessions run as connected device plus health`() {
        assertEquals(0x10 or 0x100, foregroundTypesFor(SessionSource.BELT))
    }

    @Test
    fun `demo sessions need no bluetooth service type`() {
        assertEquals(0x100, foregroundTypesFor(SessionSource.DEMO))
    }

    @Test
    fun `a belt session without bluetooth permission is blocked`() {
        assertEquals(StartError.BLUETOOTH_PERMISSION_MISSING, startBlocker(SessionSource.BELT, bluetoothGranted = false, hasAdapter = true))
    }

    @Test
    fun `a watch without a bluetooth adapter cannot run a belt session`() {
        assertEquals(StartError.BLUETOOTH_UNAVAILABLE, startBlocker(SessionSource.BELT, bluetoothGranted = true, hasAdapter = false))
    }

    @Test
    fun `the demo always starts`() {
        assertNull(startBlocker(SessionSource.DEMO, bluetoothGranted = false, hasAdapter = false))
        assertNull(startBlocker(SessionSource.BELT, bluetoothGranted = true, hasAdapter = true))
    }

    @Test
    fun `an unknown source name falls back to the belt`() {
        assertEquals(SessionSource.DEMO, sourceFrom("DEMO"))
        assertEquals(SessionSource.BELT, sourceFrom(null))
        assertEquals(SessionSource.BELT, sourceFrom("WIFI"))
    }

    @Test
    fun `the ongoing status says eliminated first`() {
        assertEquals("Eliminado", ongoingStatus(eliminated = true, source = SessionSource.DEMO))
        assertEquals("Demo en curso", ongoingStatus(eliminated = false, source = SessionSource.DEMO))
        assertEquals("Partida en curso", ongoingStatus(eliminated = false, source = SessionSource.BELT))
    }
}
```

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/session/SessionStoreTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.session

import io.github.santiquiroz.blindside.wear.ble.BleStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionStoreTest {
    @Test
    fun `starting keeps only the previous recording name`() {
        val previous = SessionUiState(lastRecordingName = "old.bsrec", startError = StartError.BLUETOOTH_UNAVAILABLE)
        val started = startedState(previous, SessionSource.BELT)
        assertTrue(started.running)
        assertEquals(SessionSource.BELT, started.source)
        assertEquals("old.bsrec", started.lastRecordingName)
        assertNull(started.startError)
    }

    @Test
    fun `stopping remembers the recording that just ended`() {
        val running = SessionUiState(running = true, ble = BleStatus.STREAMING, recordingName = "new.bsrec")
        val stopped = stoppedState(running)
        assertFalse(stopped.running)
        assertEquals(BleStatus.IDLE, stopped.ble)
        assertEquals("new.bsrec", stopped.lastRecordingName)
    }

    @Test
    fun `a blocked start reports why`() {
        val blocked = blockedState(SessionUiState(), StartError.BLUETOOTH_PERMISSION_MISSING)
        assertFalse(blocked.running)
        assertEquals(StartError.BLUETOOTH_PERMISSION_MISSING, blocked.startError)
    }
}
```

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/session/InputAdaptersTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.session

import kotlinx.coroutines.channels.Channel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InputAdaptersTest {
    private val inputs = Channel<SessionInput>(Channel.UNLIMITED)

    private fun drain(): List<SessionInput> = generateSequence { inputs.tryReceive().getOrNull() }.toList()

    @Test
    fun `watch sensor callbacks become session inputs in order`() {
        val sensors = SensorInputs(inputs)
        sensors.onGravity(0f, 0f, 9.8f, 1L)
        sensors.onGyro(0.1f, 0f, 0f, 2L)
        sensors.onStep(3L)
        assertEquals(
            listOf(SessionInput.Gravity(0f, 0f, 9.8f, 1L), SessionInput.Gyro(0.1f, 0f, 0f, 2L), SessionInput.Step(3L)),
            drain(),
        )
    }

    @Test
    fun `belt callbacks become session inputs in order`() {
        val belt = BeltInputs(inputs)
        belt.onBeltInfo("{}", 1L)
        belt.onRssi(-60, 2L)
        belt.onLinkChanged(false, 3L)
        assertEquals(
            listOf(SessionInput.BeltInfo("{}", 1L), SessionInput.Rssi(-60, 2L), SessionInput.Link(false, 3L)),
            drain(),
        )
    }

    @Test
    fun `belt packets keep their bytes and arrival time`() {
        BeltInputs(inputs).onPacket(byteArrayOf(5, 6), 99L)
        val packet = drain().single() as SessionInput.Packet
        assertEquals(listOf<Byte>(5, 6), packet.bytes.toList())
        assertEquals(99L, packet.arrivalNanos)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :wear-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.wear.session.*"`
Expected: FAIL at compilation with `Unresolved reference 'foregroundTypesFor'`.

- [ ] **Step 3: Write the pure parts and the adapters**

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/SessionSource.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.session

import android.content.pm.ServiceInfo

enum class SessionSource { BELT, DEMO }

enum class StartError { BLUETOOTH_PERMISSION_MISSING, BLUETOOTH_UNAVAILABLE }

fun foregroundTypesFor(source: SessionSource): Int = when (source) {
    SessionSource.BELT -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
    SessionSource.DEMO -> ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
}

fun startBlocker(source: SessionSource, bluetoothGranted: Boolean, hasAdapter: Boolean): StartError? = when {
    source == SessionSource.DEMO -> null
    !bluetoothGranted -> StartError.BLUETOOTH_PERMISSION_MISSING
    !hasAdapter -> StartError.BLUETOOTH_UNAVAILABLE
    else -> null
}

fun sourceFrom(name: String?): SessionSource = SessionSource.entries.firstOrNull { it.name == name } ?: SessionSource.BELT

fun ongoingStatus(eliminated: Boolean, source: SessionSource): String = when {
    eliminated -> "Eliminado"
    source == SessionSource.DEMO -> "Demo en curso"
    else -> "Partida en curso"
}
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/SessionStore.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.session

import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.wear.ble.BleStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SessionUiState(
    val running: Boolean = false,
    val source: SessionSource? = null,
    val ble: BleStatus = BleStatus.IDLE,
    val scene: RadarScene? = null,
    val watchSteps: Boolean = false,
    val recordingName: String? = null,
    val recordingFailed: Boolean = false,
    val lastRecordingName: String? = null,
    val startError: StartError? = null,
)

object SessionStore {
    private val mutableState = MutableStateFlow(SessionUiState())
    private val mutableRadarVisible = MutableStateFlow(false)
    private val mutableAmbient = MutableStateFlow(false)

    val state: StateFlow<SessionUiState> = mutableState.asStateFlow()
    val radarVisible: StateFlow<Boolean> = mutableRadarVisible.asStateFlow()
    val ambient: StateFlow<Boolean> = mutableAmbient.asStateFlow()

    fun update(transform: (SessionUiState) -> SessionUiState) = mutableState.update(transform)

    fun setRadarVisible(visible: Boolean) {
        mutableRadarVisible.value = visible
    }

    fun setAmbient(ambient: Boolean) {
        mutableAmbient.value = ambient
    }
}

fun startedState(previous: SessionUiState, source: SessionSource): SessionUiState =
    SessionUiState(running = true, source = source, lastRecordingName = previous.lastRecordingName)

fun stoppedState(previous: SessionUiState): SessionUiState =
    SessionUiState(lastRecordingName = previous.recordingName ?: previous.lastRecordingName)

fun blockedState(previous: SessionUiState, error: StartError): SessionUiState =
    stoppedState(previous).copy(startError = error)
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/InputAdapters.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.session

import io.github.santiquiroz.blindside.wear.ble.BeltListener
import io.github.santiquiroz.blindside.wear.ble.BleStatus
import io.github.santiquiroz.blindside.wear.sensors.WatchSensorListener
import kotlinx.coroutines.channels.SendChannel

class SensorInputs(private val inputs: SendChannel<SessionInput>) : WatchSensorListener {
    override fun onGravity(x: Float, y: Float, z: Float, eventNanos: Long) {
        inputs.trySend(SessionInput.Gravity(x, y, z, eventNanos))
    }

    override fun onGyro(x: Float, y: Float, z: Float, eventNanos: Long) {
        inputs.trySend(SessionInput.Gyro(x, y, z, eventNanos))
    }

    override fun onStep(eventNanos: Long) {
        inputs.trySend(SessionInput.Step(eventNanos))
    }
}

class BeltInputs(private val inputs: SendChannel<SessionInput>) : BeltListener {
    override fun onPacket(bytes: ByteArray, arrivalNanos: Long) {
        inputs.trySend(SessionInput.Packet(bytes, arrivalNanos))
    }

    override fun onBeltInfo(json: String, nowNanos: Long) {
        inputs.trySend(SessionInput.BeltInfo(json, nowNanos))
    }

    override fun onRssi(dbm: Int, nowNanos: Long) {
        inputs.trySend(SessionInput.Rssi(dbm, nowNanos))
    }

    override fun onLinkChanged(connected: Boolean, nowNanos: Long) {
        inputs.trySend(SessionInput.Link(connected, nowNanos))
    }

    override fun onStatus(status: BleStatus) {
        SessionStore.update { it.copy(ble = status) }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :wear-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.wear.session.*"`
Expected: PASS (18 from Task 5 plus 13 new = 31 tests).

- [ ] **Step 5: Write the Android side (commands, wake lock, notification, running session, service)**

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/SessionCommands.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.session

import android.content.Context
import android.content.Intent

object SessionCommands {
    const val ACTION_START = "io.github.santiquiroz.blindside.action.START"
    const val ACTION_STOP = "io.github.santiquiroz.blindside.action.STOP"
    const val ACTION_MARKER = "io.github.santiquiroz.blindside.action.MARKER"
    const val ACTION_RETRY_LINK = "io.github.santiquiroz.blindside.action.RETRY_LINK"
    const val ACTION_TOGGLE_ELIMINATED = "io.github.santiquiroz.blindside.action.TOGGLE_ELIMINATED"
    const val EXTRA_SOURCE = "source"
    const val EXTRA_WAKE_LOCK = "wake_lock"

    fun start(context: Context, source: SessionSource, wakeLock: Boolean = true) {
        val intent = serviceIntent(context, ACTION_START)
            .putExtra(EXTRA_SOURCE, source.name)
            .putExtra(EXTRA_WAKE_LOCK, wakeLock)
        context.startForegroundService(intent)
    }

    fun stop(context: Context) {
        context.startService(serviceIntent(context, ACTION_STOP))
    }

    fun marker(context: Context) {
        context.startService(serviceIntent(context, ACTION_MARKER))
    }

    fun retryLink(context: Context) {
        context.startService(serviceIntent(context, ACTION_RETRY_LINK))
    }

    fun toggleEliminatedIntent(context: Context): Intent = serviceIntent(context, ACTION_TOGGLE_ELIMINATED)

    private fun serviceIntent(context: Context, action: String): Intent =
        Intent(context, BlindsideSessionService::class.java).setAction(action)
}
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/SessionWakeLock.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.session

import android.content.Context
import android.os.PowerManager

class SessionWakeLock(context: Context) {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
        .apply { setReferenceCounted(false) }

    fun acquireOrRenew() = lock.acquire(TIMEOUT_MS)

    fun release() {
        if (lock.isHeld) lock.release()
    }

    companion object {
        private const val TAG = "blindside:session"
        private const val TIMEOUT_MS = 10 * 60 * 1_000L
        const val RENEW_EVERY_MS = 9 * 60 * 1_000L
    }
}
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/SessionNotification.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import io.github.santiquiroz.blindside.wear.MainActivity
import io.github.santiquiroz.blindside.wear.R

object SessionNotification {
    const val NOTIFICATION_ID = 7
    private const val CHANNEL_ID = "blindside_session"

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Partida", NotificationManager.IMPORTANCE_LOW)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(context: Context, status: String): Notification {
        val openApp = openAppIntent(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radar)
            .setContentTitle("Blindside")
            .setContentText(status)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(R.drawable.ic_radar, "Eliminado", toggleEliminatedIntent(context))
        OngoingActivity.Builder(context, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_radar)
            .setTouchIntent(openApp)
            .setStatus(Status.Builder().addTemplate(status).build())
            .build()
            .apply(context)
        return builder.build()
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun toggleEliminatedIntent(context: Context): PendingIntent = PendingIntent.getService(
        context,
        1,
        SessionCommands.toggleEliminatedIntent(context),
        PendingIntent.FLAG_IMMUTABLE,
    )
}
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/RunningSession.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.session

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.santiquiroz.blindside.core.RadarPipeline
import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.wear.BuildConfig
import io.github.santiquiroz.blindside.wear.ble.BeltLink
import io.github.santiquiroz.blindside.wear.demo.DemoSource
import io.github.santiquiroz.blindside.wear.demo.demoPackets
import io.github.santiquiroz.blindside.wear.haptics.HapticPlayer
import io.github.santiquiroz.blindside.wear.haptics.millisUntil
import io.github.santiquiroz.blindside.wear.permissions.PERMISSION_ACTIVITY_RECOGNITION
import io.github.santiquiroz.blindside.wear.recording.InfoHeaderSink
import io.github.santiquiroz.blindside.wear.recording.RecordSink
import io.github.santiquiroz.blindside.wear.recording.RecordingMeta
import io.github.santiquiroz.blindside.wear.recording.SessionClockStamp
import io.github.santiquiroz.blindside.wear.recording.headerJson
import io.github.santiquiroz.blindside.wear.recording.openRecordingSink
import io.github.santiquiroz.blindside.wear.recording.recordingFileName
import io.github.santiquiroz.blindside.wear.recording.recordingMeta
import io.github.santiquiroz.blindside.wear.recording.recordingsDir
import io.github.santiquiroz.blindside.wear.sensors.WatchSensors
import io.github.santiquiroz.blindside.wear.settings.AppSettings
import io.github.santiquiroz.blindside.wear.settings.ScreenMode
import io.github.santiquiroz.blindside.wear.settings.SettingsRepository
import io.github.santiquiroz.blindside.wear.settings.forNewSession
import io.github.santiquiroz.blindside.wear.settings.toPipelineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZoneId

private const val TAG = "BlindsideSession"
private const val FLUSH_EVERY_MS = 2_000L

private fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()

@OptIn(ExperimentalCoroutinesApi::class)
class RunningSession(
    private val context: Context,
    private val source: SessionSource,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val useWakeLock: Boolean,
) {
    private val pipelineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val inputs = Channel<SessionInput>(Channel.UNLIMITED)
    private val wakeLock = SessionWakeLock(context)
    private val sensors = WatchSensors(context.getSystemService(SensorManager::class.java), SensorInputs(inputs))
    private val loops = mutableListOf<Job>()
    private var consumer: Job? = null
    private var belt: BeltLink? = null

    @Volatile
    private var screenMode = ScreenMode.SIGILO

    suspend fun start() {
        settings.update { it.forNewSession() }
        val initial = settings.current()
        consumer = scope.launch(pipelineDispatcher) { consume(createEngine(initial)) }
        holdWakeLock()
        launchLoops()
        startSensors()
        startSource(initial)
    }

    fun mark() {
        inputs.trySend(SessionInput.Marker(nowNanos()))
    }

    fun retryLink() {
        belt?.retry()
    }

    suspend fun stop() {
        belt?.stop()
        sensors.stop()
        loops.forEach { it.cancel() }
        inputs.close()
        consumer?.join()
        wakeLock.release()
    }

    private suspend fun consume(engine: SessionEngine) {
        try {
            for (input in inputs) engine.handle(input)
        } finally {
            engine.close()
        }
    }

    private fun createEngine(initial: AppSettings): SessionEngine {
        val startNanos = nowNanos()
        val haptics = HapticPlayer.create(context, initial.vibrationUsage)
        return SessionEngine(
            pipeline = RadarPipelineAdapter(RadarPipeline(toPipelineConfig(initial))),
            records = openRecorder(initial, startNanos, haptics),
            haptics = haptics,
            scenes = SceneSink { scene -> SessionStore.update { it.copy(scene = scene) } },
            clock = NanoClock(::nowNanos),
            startNanos = startNanos,
            deferred = DeferredPlayback(::playLater),
            onError = { error -> Log.w(TAG, "session input failed", error) },
        )
    }

    private fun playLater(alert: ContactAlert, atNanos: Long) {
        scope.launch {
            delay(millisUntil(atNanos, nowNanos()))
            inputs.trySend(SessionInput.PlayDeferred(alert, atNanos))
        }
    }

    private fun openRecorder(initial: AppSettings, startNanos: Long, haptics: HapticPlayer): RecordSink {
        val stamp = SessionClockStamp(epochMs = System.currentTimeMillis(), elapsedNanos = startNanos)
        val name = recordingFileName(stamp.epochMs, ZoneId.systemDefault(), source.name)
        val meta = recordingMeta(
            initial, source.name, stamp, Build.MODEL, BuildConfig.VERSION_NAME,
            haptics.hasAmplitudeControl(), haptics.supportsPrimitives(),
        )
        SessionStore.update { it.copy(recordingName = name, recordingFailed = false) }
        val open = { info: String? -> openRecording(name, meta.copy(infoJson = info)) }
        return if (source == SessionSource.BELT) InfoHeaderSink(open) else open(null)
    }

    private fun openRecording(name: String, meta: RecordingMeta): RecordSink =
        openRecordingSink(recordingsDir(context), name, headerJson(meta), ::onRecordingFailed)

    private fun onRecordingFailed(error: Exception) {
        Log.e(TAG, "recording failed", error)
        SessionStore.update { it.copy(recordingFailed = true) }
    }

    private fun holdWakeLock() {
        if (!useWakeLock) return
        wakeLock.acquireOrRenew()
        loops += scope.launch { repeatEvery(SessionWakeLock.RENEW_EVERY_MS) { wakeLock.acquireOrRenew() } }
    }

    private fun launchLoops() {
        loops += scope.launch { repeatEvery(FLUSH_EVERY_MS) { inputs.trySend(SessionInput.Flush) } }
        loops += scope.launch { tickScenes() }
        loops += scope.launch { forwardMode() }
    }

    private suspend fun repeatEvery(periodMs: Long, action: () -> Unit) {
        while (currentCoroutineContext().isActive) {
            delay(periodMs)
            action()
        }
    }

    private suspend fun tickScenes() {
        while (currentCoroutineContext().isActive) {
            val period = scenePeriodMs(SessionStore.radarVisible.value, screenMode, SessionStore.ambient.value)
            if (period != null) inputs.trySend(SessionInput.Tick(nowNanos()))
            delay(period ?: IDLE_TICK_POLL_MS)
        }
    }

    private suspend fun forwardMode() {
        settings.settings.map { it.eliminated to it.screenMode }.distinctUntilChanged().collect { (eliminated, mode) ->
            screenMode = mode
            inputs.trySend(SessionInput.ModeChanged(eliminated, mode, nowNanos()))
        }
    }

    private fun startSensors() {
        val availability = sensors.start(stepsAllowed = hasActivityRecognition())
        SessionStore.update { it.copy(watchSteps = availability.steps) }
    }

    private fun hasActivityRecognition(): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION_ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun startSource(initial: AppSettings) {
        when (source) {
            SessionSource.BELT -> startBelt(initial.beltAddress)
            SessionSource.DEMO -> loops += scope.launch(Dispatchers.Default) {
                DemoSource(demoPackets(), inputs, ::nowNanos).run()
            }
        }
    }

    private fun startBelt(savedAddress: String?) {
        belt = BeltLink(context, BeltInputs(inputs), onBeltFound = ::rememberBelt).also { it.start(savedAddress) }
    }

    private fun rememberBelt(address: String) {
        scope.launch { settings.update { it.copy(beltAddress = address) } }
    }
}
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/BlindsideSessionService.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.session

import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import io.github.santiquiroz.blindside.wear.permissions.PERMISSION_BLUETOOTH_CONNECT
import io.github.santiquiroz.blindside.wear.permissions.PERMISSION_BLUETOOTH_SCAN
import io.github.santiquiroz.blindside.wear.settings.settingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class BlindsideSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: RunningSession? = null
    private var currentSource = SessionSource.BELT
    private var notificationSync: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            SessionCommands.ACTION_START -> onStart(sourceOf(intent), intent.getBooleanExtra(SessionCommands.EXTRA_WAKE_LOCK, true))
            SessionCommands.ACTION_STOP -> onStop()
            SessionCommands.ACTION_MARKER -> session?.mark() ?: stopSelf()
            SessionCommands.ACTION_RETRY_LINK -> session?.retryLink() ?: stopSelf()
            SessionCommands.ACTION_TOGGLE_ELIMINATED -> if (session != null) toggleEliminated() else stopSelf()
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun sourceOf(intent: Intent): SessionSource = sourceFrom(intent.getStringExtra(SessionCommands.EXTRA_SOURCE))

    private fun onStart(source: SessionSource, wakeLock: Boolean) {
        if (session != null) {
            goForeground(currentSource)
            return
        }
        val blocker = startBlocker(source, hasBluetoothPermissions(), hasBluetoothAdapter())
        if (blocker != null) {
            rejectStart(blocker)
            return
        }
        beginSession(source, wakeLock)
    }

    private fun beginSession(source: SessionSource, wakeLock: Boolean) {
        currentSource = source
        goForeground(source)
        SessionStore.update { startedState(it, source) }
        val created = RunningSession(this, source, settingsRepository(), scope, wakeLock)
        session = created
        scope.launch { created.start() }
        notificationSync = scope.launch { syncNotification(source) }
    }

    private fun onStop() {
        val current = session ?: return stopSelf()
        session = null
        notificationSync?.cancel()
        scope.launch {
            current.stop()
            SessionStore.update(::stoppedState)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun rejectStart(error: StartError) {
        SessionStore.update { blockedState(it, error) }
        answerForegroundStart()
        stopSelf()
    }

    // startForegroundService() must be answered with startForeground() even when the start is refused, or Android kills the app.
    private fun answerForegroundStart() {
        goForeground(SessionSource.DEMO)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun stopIfIdle() {
        if (session == null) stopSelf()
    }

    private fun toggleEliminated() {
        scope.launch { settingsRepository().update { it.copy(eliminated = !it.eliminated) } }
    }

    private fun goForeground(source: SessionSource) {
        SessionNotification.ensureChannel(this)
        val notification = SessionNotification.build(this, ongoingStatus(eliminated = false, source = source))
        startForeground(SessionNotification.NOTIFICATION_ID, notification, foregroundTypesFor(source))
    }

    private suspend fun syncNotification(source: SessionSource) {
        settingsRepository().settings.map { it.eliminated }.distinctUntilChanged().collect { eliminated ->
            val notification = SessionNotification.build(this, ongoingStatus(eliminated, source))
            getSystemService(NotificationManager::class.java).notify(SessionNotification.NOTIFICATION_ID, notification)
        }
    }

    private fun hasBluetoothPermissions(): Boolean =
        listOf(PERMISSION_BLUETOOTH_SCAN, PERMISSION_BLUETOOTH_CONNECT).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun hasBluetoothAdapter(): Boolean = getSystemService(BluetoothManager::class.java)?.adapter != null
}
```

Add the service to `watch/wear-app/src/main/AndroidManifest.xml`, right after the `</activity>` closing tag:

```xml
        <service
            android:name=".session.BlindsideSessionService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice|health" />
```

A blocked `ACTION_START` still arrives through `startForegroundService()`. `answerForegroundStart()` calls `startForeground()` with the `health` type, which needs no Bluetooth permission, and removes it at once; without that, Android raises `ForegroundServiceDidNotStartInTimeException` and kills the app in exactly the permission-revoked case the blocker exists for.

- [ ] **Step 6: Build and rerun every test**

Run: `./gradlew :wear-app:assembleDebug :wear-app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests PASS.

Run: `grep -h "foregroundServiceType" $(find wear-app/build/intermediates -path "*merged_manifest*" -name AndroidManifest.xml | head -1)`
Expected: one line containing `connectedDevice|health`.

- [ ] **Step 7: Commit**

```bash
git add watch/wear-app/src/main/AndroidManifest.xml watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/session
git commit -m "feat: servicio de sesión en primer plano con wake lock, Ongoing Activity y pipeline en un solo hilo"
```

---

### Task 10b: Spike build: start/stop screen for the spikes (2-3 oct)

Spec §12 calendar, 2-3 oct: spikes S1, S3, S10 and S12 run on the bench "con una app mínima que ya usa el grabador real" (records 1, 5, 6 and 10) and the firmware's NimBLE link. S3's result fixes the vibration usage before 5-7 oct. Spec §9 lists the spikes.

After Tasks 1-10 the whole session already works, but nothing on the watch can start it. This task adds a small spike screen that can. It is the start screen of the spike build. Task 14 makes Home the start screen and **keeps this screen** reachable from Ajustes → "Diagnóstico (spikes)". Santiago runs S1, S3, S10 and S12 with the real app on 2-3 oct, and can repeat them later from whichever build is installed. Do not delete it.

**Files:**
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/SpikeScreen.kt` (kept for good; Task 14 hosts it at `ROUTE_SPIKES`)
- Modify: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/MainActivity.kt` (full replacement below; Task 14 replaces it again)

**Interfaces:**
- Consumes: `SESSION_PERMISSIONS`, `startDecision`, `StartDecision` (Task 1); `SettingsRepository`, `settingsRepository()`, `AppSettings`, `toggledUsage`, `VibrationUsage` (Task 2); `needsRetry`, `BleStatus` (Task 7); `SessionCommands`, `SessionStore`, `SessionSource`, `SessionUiState` (Task 10).
- Produces: `@Composable fun SpikeScreen(settingsRepository: SettingsRepository)`. Task 14 opens it from the settings screen (route `ROUTE_SPIKES`, chip "Diagnóstico (spikes)"); keep its signature stable.
- It is a bench tool: it starts belt sessions without the practice gate of Home (spec §5.5), and has the "Wake lock" switch that spike S1 needs. Game-day sessions start from Home.

- [ ] **Step 1: Write the spike screen**

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/SpikeScreen.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import io.github.santiquiroz.blindside.wear.ble.needsRetry
import io.github.santiquiroz.blindside.wear.permissions.SESSION_PERMISSIONS
import io.github.santiquiroz.blindside.wear.permissions.StartDecision
import io.github.santiquiroz.blindside.wear.permissions.startDecision
import io.github.santiquiroz.blindside.wear.session.SessionCommands
import io.github.santiquiroz.blindside.wear.session.SessionSource
import io.github.santiquiroz.blindside.wear.session.SessionStore
import io.github.santiquiroz.blindside.wear.session.SessionUiState
import io.github.santiquiroz.blindside.wear.settings.AppSettings
import io.github.santiquiroz.blindside.wear.settings.SettingsRepository
import io.github.santiquiroz.blindside.wear.settings.toggledUsage
import kotlinx.coroutines.launch

@Composable
fun SpikeScreen(settingsRepository: SettingsRepository) {
    val context = LocalContext.current
    val session by SessionStore.state.collectAsStateWithLifecycle()
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    var wakeLock by remember { mutableStateOf(true) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (startDecision(grants) is StartDecision.Start) SessionCommands.start(context, SessionSource.BELT, wakeLock)
    }
    val toggleUsage = { scope.launch { settingsRepository.update { it.copy(vibrationUsage = toggledUsage(it.vibrationUsage)) } } }
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { ListHeader { Text("Blindside (spikes)") } }
        item { Text(spikeStatusLine(session), fontSize = 12.sp) }
        if (session.running) {
            runningChips(context, session)
        } else {
            item { SpikeChip("Partida (cinturón)") { launcher.launch(SESSION_PERMISSIONS) } }
            item { SpikeChip("Demo") { SessionCommands.start(context, SessionSource.DEMO, wakeLock) } }
            item { SpikeChip("Wake lock: ${if (wakeLock) "sí" else "no (S1)"}") { wakeLock = !wakeLock } }
            item { SpikeChip("Vibración: ${settings.vibrationUsage.name}") { toggleUsage() } }
        }
        session.lastRecordingName?.let { name -> item { Text("Última: $name", fontSize = 11.sp) } }
    }
}

private fun ScalingLazyListScope.runningChips(context: Context, session: SessionUiState) {
    if (needsRetry(session.ble)) item { SpikeChip("Reintentar") { SessionCommands.retryLink(context) } }
    item { SpikeChip("Marcar") { SessionCommands.marker(context) } }
    item { SpikeChip("Detener") { SessionCommands.stop(context) } }
}

@Composable
private fun SpikeChip(label: String, onClick: () -> Unit) {
    Chip(
        label = { Text(label, maxLines = 2) },
        onClick = onClick,
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun spikeStatusLine(session: SessionUiState): String =
    "${session.ble.name} · ${session.recordingName ?: "sin grabación"}"
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/MainActivity.kt` (spike build only; Task 14 replaces it with the full app, which still reaches `SpikeScreen` from Ajustes):

```kotlin
package io.github.santiquiroz.blindside.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.compose.material.MaterialTheme
import io.github.santiquiroz.blindside.wear.settings.settingsRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = settingsRepository()
        setContent { MaterialTheme { SpikeScreen(settings) } }
    }
}
```

- [ ] **Step 2: Build, rerun every test and install**

Run: `./gradlew :wear-app:assembleDebug :wear-app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests PASS.

With the watch connected over Wi-Fi debugging (Task 15's README has the steps): `adb install -r wear-app/build/outputs/apk/debug/wear-app-debug.apk`
Expected: `Success`, and the watch shows "Blindside (spikes)".

- [ ] **Step 3: Commit**

```bash
git add watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/SpikeScreen.kt watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/MainActivity.kt
git commit -m "feat: pantalla de inicio y parada para los spikes del 2-3 de octubre"
```

- [ ] **Step 4: Hand the build to Santiago for the spikes (Spanish, on the watch and the bench)**

The status line shows the `BleStatus` and the recording name. Recordings are pulled with the `adb pull` commands in Task 15's README (`/sdcard/Android/data/io.github.santiquiroz.blindside/files/recordings/`). On the spike build this screen opens at launch; once Task 14 is installed, it is at Ajustes → "Diagnóstico (spikes)" with the same chips.

1. **S12 (emparejamiento).** Abre la ventana del cinturón (arranque sin bond, o BOOT 3 s). "Partida (cinturón)" → el estado pasa por `PAIRING` y aparece el diálogo de clave del sistema → escribe los 6 dígitos → `STREAMING`. Verifica con `adb logcat -s BeltGatt` que `WriteSessionActive(active=true) -> status 0` aparece (la escritura en `control` funciona, así que el bond es autenticado). Apaga y prende el Bluetooth del reloj: vuelve a `STREAMING` sin diálogo. Prueba también con la ventana cerrada o una clave equivocada: debe quedar en `PAIRING_FAILED` con "Reintentar".
2. **S10 (lista blanca y bond perdido).** Deja la sesión 20 min (el reloj rota su dirección cada ~15 min) y apaga y prende el cinturón: los datos vuelven en ≤ 5 s. Anota la ruta exacta del menú del GW7 donde se olvida un bond BLE que no es de audio; va al mensaje de bond perdido (Task 14) y al README (Task 15).
3. **S3 (vibración).** "Demo" con "Vibración: ALARM" y la pantalla apagada: debe vibrar. Repite con No molestar, modo teatro, modo dormir e intensidad del sistema baja; luego con "Vibración: NOTIFICATION". Anota qué combinación pasa y cuál se silencia. Si gana NOTIFICATION, cambia el valor por defecto de `AppSettings.vibrationUsage` (Task 2) antes del 5-oct.
4. **S1 (pantalla apagada).** Con el cinturón en banco y un compañero cruzando cada minuto, 10 min con la pantalla apagada: llegan todas las vibraciones. Repite con "Wake lock: no (S1)" y tras `adb shell dumpsys deviceidle force-idle`. Baja la grabación: los registros 1, 5 y 6 dan la latencia de contacto a vibración, y el 10 el RSSI.

---

### Task 11: Radar draw model, labels and screen policy (pure)

Spec §5.3:
- the fan follows the coverage; fixed 6 m scale; rings at 2 and 4 m; origin below the centre;
- blips all the same size, fill = confidence, opacity = age; out-of-view contacts as an edge arrow;
- `--` and gray after a link loss; the mini status shows link, radars A/B, IMUs A/B and "sin pasos del reloj";
- in Vista, everything static shifts 1-2 px every few minutes.

§5.4 adds: Sigilo/Vista, and placeholders in ambient. §10.1 adds: eliminated turns the display off and hides contacts.

**Files:**
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/RadarGeometry.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/RadarLabels.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/ScreenPolicy.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/RadarGeometryTest.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/RadarLabelsTest.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ui/ScreenPolicyTest.kt`

**Interfaces:**
- Consumes: `.scene.{RadarScene, Blip, Confidence, CoverageSector, SensorStatus, Warning}`, `.config.{RADAR_A, RADAR_B}`; `ScreenMode` (Task 2).
- Produces:
  - `data class PointPx(x: Float, y: Float)`, `enum class BlipStyle { FILLED, OUTLINE, DASHED }`
  - `data class BlipDraw(center: PointPx, style: BlipStyle, alpha: Float)`, `data class EdgeMarkerDraw(inner: PointPx, outer: PointPx, alpha: Float)`, `data class SectorDraw(startAngleDeg: Float, sweepDeg: Float)`
  - `data class RadarDrawModel(origin, radiusPx, blipRadiusPx, ringRadiiPx: List<Float>, sectors, blips, edgeMarkers, dimmed: Boolean)`
  - `fun toDrawModel(scene: RadarScene?, widthPx: Float, heightPx: Float, offset: PointPx, showContacts: Boolean): RadarDrawModel`
  - `fun showContacts(scene: RadarScene?, ambient: Boolean): Boolean`, `fun polarToPx(origin, radiusPx, bearingDeg, rangeM): PointPx`, `fun sectorArc(sector): SectorDraw`, `fun blipStyle(confidence): BlipStyle`, `fun blipAlpha(ageMs: Long): Float`
  - `const val NO_DATA_LABEL = "--"`, `ELIMINATED_LABEL = "ELIMINADO"`, `NO_WATCH_STEPS_LABEL = "SIN PASOS"`
  - `fun centerLabel(scene, ambient): String?`, `fun warningLabel(warnings: Set<Warning>): String?`, `fun labelFor(warning: Warning): String`
  - `data class StatusItem(label: String, ok: Boolean)`, `fun statusItems(scene, watchSteps): List<StatusItem>`, `fun eliminatedActionLabel(eliminated: Boolean): String`
  - `fun keepScreenOn(mode: ScreenMode, eliminated: Boolean): Boolean`, `fun burnInOffset(mode: ScreenMode, elapsedMs: Long): PointPx`

Layout constants:
- Origin at (w/2, 0.58·h), radius 0.42·min(w, h). On the 480 px Galaxy Watch 7 (44 mm), that gives a ±100° fan with every point ≥ 10 px from the round edge.
- Blip radius 0.035·min(w, h).
- Canvas angles: 0° = 3 o'clock, clockwise. Display bearing 0 = up, clockwise. Therefore `canvasAngle = bearing − 90`.

There is no sweep animation in the MVP. The honest-state rule of spec §5.3 is met by the gray `--` state, which follows `RadarScene.linkUp`.

- [ ] **Step 1: Write the failing tests**

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/RadarGeometryTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui.radar

import io.github.santiquiroz.blindside.core.scene.Blip
import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.core.scene.CoverageSector
import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RadarGeometryTest {
    private val origin = PointPx(240f, 278f)
    private val radius = 200f
    private val noShift = PointPx(0f, 0f)

    private fun assertPoint(expected: PointPx, actual: PointPx) {
        assertEquals(expected.x, actual.x, 1e-3f)
        assertEquals(expected.y, actual.y, 1e-3f)
    }

    private fun blip(id: Int, bearing: Double, range: Double, confidence: Confidence = Confidence.BOTH, ageMs: Long = 0, outOfView: Boolean = false) =
        Blip(displayId = id, bearingDeg = bearing, rangeM = range, confidence = confidence, ageMs = ageMs, outOfView = outOfView)

    private fun scene(blips: List<Blip>, linkUp: Boolean = true, eliminated: Boolean = false) = RadarScene(
        blips = blips,
        coverage = listOf(CoverageSector(-100.0, 80.0)),
        linkUp = linkUp,
        radars = listOf(SensorStatus(0, true), SensorStatus(1, true)),
        imus = listOf(SensorStatus(0, true), SensorStatus(1, true)),
        motion = MotionState.STILL,
        warnings = emptySet(),
        eliminated = eliminated,
    )

    @Test
    fun `zero bearing points straight up`() {
        assertPoint(PointPx(240f, 178f), polarToPx(origin, radius, 0.0, 3.0))
    }

    @Test
    fun `positive bearings turn clockwise to the right`() {
        assertPoint(PointPx(440f, 278f), polarToPx(origin, radius, 90.0, 6.0))
    }

    @Test
    fun `ranges beyond six metres sit on the edge`() {
        assertPoint(PointPx(40f, 278f), polarToPx(origin, radius, -90.0, 12.0))
    }

    @Test
    fun `the right-handed coverage becomes a canvas arc`() {
        assertEquals(SectorDraw(-190f, 180f), sectorArc(CoverageSector(-100.0, 80.0)))
    }

    @Test
    fun `fill encodes confidence`() {
        assertEquals(BlipStyle.FILLED, blipStyle(Confidence.BOTH))
        assertEquals(BlipStyle.OUTLINE, blipStyle(Confidence.SINGLE))
        assertEquals(BlipStyle.DASHED, blipStyle(Confidence.COASTING))
    }

    @Test
    fun `opacity fades with age down to a floor`() {
        assertEquals(1f, blipAlpha(0L), 1e-6f)
        assertEquals(0.5f, blipAlpha(3_000L), 1e-6f)
        assertEquals(MIN_BLIP_ALPHA, blipAlpha(9_000L), 1e-6f)
    }

    @Test
    fun `the origin sits below the centre and rings mark two and four metres`() {
        val model = toDrawModel(scene(emptyList()), 480f, 480f, noShift, showContacts = true)
        assertTrue(model.origin.y > 240f)
        assertEquals(2, model.ringRadiiPx.size)
        assertEquals(model.radiusPx / 3f, model.ringRadiiPx[0], 1e-3f)
        assertEquals(model.radiusPx * 2f / 3f, model.ringRadiiPx[1], 1e-3f)
    }

    @Test
    fun `live contacts are drawn with their style`() {
        val model = toDrawModel(scene(listOf(blip(1, 0.0, 3.0, Confidence.SINGLE))), 480f, 480f, noShift, showContacts = true)
        assertEquals(BlipStyle.OUTLINE, model.blips.single().style)
        assertFalse(model.dimmed)
    }

    @Test
    fun `hidden contacts leave the fan dimmed but drawn`() {
        val model = toDrawModel(scene(listOf(blip(1, 0.0, 3.0))), 480f, 480f, noShift, showContacts = false)
        assertTrue(model.blips.isEmpty())
        assertTrue(model.dimmed)
        assertEquals(1, model.sectors.size)
    }

    @Test
    fun `out of view contacts become edge markers`() {
        val model = toDrawModel(scene(listOf(blip(2, 95.0, 4.0, outOfView = true))), 480f, 480f, noShift, showContacts = true)
        assertTrue(model.blips.isEmpty())
        assertEquals(1, model.edgeMarkers.size)
    }

    @Test
    fun `the burn-in offset moves everything`() {
        val still = toDrawModel(scene(emptyList()), 480f, 480f, noShift, showContacts = true)
        val shifted = toDrawModel(scene(emptyList()), 480f, 480f, PointPx(2f, -2f), showContacts = true)
        assertPoint(PointPx(still.origin.x + 2f, still.origin.y - 2f), shifted.origin)
    }

    @Test
    fun `contacts show only with a live link, in play and out of ambient`() {
        assertTrue(showContacts(scene(emptyList()), ambient = false))
        assertFalse(showContacts(null, ambient = false))
        assertFalse(showContacts(scene(emptyList(), linkUp = false), ambient = false))
        assertFalse(showContacts(scene(emptyList(), eliminated = true), ambient = false))
        assertFalse(showContacts(scene(emptyList()), ambient = true))
    }
}
```

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/RadarLabelsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui.radar

import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import io.github.santiquiroz.blindside.core.scene.Warning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RadarLabelsTest {
    private fun scene(linkUp: Boolean = true, eliminated: Boolean = false, imuBAlive: Boolean = true) = RadarScene(
        blips = emptyList(),
        coverage = emptyList(),
        linkUp = linkUp,
        radars = listOf(SensorStatus(0, true), SensorStatus(1, true)),
        imus = listOf(SensorStatus(0, true), SensorStatus(1, imuBAlive)),
        motion = MotionState.STILL,
        warnings = emptySet(),
        eliminated = eliminated,
    )

    @Test
    fun `no data shows dashes`() {
        assertEquals(NO_DATA_LABEL, centerLabel(null, ambient = false))
        assertEquals(NO_DATA_LABEL, centerLabel(scene(linkUp = false), ambient = false))
        assertEquals(NO_DATA_LABEL, centerLabel(scene(), ambient = true))
    }

    @Test
    fun `eliminated wins over everything else`() {
        assertEquals(ELIMINATED_LABEL, centerLabel(scene(linkUp = false, eliminated = true), ambient = false))
    }

    @Test
    fun `a live scene has no centre label`() {
        assertNull(centerLabel(scene(), ambient = false))
    }

    @Test
    fun `the most serious warning is shown`() {
        assertEquals("RADAR CAÍDO", warningLabel(setOf(Warning.YAW_UNCALIBRATED, Warning.RADAR_DOWN)))
        assertEquals("RUMBO SIN CALIBRAR", warningLabel(setOf(Warning.YAW_UNCALIBRATED)))
        assertNull(warningLabel(emptySet()))
    }

    @Test
    fun `link lost alone is left to the dashes`() {
        assertNull(warningLabel(setOf(Warning.LINK_LOST)))
    }

    @Test
    fun `every warning has a label`() {
        assertTrue(Warning.entries.all { labelFor(it).isNotBlank() })
    }

    @Test
    fun `the mini status lists link, radars and imus`() {
        val items = statusItems(scene(imuBAlive = false), watchSteps = true)
        assertEquals(listOf("BLE", "R-A", "R-B", "I-A", "I-B"), items.map { it.label })
        assertEquals(listOf(true, true, true, true, false), items.map { it.ok })
    }

    @Test
    fun `missing watch steps are flagged`() {
        assertEquals(StatusItem(NO_WATCH_STEPS_LABEL, ok = false), statusItems(scene(), watchSteps = false).last())
    }

    @Test
    fun `without a scene nothing is ok`() {
        assertTrue(statusItems(null, watchSteps = true).none { it.ok })
    }

    @Test
    fun `the eliminated button names its action`() {
        assertEquals("ME DIERON", eliminatedActionLabel(eliminated = false))
        assertEquals("REAPARECÍ", eliminatedActionLabel(eliminated = true))
    }
}
```

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ui/ScreenPolicyTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui

import io.github.santiquiroz.blindside.wear.settings.ScreenMode
import io.github.santiquiroz.blindside.wear.ui.radar.PointPx
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScreenPolicyTest {
    @Test
    fun `only vista keeps the screen on`() {
        assertTrue(keepScreenOn(ScreenMode.VISTA, eliminated = false))
        assertFalse(keepScreenOn(ScreenMode.SIGILO, eliminated = false))
    }

    @Test
    fun `eliminated lets the screen turn off even in vista`() {
        assertFalse(keepScreenOn(ScreenMode.VISTA, eliminated = true))
    }

    @Test
    fun `sigilo never shifts`() {
        assertEquals(PointPx(0f, 0f), burnInOffset(ScreenMode.SIGILO, 7 * BURN_IN_STEP_MS))
    }

    @Test
    fun `vista shifts two pixels every few minutes and cycles`() {
        assertEquals(PointPx(0f, 0f), burnInOffset(ScreenMode.VISTA, 0L))
        assertEquals(PointPx(2f, 0f), burnInOffset(ScreenMode.VISTA, BURN_IN_STEP_MS))
        assertEquals(PointPx(2f, 2f), burnInOffset(ScreenMode.VISTA, 2 * BURN_IN_STEP_MS))
        assertEquals(PointPx(0f, 0f), burnInOffset(ScreenMode.VISTA, 8 * BURN_IN_STEP_MS))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :wear-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.wear.ui.*"`
Expected: FAIL at compilation with `Unresolved reference 'PointPx'`.

- [ ] **Step 3: Write the implementation**

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/RadarGeometry.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui.radar

import io.github.santiquiroz.blindside.core.scene.Blip
import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.core.scene.CoverageSector
import io.github.santiquiroz.blindside.core.scene.RadarScene
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

data class PointPx(val x: Float, val y: Float)

enum class BlipStyle { FILLED, OUTLINE, DASHED }

data class BlipDraw(val center: PointPx, val style: BlipStyle, val alpha: Float)

data class EdgeMarkerDraw(val inner: PointPx, val outer: PointPx, val alpha: Float)

data class SectorDraw(val startAngleDeg: Float, val sweepDeg: Float)

data class RadarDrawModel(
    val origin: PointPx,
    val radiusPx: Float,
    val blipRadiusPx: Float,
    val ringRadiiPx: List<Float>,
    val sectors: List<SectorDraw>,
    val blips: List<BlipDraw>,
    val edgeMarkers: List<EdgeMarkerDraw>,
    val dimmed: Boolean,
)

const val MAX_RANGE_M = 6.0
const val ORIGIN_Y_FRACTION = 0.58f
const val RADIUS_FRACTION = 0.42f
const val BLIP_RADIUS_FRACTION = 0.035f
const val MIN_BLIP_ALPHA = 0.3f

private const val FULL_FADE_MS = 6_000.0
private const val EDGE_MARKER_INNER_M = 5.4
private const val CANVAS_ZERO_OFFSET_DEG = 90.0
private val RING_RANGES_M = listOf(2.0, 4.0)

fun toDrawModel(scene: RadarScene?, widthPx: Float, heightPx: Float, offset: PointPx, showContacts: Boolean): RadarDrawModel {
    val side = min(widthPx, heightPx)
    val origin = PointPx(widthPx / 2f + offset.x, heightPx * ORIGIN_Y_FRACTION + offset.y)
    val radius = side * RADIUS_FRACTION
    val blips = if (showContacts) scene?.blips.orEmpty() else emptyList()
    return RadarDrawModel(
        origin = origin,
        radiusPx = radius,
        blipRadiusPx = side * BLIP_RADIUS_FRACTION,
        ringRadiiPx = RING_RANGES_M.map { (it / MAX_RANGE_M).toFloat() * radius },
        sectors = scene?.coverage.orEmpty().map(::sectorArc),
        blips = blips.filterNot { it.outOfView }.map { blipDraw(it, origin, radius) },
        edgeMarkers = blips.filter { it.outOfView }.map { edgeMarker(it, origin, radius) },
        dimmed = !showContacts,
    )
}

fun showContacts(scene: RadarScene?, ambient: Boolean): Boolean =
    scene != null && scene.linkUp && !scene.eliminated && !ambient

fun polarToPx(origin: PointPx, radiusPx: Float, bearingDeg: Double, rangeM: Double): PointPx {
    val distance = rangeM.coerceIn(0.0, MAX_RANGE_M) / MAX_RANGE_M * radiusPx
    val radians = Math.toRadians(bearingDeg)
    return PointPx(origin.x + (distance * sin(radians)).toFloat(), origin.y - (distance * cos(radians)).toFloat())
}

fun sectorArc(sector: CoverageSector): SectorDraw =
    SectorDraw((sector.fromDeg - CANVAS_ZERO_OFFSET_DEG).toFloat(), sweepDeg(sector).toFloat())

fun blipStyle(confidence: Confidence): BlipStyle = when (confidence) {
    Confidence.BOTH -> BlipStyle.FILLED
    Confidence.SINGLE -> BlipStyle.OUTLINE
    Confidence.COASTING -> BlipStyle.DASHED
}

fun blipAlpha(ageMs: Long): Float = (1.0 - ageMs / FULL_FADE_MS).toFloat().coerceIn(MIN_BLIP_ALPHA, 1f)

private fun sweepDeg(sector: CoverageSector): Double {
    val raw = sector.toDeg - sector.fromDeg
    return if (raw < 0) raw + 360.0 else raw
}

private fun blipDraw(blip: Blip, origin: PointPx, radius: Float): BlipDraw =
    BlipDraw(polarToPx(origin, radius, blip.bearingDeg, blip.rangeM), blipStyle(blip.confidence), blipAlpha(blip.ageMs))

private fun edgeMarker(blip: Blip, origin: PointPx, radius: Float): EdgeMarkerDraw = EdgeMarkerDraw(
    inner = polarToPx(origin, radius, blip.bearingDeg, EDGE_MARKER_INNER_M),
    outer = polarToPx(origin, radius, blip.bearingDeg, MAX_RANGE_M),
    alpha = blipAlpha(blip.ageMs),
)
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/RadarLabels.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui.radar

import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import io.github.santiquiroz.blindside.core.scene.Warning
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B

data class StatusItem(val label: String, val ok: Boolean)

const val NO_DATA_LABEL = "--"
const val ELIMINATED_LABEL = "ELIMINADO"
const val NO_WATCH_STEPS_LABEL = "SIN PASOS"

private val WARNING_PRIORITY = listOf(
    Warning.RADAR_DOWN,
    Warning.IMU_DOWN,
    Warning.NO_IMU_COMPENSATION,
    Warning.PRONE,
    Warning.ALERT_OVERFLOW,
    Warning.CORRUPT_FRAMES,
    Warning.YAW_UNCALIBRATED,
)

fun centerLabel(scene: RadarScene?, ambient: Boolean): String? = when {
    scene?.eliminated == true -> ELIMINATED_LABEL
    scene == null || !scene.linkUp || ambient -> NO_DATA_LABEL
    else -> null
}

fun warningLabel(warnings: Set<Warning>): String? = WARNING_PRIORITY.firstOrNull { it in warnings }?.let(::labelFor)

fun labelFor(warning: Warning): String = when (warning) {
    Warning.LINK_LOST -> "SIN ENLACE"
    Warning.RADAR_DOWN -> "RADAR CAÍDO"
    Warning.IMU_DOWN -> "IMU CAÍDO"
    Warning.NO_IMU_COMPENSATION -> "MÁS FANTASMAS AL MOVERTE"
    Warning.PRONE -> "RADAR DEGRADADO"
    Warning.CORRUPT_FRAMES -> "TRAMAS CORRUPTAS"
    Warning.ALERT_OVERFLOW -> "SATURADO"
    Warning.YAW_UNCALIBRATED -> "RUMBO SIN CALIBRAR"
}

fun statusItems(scene: RadarScene?, watchSteps: Boolean): List<StatusItem> = listOfNotNull(
    StatusItem("BLE", scene?.linkUp == true),
    StatusItem("R-A", isAlive(scene?.radars, RADAR_A)),
    StatusItem("R-B", isAlive(scene?.radars, RADAR_B)),
    StatusItem("I-A", isAlive(scene?.imus, RADAR_A)),
    StatusItem("I-B", isAlive(scene?.imus, RADAR_B)),
    if (watchSteps) null else StatusItem(NO_WATCH_STEPS_LABEL, ok = false),
)

fun eliminatedActionLabel(eliminated: Boolean): String = if (eliminated) "REAPARECÍ" else "ME DIERON"

private fun isAlive(sensors: List<SensorStatus>?, id: Int): Boolean = sensors?.firstOrNull { it.id == id }?.alive == true
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/ScreenPolicy.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui

import io.github.santiquiroz.blindside.wear.settings.ScreenMode
import io.github.santiquiroz.blindside.wear.ui.radar.PointPx

const val BURN_IN_STEP_MS = 3 * 60 * 1_000L
private const val BURN_IN_SHIFT_PX = 2f
private val BURN_IN_STEPS = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1)

fun keepScreenOn(mode: ScreenMode, eliminated: Boolean): Boolean = mode == ScreenMode.VISTA && !eliminated

fun burnInOffset(mode: ScreenMode, elapsedMs: Long): PointPx {
    if (mode != ScreenMode.VISTA) return PointPx(0f, 0f)
    val (dx, dy) = BURN_IN_STEPS[((elapsedMs / BURN_IN_STEP_MS) % BURN_IN_STEPS.size).toInt()]
    return PointPx(dx * BURN_IN_SHIFT_PX, dy * BURN_IN_SHIFT_PX)
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :wear-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.wear.ui.*"`
Expected: PASS (26 tests).

- [ ] **Step 5: Commit**

```bash
git add watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ui
git commit -m "feat: modelo de dibujo del radar, etiquetas de estado y política de pantalla"
```

---

### Task 12: Radar screen (Canvas, mini status, eliminated button, Vista/Sigilo, ambient)

Spec §5.3, §5.4, §5.6 (the big eliminated button on the app screen), §10.1.

**Files:**
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/Palette.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/AmbientObserver.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/ScreenEffects.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/RadarCanvas.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/RadarScreen.kt`

**Interfaces:**
- Consumes: Task 11; `SessionUiState`, `SessionStore` (Task 10); `AppSettings` (Task 2).
- Produces:
  - `fun createAmbientObserver(activity: Activity): AmbientLifecycleObserver`, which writes `SessionStore.setAmbient` (Task 10), so the service's scene ticker and the screen read one ambient flag
  - `@Composable fun KeepScreenOn(enabled: Boolean)`, `@Composable fun ReportRadarVisibility()`
  - `fun DrawScope.drawRadar(model: RadarDrawModel)`
  - `@Composable fun RadarScreen(session: SessionUiState, settings: AppSettings, ambient: Boolean, onToggleEliminated: () -> Unit)`
  - Colours: `CONTACT_RED`, `FAN_LINE`, `RING_LINE`, `DIMMED_LINE`, `LABEL_GRAY`, `WARNING_AMBER`, `STATUS_OK`, `STATUS_BAD`

How the screen behaves:
- While visible (lifecycle STARTED), the screen tells `SessionStore` so the service ticks scenes: 30 fps in Vista, 10 fps in Sigilo.
- Keep-screen-on follows `keepScreenOn(mode, eliminated)`.
- Ambient mode (Sigilo with the system's always-on display) hides contacts and shows `--`, because ambient updates come only about once a minute.
- In ambient, only the dimmed fan and `--` are drawn: no eliminated chip, status row or warning (spec §5.4: ≤ 15 % lit pixels, a 10 px margin, no solid blocks, no animation). The service stops ticking scenes (`scenePeriodMs(…, ambient = true)` returns null, Task 5).

- [ ] **Step 1: Write palette, ambient bridge and screen effects**

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/Palette.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui

import androidx.compose.ui.graphics.Color

val CONTACT_RED = Color(0xFFE53935)
val FAN_LINE = Color(0xFF3A3A3A)
val RING_LINE = Color(0xFF2C2C2C)
val DIMMED_LINE = Color(0xFF1F1F1F)
val LABEL_GRAY = Color(0xFF6E6E6E)
val WARNING_AMBER = Color(0xFFB8860B)
val STATUS_OK = Color(0xFF4F7F4F)
val STATUS_BAD = Color(0xFFB03A2E)
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/AmbientObserver.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui

import android.app.Activity
import androidx.wear.ambient.AmbientLifecycleObserver
import io.github.santiquiroz.blindside.wear.session.SessionStore

fun createAmbientObserver(activity: Activity): AmbientLifecycleObserver =
    AmbientLifecycleObserver(activity, object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) = SessionStore.setAmbient(true)

        override fun onExitAmbient() = SessionStore.setAmbient(false)

        override fun onUpdateAmbient() = Unit
    })
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/ScreenEffects.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LifecycleStartEffect
import io.github.santiquiroz.blindside.wear.session.SessionStore

@Composable
fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
fun ReportRadarVisibility() {
    LifecycleStartEffect(Unit) {
        SessionStore.setRadarVisible(true)
        onStopOrDispose { SessionStore.setRadarVisible(false) }
    }
}
```

- [ ] **Step 2: Write the canvas drawing**

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/RadarCanvas.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui.radar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.santiquiroz.blindside.wear.ui.CONTACT_RED
import io.github.santiquiroz.blindside.wear.ui.DIMMED_LINE
import io.github.santiquiroz.blindside.wear.ui.FAN_LINE
import io.github.santiquiroz.blindside.wear.ui.RING_LINE

private const val LINE_WIDTH_PX = 2f
private const val BLIP_STROKE_PX = 3f
private const val EDGE_MARKER_WIDTH_PX = 5f
private val DASHED = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))

fun DrawScope.drawRadar(model: RadarDrawModel) {
    drawFan(model)
    drawRings(model)
    model.blips.forEach { drawBlip(it, model.blipRadiusPx) }
    model.edgeMarkers.forEach { drawEdgeMarker(it) }
}

private fun DrawScope.drawFan(model: RadarDrawModel) {
    val color = if (model.dimmed) DIMMED_LINE else FAN_LINE
    model.sectors.forEach { drawSectorArc(model.origin, model.radiusPx, it, color, useCenter = true) }
}

private fun DrawScope.drawRings(model: RadarDrawModel) {
    val color = if (model.dimmed) DIMMED_LINE else RING_LINE
    model.ringRadiiPx.forEach { radius ->
        model.sectors.forEach { drawSectorArc(model.origin, radius, it, color, useCenter = false) }
    }
}

private fun DrawScope.drawSectorArc(origin: PointPx, radius: Float, sector: SectorDraw, color: Color, useCenter: Boolean) {
    drawArc(
        color = color,
        startAngle = sector.startAngleDeg,
        sweepAngle = sector.sweepDeg,
        useCenter = useCenter,
        topLeft = Offset(origin.x - radius, origin.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = LINE_WIDTH_PX),
    )
}

private fun DrawScope.drawBlip(blip: BlipDraw, radius: Float) {
    val color = CONTACT_RED.copy(alpha = blip.alpha)
    val center = Offset(blip.center.x, blip.center.y)
    when (blip.style) {
        BlipStyle.FILLED -> drawCircle(color, radius, center)
        BlipStyle.OUTLINE -> drawCircle(color, radius, center, style = Stroke(width = BLIP_STROKE_PX))
        BlipStyle.DASHED -> drawCircle(color, radius, center, style = Stroke(width = BLIP_STROKE_PX, pathEffect = DASHED))
    }
}

private fun DrawScope.drawEdgeMarker(marker: EdgeMarkerDraw) {
    drawLine(
        color = CONTACT_RED.copy(alpha = marker.alpha),
        start = Offset(marker.inner.x, marker.inner.y),
        end = Offset(marker.outer.x, marker.outer.y),
        strokeWidth = EDGE_MARKER_WIDTH_PX,
        cap = StrokeCap.Round,
    )
}
```

- [ ] **Step 3: Write the radar screen**

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/RadarScreen.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui.radar

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.wear.session.SessionUiState
import io.github.santiquiroz.blindside.wear.settings.AppSettings
import io.github.santiquiroz.blindside.wear.ui.KeepScreenOn
import io.github.santiquiroz.blindside.wear.ui.LABEL_GRAY
import io.github.santiquiroz.blindside.wear.ui.ReportRadarVisibility
import io.github.santiquiroz.blindside.wear.ui.STATUS_BAD
import io.github.santiquiroz.blindside.wear.ui.STATUS_OK
import io.github.santiquiroz.blindside.wear.ui.WARNING_AMBER
import io.github.santiquiroz.blindside.wear.ui.burnInOffset
import io.github.santiquiroz.blindside.wear.ui.keepScreenOn
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val BURN_IN_CLOCK_TICK_MS = 30_000L

@Composable
fun RadarScreen(
    session: SessionUiState,
    settings: AppSettings,
    ambient: Boolean,
    onToggleEliminated: () -> Unit,
) {
    ReportRadarVisibility()
    KeepScreenOn(keepScreenOn(settings.screenMode, settings.eliminated))
    val elapsedMs by rememberElapsedMs()
    val shift = burnInOffset(settings.screenMode, elapsedMs)
    val scene = session.scene
    val contacts = showContacts(scene, ambient)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRadar(toDrawModel(scene, size.width, size.height, shift, contacts))
        }
        RadarOverlay(scene, ambient, session.watchSteps, settings.eliminated, shift, onToggleEliminated)
    }
}

@Composable
private fun RadarOverlay(
    scene: RadarScene?,
    ambient: Boolean,
    watchSteps: Boolean,
    eliminated: Boolean,
    shift: PointPx,
    onToggleEliminated: () -> Unit,
) {
    Box(Modifier.fillMaxSize().offset { IntOffset(shift.x.roundToInt(), shift.y.roundToInt()) }) {
        centerLabel(scene, ambient)?.let { label ->
            Text(label, Modifier.align(Alignment.Center), color = LABEL_GRAY, fontSize = 26.sp)
        }
        // Spec §5.4: the dimmed screen keeps ≤ 15 % lit pixels, so ambient shows only the fan and "--".
        if (!ambient) BottomPanel(scene, watchSteps, eliminated, onToggleEliminated, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun BottomPanel(
    scene: RadarScene?,
    watchSteps: Boolean,
    eliminated: Boolean,
    onToggleEliminated: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier.padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        warningLabel(scene?.warnings.orEmpty())?.let { Text(it, color = WARNING_AMBER, fontSize = 11.sp) }
        StatusRow(statusItems(scene, watchSteps))
        EliminatedChip(eliminated, onToggleEliminated)
    }
}

@Composable
private fun StatusRow(items: List<StatusItem>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            Text(item.label, color = if (item.ok) STATUS_OK else STATUS_BAD, fontSize = 10.sp)
        }
    }
}

@Composable
private fun EliminatedChip(eliminated: Boolean, onToggle: () -> Unit) {
    Chip(
        label = { Text(eliminatedActionLabel(eliminated), maxLines = 1) },
        onClick = onToggle,
        colors = if (eliminated) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(0.6f),
    )
}

@Composable
private fun rememberElapsedMs(): State<Long> = produceState(SystemClock.elapsedRealtime()) {
    while (true) {
        delay(BURN_IN_CLOCK_TICK_MS)
        value = SystemClock.elapsedRealtime()
    }
}
```

- [ ] **Step 4: Build**

Run: `./gradlew :wear-app:assembleDebug :wear-app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests PASS. The screen becomes reachable in Task 14. Its on-device checks are in Task 15.

- [ ] **Step 5: Commit**

```bash
git add watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui
git commit -m "feat: pantalla del radar con abanico, estado mínimo, botón de eliminado y modos Sigilo/Vista"
```

---

### Task 13: Practice mode (rhythm check, vibration quiz, do-not-disturb warning)

Spec §5.5 "Modo práctica":
- it is mandatory before arming Sigilo;
- the quiz uses a real vibration;
- it detects do-not-disturb, theatre or sleep mode, or a low system intensity;
- it warns when the interruption filter can silence the chosen usage.

§1 criterion 3 requires ≥ 90 % correct sides, so the quiz has 10 rounds and passes at 9.

**Files:**
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/practice/Quiz.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/Labels.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/Widgets.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/PracticeScreen.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/practice/QuizTest.kt`
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ui/LabelsTest.kt`

**Interfaces:**
- Consumes:
  - `.scene.Side`; `HapticPlayer`, `patternFor`, the four patterns, `dndMaySilence`, `interruptionFilterFrom` (Task 3).
  - `AppSettings` (Task 2), `LABEL_GRAY` and `WARNING_AMBER` (Task 12).
- Produces:
  - `data class QuizAnswer(expected: Side, given: Side) { val isCorrect }`, `data class QuizState(sequence: List<Side>, index = 0, correct = 0, lastAnswer: QuizAnswer? = null)`
  - `fun newQuiz(random: Random, length: Int = QUIZ_LENGTH)`, `fun currentSide(state): Side?`, `fun isFinished(state)`, `fun answer(state, given): QuizState`, `fun passed(state)`, `fun needsPractice(lastPassedEpochMs: Long?, nowEpochMs: Long): Boolean`
  - `const val QUIZ_LENGTH = 10`, `QUIZ_PASS_MIN_CORRECT = 9`, `PRACTICE_VALID_MS` (12 h)
  - `ui/Labels.kt`: `yesNo`, `sideLabel`, `sideShortLabel`, `quizProgressLabel`, `answerFeedback`, `quizResultLabel`, `motorLabel`, `DND_WARNING_MESSAGE`, `data class PracticeRhythm(label, pattern)`, `val PRACTICE_RHYTHMS` (Task 14 adds more labels here)
  - `ui/Widgets.kt`: `@Composable NavChip(label, onClick)`, `SettingChip(title, value, onClick)`, `Notice(text)`
  - `@Composable fun PracticeScreen(settings: AppSettings, onQuizPassed: () -> Unit)`

- [ ] **Step 1: Write the failing tests**

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/practice/QuizTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.practice

import io.github.santiquiroz.blindside.core.scene.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class QuizTest {
    private fun wrong(side: Side): Side = Side.entries[(side.ordinal + 1) % Side.entries.size]

    private fun play(quiz: QuizState, mistakes: Int): QuizState =
        quiz.sequence.foldIndexed(quiz) { index, state, side -> answer(state, if (index < mistakes) wrong(side) else side) }

    @Test
    fun `a new quiz has ten rounds with every side at least three times`() {
        val quiz = newQuiz(Random(7))
        assertEquals(QUIZ_LENGTH, quiz.sequence.size)
        Side.entries.forEach { side -> assertTrue(quiz.sequence.count { it == side } >= 3) }
    }

    @Test
    fun `a perfect run passes`() {
        val done = play(newQuiz(Random(1)), mistakes = 0)
        assertTrue(isFinished(done))
        assertTrue(passed(done))
        assertEquals(10, done.correct)
    }

    @Test
    fun `one mistake in ten still meets the ninety percent target`() {
        assertTrue(passed(play(newQuiz(Random(3)), mistakes = 1)))
    }

    @Test
    fun `two mistakes fail the quiz`() {
        val done = play(newQuiz(Random(2)), mistakes = 2)
        assertFalse(passed(done))
        assertEquals(8, done.correct)
    }

    @Test
    fun `the last answer is kept for feedback`() {
        val after = answer(QuizState(listOf(Side.LEFT, Side.RIGHT)), Side.CENTER)
        assertEquals(QuizAnswer(Side.LEFT, Side.CENTER), after.lastAnswer)
        assertFalse(after.lastAnswer!!.isCorrect)
        assertEquals(Side.RIGHT, currentSide(after))
    }

    @Test
    fun `answers after the end change nothing`() {
        val done = play(newQuiz(Random(4)), mistakes = 0)
        assertEquals(done, answer(done, Side.LEFT))
    }

    @Test
    fun `practice is due when never passed or older than twelve hours`() {
        assertTrue(needsPractice(lastPassedEpochMs = null, nowEpochMs = 0L))
        assertFalse(needsPractice(lastPassedEpochMs = 0L, nowEpochMs = PRACTICE_VALID_MS))
        assertTrue(needsPractice(lastPassedEpochMs = 0L, nowEpochMs = PRACTICE_VALID_MS + 1))
    }
}
```

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ui/LabelsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui

import io.github.santiquiroz.blindside.core.scene.Side
import io.github.santiquiroz.blindside.wear.practice.QuizAnswer
import io.github.santiquiroz.blindside.wear.practice.QuizState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LabelsTest {
    @Test
    fun `wrong answers name the expected side`() {
        assertEquals("Correcto", answerFeedback(QuizAnswer(Side.LEFT, Side.LEFT)))
        assertEquals("Era derecha", answerFeedback(QuizAnswer(Side.RIGHT, Side.CENTER)))
    }

    @Test
    fun `the quiz result says whether it passed`() {
        val passedQuiz = QuizState(sequence = List(10) { Side.LEFT }, index = 10, correct = 9)
        val failedQuiz = passedQuiz.copy(correct = 8)
        assertEquals("9/10: aprobado", quizResultLabel(passedQuiz))
        assertEquals("8/10: repite", quizResultLabel(failedQuiz))
    }

    @Test
    fun `progress counts from one`() {
        assertEquals("Intento 1 de 10", quizProgressLabel(QuizState(sequence = List(10) { Side.LEFT })))
    }

    @Test
    fun `the motor label reports both capabilities`() {
        assertEquals("Motor: amplitud sí, primitivas no", motorLabel(amplitudeControl = true, primitives = false))
    }

    @Test
    fun `answer chips fit three letters`() {
        assertTrue(Side.entries.all { sideShortLabel(it).length <= 3 })
    }

    @Test
    fun `practice offers every rhythm including the system buzz`() {
        assertEquals(listOf("izquierda", "centro", "derecha", "sistema"), PRACTICE_RHYTHMS.map { it.label })
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :wear-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.wear.practice.*" --tests "io.github.santiquiroz.blindside.wear.ui.LabelsTest"`
Expected: FAIL at compilation with `Unresolved reference 'newQuiz'`.

- [ ] **Step 3: Write the quiz and the labels**

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/practice/Quiz.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.practice

import io.github.santiquiroz.blindside.core.scene.Side
import kotlin.random.Random

const val QUIZ_LENGTH = 10
const val QUIZ_PASS_MIN_CORRECT = 9
const val PRACTICE_VALID_MS = 12 * 60 * 60 * 1_000L

data class QuizAnswer(val expected: Side, val given: Side) {
    val isCorrect: Boolean get() = expected == given
}

data class QuizState(
    val sequence: List<Side>,
    val index: Int = 0,
    val correct: Int = 0,
    val lastAnswer: QuizAnswer? = null,
)

fun newQuiz(random: Random, length: Int = QUIZ_LENGTH): QuizState =
    QuizState(List(length) { Side.entries[it % Side.entries.size] }.shuffled(random))

fun currentSide(state: QuizState): Side? = state.sequence.getOrNull(state.index)

fun isFinished(state: QuizState): Boolean = state.index >= state.sequence.size

fun answer(state: QuizState, given: Side): QuizState {
    val expected = currentSide(state) ?: return state
    val result = QuizAnswer(expected, given)
    val gained = if (result.isCorrect) 1 else 0
    return state.copy(index = state.index + 1, correct = state.correct + gained, lastAnswer = result)
}

fun passed(state: QuizState): Boolean = isFinished(state) && state.correct >= QUIZ_PASS_MIN_CORRECT

fun needsPractice(lastPassedEpochMs: Long?, nowEpochMs: Long): Boolean =
    lastPassedEpochMs == null || nowEpochMs - lastPassedEpochMs > PRACTICE_VALID_MS
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/Labels.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui

import io.github.santiquiroz.blindside.core.scene.Side
import io.github.santiquiroz.blindside.wear.haptics.CENTER_PATTERN
import io.github.santiquiroz.blindside.wear.haptics.HapticPattern
import io.github.santiquiroz.blindside.wear.haptics.LEFT_PATTERN
import io.github.santiquiroz.blindside.wear.haptics.RIGHT_PATTERN
import io.github.santiquiroz.blindside.wear.haptics.SYSTEM_PATTERN
import io.github.santiquiroz.blindside.wear.practice.QuizAnswer
import io.github.santiquiroz.blindside.wear.practice.QuizState
import io.github.santiquiroz.blindside.wear.practice.passed

data class PracticeRhythm(val label: String, val pattern: HapticPattern)

const val DND_WARNING_MESSAGE = "No molestar activo: puede silenciar las alertas. Desactívalo o usa vibración tipo Alarma."

val PRACTICE_RHYTHMS: List<PracticeRhythm> = listOf(
    PracticeRhythm("izquierda", LEFT_PATTERN),
    PracticeRhythm("centro", CENTER_PATTERN),
    PracticeRhythm("derecha", RIGHT_PATTERN),
    PracticeRhythm("sistema", SYSTEM_PATTERN),
)

fun yesNo(value: Boolean): String = if (value) "sí" else "no"

fun sideLabel(side: Side): String = when (side) {
    Side.LEFT -> "izquierda"
    Side.CENTER -> "centro"
    Side.RIGHT -> "derecha"
}

fun sideShortLabel(side: Side): String = when (side) {
    Side.LEFT -> "IZQ"
    Side.CENTER -> "CEN"
    Side.RIGHT -> "DER"
}

fun quizProgressLabel(state: QuizState): String = "Intento ${state.index + 1} de ${state.sequence.size}"

fun answerFeedback(answer: QuizAnswer): String = if (answer.isCorrect) "Correcto" else "Era ${sideLabel(answer.expected)}"

fun quizResultLabel(state: QuizState): String =
    "${state.correct}/${state.sequence.size}: ${if (passed(state)) "aprobado" else "repite"}"

fun motorLabel(amplitudeControl: Boolean, primitives: Boolean): String =
    "Motor: amplitud ${yesNo(amplitudeControl)}, primitivas ${yesNo(primitives)}"
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :wear-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.wear.practice.*" --tests "io.github.santiquiroz.blindside.wear.ui.LabelsTest"`
Expected: PASS (13 tests).

- [ ] **Step 5: Write the shared widgets and the practice screen**

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/Widgets.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text

@Composable
fun NavChip(label: String, onClick: () -> Unit) {
    Chip(
        label = { Text(label, maxLines = 2) },
        onClick = onClick,
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun SettingChip(title: String, value: String, onClick: () -> Unit) {
    Chip(
        label = { Text(title, maxLines = 2) },
        secondaryLabel = { Text(value, maxLines = 1) },
        onClick = onClick,
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun Notice(text: String) {
    Text(text, color = LABEL_GRAY, fontSize = 12.sp, textAlign = TextAlign.Center)
}
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/PracticeScreen.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui

import android.app.NotificationManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import io.github.santiquiroz.blindside.core.scene.Side
import io.github.santiquiroz.blindside.wear.haptics.HapticPlayer
import io.github.santiquiroz.blindside.wear.haptics.InterruptionFilter
import io.github.santiquiroz.blindside.wear.haptics.dndMaySilence
import io.github.santiquiroz.blindside.wear.haptics.interruptionFilterFrom
import io.github.santiquiroz.blindside.wear.haptics.patternFor
import io.github.santiquiroz.blindside.wear.practice.QuizState
import io.github.santiquiroz.blindside.wear.practice.answer
import io.github.santiquiroz.blindside.wear.practice.currentSide
import io.github.santiquiroz.blindside.wear.practice.isFinished
import io.github.santiquiroz.blindside.wear.practice.newQuiz
import io.github.santiquiroz.blindside.wear.practice.passed
import io.github.santiquiroz.blindside.wear.settings.AppSettings
import kotlin.random.Random

@Composable
fun PracticeScreen(settings: AppSettings, onQuizPassed: () -> Unit) {
    val context = LocalContext.current
    val player = remember(settings.vibrationUsage) { HapticPlayer.create(context, settings.vibrationUsage) }
    val dndWarning = dndMaySilence(currentInterruptionFilter(context), settings.vibrationUsage)
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { ListHeader { Text("Práctica") } }
        if (dndWarning) item { Text(DND_WARNING_MESSAGE, color = WARNING_AMBER) }
        item { Notice(motorLabel(player.hasAmplitudeControl(), player.supportsPrimitives())) }
        PRACTICE_RHYTHMS.forEach { rhythm -> item { NavChip("Probar ${rhythm.label}") { player.play(rhythm.pattern) } } }
        item { QuizPanel(player, onQuizPassed) }
    }
}

@Composable
private fun QuizPanel(player: HapticPlayer, onPassed: () -> Unit) {
    var quiz by remember { mutableStateOf<QuizState?>(null) }
    val current = quiz
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            current == null -> NavChip("Empezar quiz") { quiz = newQuiz(Random.Default) }
            isFinished(current) -> QuizResult(current) { quiz = newQuiz(Random.Default) }
            else -> QuizRound(current, player) { side -> quiz = answerAndReport(current, side, onPassed) }
        }
    }
}

@Composable
private fun QuizRound(state: QuizState, player: HapticPlayer, onAnswer: (Side) -> Unit) {
    Notice(quizProgressLabel(state))
    state.lastAnswer?.let { Notice(answerFeedback(it)) }
    NavChip("Vibrar") { currentSide(state)?.let { player.play(patternFor(it)) } }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Side.entries.forEach { side ->
            CompactChip(onClick = { onAnswer(side) }, label = { Text(sideShortLabel(side)) })
        }
    }
}

@Composable
private fun QuizResult(state: QuizState, onRestart: () -> Unit) {
    Notice(quizResultLabel(state))
    NavChip("Repetir quiz", onRestart)
}

private fun answerAndReport(state: QuizState, side: Side, onPassed: () -> Unit): QuizState =
    answer(state, side).also { if (passed(it)) onPassed() }

private fun currentInterruptionFilter(context: Context): InterruptionFilter =
    interruptionFilterFrom(context.getSystemService(NotificationManager::class.java).currentInterruptionFilter)
```

- [ ] **Step 6: Build**

Run: `./gradlew :wear-app:assembleDebug :wear-app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests PASS.

- [ ] **Step 7: Commit**

```bash
git add watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/practice watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/practice watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ui
git commit -m "feat: modo práctica con quiz de vibración real y aviso de No molestar"
```

---

### Task 14: Home, settings screen, navigation and the start flow

Spec:
- §5.2: the "Iniciar partida" permission flow; a denied Bluetooth permission explains why and leads to the settings.
- §5.6: basic settings (handedness, angles, signs, screen mode); a big eliminated button; "Grabación".
- §5.5: practice is required before Sigilo.
- §1 criterion 8: no interaction in game, except for eliminated.
- §6.10 / spec §6.10 type 4: the manual marker.

**Files:**
- Modify: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/Labels.kt` (append the home/settings labels)
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/Routes.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/HomeScreen.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/SettingsScreen.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/BlindsideApp.kt`
- Modify: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/MainActivity.kt` (full replacement below)
- Keep: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/SpikeScreen.kt` (Task 10b, unchanged). **Do not delete it**: Santiago runs spikes S1, S3, S10 and S12 with the real app on 2-3 oct, so the full app hosts it at `ROUTE_SPIKES`, opened from Ajustes → "Diagnóstico (spikes)".
- Test: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ui/HomeLabelsTest.kt`

**Interfaces:**
- Consumes: every earlier task, including `SpikeScreen(settingsRepository)` (Task 10b).
- Produces:
  - `const val ROUTE_HOME`, `ROUTE_RADAR`, `ROUTE_SETTINGS`, `ROUTE_PRACTICE`, `ROUTE_SPIKES`
  - Labels: `bleStatusLabel(BleStatus)`, `sessionHeadline(SessionUiState)`, `startErrorMessage(StartError)`, `startChipLabel(practiceDue)`, `stopLabel(confirming)`, `handednessLabel`, `screenModeLabel`, `usageLabel`, `radarName(id)`, `yawLabel(id, yawDeg)`, `signLabel(sign)`, plus `BLUETOOTH_DENIED_MESSAGE`, `RECORDING_FAILED_MESSAGE`, `APPLY_ON_START_MESSAGE`, `PAIRING_FAILED_MESSAGE`, `BOND_LOST_MESSAGE`, `MTU_TOO_LOW_MESSAGE`, `LINK_HALTED_HEADLINE`, `SPIKES_ENTRY_LABEL`
  - `@Composable HomeScreen(...)`, `SettingsScreen(settings, onUpdate, onOpenSpikes)`, `BlindsideApp(settingsRepository)`

Screens:
- **Home, idle:**
  - "Iniciar partida" (or "Práctica pendiente" when no quiz passed in the last 12 h), "Demo", "Práctica", "Ajustes".
  - Start-error and Bluetooth-denied notices, with "Abrir ajustes".
  - The last recording name.
- **Home, running:**
  - The link status line and a recording-failure notice.
  - When the link is halted (`needsRetry`, Task 7): the explanation and "Reintentar". The texts are spec §5.2's: "Clave incorrecta o ventana cerrada", "El cinturón olvidó este reloj: olvídalo en los ajustes Bluetooth del reloj y vuelve a emparejar" and "MTU insuficiente". Once spike S10 finds the exact GW7 menu path, append it to `BOND_LOST_MESSAGE`.
  - "Ver radar", the eliminated toggle, "Marcar rival", and "Detener partida", which needs two taps.
- **Settings:**
  - Handedness (cycles, clears yaw overrides), screen mode, vibration usage.
  - Per radar: yaw ±5°, invert X, speed sign.
  - "Cinturón" (forget the saved address).
  - "Diagnóstico (spikes)": opens the Task 10b spike screen (start/stop with the wake-lock switch, vibration usage, marker, retry), so the spikes run with the real app.
  - A note that geometry applies at the next start.
- A successful start opens the radar screen.

- [ ] **Step 1: Write the failing test**

`watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ui/HomeLabelsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui

import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.wear.ble.BleStatus
import io.github.santiquiroz.blindside.wear.session.SessionSource
import io.github.santiquiroz.blindside.wear.session.SessionUiState
import io.github.santiquiroz.blindside.wear.session.StartError
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomeLabelsTest {
    @Test
    fun `every link status has a label`() {
        assertTrue(BleStatus.entries.all { bleStatusLabel(it).isNotBlank() })
    }

    @Test
    fun `demo sessions are headlined as demo`() {
        assertEquals("Demo", sessionHeadline(SessionUiState(running = true, source = SessionSource.DEMO)))
        assertEquals(bleStatusLabel(BleStatus.STREAMING), sessionHeadline(SessionUiState(running = true, ble = BleStatus.STREAMING)))
    }

    @Test
    fun `a halted link gets a short headline and the spec message below it`() {
        assertEquals(LINK_HALTED_HEADLINE, sessionHeadline(SessionUiState(running = true, ble = BleStatus.BOND_LOST)))
        assertEquals(BOND_LOST_MESSAGE, bleStatusLabel(BleStatus.BOND_LOST))
        assertEquals("Clave incorrecta o ventana cerrada", bleStatusLabel(BleStatus.PAIRING_FAILED))
        assertEquals("MTU insuficiente", bleStatusLabel(BleStatus.MTU_TOO_LOW))
    }

    @Test
    fun `every start error explains itself`() {
        assertTrue(StartError.entries.all { startErrorMessage(it).isNotBlank() })
    }

    @Test
    fun `the start chip asks for practice when it is due`() {
        assertEquals("Práctica pendiente", startChipLabel(practiceDue = true))
        assertEquals("Iniciar partida", startChipLabel(practiceDue = false))
    }

    @Test
    fun `stopping asks for a second tap`() {
        assertEquals("Detener partida", stopLabel(confirming = false))
        assertEquals("¿Detener? Toca otra vez", stopLabel(confirming = true))
    }

    @Test
    fun `yaw labels round and name the radar`() {
        assertEquals("A -40°", yawLabel(RADAR_A, -40.4))
        assertEquals("B 25°", yawLabel(RADAR_B, 24.6))
    }

    @Test
    fun `handedness labels are distinct`() {
        assertEquals(Handedness.entries.size, Handedness.entries.map(::handednessLabel).toSet().size)
    }

    @Test
    fun `speed signs show their sign`() {
        assertEquals("+1", signLabel(1))
        assertEquals("-1", signLabel(-1))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :wear-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.wear.ui.HomeLabelsTest"`
Expected: FAIL at compilation with `Unresolved reference 'bleStatusLabel'`.

- [ ] **Step 3: Append the labels and add the routes**

Append to `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/Labels.kt` (and add the imports shown to the file's import block):

```kotlin
import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.wear.ble.BleStatus
import io.github.santiquiroz.blindside.wear.ble.needsRetry
import io.github.santiquiroz.blindside.wear.session.SessionSource
import io.github.santiquiroz.blindside.wear.session.SessionUiState
import io.github.santiquiroz.blindside.wear.session.StartError
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.wear.settings.ScreenMode
import io.github.santiquiroz.blindside.wear.settings.VibrationUsage
import kotlin.math.roundToInt

const val BLUETOOTH_DENIED_MESSAGE = "Sin permiso de Bluetooth la partida no arranca. Concédelo en los ajustes del reloj."
const val RECORDING_FAILED_MESSAGE = "La grabación falló; la partida sigue."
const val APPLY_ON_START_MESSAGE = "Mano, ángulos y signos se aplican al iniciar la partida."
const val PAIRING_FAILED_MESSAGE = "Clave incorrecta o ventana cerrada"
const val BOND_LOST_MESSAGE =
    "El cinturón olvidó este reloj: olvídalo en los ajustes Bluetooth del reloj y vuelve a emparejar"
const val MTU_TOO_LOW_MESSAGE = "MTU insuficiente"
const val LINK_HALTED_HEADLINE = "Enlace detenido"
const val SPIKES_ENTRY_LABEL = "Diagnóstico (spikes)"

fun bleStatusLabel(status: BleStatus): String = when (status) {
    BleStatus.IDLE -> "Sin sesión"
    BleStatus.BLUETOOTH_OFF -> "Bluetooth apagado"
    BleStatus.SEARCHING -> "Buscando cinturón (BOOT 3 s para emparejar)"
    BleStatus.PAIRING -> "Emparejando: escribe la clave de la etiqueta"
    BleStatus.PAIRING_FAILED -> PAIRING_FAILED_MESSAGE
    BleStatus.CONNECTING -> "Conectando…"
    BleStatus.STREAMING -> "Recibiendo datos"
    BleStatus.RECONNECTING -> "Reconectando…"
    BleStatus.BOND_LOST -> BOND_LOST_MESSAGE
    BleStatus.MTU_TOO_LOW -> MTU_TOO_LOW_MESSAGE
}

fun sessionHeadline(session: SessionUiState): String = when {
    session.source == SessionSource.DEMO -> "Demo"
    needsRetry(session.ble) -> LINK_HALTED_HEADLINE
    else -> bleStatusLabel(session.ble)
}

fun startErrorMessage(error: StartError): String = when (error) {
    StartError.BLUETOOTH_PERMISSION_MISSING -> BLUETOOTH_DENIED_MESSAGE
    StartError.BLUETOOTH_UNAVAILABLE -> "Este reloj no tiene Bluetooth disponible."
}

fun startChipLabel(practiceDue: Boolean): String = if (practiceDue) "Práctica pendiente" else "Iniciar partida"

fun stopLabel(confirming: Boolean): String = if (confirming) "¿Detener? Toca otra vez" else "Detener partida"

fun handednessLabel(handedness: Handedness): String = when (handedness) {
    Handedness.RIGHT -> "Diestro"
    Handedness.LEFT -> "Zurdo"
    Handedness.SWITCHER -> "Cambia de hombro"
}

fun screenModeLabel(mode: ScreenMode): String = when (mode) {
    ScreenMode.SIGILO -> "Sigilo"
    ScreenMode.VISTA -> "Vista (siempre encendida)"
}

fun usageLabel(usage: VibrationUsage): String = when (usage) {
    VibrationUsage.ALARM -> "Alarma"
    VibrationUsage.NOTIFICATION -> "Notificación"
}

fun radarName(radarId: Int): String = if (radarId == RADAR_A) "Radar A (izq.)" else "Radar B (der.)"

fun yawLabel(radarId: Int, yawDeg: Double): String = "${radarLetter(radarId)} ${yawDeg.roundToInt()}°"

fun signLabel(sign: Int): String = if (sign < 0) "-1" else "+1"

private fun radarLetter(radarId: Int): String = if (radarId == RADAR_A) "A" else "B"
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/Routes.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui

const val ROUTE_HOME = "home"
const val ROUTE_RADAR = "radar"
const val ROUTE_SETTINGS = "settings"
const val ROUTE_PRACTICE = "practice"
const val ROUTE_SPIKES = "spikes"
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :wear-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.wear.ui.HomeLabelsTest"`
Expected: PASS (9 tests).

- [ ] **Step 5: Write the home screen**

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/HomeScreen.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import io.github.santiquiroz.blindside.wear.ble.needsRetry
import io.github.santiquiroz.blindside.wear.permissions.SESSION_PERMISSIONS
import io.github.santiquiroz.blindside.wear.permissions.StartDecision
import io.github.santiquiroz.blindside.wear.permissions.startDecision
import io.github.santiquiroz.blindside.wear.practice.needsPractice
import io.github.santiquiroz.blindside.wear.session.SessionCommands
import io.github.santiquiroz.blindside.wear.session.SessionSource
import io.github.santiquiroz.blindside.wear.session.SessionUiState
import io.github.santiquiroz.blindside.wear.settings.AppSettings
import io.github.santiquiroz.blindside.wear.settings.SettingsTransform
import io.github.santiquiroz.blindside.wear.ui.radar.eliminatedActionLabel

@Composable
fun HomeScreen(
    session: SessionUiState,
    settings: AppSettings,
    onNavigate: (String) -> Unit,
    onUpdateSettings: (SettingsTransform) -> Unit,
) {
    if (session.running) RunningHome(session, settings, onNavigate, onUpdateSettings) else IdleHome(session, settings, onNavigate)
}

@Composable
private fun IdleHome(session: SessionUiState, settings: AppSettings, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    var bluetoothBlocked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        bluetoothBlocked = !startBeltSession(context, startDecision(grants), onNavigate)
    }
    val practiceDue = needsPractice(settings.quizPassedAtEpochMs, System.currentTimeMillis())
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { ListHeader { Text("Blindside") } }
        if (bluetoothBlocked) item { BlockedNotice { openAppSettings(context) } }
        session.startError?.let { error -> item { Notice(startErrorMessage(error)) } }
        item {
            StartChip(
                practiceDue = practiceDue,
                onStart = { launcher.launch(SESSION_PERMISSIONS) },
                onPractice = { onNavigate(ROUTE_PRACTICE) },
            )
        }
        item { NavChip("Demo") { startDemo(context, onNavigate) } }
        item { NavChip("Práctica") { onNavigate(ROUTE_PRACTICE) } }
        item { NavChip("Ajustes") { onNavigate(ROUTE_SETTINGS) } }
        session.lastRecordingName?.let { name -> item { Notice("Última grabación: $name") } }
    }
}

@Composable
private fun RunningHome(
    session: SessionUiState,
    settings: AppSettings,
    onNavigate: (String) -> Unit,
    onUpdateSettings: (SettingsTransform) -> Unit,
) {
    val context = LocalContext.current
    var confirmingStop by remember { mutableStateOf(false) }
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { ListHeader { Text(sessionHeadline(session)) } }
        if (needsRetry(session.ble)) item { RetryNotice(bleStatusLabel(session.ble)) { SessionCommands.retryLink(context) } }
        if (session.recordingFailed) item { Text(RECORDING_FAILED_MESSAGE, color = WARNING_AMBER) }
        item { NavChip("Ver radar") { onNavigate(ROUTE_RADAR) } }
        item { NavChip(eliminatedActionLabel(settings.eliminated)) { onUpdateSettings { it.copy(eliminated = !it.eliminated) } } }
        item { NavChip("Marcar rival") { SessionCommands.marker(context) } }
        item { NavChip(stopLabel(confirmingStop)) { confirmingStop = handleStopTap(context, confirmingStop) } }
        session.recordingName?.let { name -> item { Notice(name) } }
    }
}

@Composable
private fun StartChip(practiceDue: Boolean, onStart: () -> Unit, onPractice: () -> Unit) {
    Chip(
        label = { Text(startChipLabel(practiceDue)) },
        onClick = if (practiceDue) onPractice else onStart,
        colors = ChipDefaults.primaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BlockedNotice(onOpenSettings: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Notice(BLUETOOTH_DENIED_MESSAGE)
        NavChip("Abrir ajustes", onOpenSettings)
    }
}

@Composable
private fun RetryNotice(message: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = WARNING_AMBER)
        NavChip("Reintentar", onRetry)
    }
}

private fun startBeltSession(context: Context, decision: StartDecision, onNavigate: (String) -> Unit): Boolean =
    when (decision) {
        is StartDecision.Start -> {
            SessionCommands.start(context, SessionSource.BELT)
            onNavigate(ROUTE_RADAR)
            true
        }
        StartDecision.BlockedBluetoothDenied -> false
    }

private fun startDemo(context: Context, onNavigate: (String) -> Unit) {
    SessionCommands.start(context, SessionSource.DEMO)
    onNavigate(ROUTE_RADAR)
}

private fun handleStopTap(context: Context, confirming: Boolean): Boolean {
    if (confirming) SessionCommands.stop(context)
    return !confirming
}

private fun openAppSettings(context: Context) {
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    if (!tryStartActivity(context, details)) tryStartActivity(context, Intent(Settings.ACTION_SETTINGS))
}

// Wear OS builds may not ship the per-app details screen, so fall back to the general settings.
private fun tryStartActivity(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (error: ActivityNotFoundException) {
    false
}
```

- [ ] **Step 6: Write the settings screen, the navigation host and the activity**

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/SettingsScreen.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import io.github.santiquiroz.blindside.wear.settings.AppSettings
import io.github.santiquiroz.blindside.wear.settings.DEFAULT_RADARS
import io.github.santiquiroz.blindside.wear.settings.SettingsTransform
import io.github.santiquiroz.blindside.wear.settings.YAW_STEP_DEG
import io.github.santiquiroz.blindside.wear.settings.effectiveYawDeg
import io.github.santiquiroz.blindside.wear.settings.nextHandedness
import io.github.santiquiroz.blindside.wear.settings.radar
import io.github.santiquiroz.blindside.wear.settings.toggledScreenMode
import io.github.santiquiroz.blindside.wear.settings.toggledUsage
import io.github.santiquiroz.blindside.wear.settings.withFlipXToggled
import io.github.santiquiroz.blindside.wear.settings.withHandedness
import io.github.santiquiroz.blindside.wear.settings.withSpeedSignFlipped
import io.github.santiquiroz.blindside.wear.settings.withYawNudged

@Composable
fun SettingsScreen(settings: AppSettings, onUpdate: (SettingsTransform) -> Unit, onOpenSpikes: () -> Unit) {
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { ListHeader { Text("Ajustes") } }
        item { SettingChip("Mano", handednessLabel(settings.handedness)) { onUpdate { it.withHandedness(nextHandedness(it.handedness)) } } }
        item { SettingChip("Pantalla", screenModeLabel(settings.screenMode)) { onUpdate { it.copy(screenMode = toggledScreenMode(it.screenMode)) } } }
        item { SettingChip("Vibración", usageLabel(settings.vibrationUsage)) { onUpdate { it.copy(vibrationUsage = toggledUsage(it.vibrationUsage)) } } }
        DEFAULT_RADARS.forEach { radarItems(settings, it.radarId, onUpdate) }
        item { SettingChip("Cinturón", settings.beltAddress ?: "sin emparejar") { onUpdate { it.copy(beltAddress = null) } } }
        item { NavChip(SPIKES_ENTRY_LABEL, onOpenSpikes) }
        item { Notice(APPLY_ON_START_MESSAGE) }
    }
}

private fun ScalingLazyListScope.radarItems(settings: AppSettings, radarId: Int, onUpdate: (SettingsTransform) -> Unit) {
    val radar = settings.radar(radarId)
    item { YawRow(settings, radarId, onUpdate) }
    item { SettingChip("${radarName(radarId)}: invertir X", yesNo(radar.flipX)) { onUpdate { it.withFlipXToggled(radarId) } } }
    item { SettingChip("${radarName(radarId)}: signo velocidad", signLabel(radar.speedSign)) { onUpdate { it.withSpeedSignFlipped(radarId) } } }
}

@Composable
private fun YawRow(settings: AppSettings, radarId: Int, onUpdate: (SettingsTransform) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CompactChip(onClick = { onUpdate { it.withYawNudged(radarId, -YAW_STEP_DEG) } }, label = { Text("-5°") })
        Text(yawLabel(radarId, effectiveYawDeg(settings, radarId)), fontSize = 12.sp)
        CompactChip(onClick = { onUpdate { it.withYawNudged(radarId, YAW_STEP_DEG) } }, label = { Text("+5°") })
    }
}
```

`watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/BlindsideApp.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import io.github.santiquiroz.blindside.wear.SpikeScreen
import io.github.santiquiroz.blindside.wear.session.SessionStore
import io.github.santiquiroz.blindside.wear.settings.AppSettings
import io.github.santiquiroz.blindside.wear.settings.SettingsRepository
import io.github.santiquiroz.blindside.wear.settings.SettingsTransform
import io.github.santiquiroz.blindside.wear.ui.radar.RadarScreen
import kotlinx.coroutines.launch

@Composable
fun BlindsideApp(settingsRepository: SettingsRepository) {
    val session by SessionStore.state.collectAsStateWithLifecycle()
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val ambient by SessionStore.ambient.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val navController = rememberSwipeDismissableNavController()
    val update: (SettingsTransform) -> Unit = { transform -> scope.launch { settingsRepository.update(transform) } }
    val navigate: (String) -> Unit = { route -> navController.navigate(route) }
    MaterialTheme {
        SwipeDismissableNavHost(navController = navController, startDestination = ROUTE_HOME) {
            composable(ROUTE_HOME) { HomeScreen(session, settings, navigate, update) }
            composable(ROUTE_RADAR) {
                RadarScreen(session, settings, ambient, onToggleEliminated = { update { it.copy(eliminated = !it.eliminated) } })
            }
            composable(ROUTE_SETTINGS) { SettingsScreen(settings, update, onOpenSpikes = { navigate(ROUTE_SPIKES) }) }
            composable(ROUTE_PRACTICE) {
                PracticeScreen(settings, onQuizPassed = { update { it.copy(quizPassedAtEpochMs = System.currentTimeMillis()) } })
            }
            composable(ROUTE_SPIKES) { SpikeScreen(settingsRepository) }
        }
    }
}
```

Replace `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/MainActivity.kt`. Leave `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/SpikeScreen.kt` as Task 10b wrote it; `BlindsideApp` now hosts it at `ROUTE_SPIKES`:

```kotlin
package io.github.santiquiroz.blindside.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.santiquiroz.blindside.wear.session.SessionStore
import io.github.santiquiroz.blindside.wear.settings.settingsRepository
import io.github.santiquiroz.blindside.wear.ui.BlindsideApp
import io.github.santiquiroz.blindside.wear.ui.createAmbientObserver

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A previous activity may have died in ambient; a new one always starts interactive.
        SessionStore.setAmbient(false)
        lifecycle.addObserver(createAmbientObserver(this))
        val settings = settingsRepository()
        setContent { BlindsideApp(settings) }
    }
}
```

- [ ] **Step 7: Build, run every test, and smoke-test on the emulator (if one is available)**

Run: `./gradlew :wear-app:assembleDebug :wear-app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests PASS, and `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/SpikeScreen.kt` still exists.

Optional: run a Wear OS emulator (API 34+, round). Install with `adb install -r wear-app/build/outputs/apk/debug/wear-app-debug.apk`, open Blindside, tap "Demo", and check that the radar shows moving red contacts and vibrates. Then go to Ajustes → "Diagnóstico (spikes)" and check that "Blindside (spikes)" opens with its chips. This step is optional because the emulator has no ESP32 and may lack sensors; the real checks are in Task 15.

- [ ] **Step 8: Commit**

```bash
git add watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ui/HomeLabelsTest.kt
git commit -m "feat: pantalla de inicio con flujo de permisos, ajustes básicos y navegación del reloj"
```

---

### Task 15: Module README (build, install, pairing, recordings) and Santiago's on-device checklist

Spec §6.10 (`adb pull` export, header, sizes), §5.2 (pairing through the `info` read, lost bond, MTU), §12 (calendar: the 8-oct endurance test, the 10-oct checklist), §9 (instrumented app tests and the spikes S1, S3, S8, S10, S12 that touch the app), §1 criteria 2, 3, 5, 6, 7.

**Files:**
- Create: `watch/wear-app/README.md`

**Interfaces:**
- Consumes: the finished app (Tasks 1-14).
- Produces: documentation only.

- [ ] **Step 1: Write the README**

`watch/wear-app/README.md` (in Spanish, Santiago's working language):

````markdown
# Blindside: app del reloj (`wear-app`)

App de Wear OS para el Galaxy Watch 7. Recibe por BLE los paquetes del cinturón, los procesa con `radar-core`, dibuja el radar, vibra por lado y graba la partida completa en `.bsrec`.

## Compilar y probar

Desde `watch/`:

```bash
./gradlew :wear-app:testDebugUnitTest   # pruebas JVM
./gradlew :wear-app:assembleDebug       # APK en wear-app/build/outputs/apk/debug/wear-app-debug.apk
```

## Instalar en el reloj (depuración por Wi-Fi)

1. En el reloj: Ajustes → Acerca del reloj → Información de software → tocar "Versión de software" hasta activar las opciones de desarrollador. Luego, Opciones de desarrollador → Depuración ADB → Depuración inalámbrica.
2. Emparejar una sola vez: "Emparejar nuevo dispositivo" muestra IP, puerto y código. En el PC: `adb pair IP:PUERTO_DE_EMPAREJAMIENTO`.
3. Conectar: `adb connect IP:PUERTO_DE_CONEXIÓN`. Es otro puerto, y cambia al reiniciar la depuración inalámbrica.
4. `adb install -r wear-app/build/outputs/apk/debug/wear-app-debug.apk`

## Primer emparejamiento con el cinturón

1. Enciende el cinturón. Sin un bond guardado, la ventana de emparejamiento se abre sola (60 s, LED parpadeando). Si ya hay uno, mantén BOOT 3 s en el primer minuto tras encender y suéltalo.
2. En el reloj: Blindside → "Iniciar partida". Concede Bluetooth (dispositivos cercanos) y actividad física.
3. El reloj conecta y lee `info`. Como todavía no hay bond, Android pide la clave: el estado dice "Emparejando: escribe la clave de la etiqueta" y aparece el diálogo del sistema. Escribe los 6 dígitos de la etiqueta del cinturón antes de que se cierre la ventana.
4. El estado pasa a "Recibiendo datos" y el reloj recuerda el cinturón.
5. Si sale "Clave incorrecta o ventana cerrada", vuelve a abrir la ventana (BOOT 3 s) y toca "Reintentar" en Inicio.

Para cambiar de cinturón: Ajustes → "Cinturón" (olvida la dirección) y olvídalo también en los ajustes de Bluetooth del reloj.

## Si el cinturón olvidó este reloj

Pasa tras borrar los bonds del cinturón (BOOT 10 s), emparejar otro reloj o borrar la flash. Tras dos intentos fallidos seguidos, el reloj deja de reconectar y de escanear, y muestra "El cinturón olvidó este reloj: olvídalo en los ajustes Bluetooth del reloj y vuelve a emparejar". La app no puede borrar el bond por su cuenta.

1. En el reloj, olvida "Blindside-XXXX" en los ajustes de Bluetooth. Ruta exacta en el GW7: (anótala aquí tras el spike S10).
2. Abre la ventana del cinturón (se abre sola si borraste los bonds) y toca "Reintentar" en Inicio. Empareja como la primera vez.

Si alguna vez sale "MTU insuficiente", el reloj ya reconectó una vez por su cuenta. Toca "Reintentar"; si vuelve a salir, anota el modelo del reloj y la versión del firmware. El registro `info` de la grabación trae el MTU que vio el cinturón.

## Grabaciones `.bsrec`

- Se guardan en `/sdcard/Android/data/io.github.santiquiroz.blindside/files/recordings/` con nombres `blindside-belt-AAAAMMDD-HHMMSS.bsrec` (o `-demo-`).
- La cabecera JSON lleva la primera lectura de `info` del cinturón (o `null` en la demo o si el cinturón no respondió en 10 s), `"config"` (`TuningParams` y los montajes, escritos por `PipelineConfig.toJson()` de `radar-core`, que `pipelineConfigFromHeader` relee al reproducir) y la hora de inicio.
- Tamaño esperado en 5 h: ~43 MB de paquetes BLE, ~5 MB de gravedad y ~5 MB de giroscopio del reloj, unos **53 MB** en total.
- Desde Git Bash, pon `MSYS_NO_PATHCONV=1` antes de cada comando con rutas `/sdcard/...`.

```bash
MSYS_NO_PATHCONV=1 adb shell ls -l /sdcard/Android/data/io.github.santiquiroz.blindside/files/recordings/
MSYS_NO_PATHCONV=1 adb pull /sdcard/Android/data/io.github.santiquiroz.blindside/files/recordings/ ./recordings/
```

Si `adb pull` no tiene permiso sobre `Android/data`, usa `run-as` (funciona con el APK de depuración):

```bash
MSYS_NO_PATHCONV=1 adb exec-out run-as io.github.santiquiroz.blindside cat /sdcard/Android/data/io.github.santiquiroz.blindside/files/recordings/ARCHIVO.bsrec > ARCHIVO.bsrec
```

Revisa que el archivo bajado pese lo mismo que en `ls -l`: un pull fallido puede dejar un archivo vacío sin dar error.

## Diagnóstico (spikes)

Ajustes → "Diagnóstico (spikes)" abre la pantalla de los spikes S1, S3, S10 y S12 (2-3 oct): "Partida (cinturón)", "Demo", "Wake lock: sí/no (S1)", "Vibración: ALARM/NOTIFICATION" y, con la partida activa, "Reintentar", "Marcar" y "Detener". Graba igual que una partida normal. Es una herramienta de banco: no pide la práctica previa, así que el día de juego la partida se inicia desde Inicio.

## Lista de verificación en el reloj

Marca cada punto en el reloj real. Si un punto falla, anota qué viste y la hora, para ubicarlo en la grabación. `adb logcat -s BeltGatt BeltLink` muestra cada paso del enlace.

**Antes de jugar (app)**
1. [ ] La app abre y muestra "Blindside" con Iniciar/Demo/Práctica/Ajustes. Ajustes → "Diagnóstico (spikes)" abre "Blindside (spikes)" con sus botones.
2. [ ] **Permisos:** deniega Bluetooth → la partida no arranca, aparece el mensaje y "Abrir ajustes" lleva a los ajustes del reloj. Concédelo y deniega actividad física → la partida arranca y el estado mínimo muestra "SIN PASOS". Después, con la app cerrada, quita el permiso de Bluetooth en los ajustes del reloj y vuelve a iniciar una partida: la app lo pide otra vez y, si lo niegas, muestra el mensaje sin cerrarse.
3. [ ] **Práctica (S3):** "Probar izquierda/centro/derecha/sistema" se distinguen con el brazo en movimiento. El quiz pasa con 9/10 o más. Repítelo con No molestar, modo teatro y modo dormir, y con la intensidad de vibración del sistema baja. Anota qué combinación silencia la vibración y cambia Ajustes → Vibración (Alarma/Notificación) según el resultado. Con No molestar activo debe aparecer el aviso.
4. [ ] **Demo:** el radar dibuja el abanico y contactos rojos que se mueven, vibra con el ritmo del lado correcto y en "Inicio" aparece el nombre de la grabación.
5. [ ] **Emparejamiento (S12):** el primer emparejamiento con la clave de la etiqueta funciona (el diálogo aparece al leer `info`); tras reiniciar el reloj, "Iniciar partida" reconecta solo, sin pedir la clave. Con la ventana cerrada o una clave equivocada sale "Clave incorrecta o ventana cerrada"; al abrir la ventana y tocar "Reintentar", empareja.
6. [ ] **Bond perdido:** con la partida activa, apaga el cinturón y vuelve a encenderlo manteniendo BOOT 10 s (borra los bonds). Tras dos intentos sale "El cinturón olvidó este reloj…", y `logcat` deja de mostrar conexiones nuevas. Olvida el cinturón en Bluetooth del reloj y toca "Reintentar": empareja de nuevo.

**Con el cinturón (patio)**
7. [ ] **Datos:** en diestro, el abanico va de −100° a +80°, con anillos a 2 y 4 m. Una persona caminando al frente aparece y el reloj vibra con el lado correcto (criterio 3: ≥ 90 % en el modo práctica).
8. [ ] **Enlace:** apaga el cinturón → en unos 4-5 s la pantalla pasa a gris con "--" y hay **un solo** zumbido de sistema. Enciéndelo → los datos vuelven en ≤ 5 s (criterio 6). Repite 3 veces.
9. [ ] **Eliminado:** "ME DIERON" (en el radar, en Inicio o en la acción de la notificación) → ninguna vibración de contacto y pantalla sin contactos ("ELIMINADO"); las de sistema siguen. "REAPARECÍ" lo devuelve. Iniciar una partida nueva siempre empieza en juego.
10. [ ] **Sigilo:** con un contacto nuevo el reloj vibra y la pantalla **no** se enciende sola. Al levantar la muñeca se ve el radar. En ambient solo quedan el abanico atenuado y "--": sin botón, sin estado mínimo, sin bloques sólidos.
11. [ ] **Vista:** la pantalla no se apaga, y el dibujo se corre 2 px cada 3 min. En Vista + eliminado la pantalla sí se apaga.
12. [ ] **Pantalla apagada (S1):** 10 min con la pantalla apagada y un compañero cruzando cada minuto → todas las vibraciones llegan. Repite tras `adb shell dumpsys deviceidle force-idle`.
13. [ ] **Marcar rival:** "Marcar rival" en Inicio deja un registro `MANUAL_MARKER` (verificar al reproducir la grabación).
14. [ ] **Detener:** dos toques en "Detener partida" → desaparecen el ícono de la esfera y la notificación, Inicio muestra la grabación como "Última grabación", y `adb logcat -s BeltGatt` muestra `WriteSessionActive(active=false) -> status 0` (SESSION_ACTIVE = 0).
15. [ ] **Ongoing Activity:** con la partida activa hay un ícono en la esfera; al tocarlo vuelves a la app.
16. [ ] **Bluetooth apagado** a mitad de partida → "Bluetooth apagado" y **un solo** zumbido de sistema; al encenderlo, reconecta solo.

**Resistencia (8-oct, S8; criterios 5 y 7)**
17. [ ] Partida de 5 h en Sigilo con la pantalla apagada: la app no muere, la batería termina ≥ 17 % (objetivo ≥ 6 h en Sigilo), y se anota el % por hora. Repite 1 h en Vista (objetivo ≥ 3 h).
18. [ ] La grabación de 5 h se baja con `adb pull` y pesa unos 53 MB (≈ 43 MB de paquetes BLE + ~5 MB de gravedad + ~5 MB de giroscopio). Su cabecera trae el `info` del cinturón, y `radar-core` la reproduce entera.

**Día de juego (10/11-oct)**
19. [ ] Quiz de vibración aprobado el mismo día, No molestar apagado, reloj cargado, cargador del reloj en el power bank y modo Sigilo.
````

- [ ] **Step 2: Commit**

```bash
git add watch/wear-app/README.md
git commit -m "docs: guía de la app del reloj con emparejamiento, grabaciones y lista de verificación en el dispositivo"
```

---

## Self-review notes

- **Spec coverage (§12 "Entra", app column):**
  - service + wake lock → Task 10;
  - BLE: bond (pairing through the `info` read, no `createBond()`), reconnection, lost bond, RSSI, MTU < 247, service changed → Tasks 7-8;
  - fan in Vista → Tasks 11-12;
  - Sigilo with left/centre/right vibration → Tasks 3, 5, 12; a contact waits for a running system buzz → Task 5;
  - limiter → owned by `radar-core` per the contracts, and the app never re-limits (Task 5);
  - eliminated mode → Tasks 2, 10, 12, 14;
  - mini status → Tasks 11-12; ambient shows only the dimmed fan and `--` (spec §5.4) → Tasks 5, 12;
  - complete `.bsrec` recording, record types 1-10, header with the first `info` and `"config": toPipelineConfig(settings).toJson()` (`TuningParams` and the mounts, plan 01 Task 4d) → Tasks 4-5, 10; record payloads → plan 01 Task 4b; replay of the app's record types (8, 9, `LINK_UP`/`LINK_DOWN`) and of the header config (`pipelineConfigFromHeader`) → plan 01 Task 17;
  - vibration quiz → Task 13;
  - basic settings (handedness, angles, signs) → Tasks 2, 14;
  - permissions → Tasks 1, 10 (a refused start still answers `startForeground`), 14;
  - demo → Task 9;
  - spikes S1, S3, S10, S12 on 2-3 oct → Task 10b; the spike screen stays reachable after Task 14 from Ajustes → "Diagnóstico (spikes)";
  - `adb pull` → Task 15.
- **Deliberately not built (spec §12 "Queda para la v1 completa", or outside the MVP):**
  - tactical posture and "cero";
  - calibration wizards;
  - colour choice;
  - "encender ante contacto";
  - fine burn-in work;
  - brightness;
  - "aviso cercano";
  - the low-battery suggestion;
  - the sweep animation;
  - vibration primitives (the capability is still recorded for S3);
  - IDENTIFY from the app (spec §4.5 allows it only on configuration screens; not in §12 "Entra");
  - instrumented Android tests (covered by checklist item 2).
- **Contract additions owned by plan 01** (see "Dependencies on plan 01"; plan 01 has no Task 19 any more): `RecordType` 8-10 and the `BsrecPayloads` helpers for them and for `LINK_UP`/`LINK_DOWN` → Task 4b; `PipelineConfig.toJson()` for the header → Task 4d; `onWatchGyro` and `onBeltInfo` → Task 16a; their replay → Task 17. Plan 03 Task 4 depends on 4b and 4d; Tasks 5 and 10 on 16a (and 16c for vibrations).
- **Aligned with plan 01:**
  - the version catalog aliases (`junit5-*`);
  - `BsrecPayloads`/`SessionMode` for every recording payload;
  - the header's `"config"` from `PipelineConfig.toJson()` (plan 01 Task 4d), no reflection, so `pipelineConfigFromHeader` (Task 17) reads it back;
  - `RADAR_A`/`RADAR_B` from `.config`;
  - the demo built with the `.sim` DSL.
- **Cross-plan check (plan 02):** the filtered scan in Task 8 needs the 128-bit service UUID in the advertising data. Plan 02 puts it there, with the `Blindside-XXXX` name in the scan response. Plan 02 also keeps `SESSION_ACTIVE` across disconnections, and Task 8 rewrites `04 01` on every reconnection anyway.
