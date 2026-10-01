# Blindside fase 2 · Plan 06: app del celular (`watch/phone-app`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Galaxy S25 Ultra companion app:
- a live radar that connects to the belt with the phone role;
- a recordings list that can pull `.bsrec` files from the watch;
- a viewer with replay, heat map and summary;
- a belt tab with readable diagnostics, actions, pairing and shared settings.

All of it uses the dark visual system of spec §6, and the spec §7 E2E runs at the end.

**Architecture:** `phone-app` is a thin Android shell over three layers.
1. **`android-shared` (plan 05)** already holds the BLE link with `SET_ROLE`, the session service and runtime (`SessionService`, `SessionHost`, `SessionCommands`, `RunningSession`), recording, settings with last-write-wins stamping, the bridge codecs, the §6 theme and the radar Canvas. This plan adds only what the phone needs there, each piece with JVM tests and with defaults that keep the watch exactly as plan 05 leaves it:
   - a link profile (role + whether the link activates a session) and the manual commands `01`/`03`;
   - an info re-read;
   - session hooks (purpose, per-app traits, foreground type, belt listener, counters);
   - a clock guard on the shared-settings stamp;
   - a freshness rule for the watch status.
2. **Pure phone logic** lives in plain Kotlin files with JVM tests: navigation, launch decision, firmware gating, diagnostics mapping, heat grid, recording summary, playback clock, file naming and download verdicts.
3. **Compose screens and Android glue** stay small and only wire the pure parts together: the phone's `SessionHost`, the Data Layer client and FileProvider.

The phone's foreground service is `class PhoneSessionService : SessionService()` with `PhoneSessionHost`. It runs the same `RunningSession` as the watch, so there is one session runtime for both apps. The viewer streams the `.bsrec` file twice (one analysis pass, one replay cursor), so a 5 h, 53 MB recording never sits in memory.

**Tech Stack:**
- Kotlin 2.2.21, AGP 8.10.1, Gradle 8.11.1, JDK 17.
- Compose BOM 2025.05.00 (Material 3 1.3.2, material-icons-extended 1.7.8), activity-compose 1.10.1, lifecycle-runtime-compose 2.8.7.
- DataStore Preferences 1.1.4, kotlinx-coroutines 1.9.0 (+ `kotlinx-coroutines-play-services`), `play-services-wearable` 18.2.0.
- JUnit 5.13.1.
- E2E: `adb`, Python 3 with `pyserial`.

**Spec:** `docs/superpowers/specs/2026-10-01-blindside-android-companion-design.md`:
- §4 phone app;
- §5 phone side of the bridge;
- §6 visual system;
- §7 E2E steps 3-7.

Background: `docs/superpowers/specs/2026-09-30-blindside-v1-design.md`, `docs/superpowers/plans/2026-09-30-blindside-mvp-00-contracts.md`, `protocol/PROTOCOL.md`.

**Depends on:**
- **Plan 05**, finished on its branch `p2/android-shared-watch` (module `watch/android-shared`, package `io.github.santiquiroz.blindside.shared.*`). This plan branches from it (Task 1 Step 1).
- **Plan 04** (firmware 0.2.0, branch `feat/p2-firmware-dual`) is not needed to build or unit-test this plan. At runtime the phone only auto-starts and only guides pairing once the belt has reported dual-link firmware (Task 6 `LinkSupport`, Task 14 `phoneLaunch`). The MVP firmware 0.1.0 holds one connection and one bond, so on it the phone could take the only slot from the watch or replace the watch's bond. Task 16 needs 0.2.0 on the belt for spec §7 steps 4-6.

**Out of scope here:**
- The watch side of the bridge (plan 05 Tasks 11-13).
- The watch visual pass (plan 05 Tasks 14-16).
- The firmware (plan 04).
- Flashing the belt and the watch-only regression (spec §7 steps 1-2). Plans 04/05 and the orchestrator own those; Task 16 only checks the watch link as a precondition.

## Deviations from the spec (decided here; repeat them in the final report)

- **P1, `minSdk 33` instead of 31 (spec §4).** Plan 05 Deviation D1 sets `android-shared` to `minSdk 33` because `BeltGatt` calls the API 33 GATT overloads. A phone module at 31 fails the manifest merger, and forcing it with `overrideLibrary` would crash on Android 12. The S25 Ultra runs far above 33.
- **P2, a diagnostic link.** "Conectar para diagnóstico" opens a phone link that writes `06 01`, then `04 00`, and records nothing (Task 3, Task 6). Spec §4 asks for `IDENTIFY` disabled during a session, and that only works if the phone can connect without marking a session.
- **P3, fonts.** The phone uses plan 05's `BlindsideFonts`: JetBrains Mono **NL** 2.304 (Regular, Bold) and IBM Plex Sans 1.1.0 (Regular, Medium) from `android-shared/res/font`. If plan 05 fell back to the system fonts, the phone falls back with it.
- **P4, no phone sensors.** Spec §8 leaves "IMU del torso desde el celular" out and §4 never asks for phone sensors. The phone's pipeline therefore gets belt data only, and its recordings carry no `WATCH_*` records. The phone does not ask for `ACTIVITY_RECOGNITION`.
- **P5, auto-start and pairing guidance need firmware 0.2.0.** Spec §4 says to auto-start "si el cinturón ya está emparejado". This plan adds "and the belt reported dual-link firmware" (see "Depends on").
- **P6, hands-free pairing in the E2E goes through the phone's "Pedir al reloj que abra la ventana".** That sends the same `05` through the watch (`/belt/open-pairing`). The watch's own chip (Ajustes → "Emparejar celular") cannot be reached while a game runs; see Cross-plan flag 1.
- **Kept phone-only on purpose (not duplicates):**
  - `phoneLinkLabel`: phone wording. Plan 05 D3 keeps the watch wording in `wear-app` for the same reason.
  - `sensorChips`: long labels mapped over the shared `statusItems`.
  - `motionDurationMs`: the watch has no animations.
  - `phone.ui.theme` short aliases (`AccentColor = BlindsideColors.Accent`, …): no colour value lives in the phone.

## Cross-plan flags (for the orchestrator)

1. **Plan 05, Task 10/16:** the watch's "Emparejar celular" chip is only in Ajustes. Ajustes is reachable only from the idle home, while `canOpenPairingWindow` requires a streaming game, so on the watch the chip always answers "Primero inicia el radar". The phone's button works, through `/belt/open-pairing` → `REQUESTED`. Suggested fix in plan 05: add an "Emparejar celular" chip to `RunningHome`.
2. **Plan 05:** Task 5 here adds hooks to `SessionHost`, `SessionService`, `RunningSession` and `SessionCommands`. Every hook has a default, so `WearSessionHost` and every `wear-app` call site compile unchanged. Task 5 runs the watch gate.
3. **Plan 04:** none new. This plan consumes flags 1, 2, 4 and 5 (role before subscribe, `info` `conns`, no free slot, `diag` format).

## Global Constraints

- **Branch:** `p2/phone-app`, created from `p2/android-shared-watch` (Task 1 Step 1).
  - If another plan is running in `C:/personal/blindside` at the same time, use a worktree instead (`git worktree add ../blindside-p2-phone -b p2/phone-app p2/android-shared-watch`). Then read every `C:/personal/blindside` and `/c/personal/blindside` below as that worktree.
  - **Never merge and never push.** The orchestrator merges after the tests pass **and** spec §7 passes (Task 16, plus the watch regression owned by plan 05).
- **Identifiers and SDK levels:**
  - `applicationId = "io.github.santiquiroz.blindside"`, the same as the watch: the Wear Data Layer only links apps with the same package and signing key.
  - `namespace = "io.github.santiquiroz.blindside.phone"`.
  - `minSdk = 33` (Deviation P1), `targetSdk = 36`, `compileSdk = 36`, `versionName = "0.2.0"`, `versionCode = 2`.
- **Theme:**
  - Compose Material 3 from BOM 2025.05.00, **dark theme only** (no light scheme, no dynamic color).
  - Every colour and font comes from plan 05's `io.github.santiquiroz.blindside.shared.theme` (`Tokens`, `BlindsideColors`, `BlindsideFonts`, `contrastRatio`, `relativeLuminance`). The phone never writes a hex value.
  - Numbers (distances, bearings, times, counters) use `BlindsideFonts.Numbers` (monospace, tabular figures).
- **Components:**
  - Touch targets ≥ 48 dp.
  - Animations 150–300 ms, and 0 ms when the system animator scale is 0 ("reducir movimiento").
  - Colour is never the only signal: confidence by shape (filled, outline, dashed); status by icon.
  - Empty states always carry one action. Vector icons only, never emojis. One primary action per screen.
- **Navigation:** a bottom bar with exactly 4 destinations, icon + text: **Radar**, **Grabaciones**, **Visor**, **Cinturón**.
- **BLE role (spec §2, plan 05 Task 9):**
  - Every link writes `06 <role>` right after the encrypted `info` read and before enabling notifications: the watch `06 00`, the phone `06 01` (`BeltLinkProfile.role`).
  - A phone game then writes `04 01` like the watch. The phone's diagnostic link writes `04 00` instead (Task 3).
  - Nothing else in the watch's setup changes.
- **Connection priority:**
  - The watch keeps `HIGH` while connecting and `BALANCED` once its session is active.
  - The phone asks for `BALANCED` while connecting and **nothing afterwards**, so the belt's own 60–100 ms request after `06 01` stands (plan 04 `conn_params_for_role(Phone)`). Never `HIGH`.
- **Phone behaviour:**
  - Phone vibration is optional and **off by default**. The phone never reads its own sensors (Deviation P4).
  - Each app works alone: any bridge failure (no watch, old watch app, timeout, bad item) shows a message or is logged. It never crashes or blocks the phone radar.
- **Owner rules:** atomic small functions with descriptive names; low cyclomatic complexity; **no doc comments** (only one-line WHY comments); pure functions and immutable data; files ≤ 400 lines.
- **Commits:**
  - Spanish conventional prefixes (`feat:`, `fix:`, `refactor:`, `test:`, `chore:`, `docs:`).
  - **Never** a `Co-Authored-By` line. Never push, never `--no-verify`.
  - No destructive git (`reset --hard`, `clean`, `checkout -- .`, `branch -D`, `worktree remove`).
  - The git author is preconfigured. Deviations go in the commit body.
- **Commands** run in Git Bash from `C:/personal/blindside/watch` with `./gradlew ... --console=plain`. `watch/local.properties` must contain `sdk.dir=C:/Users/santi/AppData/Local/Android/Sdk` (forward slashes; the file is gitignored).
- **Never break the watch.** After any change under `watch/android-shared` or `watch/radar-core`, run the **watch gate** and keep it green:

  ```bash
  cd /c/personal/blindside/watch && ./gradlew :android-shared:cleanTestDebugUnitTest :wear-app:cleanTestDebugUnitTest :android-shared:testDebugUnitTest :wear-app:testDebugUnitTest :wear-app:assembleDebug --console=plain
  ```

  Then run plan 05's test-count one-liner:

  ```bash
  cd /c/personal/blindside/watch && for m in android-shared wear-app; do printf '%s %s\n' "$m" "$(cat $m/build/test-results/testDebugUnitTest/*.xml 2>/dev/null | grep -o '<testsuite [^>]*' | grep -o ' tests="[0-9]*"' | grep -o '[0-9]*' | awk '{s+=$1} END {print s+0}')"; done
  ```

  The expected counts below assume plan 05 ended at `android-shared 253`, `wear-app 46`. If Task 1 Step 2 measured other numbers, shift every expected count by the same difference.
- **Devices:** the watch and the S25 (`192.168.10.119`) may be reachable by `adb` over Wi-Fi, and the belt on `COM6`. Task 16 probes them and runs spec §7 steps 3-7. Whatever cannot run becomes a checklist item in `watch/phone-app/README.md`, as spec §7 allows.

### Bridge contract (plan 05 Tasks 11-13 and its Deviations D6-D7; the phone follows it, it does not define it)

| Path | API | Payload | Phone side (this plan) |
|---|---|---|---|
| `/recordings/list` | `MessageClient.sendRequest` → watch `WearableListenerService.onRequest` | reply: UTF-8 JSON array `[{"nombre":"blindside-belt-20261001-142233.bsrec","bytes":53000000,"inicio":1727790153000}]` (`inicio` = epoch ms). `decodeRecordingList` drops any entry whose name is not a recording name | `PhoneBridge.listRecordings()` |
| `/recordings/get/<name>` | phone `ChannelClient.openChannel(node, recordingChannelPath(name))`; the watch `sendFile`s finished recordings and closes the channel for the active, a missing or an unsafe one | raw `.bsrec` bytes | `PhoneBridge.download()` |
| `/settings` | `DataClient` item per device, a **DataMap whose `"json"` key (`DATA_JSON_KEY`) holds the UTF-8 JSON**: written with plan 05's `jsonDataRequest(path, json)` and read with `jsonIn(item)`, never as raw item bytes (corrected after review: plan 05 shipped the DataMap, so raw bytes never crossed) | `{"handedness":"RIGHT","posture":"NORMAL","updated_ms":<long>,"radars":[{"id":0,"yaw":<double\|null>,"flip_x":<bool>,"speed_sign":<±1>},…]}` (`encodeSharedSettings` / `decodeSharedSettings`). A partial radar list is rejected, unknown names fall back to defaults, yaw is clamped. Adopted with `adoptingNewer`: strictly newer `updated_ms` wins | `PhoneBridge.publishSharedSettings`, `PhoneBridgeListenerService` |
| `/status` | `DataClient` item, the same DataMap `"json"` key, read with `jsonIn(item)`. The watch publishes every 5 s while its game runs and once more as stopped | `{"active":<bool>,"ble":"<BleStatus>","link_up":<bool>,"radars":[{"id":0,"alive":<bool>},…],"updated_ms":<long>}` (`decodeWatchStatus`). Stale after 15 s (Task 4 `isStatusFresh`) | `WatchStatusStore` |
| `/belt/open-pairing` | `MessageClient.sendRequest` → watch `onRequest` (D6) | reply bytes: the UTF-8 name `REQUESTED` (the watch queued `05`) or `NO_LINK` (its belt game is not streaming); anything else decodes as `NO_LINK` (`decodeOpenPairingReply`) | `PhoneBridge.requestOpenPairing()` |

Recording names crossing the bridge must pass plan 05's `isRecordingFileName` (`blindside-[a-z]+-\d{8}-\d{6}\.bsrec`). Nothing else is ever downloaded, opened, shared or deleted.

### android-shared surface this plan consumes (plan 05; exact packages)

| Symbols | Package |
|---|---|
| `BeltLink`, `BeltListener` (with `onPairingWindow`), `BleStatus`, `needsRetry`, `MIN_STREAM_MTU`, `BeltRole`, `setRoleCommand`, `openPairingWindowCommand`, `GattOp` (`WriteRole`, `WriteOpenPairing`), `SetupEffect` (`PAIRING_WINDOW_OPENED/REFUSED`), `setupOpsAfterDiscovery(role)`, `effectsAfter`, `isControlWrite`, `BeltGatt`, `BeltGattEvents` | `io.github.santiquiroz.blindside.shared.ble` |
| `SessionHost`, `SessionService`, `SessionActions`, `SessionCommands`, `RunningSession`, `SessionEngine`, `SessionInput`, `BeltInputs`, `SessionStore`, `SessionUiState` (with `phonePairing`), `SessionSource`, `StartError`, `startedState`, `stoppedState`, `blockedState`, `foregroundTypesFor`, `ongoingStatus`, `scenePeriodMs`, `IDLE_TICK_POLL_MS`, `activeRecordingName`, `PhonePairing` | `io.github.santiquiroz.blindside.shared.session` |
| `DeviceSensors` | `io.github.santiquiroz.blindside.shared.sensors` |
| `HapticPlayer`, `HapticSink`, `LEFT_PATTERN` | `io.github.santiquiroz.blindside.shared.haptics` |
| `recordingsDir`, `recordingFileName`, `NoOpRecordSink`, `RecordingEntry(name, bytes, startEpochMs)`, `isRecordingFileName` | `io.github.santiquiroz.blindside.shared.recording` |
| `AppSettings` (with `sharedUpdatedMs`), `RadarSettings`, `WatchPosture`, `ScreenMode`, `SettingsRepository`, `SettingsTransform`, `settingsRepository`, `DEFAULT_RADARS`, `radar`, `withHandedness`, `withYawNudged`, `effectiveYawDeg`, `withFlipXToggled`, `withSpeedSignFlipped`, `YAW_STEP_DEG`, `enumOrDefault`, `SharedSettings`, `sharedSettingsOf`, `adoptingNewer`, `stampSharedEdit`, `isStamped` | `io.github.santiquiroz.blindside.shared.settings` |
| `SESSION_PERMISSIONS`, `PERMISSION_BLUETOOTH_SCAN`, `PERMISSION_BLUETOOTH_CONNECT`, `PERMISSION_POST_NOTIFICATIONS`, `PERMISSION_ACTIVITY_RECOGNITION`, `bluetoothGranted`, `shouldRequestPermissions` | `io.github.santiquiroz.blindside.shared.permissions` |
| `toDrawModel`, `showContacts`, `sectorArc`, `blipStyle`, `contactTone`, `ContactTone`, `MAX_RANGE_M`, `PointPx`, `RadarDrawModel`, `BlipStyle`, `drawRadar`, `TACTICAL_RADAR_COLORS`, `statusItems`, `warningLabel`, `eliminatedActionLabel` | `io.github.santiquiroz.blindside.shared.radar` |
| `RECORDINGS_LIST_PATH`, `SETTINGS_PATH`, `STATUS_PATH`, `OPEN_PAIRING_PATH`, `STATUS_PERIOD_MS`, `decodeRecordingList`, `recordingChannelPath`, `encodeSharedSettings`, `decodeSharedSettings`, `WatchStatus(sessionActive, ble, linkUp, radars, updatedMs)`, `encodeWatchStatus`, `decodeWatchStatus`, `OpenPairingReply`, `decodeOpenPairingReply` | `io.github.santiquiroz.blindside.shared.bridge` |
| `Tokens`, `contrastRatio`, `relativeLuminance`, `BlindsideColors`, `BlindsideFonts` | `io.github.santiquiroz.blindside.shared.theme` |

Task 1 Step 3 checks every row. A symbol that is missing means plan 05 is not finished: stop and report BLOCKED.

## Review Focus

1. **A recording name from the watch that tries to escape the recordings folder** (`../x.bsrec`, `a/b.bsrec`, `x.bsrec.part`, empty). It is dropped from the list and never becomes a path. Only names passing `isRecordingFileName` are downloaded, opened, shared or deleted.
   - Tests: plan 05 `RecordingListCodecTest`/`RecordingChannelTest`; Task 10 (`remoteRows`, `RecordingsRepository.file`).
2. **A damaged, truncated, empty or non-`.bsrec` file opened in the Visor.** That covers a corrupt header length (0xFFFFFFFF), a zero-filled crash tail, and a clock that jumps hours ahead (a corrupt `u32` time).
   - The viewer shows "No se pudo abrir…" with a way back and never crashes.
   - A truncated file still opens up to its last whole record.
   - The analysis never spins through millions of empty samples.
   - Tests: Task 11 (`BsrecReader` header cap, iterative skip of unknown records); Task 12 (`analyzeFile` on garbage, empty, truncated, 0xFFFFFFFF header and 1 MB of zeros; a 4 000 000 000 ms jump finishes in under 5 s).
3. **No watch, an old watch app without the bridge, a watch that never answers, or a watch whose game is not streaming.** "Traer del reloj" and "Pedir al reloj que abra la ventana" end in a plain message within 10 s, and the phone radar keeps working.
   - Tests: Task 8 (`pickWatchNode`), Task 9 (`pairingRequestMessage` for `REQUESTED`, `NO_LINK`, `NoWatch`, `Failed`), Task 10 (`listedState`).
4. **Shared settings bouncing between devices.** None of these may overwrite newer values or lose the phone's edit:
   - the phone's own write coming back;
   - an older write from the watch;
   - a phone clock behind the watch's last stamp;
   - a custom yaw crossing in either direction.
   - Tests: Task 4 (`stampSharedEdit` with a clock behind; `SharedSettingsCrossDeviceTest`); plan 05 `SharedSettingsTest` and `SharedSettingsCodecTest`.
5. **A download cut halfway** (watch walks away, `sendFile` fails, the channel closes early, the transfer stalls, the user cancels). No half file ever appears in Grabaciones: a part is kept only with **every listed byte** and a readable header.
   - Tests: Task 10 (`finishDownload` with a header-only part shorter than listed, `downloadVerdict`, `stalled`).
6. **The phone never weakens the watch:**
   - `06 01` before subscribing, and `04 00` on a diagnostic link;
   - no priority request after setup;
   - no auto-start or pairing guidance on single-link firmware;
   - vibration off by default and no phone sensors.
   - Tests: Task 3 (`GattOpsTest`, `BeltCommandsTest`), Task 6 (`phoneTraits`, `LinkSupportTest`), Task 14 (`phoneLaunch`).
7. **Switching tabs never freezes the live radar.** One owner (the app shell) reports scene visibility from the selected tab.
   - Tests: Task 14 (`sceneWanted`); the keyed `ReportSceneVisibility` in Task 7.
8. **The phone's own recording in progress** shows "Grabando…" in Grabaciones and cannot be opened, shared or deleted until the radar stops.
   - Tests: Task 10 (`rowActions`).

---

## File map

```
watch/
  settings.gradle.kts                      modify: include(":phone-app")
  gradle/libs.versions.toml                modify: material3, icons-extended, coroutines-play-services
  radar-core/src/main/kotlin/.../core/replay/Replay.kt          modify: replayRecord becomes public (Task 11)
  radar-core/src/main/kotlin/.../core/replay/Bsrec.kt           modify: header cap, iterative record skip (Task 11)
  radar-core/src/test/kotlin/.../core/replay/ReplayRecordTest.kt create (Task 11); BsrecTest.kt modify (Task 11)
  android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/
    ble/BleIds.kt                          modify: restartRadarCommand, identifyCommand (Task 3)
    ble/BeltCommands.kt                    create: BeltCommand, commandBytes, LinkPriority, BeltLinkProfile (Task 3)
    ble/GattOps.kt                         modify: GattOp.WriteCommand, setupOpsAfterDiscovery(profile), isControlWrite (Task 3)
    ble/BeltGatt.kt, ble/BeltLink.kt       modify: role → profile, send(), readInfo()/refreshInfo(), priorities (Task 3)
    settings/SharedSettings.kt             modify: stampSharedEdit never goes backwards (Task 4)
    bridge/StatusFreshness.kt              create: STATUS_STALE_AFTER_MS, isStatusFresh, watchSessionActive (Task 4)
    session/SessionPurpose.kt, SessionTraits.kt, CommandExtras.kt                 create (Task 5)
    session/ScenePacing.kt, SessionStore.kt, SessionSource.kt                       modify (Task 5)
    session/SessionHost.kt, SessionActions.kt, SessionCommands.kt, SessionService.kt, RunningSession.kt   modify (Task 5)
  android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/
    ble/BleIdsTest.kt, ble/GattOpsTest.kt (modify), ble/BeltCommandsTest.kt (create)
    settings/SharedSettingsTest.kt (modify), bridge/StatusFreshnessTest.kt, bridge/SharedSettingsCrossDeviceTest.kt (create)
    session/SessionPurposeTest.kt, SessionTraitsTest.kt, CommandExtrasTest.kt (create); ScenePacingTest.kt, SessionStoreTest.kt, SessionSourceTest.kt (modify)
  phone-app/
    build.gradle.kts, README.md
    e2e/e2e_tools.py, e2e/phone_e2e.sh
    src/main/AndroidManifest.xml
    src/main/res/drawable/ic_radar.xml, res/values/themes.xml, res/xml/recording_paths.xml
    src/main/kotlin/io/github/santiquiroz/blindside/phone/
      MainActivity.kt, PhoneDeps.kt
      ui/theme/Theme.kt, ui/theme/Motion.kt
      session/PhoneSessionModels.kt, PhoneStore.kt, DiagnosticBeltListener.kt, PhoneNotification.kt, PhoneSessionHost.kt
      settings/PhonePrefs.kt, settings/LinkSupport.kt
      bridge/BridgeFacts.kt, PhoneBridge.kt, WatchStatusStore.kt, PhoneBridgeListenerService.kt
      ui/PhoneUiModels.kt, ui/PhoneApp.kt, ui/PhoneActionsFactory.kt, ui/PhoneNavigationBar.kt
      ui/common/Formats.kt, Widgets.kt, ScreenEffects.kt
      ui/radar/RadarView.kt, ContactRows.kt, RadarStatus.kt, RadarTab.kt
      ui/belt/BeltInfoView.kt, BeltActions.kt, BeltTab.kt, BeltSections.kt, SharedSettingsSection.kt
      recordings/RecordingFiles.kt, RecordingsRepository.kt
      ui/recordings/FetchState.kt, FetchFromWatchDialog.kt, RecordingsTab.kt, ShareRecording.kt
      viewer/Playback.kt, ReplayCursor.kt, HeatGrid.kt, HeatPalette.kt, RecordingSummary.kt,
             SummaryRows.kt, RecordingAnalysis.kt
      ui/viewer/ViewerTab.kt, ReplayPanel.kt, HeatmapView.kt
      nav/PhoneNav.kt, nav/PhoneLaunch.kt
    src/test/kotlin/io/github/santiquiroz/blindside/phone/...   one test file per pure file
.github/workflows/ci.yml                   modify: phone-app job
```

Tasks run in order 1 → 16: each one consumes names produced by earlier tasks (see every "Interfaces" block). All Kotlin paths below use these abbreviations; commands always use the full path.

| Abbreviation | Full path |
|---|---|
| `phone/…` | `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/` |
| `phoneTest/…` | `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/` |
| `shared/…` | `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/` |
| `sharedTest/…` | `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/` |

---

### Task 1: Branch, preflight and the `phone-app` module

**Files:**
- Modify: `watch/gradle/libs.versions.toml`
- Modify: `watch/settings.gradle.kts`
- Create: `watch/phone-app/build.gradle.kts`
- Create: `watch/phone-app/src/main/AndroidManifest.xml`
- Create: `watch/phone-app/src/main/res/drawable/ic_radar.xml`
- Create: `watch/phone-app/src/main/res/values/themes.xml`
- Create: `phone/MainActivity.kt`
- Test: `phoneTest/BuildSmokeTest.kt`

**Interfaces:**
- Consumes: plan 05's branch `p2/android-shared-watch`, its `:android-shared` Gradle project, its catalog entry `libs.play.services.wearable` (plan 05 Task 13) and the symbols in "android-shared surface" above.
- Produces:
  - Branch `p2/phone-app` and Gradle project `:phone-app`.
  - Catalog aliases `libs.compose.material3`, `libs.compose.material.icons.extended` and `libs.coroutines.play.services`.
  - R class `io.github.santiquiroz.blindside.phone.R` with `R.drawable.ic_radar`, and the theme `@style/Theme.Blindside`.

- [ ] **Step 1: Create the branch on top of plan 05**

```bash
cd /c/personal/blindside
git status --short | grep -v '^??' && echo "STOP: tracked changes in the working tree" || echo "clean"
git branch --list p2/android-shared-watch
git log --oneline p2/android-shared-watch | grep -F "inicio del reloj con botón circular Iniciar radar" || echo "BLOCKED: plan 05 is not finished"
git switch -c p2/phone-app p2/android-shared-watch
git branch --show-current
```

Expected:
- `clean`, then the line `p2/android-shared-watch`.
- The commit of plan 05 Task 16, the last task that always commits (Task 17 only adds `fix:` commits when its gate fails).
- `p2/phone-app`.

If any line says STOP or BLOCKED, stop and report BLOCKED with the output. If another plan is running in this checkout, create the worktree from Global Constraints instead of `git switch` and work there. This plan never merges this branch: the orchestrator does, after spec §7.

- [ ] **Step 2: Verify the watch is green and record the baseline counts**

```bash
cd /c/personal/blindside/watch
grep -n "android-shared" settings.gradle.kts
grep -q "sdk.dir" local.properties 2>/dev/null || printf 'sdk.dir=C:/Users/santi/AppData/Local/Android/Sdk\n' > local.properties
cat local.properties
```

Then run the watch gate and the test-count one-liner (Global Constraints).

Expected:
- An `include(":android-shared")` line.
- `sdk.dir=C:/Users/santi/AppData/Local/Android/Sdk`.
- `BUILD SUCCESSFUL`, `android-shared 253` and `wear-app 46`.

Write the two numbers down; every later expected count is relative to them. If the build fails, stop and report BLOCKED with the output.

- [ ] **Step 3: Map every consumed symbol to its real file**

```bash
cd /c/personal/blindside/watch
S=android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared
for symbol in "class BeltLink(" "interface BeltListener" "fun onPairingWindow(opened: Boolean)" "enum class BleStatus" "fun needsRetry" \
  "const val MIN_STREAM_MTU" "enum class BeltRole" "fun setRoleCommand" "data class WriteRole" "data object WriteOpenPairing" \
  "fun setupOpsAfterDiscovery(role: BeltRole)" "fun isControlWrite" "interface SessionHost" "abstract class SessionService" \
  "class SessionCommands(" "class RunningSession(" "object SessionStore" "data class SessionUiState" "fun startedState" \
  "fun stoppedState" "fun activeRecordingName" "const val IDLE_TICK_POLL_MS" "fun scenePeriodMs" "class DeviceSensors" \
  "fun interface HapticSink" "class HapticPlayer" "object NoOpRecordSink" "fun recordingsDir" "fun isRecordingFileName" \
  "data class RecordingEntry" "fun Context.settingsRepository" "class SettingsRepository(" "fun stampSharedEdit" \
  "fun AppSettings.adoptingNewer" "fun sharedSettingsOf" "fun isStamped" "fun AppSettings.withYawNudged" "fun effectiveYawDeg" \
  "fun <reified E : Enum<E>> enumOrDefault" "val SESSION_PERMISSIONS" "const val PERMISSION_POST_NOTIFICATIONS" \
  "fun bluetoothGranted" "fun shouldRequestPermissions" "fun toDrawModel" "fun showContacts" "fun sectorArc" "fun blipStyle" \
  "fun contactTone" "const val MAX_RANGE_M" "fun DrawScope.drawRadar" "val TACTICAL_RADAR_COLORS" "fun statusItems" \
  "fun warningLabel" "fun eliminatedActionLabel" "const val RECORDINGS_LIST_PATH" "fun decodeRecordingList" \
  "fun recordingChannelPath" "fun encodeSharedSettings" "fun decodeSharedSettings" "data class WatchStatus" \
  "fun decodeWatchStatus" "fun encodeWatchStatus" "enum class OpenPairingReply" "fun decodeOpenPairingReply" \
  "object Tokens" "fun contrastRatio" "object BlindsideColors" "object BlindsideFonts"; do
  hit=$(grep -rlF "$symbol" "$S" | head -1)
  printf '%-42s %s\n' "$symbol" "${hit:-MISSING}"
done
```

Expected: every line ends in a path whose sub-package matches the "android-shared surface" table. If any line says `MISSING`, stop and report BLOCKED with that line.

- [ ] **Step 4: Write the failing smoke test**

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/BuildSmokeTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone

import io.github.santiquiroz.blindside.core.replay.RecordType
import io.github.santiquiroz.blindside.shared.radar.MAX_RANGE_M
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class BuildSmokeTest {
    @Test
    fun `the phone module sees radar-core and android-shared`() {
        assertEquals(1, RecordType.BLE_PACKET.code)
        assertEquals(6.0, MAX_RANGE_M)
        assertFalse(SessionUiState().running)
    }
}
```

- [ ] **Step 5: Run it to make sure it fails**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest --console=plain`
Expected: FAIL with `Project 'phone-app' not found in root project 'blindside-watch'`.

- [ ] **Step 6: Add the catalog entries**

Plan 05 Task 13 already added `playServicesWearable` and `play-services-wearable`. In `watch/gradle/libs.versions.toml`, add under `[libraries]` only:

```toml
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
coroutines-play-services = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-play-services", version.ref = "coroutines" }
```

- [ ] **Step 7: Include the module**

Append to `watch/settings.gradle.kts`:

```kotlin
include(":phone-app")
```

- [ ] **Step 8: Create the build file**

Create `watch/phone-app/build.gradle.kts`:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.santiquiroz.blindside.phone"
    compileSdk = 36

    defaultConfig {
        // Spec §4: the Wear Data Layer only links apps that share the applicationId and the signing key.
        applicationId = "io.github.santiquiroz.blindside"
        // Deviation P1: android-shared needs API 33 (plan 05 D1); 31 fails the manifest merger.
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
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
    implementation(project(":android-shared"))
    implementation(project(":radar-core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    implementation(libs.datastore.preferences)
    implementation(libs.play.services.wearable)

    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.launcher)
}
```

- [ ] **Step 9: Create the manifest, icon and window theme**

Create `watch/phone-app/src/main/AndroidManifest.xml`. There is no `ACTIVITY_RECOGNITION`: the phone reads no sensors (Deviation P4).

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature
        android:name="android.hardware.bluetooth_le"
        android:required="true" />

    <uses-permission
        android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:allowBackup="false"
        android:icon="@drawable/ic_radar"
        android:label="Blindside"
        android:theme="@style/Theme.Blindside">

        <activity
            android:name=".MainActivity"
            android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode|density"
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

Create `watch/phone-app/src/main/res/drawable/ic_radar.xml` (same drawing as the watch icon):

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

Create `watch/phone-app/src/main/res/values/themes.xml`:

```xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.Blindside" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">@android:color/black</item>
    </style>
</resources>
```

- [ ] **Step 10: Create the first activity**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/MainActivity.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("Blindside") }
    }
}
```

- [ ] **Step 11: Run the test and build the APK**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest :phone-app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`, 1 test passed, and `phone-app/build/outputs/apk/debug/phone-app-debug.apk` exists (`ls -l phone-app/build/outputs/apk/debug/`). If the merger complains about `minSdkVersion`, check that Step 8 says 33.

- [ ] **Step 12: Commit**

```bash
cd /c/personal/blindside
git add watch/gradle/libs.versions.toml watch/settings.gradle.kts watch/phone-app/build.gradle.kts \
  watch/phone-app/src/main/AndroidManifest.xml watch/phone-app/src/main/res/drawable/ic_radar.xml \
  watch/phone-app/src/main/res/values/themes.xml \
  watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/MainActivity.kt \
  watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/BuildSmokeTest.kt
git commit -m "chore: módulo phone-app con Material 3 y Wear Data Layer" \
  -m "Desviación P1: minSdk 33 en lugar de 31 (spec §4), porque android-shared usa las sobrecargas GATT de API 33 (plan 05, desviación D1)."
```

---

### Task 2: Phone theme over the shared visual system

**Files:**
- Create: `phone/ui/theme/Motion.kt`
- Create: `phone/ui/theme/Theme.kt`
- Modify: `phone/MainActivity.kt`
- Test: `phoneTest/ui/theme/MotionTest.kt`, `phoneTest/ui/theme/PhoneContrastTest.kt`

**Interfaces:**
- Consumes: plan 05 `Tokens`, `contrastRatio`, `BlindsideColors`, `BlindsideFonts` (`io.github.santiquiroz.blindside.shared.theme`). Plan 05 Task 14 already downloaded the fonts and licences, so this task downloads nothing.
- Produces (package `io.github.santiquiroz.blindside.phone.ui.theme`):
  - Pure: `const val MOTION_MIN_MS = 150`, `MOTION_MAX_MS = 300`; `fun motionDurationMs(baseMs: Int, animatorScale: Float): Int`.
  - Compose aliases of the shared colours: `val BgColor`, `SurfaceColor`, `Surface2Color`, `RingColor`, `AccentColor`, `AccentDimColor`, `AlertRedColor`, `AlertRedDimColor`, `WarnColor`, `TextColor`, `Text2Color: Color`; `val NumberStyle: TextStyle` (= `BlindsideFonts.Numbers`).
  - `@Composable fun BlindsideTheme(content: @Composable () -> Unit)`; `@Composable fun rememberMotionDurationMs(baseMs: Int): Int`.

- [ ] **Step 1: Write the failing tests**

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/ui/theme/MotionTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MotionTest {
    @Test
    fun `reduced motion turns every animation off`() {
        assertEquals(0, motionDurationMs(200, animatorScale = 0f))
    }

    @Test
    fun `animations stay between 150 and 300 ms`() {
        assertEquals(200, motionDurationMs(200, animatorScale = 1f))
        assertEquals(MOTION_MIN_MS, motionDurationMs(80, animatorScale = 1f))
        assertEquals(MOTION_MAX_MS, motionDurationMs(900, animatorScale = 2f))
    }
}
```

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/ui/theme/PhoneContrastTest.kt`. Plan 05's `ContrastTest` covers text on black. The phone also puts text on cards (`surface`, `surface-2`), so these pairs are checked here:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.theme

import io.github.santiquiroz.blindside.shared.theme.Tokens
import io.github.santiquiroz.blindside.shared.theme.contrastRatio
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhoneContrastTest {
    private val cards = listOf(Tokens.SURFACE, Tokens.SURFACE_2)

    @Test
    fun `main text stays at 7 to 1 on the phone's cards`() {
        cards.forEach { assertTrue(contrastRatio(Tokens.TEXT, it) >= 7.0, "text on ${it.toString(16)}") }
    }

    @Test
    fun `warnings and errors stay at 4,5 to 1 on the phone's cards`() {
        listOf(Tokens.WARN, Tokens.ALERT_RED).forEach { foreground ->
            cards.forEach { background ->
                assertTrue(contrastRatio(foreground, background) >= 4.5, "${foreground.toString(16)} on ${background.toString(16)}")
            }
        }
    }
}
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.phone.ui.theme.*" --console=plain`
Expected: FAIL with `Unresolved reference 'motionDurationMs'`.

- [ ] **Step 3: Write the motion rule**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/theme/Motion.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.theme

const val MOTION_MIN_MS = 150
const val MOTION_MAX_MS = 300

// Spec §6: 150-300 ms, and "reducir movimiento" (animator scale 0) turns animation off.
fun motionDurationMs(baseMs: Int, animatorScale: Float): Int =
    if (animatorScale <= 0f) 0 else baseMs.coerceIn(MOTION_MIN_MS, MOTION_MAX_MS)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.phone.ui.theme.*" --console=plain`
Expected: PASS (4 tests).

- [ ] **Step 5: Write the Compose theme**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/theme/Theme.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.theme

import android.provider.Settings
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import io.github.santiquiroz.blindside.shared.theme.BlindsideColors
import io.github.santiquiroz.blindside.shared.theme.BlindsideFonts

// Short names for the shared §6 tokens (plan 05 Task 14); no colour value lives in the phone.
val BgColor: Color = BlindsideColors.Bg
val SurfaceColor: Color = BlindsideColors.Surface
val Surface2Color: Color = BlindsideColors.Surface2
val RingColor: Color = BlindsideColors.Ring
val AccentColor: Color = BlindsideColors.Accent
val AccentDimColor: Color = BlindsideColors.AccentDim
val AlertRedColor: Color = BlindsideColors.AlertRed
val AlertRedDimColor: Color = BlindsideColors.AlertRedDim
val WarnColor: Color = BlindsideColors.Warn
val TextColor: Color = BlindsideColors.Text
val Text2Color: Color = BlindsideColors.Text2

val NumberStyle: TextStyle = BlindsideFonts.Numbers

private val PhoneColors: ColorScheme = darkColorScheme(
    primary = AccentColor,
    onPrimary = BgColor,
    primaryContainer = AccentDimColor,
    onPrimaryContainer = TextColor,
    secondary = AccentDimColor,
    onSecondary = TextColor,
    secondaryContainer = Surface2Color,
    onSecondaryContainer = TextColor,
    tertiary = WarnColor,
    onTertiary = BgColor,
    background = BgColor,
    onBackground = TextColor,
    surface = BgColor,
    onSurface = TextColor,
    surfaceVariant = Surface2Color,
    onSurfaceVariant = Text2Color,
    surfaceContainerLowest = BgColor,
    surfaceContainerLow = SurfaceColor,
    surfaceContainer = SurfaceColor,
    surfaceContainerHigh = Surface2Color,
    surfaceContainerHighest = Surface2Color,
    error = AlertRedColor,
    onError = BgColor,
    errorContainer = AlertRedDimColor,
    onErrorContainer = TextColor,
    outline = RingColor,
    outlineVariant = RingColor,
)

private val PhoneTypography: Typography = Typography().withFamily(BlindsideFonts.Sans)

@Composable
fun BlindsideTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PhoneColors, typography = PhoneTypography, content = content)
}

@Composable
fun rememberMotionDurationMs(baseMs: Int): Int {
    val context = LocalContext.current
    return remember(baseMs) {
        motionDurationMs(baseMs, Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
    }
}

private fun Typography.withFamily(family: FontFamily): Typography = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)
```

- [ ] **Step 6: Wrap the activity in the theme**

Replace `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/MainActivity.kt` with:

```kotlin
package io.github.santiquiroz.blindside.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import io.github.santiquiroz.blindside.phone.ui.theme.BlindsideTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BlindsideTheme {
                Surface(Modifier.fillMaxSize()) { Text("Blindside") }
            }
        }
    }
}
```

- [ ] **Step 7: Run the tests and build**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest :phone-app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`, 5 tests passed.

- [ ] **Step 8: Commit**

```bash
cd /c/personal/blindside
git add watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/theme \
  watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/ui/theme \
  watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/MainActivity.kt
git commit -m "feat: tema oscuro del celular sobre los tokens y fuentes compartidos de android-shared"
```

---

### Task 3: Link profile and manual commands on the shared BLE link

This task builds on what plan 05 Tasks 9-10 already put in `shared/ble`. It keeps all of these as they are:
- `BeltRole`, `setRoleCommand` and `GattOp.WriteRole`;
- `GattOp.WriteOpenPairing`, `SetupEffect.PAIRING_WINDOW_OPENED/REFUSED`, `BeltGattEvents.onPairingWindow` and `BeltListener.onPairingWindow`;
- `effectsAfter`, the `applyEffects` pairing branches and `isControlWrite`'s cases.

It replaces only plan 05's `role: BeltRole` parameter with a `profile: BeltLinkProfile`, in the same position. On top of that it adds:
- the manual commands `01` and `03` (`GattOp.WriteCommand`, `send()`);
- an info re-read (`readInfo()` / `refreshInfo()`);
- the per-role connection priority;
- the diagnostic link's `04 00`.

**Files:**
- Modify: `shared/ble/BleIds.kt` (two command builders)
- Create: `shared/ble/BeltCommands.kt`
- Modify: `shared/ble/GattOps.kt` (`GattOp.WriteCommand`, `setupOpsAfterDiscovery(profile)`, `isControlWrite`)
- Modify: `shared/ble/BeltGatt.kt` (constructor, `start`, `handleConnected`, `applyEffects`' priority line, `handleDiscovery`, new `send`, `readInfo`, `requestPriority`, `androidPriority`)
- Modify: `shared/ble/BeltLink.kt` (constructor, `openGatt`, `openPairingWindow`, new `send`, `refreshInfo`, `withStreamingGatt`)
- Modify: `shared/session/RunningSession.kt` (one call site)
- Test: modify `sharedTest/ble/BleIdsTest.kt` and `sharedTest/ble/GattOpsTest.kt`; create `sharedTest/ble/BeltCommandsTest.kt`

**Interfaces:**
- Consumes: plan 05 `BeltRole`, `setRoleCommand`, `openPairingWindowCommand`, `GattOp` (with `WriteRole`, `WriteOpenPairing`), `SetupEffect`, `effectsAfter`, `isControlWrite`, `BeltGatt(…, events, role)`, `BeltLink(context, listener, role, onBeltFound)`, `SessionHost.beltRole`, `REQUESTED_MTU`, `GATT_SUCCESS_STATUS`, `LOCAL_FAILURE_STATUS`, `GATT_OP_TIMEOUT_MS`.
- Produces (package `io.github.santiquiroz.blindside.shared.ble`):
  - `fun restartRadarCommand(radarId: Int): ByteArray` (`01 <id>`), `fun identifyCommand(): ByteArray` (`03`).
  - `sealed interface BeltCommand { data class RestartRadar(val radarId: Int); data object Identify }`, `fun commandBytes(command: BeltCommand): ByteArray`.
  - `enum class LinkPriority { HIGH, BALANCED }`, `fun connectPriorityFor(role: BeltRole): LinkPriority`, `fun settledPriorityFor(role: BeltRole): LinkPriority?`.
  - `data class BeltLinkProfile(val role: BeltRole, val activatesSession: Boolean = true)`.
  - `GattOp.WriteCommand(val command: BeltCommand)`, `fun setupOpsAfterDiscovery(profile: BeltLinkProfile): List<GattOp>`.
  - `BeltGatt(context, device, handler, events, profile)` with `send(command)`, `readInfo()`.
  - `BeltLink(context, listener, profile, onBeltFound)` with `send(command): Boolean`, `refreshInfo(): Boolean`.

- [ ] **Step 1: Confirm what plan 05 left in `ble`**

```bash
cd /c/personal/blindside/watch
S=android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared
grep -n "enum class BeltRole\|fun setRoleCommand\|fun openPairingWindowCommand" $S/ble/BleIds.kt
grep -n "data class WriteRole\|data object WriteOpenPairing\|fun setupOpsAfterDiscovery\|fun isControlWrite\|PAIRING_WINDOW_OPENED" $S/ble/GattOps.kt
grep -n "private val role: BeltRole\|fun openPairingWindow\|BeltGatt(context, target, handler, this, role)\|setupOpsAfterDiscovery(role)" $S/ble/BeltGatt.kt $S/ble/BeltLink.kt
grep -n "host.beltRole" $S/session/RunningSession.kt
```

Expected: each grep prints its lines, with `fun setupOpsAfterDiscovery(role: BeltRole)` and `private val role: BeltRole` in both `BeltGatt.kt` and `BeltLink.kt`. If one is missing, plan 05 Tasks 9-10 are not in: stop and report BLOCKED.

- [ ] **Step 2: Write the failing tests**

In `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/ble/BleIdsTest.kt`, add:

```kotlin
    @Test
    fun `restart radar carries the radar id and identify is one byte`() {
        assertEquals(listOf<Byte>(1, 0), restartRadarCommand(0).toList())
        assertEquals(listOf<Byte>(1, 1), restartRadarCommand(1).toList())
        assertEquals(listOf<Byte>(3), identifyCommand().toList())
    }
```

Create `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/ble/BeltCommandsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.ble

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BeltCommandsTest {
    @Test
    fun `each manual command becomes its control bytes`() {
        assertArrayEquals(restartRadarCommand(1), commandBytes(BeltCommand.RestartRadar(1)))
        assertArrayEquals(identifyCommand(), commandBytes(BeltCommand.Identify))
    }

    @Test
    fun `the watch connects fast and settles balanced`() {
        assertEquals(LinkPriority.HIGH, connectPriorityFor(BeltRole.WATCH))
        assertEquals(LinkPriority.BALANCED, settledPriorityFor(BeltRole.WATCH))
    }

    @Test
    fun `the phone never settles a priority so the belt's 60 to 100 ms request stands`() {
        assertEquals(LinkPriority.BALANCED, connectPriorityFor(BeltRole.PHONE))
        assertNull(settledPriorityFor(BeltRole.PHONE))
    }

    @Test
    fun `a link profile activates the session unless told otherwise`() {
        assertTrue(BeltLinkProfile(BeltRole.PHONE).activatesSession)
    }
}
```

In `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/ble/GattOpsTest.kt`:

1. Point plan 05's calls at a profile and rename the control-write test:

```bash
cd /c/personal/blindside/watch
T=android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/ble/GattOpsTest.kt
sed -i -E 's/setupOpsAfterDiscovery\(BeltRole\.(WATCH|PHONE)\)/setupOpsAfterDiscovery(BeltLinkProfile(BeltRole.\1))/g' "$T"
sed -i 's/control writes are the role, session and pairing writes/control writes are the role, session, pairing and command writes/' "$T"
grep -n "setupOpsAfterDiscovery\|control writes are" "$T"
```

   Expected: three `setupOpsAfterDiscovery(BeltLinkProfile(BeltRole.…))` calls (one in the setup test, two in `the phone sends its own role`) and the renamed test.

2. In the renamed test, add the line:

```kotlin
        assertTrue(isControlWrite(GattOp.WriteCommand(BeltCommand.Identify)))
```

3. Add three tests:

```kotlin
    @Test
    fun `a diagnostic link marks its slot inactive instead of starting a session`() {
        val expected = listOf(
            GattOp.RequestMtu(REQUESTED_MTU),
            GattOp.ReadInfo,
            GattOp.WriteRole(BeltRole.PHONE),
            GattOp.EnableStreamNotify,
            GattOp.WriteSessionActive(false),
        )
        assertEquals(expected, setupOpsAfterDiscovery(BeltLinkProfile(BeltRole.PHONE, activatesSession = false)))
    }

    @Test
    fun `a refused session reset or manual command never ends the setup`() {
        assertEquals(nothing, effectsAfter(GattOp.WriteSessionActive(false), 3))
        assertEquals(nothing, effectsAfter(GattOp.WriteCommand(BeltCommand.Identify), LOCAL_FAILURE_STATUS))
        assertEquals(nothing, effectsAfter(GattOp.WriteCommand(BeltCommand.RestartRadar(0)), GATT_SUCCESS_STATUS))
    }

    @Test
    fun `manual commands use the regular write timeout`() {
        assertEquals(GATT_OP_TIMEOUT_MS, timeoutMsFor(GattOp.WriteCommand(BeltCommand.Identify)))
    }
```

- [ ] **Step 3: Run them to make sure they fail**

Run: `cd /c/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest --tests "io.github.santiquiroz.blindside.shared.ble.*" --console=plain`
Expected: FAIL with `Unresolved reference 'restartRadarCommand'` and `'BeltLinkProfile'`.

- [ ] **Step 4: Write the command bytes and the link profile**

In `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/ble/BleIds.kt`, add after `fun openPairingWindowCommand()`:

```kotlin
private const val CMD_RESTART_RADAR: Byte = 0x01
private const val CMD_IDENTIFY: Byte = 0x03

fun restartRadarCommand(radarId: Int): ByteArray = byteArrayOf(CMD_RESTART_RADAR, radarId.toByte())

fun identifyCommand(): ByteArray = byteArrayOf(CMD_IDENTIFY)
```

Create `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/ble/BeltCommands.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.ble

sealed interface BeltCommand {
    data class RestartRadar(val radarId: Int) : BeltCommand
    data object Identify : BeltCommand
}

enum class LinkPriority { HIGH, BALANCED }

data class BeltLinkProfile(val role: BeltRole, val activatesSession: Boolean = true)

fun commandBytes(command: BeltCommand): ByteArray = when (command) {
    is BeltCommand.RestartRadar -> restartRadarCommand(command.radarId)
    BeltCommand.Identify -> identifyCommand()
}

fun connectPriorityFor(role: BeltRole): LinkPriority = if (role == BeltRole.WATCH) LinkPriority.HIGH else LinkPriority.BALANCED

// Spec §2: after 06 01 the belt asks for 60-100 ms itself, and any later request from the phone would override it.
fun settledPriorityFor(role: BeltRole): LinkPriority? = if (role == BeltRole.WATCH) LinkPriority.BALANCED else null
```

- [ ] **Step 5: Teach the op queue about profiles and commands (edit in place)**

In `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/ble/GattOps.kt`:

1. Add one member to `sealed interface GattOp`, after `data object WriteOpenPairing : GattOp`:

```kotlin
    data class WriteCommand(val command: BeltCommand) : GattOp
```

2. Replace plan 05's whole `setupOpsAfterDiscovery(role: BeltRole)` function, including its comment line, with:

```kotlin
// The control writes need the encrypted link the info read sets up; the role goes before the subscription so the belt applies its connection parameters first.
fun setupOpsAfterDiscovery(profile: BeltLinkProfile): List<GattOp> = listOf(
    GattOp.RequestMtu(REQUESTED_MTU),
    GattOp.ReadInfo,
    GattOp.WriteRole(profile.role),
    GattOp.EnableStreamNotify,
    GattOp.WriteSessionActive(profile.activatesSession),
)
```

   A diagnostic link writes `04 00`. The belt keeps `SESSION_ACTIVE` per slot across disconnections (plan 04 H28), so a stale `1` on the slot it lands on would otherwise block `IDENTIFY` silently.

3. Replace plan 05's `isControlWrite` with:

```kotlin
fun isControlWrite(op: GattOp): Boolean = when (op) {
    is GattOp.WriteRole, is GattOp.WriteSessionActive, GattOp.WriteOpenPairing, is GattOp.WriteCommand -> true
    else -> false
}
```

Leave `SetupEffect`, `effectsAfter` and `pairingWindowEffect` exactly as plan 05 left them:
- a `WriteCommand` and a `WriteSessionActive(false)` fall into `else -> emptyList()`;
- `WriteOpenPairing` keeps reporting `PAIRING_WINDOW_OPENED` / `PAIRING_WINDOW_REFUSED`.

- [ ] **Step 6: Run the pure tests**

Run: `cd /c/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest --tests "io.github.santiquiroz.blindside.shared.ble.*" --console=plain`
Expected: compilation fails only in `BeltGatt.kt`, with `'when' expression must be exhaustive, add necessary 'is WriteCommand' branch` and `Argument type mismatch` at `setupOpsAfterDiscovery(role)`. The next two steps fix both.

- [ ] **Step 7: Give `BeltGatt` the profile, commands and info re-reads (edit in place)**

In `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/ble/BeltGatt.kt`:

1. In the constructor, replace `private val role: BeltRole,` with:

```kotlin
    private val profile: BeltLinkProfile,
```

2. After `fun openPairingWindow() = enqueueOp(GattOp.WriteOpenPairing)`, add:

```kotlin
    fun send(command: BeltCommand) = enqueueOp(GattOp.WriteCommand(command))

    fun readInfo() = enqueueOp(GattOp.ReadInfo)
```

3. In `private fun start(op: GattOp): Boolean`, add this branch right after plan 05's `GattOp.WriteOpenPairing -> writeControl(current, openPairingWindowCommand())`:

```kotlin
            is GattOp.WriteCommand -> writeControl(current, commandBytes(op.command))
```

4. In `handleDiscovery`, replace `setupOpsAfterDiscovery(role).forEach(::enqueueOp)` with:

```kotlin
setupOpsAfterDiscovery(profile).forEach(::enqueueOp)
```

5. In `handleConnected`, replace `gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)` with:

```kotlin
        requestPriority(connectPriorityFor(profile.role))
```

6. In `applyEffects`, replace **only** the `LOWER_PRIORITY` line with the one below, and keep the `REPORT_LINK_UP` line and both `PAIRING_WINDOW_*` lines plan 05 added:

```kotlin
        if (SetupEffect.LOWER_PRIORITY in effects) settledPriorityFor(profile.role)?.let(::requestPriority)
```

7. Add before `private val callback = object : BluetoothGattCallback() {`:

```kotlin
    private fun requestPriority(priority: LinkPriority) {
        gatt?.requestConnectionPriority(androidPriority(priority))
    }

    private fun androidPriority(priority: LinkPriority): Int = when (priority) {
        LinkPriority.HIGH -> BluetoothGatt.CONNECTION_PRIORITY_HIGH
        LinkPriority.BALANCED -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
    }
```

`handleControlWrite` (plan 05: `queue.inFlight?.takeIf(::isControlWrite)`) and `handleInfoRead` (`queue.inFlight != GattOp.ReadInfo`) already cover a `WriteCommand` and a re-read: leave them.

- [ ] **Step 8: Give `BeltLink` the profile and the manual actions (edit in place)**

In `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/ble/BeltLink.kt`:

1. In the constructor, replace `private val role: BeltRole,` with `private val profile: BeltLinkProfile,`. It stays second, after `listener`, so the signature reads `BeltLink(context, listener, profile, onBeltFound)`.
2. In `openGatt`, replace `BeltGatt(context, target, handler, this, role)` with `BeltGatt(context, target, handler, this, profile)`.
3. Replace plan 05's whole `fun openPairingWindow(): Boolean { … }` with:

```kotlin
    fun openPairingWindow(): Boolean = withStreamingGatt { it.openPairingWindow() }

    fun send(command: BeltCommand): Boolean = withStreamingGatt { it.send(command) }

    fun refreshInfo(): Boolean = withStreamingGatt { it.readInfo() }
```

4. Add before `private fun connectingStatus()`:

```kotlin
    // Control writes ride only on the encrypted, subscribed link; before that the setup queue owns the GATT.
    private fun withStreamingGatt(action: (BeltGatt) -> Unit): Boolean {
        val current = gatt?.takeIf { reporter.up } ?: return false
        action(current)
        return true
    }
```

- [ ] **Step 9: Keep the session building its link from the host's role**

In `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/session/RunningSession.kt`:
- Add the import `import io.github.santiquiroz.blindside.shared.ble.BeltLinkProfile`.
- Replace `BeltLink(context, BeltInputs(inputs), host.beltRole, onBeltFound = ::rememberBelt)` with:

```kotlin
BeltLink(context, BeltInputs(inputs), BeltLinkProfile(host.beltRole), onBeltFound = ::rememberBelt)
```

Check that nothing else still passes a bare role:

```bash
cd /c/personal/blindside/watch
grep -rn "setupOpsAfterDiscovery(role\|private val role: BeltRole\|this, role)\|host.beltRole, onBeltFound" android-shared/src wear-app/src || echo "no bare roles left"
```

Expected: `no bare roles left`.

- [ ] **Step 10: Run the watch gate and the test-count one-liner**

Expected: `BUILD SUCCESSFUL`; `android-shared 261` (253 + 8), `wear-app 46`. The watch's setup is byte for byte plan 05's: `BeltLinkProfile(BeltRole.WATCH)` gives `06 00` then `04 01`, `HIGH` then `BALANCED`.

- [ ] **Step 11: Commit**

```bash
cd /c/personal/blindside
git add watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/ble \
  watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/session/RunningSession.kt \
  watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/ble
git commit -m "feat: perfil de enlace con rol, comandos manuales 01/03 y relectura de info en el enlace compartido"
```

---

### Task 4: Shared-settings clock guard and watch-status freshness

**Files:**
- Modify: `shared/settings/SharedSettings.kt` (`stampSharedEdit`)
- Create: `shared/bridge/StatusFreshness.kt`
- Test: modify `sharedTest/settings/SharedSettingsTest.kt`; create `sharedTest/bridge/StatusFreshnessTest.kt` and `sharedTest/bridge/SharedSettingsCrossDeviceTest.kt`

**Interfaces:**
- Consumes: plan 05 `SharedSettings`, `sharedSettingsOf`, `adoptingNewer`, `stampSharedEdit`, `encodeSharedSettings`, `decodeSharedSettings`, `WatchStatus`, `STATUS_PERIOD_MS`; settings helpers `withHandedness`, `withYawNudged`, `withFlipXToggled`, `withSpeedSignFlipped`, `radar`, `YAW_STEP_DEG`.
- Produces:
  - `stampSharedEdit(before, after, nowMs)`: a local edit of a shared field is stamped `max(nowMs, before.sharedUpdatedMs + 1)`, so the stamp never goes backwards.
  - Package `io.github.santiquiroz.blindside.shared.bridge`: `const val STATUS_STALE_AFTER_MS = 3 * STATUS_PERIOD_MS`, `fun isStatusFresh(status: WatchStatus, nowMs: Long): Boolean` and `fun watchSessionActive(status: WatchStatus?, nowMs: Long): Boolean`.

- [ ] **Step 1: Confirm plan 05's bridge and stamping are in place**

```bash
cd /c/personal/blindside/watch
S=android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared
ls $S/bridge
grep -n "fun stampSharedEdit\|fun AppSettings.adoptingNewer" $S/settings/SharedSettings.kt
grep -n "clock: () -> Long" $S/settings/SettingsRepository.kt
```

Expected:
- `BridgePaths.kt`, `JsonFields.kt`, `OpenPairingReply.kt`, `RecordingChannel.kt`, `RecordingListCodec.kt`, `SharedSettingsCodec.kt`, `WatchStatus.kt`.
- The two functions and the repository clock.

This plan never redeclares any of them. If one is missing, stop and report BLOCKED.

- [ ] **Step 2: Write the failing tests**

In `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/settings/SharedSettingsTest.kt`, add:

```kotlin
    @Test
    fun `a local edit with a clock behind the last stamp still moves the stamp forward`() {
        val adopted = AppSettings(sharedUpdatedMs = 5_000L)
        assertEquals(5_001L, stampSharedEdit(adopted, adopted.withHandedness(Handedness.LEFT), nowMs = 1_000L).sharedUpdatedMs)
    }
```

Create `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/bridge/StatusFreshnessTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.bridge

import io.github.santiquiroz.blindside.core.scene.SensorStatus
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StatusFreshnessTest {
    private val status = WatchStatus(
        sessionActive = true,
        ble = BleStatus.STREAMING,
        linkUp = true,
        radars = listOf(SensorStatus(0, true)),
        updatedMs = 10_000L,
    )

    @Test
    fun `a status stays fresh for three publish periods`() {
        assertEquals(3 * STATUS_PERIOD_MS, STATUS_STALE_AFTER_MS)
        assertTrue(isStatusFresh(status, nowMs = 25_000L))
        assertFalse(isStatusFresh(status, nowMs = 25_001L))
    }

    @Test
    fun `only a fresh status can report an active session`() {
        assertTrue(watchSessionActive(status, nowMs = 12_000L))
        assertFalse(watchSessionActive(status, nowMs = 60_000L))
        assertFalse(watchSessionActive(status.copy(sessionActive = false), nowMs = 12_000L))
        assertFalse(watchSessionActive(null, nowMs = 12_000L))
    }

    @Test
    fun `a status stamped by a watch clock slightly ahead still counts as fresh`() {
        assertTrue(isStatusFresh(status, nowMs = 9_000L))
    }
}
```

Create `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/bridge/SharedSettingsCrossDeviceTest.kt`. It runs both apps' real code path: an edit stamped by `stampSharedEdit`, encoded by one device, decoded and adopted by the other.

```kotlin
package io.github.santiquiroz.blindside.shared.bridge

import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import io.github.santiquiroz.blindside.shared.settings.ScreenMode
import io.github.santiquiroz.blindside.shared.settings.SharedSettings
import io.github.santiquiroz.blindside.shared.settings.YAW_STEP_DEG
import io.github.santiquiroz.blindside.shared.settings.adoptingNewer
import io.github.santiquiroz.blindside.shared.settings.radar
import io.github.santiquiroz.blindside.shared.settings.sharedSettingsOf
import io.github.santiquiroz.blindside.shared.settings.stampSharedEdit
import io.github.santiquiroz.blindside.shared.settings.withFlipXToggled
import io.github.santiquiroz.blindside.shared.settings.withHandedness
import io.github.santiquiroz.blindside.shared.settings.withSpeedSignFlipped
import io.github.santiquiroz.blindside.shared.settings.withYawNudged
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SharedSettingsCrossDeviceTest {
    private val watch = AppSettings(screenMode = ScreenMode.VISTA, beltAddress = "AA:BB", sharedUpdatedMs = 1_000L)
    private val phone = AppSettings(sharedUpdatedMs = 1_000L)

    // withHandedness clears yaw overrides, so the hand changes first and the yaw is nudged after it.
    private fun edited(before: AppSettings, nowMs: Long): AppSettings = stampSharedEdit(
        before,
        before.withHandedness(Handedness.LEFT).withYawNudged(RADAR_A, -YAW_STEP_DEG).withFlipXToggled(RADAR_B).withSpeedSignFlipped(RADAR_A),
        nowMs,
    )

    private fun overTheBridge(settings: AppSettings): SharedSettings = decodeSharedSettings(encodeSharedSettings(sharedSettingsOf(settings)))!!

    @Test
    fun `a phone edit reaches the watch with its hand, yaw, flip and sign`() {
        val phoneEdit = edited(phone, nowMs = 2_000L)
        val atWatch = watch.adoptingNewer(overTheBridge(phoneEdit))
        assertEquals(phoneEdit.radars, atWatch.radars)
        assertEquals(Handedness.LEFT, atWatch.handedness)
        assertEquals(ScreenMode.VISTA, atWatch.screenMode)
        assertEquals("AA:BB", atWatch.beltAddress)
    }

    @Test
    fun `a custom yaw survives the trip in both directions`() {
        val watchEdit = edited(watch, nowMs = 2_000L)
        assertNotNull(watchEdit.radar(RADAR_A).yawDegOverride)
        assertEquals(watchEdit.radar(RADAR_A).yawDegOverride, phone.adoptingNewer(overTheBridge(watchEdit)).radar(RADAR_A).yawDegOverride)
    }

    @Test
    fun `the echo of a phone edit never undoes it`() {
        val phoneEdit = edited(phone, nowMs = 2_000L)
        val atWatch = watch.adoptingNewer(overTheBridge(phoneEdit))
        assertEquals(phoneEdit, phoneEdit.adoptingNewer(overTheBridge(atWatch)))
    }

    @Test
    fun `an older watch write arriving after a phone edit is ignored`() {
        val phoneEdit = edited(phone, nowMs = 2_000L)
        val olderWatch = stampSharedEdit(watch, watch.withHandedness(Handedness.SWITCHER), nowMs = 1_500L)
        assertEquals(phoneEdit, phoneEdit.adoptingNewer(overTheBridge(olderWatch)))
    }
}
```

- [ ] **Step 3: Run them to make sure they fail**

Run: `cd /c/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest --tests "io.github.santiquiroz.blindside.shared.bridge.*" --tests "io.github.santiquiroz.blindside.shared.settings.SharedSettingsTest" --console=plain`
Expected:
- FAIL with `Unresolved reference 'STATUS_STALE_AFTER_MS'`.
- Once that compiles, `a local edit with a clock behind…` fails with `expected: <5001> but was: <1000>`.

- [ ] **Step 4: Stamps never go backwards**

In `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/settings/SharedSettings.kt`, replace plan 05's `stampSharedEdit`, together with its comment line, with:

```kotlin
// An adoption keeps the remote stamp; a local edit is stamped past the last one, so a clock behind the other device's still writes the newest value.
fun stampSharedEdit(before: AppSettings, after: AppSettings, nowMs: Long): AppSettings = when {
    after.sharedUpdatedMs != before.sharedUpdatedMs -> after
    sharedSettingsOf(after) != sharedSettingsOf(before) -> after.copy(sharedUpdatedMs = maxOf(nowMs, before.sharedUpdatedMs + 1))
    else -> after
}
```

Plan 05's `SharedSettingsTest` and `SettingsRepositoryTest` keep passing: every stamp they expect is already above the previous one.

- [ ] **Step 5: Write the status freshness rule**

Create `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/bridge/StatusFreshness.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.bridge

const val STATUS_STALE_AFTER_MS = 3 * STATUS_PERIOD_MS

// The watch publishes every 5 s only while its game runs, so three missed periods mean it stopped or walked away.
fun isStatusFresh(status: WatchStatus, nowMs: Long): Boolean = nowMs - status.updatedMs <= STATUS_STALE_AFTER_MS

fun watchSessionActive(status: WatchStatus?, nowMs: Long): Boolean =
    status != null && status.sessionActive && isStatusFresh(status, nowMs)
```

- [ ] **Step 6: Run the watch gate and the test-count one-liner**

Expected: `BUILD SUCCESSFUL`; `android-shared 269` (261 + 8), `wear-app 46`.

- [ ] **Step 7: Commit**

```bash
cd /c/personal/blindside
git add watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/settings/SharedSettings.kt \
  watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/bridge/StatusFreshness.kt \
  watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/settings/SharedSettingsTest.kt \
  watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/bridge
git commit -m "feat: el sello de los ajustes compartidos nunca retrocede y el estado del reloj caduca a los 15 s"
```

---

### Task 5: The shared session serves both apps (purpose, traits, manual commands)

Spec §3 generalises the service: each app implements `SessionHost` and sends its own role. Plan 05 built `SessionService`, `SessionHost`, `SessionCommands(serviceClass)` and `RunningSession(…, host)` for exactly this. This task adds the hooks the phone needs, all with defaults equal to today's watch behaviour, so `WearSessionHost` and every `wear-app` call site compile and run unchanged:
- **A purpose:** a game, or a diagnostic link (Deviation P2).
- **Per-app traits:**
  - the link profile;
  - whether the session records;
  - whether it reads the device's sensors;
  - whether it vibrates;
  - a fixed frame period;
  - the recording-name tag.
- **The foreground service type.**
- **A belt-listener wrapper and a counters callback,** for the phone's diagnostics.
- **The manual belt commands `01`/`03` and an info re-read,** through the service.

**Files:**
- Create: `shared/session/SessionPurpose.kt`, `shared/session/SessionTraits.kt`, `shared/session/CommandExtras.kt`
- Modify: `shared/session/ScenePacing.kt` (`pacedPeriodMs`), `shared/session/SessionStore.kt` (`purpose`), `shared/session/SessionSource.kt` (`ongoingStatus` purpose)
- Rewrite: `shared/session/SessionHost.kt`, `shared/session/SessionActions.kt`, `shared/session/SessionCommands.kt`, `shared/session/SessionService.kt`, `shared/session/RunningSession.kt`
- Test: create `sharedTest/session/SessionPurposeTest.kt`, `SessionTraitsTest.kt`, `CommandExtrasTest.kt`; modify `sharedTest/session/ScenePacingTest.kt`, `SessionStoreTest.kt`, `SessionSourceTest.kt`

**Interfaces:**
- Consumes:
  - Task 3: `BeltCommand`, `BeltLinkProfile`, `BeltLink.send`, `BeltLink.refreshInfo`.
  - Plan 05 Tasks 6, 7, 9, 10 and 13, as they left these files: `SessionHost` (`appVersion`, `notificationId`, `beltRole`, `ensureNotificationChannel`, `notification`, `launchCompanions`), `SessionService`, `SessionActions`, `SessionCommands`, `RunningSession` (`openPairingWindow`), `pairingAfterRequest`.
- Produces (package `io.github.santiquiroz.blindside.shared.session`):
  - **Purpose:** `enum class SessionPurpose { GAME, DIAGNOSTIC }`, `enum class StartTransition { KEEP, START, RESTART }`, `fun purposeFrom(name: String?): SessionPurpose` and `fun startTransition(running: SessionPurpose?, requested: SessionPurpose): StartTransition`.
  - **Traits:** `data class SessionTraits(link: BeltLinkProfile, records = true, readsDeviceSensors = true, vibrates = true, framePeriodMs: Long? = null, recordingTag: String? = null)`, `val SILENT_HAPTICS: HapticSink`, `fun hapticsFor(traits, player: HapticSink): HapticSink` and `fun recordingTagFor(traits, source): String`.
  - **Pacing:** `fun pacedPeriodMs(framePeriodMs: Long?, radarVisible: Boolean, mode: ScreenMode, ambient: Boolean): Long?`.
  - **Command extras:** `data class CommandExtras(val kind: String, val radarId: Int)`, `COMMAND_RESTART_RADAR`, `COMMAND_IDENTIFY`, `NO_RADAR_ID`, `fun commandExtras(command: BeltCommand): CommandExtras` and `fun commandFrom(extras: CommandExtras): BeltCommand?`.
  - **State:** `SessionUiState.purpose: SessionPurpose?`, `startedState(previous, source, purpose = SessionPurpose.GAME)` and `ongoingStatus(eliminated, source, purpose = SessionPurpose.GAME)`.
  - **`SessionHost` gains, each with a default:**
    - `suspend fun traitsFor(context: Context, purpose: SessionPurpose): SessionTraits` (default `SessionTraits(BeltLinkProfile(beltRole))`);
    - `fun foregroundTypes(source: SessionSource): Int` (default `foregroundTypesFor(source)`);
    - `fun beltListener(inner: BeltListener): BeltListener` (default `inner`);
    - `fun onScene(counters: PipelineCounters)` (default no-op).
  - **Service and commands:**
    - `SessionActions.ACTION_SEND_COMMAND`, `ACTION_REFRESH_INFO`, `EXTRA_PURPOSE`, `EXTRA_COMMAND` and `EXTRA_RADAR_ID`.
    - `SessionCommands.start(context, source, wakeLock = true, purpose = SessionPurpose.GAME)`, `send(context, command)`, `refreshInfo(context)` and `stopIntent(context)`.
    - `RunningSession(context, source, settings, scope, useWakeLock, host, purpose = SessionPurpose.GAME)` with `send(command): Boolean` and `refreshInfo(): Boolean`.

- [ ] **Step 1: Confirm the plan 05 shape of the session files**

```bash
cd /c/personal/blindside/watch
S=android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/session
grep -n "val beltRole\|fun launchCompanions" $S/SessionHost.kt
grep -n "ACTION_OPEN_PAIRING\|companions\|requestPairingWindow" $S/SessionService.kt
grep -n "fun openPairing\|fun toggleEliminatedIntent" $S/SessionCommands.kt
grep -n "private val host: SessionHost\|fun openPairingWindow\|host.appVersion\|BeltLinkProfile(host.beltRole)" $S/RunningSession.kt
ls $S
```

Expected:
- Every grep prints its lines.
- `ls` shows plan 05's 14 session files plus `PhonePairing.kt`.

The rewrites below are the plan 05 files with this task's additions folded in. If a file holds anything these greps did not expect (a later fix commit, say), keep that part in the rewrite too and note it in the commit body.

- [ ] **Step 2: Write the failing tests**

Create `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/session/SessionPurposeTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SessionPurposeTest {
    @Test
    fun `an unknown or missing purpose is a game`() {
        assertEquals(SessionPurpose.GAME, purposeFrom(null))
        assertEquals(SessionPurpose.GAME, purposeFrom("PARTY"))
        assertEquals(SessionPurpose.DIAGNOSTIC, purposeFrom("DIAGNOSTIC"))
    }

    @Test
    fun `asking for the running purpose keeps it, another purpose restarts and nothing running starts`() {
        assertEquals(StartTransition.START, startTransition(null, SessionPurpose.GAME))
        assertEquals(StartTransition.KEEP, startTransition(SessionPurpose.GAME, SessionPurpose.GAME))
        assertEquals(StartTransition.RESTART, startTransition(SessionPurpose.DIAGNOSTIC, SessionPurpose.GAME))
        assertEquals(StartTransition.RESTART, startTransition(SessionPurpose.GAME, SessionPurpose.DIAGNOSTIC))
    }
}
```

Create `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/session/SessionTraitsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.session

import io.github.santiquiroz.blindside.shared.ble.BeltLinkProfile
import io.github.santiquiroz.blindside.shared.ble.BeltRole
import io.github.santiquiroz.blindside.shared.haptics.HapticSink
import io.github.santiquiroz.blindside.shared.haptics.LEFT_PATTERN
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionTraitsTest {
    private val watchGame = SessionTraits(BeltLinkProfile(BeltRole.WATCH))

    @Test
    fun `the default traits are the watch game exactly as it ran before`() {
        assertTrue(watchGame.link.activatesSession)
        assertTrue(watchGame.records)
        assertTrue(watchGame.readsDeviceSensors)
        assertTrue(watchGame.vibrates)
        assertNull(watchGame.framePeriodMs)
        assertEquals("BELT", recordingTagFor(watchGame, SessionSource.BELT))
        assertEquals("DEMO", recordingTagFor(watchGame, SessionSource.DEMO))
    }

    @Test
    fun `a session that must not vibrate gets a silent sink`() {
        val player = HapticSink { }
        assertSame(player, hapticsFor(watchGame, player))
        assertSame(SILENT_HAPTICS, hapticsFor(watchGame.copy(vibrates = false), player))
        SILENT_HAPTICS.play(LEFT_PATTERN)
    }

    @Test
    fun `a host tag renames the recording file without touching the source`() {
        assertEquals("PHONE", recordingTagFor(watchGame.copy(recordingTag = "PHONE"), SessionSource.BELT))
    }
}
```

Create `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/session/CommandExtrasTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.session

import io.github.santiquiroz.blindside.shared.ble.BeltCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CommandExtrasTest {
    @Test
    fun `restart and identify survive the trip through intent extras`() {
        listOf(BeltCommand.RestartRadar(0), BeltCommand.RestartRadar(1), BeltCommand.Identify).forEach { command ->
            assertEquals(command, commandFrom(commandExtras(command)))
        }
    }

    @Test
    fun `a radar id other than A or B is rejected`() {
        assertNull(commandFrom(CommandExtras(COMMAND_RESTART_RADAR, 2)))
        assertNull(commandFrom(CommandExtras(COMMAND_RESTART_RADAR, NO_RADAR_ID)))
    }

    @Test
    fun `unknown command kinds are rejected`() {
        assertNull(commandFrom(CommandExtras("format", 0)))
    }
}
```

In `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/session/ScenePacingTest.kt`, add (with `import org.junit.jupiter.api.Assertions.assertNull` if missing):

```kotlin
    @Test
    fun `a fixed frame period replaces the screen mode only while the radar is on screen`() {
        assertEquals(33L, pacedPeriodMs(33L, radarVisible = true, mode = ScreenMode.SIGILO, ambient = false))
        assertNull(pacedPeriodMs(33L, radarVisible = false, mode = ScreenMode.VISTA, ambient = false))
        assertNull(pacedPeriodMs(33L, radarVisible = true, mode = ScreenMode.VISTA, ambient = true))
        assertEquals(
            scenePeriodMs(radarVisible = true, mode = ScreenMode.SIGILO, ambient = false),
            pacedPeriodMs(null, radarVisible = true, mode = ScreenMode.SIGILO, ambient = false),
        )
    }
```

In `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/session/SessionStoreTest.kt`, add:

```kotlin
    @Test
    fun `a started session remembers its purpose and stopping forgets it`() {
        val diagnostic = startedState(SessionUiState(), SessionSource.BELT, SessionPurpose.DIAGNOSTIC)
        assertEquals(SessionPurpose.DIAGNOSTIC, diagnostic.purpose)
        assertEquals(SessionPurpose.GAME, startedState(SessionUiState(), SessionSource.BELT).purpose)
        assertNull(stoppedState(diagnostic).purpose)
    }
```

In `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/session/SessionSourceTest.kt`, add:

```kotlin
    @Test
    fun `a diagnostic session says so in its notification`() {
        assertEquals("Diagnóstico del cinturón", ongoingStatus(eliminated = false, source = SessionSource.BELT, purpose = SessionPurpose.DIAGNOSTIC))
        assertEquals("Eliminado", ongoingStatus(eliminated = true, source = SessionSource.BELT, purpose = SessionPurpose.GAME))
    }
```

- [ ] **Step 3: Run them to make sure they fail**

Run: `cd /c/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest --tests "io.github.santiquiroz.blindside.shared.session.*" --console=plain`
Expected: FAIL with `Unresolved reference 'SessionPurpose'`.

- [ ] **Step 4: Write the pure session additions**

Create `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/session/SessionPurpose.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.session

enum class SessionPurpose { GAME, DIAGNOSTIC }

enum class StartTransition { KEEP, START, RESTART }

fun purposeFrom(name: String?): SessionPurpose = SessionPurpose.entries.firstOrNull { it.name == name } ?: SessionPurpose.GAME

fun startTransition(running: SessionPurpose?, requested: SessionPurpose): StartTransition = when (running) {
    null -> StartTransition.START
    requested -> StartTransition.KEEP
    else -> StartTransition.RESTART
}
```

Create `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/session/SessionTraits.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.session

import io.github.santiquiroz.blindside.shared.ble.BeltLinkProfile
import io.github.santiquiroz.blindside.shared.haptics.HapticSink

data class SessionTraits(
    val link: BeltLinkProfile,
    val records: Boolean = true,
    val readsDeviceSensors: Boolean = true,
    val vibrates: Boolean = true,
    val framePeriodMs: Long? = null,
    val recordingTag: String? = null,
)

val SILENT_HAPTICS = HapticSink { }

fun hapticsFor(traits: SessionTraits, player: HapticSink): HapticSink = if (traits.vibrates) player else SILENT_HAPTICS

fun recordingTagFor(traits: SessionTraits, source: SessionSource): String = traits.recordingTag ?: source.name
```

Create `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/session/CommandExtras.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.session

import io.github.santiquiroz.blindside.shared.ble.BeltCommand

data class CommandExtras(val kind: String, val radarId: Int)

const val COMMAND_RESTART_RADAR = "restart_radar"
const val COMMAND_IDENTIFY = "identify"
const val NO_RADAR_ID = -1

private val VALID_RADAR_IDS = 0..1

fun commandExtras(command: BeltCommand): CommandExtras = when (command) {
    is BeltCommand.RestartRadar -> CommandExtras(COMMAND_RESTART_RADAR, command.radarId)
    BeltCommand.Identify -> CommandExtras(COMMAND_IDENTIFY, NO_RADAR_ID)
}

// The service receives intents, so anything that is not a known command for radar A or B is dropped here.
fun commandFrom(extras: CommandExtras): BeltCommand? = when (extras.kind) {
    COMMAND_RESTART_RADAR -> extras.radarId.takeIf { it in VALID_RADAR_IDS }?.let { BeltCommand.RestartRadar(it) }
    COMMAND_IDENTIFY -> BeltCommand.Identify
    else -> null
}
```

In `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/session/ScenePacing.kt`, add at the end:

```kotlin
// A host with its own frame rate (the phone) keeps the same rule: no scenes off screen or in ambient.
fun pacedPeriodMs(framePeriodMs: Long?, radarVisible: Boolean, mode: ScreenMode, ambient: Boolean): Long? {
    val screenPeriod = scenePeriodMs(radarVisible, mode, ambient) ?: return null
    return framePeriodMs ?: screenPeriod
}
```

In `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/session/SessionStore.kt`:
- In `data class SessionUiState`, add `val purpose: SessionPurpose? = null,` right after `val source: SessionSource? = null,`.
- Replace `startedState` with:

```kotlin
fun startedState(previous: SessionUiState, source: SessionSource, purpose: SessionPurpose = SessionPurpose.GAME): SessionUiState =
    SessionUiState(running = true, source = source, purpose = purpose, lastRecordingName = previous.lastRecordingName)
```

`stoppedState` builds a fresh `SessionUiState`, so the purpose goes back to `null` without a change.

In `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/session/SessionSource.kt`, replace `ongoingStatus` with:

```kotlin
fun ongoingStatus(eliminated: Boolean, source: SessionSource, purpose: SessionPurpose = SessionPurpose.GAME): String = when {
    purpose == SessionPurpose.DIAGNOSTIC -> "Diagnóstico del cinturón"
    eliminated -> "Eliminado"
    source == SessionSource.DEMO -> "Demo en curso"
    else -> "Partida en curso"
}
```

- [ ] **Step 5: Run the pure tests**

Run: `cd /c/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest --tests "io.github.santiquiroz.blindside.shared.session.*" --console=plain`
Expected: PASS (2 + 3 + 3 new tests, plus one more in each of `ScenePacingTest`, `SessionStoreTest` and `SessionSourceTest`).

- [ ] **Step 6: Rewrite `SessionHost.kt`**

Write `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/session/SessionHost.kt` (whole file):

```kotlin
package io.github.santiquiroz.blindside.shared.session

import android.app.Notification
import android.content.Context
import io.github.santiquiroz.blindside.core.PipelineCounters
import io.github.santiquiroz.blindside.shared.ble.BeltLinkProfile
import io.github.santiquiroz.blindside.shared.ble.BeltListener
import io.github.santiquiroz.blindside.shared.ble.BeltRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

interface SessionHost {
    val appVersion: String
    val notificationId: Int
    val beltRole: BeltRole

    fun ensureNotificationChannel(context: Context)

    fun notification(context: Context, status: String): Notification

    fun launchCompanions(context: Context, scope: CoroutineScope): List<Job> = emptyList()

    suspend fun traitsFor(context: Context, purpose: SessionPurpose): SessionTraits = SessionTraits(BeltLinkProfile(beltRole))

    fun foregroundTypes(source: SessionSource): Int = foregroundTypesFor(source)

    fun beltListener(inner: BeltListener): BeltListener = inner

    fun onScene(counters: PipelineCounters) = Unit
}
```

- [ ] **Step 7: Rewrite `SessionActions.kt` and `SessionCommands.kt`**

Write `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/session/SessionActions.kt` (whole file):

```kotlin
package io.github.santiquiroz.blindside.shared.session

object SessionActions {
    const val ACTION_START = "io.github.santiquiroz.blindside.action.START"
    const val ACTION_STOP = "io.github.santiquiroz.blindside.action.STOP"
    const val ACTION_MARKER = "io.github.santiquiroz.blindside.action.MARKER"
    const val ACTION_RETRY_LINK = "io.github.santiquiroz.blindside.action.RETRY_LINK"
    const val ACTION_TOGGLE_ELIMINATED = "io.github.santiquiroz.blindside.action.TOGGLE_ELIMINATED"
    const val ACTION_OPEN_PAIRING = "io.github.santiquiroz.blindside.action.OPEN_PAIRING"
    const val ACTION_SEND_COMMAND = "io.github.santiquiroz.blindside.action.SEND_COMMAND"
    const val ACTION_REFRESH_INFO = "io.github.santiquiroz.blindside.action.REFRESH_INFO"
    const val EXTRA_SOURCE = "source"
    const val EXTRA_WAKE_LOCK = "wake_lock"
    const val EXTRA_PURPOSE = "purpose"
    const val EXTRA_COMMAND = "command"
    const val EXTRA_RADAR_ID = "radar_id"
}
```

Write `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/session/SessionCommands.kt` (whole file):

```kotlin
package io.github.santiquiroz.blindside.shared.session

import android.app.Service
import android.content.Context
import android.content.Intent
import io.github.santiquiroz.blindside.shared.ble.BeltCommand

class SessionCommands(private val serviceClass: Class<out Service>) {
    fun start(
        context: Context,
        source: SessionSource,
        wakeLock: Boolean = true,
        purpose: SessionPurpose = SessionPurpose.GAME,
    ) {
        val intent = serviceIntent(context, SessionActions.ACTION_START)
            .putExtra(SessionActions.EXTRA_SOURCE, source.name)
            .putExtra(SessionActions.EXTRA_WAKE_LOCK, wakeLock)
            .putExtra(SessionActions.EXTRA_PURPOSE, purpose.name)
        context.startForegroundService(intent)
    }

    fun stop(context: Context) {
        context.startService(stopIntent(context))
    }

    fun marker(context: Context) {
        context.startService(serviceIntent(context, SessionActions.ACTION_MARKER))
    }

    fun retryLink(context: Context) {
        context.startService(serviceIntent(context, SessionActions.ACTION_RETRY_LINK))
    }

    fun openPairing(context: Context) {
        context.startService(serviceIntent(context, SessionActions.ACTION_OPEN_PAIRING))
    }

    fun send(context: Context, command: BeltCommand) {
        val extras = commandExtras(command)
        val intent = serviceIntent(context, SessionActions.ACTION_SEND_COMMAND)
            .putExtra(SessionActions.EXTRA_COMMAND, extras.kind)
            .putExtra(SessionActions.EXTRA_RADAR_ID, extras.radarId)
        context.startService(intent)
    }

    fun refreshInfo(context: Context) {
        context.startService(serviceIntent(context, SessionActions.ACTION_REFRESH_INFO))
    }

    fun toggleEliminatedIntent(context: Context): Intent = serviceIntent(context, SessionActions.ACTION_TOGGLE_ELIMINATED)

    fun stopIntent(context: Context): Intent = serviceIntent(context, SessionActions.ACTION_STOP)

    private fun serviceIntent(context: Context, action: String): Intent =
        Intent(context, serviceClass).setAction(action)
}
```

- [ ] **Step 8: Rewrite `SessionService.kt`**

This is plan 05's service with the purpose transitions, the two new actions, the host's foreground type and a guarded answer to a refused start folded in. Write `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/session/SessionService.kt` (whole file):

```kotlin
package io.github.santiquiroz.blindside.shared.session

import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.santiquiroz.blindside.shared.permissions.PERMISSION_BLUETOOTH_CONNECT
import io.github.santiquiroz.blindside.shared.permissions.PERMISSION_BLUETOOTH_SCAN
import io.github.santiquiroz.blindside.shared.settings.settingsRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val TAG = "BlindsideService"

// RunningSession launches in this scope too, so one failed task is logged instead of killing the whole game.
private val logFailedTask = CoroutineExceptionHandler { _, error -> Log.e(TAG, "session task failed", error) }

abstract class SessionService : Service() {
    protected abstract val host: SessionHost
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logFailedTask)
    private var session: RunningSession? = null
    private var currentSource = SessionSource.BELT
    private var currentPurpose = SessionPurpose.GAME
    private var notificationSync: Job? = null
    private var companions: List<Job> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            SessionActions.ACTION_START -> onStart(sourceOf(intent), intent.getBooleanExtra(SessionActions.EXTRA_WAKE_LOCK, true), purposeOf(intent))
            SessionActions.ACTION_STOP -> onStop()
            SessionActions.ACTION_MARKER -> session?.mark() ?: stopSelf()
            SessionActions.ACTION_RETRY_LINK -> session?.retryLink() ?: stopSelf()
            SessionActions.ACTION_TOGGLE_ELIMINATED -> if (session != null) toggleEliminated(scope, settingsRepository()) else stopSelf()
            SessionActions.ACTION_OPEN_PAIRING -> requestPairingWindow()
            SessionActions.ACTION_SEND_COMMAND -> sendCommand(intent)
            SessionActions.ACTION_REFRESH_INFO -> refreshInfo()
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun sourceOf(intent: Intent): SessionSource = sourceFrom(intent.getStringExtra(SessionActions.EXTRA_SOURCE))

    private fun purposeOf(intent: Intent): SessionPurpose = purposeFrom(intent.getStringExtra(SessionActions.EXTRA_PURPOSE))

    private fun onStart(source: SessionSource, wakeLock: Boolean, purpose: SessionPurpose) {
        when (startTransition(session?.let { currentPurpose }, purpose)) {
            StartTransition.KEEP -> goForeground(currentSource)
            StartTransition.START -> startIfAllowed(source, wakeLock, purpose)
            StartTransition.RESTART -> restartAs(source, wakeLock, purpose)
        }
    }

    private fun startIfAllowed(source: SessionSource, wakeLock: Boolean, purpose: SessionPurpose) {
        val blocker = startBlocker(source, hasBluetoothPermissions(), hasBluetoothAdapter())
        if (blocker == null) beginSession(source, wakeLock, purpose) else rejectStart(blocker)
    }

    private fun beginSession(source: SessionSource, wakeLock: Boolean, purpose: SessionPurpose) {
        currentSource = source
        currentPurpose = purpose
        goForeground(source)
        SessionStore.update { startedState(it, source, purpose) }
        val created = RunningSession(this, source, settingsRepository(), scope, wakeLock, host, purpose)
        session = created
        scope.launch { created.start() }
        notificationSync = scope.launch { syncNotification(source, purpose) }
        companions = host.launchCompanions(this, scope)
    }

    // A purpose change (diagnostic and game) keeps the foreground service: the old link closes before the new one opens.
    private fun restartAs(source: SessionSource, wakeLock: Boolean, purpose: SessionPurpose) {
        val previous = detachSession() ?: return startIfAllowed(source, wakeLock, purpose)
        scope.launch {
            previous.stop()
            startIfAllowed(source, wakeLock, purpose)
        }
    }

    private fun onStop() {
        val current = detachSession() ?: return stopSelf()
        scope.launch {
            current.stop()
            SessionStore.update(::stoppedState)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun detachSession(): RunningSession? {
        val current = session ?: return null
        session = null
        notificationSync?.cancel()
        companions.forEach { it.cancel() }
        companions = emptyList()
        return current
    }

    private fun requestPairingWindow() {
        val queued = session?.openPairingWindow() ?: false
        SessionStore.update { it.copy(phonePairing = pairingAfterRequest(queued)) }
        stopIfIdle()
    }

    private fun sendCommand(intent: Intent) {
        val current = session ?: return stopSelf()
        val command = commandFrom(commandExtrasOf(intent))
        if (command == null || !current.send(command)) Log.w(TAG, "command not sent: ${intent.getStringExtra(SessionActions.EXTRA_COMMAND)}")
    }

    private fun refreshInfo() {
        val current = session ?: return stopSelf()
        if (!current.refreshInfo()) Log.w(TAG, "info refresh skipped: link not streaming")
    }

    private fun commandExtrasOf(intent: Intent): CommandExtras = CommandExtras(
        intent.getStringExtra(SessionActions.EXTRA_COMMAND).orEmpty(),
        intent.getIntExtra(SessionActions.EXTRA_RADAR_ID, NO_RADAR_ID),
    )

    private fun rejectStart(error: StartError) {
        SessionStore.update { blockedState(it, error) }
        answerForegroundStart()
        stopSelf()
    }

    // startForegroundService() must be answered with startForeground(); a host whose only type needs Bluetooth may fail here, which is logged.
    private fun answerForegroundStart() {
        runCatching { goForeground(SessionSource.DEMO) }.onFailure { Log.w(TAG, "could not answer the foreground start", it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun stopIfIdle() {
        if (session == null) stopSelf()
    }

    private fun goForeground(source: SessionSource) {
        host.ensureNotificationChannel(this)
        val notification = host.notification(this, ongoingStatus(eliminated = false, source = source, purpose = currentPurpose))
        startForeground(host.notificationId, notification, host.foregroundTypes(source))
    }

    private suspend fun syncNotification(source: SessionSource, purpose: SessionPurpose) {
        SessionStore.state.map { it.eliminated }.distinctUntilChanged().collect { eliminated ->
            val notification = host.notification(this, ongoingStatus(eliminated, source, purpose))
            getSystemService(NotificationManager::class.java).notify(host.notificationId, notification)
        }
    }

    private fun hasBluetoothPermissions(): Boolean =
        listOf(PERMISSION_BLUETOOTH_SCAN, PERMISSION_BLUETOOTH_CONNECT).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun hasBluetoothAdapter(): Boolean = getSystemService(BluetoothManager::class.java)?.adapter != null
}
```

For the watch nothing changes:
- its purpose is always `GAME`, so `startTransition` gives `KEEP` exactly where plan 05 called `goForeground(currentSource)`;
- `foregroundTypes` defaults to `foregroundTypesFor`;
- the refused-start answer still goes foreground as `DEMO` (health type).

- [ ] **Step 9: Rewrite `RunningSession.kt`**

This is plan 05's runtime with the traits folded in. Write `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/session/RunningSession.kt` (whole file):

```kotlin
package io.github.santiquiroz.blindside.shared.session

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.santiquiroz.blindside.core.RadarPipeline
import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.shared.ble.BeltCommand
import io.github.santiquiroz.blindside.shared.ble.BeltLink
import io.github.santiquiroz.blindside.shared.ble.BeltLinkProfile
import io.github.santiquiroz.blindside.shared.demo.DemoSource
import io.github.santiquiroz.blindside.shared.demo.demoPackets
import io.github.santiquiroz.blindside.shared.haptics.HapticPlayer
import io.github.santiquiroz.blindside.shared.haptics.dndMaySilenceNow
import io.github.santiquiroz.blindside.shared.haptics.millisUntil
import io.github.santiquiroz.blindside.shared.permissions.PERMISSION_ACTIVITY_RECOGNITION
import io.github.santiquiroz.blindside.shared.recording.InfoHeaderSink
import io.github.santiquiroz.blindside.shared.recording.NoOpRecordSink
import io.github.santiquiroz.blindside.shared.recording.RecordSink
import io.github.santiquiroz.blindside.shared.recording.RecordingMeta
import io.github.santiquiroz.blindside.shared.recording.SessionClockStamp
import io.github.santiquiroz.blindside.shared.recording.headerJson
import io.github.santiquiroz.blindside.shared.recording.openRecordingSink
import io.github.santiquiroz.blindside.shared.recording.recordingFileName
import io.github.santiquiroz.blindside.shared.recording.recordingMeta
import io.github.santiquiroz.blindside.shared.recording.recordingsDir
import io.github.santiquiroz.blindside.shared.sensors.DeviceSensors
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import io.github.santiquiroz.blindside.shared.settings.ScreenMode
import io.github.santiquiroz.blindside.shared.settings.SettingsRepository
import io.github.santiquiroz.blindside.shared.settings.forNewSession
import io.github.santiquiroz.blindside.shared.settings.toPipelineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
    private val host: SessionHost,
    private val purpose: SessionPurpose = SessionPurpose.GAME,
) {
    private val pipelineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val inputs = Channel<SessionInput>(Channel.UNLIMITED)
    private val wakeLock = SessionWakeLock(context)
    private val sensors = DeviceSensors(context.getSystemService(SensorManager::class.java), SensorInputs(inputs))
    private val loops = mutableListOf<Job>()
    private var consumer: Job? = null
    private var belt: BeltLink? = null
    private var traits = SessionTraits(BeltLinkProfile(host.beltRole))

    @Volatile
    private var screenMode = ScreenMode.SIGILO

    suspend fun start() {
        settings.update { it.forNewSession() }
        val initial = settings.current()
        traits = host.traitsFor(context, purpose)
        flagDndRisk(initial)
        consumer = scope.launch(pipelineDispatcher) { consume(createEngine(initial)) }
        holdWakeLock()
        launchLoops()
        if (traits.readsDeviceSensors) startSensors()
        startSource(initial)
    }

    fun mark() {
        inputs.trySend(SessionInput.Marker(nowNanos()))
    }

    fun retryLink() {
        belt?.retry()
    }

    fun openPairingWindow(): Boolean = belt?.openPairingWindow() ?: false

    fun send(command: BeltCommand): Boolean = belt?.send(command) ?: false

    fun refreshInfo(): Boolean = belt?.refreshInfo() ?: false

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
        val player = HapticPlayer.create(context, initial.vibrationUsage)
        val pipeline = RadarPipeline(toPipelineConfig(initial))
        return SessionEngine(
            pipeline = RadarPipelineAdapter(pipeline),
            records = openRecorder(initial, startNanos, player),
            haptics = hapticsFor(traits, player),
            scenes = SceneSink { scene -> publish(scene, pipeline) },
            clock = NanoClock(::nowNanos),
            startNanos = startNanos,
            deferred = DeferredPlayback(::playLater),
            onError = { error -> Log.w(TAG, "session input failed", error) },
        )
    }

    // SceneSink runs on the pipeline thread inside the tick, so reading the counters cannot race a packet.
    private fun publish(scene: RadarScene, pipeline: RadarPipeline) {
        SessionStore.update { it.copy(scene = scene) }
        host.onScene(pipeline.counters())
    }

    private fun playLater(alert: ContactAlert, atNanos: Long) {
        scope.launch {
            delay(millisUntil(atNanos, nowNanos()))
            inputs.trySend(SessionInput.PlayDeferred(alert, atNanos))
        }
    }

    private fun openRecorder(initial: AppSettings, startNanos: Long, haptics: HapticPlayer): RecordSink {
        if (!traits.records) return NoOpRecordSink
        val stamp = SessionClockStamp(epochMs = System.currentTimeMillis(), elapsedNanos = startNanos)
        val name = recordingFileName(stamp.epochMs, ZoneId.systemDefault(), recordingTagFor(traits, source))
        val meta = recordingMeta(
            initial, source.name, stamp, Build.MODEL, host.appVersion,
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
            val period = pacedPeriodMs(traits.framePeriodMs, SessionStore.radarVisible.value, screenMode, SessionStore.ambient.value)
            if (period != null) inputs.trySend(SessionInput.Tick(nowNanos()))
            delay(period ?: IDLE_TICK_POLL_MS)
        }
    }

    private suspend fun forwardMode() {
        modeChanges().collect { (eliminated, mode) ->
            screenMode = mode
            inputs.trySend(SessionInput.ModeChanged(eliminated, mode, nowNanos()))
        }
    }

    private fun modeChanges(): Flow<Pair<Boolean, ScreenMode>> =
        combine(SessionStore.state.map { it.eliminated }, settings.settings.map { it.screenMode }) { eliminated, mode ->
            eliminated to mode
        }.distinctUntilChanged()

    private fun flagDndRisk(initial: AppSettings) {
        val atRisk = dndMaySilenceNow(context, initial.vibrationUsage)
        SessionStore.update { it.copy(dndMaySilenceAlerts = atRisk) }
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
        val listener = host.beltListener(BeltInputs(inputs))
        belt = BeltLink(context, listener, traits.link, onBeltFound = ::rememberBelt).also { it.start(savedAddress) }
    }

    private fun rememberBelt(address: String) {
        scope.launch { settings.update { it.copy(beltAddress = address) } }
    }
}
```

With `WearSessionHost`'s default traits the watch gets the same session:
- `records`, `readsDeviceSensors` and `vibrates` are all true;
- `framePeriodMs = null` makes `pacedPeriodMs` return `scenePeriodMs`;
- the tag `null` keeps `source.name`;
- `beltListener` is `BeltInputs` itself;
- `onScene` does nothing.

- [ ] **Step 10: Run the watch gate and the test-count one-liner, then confirm the watch did not move**

Expected: `BUILD SUCCESSFUL`; `android-shared 280` (269 + 11), `wear-app 46`.

```bash
cd /c/personal/blindside/watch
grep -rn "override fun traitsFor\|override fun foregroundTypes\|override fun beltListener\|override fun onScene" wear-app/src || echo "the watch uses every default"
grep -n "foregroundServiceType" wear-app/src/main/AndroidManifest.xml
```

Expected: `the watch uses every default`, and the watch service still declares `connectedDevice|health`.

- [ ] **Step 11: Commit**

```bash
cd /c/personal/blindside
git add watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/session \
  watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/session
git commit -m "feat: la sesión compartida acepta propósito, rasgos por app y comandos manuales del cinturón"
```

---

### Task 6: Phone session host (foreground service, phone role, diagnostic link, preferences)

**Files:**
- Create: `phone/session/PhoneSessionModels.kt`, `phone/session/PhoneStore.kt`, `phone/session/DiagnosticBeltListener.kt`, `phone/session/PhoneNotification.kt`, `phone/session/PhoneSessionHost.kt`
- Create: `phone/settings/LinkSupport.kt`, `phone/settings/PhonePrefs.kt`
- Modify: `watch/phone-app/src/main/AndroidManifest.xml`
- Test: `phoneTest/session/PhoneSessionModelsTest.kt`, `phoneTest/session/DiagnosticBeltListenerTest.kt`, `phoneTest/settings/PhonePrefsTest.kt`, `phoneTest/settings/LinkSupportTest.kt`

**Interfaces:**
- Consumes:
  - Task 5: `SessionHost` (and its hooks), `SessionService`, `SessionCommands`, `SessionTraits`, `SessionPurpose`, `SessionSource`.
  - Task 3: `BeltLinkProfile`, `BeltRole`.
  - Plan 05: `BeltListener`, `enumOrDefault`.
  - radar-core: `PipelineCounters`, `MiniJson`.
- Produces:
  - Package `io.github.santiquiroz.blindside.phone.session`:
    - `const val PHONE_RECORDING_TAG = "PHONE"`, `PHONE_FRAME_MS = 33L`, `PHONE_FOREGROUND_TYPES`;
    - `fun phoneTraits(purpose: SessionPurpose, vibrate: Boolean): SessionTraits`;
    - `data class PhoneDiagnostics(val infoJson: String? = null, val rssiDbm: Int? = null, val counters: PipelineCounters? = null)`, `object PhoneStore { val state: StateFlow<PhoneDiagnostics>; fun update(transform) }`;
    - `class DiagnosticBeltListener(inner: BeltListener) : BeltListener`;
    - `object PhoneNotification`, `object PhoneSessionHost : SessionHost`, `class PhoneSessionService : SessionService()`, `val PhoneSessionCommands: SessionCommands`, `fun startPhoneSession(context, purpose)`.
  - Package `io.github.santiquiroz.blindside.phone.settings`:
    - `enum class LinkSupport { UNKNOWN, SINGLE_LINK, DUAL_LINK }`, `fun linkSupportOf(infoJson: String): LinkSupport`, `fun linkSupportAfterInfo(previous: LinkSupport, infoJson: String?): LinkSupport`, `fun firmwareAtLeast(version: String?, major: Int, minor: Int): Boolean`;
    - `data class PhonePrefs(val vibrate: Boolean = false, val linkSupport: LinkSupport = LinkSupport.UNKNOWN)`;
    - `class PhonePrefsRepository(store, onWriteFailed)` with `prefs: Flow<PhonePrefs>`, `suspend current()`, `suspend update(transform): Boolean`;
    - `fun Context.phonePrefsRepository()`, `fun phonePrefsFrom(prefs: Preferences)`, `fun writePhonePrefs(prefs: MutablePreferences, value: PhonePrefs)`.

- [ ] **Step 1: Write the failing tests**

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/session/PhoneSessionModelsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.session

import io.github.santiquiroz.blindside.shared.ble.BeltLinkProfile
import io.github.santiquiroz.blindside.shared.ble.BeltRole
import io.github.santiquiroz.blindside.shared.session.SessionPurpose
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhoneSessionModelsTest {
    @Test
    fun `a radar game announces the phone, activates its session and records as the phone`() {
        val traits = phoneTraits(SessionPurpose.GAME, vibrate = false)
        assertEquals(BeltLinkProfile(BeltRole.PHONE, activatesSession = true), traits.link)
        assertTrue(traits.records)
        assertEquals(PHONE_RECORDING_TAG, traits.recordingTag)
    }

    @Test
    fun `a diagnostic link announces the phone without a session, records nothing and never vibrates`() {
        val traits = phoneTraits(SessionPurpose.DIAGNOSTIC, vibrate = true)
        assertEquals(BeltLinkProfile(BeltRole.PHONE, activatesSession = false), traits.link)
        assertFalse(traits.records)
        assertFalse(traits.vibrates)
    }

    @Test
    fun `the phone never feeds its own sensors to the pipeline`() {
        SessionPurpose.entries.forEach { assertFalse(phoneTraits(it, vibrate = true).readsDeviceSensors) }
    }

    @Test
    fun `the phone vibrates only when its setting is on`() {
        assertFalse(phoneTraits(SessionPurpose.GAME, vibrate = false).vibrates)
        assertTrue(phoneTraits(SessionPurpose.GAME, vibrate = true).vibrates)
    }

    @Test
    fun `the phone draws about 30 scenes per second while a radar view is on screen`() {
        assertEquals(PHONE_FRAME_MS, phoneTraits(SessionPurpose.GAME, vibrate = false).framePeriodMs)
    }

    @Test
    fun `the phone service runs only as a connected device`() {
        assertEquals(0x10, PHONE_FOREGROUND_TYPES)
    }
}
```

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/session/DiagnosticBeltListenerTest.kt`. The test object implements every `BeltListener` member, including plan 05's `onPairingWindow`:

```kotlin
package io.github.santiquiroz.blindside.phone.session

import io.github.santiquiroz.blindside.shared.ble.BeltListener
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiagnosticBeltListenerTest {
    private val calls = mutableListOf<String>()
    private val inner = object : BeltListener {
        override fun onPacket(bytes: ByteArray, arrivalNanos: Long) { calls += "packet" }
        override fun onBeltInfo(json: String, nowNanos: Long) { calls += "info:$json" }
        override fun onRssi(dbm: Int, nowNanos: Long) { calls += "rssi:$dbm" }
        override fun onLinkChanged(connected: Boolean, nowNanos: Long) { calls += "link:$connected" }
        override fun onStatus(status: BleStatus) { calls += "status:$status" }
        override fun onPairingWindow(opened: Boolean) { calls += "pairing:$opened" }
    }

    @Test
    fun `info and signal reach the session and the diagnostic store, everything else passes through`() {
        PhoneStore.update { PhoneDiagnostics() }
        val listener = DiagnosticBeltListener(inner)

        listener.onBeltInfo("""{"fw":"0.1.0"}""", 1L)
        listener.onRssi(-58, 2L)
        listener.onPacket(byteArrayOf(1), 3L)
        listener.onStatus(BleStatus.STREAMING)
        listener.onPairingWindow(true)

        assertEquals(listOf("info:{\"fw\":\"0.1.0\"}", "rssi:-58", "packet", "status:STREAMING", "pairing:true"), calls)
        assertEquals("""{"fw":"0.1.0"}""", PhoneStore.state.value.infoJson)
        assertEquals(-58, PhoneStore.state.value.rssiDbm)
    }
}
```

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/settings/LinkSupportTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LinkSupportTest {
    @Test
    fun `an info with conns comes from the dual-link firmware`() {
        assertEquals(LinkSupport.DUAL_LINK, linkSupportOf("""{"fw":"0.2.0","conns":[]}"""))
        assertEquals(LinkSupport.DUAL_LINK, linkSupportOf("""{"conns":[{"role":"watch"}]}"""))
    }

    @Test
    fun `version 0_2 or later is dual-link even without conns`() {
        assertEquals(LinkSupport.DUAL_LINK, linkSupportOf("""{"fw":"0.2.0"}"""))
        assertEquals(LinkSupport.DUAL_LINK, linkSupportOf("""{"fw":"1.0.3"}"""))
    }

    @Test
    fun `the MVP firmware serves one link`() {
        assertEquals(LinkSupport.SINGLE_LINK, linkSupportOf("""{"fw":"0.1.0","conn":{"interval_ms":45.0}}"""))
    }

    @Test
    fun `an unreadable info tells nothing`() {
        assertEquals(LinkSupport.UNKNOWN, linkSupportOf("garbage"))
        assertEquals(LinkSupport.UNKNOWN, linkSupportOf("""{"proto":1}"""))
    }

    @Test
    fun `a new info only replaces what was known when it says something`() {
        assertEquals(LinkSupport.DUAL_LINK, linkSupportAfterInfo(LinkSupport.DUAL_LINK, "garbage"))
        assertEquals(LinkSupport.SINGLE_LINK, linkSupportAfterInfo(LinkSupport.DUAL_LINK, """{"fw":"0.1.0"}"""))
        assertEquals(LinkSupport.UNKNOWN, linkSupportAfterInfo(LinkSupport.UNKNOWN, null))
    }

    @Test
    fun `versions compare by number, not by text`() {
        assertTrue(firmwareAtLeast("0.10.0", 0, 2))
        assertFalse(firmwareAtLeast("0.1.9", 0, 2))
        assertFalse(firmwareAtLeast("v2", 0, 2))
        assertFalse(firmwareAtLeast(null, 0, 2))
    }
}
```

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/settings/PhonePrefsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class PhonePrefsTest {
    @Test
    fun `vibration is off and the belt firmware unknown by default`() {
        assertEquals(PhonePrefs(vibrate = false, linkSupport = LinkSupport.UNKNOWN), phonePrefsFrom(emptyPreferences()))
    }

    @Test
    fun `prefs survive a write and a read`() {
        val prefs = mutablePreferencesOf()
        writePhonePrefs(prefs, PhonePrefs(vibrate = true, linkSupport = LinkSupport.DUAL_LINK))
        assertEquals(PhonePrefs(vibrate = true, linkSupport = LinkSupport.DUAL_LINK), phonePrefsFrom(prefs))
    }

    @Test
    fun `an unknown stored firmware support reads as unknown`() {
        val prefs = mutablePreferencesOf(stringPreferencesKey("belt_link_support") to "TRIPLE_LINK")
        assertEquals(LinkSupport.UNKNOWN, phonePrefsFrom(prefs).linkSupport)
    }

    @Test
    fun `updates are visible to the next read`(@TempDir dir: File) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "phone.preferences_pb") }
        val repository = PhonePrefsRepository(store, onWriteFailed = {})
        val read = runBlocking {
            repository.update { it.copy(vibrate = true) }
            repository.current()
        }
        scope.cancel()
        assertEquals(PhonePrefs(vibrate = true), read)
    }

    @Test
    fun `a failed write is reported instead of thrown`() {
        val failures = mutableListOf<IOException>()
        val repository = PhonePrefsRepository(DiskFullStore(), onWriteFailed = { failures += it })
        assertFalse(runBlocking { repository.update { it.copy(vibrate = true) } })
        assertEquals(listOf("disk full"), failures.map { it.message })
    }

    private class DiskFullStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("disk full")
    }
}
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.phone.session.*" --tests "io.github.santiquiroz.blindside.phone.settings.*" --console=plain`
Expected: FAIL with `Unresolved reference 'phoneTraits'`.

- [ ] **Step 3: Write the pure phone session models and the diagnostics store**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/session/PhoneSessionModels.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.session

import android.content.pm.ServiceInfo
import io.github.santiquiroz.blindside.shared.ble.BeltLinkProfile
import io.github.santiquiroz.blindside.shared.ble.BeltRole
import io.github.santiquiroz.blindside.shared.session.SessionPurpose
import io.github.santiquiroz.blindside.shared.session.SessionTraits

const val PHONE_RECORDING_TAG = "PHONE"
const val PHONE_FRAME_MS = 33L
const val PHONE_FOREGROUND_TYPES = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

// Spec §8 leaves the phone's own IMU out, so the pipeline gets belt data only; a diagnostic link records nothing and marks no session.
fun phoneTraits(purpose: SessionPurpose, vibrate: Boolean): SessionTraits {
    val game = purpose == SessionPurpose.GAME
    return SessionTraits(
        link = BeltLinkProfile(BeltRole.PHONE, activatesSession = game),
        records = game,
        readsDeviceSensors = false,
        vibrates = game && vibrate,
        framePeriodMs = PHONE_FRAME_MS,
        recordingTag = PHONE_RECORDING_TAG,
    )
}
```

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/session/PhoneStore.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.session

import io.github.santiquiroz.blindside.core.PipelineCounters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PhoneDiagnostics(
    val infoJson: String? = null,
    val rssiDbm: Int? = null,
    val counters: PipelineCounters? = null,
)

// The last info outlives its link so the Belt tab is never blank between connections.
object PhoneStore {
    private val mutableState = MutableStateFlow(PhoneDiagnostics())

    val state: StateFlow<PhoneDiagnostics> = mutableState.asStateFlow()

    fun update(transform: (PhoneDiagnostics) -> PhoneDiagnostics) = mutableState.update(transform)
}
```

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/session/DiagnosticBeltListener.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.session

import io.github.santiquiroz.blindside.shared.ble.BeltListener

class DiagnosticBeltListener(private val inner: BeltListener) : BeltListener by inner {
    override fun onBeltInfo(json: String, nowNanos: Long) {
        PhoneStore.update { it.copy(infoJson = json) }
        inner.onBeltInfo(json, nowNanos)
    }

    override fun onRssi(dbm: Int, nowNanos: Long) {
        PhoneStore.update { it.copy(rssiDbm = dbm) }
        inner.onRssi(dbm, nowNanos)
    }
}
```

- [ ] **Step 4: Write the firmware support rule and the phone preferences**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/settings/LinkSupport.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.settings

import io.github.santiquiroz.blindside.core.protocol.MiniJson

enum class LinkSupport { UNKNOWN, SINGLE_LINK, DUAL_LINK }

private const val DUAL_LINK_MAJOR = 0
private const val DUAL_LINK_MINOR = 2

// Firmware 0.2.0 adds "conns" to info (plan 04 Task 6); 0.1.0 holds one link and one bond, so the phone would take the watch's place.
fun linkSupportOf(infoJson: String): LinkSupport {
    val root = MiniJson.parseOrNull(infoJson) as? Map<*, *> ?: return LinkSupport.UNKNOWN
    val firmware = root["fw"] as? String
    return when {
        root["conns"] is List<*> || firmwareAtLeast(firmware, DUAL_LINK_MAJOR, DUAL_LINK_MINOR) -> LinkSupport.DUAL_LINK
        firmware != null -> LinkSupport.SINGLE_LINK
        else -> LinkSupport.UNKNOWN
    }
}

// A garbled read says nothing and must not forget a firmware already seen.
fun linkSupportAfterInfo(previous: LinkSupport, infoJson: String?): LinkSupport =
    infoJson?.let(::linkSupportOf)?.takeIf { it != LinkSupport.UNKNOWN } ?: previous

fun firmwareAtLeast(version: String?, major: Int, minor: Int): Boolean {
    val numbers = version?.split('.')?.take(2)?.map { it.toIntOrNull() ?: return false } ?: return false
    if (numbers.size < 2) return false
    return compareValuesBy(numbers, listOf(major, minor), { it[0] }, { it[1] }) >= 0
}
```

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/settings/PhonePrefs.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.santiquiroz.blindside.shared.settings.enumOrDefault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private const val TAG = "PhonePrefs"

data class PhonePrefs(val vibrate: Boolean = false, val linkSupport: LinkSupport = LinkSupport.UNKNOWN)

private object PhoneKeys {
    val VIBRATE = booleanPreferencesKey("phone_vibrate")
    val LINK_SUPPORT = stringPreferencesKey("belt_link_support")
}

private val Context.phonePrefsStore: DataStore<Preferences> by preferencesDataStore(name = "blindside_phone")

fun Context.phonePrefsRepository(): PhonePrefsRepository =
    PhonePrefsRepository(applicationContext.phonePrefsStore, onWriteFailed = { Log.w(TAG, "phone prefs write failed", it) })

fun phonePrefsFrom(prefs: Preferences): PhonePrefs = PhonePrefs(
    vibrate = prefs[PhoneKeys.VIBRATE] ?: false,
    linkSupport = enumOrDefault(prefs[PhoneKeys.LINK_SUPPORT], LinkSupport.UNKNOWN),
)

fun writePhonePrefs(prefs: MutablePreferences, value: PhonePrefs) {
    prefs[PhoneKeys.VIBRATE] = value.vibrate
    prefs[PhoneKeys.LINK_SUPPORT] = value.linkSupport.name
}

class PhonePrefsRepository(
    private val store: DataStore<Preferences>,
    private val onWriteFailed: (IOException) -> Unit,
) {
    val prefs: Flow<PhonePrefs> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map(::phonePrefsFrom)

    suspend fun current(): PhonePrefs = prefs.first()

    suspend fun update(transform: (PhonePrefs) -> PhonePrefs): Boolean = try {
        store.edit { writePhonePrefs(it, transform(phonePrefsFrom(it))) }
        true
    } catch (error: IOException) {
        onWriteFailed(error)
        false
    }
}
```

- [ ] **Step 5: Run the pure tests**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.phone.session.*" --tests "io.github.santiquiroz.blindside.phone.settings.*" --console=plain`
Expected: PASS (6 + 1 + 6 + 5 tests).

- [ ] **Step 6: Write the notification, the host and the service**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/session/PhoneNotification.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.santiquiroz.blindside.phone.MainActivity
import io.github.santiquiroz.blindside.phone.R

object PhoneNotification {
    const val NOTIFICATION_ID = 11
    private const val CHANNEL_ID = "blindside_phone_session"
    private const val REQUEST_TOGGLE = 1
    private const val REQUEST_STOP = 2

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Radar en curso", NotificationManager.IMPORTANCE_LOW)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(context: Context, status: String): Notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_radar)
        .setContentTitle("Blindside")
        .setContentText(status)
        .setOngoing(true)
        .setContentIntent(openAppIntent(context))
        .addAction(R.drawable.ic_radar, "Eliminado", servicePendingIntent(context, REQUEST_TOGGLE, PhoneSessionCommands.toggleEliminatedIntent(context)))
        .addAction(R.drawable.ic_radar, "Detener", servicePendingIntent(context, REQUEST_STOP, PhoneSessionCommands.stopIntent(context)))
        .build()

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun servicePendingIntent(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getService(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE)
}
```

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/session/PhoneSessionHost.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.session

import android.app.Notification
import android.content.Context
import io.github.santiquiroz.blindside.core.PipelineCounters
import io.github.santiquiroz.blindside.phone.BuildConfig
import io.github.santiquiroz.blindside.phone.settings.phonePrefsRepository
import io.github.santiquiroz.blindside.shared.ble.BeltListener
import io.github.santiquiroz.blindside.shared.ble.BeltRole
import io.github.santiquiroz.blindside.shared.session.SessionCommands
import io.github.santiquiroz.blindside.shared.session.SessionHost
import io.github.santiquiroz.blindside.shared.session.SessionPurpose
import io.github.santiquiroz.blindside.shared.session.SessionService
import io.github.santiquiroz.blindside.shared.session.SessionSource
import io.github.santiquiroz.blindside.shared.session.SessionTraits

object PhoneSessionHost : SessionHost {
    override val appVersion: String = BuildConfig.VERSION_NAME
    override val notificationId: Int = PhoneNotification.NOTIFICATION_ID
    override val beltRole: BeltRole = BeltRole.PHONE

    override fun ensureNotificationChannel(context: Context) = PhoneNotification.ensureChannel(context)

    override fun notification(context: Context, status: String): Notification = PhoneNotification.build(context, status)

    override suspend fun traitsFor(context: Context, purpose: SessionPurpose): SessionTraits =
        phoneTraits(purpose, context.phonePrefsRepository().current().vibrate)

    override fun foregroundTypes(source: SessionSource): Int = PHONE_FOREGROUND_TYPES

    override fun beltListener(inner: BeltListener): BeltListener = DiagnosticBeltListener(inner)

    override fun onScene(counters: PipelineCounters) {
        PhoneStore.update { it.copy(counters = counters) }
    }
}

class PhoneSessionService : SessionService() {
    override val host: SessionHost = PhoneSessionHost
}

val PhoneSessionCommands = SessionCommands(PhoneSessionService::class.java)

// The phone holds no wake lock: it shows the radar, it does not buzz the player in the dark like the watch.
fun startPhoneSession(context: Context, purpose: SessionPurpose) =
    PhoneSessionCommands.start(context, SessionSource.BELT, wakeLock = false, purpose = purpose)
```

- [ ] **Step 7: Register the service**

In `watch/phone-app/src/main/AndroidManifest.xml`, add inside `<application>` after the `</activity>` line:

```xml
        <service
            android:name=".session.PhoneSessionService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />
```

- [ ] **Step 8: Run every phone test and build**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest :phone-app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`, 23 phone tests pass (5 + 18).

- [ ] **Step 9: Commit**

```bash
cd /c/personal/blindside
git add watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/session \
  watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/settings \
  watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/session \
  watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/settings \
  watch/phone-app/src/main/AndroidManifest.xml
git commit -m "feat: sesión del celular sobre el servicio compartido con rol celular, enlace de diagnóstico y vibración opcional"
```

---

### Task 7: Radar tab (shared canvas, contact list, status, eliminated button)

**Files:**
- Create: `phone/ui/common/Formats.kt`
- Create: `phone/ui/common/Widgets.kt`
- Create: `phone/ui/common/ScreenEffects.kt`
- Create: `phone/ui/PhoneUiModels.kt`
- Create: `phone/ui/radar/RadarView.kt`
- Create: `phone/ui/radar/ContactRows.kt`
- Create: `phone/ui/radar/RadarStatus.kt`
- Create: `phone/ui/radar/RadarTab.kt`
- Test: `phoneTest/ui/common/FormatsTest.kt`, `phoneTest/ui/radar/ContactRowsTest.kt`, `phoneTest/ui/radar/RadarStatusTest.kt`

**Interfaces:**
- Consumes:
  - Task 2: colour aliases and `NumberStyle`.
  - Task 5: `SessionPurpose`, `SessionUiState.purpose`.
  - Task 6: `PhoneDiagnostics`, `PhonePrefs`, `LinkSupport`.
  - Plan 05 radar: `toDrawModel`, `showContacts`, `drawRadar`, `TACTICAL_RADAR_COLORS`, `blipStyle`, `contactTone`, `ContactTone`, `BlipStyle`, `PointPx`, `statusItems`, `warningLabel`, `eliminatedActionLabel`.
  - Plan 05 elsewhere: `BlindsideColors`, `needsRetry`, `SessionStore`, `SessionUiState`, `StartError`, `BleStatus`, `WatchStatus`.
- Produces:
  - Package `…phone.ui.common`:
    - formats `formatDecimal(Double)`, `formatMeters(Double)`, `formatSeconds(ms: Long)`, `formatPercent(fraction: Double)`, `formatClock(ms: Long)`, `formatUptime(seconds: Long)`, `formatBytes(Long)`, `formatAgo(ms: Long)`; `val MIN_TOUCH: Dp`;
    - composables `SectionCard(title, modifier, content)`, `EmptyState(message, actionLabel, icon, onAction, modifier)`, `StatusChip(label, ok)`, `NumberText(text, modifier, style, color)`, `SwitchRow(label, checked, onToggle)`, `KeepScreenOn()`, `ReportSceneVisibility(wanted: Boolean)`, `rememberNowMs(): State<Long>`.
  - Package `…phone.ui`: `data class PhoneUiState(session, phone: PhoneDiagnostics, settings, prefs, watchStatus, bluetoothBlocked)` and `data class PhoneActions(startRadar, startDiagnostic, stop, toggleEliminated, retryLink, sendCommand, refreshInfo, updateSettings, updatePrefs, openAppSettings)`.
  - Package `…phone.ui.radar`:
    - composables `RadarView(scene, modifier)`, `ConfidenceGlyph(style)`, `SensorChipRow(chips)`, `RadarTab(state: PhoneUiState, actions: PhoneActions)`;
    - contact rows `data class ContactRow`, `contactRows(scene)`, `contactRow(blip)`, `formatBearing(deg)`, `confidenceLabel(confidence, outOfView)`;
    - status `data class SensorChip(label, ok)`, `START_RADAR_LABEL`, `BLUETOOTH_DENIED_TEXT`, `UPDATE_FIRMWARE_TEXT`, `isLiveRadar(session)`, `sensorChips(scene)`, `phoneLinkLabel(status)`, `startErrorText(error)`, `radarBanner(session)`, `recordingLine(session)`, `idleRadarHint(beltPaired, purpose, linkSupport)`, `radarDescription(scene)`.

- [ ] **Step 1: Write the failing tests**

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/ui/common/FormatsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FormatsTest {
    @Test
    fun `decimals use a comma and one digit`() {
        assertEquals("2,4", formatDecimal(2.44))
        assertEquals("3,0 m", formatMeters(3.0))
        assertEquals("1,2 s", formatSeconds(1_200))
        assertEquals("0,0 s", formatSeconds(0))
    }

    @Test
    fun `percentages are whole numbers with a spaced sign`() {
        assertEquals("25 %", formatPercent(0.25))
        assertEquals("0 %", formatPercent(0.0))
    }

    @Test
    fun `clock shows hours only when there are some`() {
        assertEquals("01:05", formatClock(65_000))
        assertEquals("1:02:05", formatClock(3_725_000))
        assertEquals("00:00", formatClock(-5))
    }

    @Test
    fun `uptime picks the two largest units`() {
        assertEquals("42 s", formatUptime(42))
        assertEquals("3 min 05 s", formatUptime(185))
        assertEquals("1 h 02 min", formatUptime(3_720))
    }

    @Test
    fun `sizes use binary units`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1,5 KB", formatBytes(1_536))
        assertEquals("5,0 MB", formatBytes(5 * 1_048_576L))
    }

    @Test
    fun `ages read as time ago`() {
        assertEquals("hace 3 s", formatAgo(3_000))
        assertEquals("hace 0 s", formatAgo(-50))
    }
}
```

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/ui/radar/ContactRowsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.radar

import io.github.santiquiroz.blindside.core.scene.Blip
import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.shared.radar.BlipStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ContactRowsTest {
    private fun blip(id: Int, rangeM: Double, bearingDeg: Double = 0.0, confidence: Confidence = Confidence.BOTH, ageMs: Long = 0, outOfView: Boolean = false) =
        Blip(id, bearingDeg, rangeM, confidence, ageMs, outOfView)

    private fun scene(blips: List<Blip>, linkUp: Boolean = true, eliminated: Boolean = false) =
        RadarScene(blips, emptyList(), linkUp, emptyList(), emptyList(), MotionState.STILL, emptySet(), eliminated)

    @Test
    fun `contacts are listed nearest first`() {
        val rows = contactRows(scene(listOf(blip(1, 4.0), blip(2, 1.5), blip(3, 2.5))))
        assertEquals(listOf("#2", "#3", "#1"), rows.map { it.id })
    }

    @Test
    fun `a row shows distance, bearing, confidence and age in readable units`() {
        val row = contactRow(blip(4, 2.44, bearingDeg = -44.6, confidence = Confidence.SINGLE, ageMs = 1_200))
        assertEquals(ContactRow("#4", "2,4 m", "-45°", "Un radar", "1,2 s", BlipStyle.OUTLINE), row)
    }

    @Test
    fun `confidence is told by shape as well as by words`() {
        assertEquals("Ambos radares" to BlipStyle.FILLED, contactRow(blip(1, 1.0)).let { it.confidence to it.style })
        assertEquals("Perdido" to BlipStyle.DASHED, contactRow(blip(1, 1.0, confidence = Confidence.COASTING)).let { it.confidence to it.style })
    }

    @Test
    fun `a contact out of view says so`() {
        assertEquals("Fuera de vista", contactRow(blip(1, 1.0, outOfView = true)).confidence)
    }

    @Test
    fun `bearings are signed and zero has no sign`() {
        assertEquals("+30°", formatBearing(30.2))
        assertEquals("0°", formatBearing(-0.4))
        assertEquals("0°", formatBearing(0.4))
    }

    @Test
    fun `no contacts are listed without a link, when eliminated or without a scene`() {
        val blips = listOf(blip(1, 1.0))
        assertEquals(emptyList<ContactRow>(), contactRows(scene(blips, linkUp = false)))
        assertEquals(emptyList<ContactRow>(), contactRows(scene(blips, eliminated = true)))
        assertEquals(emptyList<ContactRow>(), contactRows(null))
    }
}
```

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/ui/radar/RadarStatusTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.radar

import io.github.santiquiroz.blindside.core.scene.Blip
import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import io.github.santiquiroz.blindside.phone.settings.LinkSupport
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.session.SessionPurpose
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.session.StartError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RadarStatusTest {
    private fun scene(linkUp: Boolean = true, eliminated: Boolean = false, blips: List<Blip> = emptyList()) = RadarScene(
        blips = blips,
        coverage = emptyList(),
        linkUp = linkUp,
        radars = listOf(SensorStatus(0, true), SensorStatus(1, false)),
        imus = listOf(SensorStatus(0, true), SensorStatus(1, true)),
        motion = MotionState.STILL,
        warnings = emptySet(),
        eliminated = eliminated,
    )

    @Test
    fun `the live radar shows only for a running game`() {
        assertTrue(isLiveRadar(SessionUiState(running = true, purpose = SessionPurpose.GAME)))
        assertFalse(isLiveRadar(SessionUiState(running = true, purpose = SessionPurpose.DIAGNOSTIC)))
        assertFalse(isLiveRadar(SessionUiState(purpose = SessionPurpose.GAME)))
    }

    @Test
    fun `sensor chips report the link, both radars and both imus`() {
        val expected = listOf(
            SensorChip("Enlace", true),
            SensorChip("Radar A", true),
            SensorChip("Radar B", false),
            SensorChip("IMU A", true),
            SensorChip("IMU B", true),
        )
        assertEquals(expected, sensorChips(scene()))
        assertTrue(sensorChips(null).none { it.ok })
    }

    @Test
    fun `searching tells the user how to pair the phone and when there is no room`() {
        assertTrue("Emparejar celular" in phoneLinkLabel(BleStatus.SEARCHING))
        assertTrue("no queda espacio" in phoneLinkLabel(BleStatus.SEARCHING))
        assertTrue("clave" in phoneLinkLabel(BleStatus.PAIRING))
    }

    @Test
    fun `the banner explains a refused start first, then the link, and hides once data flows`() {
        val blocked = SessionUiState(startError = StartError.BLUETOOTH_PERMISSION_MISSING, ble = BleStatus.SEARCHING)
        assertEquals(BLUETOOTH_DENIED_TEXT, radarBanner(blocked))
        assertEquals(phoneLinkLabel(BleStatus.SEARCHING), radarBanner(SessionUiState(running = true, ble = BleStatus.SEARCHING)))
        assertNull(radarBanner(SessionUiState(running = true, ble = BleStatus.STREAMING, scene = scene())))
    }

    @Test
    fun `the header line names the recording or its failure`() {
        assertEquals("Grabando: r.bsrec", recordingLine(SessionUiState(recordingName = "r.bsrec")))
        assertEquals("La grabación falló; el radar sigue.", recordingLine(SessionUiState(recordingName = "r.bsrec", recordingFailed = true)))
        assertNull(recordingLine(SessionUiState()))
    }

    @Test
    fun `the idle hint guides the first pairing, warns about old firmware and mentions a diagnostic in progress`() {
        assertTrue("Emparejar celular" in idleRadarHint(beltPaired = false, purpose = null, linkSupport = LinkSupport.UNKNOWN))
        assertTrue("emparejado" in idleRadarHint(beltPaired = true, purpose = null, linkSupport = LinkSupport.DUAL_LINK))
        assertTrue(UPDATE_FIRMWARE_TEXT in idleRadarHint(beltPaired = true, purpose = null, linkSupport = LinkSupport.SINGLE_LINK))
        assertTrue("diagnóstico" in idleRadarHint(beltPaired = true, purpose = SessionPurpose.DIAGNOSTIC, linkSupport = LinkSupport.DUAL_LINK))
    }

    @Test
    fun `screen readers hear what the radar shows`() {
        assertEquals("Radar sin enlace", radarDescription(null))
        assertEquals("Radar en modo eliminado", radarDescription(scene(eliminated = true)))
        val one = Blip(1, 0.0, 2.0, Confidence.BOTH, 0, false)
        assertEquals("Radar con 1 contactos", radarDescription(scene(blips = listOf(one))))
    }
}
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.phone.ui.*" --console=plain`
Expected: FAIL with `Unresolved reference 'formatDecimal'`.

- [ ] **Step 3: Write the formats**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/common/Formats.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.common

import java.util.Locale
import kotlin.math.roundToInt

private val SPANISH: Locale = Locale.forLanguageTag("es")
private const val KIB = 1_024L
private const val MIB = 1_024L * 1_024L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_MINUTE = 60L

fun formatDecimal(value: Double): String = String.format(SPANISH, "%.1f", value)

fun formatMeters(meters: Double): String = "${formatDecimal(meters)} m"

fun formatSeconds(ms: Long): String = "${formatDecimal(ms / 1_000.0)} s"

fun formatPercent(fraction: Double): String = "${(fraction * 100).roundToInt()} %"

fun formatClock(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1_000L
    val hours = total / SECONDS_PER_HOUR
    val minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = total % SECONDS_PER_MINUTE
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}

fun formatUptime(seconds: Long): String = when {
    seconds < SECONDS_PER_MINUTE -> "$seconds s"
    seconds < SECONDS_PER_HOUR -> String.format(Locale.ROOT, "%d min %02d s", seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
    else -> String.format(Locale.ROOT, "%d h %02d min", seconds / SECONDS_PER_HOUR, (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE)
}

fun formatBytes(bytes: Long): String = when {
    bytes < KIB -> "$bytes B"
    bytes < MIB -> "${formatDecimal(bytes.toDouble() / KIB)} KB"
    else -> "${formatDecimal(bytes.toDouble() / MIB)} MB"
}

fun formatAgo(ms: Long): String = "hace ${formatUptime(ms.coerceAtLeast(0L) / 1_000L)}"
```

- [ ] **Step 4: Write the contact rows and status labels**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/radar/ContactRows.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.radar

import io.github.santiquiroz.blindside.core.scene.Blip
import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.phone.ui.common.formatMeters
import io.github.santiquiroz.blindside.phone.ui.common.formatSeconds
import io.github.santiquiroz.blindside.shared.radar.BlipStyle
import io.github.santiquiroz.blindside.shared.radar.blipStyle
import java.util.Locale
import kotlin.math.roundToInt

data class ContactRow(
    val id: String,
    val distance: String,
    val bearing: String,
    val confidence: String,
    val age: String,
    val style: BlipStyle,
)

fun contactRows(scene: RadarScene?): List<ContactRow> =
    scene?.takeIf { it.linkUp && !it.eliminated }?.blips.orEmpty().sortedBy { it.rangeM }.map(::contactRow)

fun contactRow(blip: Blip): ContactRow = ContactRow(
    id = "#${blip.displayId}",
    distance = formatMeters(blip.rangeM),
    bearing = formatBearing(blip.bearingDeg),
    confidence = confidenceLabel(blip.confidence, blip.outOfView),
    age = formatSeconds(blip.ageMs),
    style = blipStyle(blip.confidence),
)

fun formatBearing(deg: Double): String {
    val rounded = deg.roundToInt()
    return if (rounded == 0) "0°" else String.format(Locale.ROOT, "%+d°", rounded)
}

fun confidenceLabel(confidence: Confidence, outOfView: Boolean): String = when {
    outOfView -> "Fuera de vista"
    confidence == Confidence.BOTH -> "Ambos radares"
    confidence == Confidence.SINGLE -> "Un radar"
    else -> "Perdido"
}
```

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/radar/RadarStatus.kt`. The chips reuse plan 05's `statusItems` and only lengthen its labels:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.radar

import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.phone.settings.LinkSupport
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.radar.statusItems
import io.github.santiquiroz.blindside.shared.session.SessionPurpose
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.session.StartError

data class SensorChip(val label: String, val ok: Boolean)

const val START_RADAR_LABEL = "Iniciar radar"
const val BLUETOOTH_DENIED_TEXT = "Sin permiso de Bluetooth el radar no arranca. Concédelo en los ajustes de la app."
const val UPDATE_FIRMWARE_TEXT = "Actualiza el firmware del cinturón para usar reloj y celular a la vez."

private val CHIP_LABELS = mapOf("BLE" to "Enlace", "R-A" to "Radar A", "R-B" to "Radar B", "I-A" to "IMU A", "I-B" to "IMU B")

fun isLiveRadar(session: SessionUiState): Boolean = session.running && session.purpose == SessionPurpose.GAME

fun sensorChips(scene: RadarScene?): List<SensorChip> =
    statusItems(scene, watchSteps = true).map { SensorChip(CHIP_LABELS[it.label] ?: it.label, it.ok) }

fun phoneLinkLabel(status: BleStatus): String = when (status) {
    BleStatus.IDLE -> "Sin conexión"
    BleStatus.BLUETOOTH_OFF -> "Bluetooth apagado"
    BleStatus.SEARCHING -> "Buscando el cinturón. Primera vez: Cinturón → Pedir al reloj que abra la ventana " +
        "(o en el reloj, Ajustes → Emparejar celular, o BOOT 3 s). Si ya están conectados el reloj y otro celular, no queda espacio."
    BleStatus.PAIRING -> "Emparejando: escribe la clave de 6 dígitos de la etiqueta"
    BleStatus.PAIRING_FAILED -> "Clave incorrecta o ventana cerrada"
    BleStatus.CONNECTING -> "Conectando…"
    BleStatus.STREAMING -> "Recibiendo datos"
    BleStatus.RECONNECTING -> "Reconectando…"
    BleStatus.BOND_LOST -> "El cinturón olvidó este celular: olvídalo en Ajustes → Bluetooth y vuelve a emparejar"
    BleStatus.MTU_TOO_LOW -> "MTU insuficiente"
}

fun startErrorText(error: StartError): String = when (error) {
    StartError.BLUETOOTH_PERMISSION_MISSING -> BLUETOOTH_DENIED_TEXT
    StartError.BLUETOOTH_UNAVAILABLE -> "Este celular no tiene Bluetooth disponible."
}

fun radarBanner(session: SessionUiState): String? = session.startError?.let(::startErrorText) ?: linkBanner(session)

fun recordingLine(session: SessionUiState): String? =
    if (session.recordingFailed) "La grabación falló; el radar sigue." else session.recordingName?.let { "Grabando: $it" }

// On firmware 0.1.0 the belt holds one link: a phone radar would take the slot the watch reconnects to.
fun idleRadarHint(beltPaired: Boolean, purpose: SessionPurpose?, linkSupport: LinkSupport): String = when {
    purpose == SessionPurpose.DIAGNOSTIC -> "Hay un diagnóstico en curso: iniciar el radar lo reemplaza."
    linkSupport == LinkSupport.SINGLE_LINK -> "$UPDATE_FIRMWARE_TEXT Con este firmware el celular le quita el cinturón al reloj."
    beltPaired -> "Cinturón emparejado. Con el arranque automático activo, el radar se inicia solo al abrir la app."
    else -> "Primera vez: Cinturón → Pedir al reloj que abra la ventana (o en el reloj, Ajustes → Emparejar celular, " +
        "o BOOT 3 s en el cinturón) y luego toca Iniciar radar."
}

fun radarDescription(scene: RadarScene?): String = when {
    scene == null || !scene.linkUp -> "Radar sin enlace"
    scene.eliminated -> "Radar en modo eliminado"
    else -> "Radar con ${scene.blips.size} contactos"
}

private fun linkBanner(session: SessionUiState): String? =
    if (session.scene?.linkUp == true) null else phoneLinkLabel(session.ble)
```

- [ ] **Step 5: Run the pure tests**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.phone.ui.*" --console=plain`
Expected: PASS (6 + 6 + 7 tests; the theme tests still pass).

- [ ] **Step 6: Write the shared widgets, screen effects and UI models**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/common/Widgets.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.santiquiroz.blindside.phone.ui.theme.AccentColor
import io.github.santiquiroz.blindside.phone.ui.theme.AlertRedColor
import io.github.santiquiroz.blindside.phone.ui.theme.NumberStyle
import io.github.santiquiroz.blindside.phone.ui.theme.RingColor
import io.github.santiquiroz.blindside.phone.ui.theme.SurfaceColor
import io.github.santiquiroz.blindside.phone.ui.theme.Text2Color
import io.github.santiquiroz.blindside.phone.ui.theme.TextColor

val MIN_TOUCH: Dp = 48.dp
private val CHIP_SHAPE = RoundedCornerShape(16.dp)

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceColor, contentColor = TextColor),
        border = BorderStroke(1.dp, RingColor),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun EmptyState(message: String, actionLabel: String, icon: ImageVector, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = Text2Color, modifier = Modifier.size(48.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = Text2Color, textAlign = TextAlign.Center)
        Button(onClick = onAction, modifier = Modifier.heightIn(min = MIN_TOUCH)) { Text(actionLabel) }
    }
}

@Composable
fun StatusChip(label: String, ok: Boolean) {
    val tint = if (ok) AccentColor else AlertRedColor
    Row(
        modifier = Modifier.border(1.dp, tint, CHIP_SHAPE).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
            contentDescription = if (ok) "correcto" else "con fallo",
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextColor)
    }
}

@Composable
fun NumberText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
) {
    Text(text, modifier = modifier, style = style.merge(NumberStyle), color = color)
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH).toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}
```

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/common/ScreenEffects.kt`. Scene visibility has **one owner**, the app shell (Task 14), keyed by the selected tab. Two tabs fading into each other can therefore never leave it `false` while a radar is on screen:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LifecycleStartEffect
import io.github.santiquiroz.blindside.shared.session.SessionStore
import kotlinx.coroutines.delay

private const val NOW_TICK_MS = 1_000L

@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

// A changed key first disposes the old effect (false) and then starts the new one, so the last word is always the current tab's.
@Composable
fun ReportSceneVisibility(wanted: Boolean) {
    LifecycleStartEffect(wanted) {
        SessionStore.setRadarVisible(wanted)
        onStopOrDispose { SessionStore.setRadarVisible(false) }
    }
}

@Composable
fun rememberNowMs(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(NOW_TICK_MS)
        value = System.currentTimeMillis()
    }
}
```

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/PhoneUiModels.kt`. `updateSettings` covers both shared and local fields: plan 05's `SettingsRepository.update` stamps an edit only when a shared field changed, and the publisher (Task 8) sends only stamped changes.

```kotlin
package io.github.santiquiroz.blindside.phone.ui

import io.github.santiquiroz.blindside.phone.session.PhoneDiagnostics
import io.github.santiquiroz.blindside.phone.settings.PhonePrefs
import io.github.santiquiroz.blindside.shared.ble.BeltCommand
import io.github.santiquiroz.blindside.shared.bridge.WatchStatus
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import io.github.santiquiroz.blindside.shared.settings.SettingsTransform

data class PhoneUiState(
    val session: SessionUiState = SessionUiState(),
    val phone: PhoneDiagnostics = PhoneDiagnostics(),
    val settings: AppSettings = AppSettings(),
    val prefs: PhonePrefs = PhonePrefs(),
    val watchStatus: WatchStatus? = null,
    val bluetoothBlocked: Boolean = false,
)

data class PhoneActions(
    val startRadar: () -> Unit = {},
    val startDiagnostic: () -> Unit = {},
    val stop: () -> Unit = {},
    val toggleEliminated: () -> Unit = {},
    val retryLink: () -> Unit = {},
    val sendCommand: (BeltCommand) -> Unit = {},
    val refreshInfo: () -> Unit = {},
    val updateSettings: (SettingsTransform) -> Unit = {},
    val updatePrefs: ((PhonePrefs) -> PhonePrefs) -> Unit = {},
    val openAppSettings: () -> Unit = {},
)
```

- [ ] **Step 7: Draw the radar with the shared Canvas**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/radar/RadarView.kt`.
- The radar is plan 05's `drawRadar` with `TACTICAL_RADAR_COLORS` (spec §4: "Canvas compartido"). It is the same picture as the watch, with contacts in waiting drawn in `accent-dim` (spec §6).
- The phone only adds the list's legend glyph, which uses the same shape and tone rule.

```kotlin
package io.github.santiquiroz.blindside.phone.ui.radar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.phone.ui.theme.AccentColor
import io.github.santiquiroz.blindside.phone.ui.theme.AccentDimColor
import io.github.santiquiroz.blindside.shared.radar.BlipStyle
import io.github.santiquiroz.blindside.shared.radar.ContactTone
import io.github.santiquiroz.blindside.shared.radar.PointPx
import io.github.santiquiroz.blindside.shared.radar.TACTICAL_RADAR_COLORS
import io.github.santiquiroz.blindside.shared.radar.contactTone
import io.github.santiquiroz.blindside.shared.radar.drawRadar
import io.github.santiquiroz.blindside.shared.radar.showContacts
import io.github.santiquiroz.blindside.shared.radar.toDrawModel

private val GLYPH_SIZE = 18.dp
private val GLYPH_STROKE = 2.dp
private val GLYPH_DASH = 3.dp
private const val GLYPH_RADIUS_FRACTION = 0.33f

@Composable
fun RadarView(scene: RadarScene?, modifier: Modifier = Modifier) {
    Canvas(modifier.semantics { contentDescription = radarDescription(scene) }) {
        val model = toDrawModel(scene, size.width, size.height, PointPx(0f, 0f), showContacts(scene, ambient = false))
        drawRadar(model, TACTICAL_RADAR_COLORS)
    }
}

@Composable
fun ConfidenceGlyph(style: BlipStyle) {
    val color = if (contactTone(style) == ContactTone.DIM) AccentDimColor else AccentColor
    Canvas(Modifier.size(GLYPH_SIZE)) { drawGlyph(style, color, size.minDimension * GLYPH_RADIUS_FRACTION) }
}

private fun DrawScope.drawGlyph(style: BlipStyle, color: Color, radius: Float) {
    if (style == BlipStyle.FILLED) return drawCircle(color, radius)
    val dash = if (style == BlipStyle.DASHED) PathEffect.dashPathEffect(floatArrayOf(GLYPH_DASH.toPx(), GLYPH_DASH.toPx())) else null
    drawCircle(color, radius, style = Stroke(width = GLYPH_STROKE.toPx(), pathEffect = dash))
}
```

- [ ] **Step 8: Write the Radar tab**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/radar/RadarTab.kt`. It has no visibility effect of its own: the app shell owns that.

```kotlin
package io.github.santiquiroz.blindside.phone.ui.radar

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.santiquiroz.blindside.phone.ui.PhoneActions
import io.github.santiquiroz.blindside.phone.ui.PhoneUiState
import io.github.santiquiroz.blindside.phone.ui.common.KeepScreenOn
import io.github.santiquiroz.blindside.phone.ui.common.MIN_TOUCH
import io.github.santiquiroz.blindside.phone.ui.common.NumberText
import io.github.santiquiroz.blindside.phone.ui.common.StatusChip
import io.github.santiquiroz.blindside.phone.ui.theme.AccentColor
import io.github.santiquiroz.blindside.phone.ui.theme.AlertRedColor
import io.github.santiquiroz.blindside.phone.ui.theme.BgColor
import io.github.santiquiroz.blindside.phone.ui.theme.Text2Color
import io.github.santiquiroz.blindside.phone.ui.theme.WarnColor
import io.github.santiquiroz.blindside.shared.ble.needsRetry
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.radar.eliminatedActionLabel
import io.github.santiquiroz.blindside.shared.radar.warningLabel

private val START_BUTTON_SIZE = 200.dp
private val START_ICON_SIZE = 56.dp
private val ELIMINATED_BUTTON_HEIGHT = 64.dp
private val CONTACT_LIST_MAX_HEIGHT = 200.dp

@Composable
fun RadarTab(state: PhoneUiState, actions: PhoneActions) {
    if (isLiveRadar(state.session)) LiveRadar(state.session, actions) else IdleRadar(state, actions)
}

@Composable
private fun IdleRadar(state: PhoneUiState, actions: PhoneActions) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    ) {
        StartRadarButton(actions.startRadar)
        Text(
            idleRadarHint(state.settings.beltAddress != null, state.session.purpose, state.prefs.linkSupport),
            style = MaterialTheme.typography.bodyMedium,
            color = Text2Color,
            textAlign = TextAlign.Center,
        )
        state.session.startError?.let { Text(startErrorText(it), color = AlertRedColor, textAlign = TextAlign.Center) }
        if (state.bluetoothBlocked) BluetoothBlockedNotice(actions.openAppSettings)
    }
}

@Composable
private fun StartRadarButton(onStart: () -> Unit) {
    Button(onClick = onStart, shape = CircleShape, modifier = Modifier.size(START_BUTTON_SIZE)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(START_ICON_SIZE))
            Spacer(Modifier.height(8.dp))
            Text(START_RADAR_LABEL, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun BluetoothBlockedNotice(onOpenSettings: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(BLUETOOTH_DENIED_TEXT, color = WarnColor, textAlign = TextAlign.Center)
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = MIN_TOUCH)) { Text("Abrir ajustes") }
    }
}

@Composable
private fun LiveRadar(session: SessionUiState, actions: PhoneActions) {
    KeepScreenOn()
    var confirmingStop by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RadarHeader(session, onStop = { confirmingStop = true })
        SensorChipRow(sensorChips(session.scene))
        radarBanner(session)?.let { LinkBanner(it, needsRetry(session.ble), actions.retryLink) }
        warningLabel(session.scene?.warnings.orEmpty())?.let { Text(it, color = WarnColor, style = MaterialTheme.typography.labelLarge) }
        RadarView(session.scene, Modifier.weight(1f).fillMaxWidth())
        ContactList(contactRows(session.scene), Modifier.fillMaxWidth().heightIn(max = CONTACT_LIST_MAX_HEIGHT))
        EliminatedButton(session.eliminated, actions.toggleEliminated)
    }
    if (confirmingStop) {
        StopDialog(
            onConfirm = {
                confirmingStop = false
                actions.stop()
            },
            onDismiss = { confirmingStop = false },
        )
    }
}

@Composable
private fun RadarHeader(session: SessionUiState, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Radar en vivo", style = MaterialTheme.typography.titleLarge)
            recordingLine(session)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Text2Color) }
        }
        TextButton(onClick = onStop, modifier = Modifier.heightIn(min = MIN_TOUCH)) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Detener")
        }
    }
}

@Composable
fun SensorChipRow(chips: List<SensorChip>) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { StatusChip(it.label, it.ok) }
    }
}

@Composable
private fun LinkBanner(message: String, canRetry: Boolean, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f), color = WarnColor, style = MaterialTheme.typography.bodyMedium)
        if (canRetry) TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = MIN_TOUCH)) { Text("Reintentar") }
    }
}

@Composable
private fun ContactList(rows: List<ContactRow>, modifier: Modifier) {
    if (rows.isEmpty()) {
        Text("Sin contactos", modifier.padding(vertical = 8.dp), color = Text2Color)
        return
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(rows, key = { it.id }) { ContactLine(it) }
    }
}

@Composable
private fun ContactLine(row: ContactRow) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConfidenceGlyph(row.style)
        NumberText(row.id, Modifier.width(44.dp))
        NumberText(row.distance, Modifier.width(72.dp))
        NumberText(row.bearing, Modifier.width(56.dp))
        Text(row.confidence, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Text2Color)
        NumberText(row.age, style = MaterialTheme.typography.bodyMedium, color = Text2Color)
    }
}

@Composable
private fun EliminatedButton(eliminated: Boolean, onToggle: () -> Unit) {
    Button(
        onClick = onToggle,
        colors = ButtonDefaults.buttonColors(containerColor = if (eliminated) AccentColor else AlertRedColor, contentColor = BgColor),
        modifier = Modifier.fillMaxWidth().height(ELIMINATED_BUTTON_HEIGHT),
    ) {
        Icon(if (eliminated) Icons.Filled.Refresh else Icons.Filled.Close, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(eliminatedActionLabel(eliminated), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun StopDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text("Detener") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Seguir") } },
        title = { Text("¿Detener el radar?") },
        text = { Text("Se cierra la grabación y el celular suelta el cinturón; el reloj sigue igual.") },
    )
}
```

- [ ] **Step 9: Run every phone test and build**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest :phone-app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`, 42 phone tests pass.

- [ ] **Step 10: Commit**

```bash
cd /c/personal/blindside
git add watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/common \
  watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/PhoneUiModels.kt \
  watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/radar \
  watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/ui/common \
  watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/ui/radar
git commit -m "feat: pestaña Radar del celular con el lienzo compartido, contactos, estado de sensores y modo eliminado"
```

---

### Task 8: Data Layer client, shared settings and watch status

The phone uses plan 05's transport and codecs, unchanged:
- **The watch's DataMap transport, shared, never re-implemented.** It publishes with android-shared's `jsonDataRequest(path, json)` (a DataMap with the JSON under `DATA_JSON_KEY`) and reads with `jsonIn(item)`, exactly as the watch does (plan 05 Task 13). Raw item bytes would be unreadable to the other side, and a phone-side decoder could drift from the watch's.
  The code blocks below still show the first raw-bytes version (`jsonFromItemBytes`, `setData`); the review fix replaced it with `jsonDataRequest`/`jsonIn` and deleted `jsonFromItemBytes` and its test.
- **Plan 05's codecs** for `/settings`, `/status` and `/belt/open-pairing`.
- **Plan 05's last-write-wins rule.** `SettingsRepository.update` stamps edits, and `adoptingNewer` adopts only strictly newer remotes.

There is no phone-side settings stamp and no phone-side codec.

**Files:**
- Create: `phone/bridge/BridgeFacts.kt`
- Create: `phone/bridge/WatchStatusStore.kt`
- Create: `phone/bridge/PhoneBridge.kt`
- Create: `phone/bridge/PhoneBridgeListenerService.kt`
- Modify: `watch/phone-app/src/main/AndroidManifest.xml`
- Test: `phoneTest/bridge/BridgeFactsTest.kt`

**Interfaces:**
- Consumes:
  - Plan 05 bridge: `RECORDINGS_LIST_PATH`, `SETTINGS_PATH`, `STATUS_PATH`, `OPEN_PAIRING_PATH`, `decodeRecordingList`, `encodeSharedSettings`, `decodeSharedSettings`, `WatchStatus`, `encodeWatchStatus`, `decodeWatchStatus`, `OpenPairingReply`, `decodeOpenPairingReply`.
  - Plan 05 settings and recording: `SharedSettings`, `sharedSettingsOf`, `isStamped`, `adoptingNewer`, `SettingsRepository`, `settingsRepository()`, `RecordingEntry`.
- Produces (package `…phone.bridge`):
  - `data class NodeFacts(val id: String, val nearby: Boolean)`, `sealed interface BridgeResult<out T> { Ok(value); NoWatch; Failed(reason) }`, `data class BridgeChange(val path: String, val json: String)`, `const val BRIDGE_REQUEST_TIMEOUT_MS = 10_000L`.
  - `fun pickWatchNode(nodes: List<NodeFacts>): String?`, `fun newestSettings(jsons: List<String>): SharedSettings?`, `fun newestStatus(jsons: List<String>): WatchStatus?`.
  - `object WatchStatusStore { state: StateFlow<WatchStatus?>; offer(status) }`, `fun newerStatus(current: WatchStatus?, incoming: WatchStatus): WatchStatus`.
  - `class PhoneBridge(context)` with `suspend listRecordings(): BridgeResult<List<RecordingEntry>>`, `suspend requestOpenPairing(): BridgeResult<OpenPairingReply>`, `suspend publishSharedSettings(repository: SettingsRepository)`, `suspend latestSharedSettings(): SharedSettings?`, `suspend latestStatus(): WatchStatus?`.
  - `class PhoneBridgeListenerService`.

- [ ] **Step 1: Write the failing tests**

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/bridge/BridgeFactsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.bridge

import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.bridge.WatchStatus
import io.github.santiquiroz.blindside.shared.bridge.encodeSharedSettings
import io.github.santiquiroz.blindside.shared.bridge.encodeWatchStatus
import io.github.santiquiroz.blindside.shared.settings.DEFAULT_RADARS
import io.github.santiquiroz.blindside.shared.settings.SharedSettings
import io.github.santiquiroz.blindside.shared.settings.WatchPosture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class BridgeFactsTest {
    private val status = WatchStatus(sessionActive = true, ble = BleStatus.STREAMING, linkUp = true, radars = emptyList(), updatedMs = 2_000L)

    @Test
    fun `the nearby node is the watch`() {
        assertEquals("watch", pickWatchNode(listOf(NodeFacts("cloud", nearby = false), NodeFacts("watch", nearby = true))))
    }

    @Test
    fun `a node that is not nearby is still better than none`() {
        assertEquals("cloud", pickWatchNode(listOf(NodeFacts("cloud", nearby = false))))
    }

    @Test
    fun `no connected node means no watch`() {
        assertNull(pickWatchNode(emptyList()))
    }

    @Test
    fun `an older status never replaces a newer one`() {
        val older = status.copy(sessionActive = false, updatedMs = 1_000L)
        assertSame(status, newerStatus(status, older))
        assertSame(status, newerStatus(older, status))
        assertSame(older, newerStatus(null, older))
    }

    @Test
    fun `item bytes are read as utf-8 json and an empty item is nothing`() {
        assertEquals("""{"a":"ñ"}""", jsonFromItemBytes("""{"a":"ñ"}""".toByteArray(Charsets.UTF_8)))
        assertNull(jsonFromItemBytes(ByteArray(0)))
        assertNull(jsonFromItemBytes(null))
    }

    @Test
    fun `the newest valid settings win and garbage is skipped`() {
        val older = encodeSharedSettings(SharedSettings(Handedness.LEFT, DEFAULT_RADARS, WatchPosture.NORMAL, 100L))
        val newer = encodeSharedSettings(SharedSettings(Handedness.SWITCHER, DEFAULT_RADARS, WatchPosture.NORMAL, 200L))
        assertEquals(Handedness.SWITCHER, newestSettings(listOf(older, "garbage", newer))?.handedness)
        assertNull(newestSettings(listOf("garbage")))
    }

    @Test
    fun `the newest status wins`() {
        val older = encodeWatchStatus(status.copy(updatedMs = 1_000L))
        assertEquals(2_000L, newestStatus(listOf(encodeWatchStatus(status), older, "nope"))?.updatedMs)
    }
}
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.phone.bridge.*" --console=plain`
Expected: FAIL with `Unresolved reference 'pickWatchNode'`.

- [ ] **Step 3: Write the pure bridge facts and the status store**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/bridge/BridgeFacts.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.bridge

import io.github.santiquiroz.blindside.shared.bridge.WatchStatus
import io.github.santiquiroz.blindside.shared.bridge.decodeSharedSettings
import io.github.santiquiroz.blindside.shared.bridge.decodeWatchStatus
import io.github.santiquiroz.blindside.shared.settings.SharedSettings

data class NodeFacts(val id: String, val nearby: Boolean)

sealed interface BridgeResult<out T> {
    data class Ok<out T>(val value: T) : BridgeResult<T>
    data object NoWatch : BridgeResult<Nothing>
    data class Failed(val reason: String) : BridgeResult<Nothing>
}

data class BridgeChange(val path: String, val json: String)

const val BRIDGE_REQUEST_TIMEOUT_MS = 10_000L

// The watch is the node close by; a cloud-relayed node is only a fallback.
fun pickWatchNode(nodes: List<NodeFacts>): String? = (nodes.firstOrNull { it.nearby } ?: nodes.firstOrNull())?.id

// Plan 05 Task 13 publishes /settings and /status as raw UTF-8 JSON (PutDataRequest.setData), never as a DataMap.
fun jsonFromItemBytes(bytes: ByteArray?): String? = bytes?.takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8)

// The phone and the watch each own an item at the same path; the newest valid one wins and garbage is skipped.
fun newestSettings(jsons: List<String>): SharedSettings? = jsons.mapNotNull(::decodeSharedSettings).maxByOrNull { it.updatedMs }

fun newestStatus(jsons: List<String>): WatchStatus? = jsons.mapNotNull(::decodeWatchStatus).maxByOrNull { it.updatedMs }
```

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/bridge/WatchStatusStore.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.bridge

import io.github.santiquiroz.blindside.shared.bridge.WatchStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object WatchStatusStore {
    private val mutableState = MutableStateFlow<WatchStatus?>(null)

    val state: StateFlow<WatchStatus?> = mutableState.asStateFlow()

    fun offer(status: WatchStatus) = mutableState.update { newerStatus(it, status) }
}

// Data Layer events can arrive out of order; an older status never replaces a newer one.
fun newerStatus(current: WatchStatus?, incoming: WatchStatus): WatchStatus =
    if (current != null && current.updatedMs > incoming.updatedMs) current else incoming
```

- [ ] **Step 4: Run the pure tests**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.phone.bridge.*" --console=plain`
Expected: PASS (7 tests).

- [ ] **Step 5: Write the Data Layer client**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/bridge/PhoneBridge.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.bridge

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import io.github.santiquiroz.blindside.shared.bridge.OPEN_PAIRING_PATH
import io.github.santiquiroz.blindside.shared.bridge.OpenPairingReply
import io.github.santiquiroz.blindside.shared.bridge.RECORDINGS_LIST_PATH
import io.github.santiquiroz.blindside.shared.bridge.SETTINGS_PATH
import io.github.santiquiroz.blindside.shared.bridge.STATUS_PATH
import io.github.santiquiroz.blindside.shared.bridge.WatchStatus
import io.github.santiquiroz.blindside.shared.bridge.decodeOpenPairingReply
import io.github.santiquiroz.blindside.shared.bridge.decodeRecordingList
import io.github.santiquiroz.blindside.shared.bridge.encodeSharedSettings
import io.github.santiquiroz.blindside.shared.recording.RecordingEntry
import io.github.santiquiroz.blindside.shared.settings.SettingsRepository
import io.github.santiquiroz.blindside.shared.settings.SharedSettings
import io.github.santiquiroz.blindside.shared.settings.isStamped
import io.github.santiquiroz.blindside.shared.settings.sharedSettingsOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

private const val TAG = "PhoneBridge"
private const val TIMEOUT_REPLY = "sin respuesta a tiempo"

class PhoneBridge(context: Context) {
    private val nodes = Wearable.getNodeClient(context)
    private val messages = Wearable.getMessageClient(context)
    private val data = Wearable.getDataClient(context)

    suspend fun listRecordings(): BridgeResult<List<RecordingEntry>> = withWatch { node ->
        BridgeResult.Ok(decodeRecordingList(request(node, RECORDINGS_LIST_PATH).toString(Charsets.UTF_8)))
    }

    // Plan 05 D6: the watch answers this path only through onRequest, so it must be a request, not a fire-and-forget message.
    suspend fun requestOpenPairing(): BridgeResult<OpenPairingReply> = withWatch { node ->
        BridgeResult.Ok(decodeOpenPairingReply(request(node, OPEN_PAIRING_PATH)))
    }

    // Same rule as the watch's publisher (plan 05 Task 13): every stamped change of the shared part goes out; an adopted remote goes out as its own echo.
    suspend fun publishSharedSettings(repository: SettingsRepository) {
        repository.settings
            .map(::sharedSettingsOf)
            .distinctUntilChanged()
            .filter(::isStamped)
            .collect { publish(SETTINGS_PATH, encodeSharedSettings(it)) }
    }

    suspend fun latestSharedSettings(): SharedSettings? = newestSettings(itemJsons(SETTINGS_PATH))

    suspend fun latestStatus(): WatchStatus? = newestStatus(itemJsons(STATUS_PATH))

    private suspend fun request(node: String, path: String): ByteArray =
        withTimeout(BRIDGE_REQUEST_TIMEOUT_MS) { messages.sendRequest(node, path, ByteArray(0)).await() }

    private suspend fun publish(path: String, json: String) {
        try {
            data.putDataItem(PutDataRequest.create(path).setData(json.toByteArray(Charsets.UTF_8)).setUrgent()).await()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "publish $path failed", error)
        }
    }

    private suspend fun itemJsons(path: String): List<String> = try {
        val buffer = data.dataItems.await()
        try {
            buffer.filter { it.uri.path == path }.mapNotNull { jsonFromItemBytes(it.data) }
        } finally {
            buffer.release()
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.w(TAG, "data items unavailable", error)
        emptyList()
    }

    private suspend fun <T> withWatch(block: suspend (String) -> BridgeResult<T>): BridgeResult<T> = try {
        pickWatchNode(connectedNodes())?.let { block(it) } ?: BridgeResult.NoWatch
    } catch (error: TimeoutCancellationException) {
        BridgeResult.Failed(TIMEOUT_REPLY)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.w(TAG, "bridge call failed", error)
        BridgeResult.Failed(error.message ?: error.javaClass.simpleName)
    }

    private suspend fun connectedNodes(): List<NodeFacts> =
        nodes.connectedNodes.await().map { NodeFacts(it.id, it.isNearby) }
}
```

- [ ] **Step 6: Write the listener service and register it**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/bridge/PhoneBridgeListenerService.kt`. One malformed item must never kill the listener, so each event is read in isolation and each change is applied in isolation:

```kotlin
package io.github.santiquiroz.blindside.phone.bridge

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import io.github.santiquiroz.blindside.shared.bridge.SETTINGS_PATH
import io.github.santiquiroz.blindside.shared.bridge.STATUS_PATH
import io.github.santiquiroz.blindside.shared.bridge.decodeSharedSettings
import io.github.santiquiroz.blindside.shared.bridge.decodeWatchStatus
import io.github.santiquiroz.blindside.shared.settings.adoptingNewer
import io.github.santiquiroz.blindside.shared.settings.settingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

private const val TAG = "PhoneBridgeListener"

class PhoneBridgeListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        // The event buffer dies when this returns, so the changes are copied out first; the DataStore write is short.
        val changes = events.mapNotNull(::changeOf)
        runBlocking { changes.forEach { applySafely(it) } }
    }

    private suspend fun applySafely(change: BridgeChange) {
        try {
            apply(change)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "bridge change at ${change.path} ignored", error)
        }
    }

    private suspend fun apply(change: BridgeChange) {
        when (change.path) {
            SETTINGS_PATH -> decodeSharedSettings(change.json)?.let { remote -> settingsRepository().update { it.adoptingNewer(remote) } }
            STATUS_PATH -> decodeWatchStatus(change.json)?.let(WatchStatusStore::offer)
        }
    }
}

private fun changeOf(event: DataEvent): BridgeChange? =
    runCatching { readChange(event) }.onFailure { Log.w(TAG, "unreadable data event", it) }.getOrNull()

private fun readChange(event: DataEvent): BridgeChange? {
    if (event.type != DataEvent.TYPE_CHANGED) return null
    val path = event.dataItem.uri.path ?: return null
    return jsonFromItemBytes(event.dataItem.data)?.let { BridgeChange(path, it) }
}
```

In `watch/phone-app/src/main/AndroidManifest.xml`, add inside `<application>` after the `PhoneSessionService` entry:

```xml
        <service
            android:name=".bridge.PhoneBridgeListenerService"
            android:exported="true">
            <intent-filter>
                <action android:name="com.google.android.gms.wearable.DATA_CHANGED" />
                <data android:scheme="wear" android:host="*" android:path="/settings" />
            </intent-filter>
            <intent-filter>
                <action android:name="com.google.android.gms.wearable.DATA_CHANGED" />
                <data android:scheme="wear" android:host="*" android:path="/status" />
            </intent-filter>
        </service>
```

- [ ] **Step 7: Run every phone test and build**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest :phone-app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`, 49 phone tests pass.

```bash
cd /c/personal/blindside/watch
grep -rn "setData\|jsonFromItemBytes\|sendMessage" phone-app/src/main || echo "shared DataMap transport and requests only"
```

Expected: `shared DataMap transport and requests only`.

- [ ] **Step 8: Commit**

```bash
cd /c/personal/blindside
git add watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/bridge \
  watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/bridge \
  watch/phone-app/src/main/AndroidManifest.xml
git commit -m "feat: cliente del Data Layer en el celular con el transporte y los códecs del reloj"
```

---

### Task 9: Belt tab (readable diagnostics, actions, pairing, shared settings)

**Files:**
- Create: `phone/ui/belt/BeltInfoView.kt`
- Create: `phone/ui/belt/BeltActions.kt`
- Create: `phone/ui/belt/BeltSections.kt`
- Create: `phone/ui/belt/SharedSettingsSection.kt`
- Create: `phone/ui/belt/BeltTab.kt`
- Test: `phoneTest/ui/belt/BeltInfoViewTest.kt`, `phoneTest/ui/belt/BeltActionsTest.kt`

**Interfaces:**
- Consumes:
  - Task 3: `BeltCommand`.
  - Task 4: `isStatusFresh`, `watchSessionActive`.
  - Task 5: `SessionPurpose`.
  - Task 6: `PhoneDiagnostics`, `LinkSupport`, `PhonePrefs`.
  - Task 7: `PhoneUiState`, `PhoneActions`, `SectionCard`, `SwitchRow`, `NumberText`, `MIN_TOUCH`, `SensorChipRow`, `sensorChips`, `phoneLinkLabel`, `START_RADAR_LABEL`, `UPDATE_FIRMWARE_TEXT`, `formatDecimal`, `formatUptime`, `formatAgo`, `rememberNowMs`.
  - Task 8: `BridgeResult`, `PhoneBridge.requestOpenPairing`.
  - Plan 05: `WatchStatus`, `OpenPairingReply`, `MIN_STREAM_MTU`.
- Produces (package `…phone.ui.belt`):
  - Info view: `data class RadarInfo`, `ImuInfo`, `ConnInfo`, `BeltInfoView`, `InfoRow(label, value, ok = true)`; `fun parseBeltInfoView(json: String): BeltInfoView?`, `fun infoRows(view): List<InfoRow>`, `fun counterRows(counters: PipelineCounters?, rssiDbm: Int?): List<InfoRow>`, `fun roleLabel(role: String): String`.
  - Action rules:
    - `enum class ActionBlock { NOT_CONNECTED, SESSION_ACTIVE }`, `data class PairingGuidance(text, canAskWatch)`;
    - texts `PAIRING_STEPS`, `NO_WATCH_LINK_TEXT`, `FIRMWARE_CAUTION`, `APPLY_ON_START_TEXT`;
    - `phoneLinkUp(session)`, `restartBlock(linkUp)`, `identifyBlock(linkUp, purpose, watchSessionActive)`, `actionBlockText(block)`, `pairingGuidance(linkSupport)`, `pairingRequestMessage(result: BridgeResult<OpenPairingReply>)`, `liveDiagnostics(phone, running)`, `beltLinkText(session)`, `watchStatusLine(status: WatchStatus?, nowMs)`, `handednessLabel(handedness)`, `yawText(radarId, yawDeg)`.
  - `@Composable BeltTab(state: PhoneUiState, actions: PhoneActions, bridge: PhoneBridge)`.

- [ ] **Step 1: Write the failing tests**

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/ui/belt/BeltInfoViewTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.belt

import io.github.santiquiroz.blindside.core.PipelineCounters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BeltInfoViewTest {
    private val mvpInfo = """{"proto":1,"fw":"0.1.0","boot_id":"9f3a12c4","reset":"POWERON","mtu":255,""" +
        """"radars":[{"id":0,"fw":"V2.04.23101915","baud":256000},{"id":1,"fw":"","baud":0}],""" +
        """"imus":[{"id":0,"who":104,"gyro_lsb_dps":65.5,"accel_lsb_g":4096,"repeats":0},{"id":1,"who":0,"gyro_lsb_dps":65.5,"accel_lsb_g":4096,"repeats":3}],""" +
        """"tx_power_dbm":9,"conn":{"interval_ms":45.0,"latency":0,"timeout_ms":5000},"uptime_s":42}"""

    private val dualInfo = """{"proto":1,"fw":"0.2.0","mtu":247,"radars":[],"imus":[],""" +
        """"conns":[{"role":"watch","itvl_ms":45.0,"lat":0,"timeout_ms":5000,"sent":1200,"dropped":0},""" +
        """{"role":"phone","itvl_ms":90.0,"lat":0,"timeout_ms":6000,"sent":1100,"dropped":7}],"bonds":2}"""

    @Test
    fun `the MVP info reads as plain rows`() {
        val expected = listOf(
            InfoRow("Firmware", "0.1.0 · proto 1"),
            InfoRow("Último arranque", "POWERON · 9f3a12c4"),
            InfoRow("Encendido", "42 s"),
            InfoRow("Radar A", "V2.04.23101915 · 256000 baud"),
            InfoRow("Radar B", "no detectado", ok = false),
            InfoRow("IMU A", "WHO 104 · 0 repeticiones"),
            InfoRow("IMU B", "no encontrado", ok = false),
            InfoRow("Potencia", "9 dBm"),
            InfoRow("MTU", "255"),
            InfoRow("Conexión", "45,0 ms · latencia 0 · supervisión 5000 ms"),
        )
        assertEquals(expected, infoRows(parseBeltInfoView(mvpInfo)!!))
    }

    @Test
    fun `both connections and the bond count show once the firmware reports them`() {
        val rows = infoRows(parseBeltInfoView(dualInfo)!!)
        assertTrue(InfoRow("Conexión reloj", "45,0 ms · enviados 1200 · descartados 0") in rows)
        assertTrue(InfoRow("Conexión celular", "90,0 ms · enviados 1100 · descartados 7") in rows)
        assertTrue(InfoRow("Dispositivos emparejados", "2") in rows)
        assertTrue(rows.none { it.label == "Conexión" })
    }

    @Test
    fun `drops on the watch link are a fault and drops on the phone link are not`() {
        val view = parseBeltInfoView(dualInfo)!!.let { it.copy(conns = it.conns.map { conn -> conn.copy(dropped = 3) }) }
        val rows = infoRows(view)
        assertFalse(rows.first { it.label == "Conexión reloj" }.ok)
        assertTrue(rows.first { it.label == "Conexión celular" }.ok)
    }

    @Test
    fun `an mtu below 247 is flagged`() {
        assertFalse(infoRows(parseBeltInfoView("""{"mtu":185}""")!!).first { it.label == "MTU" }.ok)
    }

    @Test
    fun `info that is not a json object has no view`() {
        assertNull(parseBeltInfoView("not json"))
        assertNull(parseBeltInfoView("[]"))
    }

    @Test
    fun `an empty object still names the firmware as unknown`() {
        assertEquals(listOf(InfoRow("Firmware", "desconocido")), infoRows(parseBeltInfoView("{}")!!))
    }

    @Test
    fun `counters read in plain words with faults flagged`() {
        val rows = counterRows(PipelineCounters(packets = 900, lostPackets = 3, malformedPackets = 1, espResets = 1), rssiDbm = -61)
        val expected = listOf(
            InfoRow("Paquetes recibidos", "900"),
            InfoRow("Paquetes perdidos", "3"),
            InfoRow("Paquetes malformados", "1", ok = false),
            InfoRow("Paquetes truncados", "0"),
            InfoRow("Reinicios del cinturón", "1"),
            InfoRow("Señal", "-61 dBm"),
        )
        assertEquals(expected, rows)
        assertEquals(emptyList<InfoRow>(), counterRows(null, -61))
    }

    @Test
    fun `roles read in spanish`() {
        assertEquals("reloj", roleLabel("watch"))
        assertEquals("celular", roleLabel("phone"))
        assertEquals("tablet", roleLabel("tablet"))
    }
}
```

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/ui/belt/BeltActionsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.belt

import io.github.santiquiroz.blindside.core.PipelineCounters
import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.RADAR_B
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import io.github.santiquiroz.blindside.phone.bridge.BridgeResult
import io.github.santiquiroz.blindside.phone.session.PhoneDiagnostics
import io.github.santiquiroz.blindside.phone.settings.LinkSupport
import io.github.santiquiroz.blindside.phone.ui.radar.UPDATE_FIRMWARE_TEXT
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.bridge.OpenPairingReply
import io.github.santiquiroz.blindside.shared.bridge.WatchStatus
import io.github.santiquiroz.blindside.shared.session.SessionPurpose
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BeltActionsTest {
    @Test
    fun `the phone link is up only while a session streams`() {
        assertTrue(phoneLinkUp(SessionUiState(running = true, ble = BleStatus.STREAMING)))
        assertFalse(phoneLinkUp(SessionUiState(running = true, ble = BleStatus.CONNECTING)))
        assertFalse(phoneLinkUp(SessionUiState(running = false, ble = BleStatus.STREAMING)))
    }

    @Test
    fun `restarting a radar needs only a streaming link`() {
        assertNull(restartBlock(linkUp = true))
        assertEquals(ActionBlock.NOT_CONNECTED, restartBlock(linkUp = false))
    }

    @Test
    fun `identify is blocked while any device plays a game`() {
        assertEquals(ActionBlock.NOT_CONNECTED, identifyBlock(linkUp = false, purpose = SessionPurpose.DIAGNOSTIC, watchSessionActive = false))
        assertEquals(ActionBlock.SESSION_ACTIVE, identifyBlock(linkUp = true, purpose = SessionPurpose.GAME, watchSessionActive = false))
        assertEquals(ActionBlock.SESSION_ACTIVE, identifyBlock(linkUp = true, purpose = SessionPurpose.DIAGNOSTIC, watchSessionActive = true))
        assertNull(identifyBlock(linkUp = true, purpose = SessionPurpose.DIAGNOSTIC, watchSessionActive = false))
    }

    @Test
    fun `every block explains itself`() {
        ActionBlock.entries.forEach { assertTrue(actionBlockText(it).isNotBlank()) }
    }

    @Test
    fun `the pairing request always ends in a next step`() {
        assertTrue("60 s" in pairingRequestMessage(BridgeResult.Ok(OpenPairingReply.REQUESTED)))
        assertEquals(NO_WATCH_LINK_TEXT, pairingRequestMessage(BridgeResult.Ok(OpenPairingReply.NO_LINK)))
        assertTrue("BOOT" in pairingRequestMessage(BridgeResult.NoWatch))
        val failed = pairingRequestMessage(BridgeResult.Failed("sin respuesta a tiempo"))
        assertTrue("sin respuesta a tiempo" in failed && "BOOT" in failed)
    }

    @Test
    fun `a watch whose game is not running says to start it`() {
        assertTrue("inicia el radar" in NO_WATCH_LINK_TEXT && "BOOT" in NO_WATCH_LINK_TEXT)
    }

    @Test
    fun `pairing guidance depends on the belt firmware`() {
        assertEquals(PairingGuidance(PAIRING_STEPS, canAskWatch = true), pairingGuidance(LinkSupport.DUAL_LINK))
        assertEquals(PairingGuidance(UPDATE_FIRMWARE_TEXT, canAskWatch = false), pairingGuidance(LinkSupport.SINGLE_LINK))
        val unknown = pairingGuidance(LinkSupport.UNKNOWN)
        assertTrue(unknown.canAskWatch && FIRMWARE_CAUTION in unknown.text && PAIRING_STEPS in unknown.text)
    }

    @Test
    fun `the pairing steps say what to do when the belt has no free slot`() {
        assertTrue("no queda espacio" in PAIRING_STEPS)
    }

    @Test
    fun `counters and signal show only while the phone is connected`() {
        val phone = PhoneDiagnostics(infoJson = "{}", rssiDbm = -60, counters = PipelineCounters(packets = 9))
        assertEquals(phone, liveDiagnostics(phone, running = true))
        assertEquals(PhoneDiagnostics(infoJson = "{}"), liveDiagnostics(phone, running = false))
    }

    @Test
    fun `the watch line says when there is no recent status`() {
        val status = WatchStatus(true, BleStatus.STREAMING, linkUp = true, radars = listOf(SensorStatus(0, true), SensorStatus(RADAR_B, false)), updatedMs = 10_000L)
        assertTrue("sin datos" in watchStatusLine(null, nowMs = 0L))
        assertEquals("Reloj: sin datos recientes (hace 20 s)", watchStatusLine(status, nowMs = 30_000L))
        assertEquals("Reloj: partida activa · enlace bien · radar A bien, B caído", watchStatusLine(status, nowMs = 12_000L))
        assertTrue("enlace caído" in watchStatusLine(status.copy(linkUp = false), nowMs = 12_000L))
    }

    @Test
    fun `the link line names the purpose and the state`() {
        assertEquals("Celular sin conexión al cinturón", beltLinkText(SessionUiState()))
        val diagnostic = SessionUiState(running = true, purpose = SessionPurpose.DIAGNOSTIC, ble = BleStatus.STREAMING)
        assertEquals("Diagnóstico: Recibiendo datos", beltLinkText(diagnostic))
    }

    @Test
    fun `settings labels read in spanish`() {
        assertEquals("Cambia de hombro", handednessLabel(Handedness.SWITCHER))
        assertEquals("Radar B -40°", yawText(RADAR_B, -39.6))
    }
}
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.phone.ui.belt.*" --console=plain`
Expected: FAIL with `Unresolved reference 'parseBeltInfoView'`.

- [ ] **Step 3: Write the info mapping**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/belt/BeltInfoView.kt`. It reads the MVP `conn` and the 0.2.0 `conns`/`bonds` (plan 04 flag 2):

```kotlin
package io.github.santiquiroz.blindside.phone.ui.belt

import io.github.santiquiroz.blindside.core.PipelineCounters
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.protocol.MiniJson
import io.github.santiquiroz.blindside.phone.ui.common.formatDecimal
import io.github.santiquiroz.blindside.phone.ui.common.formatUptime
import io.github.santiquiroz.blindside.shared.ble.MIN_STREAM_MTU

data class RadarInfo(val id: Int, val firmware: String, val baud: Int)

data class ImuInfo(val id: Int, val whoAmI: Int, val repeats: Long)

data class ConnInfo(val role: String, val intervalMs: Double, val latency: Int, val timeoutMs: Int, val sent: Long, val dropped: Long)

data class BeltInfoView(
    val firmware: String?,
    val proto: Int?,
    val bootId: String?,
    val reset: String?,
    val mtu: Int?,
    val txPowerDbm: Int?,
    val uptimeS: Long?,
    val radars: List<RadarInfo>,
    val imus: List<ImuInfo>,
    val conns: List<ConnInfo>,
    val legacyConn: ConnInfo?,
    val bonds: Int?,
)

data class InfoRow(val label: String, val value: String, val ok: Boolean = true)

private const val WATCH_ROLE = "watch"
private const val PHONE_ROLE = "phone"

fun parseBeltInfoView(json: String): BeltInfoView? {
    val root = MiniJson.parseOrNull(json) as? Map<*, *> ?: return null
    return BeltInfoView(
        firmware = root["fw"] as? String,
        proto = intOf(root["proto"]),
        bootId = root["boot_id"] as? String,
        reset = root["reset"] as? String,
        mtu = intOf(root["mtu"]),
        txPowerDbm = intOf(root["tx_power_dbm"]),
        uptimeS = longOf(root["uptime_s"]),
        radars = objects(root["radars"]).mapNotNull(::radarInfoOf),
        imus = objects(root["imus"]).mapNotNull(::imuInfoOf),
        conns = objects(root["conns"]).map(::connInfoOf),
        legacyConn = (root["conn"] as? Map<*, *>)?.let(::legacyConnOf),
        bonds = intOf(root["bonds"]),
    )
}

fun infoRows(view: BeltInfoView): List<InfoRow> =
    headerRows(view) + view.radars.map(::radarRow) + view.imus.map(::imuRow) + linkRows(view) + connRows(view)

fun counterRows(counters: PipelineCounters?, rssiDbm: Int?): List<InfoRow> {
    if (counters == null) return emptyList()
    return listOfNotNull(
        InfoRow("Paquetes recibidos", "${counters.packets}"),
        InfoRow("Paquetes perdidos", "${counters.lostPackets}"),
        InfoRow("Paquetes malformados", "${counters.malformedPackets}", ok = counters.malformedPackets == 0L),
        InfoRow("Paquetes truncados", "${counters.truncatedPackets}", ok = counters.truncatedPackets == 0L),
        InfoRow("Reinicios del cinturón", "${counters.espResets}"),
        rssiDbm?.let { InfoRow("Señal", "$it dBm") },
    )
}

fun roleLabel(role: String): String = when (role) {
    WATCH_ROLE -> "reloj"
    PHONE_ROLE -> "celular"
    else -> role
}

private fun headerRows(view: BeltInfoView): List<InfoRow> = listOfNotNull(
    InfoRow("Firmware", firmwareText(view)),
    view.reset?.let { InfoRow("Último arranque", listOfNotNull(it, view.bootId).joinToString(" · ")) },
    view.uptimeS?.let { InfoRow("Encendido", formatUptime(it)) },
)

private fun linkRows(view: BeltInfoView): List<InfoRow> = listOfNotNull(
    view.txPowerDbm?.let { InfoRow("Potencia", "$it dBm") },
    view.mtu?.let { InfoRow("MTU", "$it", ok = it >= MIN_STREAM_MTU) },
    view.bonds?.let { InfoRow("Dispositivos emparejados", "$it") },
)

private fun connRows(view: BeltInfoView): List<InfoRow> =
    if (view.conns.isNotEmpty()) view.conns.map(::connRow) else listOfNotNull(view.legacyConn?.let(::legacyConnRow))

private fun firmwareText(view: BeltInfoView): String =
    listOfNotNull(view.firmware, view.proto?.let { "proto $it" }).joinToString(" · ").ifEmpty { "desconocido" }

private fun radarRow(radar: RadarInfo): InfoRow {
    val detected = radar.baud > 0
    val value = if (detected) "${radar.firmware} · ${radar.baud} baud" else "no detectado"
    return InfoRow("Radar ${letterOf(radar.id)}", value, ok = detected)
}

private fun imuRow(imu: ImuInfo): InfoRow {
    val found = imu.whoAmI != 0
    val value = if (found) "WHO ${imu.whoAmI} · ${imu.repeats} repeticiones" else "no encontrado"
    return InfoRow("IMU ${letterOf(imu.id)}", value, ok = found)
}

// Spec §2: the belt drops phone packets to protect the watch, so only drops on the watch link are a fault.
private fun connRow(conn: ConnInfo): InfoRow = InfoRow(
    "Conexión ${roleLabel(conn.role)}",
    "${formatDecimal(conn.intervalMs)} ms · enviados ${conn.sent} · descartados ${conn.dropped}",
    ok = !(conn.role == WATCH_ROLE && conn.dropped > 0),
)

private fun legacyConnRow(conn: ConnInfo): InfoRow =
    InfoRow("Conexión", "${formatDecimal(conn.intervalMs)} ms · latencia ${conn.latency} · supervisión ${conn.timeoutMs} ms")

private fun letterOf(id: Int): String = if (id == RADAR_A) "A" else "B"

private fun objects(value: Any?): List<Map<*, *>> = (value as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()

private fun intOf(value: Any?): Int? = (value as? Double)?.toInt()

private fun longOf(value: Any?): Long? = (value as? Double)?.toLong()

private fun radarInfoOf(fields: Map<*, *>): RadarInfo? =
    intOf(fields["id"])?.let { RadarInfo(it, fields["fw"] as? String ?: "", intOf(fields["baud"]) ?: 0) }

private fun imuInfoOf(fields: Map<*, *>): ImuInfo? =
    intOf(fields["id"])?.let { ImuInfo(it, intOf(fields["who"]) ?: 0, longOf(fields["repeats"]) ?: 0L) }

private fun connInfoOf(fields: Map<*, *>): ConnInfo = ConnInfo(
    role = fields["role"] as? String ?: "?",
    intervalMs = fields["itvl_ms"] as? Double ?: 0.0,
    latency = intOf(fields["lat"]) ?: 0,
    timeoutMs = intOf(fields["timeout_ms"]) ?: 0,
    sent = longOf(fields["sent"]) ?: 0L,
    dropped = longOf(fields["dropped"]) ?: 0L,
)

private fun legacyConnOf(fields: Map<*, *>): ConnInfo = ConnInfo(
    role = "",
    intervalMs = fields["interval_ms"] as? Double ?: 0.0,
    latency = intOf(fields["latency"]) ?: 0,
    timeoutMs = intOf(fields["timeout_ms"]) ?: 0,
    sent = 0L,
    dropped = 0L,
)
```

- [ ] **Step 4: Write the action rules and labels**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/belt/BeltActions.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.belt

import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import io.github.santiquiroz.blindside.phone.bridge.BridgeResult
import io.github.santiquiroz.blindside.phone.session.PhoneDiagnostics
import io.github.santiquiroz.blindside.phone.settings.LinkSupport
import io.github.santiquiroz.blindside.phone.ui.common.formatAgo
import io.github.santiquiroz.blindside.phone.ui.radar.UPDATE_FIRMWARE_TEXT
import io.github.santiquiroz.blindside.phone.ui.radar.phoneLinkLabel
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.bridge.OpenPairingReply
import io.github.santiquiroz.blindside.shared.bridge.WatchStatus
import io.github.santiquiroz.blindside.shared.bridge.isStatusFresh
import io.github.santiquiroz.blindside.shared.session.SessionPurpose
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import kotlin.math.roundToInt

enum class ActionBlock { NOT_CONNECTED, SESSION_ACTIVE }

data class PairingGuidance(val text: String, val canAskWatch: Boolean)

const val PAIRING_STEPS = "1. Con el radar del reloj en marcha, toca Pedir al reloj que abra la ventana " +
    "(o en el reloj, Ajustes → Emparejar celular; o BOOT 3 s en el primer minuto tras encender el cinturón).\n" +
    "2. Toca Iniciar radar.\n" +
    "3. Cuando Android lo pida, escribe la clave de 6 dígitos de la etiqueta del cinturón.\n" +
    "Si el cinturón ya tiene conectados el reloj y otro celular, no queda espacio: desconecta uno."

const val NO_WATCH_LINK_TEXT = "En el reloj, inicia el radar primero (o mantén BOOT 3 s en el primer minuto tras encender el cinturón)."

const val FIRMWARE_CAUTION = "Requiere el firmware 0.2.0 del cinturón: con el 0.1.0, emparejar el celular desempareja el reloj."

const val APPLY_ON_START_TEXT = "Mano, ángulos y signos se comparten con el reloj y se aplican al iniciar el radar."

fun phoneLinkUp(session: SessionUiState): Boolean = session.running && session.ble == BleStatus.STREAMING

fun restartBlock(linkUp: Boolean): ActionBlock? = if (linkUp) null else ActionBlock.NOT_CONNECTED

// Spec §2: the belt ignores IDENTIFY while any link has its session active, so the button says why instead of failing silently.
fun identifyBlock(linkUp: Boolean, purpose: SessionPurpose?, watchSessionActive: Boolean): ActionBlock? = when {
    !linkUp -> ActionBlock.NOT_CONNECTED
    purpose == SessionPurpose.GAME || watchSessionActive -> ActionBlock.SESSION_ACTIVE
    else -> null
}

fun actionBlockText(block: ActionBlock): String = when (block) {
    ActionBlock.NOT_CONNECTED -> "Conecta el cinturón para usar las acciones."
    ActionBlock.SESSION_ACTIVE -> "Identificar no funciona con una partida activa en el reloj o en el celular."
}

// On 0.1.0 the belt keeps one bond: pairing the phone there unpairs the watch (Deviation P5).
fun pairingGuidance(linkSupport: LinkSupport): PairingGuidance = when (linkSupport) {
    LinkSupport.DUAL_LINK -> PairingGuidance(PAIRING_STEPS, canAskWatch = true)
    LinkSupport.SINGLE_LINK -> PairingGuidance(UPDATE_FIRMWARE_TEXT, canAskWatch = false)
    LinkSupport.UNKNOWN -> PairingGuidance("$PAIRING_STEPS\n$FIRMWARE_CAUTION", canAskWatch = true)
}

fun pairingRequestMessage(result: BridgeResult<OpenPairingReply>): String = when (result) {
    is BridgeResult.Ok -> pairingReplyMessage(result.value)
    BridgeResult.NoWatch -> "No hay un reloj conectado: mantén BOOT 3 s en el primer minuto tras encender el cinturón."
    is BridgeResult.Failed -> "El reloj no respondió (${result.reason}): usa el botón BOOT del cinturón."
}

fun liveDiagnostics(phone: PhoneDiagnostics, running: Boolean): PhoneDiagnostics =
    if (running) phone else phone.copy(rssiDbm = null, counters = null)

fun beltLinkText(session: SessionUiState): String =
    if (!session.running) "Celular sin conexión al cinturón" else "${purposeLabel(session.purpose)}: ${phoneLinkLabel(session.ble)}"

fun watchStatusLine(status: WatchStatus?, nowMs: Long): String = when {
    status == null -> "Reloj: sin datos (abre Blindside en el reloj para compartir su estado)"
    !isStatusFresh(status, nowMs) -> "Reloj: sin datos recientes (${formatAgo(nowMs - status.updatedMs)})"
    else -> "Reloj: ${sessionWord(status)} · ${linkWord(status.linkUp)} · ${radarsWord(status.radars)}"
}

fun handednessLabel(handedness: Handedness): String = when (handedness) {
    Handedness.RIGHT -> "Diestro"
    Handedness.LEFT -> "Zurdo"
    Handedness.SWITCHER -> "Cambia de hombro"
}

fun yawText(radarId: Int, yawDeg: Double): String = "Radar ${if (radarId == RADAR_A) "A" else "B"} ${yawDeg.roundToInt()}°"

// The watch only queues 05 (plan 05 D8): REQUESTED means asked, and the belt's own reply shows on the watch.
private fun pairingReplyMessage(reply: OpenPairingReply): String = when (reply) {
    OpenPairingReply.REQUESTED -> "Pedido enviado: el reloj abre la ventana del cinturón por 60 s. Ahora toca Iniciar radar."
    OpenPairingReply.NO_LINK -> NO_WATCH_LINK_TEXT
}

private fun purposeLabel(purpose: SessionPurpose?): String = if (purpose == SessionPurpose.DIAGNOSTIC) "Diagnóstico" else "Radar"

private fun sessionWord(status: WatchStatus): String = if (status.sessionActive) "partida activa" else "sin partida"

private fun linkWord(linkUp: Boolean): String = if (linkUp) "enlace bien" else "enlace caído"

private fun radarsWord(radars: List<SensorStatus>): String = "radar A ${aliveWord(radars, RADAR_A)}, B ${aliveWord(radars, RADAR_B)}"

private fun aliveWord(radars: List<SensorStatus>, id: Int): String = if (radars.any { it.id == id && it.alive }) "bien" else "caído"
```

- [ ] **Step 5: Run the pure tests**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.phone.ui.belt.*" --console=plain`
Expected: PASS (8 + 12 tests).

- [ ] **Step 6: Write the belt sections**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/belt/BeltSections.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.belt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import io.github.santiquiroz.blindside.phone.bridge.PhoneBridge
import io.github.santiquiroz.blindside.phone.session.PhoneDiagnostics
import io.github.santiquiroz.blindside.phone.settings.LinkSupport
import io.github.santiquiroz.blindside.phone.settings.PhonePrefs
import io.github.santiquiroz.blindside.phone.ui.PhoneActions
import io.github.santiquiroz.blindside.phone.ui.PhoneUiState
import io.github.santiquiroz.blindside.phone.ui.common.MIN_TOUCH
import io.github.santiquiroz.blindside.phone.ui.common.NumberText
import io.github.santiquiroz.blindside.phone.ui.common.SectionCard
import io.github.santiquiroz.blindside.phone.ui.common.SwitchRow
import io.github.santiquiroz.blindside.phone.ui.radar.START_RADAR_LABEL
import io.github.santiquiroz.blindside.phone.ui.radar.SensorChipRow
import io.github.santiquiroz.blindside.phone.ui.radar.sensorChips
import io.github.santiquiroz.blindside.phone.ui.theme.AccentColor
import io.github.santiquiroz.blindside.phone.ui.theme.AlertRedColor
import io.github.santiquiroz.blindside.phone.ui.theme.Text2Color
import io.github.santiquiroz.blindside.shared.ble.BeltCommand
import io.github.santiquiroz.blindside.shared.session.SessionPurpose
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import kotlinx.coroutines.launch

private val RADARS = listOf(RADAR_A to "A", RADAR_B to "B")

@Composable
fun LinkSection(state: PhoneUiState, nowMs: Long, actions: PhoneActions) {
    SectionCard("Enlace") {
        Text(beltLinkText(state.session), style = MaterialTheme.typography.bodyLarge)
        Text(watchStatusLine(state.watchStatus, nowMs), style = MaterialTheme.typography.bodyMedium, color = Text2Color)
        if (state.session.running) SensorChipRow(sensorChips(state.session.scene))
        LinkButton(state, actions)
    }
}

@Composable
private fun LinkButton(state: PhoneUiState, actions: PhoneActions) {
    val full = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH)
    when {
        !state.session.running -> Button(onClick = actions.startDiagnostic, modifier = full) { Text("Conectar para diagnóstico") }
        state.session.purpose == SessionPurpose.DIAGNOSTIC -> OutlinedButton(onClick = actions.stop, modifier = full) { Text("Desconectar") }
    }
}

@Composable
fun DiagnosticSection(phone: PhoneDiagnostics, running: Boolean, linkUp: Boolean, onRefresh: () -> Unit, onConnect: () -> Unit) {
    SectionCard("Diagnóstico") {
        val live = liveDiagnostics(phone, running)
        val view = live.infoJson?.let(::parseBeltInfoView)
        if (view == null) NoDiagnostic(onConnect) else InfoDetails(infoRows(view), linkUp, onRefresh)
        val counters = counterRows(live.counters, live.rssiDbm)
        if (counters.isNotEmpty()) {
            Text("Contadores", style = MaterialTheme.typography.titleSmall)
            counters.forEach { InfoLine(it) }
        }
    }
}

@Composable
private fun NoDiagnostic(onConnect: () -> Unit) {
    Text("Aún no hay diagnóstico: conecta el cinturón para leer su información.", color = Text2Color)
    TextButton(onClick = onConnect, modifier = Modifier.heightIn(min = MIN_TOUCH)) { Text("Conectar para diagnóstico") }
}

@Composable
private fun InfoDetails(rows: List<InfoRow>, linkUp: Boolean, onRefresh: () -> Unit) {
    rows.forEach { InfoLine(it) }
    OutlinedButton(onClick = onRefresh, enabled = linkUp, modifier = Modifier.heightIn(min = MIN_TOUCH)) {
        Icon(Icons.Filled.Refresh, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Actualizar")
    }
}

@Composable
private fun InfoLine(row: InfoRow) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (row.ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
            contentDescription = if (row.ok) "correcto" else "con fallo",
            tint = if (row.ok) AccentColor else AlertRedColor,
            modifier = Modifier.size(16.dp),
        )
        Text(row.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Text2Color)
        NumberText(row.value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ActionsSection(identify: ActionBlock?, restart: ActionBlock?, onCommand: (BeltCommand) -> Unit) {
    SectionCard("Acciones") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RADARS.forEach { (id, letter) ->
                OutlinedButton(
                    onClick = { onCommand(BeltCommand.RestartRadar(id)) },
                    enabled = restart == null,
                    modifier = Modifier.weight(1f).heightIn(min = MIN_TOUCH),
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Reiniciar radar $letter")
                }
            }
        }
        OutlinedButton(
            onClick = { onCommand(BeltCommand.Identify) },
            enabled = identify == null,
            modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH),
        ) {
            Icon(Icons.Filled.Lightbulb, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Identificar (parpadea el LED)")
        }
        (identify ?: restart)?.let { Text(actionBlockText(it), style = MaterialTheme.typography.bodySmall, color = Text2Color) }
    }
}

@Composable
fun PairingSection(bridge: PhoneBridge, linkSupport: LinkSupport, onStartRadar: () -> Unit) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    val guidance = pairingGuidance(linkSupport)
    SectionCard("Emparejar este celular") {
        Text(guidance.text, style = MaterialTheme.typography.bodyMedium, color = Text2Color)
        FilledTonalButton(
            onClick = {
                asking = true
                scope.launch {
                    message = pairingRequestMessage(bridge.requestOpenPairing())
                    asking = false
                }
            },
            enabled = guidance.canAskWatch && !asking,
            modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH),
        ) { Text("Pedir al reloj que abra la ventana") }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        OutlinedButton(onClick = onStartRadar, modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH)) { Text(START_RADAR_LABEL) }
    }
}

@Composable
fun PhoneSettingsSection(settings: AppSettings, prefs: PhonePrefs, actions: PhoneActions) {
    SectionCard("Este celular") {
        SwitchRow("Vibrar en el celular", prefs.vibrate) { actions.updatePrefs { it.copy(vibrate = !it.vibrate) } }
        SwitchRow("Iniciar radar al abrir", settings.autoStartRadar) { actions.updateSettings { it.copy(autoStartRadar = !it.autoStartRadar) } }
        Text(
            "La vibración se aplica al iniciar el radar. El arranque automático requiere el firmware 0.2.0 del cinturón.",
            style = MaterialTheme.typography.bodySmall,
            color = Text2Color,
        )
    }
}
```

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/belt/SharedSettingsSection.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.belt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.phone.ui.common.MIN_TOUCH
import io.github.santiquiroz.blindside.phone.ui.common.NumberText
import io.github.santiquiroz.blindside.phone.ui.common.SectionCard
import io.github.santiquiroz.blindside.phone.ui.common.SwitchRow
import io.github.santiquiroz.blindside.phone.ui.theme.RingColor
import io.github.santiquiroz.blindside.phone.ui.theme.Text2Color
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import io.github.santiquiroz.blindside.shared.settings.DEFAULT_RADARS
import io.github.santiquiroz.blindside.shared.settings.SettingsTransform
import io.github.santiquiroz.blindside.shared.settings.YAW_STEP_DEG
import io.github.santiquiroz.blindside.shared.settings.effectiveYawDeg
import io.github.santiquiroz.blindside.shared.settings.radar
import io.github.santiquiroz.blindside.shared.settings.withFlipXToggled
import io.github.santiquiroz.blindside.shared.settings.withHandedness
import io.github.santiquiroz.blindside.shared.settings.withSpeedSignFlipped
import io.github.santiquiroz.blindside.shared.settings.withYawNudged

@Composable
fun SharedSettingsSection(settings: AppSettings, onUpdate: (SettingsTransform) -> Unit) {
    SectionCard("Ajustes compartidos con el reloj") {
        Text("Mano", style = MaterialTheme.typography.labelLarge)
        HandednessSelector(settings.handedness) { chosen -> onUpdate { it.withHandedness(chosen) } }
        DEFAULT_RADARS.forEach { RadarSettingsBlock(settings, it.radarId, onUpdate) }
        Text(APPLY_ON_START_TEXT, style = MaterialTheme.typography.bodySmall, color = Text2Color)
    }
}

@Composable
private fun HandednessSelector(selected: Handedness, onSelect: (Handedness) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        Handedness.entries.forEachIndexed { index, handedness ->
            SegmentedButton(
                selected = handedness == selected,
                onClick = { onSelect(handedness) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = Handedness.entries.size),
            ) { Text(handednessLabel(handedness), maxLines = 1) }
        }
    }
}

@Composable
private fun RadarSettingsBlock(settings: AppSettings, radarId: Int, onUpdate: (SettingsTransform) -> Unit) {
    val radar = settings.radar(radarId)
    HorizontalDivider(color = RingColor)
    YawRow(settings, radarId, onUpdate)
    SwitchRow("Invertir X", radar.flipX) { onUpdate { it.withFlipXToggled(radarId) } }
    SwitchRow("Signo de velocidad invertido", radar.speedSign < 0) { onUpdate { it.withSpeedSignFlipped(radarId) } }
}

@Composable
private fun YawRow(settings: AppSettings, radarId: Int, onUpdate: (SettingsTransform) -> Unit) {
    val label = yawText(radarId, effectiveYawDeg(settings, radarId))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { onUpdate { it.withYawNudged(radarId, -YAW_STEP_DEG) } },
            modifier = Modifier.heightIn(min = MIN_TOUCH).semantics { contentDescription = "$label: girar 5 grados a la izquierda" },
        ) { Text("−5°") }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { NumberText(label) }
        OutlinedButton(
            onClick = { onUpdate { it.withYawNudged(radarId, YAW_STEP_DEG) } },
            modifier = Modifier.heightIn(min = MIN_TOUCH).semantics { contentDescription = "$label: girar 5 grados a la derecha" },
        ) { Text("+5°") }
    }
}
```

- [ ] **Step 7: Write the tab**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/belt/BeltTab.kt`. It has no visibility effect of its own: the app shell owns that (Task 14).

```kotlin
package io.github.santiquiroz.blindside.phone.ui.belt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.santiquiroz.blindside.phone.bridge.PhoneBridge
import io.github.santiquiroz.blindside.phone.ui.PhoneActions
import io.github.santiquiroz.blindside.phone.ui.PhoneUiState
import io.github.santiquiroz.blindside.phone.ui.common.rememberNowMs
import io.github.santiquiroz.blindside.shared.bridge.watchSessionActive

@Composable
fun BeltTab(state: PhoneUiState, actions: PhoneActions, bridge: PhoneBridge) {
    val nowMs by rememberNowMs()
    val linkUp = phoneLinkUp(state.session)
    val identify = identifyBlock(linkUp, state.session.purpose, watchSessionActive(state.watchStatus, nowMs))
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Cinturón", style = MaterialTheme.typography.headlineSmall)
        LinkSection(state, nowMs, actions)
        DiagnosticSection(state.phone, state.session.running, linkUp, actions.refreshInfo, actions.startDiagnostic)
        ActionsSection(identify, restartBlock(linkUp), actions.sendCommand)
        PairingSection(bridge, state.prefs.linkSupport, actions.startRadar)
        SharedSettingsSection(state.settings, actions.updateSettings)
        PhoneSettingsSection(state.settings, state.prefs, actions)
    }
}
```

- [ ] **Step 8: Run every phone test and build**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest :phone-app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`, 69 phone tests pass.

- [ ] **Step 9: Commit**

```bash
cd /c/personal/blindside
git add watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/belt \
  watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/ui/belt
git commit -m "feat: pestaña Cinturón con diagnóstico legible, acciones, emparejamiento guiado por firmware y ajustes compartidos"
```

---

### Task 10: Recordings tab (local list, pull from the watch, share, delete)

**Files:**
- Create: `phone/recordings/RecordingFiles.kt`
- Create: `phone/recordings/RecordingsRepository.kt`
- Modify: `phone/bridge/BridgeFacts.kt` (append the download helpers)
- Modify: `phone/bridge/PhoneBridge.kt` (add `download` and `cancelDownload`)
- Create: `phone/ui/recordings/FetchState.kt`
- Create: `phone/ui/recordings/FetchFromWatchDialog.kt`
- Create: `phone/ui/recordings/ShareRecording.kt`
- Create: `phone/ui/recordings/RecordingsTab.kt`
- Create: `watch/phone-app/src/main/res/xml/recording_paths.xml`
- Modify: `watch/phone-app/src/main/AndroidManifest.xml`
- Test: `phoneTest/recordings/RecordingFilesTest.kt`, `phoneTest/recordings/RecordingsRepositoryTest.kt`, `phoneTest/bridge/DownloadFactsTest.kt`, `phoneTest/ui/recordings/FetchStateTest.kt`

**Interfaces:**
- Consumes:
  - Plan 05: `RecordingEntry(name, bytes, startEpochMs)`, `isRecordingFileName`, `recordingChannelPath`.
  - Task 7: `EmptyState`, `NumberText`, `MIN_TOUCH`, `formatBytes`, colour aliases.
  - Task 8: `PhoneBridge`, `BridgeResult`, `BRIDGE_REQUEST_TIMEOUT_MS`.
  - radar-core: `BsrecReader`.
- Produces:
  - Package `…phone.recordings`:
    - types `enum class RecordingOrigin { WATCH, PHONE, DEMO, UNKNOWN }`, `data class FileFacts(name, bytes, modifiedMs)`, `data class LocalRecording(name, bytes, modifiedMs, origin, startedAt: LocalDateTime?)`, `data class RemoteRow(entry: RecordingEntry, alreadyLocal: Boolean)`, `data class RowActions(canOpen, canShare, canDelete, note: String?)`;
    - constants `BSREC_MIME`, `RECORDING_NOW_NOTE`;
    - functions `originOfName`, `startedAtOf`, `originLabel`, `recordingTitle(name)`, `localRecordings(files)`, `remoteRows(entries, localNames)`, `markDownloaded(rows, name)`, `rowActions(name, activeName)`, `partFileName(name)`, `looksLikeBsrec(file)`, `finishDownload(part, target, expectedBytes): Boolean`;
    - `class RecordingsRepository(val dir: File)` with `list()`, `names()`, `file(name): File?`, `delete(name): Boolean`.
  - Package `…phone.bridge`:
    - `const val COPY_BUFFER_BYTES`, `DOWNLOAD_STALL_MS = 15_000L`, `STALL_CHECK_EVERY_MS`;
    - `fun stalled(lastProgressMs: Long, nowMs: Long): Boolean`, `enum class DownloadVerdict { COMPLETE, INCOMPLETE, STALLED, CANCELLED }`, `fun downloadVerdict(copiedBytes, expectedBytes, timedOut, cancelled): DownloadVerdict`, `fun verdictReason(verdict): String`, `fun copyStream(input, output, onProgress, bufferBytes = COPY_BUFFER_BYTES): Long`;
    - `PhoneBridge.download(entry, dir, onProgress): BridgeResult<File>`, `PhoneBridge.cancelDownload()`.
  - Package `…phone.ui.recordings`: `sealed interface FetchState { Loading; Listed(rows, note); Downloading(rows, name, receivedBytes, totalBytes); Problem(text) }`, `listedState(result, localNames)`, `afterDownload(rows, name, result)`, `currentRows(state)`, `downloadProgress(received, total)`, `canDismiss(state)`, `canCancel(state)`, `NO_WATCH_TEXT`, `NO_REMOTE_RECORDINGS`; `fun shareRecording(context, file)`; `@Composable RecordingsTab(repository, bridge, activeName: String?, onOpen: (String) -> Unit, onDeleted: (String) -> Unit)`.

- [ ] **Step 1: Write the failing tests**

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/recordings/RecordingFilesTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.recordings

import io.github.santiquiroz.blindside.core.replay.BsrecWriter
import io.github.santiquiroz.blindside.shared.recording.RecordingEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File

class RecordingFilesTest {
    private val name = "blindside-belt-20261001-153012.bsrec"

    private fun validBsrec(): ByteArray = ByteArrayOutputStream().also { BsrecWriter(it, "{}").close() }.toByteArray()

    @Test
    fun `titles name the device and the start time`() {
        assertEquals("Reloj · 01/10/2026 · 15:30", recordingTitle(name))
        assertEquals("Celular · 01/10/2026 · 09:05", recordingTitle("blindside-phone-20261001-090500.bsrec"))
        assertEquals("Demo · 30/09/2026 · 23:59", recordingTitle("blindside-demo-20260930-235959.bsrec"))
        assertEquals("custom.bsrec", recordingTitle("custom.bsrec"))
    }

    @Test
    fun `the local list keeps only recording names`() {
        val files = listOf(
            FileFacts(name, 10, 1),
            FileFacts("$name.part", 5, 2),
            FileFacts("notes.txt", 1, 3),
            FileFacts("../escape.bsrec", 1, 4),
            FileFacts("custom.bsrec", 1, 5),
        )
        assertEquals(listOf(name), localRecordings(files).map { it.name })
    }

    @Test
    fun `the newest recording comes first`() {
        val files = listOf(
            FileFacts("blindside-belt-20260930-120000.bsrec", 1, modifiedMs = 9_999),
            FileFacts("blindside-phone-20261001-080000.bsrec", 1, modifiedMs = 2),
            FileFacts("blindside-demo-20260930-235959.bsrec", 1, modifiedMs = 1),
        )
        val expected = listOf("blindside-phone-20261001-080000.bsrec", "blindside-demo-20260930-235959.bsrec", "blindside-belt-20260930-120000.bsrec")
        assertEquals(expected, localRecordings(files).map { it.name })
    }

    @Test
    fun `remote rows mark what is already here, drop unsafe names and put the newest first`() {
        val old = RecordingEntry("blindside-belt-20261001-100000.bsrec", 1, 100)
        val new = RecordingEntry("blindside-belt-20261001-110000.bsrec", 2, 200)
        val rows = remoteRows(listOf(old, new, RecordingEntry("../x.bsrec", 3, 300)), localNames = setOf(old.name))
        assertEquals(listOf(new.name to false, old.name to true), rows.map { it.entry.name to it.alreadyLocal })
        assertEquals(listOf(true, true), markDownloaded(rows, new.name).map { it.alreadyLocal })
    }

    @Test
    fun `the recording being written can only be watched growing`() {
        assertEquals(RowActions(canOpen = false, canShare = false, canDelete = false, note = RECORDING_NOW_NOTE), rowActions(name, activeName = name))
        assertEquals(RowActions(canOpen = true, canShare = true, canDelete = true, note = null), rowActions(name, activeName = "other.bsrec"))
        assertEquals(RowActions(canOpen = true, canShare = true, canDelete = true, note = null), rowActions(name, activeName = null))
    }

    @Test
    fun `a complete download becomes the recording and leaves no part file`(@TempDir dir: File) {
        val bytes = validBsrec()
        val part = File(dir, partFileName(name)).apply { writeBytes(bytes) }
        val target = File(dir, name)
        assertTrue(finishDownload(part, target, expectedBytes = bytes.size.toLong()))
        assertTrue(target.isFile)
        assertFalse(part.exists())
    }

    @Test
    fun `a cut download is discarded and never replaces an existing recording`(@TempDir dir: File) {
        val part = File(dir, partFileName(name)).apply { writeBytes(byteArrayOf(0x42, 0x53)) }
        val target = File(dir, name).apply { writeBytes(validBsrec()) }
        assertFalse(finishDownload(part, target, expectedBytes = 2))
        assertFalse(part.exists())
        assertTrue(looksLikeBsrec(target))
    }

    @Test
    fun `a part with a valid header but fewer bytes than listed is discarded`(@TempDir dir: File) {
        val headerOnly = validBsrec()
        val part = File(dir, partFileName(name)).apply { writeBytes(headerOnly) }
        assertFalse(finishDownload(part, File(dir, name), expectedBytes = headerOnly.size + 4_096L))
        assertFalse(part.exists())
        assertFalse(File(dir, name).exists())
    }

    @Test
    fun `an empty or missing file is not a recording`(@TempDir dir: File) {
        assertFalse(looksLikeBsrec(File(dir, "empty.bsrec").apply { writeBytes(ByteArray(0)) }))
        assertFalse(looksLikeBsrec(File(dir, "missing.bsrec")))
    }
}
```

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/recordings/RecordingsRepositoryTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.recordings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RecordingsRepositoryTest {
    private val a = "blindside-belt-20261001-100000.bsrec"
    private val b = "blindside-belt-20261001-110000.bsrec"

    @Test
    fun `lists recordings and ignores parts, folders and other files`(@TempDir dir: File) {
        File(dir, a).writeText("x")
        File(dir, "$b.part").writeText("x")
        File(dir, "c.txt").writeText("x")
        File(dir, "blindside-belt-20261001-120000.bsrec").mkdirs()
        val repository = RecordingsRepository(dir)
        assertEquals(listOf(a), repository.list().map { it.name })
        assertEquals(setOf(a), repository.names())
    }

    @Test
    fun `an unsafe or missing name never resolves to a file`(@TempDir dir: File) {
        File(dir, a).writeText("x")
        val repository = RecordingsRepository(File(dir, "sub").apply { mkdirs() })
        assertNull(repository.file("../$a"))
        assertNull(repository.file(b))
    }

    @Test
    fun `deleting removes only the named recording`(@TempDir dir: File) {
        File(dir, a).writeText("x")
        File(dir, b).writeText("x")
        val repository = RecordingsRepository(dir)
        assertTrue(repository.delete(a))
        assertFalse(repository.delete(a))
        assertEquals(setOf(b), repository.names())
    }

    @Test
    fun `a missing folder lists nothing`(@TempDir dir: File) {
        assertEquals(emptyList<LocalRecording>(), RecordingsRepository(File(dir, "nope")).list())
    }
}
```

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/bridge/DownloadFactsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.bridge

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DownloadFactsTest {
    @Test
    fun `a transfer stalls only after 15 s without a byte, however long it is`() {
        assertFalse(stalled(lastProgressMs = 1_000L, nowMs = 15_999L))
        assertTrue(stalled(lastProgressMs = 1_000L, nowMs = 16_000L))
    }

    @Test
    fun `copying reports growing progress and copies every byte`() {
        val source = ByteArray(200_000) { (it % 251).toByte() }
        val target = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        val copied = copyStream(ByteArrayInputStream(source), target, onProgress = { progress += it })
        assertEquals(200_000L, copied)
        assertArrayEquals(source, target.toByteArray())
        assertEquals(200_000L, progress.last())
        assertEquals(progress.sorted(), progress)
    }

    @Test
    fun `only every listed byte with no stall and no cancel completes a download`() {
        assertEquals(DownloadVerdict.COMPLETE, downloadVerdict(copiedBytes = 10, expectedBytes = 10, timedOut = false, cancelled = false))
        assertEquals(DownloadVerdict.INCOMPLETE, downloadVerdict(copiedBytes = 9, expectedBytes = 10, timedOut = false, cancelled = false))
        assertEquals(DownloadVerdict.STALLED, downloadVerdict(copiedBytes = 10, expectedBytes = 10, timedOut = true, cancelled = false))
        assertEquals(DownloadVerdict.CANCELLED, downloadVerdict(copiedBytes = 3, expectedBytes = 10, timedOut = true, cancelled = true))
    }

    @Test
    fun `every failed verdict explains itself`() {
        DownloadVerdict.entries.filter { it != DownloadVerdict.COMPLETE }.forEach { assertTrue(verdictReason(it).isNotBlank()) }
    }
}
```

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/ui/recordings/FetchStateTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.recordings

import io.github.santiquiroz.blindside.phone.bridge.BridgeResult
import io.github.santiquiroz.blindside.phone.recordings.RemoteRow
import io.github.santiquiroz.blindside.shared.recording.RecordingEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class FetchStateTest {
    private val here = RecordingEntry("blindside-belt-20261001-100000.bsrec", 10, 2)
    private val there = RecordingEntry("blindside-belt-20261001-110000.bsrec", 20, 3)
    private val rows = listOf(RemoteRow(there, false), RemoteRow(here, true))

    @Test
    fun `a listed reply marks what is already on the phone`() {
        assertEquals(FetchState.Listed(rows), listedState(BridgeResult.Ok(listOf(here, there)), setOf(here.name)))
    }

    @Test
    fun `an empty watch says so`() {
        assertEquals(FetchState.Listed(emptyList(), NO_REMOTE_RECORDINGS), listedState(BridgeResult.Ok(emptyList()), emptySet()))
    }

    @Test
    fun `no watch or no answer ends in a message`() {
        assertEquals(FetchState.Problem(NO_WATCH_TEXT), listedState(BridgeResult.NoWatch, emptySet()))
        val failed = listedState(BridgeResult.Failed("sin respuesta a tiempo"), emptySet())
        assertTrue(failed is FetchState.Problem && "sin respuesta a tiempo" in failed.text)
    }

    @Test
    fun `a finished download marks its row and says so`() {
        val state = afterDownload(rows, there.name, BridgeResult.Ok(File(there.name)))
        assertEquals(listOf(true, true), currentRows(state).map { it.alreadyLocal })
        assertTrue((state as FetchState.Listed).note!!.startsWith("Traída"))
    }

    @Test
    fun `a failed download keeps the rows and explains why`() {
        val state = afterDownload(rows, there.name, BridgeResult.Failed("descarga incompleta"))
        assertEquals(rows, currentRows(state))
        assertTrue("descarga incompleta" in (state as FetchState.Listed).note!!)
    }

    @Test
    fun `progress stays between zero and one`() {
        assertEquals(0.5f, downloadProgress(5, 10))
        assertEquals(1f, downloadProgress(20, 10))
        assertEquals(0f, downloadProgress(5, 0))
    }

    @Test
    fun `the dialog cannot be dismissed in the middle of a download`() {
        assertFalse(canDismiss(FetchState.Downloading(rows, there.name, 1, 20)))
        assertTrue(canDismiss(FetchState.Loading))
    }

    @Test
    fun `a download can always be cancelled and nothing else offers cancel`() {
        assertTrue(canCancel(FetchState.Downloading(rows, there.name, 1, 20)))
        assertFalse(canCancel(FetchState.Listed(rows)))
        assertFalse(canCancel(FetchState.Loading))
    }
}
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.phone.recordings.*" --tests "io.github.santiquiroz.blindside.phone.bridge.DownloadFactsTest" --tests "io.github.santiquiroz.blindside.phone.ui.recordings.*" --console=plain`
Expected: FAIL with `Unresolved reference 'recordingTitle'`.

- [ ] **Step 3: Write the recording files logic and the repository**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/recordings/RecordingFiles.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.recordings

import io.github.santiquiroz.blindside.core.replay.BsrecReader
import io.github.santiquiroz.blindside.shared.recording.RecordingEntry
import io.github.santiquiroz.blindside.shared.recording.isRecordingFileName
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class RecordingOrigin { WATCH, PHONE, DEMO, UNKNOWN }

data class FileFacts(val name: String, val bytes: Long, val modifiedMs: Long)

data class LocalRecording(
    val name: String,
    val bytes: Long,
    val modifiedMs: Long,
    val origin: RecordingOrigin,
    val startedAt: LocalDateTime?,
)

data class RemoteRow(val entry: RecordingEntry, val alreadyLocal: Boolean)

data class RowActions(val canOpen: Boolean, val canShare: Boolean, val canDelete: Boolean, val note: String?)

const val BSREC_MIME = "application/octet-stream"
const val RECORDING_NOW_NOTE = "Grabando…"

private const val PART_SUFFIX = ".part"
private val NAME_PATTERN = Regex("^blindside-([a-z]+)-(\\d{8})-(\\d{6})\\.bsrec$")
private val NAME_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
private val TITLE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy · HH:mm")
private val NEWEST_FIRST: Comparator<LocalRecording> =
    compareByDescending<LocalRecording> { it.startedAt }.thenByDescending { it.modifiedMs }
private val IDLE_ROW = RowActions(canOpen = true, canShare = true, canDelete = true, note = null)
private val GROWING_ROW = RowActions(canOpen = false, canShare = false, canDelete = false, note = RECORDING_NOW_NOTE)

fun originOfName(name: String): RecordingOrigin = when (NAME_PATTERN.matchEntire(name)?.groupValues?.get(1)) {
    "belt" -> RecordingOrigin.WATCH
    "phone" -> RecordingOrigin.PHONE
    "demo" -> RecordingOrigin.DEMO
    else -> RecordingOrigin.UNKNOWN
}

fun startedAtOf(name: String): LocalDateTime? {
    val match = NAME_PATTERN.matchEntire(name) ?: return null
    return runCatching { LocalDateTime.parse(match.groupValues[2] + match.groupValues[3], NAME_STAMP) }.getOrNull()
}

fun originLabel(origin: RecordingOrigin): String = when (origin) {
    RecordingOrigin.WATCH -> "Reloj"
    RecordingOrigin.PHONE -> "Celular"
    RecordingOrigin.DEMO -> "Demo"
    RecordingOrigin.UNKNOWN -> "Otra"
}

fun recordingTitle(name: String): String =
    startedAtOf(name)?.let { "${originLabel(originOfName(name))} · ${TITLE_STAMP.format(it)}" } ?: name

fun localRecordings(files: List<FileFacts>): List<LocalRecording> =
    files.filter { isRecordingFileName(it.name) }.map(::localRecording).sortedWith(NEWEST_FIRST)

fun remoteRows(entries: List<RecordingEntry>, localNames: Set<String>): List<RemoteRow> =
    entries.filter { isRecordingFileName(it.name) }.sortedByDescending { it.startEpochMs }.map { RemoteRow(it, it.name in localNames) }

fun markDownloaded(rows: List<RemoteRow>, name: String): List<RemoteRow> =
    rows.map { if (it.entry.name == name) it.copy(alreadyLocal = true) else it }

// The file being written is still growing: opening it reads a moving target, and sharing or deleting it pulls it from under the recorder.
fun rowActions(name: String, activeName: String?): RowActions = if (name == activeName) GROWING_ROW else IDLE_ROW

fun partFileName(name: String): String = name + PART_SUFFIX

fun looksLikeBsrec(file: File): Boolean = runCatching { file.inputStream().use { BsrecReader(it).headerJson } }.isSuccess

// A part becomes a recording only with every listed byte and a readable header, so a cut transfer never reaches the list.
fun finishDownload(part: File, target: File, expectedBytes: Long): Boolean {
    if (part.length() != expectedBytes || !looksLikeBsrec(part)) return discard(part)
    target.delete()
    return part.renameTo(target)
}

private fun localRecording(file: FileFacts): LocalRecording =
    LocalRecording(file.name, file.bytes, file.modifiedMs, originOfName(file.name), startedAtOf(file.name))

private fun discard(part: File): Boolean {
    part.delete()
    return false
}
```

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/recordings/RecordingsRepository.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.recordings

import io.github.santiquiroz.blindside.shared.recording.isRecordingFileName
import java.io.File

class RecordingsRepository(val dir: File) {
    fun list(): List<LocalRecording> =
        localRecordings(dir.listFiles().orEmpty().filter { it.isFile }.map { FileFacts(it.name, it.length(), it.lastModified()) })

    fun names(): Set<String> = list().map { it.name }.toSet()

    fun file(name: String): File? = name.takeIf(::isRecordingFileName)?.let { File(dir, it) }?.takeIf { it.isFile }

    fun delete(name: String): Boolean = file(name)?.delete() == true
}
```

- [ ] **Step 4: Append the download helpers to the bridge facts**

Append to `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/bridge/BridgeFacts.kt`, and add `import java.io.InputStream` and `import java.io.OutputStream` at the top:

```kotlin
const val COPY_BUFFER_BYTES = 64 * 1_024
const val DOWNLOAD_STALL_MS = 15_000L
const val STALL_CHECK_EVERY_MS = 1_000L

enum class DownloadVerdict { COMPLETE, INCOMPLETE, STALLED, CANCELLED }

// A Bluetooth transfer can crawl through 53 MB, so only silence ends it (no byte for 15 s), never its total length.
fun stalled(lastProgressMs: Long, nowMs: Long): Boolean = nowMs - lastProgressMs >= DOWNLOAD_STALL_MS

fun downloadVerdict(copiedBytes: Long, expectedBytes: Long, timedOut: Boolean, cancelled: Boolean): DownloadVerdict = when {
    cancelled -> DownloadVerdict.CANCELLED
    timedOut -> DownloadVerdict.STALLED
    copiedBytes != expectedBytes -> DownloadVerdict.INCOMPLETE
    else -> DownloadVerdict.COMPLETE
}

fun verdictReason(verdict: DownloadVerdict): String = when (verdict) {
    DownloadVerdict.COMPLETE -> "completa"
    DownloadVerdict.INCOMPLETE -> "descarga incompleta"
    DownloadVerdict.STALLED -> "el reloj dejó de enviar datos"
    DownloadVerdict.CANCELLED -> "descarga cancelada"
}

fun copyStream(input: InputStream, output: OutputStream, onProgress: (Long) -> Unit, bufferBytes: Int = COPY_BUFFER_BYTES): Long {
    val buffer = ByteArray(bufferBytes)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return total
        output.write(buffer, 0, read)
        total += read
        onProgress(total)
    }
}
```

- [ ] **Step 5: Write the fetch states**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/recordings/FetchState.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.recordings

import io.github.santiquiroz.blindside.phone.bridge.BridgeResult
import io.github.santiquiroz.blindside.phone.recordings.RemoteRow
import io.github.santiquiroz.blindside.phone.recordings.markDownloaded
import io.github.santiquiroz.blindside.phone.recordings.recordingTitle
import io.github.santiquiroz.blindside.phone.recordings.remoteRows
import io.github.santiquiroz.blindside.shared.recording.RecordingEntry
import java.io.File

sealed interface FetchState {
    data object Loading : FetchState
    data class Listed(val rows: List<RemoteRow>, val note: String? = null) : FetchState
    data class Downloading(val rows: List<RemoteRow>, val name: String, val receivedBytes: Long, val totalBytes: Long) : FetchState
    data class Problem(val text: String) : FetchState
}

const val NO_WATCH_TEXT = "No hay un reloj conectado. Revisa la conexión en Galaxy Wearable y vuelve a intentarlo."
const val NO_REMOTE_RECORDINGS = "El reloj no tiene grabaciones terminadas."

fun listedState(result: BridgeResult<List<RecordingEntry>>, localNames: Set<String>): FetchState = when (result) {
    is BridgeResult.Ok -> FetchState.Listed(remoteRows(result.value, localNames), if (result.value.isEmpty()) NO_REMOTE_RECORDINGS else null)
    BridgeResult.NoWatch -> FetchState.Problem(NO_WATCH_TEXT)
    is BridgeResult.Failed -> FetchState.Problem("El reloj no respondió (${result.reason}). Actualiza Blindside en el reloj e inténtalo otra vez.")
}

fun afterDownload(rows: List<RemoteRow>, name: String, result: BridgeResult<File>): FetchState = when (result) {
    is BridgeResult.Ok -> FetchState.Listed(markDownloaded(rows, name), "Traída: ${recordingTitle(name)}")
    BridgeResult.NoWatch -> FetchState.Listed(rows, "El reloj se desconectó: no se trajo ${recordingTitle(name)}.")
    is BridgeResult.Failed -> FetchState.Listed(rows, "No se pudo traer ${recordingTitle(name)} (${result.reason}).")
}

fun currentRows(state: FetchState): List<RemoteRow> = when (state) {
    is FetchState.Listed -> state.rows
    is FetchState.Downloading -> state.rows
    else -> emptyList()
}

fun downloadProgress(received: Long, total: Long): Float = if (total <= 0L) 0f else (received.toFloat() / total).coerceIn(0f, 1f)

fun canDismiss(state: FetchState): Boolean = state !is FetchState.Downloading

fun canCancel(state: FetchState): Boolean = state is FetchState.Downloading
```

- [ ] **Step 6: Run the pure tests**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.phone.recordings.*" --tests "io.github.santiquiroz.blindside.phone.bridge.DownloadFactsTest" --tests "io.github.santiquiroz.blindside.phone.ui.recordings.*" --console=plain`
Expected: PASS (9 + 4 + 4 + 8 tests).

- [ ] **Step 7: Add the download to the bridge**

In `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/bridge/PhoneBridge.kt`:

Add these imports:

```kotlin
import android.os.SystemClock
import com.google.android.gms.wearable.ChannelClient
import io.github.santiquiroz.blindside.phone.recordings.finishDownload
import io.github.santiquiroz.blindside.phone.recordings.partFileName
import io.github.santiquiroz.blindside.shared.bridge.recordingChannelPath
import io.github.santiquiroz.blindside.shared.recording.isRecordingFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
```

Add next to the other private constants:

```kotlin
private const val UNSAFE_NAME = "nombre no válido"
private const val CORRUPT_DOWNLOAD = "archivo dañado"

private data class CopyOutcome(val copiedBytes: Long, val timedOut: Boolean)
```

Add two fields after `private val data = Wearable.getDataClient(context)`:

```kotlin
    private val channels = Wearable.getChannelClient(context)
    @Volatile private var activeDownload: ChannelClient.Channel? = null
    @Volatile private var cancelRequested = false
```

Add after `requestOpenPairing()`:

```kotlin
    suspend fun download(entry: RecordingEntry, dir: File, onProgress: (Long) -> Unit): BridgeResult<File> {
        if (!isRecordingFileName(entry.name)) return BridgeResult.Failed(UNSAFE_NAME)
        cancelRequested = false
        return withWatch { node ->
            val channel = withTimeout(BRIDGE_REQUEST_TIMEOUT_MS) { channels.openChannel(node, recordingChannelPath(entry.name)).await() }
            activeDownload = channel
            try {
                receive(channel, entry, dir, onProgress)
            } finally {
                activeDownload = null
                channels.close(channel)
            }
        }
    }

    // Closing the channel is the only way to unblock a ChannelClient read; the copy then ends short and is discarded.
    fun cancelDownload() {
        cancelRequested = true
        activeDownload?.let { channels.close(it) }
    }

    private suspend fun receive(channel: ChannelClient.Channel, entry: RecordingEntry, dir: File, onProgress: (Long) -> Unit): BridgeResult<File> {
        if (cancelRequested) return BridgeResult.Failed(verdictReason(DownloadVerdict.CANCELLED))
        dir.mkdirs()
        val part = File(dir, partFileName(entry.name))
        val input = withTimeout(BRIDGE_REQUEST_TIMEOUT_MS) { channels.getInputStream(channel).await() }
        val outcome = copyWithWatchdog(channel, input, part, onProgress)
        val verdict = downloadVerdict(outcome.copiedBytes, entry.bytes, outcome.timedOut, cancelRequested)
        return settle(verdict, part, File(dir, entry.name), entry.bytes)
    }

    private fun settle(verdict: DownloadVerdict, part: File, target: File, expectedBytes: Long): BridgeResult<File> {
        if (verdict != DownloadVerdict.COMPLETE) {
            part.delete()
            return BridgeResult.Failed(verdictReason(verdict))
        }
        return if (finishDownload(part, target, expectedBytes)) BridgeResult.Ok(target) else BridgeResult.Failed(CORRUPT_DOWNLOAD)
    }

    private suspend fun copyWithWatchdog(
        channel: ChannelClient.Channel,
        input: InputStream,
        part: File,
        onProgress: (Long) -> Unit,
    ): CopyOutcome = coroutineScope {
        val lastProgressMs = AtomicLong(SystemClock.elapsedRealtime())
        val timedOut = AtomicBoolean(false)
        val watchdog = launch { closeWhenStalled(channel, lastProgressMs, timedOut) }
        try {
            val copied = withContext(Dispatchers.IO) { copyToPart(input, part, lastProgressMs, onProgress) }
            CopyOutcome(copied, timedOut.get())
        } finally {
            watchdog.cancel()
        }
    }

    // A closed or broken channel either ends the stream early or throws; both leave a short copy that the verdict rejects.
    private fun copyToPart(input: InputStream, part: File, lastProgressMs: AtomicLong, onProgress: (Long) -> Unit): Long {
        var copied = 0L
        val track: (Long) -> Unit = { total ->
            copied = total
            lastProgressMs.set(SystemClock.elapsedRealtime())
            onProgress(total)
        }
        try {
            input.use { source -> part.outputStream().use { copyStream(source, it, track) } }
        } catch (error: IOException) {
            Log.w(TAG, "transfer interrupted after $copied bytes", error)
        }
        return copied
    }

    private suspend fun closeWhenStalled(channel: ChannelClient.Channel, lastProgressMs: AtomicLong, timedOut: AtomicBoolean) {
        while (!stalled(lastProgressMs.get(), SystemClock.elapsedRealtime())) delay(STALL_CHECK_EVERY_MS)
        timedOut.set(true)
        channels.close(channel)
    }
```

- [ ] **Step 8: Expose recordings through a FileProvider**

Create `watch/phone-app/src/main/res/xml/recording_paths.xml`:

```xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <external-files-path name="recordings" path="recordings/" />
    <files-path name="recordings_internal" path="recordings/" />
</paths>
```

In `watch/phone-app/src/main/AndroidManifest.xml`, add inside `<application>` after the `PhoneBridgeListenerService` entry:

```xml
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.files"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/recording_paths" />
        </provider>
```

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/recordings/ShareRecording.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.recordings

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.santiquiroz.blindside.phone.recordings.BSREC_MIME
import java.io.File

private const val FILE_PROVIDER_SUFFIX = ".files"

fun shareRecording(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(BSREC_MIME)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Compartir grabación"))
}
```

- [ ] **Step 9: Write the fetch dialog and the tab**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/recordings/FetchFromWatchDialog.kt`. While a download runs, the dialog cannot be dismissed, but "Cancelar" closes the channel and the part is deleted:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.recordings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.santiquiroz.blindside.phone.bridge.PhoneBridge
import io.github.santiquiroz.blindside.phone.recordings.RecordingsRepository
import io.github.santiquiroz.blindside.phone.recordings.RemoteRow
import io.github.santiquiroz.blindside.phone.recordings.recordingTitle
import io.github.santiquiroz.blindside.phone.ui.common.MIN_TOUCH
import io.github.santiquiroz.blindside.phone.ui.common.NumberText
import io.github.santiquiroz.blindside.phone.ui.common.formatBytes
import io.github.santiquiroz.blindside.phone.ui.theme.Text2Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LIST_MAX_HEIGHT = 360.dp

@Composable
fun FetchFromWatchDialog(bridge: PhoneBridge, repository: RecordingsRepository, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<FetchState>(FetchState.Loading) }
    LaunchedEffect(Unit) {
        val local = withContext(Dispatchers.IO) { repository.names() }
        state = listedState(bridge.listRecordings(), local)
    }
    val download: (RemoteRow) -> Unit = { row ->
        scope.launch {
            val rows = currentRows(state)
            state = FetchState.Downloading(rows, row.entry.name, 0L, row.entry.bytes)
            val result = bridge.download(row.entry, repository.dir) { received ->
                state = FetchState.Downloading(rows, row.entry.name, received, row.entry.bytes)
            }
            state = afterDownload(rows, row.entry.name, result)
        }
    }
    AlertDialog(
        onDismissRequest = { if (canDismiss(state)) onDismiss() },
        confirmButton = { TextButton(onClick = onDismiss, enabled = canDismiss(state)) { Text("Cerrar") } },
        dismissButton = { if (canCancel(state)) TextButton(onClick = bridge::cancelDownload) { Text("Cancelar") } },
        title = { Text("Grabaciones del reloj") },
        text = { FetchBody(state, download) },
    )
}

@Composable
private fun FetchBody(state: FetchState, onDownload: (RemoteRow) -> Unit) {
    when (state) {
        FetchState.Loading -> AskingWatch()
        is FetchState.Problem -> Text(state.text)
        is FetchState.Downloading -> DownloadProgress(state)
        is FetchState.Listed -> RemoteList(state, onDownload)
    }
}

@Composable
private fun AskingWatch() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp))
        Text("Preguntando al reloj…")
    }
}

@Composable
private fun DownloadProgress(state: FetchState.Downloading) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Trayendo ${recordingTitle(state.name)}")
        LinearProgressIndicator(progress = { downloadProgress(state.receivedBytes, state.totalBytes) }, modifier = Modifier.fillMaxWidth())
        NumberText("${formatBytes(state.receivedBytes)} de ${formatBytes(state.totalBytes)}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RemoteList(state: FetchState.Listed, onDownload: (RemoteRow) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.note?.let { Text(it, color = Text2Color) }
        LazyColumn(Modifier.heightIn(max = LIST_MAX_HEIGHT)) {
            items(state.rows, key = { it.entry.name }) { RemoteLine(it, onDownload) }
        }
    }
}

@Composable
private fun RemoteLine(row: RemoteRow, onDownload: (RemoteRow) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(recordingTitle(row.entry.name), style = MaterialTheme.typography.bodyMedium)
            NumberText(formatBytes(row.entry.bytes), style = MaterialTheme.typography.bodySmall, color = Text2Color)
        }
        if (row.alreadyLocal) Text("Ya está", color = Text2Color) else TextButton(onClick = { onDownload(row) }) { Text("Traer") }
    }
}
```

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/recordings/RecordingsTab.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.recordings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.santiquiroz.blindside.phone.bridge.PhoneBridge
import io.github.santiquiroz.blindside.phone.recordings.LocalRecording
import io.github.santiquiroz.blindside.phone.recordings.RecordingsRepository
import io.github.santiquiroz.blindside.phone.recordings.RowActions
import io.github.santiquiroz.blindside.phone.recordings.recordingTitle
import io.github.santiquiroz.blindside.phone.recordings.rowActions
import io.github.santiquiroz.blindside.phone.ui.common.EmptyState
import io.github.santiquiroz.blindside.phone.ui.common.MIN_TOUCH
import io.github.santiquiroz.blindside.phone.ui.common.NumberText
import io.github.santiquiroz.blindside.phone.ui.common.formatBytes
import io.github.santiquiroz.blindside.phone.ui.theme.AlertRedColor
import io.github.santiquiroz.blindside.phone.ui.theme.RingColor
import io.github.santiquiroz.blindside.phone.ui.theme.SurfaceColor
import io.github.santiquiroz.blindside.phone.ui.theme.Text2Color
import io.github.santiquiroz.blindside.phone.ui.theme.TextColor
import io.github.santiquiroz.blindside.phone.ui.theme.WarnColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val FETCH_FROM_WATCH_LABEL = "Traer del reloj"
private const val NO_RECORDINGS_TEXT = "Aún no hay grabaciones en este celular. Trae las del reloj o inicia el radar."

private data class RecordingActions(
    val activeName: String?,
    val onOpen: (String) -> Unit,
    val onShare: (LocalRecording) -> Unit,
    val onDelete: (LocalRecording) -> Unit,
)

@Composable
fun RecordingsTab(
    repository: RecordingsRepository,
    bridge: PhoneBridge,
    activeName: String?,
    onOpen: (String) -> Unit,
    onDeleted: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val recordings by produceState<List<LocalRecording>?>(null, version, activeName) { value = withContext(Dispatchers.IO) { repository.list() } }
    var fetching by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<LocalRecording?>(null) }
    val actions = RecordingActions(
        activeName = activeName,
        onOpen = onOpen,
        onShare = { recording -> repository.file(recording.name)?.let { shareRecording(context, it) } },
        onDelete = { deleting = it },
    )
    RecordingsBody(recordings, actions, onFetch = { fetching = true })
    if (fetching) {
        FetchFromWatchDialog(bridge, repository, onDismiss = {
            fetching = false
            version++
        })
    }
    deleting?.let { target ->
        DeleteDialog(
            recording = target,
            onConfirm = {
                deleting = null
                scope.launch {
                    withContext(Dispatchers.IO) { repository.delete(target.name) }
                    onDeleted(target.name)
                    version++
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun RecordingsBody(recordings: List<LocalRecording>?, actions: RecordingActions, onFetch: () -> Unit) {
    when {
        recordings == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        recordings.isEmpty() -> EmptyState(NO_RECORDINGS_TEXT, FETCH_FROM_WATCH_LABEL, Icons.Filled.FolderOpen, onFetch)
        else -> RecordingList(recordings, actions, onFetch)
    }
}

@Composable
private fun RecordingList(recordings: List<LocalRecording>, actions: RecordingActions, onFetch: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Grabaciones", style = MaterialTheme.typography.headlineSmall) }
        item { FetchButton(onFetch) }
        items(recordings, key = { it.name }) { RecordingCard(it, actions, rowActions(it.name, actions.activeName)) }
    }
}

@Composable
private fun FetchButton(onFetch: () -> Unit) {
    Button(onClick = onFetch, modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH)) {
        Icon(Icons.Filled.Download, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(FETCH_FROM_WATCH_LABEL)
    }
}

@Composable
private fun RecordingCard(recording: LocalRecording, actions: RecordingActions, allowed: RowActions) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceColor, contentColor = TextColor),
        border = BorderStroke(1.dp, RingColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = MIN_TOUCH)
                .clickable(enabled = allowed.canOpen, onClickLabel = "Abrir en el visor") { actions.onOpen(recording.name) }
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(recordingTitle(recording.name), style = MaterialTheme.typography.titleSmall)
                NumberText(formatBytes(recording.bytes), style = MaterialTheme.typography.bodySmall, color = Text2Color)
                allowed.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = WarnColor) }
            }
            if (allowed.canShare) IconButton(onClick = { actions.onShare(recording) }) { Icon(Icons.Filled.Share, contentDescription = "Compartir") }
            if (allowed.canDelete) IconButton(onClick = { actions.onDelete(recording) }) { Icon(Icons.Filled.Delete, contentDescription = "Borrar") }
        }
    }
}

@Composable
private fun DeleteDialog(recording: LocalRecording, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text("Borrar", color = AlertRedColor) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        title = { Text("¿Borrar esta grabación?") },
        text = { Text("${recordingTitle(recording.name)}. No se puede deshacer.") },
    )
}
```

- [ ] **Step 10: Run every phone test and build**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest :phone-app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`, 94 phone tests pass.

- [ ] **Step 11: Commit**

```bash
cd /c/personal/blindside
git add watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/recordings \
  watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/bridge \
  watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/recordings \
  watch/phone-app/src/main/res/xml/recording_paths.xml watch/phone-app/src/main/AndroidManifest.xml \
  watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/recordings \
  watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/bridge/DownloadFactsTest.kt \
  watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/ui/recordings
git commit -m "feat: pestaña Grabaciones con traer del reloj, descarga verificada y cancelable, compartir y borrar"
```

---

### Task 11: Hardened `.bsrec` reader and the viewer's replay engine (playback clock and seekable cursor)

**Files:**
- Modify: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/replay/Replay.kt` (make `replayRecord` public)
- Modify: `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/replay/Bsrec.kt` (`BsrecReader`: header cap, iterative skip of unknown records)
- Test: create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/replay/ReplayRecordTest.kt`; modify `.../core/replay/BsrecTest.kt`
- Create: `phone/viewer/Playback.kt`
- Create: `phone/viewer/ReplayCursor.kt`
- Test: `phoneTest/viewer/TestRecordings.kt` (test helper), `phoneTest/viewer/PlaybackTest.kt`, `phoneTest/viewer/ReplayCursorTest.kt`

**Interfaces:**
- Consumes: radar-core `RadarPipeline`, `BsrecReader`, `BsrecRecord`, `BsrecWriter`, `BSREC_MAGIC`, `BSREC_FORMAT_VERSION`, `pipelineConfigFromHeader`, `replayRecording`, `simulate`, `simulateWatchGyro`, `Scenarios`.
- Produces:
  - radar-core:
    - `fun replayRecord(record: BsrecRecord, pipeline: RadarPipeline, nanos: Long): List<PipelineEvent>` (was private; same name and body);
    - `const val MAX_BSREC_HEADER_BYTES = 1 MiB` and `class BsrecReader(input: InputStream, maxHeaderBytes: Long = MAX_BSREC_HEADER_BYTES)`. A header length above the cap throws `IllegalArgumentException` before anything is allocated, and unknown record types are skipped in a loop instead of by recursion.
  - Package `…phone.viewer`: `val PLAYBACK_SPEEDS: List<Int> = listOf(1, 2, 4, 8)`, `data class Playback(positionMs = 0L, durationMs = 0L, speed = 1, playing = false)`, `fun advanced(playback, wallDeltaMs): Playback`, `fun toggledPlay(playback): Playback`, `fun withSpeed(playback, speed): Playback`, `fun seekedTo(playback, positionMs): Playback`, `internal const val NANOS_PER_MS = 1_000_000L`, and `class ReplayCursor(open: () -> InputStream) : Closeable` with `val positionMs: Long`, `fun needsRestart(targetMs: Long): Boolean`, `fun seekTo(targetMs: Long): RadarScene`, `fun close()`.
  - Test helpers (package `…phone.viewer`, test source set): `fun headerFor(scenario: Scenario): String`, `fun simulatedRecords(scenario: Scenario): List<BsrecRecord>`, `fun recordingBytes(headerJson: String, records: List<BsrecRecord>): ByteArray`.

- [ ] **Step 1: Write the failing radar-core tests**

Create `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/replay/ReplayRecordTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.core.replay

import io.github.santiquiroz.blindside.core.RadarPipeline
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.sim.Scenarios
import io.github.santiquiroz.blindside.core.sim.simulate
import io.github.santiquiroz.blindside.core.sim.simulateWatchGyro
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReplayRecordTest {
    @Test
    fun `feeding records one at a time gives the same events as replaying the whole recording`() {
        val scenario = Scenarios.crossing()
        val packets = simulate(scenario)
        val start = packets.first().arrivalNanos
        val packetRecords = packets.map { BsrecRecord(RecordType.BLE_PACKET, (it.arrivalNanos - start) / 1_000_000L, it.bytes) }
        val gyroRecords = simulateWatchGyro(scenario).filter { it.eventNanos >= start }.map {
            BsrecRecord(RecordType.WATCH_GYRO, (it.eventNanos - start) / 1_000_000L, BsrecPayloads.watchGyro(it.x, it.y, it.z, it.eventNanos))
        }
        val records = (packetRecords + gyroRecords).sortedBy { it.tMsSinceStart }
        val config = PipelineConfig(mounts = scenario.mounts)

        val whole = replayRecording(records.asSequence(), RadarPipeline(config))
        val pipeline = RadarPipeline(config).also { it.onLinkState(true, 0L) }
        val stepwise = records.flatMap { replayRecord(it, pipeline, it.tMsSinceStart * 1_000_000L) }

        assertTrue(whole.isNotEmpty())
        assertEquals(whole, stepwise)
    }
}
```

In `watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/replay/BsrecTest.kt`, add these tests. They use the file's existing `record(...)` helper and its `assertThrows` import:

```kotlin
    @Test
    fun `a corrupt header length is refused before anything is allocated`() {
        val bytes = BSREC_MAGIC.toByteArray(Charsets.US_ASCII) + byteArrayOf(BSREC_FORMAT_VERSION.toByte(), -1, -1, -1, -1)
        assertThrows<IllegalArgumentException> { BsrecReader(ByteArrayInputStream(bytes)) }
    }

    @Test
    fun `a header longer than the caller allows is refused`() {
        assertThrows<IllegalArgumentException> { BsrecReader(ByteArrayInputStream(record()), maxHeaderBytes = 3) }
    }

    @Test
    fun `a zero-filled tail is skipped without overflowing the stack`() {
        val reader = BsrecReader(ByteArrayInputStream(record() + ByteArray(1_000_000)))
        assertEquals(0, reader.records().count())
    }
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `cd /c/personal/blindside/watch && ./gradlew :radar-core:test --tests "io.github.santiquiroz.blindside.core.replay.*" --console=plain`
Expected:
- FAIL with `Cannot access 'fun replayRecord(…)': it is private in file` and `No parameter with name 'maxHeaderBytes'`.
- Once those compile, the corrupt-length test fails with `NegativeArraySizeException` and the zero-tail test with `StackOverflowError`.

- [ ] **Step 3: Make `replayRecord` public**

In `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/replay/Replay.kt`, change

```kotlin
private fun replayRecord(record: BsrecRecord, pipeline: RadarPipeline, nanos: Long): List<PipelineEvent> = when (record.type) {
```

to

```kotlin
fun replayRecord(record: BsrecRecord, pipeline: RadarPipeline, nanos: Long): List<PipelineEvent> = when (record.type) {
```

- [ ] **Step 4: Harden the reader**

In `watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/replay/Bsrec.kt`, replace the whole `class BsrecReader(input: InputStream) { … }` with the block below. `BsrecWriter`, `RecordType` and the constants stay as they are.

```kotlin
const val MAX_BSREC_HEADER_BYTES = 1L shl 20

class BsrecReader(input: InputStream, private val maxHeaderBytes: Long = MAX_BSREC_HEADER_BYTES) {
    private val data = DataInputStream(input)
    val headerJson: String = readHeader()

    // A recording cut by a crash ends with a partial record; reading stops there instead of failing.
    fun records(): Sequence<BsrecRecord> = generateSequence { readRawRecord() }.mapNotNull(::knownRecord)

    private fun readHeader(): String {
        val fixed = ByteArray(BSREC_MAGIC.length + 5).also { data.readFully(it) }
        require(String(fixed, 0, BSREC_MAGIC.length, Charsets.US_ASCII) == BSREC_MAGIC) { "not a .bsrec file" }
        require(fixed[BSREC_MAGIC.length].toInt() == BSREC_FORMAT_VERSION) { "unsupported .bsrec version ${fixed[BSREC_MAGIC.length]}" }
        val length = fixed.u32le(BSREC_MAGIC.length + 1)
        // A corrupt length would allocate gigabytes (or a negative array) before a byte of JSON is read.
        require(length <= maxHeaderBytes) { "header length $length exceeds $maxHeaderBytes" }
        return String(ByteArray(length.toInt()).also { data.readFully(it) }, Charsets.UTF_8)
    }

    // Unknown types are dropped by the sequence, never by recursion: a zero-filled tail used to overflow the stack.
    private fun readRawRecord(): RawRecord? = try {
        val head = ByteArray(RECORD_HEADER_BYTES).also { data.readFully(it) }
        val payload = ByteArray(head.u16le(5)).also { data.readFully(it) }
        RawRecord(head[0].toInt() and 0xFF, head.u32le(1), payload)
    } catch (_: EOFException) {
        null
    }
}

private class RawRecord(val code: Int, val tMs: Long, val payload: ByteArray)

private fun knownRecord(raw: RawRecord): BsrecRecord? =
    RecordType.fromCode(raw.code)?.let { BsrecRecord(it, raw.tMs, raw.payload) }
```

- [ ] **Step 5: Run radar-core and the watch gate**

Run: `cd /c/personal/blindside/watch && ./gradlew :radar-core:test --console=plain`, then the watch gate and the test-count one-liner.
Expected:
- `BUILD SUCCESSFUL` both times.
- radar-core 258 (254 + 4). Count it with `cat radar-core/build/test-results/test/*.xml | grep -o '<testsuite [^>]*' | grep -o ' tests="[0-9]*"' | grep -o '[0-9]*' | awk '{s+=$1} END {print s+0}'`.
- `android-shared 280`, `wear-app 46`.

The existing `BsrecTest` cases (round trip, a truncated tail, unknown types skipped) keep passing: the output is the same, only the way it is computed changed.

- [ ] **Step 6: Write the failing phone tests**

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/viewer/TestRecordings.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.viewer

import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.config.toJson
import io.github.santiquiroz.blindside.core.protocol.MiniJson
import io.github.santiquiroz.blindside.core.replay.BsrecPayloads
import io.github.santiquiroz.blindside.core.replay.BsrecRecord
import io.github.santiquiroz.blindside.core.replay.BsrecWriter
import io.github.santiquiroz.blindside.core.replay.RecordType
import io.github.santiquiroz.blindside.core.sim.Scenario
import io.github.santiquiroz.blindside.core.sim.simulate
import io.github.santiquiroz.blindside.core.sim.simulateWatchGyro
import java.io.ByteArrayOutputStream

fun headerFor(scenario: Scenario): String = MiniJson.obj(listOf("config" to PipelineConfig(mounts = scenario.mounts).toJson()))

fun simulatedRecords(scenario: Scenario): List<BsrecRecord> {
    val packets = simulate(scenario)
    val startNanos = packets.first().arrivalNanos
    val packetRecords = packets.map { BsrecRecord(RecordType.BLE_PACKET, msSince(it.arrivalNanos, startNanos), it.bytes) }
    val gyroRecords = simulateWatchGyro(scenario).filter { it.eventNanos >= startNanos }.map {
        BsrecRecord(RecordType.WATCH_GYRO, msSince(it.eventNanos, startNanos), BsrecPayloads.watchGyro(it.x, it.y, it.z, it.eventNanos))
    }
    return (packetRecords + gyroRecords).sortedBy { it.tMsSinceStart }
}

fun recordingBytes(headerJson: String, records: List<BsrecRecord>): ByteArray {
    val out = ByteArrayOutputStream()
    val writer = BsrecWriter(out, headerJson)
    records.forEach(writer::write)
    writer.close()
    return out.toByteArray()
}

private fun msSince(nanos: Long, startNanos: Long): Long = (nanos - startNanos) / 1_000_000L
```

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/viewer/PlaybackTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.viewer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaybackTest {
    private val ten = Playback(positionMs = 0, durationMs = 10_000)

    @Test
    fun `a paused playback does not move`() {
        assertEquals(ten, advanced(ten, wallDeltaMs = 500))
    }

    @Test
    fun `playing advances by wall time times the speed`() {
        assertEquals(400L, advanced(ten.copy(playing = true, speed = 4), wallDeltaMs = 100).positionMs)
    }

    @Test
    fun `playback stops exactly at the end`() {
        val end = advanced(ten.copy(positionMs = 9_950, playing = true, speed = 8), wallDeltaMs = 100)
        assertEquals(10_000L, end.positionMs)
        assertFalse(end.playing)
    }

    @Test
    fun `a backwards wall step never rewinds`() {
        assertEquals(2_000L, advanced(ten.copy(positionMs = 2_000, playing = true), wallDeltaMs = -50).positionMs)
    }

    @Test
    fun `play at the end starts over and pause keeps the position`() {
        val restarted = toggledPlay(ten.copy(positionMs = 10_000))
        assertEquals(0L, restarted.positionMs)
        assertTrue(restarted.playing)
        assertEquals(ten.copy(positionMs = 3_000), toggledPlay(ten.copy(positionMs = 3_000, playing = true)))
    }

    @Test
    fun `only 1, 2, 4 and 8 times are accepted`() {
        assertEquals(listOf(1, 2, 4, 8), PLAYBACK_SPEEDS)
        assertEquals(8, withSpeed(ten, 8).speed)
        assertEquals(1, withSpeed(ten, 3).speed)
    }

    @Test
    fun `seeking is clamped to the recording`() {
        assertEquals(0L, seekedTo(ten, -10).positionMs)
        assertEquals(10_000L, seekedTo(ten, 99_000).positionMs)
        assertEquals(4_200L, seekedTo(ten, 4_200).positionMs)
    }

    @Test
    fun `an empty recording plays and stops at once`() {
        val empty = toggledPlay(Playback())
        assertFalse(advanced(empty, wallDeltaMs = 16).playing)
    }
}
```

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/viewer/ReplayCursorTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.viewer

import io.github.santiquiroz.blindside.core.sim.Scenarios
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class ReplayCursorTest {
    private val scenario = Scenarios.crossing()
    private val bytes = recordingBytes(headerFor(scenario), simulatedRecords(scenario))

    private fun cursor() = ReplayCursor { ByteArrayInputStream(bytes) }

    @Test
    fun `seeking back shows exactly what a fresh replay shows at that time`() {
        val replay = cursor()
        replay.seekTo(7_000)
        assertEquals(cursor().seekTo(4_000), replay.seekTo(4_000))
    }

    @Test
    fun `seeking forward in small steps matches one long seek`() {
        val stepped = cursor()
        (0L..6_000L step 500L).forEach { stepped.seekTo(it) }
        assertEquals(cursor().seekTo(6_000), stepped.seekTo(6_000))
    }

    @Test
    fun `only an earlier time needs a restart`() {
        val replay = cursor()
        replay.seekTo(3_000)
        assertTrue(replay.needsRestart(2_999))
        assertFalse(replay.needsRestart(3_000))
        assertFalse(replay.needsRestart(5_000))
    }

    @Test
    fun `the walker crossing behind the player shows up during the replay`() {
        val replay = cursor()
        assertTrue((0L..8_500L step 250L).any { replay.seekTo(it).blips.isNotEmpty() })
    }

    @Test
    fun `seeking past the end keeps the last state`() {
        val replay = cursor()
        replay.seekTo(60_000)
        assertEquals(60_000L, replay.positionMs)
    }

    @Test
    fun `closing the cursor closes the recording`() {
        var closed = false
        val replay = ReplayCursor {
            object : ByteArrayInputStream(bytes) {
                override fun close() {
                    closed = true
                }
            }
        }
        replay.close()
        assertTrue(closed)
    }
}
```

- [ ] **Step 7: Run them to make sure they fail**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.phone.viewer.*" --console=plain`
Expected: FAIL with `Unresolved reference 'Playback'`.

- [ ] **Step 8: Write the playback clock**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/viewer/Playback.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.viewer

val PLAYBACK_SPEEDS: List<Int> = listOf(1, 2, 4, 8)

data class Playback(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Int = 1,
    val playing: Boolean = false,
)

fun advanced(playback: Playback, wallDeltaMs: Long): Playback {
    if (!playback.playing) return playback
    val next = (playback.positionMs + wallDeltaMs.coerceAtLeast(0L) * playback.speed).coerceAtMost(playback.durationMs)
    return playback.copy(positionMs = next, playing = next < playback.durationMs)
}

// Play at the end starts over, like any media player.
fun toggledPlay(playback: Playback): Playback = when {
    playback.playing -> playback.copy(playing = false)
    playback.positionMs >= playback.durationMs -> playback.copy(positionMs = 0L, playing = true)
    else -> playback.copy(playing = true)
}

fun withSpeed(playback: Playback, speed: Int): Playback = if (speed in PLAYBACK_SPEEDS) playback.copy(speed = speed) else playback

fun seekedTo(playback: Playback, positionMs: Long): Playback = playback.copy(positionMs = positionMs.coerceIn(0L, playback.durationMs))
```

- [ ] **Step 9: Write the cursor**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/viewer/ReplayCursor.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.viewer

import io.github.santiquiroz.blindside.core.RadarPipeline
import io.github.santiquiroz.blindside.core.replay.BsrecReader
import io.github.santiquiroz.blindside.core.replay.BsrecRecord
import io.github.santiquiroz.blindside.core.replay.pipelineConfigFromHeader
import io.github.santiquiroz.blindside.core.replay.replayRecord
import io.github.santiquiroz.blindside.core.scene.RadarScene
import java.io.Closeable
import java.io.InputStream

internal const val NANOS_PER_MS = 1_000_000L

// The pipeline cannot be rewound, so a seek backwards replays from the start; forward seeks only feed what is new.
class ReplayCursor(private val open: () -> InputStream) : Closeable {
    private var stream: InputStream = open()
    private var reader = BsrecReader(stream)
    private var pipeline = startedPipeline(reader.headerJson)
    private var records: Iterator<BsrecRecord> = reader.records().iterator()
    private var pending: BsrecRecord? = null

    var positionMs: Long = 0L
        private set

    fun needsRestart(targetMs: Long): Boolean = targetMs < positionMs

    fun seekTo(targetMs: Long): RadarScene {
        if (needsRestart(targetMs)) restart()
        feedUntil(targetMs)
        positionMs = targetMs
        return pipeline.scene(targetMs * NANOS_PER_MS)
    }

    override fun close() = stream.close()

    private fun restart() {
        stream.close()
        stream = open()
        reader = BsrecReader(stream)
        pipeline = startedPipeline(reader.headerJson)
        records = reader.records().iterator()
        pending = null
        positionMs = 0L
    }

    private fun feedUntil(targetMs: Long) {
        var next = pending ?: nextRecord()
        while (next != null && next.tMsSinceStart <= targetMs) {
            replayRecord(next, pipeline, next.tMsSinceStart * NANOS_PER_MS)
            next = nextRecord()
        }
        pending = next
    }

    private fun nextRecord(): BsrecRecord? = if (records.hasNext()) records.next() else null
}

// Same start as radar-core's replayRecording: a recording without link records still plays as linked.
private fun startedPipeline(headerJson: String): RadarPipeline =
    RadarPipeline(pipelineConfigFromHeader(headerJson)).also { it.onLinkState(true, 0L) }
```

- [ ] **Step 10: Run the tests**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.phone.viewer.*" --console=plain`
Expected: PASS (8 + 6 tests).

- [ ] **Step 11: Commit**

```bash
cd /c/personal/blindside
git add watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/replay/Replay.kt \
  watch/radar-core/src/main/kotlin/io/github/santiquiroz/blindside/core/replay/Bsrec.kt \
  watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/replay/ReplayRecordTest.kt \
  watch/radar-core/src/test/kotlin/io/github/santiquiroz/blindside/core/replay/BsrecTest.kt \
  watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/viewer/Playback.kt \
  watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/viewer/ReplayCursor.kt \
  watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/viewer
git commit -m "feat: lector .bsrec blindado contra cabeceras corruptas y colas en cero, y motor de reproducción del visor"
```

---

### Task 12: Heat map and recording summary in one streaming pass

**Files:**
- Create: `phone/viewer/HeatGrid.kt`
- Create: `phone/viewer/HeatPalette.kt`
- Create: `phone/viewer/RecordingSummary.kt`
- Create: `phone/viewer/SummaryRows.kt`
- Create: `phone/viewer/RecordingAnalysis.kt`
- Test: `phoneTest/viewer/HeatGridTest.kt`, `phoneTest/viewer/HeatPaletteTest.kt`, `phoneTest/viewer/RecordingSummaryTest.kt`, `phoneTest/viewer/SummaryRowsTest.kt`, `phoneTest/viewer/RecordingAnalysisTest.kt`

**Interfaces:**
- Consumes:
  - Plan 05: `Tokens`, `contrastRatio`, `relativeLuminance` (`shared.theme`); `MAX_RANGE_M`, `PointPx` (`shared.radar`).
  - Task 7: `formatClock`, `formatDecimal`, `formatSeconds`, `formatPercent`.
  - Task 11: `replayRecord`, `BsrecReader(input, maxHeaderBytes)`, `MAX_BSREC_HEADER_BYTES`, `NANOS_PER_MS`, the test helpers.
  - radar-core: `coverageOf`, `BsrecPayloads`, `MotionState`.
- Produces (package `…phone.viewer`):
  - Heat grid:
    - constants `HEAT_CELL_M = 0.5`, `HEAT_EXTENT_M = MAX_RANGE_M`, `HEAT_COLUMNS = 24`, `HEAT_ROWS = 24`;
    - types `data class HeatCell(col, row)`, `data class HeatGrid(counts: List<Int>)` with `total` and `max`, `data class CellRectPx(left, top, size)`, `class HeatCounter { add(cell); toGrid() }`;
    - functions `heatCellOf(blip): HeatCell?`, `cellAt(xM, yM): HeatCell?`, `countAt(grid, cell)`, `hotCells(grid): List<Pair<HeatCell, Int>>`, `hottestCell(grid): HeatCell?`, `cellCenterM(cell): Pair<Double, Double>`, `cellRectPx(cell, origin, radiusPx): CellRectPx`, `heatGridOf(cells)`.
  - Palette: `val HEAT_RAMP: List<Long>`, `heatLevel(count, max): Int?`, `heatLegendLabels(max, sampleMs): List<String>`, `heatDescription(grid): String`.
  - Summary: `data class SummaryAccumulator`, `data class RecordingSummary(durationMs, confirmed, confirmedPerMinute, alerts, linkGaps, linkGapMs, walkingFraction, stillFraction, motionSamples, latencyMedianMs, latencyP90Ms)`, `afterRecord(acc, record)`, `afterMotion(acc, motion)`, `summaryOf(acc)`, `nearestRank(sortedMs, percent): Long?`, `data class SummaryRow(label, value)`, `summaryRows(summary)`, `latencyText(summary)`.
  - Analysis:
    - constants `ANALYSIS_SAMPLE_MS = 250L`, `MAX_SAMPLED_GAP_MS = 5_000L`;
    - types `data class RecordingAnalysis(summary, heat, coverage)`, `sealed interface AnalysisState { Running(progress); Done(analysis); Failed(reason) }`, `class CountingInputStream`;
    - functions `analyzeRecording(headerJson, records, sampleEveryMs = ANALYSIS_SAMPLE_MS)`, `analyzeFile(file, onProgress): AnalysisState` (never throws on a damaged file), `countsForAnalysis(scene)`, `percentOf(read, total)`, `nextSampleAfterSkip(nextSampleMs, recordMs)`.

- [ ] **Step 1: Write the failing tests**

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/viewer/HeatGridTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.viewer

import io.github.santiquiroz.blindside.core.scene.Blip
import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.shared.radar.PointPx
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HeatGridTest {
    private fun blip(bearingDeg: Double, rangeM: Double, outOfView: Boolean = false) =
        Blip(1, bearingDeg, rangeM, Confidence.BOTH, 0, outOfView)

    @Test
    fun `the grid covers the fan in half-metre cells`() {
        assertEquals(24, HEAT_COLUMNS)
        assertEquals(24, HEAT_ROWS)
    }

    @Test
    fun `a contact straight ahead lands in the centre column`() {
        assertEquals(HeatCell(12, 18), heatCellOf(blip(0.0, 3.2)))
    }

    @Test
    fun `positive bearings go right and negative bearings go left`() {
        assertEquals(HeatCell(14, 12), heatCellOf(blip(90.0, 1.0)))
        assertEquals(HeatCell(10, 12), heatCellOf(blip(-90.0, 1.0)))
    }

    @Test
    fun `contacts beyond the fan or out of view count nowhere`() {
        assertNull(heatCellOf(blip(0.0, 7.0)))
        assertNull(heatCellOf(blip(0.0, 2.0, outOfView = true)))
        assertNull(cellAt(-6.01, 0.0))
    }

    @Test
    fun `counts add up per cell`() {
        val a = HeatCell(12, 18)
        val b = HeatCell(3, 4)
        val grid = heatGridOf(listOf(a, a, b))
        assertEquals(2, countAt(grid, a))
        assertEquals(3, grid.total)
        assertEquals(2, grid.max)
        assertEquals(a, hottestCell(grid))
        assertEquals(listOf(b to 1, a to 2), hotCells(grid))
    }

    @Test
    fun `an empty grid has no hottest cell`() {
        val empty = heatGridOf(emptyList())
        assertEquals(0, empty.total)
        assertEquals(0, empty.max)
        assertNull(hottestCell(empty))
    }

    @Test
    fun `cell centres and pixel squares follow the radar drawing`() {
        assertEquals(0.25 to 3.25, cellCenterM(HeatCell(12, 18)))
        assertEquals(CellRectPx(left = 100f, top = 95f, size = 5f), cellRectPx(HeatCell(12, 12), PointPx(100f, 100f), radiusPx = 60f))
    }
}
```

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/viewer/HeatPaletteTest.kt`. It checks the ramp with plan 05's contrast math:

```kotlin
package io.github.santiquiroz.blindside.phone.viewer

import io.github.santiquiroz.blindside.shared.theme.Tokens
import io.github.santiquiroz.blindside.shared.theme.contrastRatio
import io.github.santiquiroz.blindside.shared.theme.relativeLuminance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeatPaletteTest {
    @Test
    fun `every heat colour stands out on black`() {
        HEAT_RAMP.forEach { assertTrue(contrastRatio(it, Tokens.BG) >= 3.0, it.toString(16)) }
    }

    @Test
    fun `heat gets brighter at every step so it also reads without colour`() {
        val luminances = HEAT_RAMP.map(::relativeLuminance)
        assertEquals(luminances.sorted(), luminances)
        assertEquals(luminances.size, luminances.toSet().size)
    }

    @Test
    fun `counts map to five levels and zero to none`() {
        assertNull(heatLevel(0, 10))
        assertNull(heatLevel(3, 0))
        assertEquals(0, heatLevel(1, 10))
        assertEquals(2, heatLevel(5, 10))
        assertEquals(4, heatLevel(10, 10))
    }

    @Test
    fun `the legend tells the time each level stands for`() {
        assertEquals(listOf("≤ 0,5 s", "≤ 1,0 s", "≤ 1,5 s", "≤ 2,0 s", "≤ 2,5 s"), heatLegendLabels(max = 10, sampleMs = 250))
    }

    @Test
    fun `screen readers hear how many cells are hot`() {
        assertEquals("Mapa de calor con 1 celdas con contactos", heatDescription(heatGridOf(listOf(HeatCell(1, 1)))))
    }
}
```

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/viewer/RecordingSummaryTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.viewer

import io.github.santiquiroz.blindside.core.replay.BsrecPayloads
import io.github.santiquiroz.blindside.core.replay.BsrecRecord
import io.github.santiquiroz.blindside.core.replay.RecordType
import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.scene.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RecordingSummaryTest {
    private fun record(type: RecordType, tMs: Long, payload: ByteArray = ByteArray(0)) = BsrecRecord(type, tMs, payload)
    private fun confirmed(id: Int, tMs: Long) = record(RecordType.TRACK_CONFIRMED, tMs, BsrecPayloads.trackConfirmed(id))
    private fun vibrated(id: Int, tMs: Long) = record(RecordType.VIBRATION_STARTED, tMs, BsrecPayloads.vibrationStarted(id, Side.LEFT))
    private fun link(up: Boolean, tMs: Long) = record(RecordType.MODE_CHANGE, tMs, BsrecPayloads.linkChange(up))
    private fun packet(tMs: Long) = record(RecordType.BLE_PACKET, tMs, byteArrayOf(1))

    private val session = listOf(
        link(true, 0), packet(500), confirmed(1, 1_000), vibrated(1, 1_200), vibrated(1, 3_000),
        confirmed(2, 5_000), vibrated(2, 5_600), link(false, 10_000), link(true, 12_500),
        confirmed(3, 20_000), link(false, 50_000), packet(60_000),
    )

    private fun summarize(records: List<BsrecRecord>, motions: List<MotionState> = emptyList()): RecordingSummary {
        val afterRecords = records.fold(SummaryAccumulator(), ::afterRecord)
        return summaryOf(motions.fold(afterRecords, ::afterMotion))
    }

    @Test
    fun `duration, confirmations and alerts come from the records`() {
        val summary = summarize(session)
        assertEquals(60_000L, summary.durationMs)
        assertEquals(3, summary.confirmed)
        assertEquals(3.0, summary.confirmedPerMinute, 1e-9)
        assertEquals(3, summary.alerts)
    }

    @Test
    fun `each drop is a gap and an open gap runs to the end`() {
        val summary = summarize(session)
        assertEquals(2, summary.linkGaps)
        assertEquals(2_500L + 10_000L, summary.linkGapMs)
    }

    @Test
    fun `searching before the first connection is not a gap`() {
        val summary = summarize(listOf(link(false, 0), link(true, 4_000), packet(5_000)))
        assertEquals(0, summary.linkGaps)
        assertEquals(0L, summary.linkGapMs)
    }

    @Test
    fun `confirmation latency pairs each confirmation with its first buzz`() {
        val summary = summarize(session)
        assertEquals(200L, summary.latencyMedianMs)
        assertEquals(600L, summary.latencyP90Ms)
    }

    @Test
    fun `without buzzes there is no latency`() {
        assertNull(summarize(listOf(confirmed(1, 100))).latencyMedianMs)
    }

    @Test
    fun `walking and still shares come from the motion samples`() {
        val summary = summarize(emptyList(), listOf(MotionState.STILL, MotionState.STILL, MotionState.STILL, MotionState.WALKING))
        assertEquals(0.25, summary.walkingFraction, 1e-9)
        assertEquals(0.75, summary.stillFraction, 1e-9)
        assertEquals(4, summary.motionSamples)
    }

    @Test
    fun `an empty recording summarizes to zeros`() {
        val summary = summarize(emptyList())
        assertEquals(0L, summary.durationMs)
        assertEquals(0.0, summary.confirmedPerMinute)
        assertEquals(0.0, summary.walkingFraction)
    }

    @Test
    fun `nearest rank always picks a real sample`() {
        assertEquals(200L, nearestRank(listOf(200L, 600L), 50))
        assertEquals(600L, nearestRank(listOf(200L, 600L), 90))
        assertEquals(30L, nearestRank(listOf(10L, 20L, 30L, 40L), 75))
        assertNull(nearestRank(emptyList(), 50))
    }
}
```

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/viewer/SummaryRowsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.viewer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SummaryRowsTest {
    private val summary = RecordingSummary(
        durationMs = 3_725_000,
        confirmed = 3,
        confirmedPerMinute = 0.6,
        alerts = 2,
        linkGaps = 1,
        linkGapMs = 12_500,
        walkingFraction = 0.25,
        stillFraction = 0.75,
        motionSamples = 4,
        latencyMedianMs = 200,
        latencyP90Ms = 600,
    )

    @Test
    fun `every spec metric reads as one row`() {
        val expected = listOf(
            SummaryRow("Duración", "1:02:05"),
            SummaryRow("Contactos confirmados", "3 · 0,6 por minuto"),
            SummaryRow("Alertas", "2"),
            SummaryRow("Huecos de enlace", "1 · 12,5 s"),
            SummaryRow("Caminando", "25 %"),
            SummaryRow("Quieto", "75 %"),
            SummaryRow("Latencia de confirmación", "mediana 200 ms · p90 600 ms"),
        )
        assertEquals(expected, summaryRows(summary))
    }

    @Test
    fun `missing latency says so`() {
        assertEquals("sin datos", latencyText(summary.copy(latencyMedianMs = null, latencyP90Ms = null)))
    }
}
```

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/viewer/RecordingAnalysisTest.kt`. The last two tests are the damaged files a crash or a bad copy leaves behind:

```kotlin
package io.github.santiquiroz.blindside.phone.viewer

import io.github.santiquiroz.blindside.core.replay.BSREC_FORMAT_VERSION
import io.github.santiquiroz.blindside.core.replay.BSREC_MAGIC
import io.github.santiquiroz.blindside.core.replay.BsrecRecord
import io.github.santiquiroz.blindside.core.replay.RecordType
import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.sim.Scenarios
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import kotlin.math.hypot

class RecordingAnalysisTest {
    private val scenario = Scenarios.crossing()
    private val records = simulatedRecords(scenario)

    private fun scene(linkUp: Boolean, eliminated: Boolean) =
        RadarScene(emptyList(), emptyList(), linkUp, emptyList(), emptyList(), MotionState.STILL, emptySet(), eliminated)

    @Test
    fun `the crossing walker heats cells at its real distance`() {
        val analysis = analyzeRecording(headerFor(scenario), records.asSequence())
        assertTrue(analysis.heat.total > 0)
        val (x, y) = cellCenterM(hottestCell(analysis.heat)!!)
        assertTrue(hypot(x, y) in 2.5..5.0, "hottest cell at ${hypot(x, y)} m")
    }

    @Test
    fun `the summary spans the whole recording`() {
        val summary = analyzeRecording(headerFor(scenario), records.asSequence()).summary
        assertEquals(records.last().tMsSinceStart, summary.durationMs)
        assertTrue(summary.motionSamples > 0)
    }

    @Test
    fun `the fan comes from the recorded mounts`() {
        assertEquals(2, analyzeRecording(headerFor(scenario), records.asSequence()).coverage.size)
    }

    @Test
    fun `a clock jump of weeks does not stall the analysis`() {
        val jumped = listOf(records.first(), BsrecRecord(RecordType.MANUAL_MARKER, 4_000_000_000L, ByteArray(0)))
        assertTimeoutPreemptively(Duration.ofSeconds(5)) { analyzeRecording(headerFor(scenario), jumped.asSequence()) }
    }

    @Test
    fun `a long gap skips straight to the last seconds before the next record`() {
        assertEquals(3_595_000L, nextSampleAfterSkip(250L, 3_600_000L))
        assertEquals(250L, nextSampleAfterSkip(250L, 1_000L))
    }

    @Test
    fun `a file that is not a recording fails with a reason`(@TempDir dir: File) {
        val garbage = File(dir, "x.bsrec").apply { writeText("hello, this is not a recording") }
        assertTrue(analyzeFile(garbage) {} is AnalysisState.Failed)
    }

    @Test
    fun `an empty file fails instead of crashing`(@TempDir dir: File) {
        assertTrue(analyzeFile(File(dir, "e.bsrec").apply { writeBytes(ByteArray(0)) }) {} is AnalysisState.Failed)
    }

    @Test
    fun `a recording cut in the middle of a record still opens`(@TempDir dir: File) {
        val full = recordingBytes(headerFor(scenario), records)
        val cut = File(dir, "cut.bsrec").apply { writeBytes(full.copyOf(full.size - 7)) }
        assertTrue(analyzeFile(cut) {} is AnalysisState.Done)
    }

    @Test
    fun `progress climbs to 100 percent without going back`(@TempDir dir: File) {
        val file = File(dir, "ok.bsrec").apply { writeBytes(recordingBytes(headerFor(scenario), records)) }
        val seen = mutableListOf<Float>()
        analyzeFile(file) { seen += it }
        assertEquals(1f, seen.last())
        assertEquals(seen.sorted(), seen)
    }

    @Test
    fun `percent is safe with an empty total`() {
        assertEquals(100, percentOf(5, 0))
        assertEquals(50, percentOf(5, 10))
    }

    @Test
    fun `only a linked, playing scene counts`() {
        assertTrue(countsForAnalysis(scene(linkUp = true, eliminated = false)))
        assertFalse(countsForAnalysis(scene(linkUp = false, eliminated = false)))
        assertFalse(countsForAnalysis(scene(linkUp = true, eliminated = true)))
    }

    @Test
    fun `a corrupt header length fails instead of allocating gigabytes`(@TempDir dir: File) {
        val bytes = BSREC_MAGIC.toByteArray(Charsets.US_ASCII) + byteArrayOf(BSREC_FORMAT_VERSION.toByte(), -1, -1, -1, -1) + ByteArray(64)
        assertTrue(analyzeFile(File(dir, "h.bsrec").apply { writeBytes(bytes) }) {} is AnalysisState.Failed)
    }

    @Test
    fun `a zero-filled tail after a valid header still opens without overflowing the stack`(@TempDir dir: File) {
        val bytes = recordingBytes(headerFor(scenario), records) + ByteArray(1_000_000)
        assertTrue(analyzeFile(File(dir, "z.bsrec").apply { writeBytes(bytes) }) {} is AnalysisState.Done)
    }
}
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.phone.viewer.*" --console=plain`
Expected: FAIL with `Unresolved reference 'HEAT_COLUMNS'`.

- [ ] **Step 3: Write the heat grid and its palette**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/viewer/HeatGrid.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.viewer

import io.github.santiquiroz.blindside.core.scene.Blip
import io.github.santiquiroz.blindside.shared.radar.MAX_RANGE_M
import io.github.santiquiroz.blindside.shared.radar.PointPx
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

const val HEAT_CELL_M = 0.5
const val HEAT_EXTENT_M = MAX_RANGE_M
val HEAT_COLUMNS: Int = (2 * HEAT_EXTENT_M / HEAT_CELL_M).toInt()
val HEAT_ROWS: Int = HEAT_COLUMNS

data class HeatCell(val col: Int, val row: Int)

data class HeatGrid(val counts: List<Int>) {
    val total: Int get() = counts.sum()
    val max: Int get() = counts.maxOrNull() ?: 0
}

data class CellRectPx(val left: Float, val top: Float, val size: Float)

// Counting tens of thousands of samples into a fresh immutable grid each time would copy the grid per sample.
class HeatCounter {
    private val counts = IntArray(HEAT_COLUMNS * HEAT_ROWS)

    fun add(cell: HeatCell) {
        counts[indexOf(cell)]++
    }

    fun toGrid(): HeatGrid = HeatGrid(counts.toList())
}

fun heatCellOf(blip: Blip): HeatCell? {
    if (blip.outOfView) return null
    val radians = Math.toRadians(blip.bearingDeg)
    return cellAt(blip.rangeM * sin(radians), blip.rangeM * cos(radians))
}

fun cellAt(xM: Double, yM: Double): HeatCell? =
    HeatCell(axisIndex(xM), axisIndex(yM)).takeIf { it.col in 0 until HEAT_COLUMNS && it.row in 0 until HEAT_ROWS }

fun heatGridOf(cells: Iterable<HeatCell>): HeatGrid = HeatCounter().apply { cells.forEach(::add) }.toGrid()

fun countAt(grid: HeatGrid, cell: HeatCell): Int = grid.counts[indexOf(cell)]

fun hotCells(grid: HeatGrid): List<Pair<HeatCell, Int>> =
    grid.counts.mapIndexedNotNull { index, count -> if (count > 0) cellOfIndex(index) to count else null }

fun hottestCell(grid: HeatGrid): HeatCell? = hotCells(grid).maxByOrNull { it.second }?.first

fun cellCenterM(cell: HeatCell): Pair<Double, Double> = edgeM(cell.col) + HEAT_CELL_M / 2 to edgeM(cell.row) + HEAT_CELL_M / 2

// Same mapping as the radar drawing: +x to the right, +y up the screen, MAX_RANGE_M at the fan radius.
fun cellRectPx(cell: HeatCell, origin: PointPx, radiusPx: Float): CellRectPx {
    val pxPerM = radiusPx / HEAT_EXTENT_M
    val left = origin.x + edgeM(cell.col) * pxPerM
    val top = origin.y - edgeM(cell.row + 1) * pxPerM
    return CellRectPx(left.toFloat(), top.toFloat(), (HEAT_CELL_M * pxPerM).toFloat())
}

private fun axisIndex(meters: Double): Int = floor((meters + HEAT_EXTENT_M) / HEAT_CELL_M).toInt()

private fun edgeM(index: Int): Double = index * HEAT_CELL_M - HEAT_EXTENT_M

private fun indexOf(cell: HeatCell): Int = cell.row * HEAT_COLUMNS + cell.col

private fun cellOfIndex(index: Int): HeatCell = HeatCell(index % HEAT_COLUMNS, index / HEAT_COLUMNS)
```

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/viewer/HeatPalette.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.viewer

import io.github.santiquiroz.blindside.phone.ui.common.formatSeconds

// Upper half of viridis: colour-blind safe, ≥ 3:1 on black and brighter at every step.
val HEAT_RAMP: List<Long> = listOf(0xFF31688EL, 0xFF21918CL, 0xFF35B779L, 0xFF90D743L, 0xFFFDE725L)

fun heatLevel(count: Int, max: Int): Int? {
    if (count <= 0 || max <= 0) return null
    return (ceilDiv(count.toLong() * HEAT_RAMP.size, max.toLong()) - 1).toInt().coerceIn(0, HEAT_RAMP.lastIndex)
}

fun heatLegendLabels(max: Int, sampleMs: Long): List<String> =
    HEAT_RAMP.indices.map { level -> "≤ ${formatSeconds(levelUpperCount(level, max) * sampleMs)}" }

fun heatDescription(grid: HeatGrid): String = "Mapa de calor con ${hotCells(grid).size} celdas con contactos"

private fun levelUpperCount(level: Int, max: Int): Long = ceilDiv((level + 1).toLong() * max, HEAT_RAMP.size.toLong())

private fun ceilDiv(numerator: Long, denominator: Long): Long = (numerator + denominator - 1) / denominator
```

- [ ] **Step 4: Write the summary accumulator and its rows**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/viewer/RecordingSummary.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.viewer

import io.github.santiquiroz.blindside.core.protocol.i32le
import io.github.santiquiroz.blindside.core.replay.BsrecPayloads
import io.github.santiquiroz.blindside.core.replay.BsrecRecord
import io.github.santiquiroz.blindside.core.replay.RecordType
import io.github.santiquiroz.blindside.core.scene.MotionState
import kotlin.math.ceil

data class SummaryAccumulator(
    val lastTMs: Long = 0L,
    val confirmed: Int = 0,
    val alerts: Int = 0,
    val linkSeenUp: Boolean = false,
    val linkDownSinceMs: Long? = null,
    val linkGaps: Int = 0,
    val linkGapMs: Long = 0L,
    val pendingConfirms: Map<Int, Long> = emptyMap(),
    val latenciesMs: List<Long> = emptyList(),
    val motionCounts: Map<MotionState, Int> = emptyMap(),
)

data class RecordingSummary(
    val durationMs: Long,
    val confirmed: Int,
    val confirmedPerMinute: Double,
    val alerts: Int,
    val linkGaps: Int,
    val linkGapMs: Long,
    val walkingFraction: Double,
    val stillFraction: Double,
    val motionSamples: Int,
    val latencyMedianMs: Long?,
    val latencyP90Ms: Long?,
)

private const val MS_PER_MINUTE = 60_000.0

fun afterRecord(acc: SummaryAccumulator, record: BsrecRecord): SummaryAccumulator {
    val timed = acc.copy(lastTMs = maxOf(acc.lastTMs, record.tMsSinceStart))
    return when (record.type) {
        RecordType.TRACK_CONFIRMED -> afterConfirm(timed, BsrecPayloads.readTrackConfirmed(record.payload), record.tMsSinceStart)
        RecordType.VIBRATION_STARTED -> afterVibration(timed, record.payload.i32le(0), record.tMsSinceStart)
        RecordType.MODE_CHANGE -> afterLinkChange(timed, BsrecPayloads.readLinkChange(record.payload), record.tMsSinceStart)
        else -> timed
    }
}

fun afterMotion(acc: SummaryAccumulator, motion: MotionState): SummaryAccumulator =
    acc.copy(motionCounts = acc.motionCounts + (motion to (acc.motionCounts[motion] ?: 0) + 1))

fun summaryOf(acc: SummaryAccumulator): RecordingSummary {
    val samples = acc.motionCounts.values.sum()
    val latencies = acc.latenciesMs.sorted()
    return RecordingSummary(
        durationMs = acc.lastTMs,
        confirmed = acc.confirmed,
        confirmedPerMinute = perMinute(acc.confirmed, acc.lastTMs),
        alerts = acc.alerts,
        linkGaps = acc.linkGaps,
        linkGapMs = acc.linkGapMs + openGapMs(acc),
        walkingFraction = fraction(acc.motionCounts[MotionState.WALKING], samples),
        stillFraction = fraction(acc.motionCounts[MotionState.STILL], samples),
        motionSamples = samples,
        latencyMedianMs = nearestRank(latencies, 50),
        latencyP90Ms = nearestRank(latencies, 90),
    )
}

fun nearestRank(sortedMs: List<Long>, percent: Int): Long? {
    if (sortedMs.isEmpty()) return null
    val rank = ceil(percent / 100.0 * sortedMs.size).toInt().coerceIn(1, sortedMs.size)
    return sortedMs[rank - 1]
}

private fun afterConfirm(acc: SummaryAccumulator, displayId: Int, tMs: Long): SummaryAccumulator = acc.copy(
    confirmed = acc.confirmed + 1,
    pendingConfirms = if (displayId in acc.pendingConfirms) acc.pendingConfirms else acc.pendingConfirms + (displayId to tMs),
)

// Only the first buzz after a confirmation measures its latency (records 5 and 6); later buzzes are plain alerts.
private fun afterVibration(acc: SummaryAccumulator, displayId: Int, tMs: Long): SummaryAccumulator {
    val counted = acc.copy(alerts = acc.alerts + 1)
    val confirmedAt = acc.pendingConfirms[displayId] ?: return counted
    return counted.copy(pendingConfirms = acc.pendingConfirms - displayId, latenciesMs = acc.latenciesMs + (tMs - confirmedAt))
}

private fun afterLinkChange(acc: SummaryAccumulator, up: Boolean?, tMs: Long): SummaryAccumulator = when (up) {
    true -> linkCameUp(acc, tMs)
    false -> linkWentDown(acc, tMs)
    null -> acc
}

private fun linkCameUp(acc: SummaryAccumulator, tMs: Long): SummaryAccumulator =
    acc.copy(linkSeenUp = true, linkDownSinceMs = null, linkGapMs = acc.linkGapMs + (acc.linkDownSinceMs?.let { tMs - it } ?: 0L))

// Searching before the first connection is not a gap; only a drop of a link that was up counts.
private fun linkWentDown(acc: SummaryAccumulator, tMs: Long): SummaryAccumulator =
    if (!acc.linkSeenUp || acc.linkDownSinceMs != null) acc else acc.copy(linkGaps = acc.linkGaps + 1, linkDownSinceMs = tMs)

private fun openGapMs(acc: SummaryAccumulator): Long = acc.linkDownSinceMs?.let { acc.lastTMs - it } ?: 0L

private fun perMinute(count: Int, durationMs: Long): Double = if (durationMs <= 0L) 0.0 else count * MS_PER_MINUTE / durationMs

private fun fraction(count: Int?, total: Int): Double = if (total == 0) 0.0 else (count ?: 0).toDouble() / total
```

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/viewer/SummaryRows.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.viewer

import io.github.santiquiroz.blindside.phone.ui.common.formatClock
import io.github.santiquiroz.blindside.phone.ui.common.formatDecimal
import io.github.santiquiroz.blindside.phone.ui.common.formatPercent
import io.github.santiquiroz.blindside.phone.ui.common.formatSeconds

data class SummaryRow(val label: String, val value: String)

fun summaryRows(summary: RecordingSummary): List<SummaryRow> = listOf(
    SummaryRow("Duración", formatClock(summary.durationMs)),
    SummaryRow("Contactos confirmados", "${summary.confirmed} · ${formatDecimal(summary.confirmedPerMinute)} por minuto"),
    SummaryRow("Alertas", "${summary.alerts}"),
    SummaryRow("Huecos de enlace", "${summary.linkGaps} · ${formatSeconds(summary.linkGapMs)}"),
    SummaryRow("Caminando", formatPercent(summary.walkingFraction)),
    SummaryRow("Quieto", formatPercent(summary.stillFraction)),
    SummaryRow("Latencia de confirmación", latencyText(summary)),
)

fun latencyText(summary: RecordingSummary): String {
    val median = summary.latencyMedianMs ?: return "sin datos"
    return "mediana $median ms · p90 ${summary.latencyP90Ms ?: median} ms"
}
```

- [ ] **Step 5: Write the streaming analysis**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/viewer/RecordingAnalysis.kt`. A damaged file must end in `Failed`, never in a crash of the `produceState` that runs it. That covers an `IOException`, any `RuntimeException` (a bad length, a bad enum), an `OutOfMemoryError` and a `StackOverflowError`. Cancellation still propagates.

```kotlin
package io.github.santiquiroz.blindside.phone.viewer

import io.github.santiquiroz.blindside.core.RadarPipeline
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import io.github.santiquiroz.blindside.core.replay.BsrecReader
import io.github.santiquiroz.blindside.core.replay.BsrecRecord
import io.github.santiquiroz.blindside.core.replay.MAX_BSREC_HEADER_BYTES
import io.github.santiquiroz.blindside.core.replay.pipelineConfigFromHeader
import io.github.santiquiroz.blindside.core.replay.replayRecord
import io.github.santiquiroz.blindside.core.scene.CoverageSector
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.coverageOf
import kotlinx.coroutines.CancellationException
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

const val ANALYSIS_SAMPLE_MS = 250L

// Past the link timeout a gap shows no contacts, so sampling all of it would only burn time.
const val MAX_SAMPLED_GAP_MS = 5_000L

private const val UNREADABLE_FILE = "no se pudo leer el archivo"
private const val NOT_A_RECORDING = "no es una grabación .bsrec válida"
private val BOTH_RADARS = setOf(RADAR_A, RADAR_B)

data class RecordingAnalysis(val summary: RecordingSummary, val heat: HeatGrid, val coverage: List<CoverageSector>)

sealed interface AnalysisState {
    data class Running(val progress: Float) : AnalysisState
    data class Done(val analysis: RecordingAnalysis) : AnalysisState
    data class Failed(val reason: String) : AnalysisState
}

class CountingInputStream(inner: InputStream) : FilterInputStream(inner) {
    var count: Long = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

    override fun skip(n: Long): Long = super.skip(n).also { count += it }
}

fun analyzeRecording(headerJson: String, records: Sequence<BsrecRecord>, sampleEveryMs: Long = ANALYSIS_SAMPLE_MS): RecordingAnalysis {
    val config = pipelineConfigFromHeader(headerJson)
    val run = AnalysisRun(RadarPipeline(config), sampleEveryMs)
    records.forEach(run::feed)
    return run.finish(coverageOf(config.mounts, BOTH_RADARS, config.tuning.decode))
}

// Any failure inside a damaged file is the file's fault: it ends in Failed, never in a crash of the viewer.
fun analyzeFile(file: File, onProgress: (Float) -> Unit): AnalysisState = try {
    CountingInputStream(BufferedInputStream(FileInputStream(file))).use { analyzeStream(it, file.length(), onProgress) }
} catch (error: CancellationException) {
    throw error
} catch (error: IOException) {
    AnalysisState.Failed(UNREADABLE_FILE)
} catch (error: RuntimeException) {
    AnalysisState.Failed(NOT_A_RECORDING)
} catch (error: OutOfMemoryError) {
    AnalysisState.Failed(NOT_A_RECORDING)
} catch (error: StackOverflowError) {
    AnalysisState.Failed(NOT_A_RECORDING)
}

fun countsForAnalysis(scene: RadarScene): Boolean = scene.linkUp && !scene.eliminated

fun percentOf(read: Long, total: Long): Int = (read * 100 / total.coerceAtLeast(1L)).toInt().coerceIn(0, 100)

fun nextSampleAfterSkip(nextSampleMs: Long, recordMs: Long): Long = maxOf(nextSampleMs, recordMs - MAX_SAMPLED_GAP_MS)

// A header can never be longer than the file that holds it.
private fun analyzeStream(counting: CountingInputStream, totalBytes: Long, onProgress: (Float) -> Unit): AnalysisState {
    val reader = BsrecReader(counting, maxHeaderBytes = minOf(totalBytes, MAX_BSREC_HEADER_BYTES))
    val progress = ProgressReporter(totalBytes, onProgress)
    val records = reader.records().onEach { progress.report(counting.count) }
    return AnalysisState.Done(analyzeRecording(reader.headerJson, records))
}

private class ProgressReporter(private val totalBytes: Long, private val onProgress: (Float) -> Unit) {
    private var lastPercent = -1

    fun report(readBytes: Long) {
        val percent = percentOf(readBytes, totalBytes)
        if (percent <= lastPercent) return
        lastPercent = percent
        onProgress(percent / 100f)
    }
}

private class AnalysisRun(private val pipeline: RadarPipeline, private val sampleEveryMs: Long) {
    private val heat = HeatCounter()
    private var summary = SummaryAccumulator()
    private var nextSampleMs = sampleEveryMs

    init {
        pipeline.onLinkState(true, 0L)
    }

    fun feed(record: BsrecRecord) {
        sampleUntil(record.tMsSinceStart)
        replayRecord(record, pipeline, record.tMsSinceStart * NANOS_PER_MS)
        summary = afterRecord(summary, record)
    }

    fun finish(coverage: List<CoverageSector>): RecordingAnalysis = RecordingAnalysis(summaryOf(summary), heat.toGrid(), coverage)

    private fun sampleUntil(recordMs: Long) {
        nextSampleMs = nextSampleAfterSkip(nextSampleMs, recordMs)
        while (nextSampleMs <= recordMs) {
            sample(nextSampleMs)
            nextSampleMs += sampleEveryMs
        }
    }

    private fun sample(atMs: Long) {
        val scene = pipeline.scene(atMs * NANOS_PER_MS)
        if (!countsForAnalysis(scene)) return
        summary = afterMotion(summary, scene.motion)
        scene.blips.mapNotNull(::heatCellOf).forEach(heat::add)
    }
}
```

- [ ] **Step 6: Run the tests**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.phone.viewer.*" --console=plain`
Expected: PASS (7 + 5 + 8 + 2 + 13 new tests, plus Task 11's 14).

If `the crossing walker heats cells at its real distance` fails on the distance, check `heatCellOf` against `polarToPx` in `shared/radar/RadarGeometry.kt` before touching the test. Print `hotCells(analysis.heat)` in the assertion message to see where the heat landed. Both must use `x = r·sin(bearing)` and `y = r·cos(bearing)`.

- [ ] **Step 7: Commit**

```bash
cd /c/personal/blindside
git add watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/viewer \
  watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/viewer
git commit -m "feat: mapa de calor y resumen de grabaciones en una sola pasada, sin caídas con archivos dañados"
```

---

### Task 13: Viewer tab (replay, heat map, summary)

**Files:**
- Create: `phone/ui/viewer/ViewerTab.kt`
- Create: `phone/ui/viewer/ReplayPanel.kt`
- Create: `phone/ui/viewer/HeatmapView.kt`

**Interfaces:**
- Consumes:
  - Task 7: `RadarView`, `EmptyState`, `SectionCard`, `NumberText`, `formatClock`, `formatPercent`.
  - Task 10: `RecordingsRepository.file`, `recordingTitle`.
  - Task 11: `ReplayCursor`, `Playback`, `PLAYBACK_SPEEDS`, `advanced`, `toggledPlay`, `withSpeed`, `seekedTo`.
  - Task 12: `analyzeFile`, `AnalysisState`, `RecordingAnalysis`, `HEAT_RAMP`, `heatLevel`, `heatLegendLabels`, `heatDescription`, `hotCells`, `cellRectPx`, `summaryRows`, `SummaryRow`, `ANALYSIS_SAMPLE_MS`.
  - Plan 05: `toDrawModel`, `sectorArc`, `drawRadar`, `TACTICAL_RADAR_COLORS`, `PointPx`.
- Produces (package `…phone.ui.viewer`): `@Composable ViewerTab(name: String?, repository: RecordingsRepository, onPickRecording: () -> Unit)`, `@Composable ReplayPanel(file: File, durationMs: Long)`, `@Composable HeatmapView(analysis: RecordingAnalysis)`.

This task is UI only; Tasks 11 and 12 tested its logic. Its gate is a clean build plus the on-device checklist and E2E (Tasks 15-16).

- [ ] **Step 1: Write the heat map view**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/viewer/HeatmapView.kt`. The heat map sits on the same shared fan as the live radar, with the recorded coverage and no contacts:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.santiquiroz.blindside.phone.ui.common.NumberText
import io.github.santiquiroz.blindside.phone.ui.theme.Text2Color
import io.github.santiquiroz.blindside.phone.viewer.ANALYSIS_SAMPLE_MS
import io.github.santiquiroz.blindside.phone.viewer.HEAT_RAMP
import io.github.santiquiroz.blindside.phone.viewer.HeatGrid
import io.github.santiquiroz.blindside.phone.viewer.RecordingAnalysis
import io.github.santiquiroz.blindside.phone.viewer.cellRectPx
import io.github.santiquiroz.blindside.phone.viewer.heatDescription
import io.github.santiquiroz.blindside.phone.viewer.heatLegendLabels
import io.github.santiquiroz.blindside.phone.viewer.heatLevel
import io.github.santiquiroz.blindside.phone.viewer.hotCells
import io.github.santiquiroz.blindside.shared.radar.PointPx
import io.github.santiquiroz.blindside.shared.radar.TACTICAL_RADAR_COLORS
import io.github.santiquiroz.blindside.shared.radar.drawRadar
import io.github.santiquiroz.blindside.shared.radar.sectorArc
import io.github.santiquiroz.blindside.shared.radar.toDrawModel

private const val NO_HEAT_TEXT = "Sin contactos en esta grabación."
private const val HEAT_EXPLANATION = "Tiempo con un contacto en cada celda de 0,5 m, en el marco del cuerpo."
private val SWATCH = 20.dp

@Composable
fun HeatmapView(analysis: RecordingAnalysis) {
    if (analysis.heat.total == 0) {
        Text(NO_HEAT_TEXT, color = Text2Color)
        return
    }
    val sectors = remember(analysis) { analysis.coverage.map(::sectorArc) }
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).semantics { contentDescription = heatDescription(analysis.heat) }) {
        val frame = toDrawModel(null, size.width, size.height, PointPx(0f, 0f), showContacts = false).copy(sectors = sectors, dimmed = false)
        drawRadar(frame, TACTICAL_RADAR_COLORS)
        drawHeatCells(analysis.heat, frame.origin, frame.radiusPx)
    }
    HeatLegend(analysis.heat.max)
    Text(HEAT_EXPLANATION, style = MaterialTheme.typography.bodySmall, color = Text2Color)
}

private fun DrawScope.drawHeatCells(grid: HeatGrid, origin: PointPx, radiusPx: Float) {
    val max = grid.max
    hotCells(grid).forEach { (cell, count) ->
        val level = heatLevel(count, max) ?: return@forEach
        val rect = cellRectPx(cell, origin, radiusPx)
        drawRect(Color(HEAT_RAMP[level]), topLeft = Offset(rect.left, rect.top), size = Size(rect.size, rect.size))
    }
}

@Composable
private fun HeatLegend(max: Int) {
    val labels = heatLegendLabels(max, ANALYSIS_SAMPLE_MS)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        HEAT_RAMP.forEachIndexed { level, argb ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(SWATCH).background(Color(argb)))
                NumberText(labels[level], style = MaterialTheme.typography.labelSmall, color = Text2Color)
            }
        }
    }
}
```

- [ ] **Step 2: Write the replay panel**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/viewer/ReplayPanel.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.phone.ui.common.NumberText
import io.github.santiquiroz.blindside.phone.ui.common.formatClock
import io.github.santiquiroz.blindside.phone.ui.radar.RadarView
import io.github.santiquiroz.blindside.phone.ui.theme.AlertRedColor
import io.github.santiquiroz.blindside.phone.ui.theme.Text2Color
import io.github.santiquiroz.blindside.phone.viewer.PLAYBACK_SPEEDS
import io.github.santiquiroz.blindside.phone.viewer.Playback
import io.github.santiquiroz.blindside.phone.viewer.ReplayCursor
import io.github.santiquiroz.blindside.phone.viewer.advanced
import io.github.santiquiroz.blindside.phone.viewer.seekedTo
import io.github.santiquiroz.blindside.phone.viewer.toggledPlay
import io.github.santiquiroz.blindside.phone.viewer.withSpeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

private val PLAY_BUTTON_SIZE = 64.dp
private val PLAY_ICON_SIZE = 32.dp
private const val MIN_SLIDER_RANGE = 1f

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun ReplayPanel(file: File, durationMs: Long) {
    val cursor = remember(file) { runCatching { ReplayCursor { BufferedInputStream(FileInputStream(file)) } }.getOrNull() }
    if (cursor == null) {
        Text("No se pudo abrir la reproducción de esta grabación.", color = AlertRedColor)
        return
    }
    DisposableEffect(cursor) { onDispose { cursor.close() } }
    var playback by remember(file) { mutableStateOf(Playback(durationMs = durationMs)) }
    var scene by remember(file) { mutableStateOf<RadarScene?>(null) }
    var seeking by remember(file) { mutableStateOf(false) }
    val replayThread = remember { Dispatchers.Default.limitedParallelism(1) }
    LaunchedEffect(cursor) {
        var shownMs = 0L
        // Conflating keeps only the newest position, so a long scrub never queues a pile of slow backward seeks.
        snapshotFlow { playback.positionMs }.conflate().collect { target ->
            seeking = target < shownMs
            scene = withContext(replayThread) { runCatching { cursor.seekTo(target) }.getOrNull() } ?: scene
            shownMs = target
            seeking = false
        }
    }
    LaunchedEffect(playback.playing) {
        if (playback.playing) runPlaybackClock(current = { playback }, update = { playback = it })
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            RadarView(scene, Modifier.fillMaxSize())
            if (seeking) Text("Buscando…", Modifier.align(Alignment.TopEnd), color = Text2Color)
        }
        Transport(playback, onChange = { playback = it })
    }
}

private suspend fun runPlaybackClock(current: () -> Playback, update: (Playback) -> Unit) {
    var lastFrameMs = withFrameMillis { it }
    while (current().playing) {
        val frameMs = withFrameMillis { it }
        update(advanced(current(), frameMs - lastFrameMs))
        lastFrameMs = frameMs
    }
}

@Composable
private fun Transport(playback: Playback, onChange: (Playback) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledIconButton(onClick = { onChange(toggledPlay(playback)) }, modifier = Modifier.size(PLAY_BUTTON_SIZE)) {
            Icon(
                imageVector = if (playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playback.playing) "Pausar" else "Reproducir",
                modifier = Modifier.size(PLAY_ICON_SIZE),
            )
        }
        SpeedSelector(playback.speed) { onChange(withSpeed(playback, it)) }
    }
    Slider(
        value = playback.positionMs.toFloat(),
        onValueChange = { onChange(seekedTo(playback, it.toLong())) },
        valueRange = 0f..playback.durationMs.toFloat().coerceAtLeast(MIN_SLIDER_RANGE),
        modifier = Modifier.semantics { contentDescription = "Línea de tiempo" },
    )
    Row(Modifier.fillMaxWidth()) {
        NumberText(formatClock(playback.positionMs), style = MaterialTheme.typography.bodySmall, color = Text2Color)
        Spacer(Modifier.weight(1f))
        NumberText(formatClock(playback.durationMs), style = MaterialTheme.typography.bodySmall, color = Text2Color)
    }
}

@Composable
private fun SpeedSelector(selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        PLAYBACK_SPEEDS.forEachIndexed { index, speed ->
            SegmentedButton(
                selected = speed == selected,
                onClick = { onSelect(speed) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = PLAYBACK_SPEEDS.size),
            ) { NumberText("$speed×") }
        }
    }
}
```

- [ ] **Step 3: Write the viewer tab**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/viewer/ViewerTab.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.santiquiroz.blindside.phone.recordings.RecordingsRepository
import io.github.santiquiroz.blindside.phone.recordings.recordingTitle
import io.github.santiquiroz.blindside.phone.ui.common.EmptyState
import io.github.santiquiroz.blindside.phone.ui.common.NumberText
import io.github.santiquiroz.blindside.phone.ui.common.SectionCard
import io.github.santiquiroz.blindside.phone.ui.common.formatPercent
import io.github.santiquiroz.blindside.phone.ui.theme.Text2Color
import io.github.santiquiroz.blindside.phone.viewer.AnalysisState
import io.github.santiquiroz.blindside.phone.viewer.RecordingAnalysis
import io.github.santiquiroz.blindside.phone.viewer.SummaryRow
import io.github.santiquiroz.blindside.phone.viewer.analyzeFile
import io.github.santiquiroz.blindside.phone.viewer.summaryRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val VIEWER_EMPTY_TEXT = "Elige una grabación para verla aquí: reproducción, mapa de calor y resumen."

@Composable
fun ViewerTab(name: String?, repository: RecordingsRepository, onPickRecording: () -> Unit) {
    val file = remember(name) { name?.let(repository::file) }
    if (file == null) {
        EmptyState(VIEWER_EMPTY_TEXT, "Ver grabaciones", Icons.Filled.Insights, onPickRecording)
        return
    }
    val analysis by produceState<AnalysisState>(AnalysisState.Running(0f), file) {
        value = withContext(Dispatchers.Default) { analyzeFile(file) { progress -> value = AnalysisState.Running(progress) } }
    }
    when (val current = analysis) {
        is AnalysisState.Running -> AnalysisProgress(current.progress)
        is AnalysisState.Failed -> EmptyState("No se pudo abrir: ${current.reason}.", "Volver a grabaciones", Icons.Filled.ErrorOutline, onPickRecording)
        is AnalysisState.Done -> ViewerContent(file, current.analysis)
    }
}

@Composable
private fun AnalysisProgress(progress: Float) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("Analizando la grabación…", style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        NumberText(formatPercent(progress.toDouble()), color = Text2Color)
    }
}

@Composable
private fun ViewerContent(file: File, analysis: RecordingAnalysis) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(recordingTitle(file.name), style = MaterialTheme.typography.headlineSmall)
        ReplayPanel(file, analysis.summary.durationMs)
        SectionCard("Mapa de calor") { HeatmapView(analysis) }
        SectionCard("Resumen") { summaryRows(analysis.summary).forEach { SummaryLine(it) } }
    }
}

@Composable
private fun SummaryLine(row: SummaryRow) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(row.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Text2Color)
        NumberText(row.value, style = MaterialTheme.typography.bodyMedium)
    }
}
```

- [ ] **Step 4: Build**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest :phone-app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
cd /c/personal/blindside
git add watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/viewer
git commit -m "feat: pestaña Visor con reproducción por velocidades, mapa de calor sobre el lienzo compartido y resumen"
```

---

### Task 14: App shell (bottom navigation, launch decision, permissions, bridge wiring)

**Files:**
- Create: `phone/nav/PhoneNav.kt`
- Create: `phone/nav/PhoneLaunch.kt`
- Create: `phone/PhoneDeps.kt`
- Create: `phone/ui/PhoneActionsFactory.kt`
- Create: `phone/ui/PhoneNavigationBar.kt`
- Create: `phone/ui/PhoneApp.kt`
- Modify: `phone/MainActivity.kt`
- Test: `phoneTest/nav/PhoneNavTest.kt`, `phoneTest/nav/PhoneLaunchTest.kt`

**Interfaces:**
- Consumes:
  - Tabs: `RadarTab`, `RecordingsTab`, `ViewerTab`, `BeltTab`.
  - Phone state and actions: `PhoneUiState`, `PhoneActions`, `ReportSceneVisibility`, `PhoneSessionCommands`, `startPhoneSession`, `PhoneStore`, `WatchStatusStore`, `PhoneBridge`, `RecordingsRepository`, `phonePrefsRepository`, `LinkSupport`, `linkSupportAfterInfo`.
  - Theme: `BlindsideTheme`, `rememberMotionDurationMs`.
  - Plan 05: `SessionStore`, `SessionPurpose`, `activeRecordingName`, `settingsRepository`, `adoptingNewer`, `recordingsDir`, `PERMISSION_BLUETOOTH_SCAN`, `PERMISSION_BLUETOOTH_CONNECT`, `PERMISSION_POST_NOTIFICATIONS`, `bluetoothGranted`, `shouldRequestPermissions`.
- Produces:
  - Package `…phone.nav`:
    - `enum class PhoneTab { RADAR, RECORDINGS, VIEWER, BELT }`, `data class PhoneNav(tab = RADAR, viewing: String? = null)`;
    - `selectTab(nav, tab)`, `openRecording(nav, name)`, `afterDelete(nav, name)`, `backFrom(nav): PhoneNav?`, `tabLabel(tab)`, `sceneWanted(tab)`, `navToStrings(nav)`, `navFromStrings(values)`;
    - `enum class PhoneLaunch { AUTO_START, NONE }`, `val PHONE_PERMISSIONS: Array<String>`, `phoneLaunch(settings, sessionRunning, bluetoothGranted, linkSupport)`.
  - Package `…phone`: `class PhoneDeps(context)` with `settings`, `prefs`, `bridge`, `recordings`.
  - Package `…phone.ui`: `@Composable rememberPhoneActions(deps, onBluetoothBlocked): PhoneActions`, `fun grantsOf(context): Map<String, Boolean>`, `@Composable PhoneNavigationBar(selected, onSelect)`, `@Composable PhoneApp(deps)`.

- [ ] **Step 1: Write the failing tests**

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/nav/PhoneNavTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PhoneNavTest {
    @Test
    fun `the four destinations follow the spec order with their names`() {
        assertEquals(listOf("Radar", "Grabaciones", "Visor", "Cinturón"), PhoneTab.entries.map(::tabLabel))
    }

    @Test
    fun `the app opens on the radar with nothing to view`() {
        assertEquals(PhoneNav(PhoneTab.RADAR, viewing = null), PhoneNav())
    }

    @Test
    fun `opening a recording jumps to the viewer with it`() {
        assertEquals(PhoneNav(PhoneTab.VIEWER, "a.bsrec"), openRecording(PhoneNav(PhoneTab.RECORDINGS), "a.bsrec"))
    }

    @Test
    fun `switching tabs keeps the recording in the viewer`() {
        assertEquals(PhoneNav(PhoneTab.BELT, "a.bsrec"), selectTab(PhoneNav(PhoneTab.VIEWER, "a.bsrec"), PhoneTab.BELT))
    }

    @Test
    fun `deleting the open recording empties the viewer and other deletions leave it`() {
        val viewing = PhoneNav(PhoneTab.RECORDINGS, "a.bsrec")
        assertEquals(PhoneNav(PhoneTab.RECORDINGS, null), afterDelete(viewing, "a.bsrec"))
        assertEquals(viewing, afterDelete(viewing, "b.bsrec"))
    }

    @Test
    fun `back returns to the radar and then leaves the app`() {
        assertEquals(PhoneNav(PhoneTab.RADAR, "a.bsrec"), backFrom(PhoneNav(PhoneTab.VIEWER, "a.bsrec")))
        assertNull(backFrom(PhoneNav(PhoneTab.RADAR)))
    }

    @Test
    fun `navigation survives being saved and a broken save falls back to the radar`() {
        val nav = PhoneNav(PhoneTab.VIEWER, "a.bsrec")
        assertEquals(nav, navFromStrings(navToStrings(nav)))
        assertEquals(PhoneNav(PhoneTab.BELT), navFromStrings(navToStrings(PhoneNav(PhoneTab.BELT))))
        assertEquals(PhoneNav(), navFromStrings(listOf("PARTY")))
        assertEquals(PhoneNav(), navFromStrings(emptyList()))
    }

    @Test
    fun `only the radar and belt tabs keep live scenes coming`() {
        assertEquals(listOf(true, false, false, true), PhoneTab.entries.map(::sceneWanted))
    }
}
```

Create `watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/nav/PhoneLaunchTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.nav

import io.github.santiquiroz.blindside.phone.settings.LinkSupport
import io.github.santiquiroz.blindside.shared.permissions.PERMISSION_ACTIVITY_RECOGNITION
import io.github.santiquiroz.blindside.shared.permissions.PERMISSION_BLUETOOTH_CONNECT
import io.github.santiquiroz.blindside.shared.permissions.PERMISSION_BLUETOOTH_SCAN
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhoneLaunchTest {
    private val paired = AppSettings(beltAddress = "AA:BB:CC:DD:EE:FF")

    @Test
    fun `a paired dual-link belt with bluetooth granted starts the radar on open`() {
        assertEquals(PhoneLaunch.AUTO_START, phoneLaunch(paired, sessionRunning = false, bluetoothGranted = true, linkSupport = LinkSupport.DUAL_LINK))
    }

    @Test
    fun `the first pairing waits for the user`() {
        assertEquals(PhoneLaunch.NONE, phoneLaunch(AppSettings(), sessionRunning = false, bluetoothGranted = true, linkSupport = LinkSupport.DUAL_LINK))
    }

    @Test
    fun `a missing bluetooth grant waits for a tap so the prompt is seen`() {
        assertEquals(PhoneLaunch.NONE, phoneLaunch(paired, sessionRunning = false, bluetoothGranted = false, linkSupport = LinkSupport.DUAL_LINK))
    }

    @Test
    fun `turning auto start off always waits`() {
        assertEquals(PhoneLaunch.NONE, phoneLaunch(paired.copy(autoStartRadar = false), sessionRunning = false, bluetoothGranted = true, linkSupport = LinkSupport.DUAL_LINK))
    }

    @Test
    fun `a session already running is never started twice`() {
        assertEquals(PhoneLaunch.NONE, phoneLaunch(paired, sessionRunning = true, bluetoothGranted = true, linkSupport = LinkSupport.DUAL_LINK))
    }

    @Test
    fun `a belt that holds one link never auto-starts, so the phone cannot take it from the watch`() {
        assertEquals(PhoneLaunch.NONE, phoneLaunch(paired, sessionRunning = false, bluetoothGranted = true, linkSupport = LinkSupport.SINGLE_LINK))
    }

    @Test
    fun `a firmware the phone has not seen yet waits for a tap`() {
        assertEquals(PhoneLaunch.NONE, phoneLaunch(paired, sessionRunning = false, bluetoothGranted = true, linkSupport = LinkSupport.UNKNOWN))
    }

    @Test
    fun `the phone asks for bluetooth and notifications, never for physical activity`() {
        assertTrue(PERMISSION_BLUETOOTH_SCAN in PHONE_PERMISSIONS && PERMISSION_BLUETOOTH_CONNECT in PHONE_PERMISSIONS)
        assertFalse(PERMISSION_ACTIVITY_RECOGNITION in PHONE_PERMISSIONS)
    }
}
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.phone.nav.*" --console=plain`
Expected: FAIL with `Unresolved reference 'PhoneTab'`.

- [ ] **Step 3: Write navigation and launch rules**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/nav/PhoneNav.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.nav

enum class PhoneTab { RADAR, RECORDINGS, VIEWER, BELT }

data class PhoneNav(val tab: PhoneTab = PhoneTab.RADAR, val viewing: String? = null)

fun selectTab(nav: PhoneNav, tab: PhoneTab): PhoneNav = nav.copy(tab = tab)

fun openRecording(nav: PhoneNav, name: String): PhoneNav = nav.copy(tab = PhoneTab.VIEWER, viewing = name)

fun afterDelete(nav: PhoneNav, name: String): PhoneNav = if (nav.viewing == name) nav.copy(viewing = null) else nav

// Back walks to the radar first, so one more back from the radar leaves the app.
fun backFrom(nav: PhoneNav): PhoneNav? = if (nav.tab == PhoneTab.RADAR) null else nav.copy(tab = PhoneTab.RADAR)

fun tabLabel(tab: PhoneTab): String = when (tab) {
    PhoneTab.RADAR -> "Radar"
    PhoneTab.RECORDINGS -> "Grabaciones"
    PhoneTab.VIEWER -> "Visor"
    PhoneTab.BELT -> "Cinturón"
}

fun navToStrings(nav: PhoneNav): List<String> = listOfNotNull(nav.tab.name, nav.viewing)

fun navFromStrings(values: List<String>): PhoneNav =
    PhoneNav(PhoneTab.entries.firstOrNull { it.name == values.getOrNull(0) } ?: PhoneTab.RADAR, values.getOrNull(1))

// Only the radar and belt tabs show the live scene and its counters; the others must not keep the pipeline ticking.
fun sceneWanted(tab: PhoneTab): Boolean = tab == PhoneTab.RADAR || tab == PhoneTab.BELT
```

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/nav/PhoneLaunch.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.nav

import io.github.santiquiroz.blindside.phone.settings.LinkSupport
import io.github.santiquiroz.blindside.shared.permissions.PERMISSION_BLUETOOTH_CONNECT
import io.github.santiquiroz.blindside.shared.permissions.PERMISSION_BLUETOOTH_SCAN
import io.github.santiquiroz.blindside.shared.permissions.PERMISSION_POST_NOTIFICATIONS
import io.github.santiquiroz.blindside.shared.settings.AppSettings

enum class PhoneLaunch { AUTO_START, NONE }

// Deviation P4: the phone reads no sensors, so it never asks for physical activity.
val PHONE_PERMISSIONS: Array<String> = arrayOf(PERMISSION_BLUETOOTH_SCAN, PERMISSION_BLUETOOTH_CONNECT, PERMISSION_POST_NOTIFICATIONS)

fun phoneLaunch(settings: AppSettings, sessionRunning: Boolean, bluetoothGranted: Boolean, linkSupport: LinkSupport): PhoneLaunch = when {
    sessionRunning || !bluetoothGranted -> PhoneLaunch.NONE
    wantsAutoStart(settings, linkSupport) -> PhoneLaunch.AUTO_START
    else -> PhoneLaunch.NONE
}

// The first pairing needs the user on screen, and on firmware 0.1.0 a phone radar would take the watch's only slot (Deviation P5).
private fun wantsAutoStart(settings: AppSettings, linkSupport: LinkSupport): Boolean =
    settings.autoStartRadar && settings.beltAddress != null && linkSupport == LinkSupport.DUAL_LINK
```

- [ ] **Step 4: Run the pure tests**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest --tests "io.github.santiquiroz.blindside.phone.nav.*" --console=plain`
Expected: PASS (8 + 8 tests).

- [ ] **Step 5: Write the dependencies holder and the actions factory**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/PhoneDeps.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone

import android.content.Context
import io.github.santiquiroz.blindside.phone.bridge.PhoneBridge
import io.github.santiquiroz.blindside.phone.recordings.RecordingsRepository
import io.github.santiquiroz.blindside.phone.settings.PhonePrefsRepository
import io.github.santiquiroz.blindside.phone.settings.phonePrefsRepository
import io.github.santiquiroz.blindside.shared.recording.recordingsDir
import io.github.santiquiroz.blindside.shared.settings.SettingsRepository
import io.github.santiquiroz.blindside.shared.settings.settingsRepository

class PhoneDeps(context: Context) {
    val settings: SettingsRepository = context.settingsRepository()
    val prefs: PhonePrefsRepository = context.phonePrefsRepository()
    val bridge: PhoneBridge = PhoneBridge(context)
    val recordings: RecordingsRepository = RecordingsRepository(recordingsDir(context))
}
```

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/PhoneActionsFactory.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.santiquiroz.blindside.phone.PhoneDeps
import io.github.santiquiroz.blindside.phone.nav.PHONE_PERMISSIONS
import io.github.santiquiroz.blindside.phone.session.PhoneSessionCommands
import io.github.santiquiroz.blindside.phone.session.startPhoneSession
import io.github.santiquiroz.blindside.shared.permissions.bluetoothGranted
import io.github.santiquiroz.blindside.shared.permissions.shouldRequestPermissions
import io.github.santiquiroz.blindside.shared.session.SessionPurpose
import kotlinx.coroutines.launch

@Composable
fun rememberPhoneActions(deps: PhoneDeps, onBluetoothBlocked: (Boolean) -> Unit): PhoneActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val startSession = rememberSessionStarter(onBluetoothBlocked)
    return remember(deps, startSession) {
        PhoneActions(
            startRadar = { startSession(SessionPurpose.GAME) },
            startDiagnostic = { startSession(SessionPurpose.DIAGNOSTIC) },
            stop = { PhoneSessionCommands.stop(context) },
            toggleEliminated = { context.startService(PhoneSessionCommands.toggleEliminatedIntent(context)) },
            retryLink = { PhoneSessionCommands.retryLink(context) },
            sendCommand = { PhoneSessionCommands.send(context, it) },
            refreshInfo = { PhoneSessionCommands.refreshInfo(context) },
            updateSettings = { transform -> scope.launch { deps.settings.update(transform) } },
            updatePrefs = { transform -> scope.launch { deps.prefs.update(transform) } },
            openAppSettings = { openAppSettings(context) },
        )
    }
}

fun grantsOf(context: Context): Map<String, Boolean> =
    PHONE_PERMISSIONS.associateWith { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

// Notifications ride along with the Bluetooth prompt; the foreground service only starts once Bluetooth is granted.
@Composable
private fun rememberSessionStarter(onBluetoothBlocked: (Boolean) -> Unit): (SessionPurpose) -> Unit {
    val context = LocalContext.current
    val pending = remember { mutableStateOf(SessionPurpose.GAME) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted = bluetoothGranted(grants)
        onBluetoothBlocked(!granted)
        if (granted) startPhoneSession(context, pending.value)
    }
    return remember(launcher) {
        { purpose: SessionPurpose ->
            pending.value = purpose
            if (shouldRequestPermissions(grantsOf(context))) launcher.launch(PHONE_PERMISSIONS) else startPhoneSession(context, purpose)
        }
    }
}

private fun openAppSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
}
```

- [ ] **Step 6: Write the navigation bar and the app scaffold**

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/PhoneNavigationBar.kt`:

```kotlin
package io.github.santiquiroz.blindside.phone.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.santiquiroz.blindside.phone.nav.PhoneTab
import io.github.santiquiroz.blindside.phone.nav.tabLabel
import io.github.santiquiroz.blindside.phone.ui.theme.AccentColor
import io.github.santiquiroz.blindside.phone.ui.theme.Surface2Color
import io.github.santiquiroz.blindside.phone.ui.theme.SurfaceColor
import io.github.santiquiroz.blindside.phone.ui.theme.Text2Color

@Composable
fun PhoneNavigationBar(selected: PhoneTab, onSelect: (PhoneTab) -> Unit) {
    NavigationBar(containerColor = SurfaceColor) {
        PhoneTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tabIcon(tab), contentDescription = null) },
                label = { Text(tabLabel(tab)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AccentColor,
                    selectedTextColor = AccentColor,
                    indicatorColor = Surface2Color,
                    unselectedIconColor = Text2Color,
                    unselectedTextColor = Text2Color,
                ),
            )
        }
    }
}

private fun tabIcon(tab: PhoneTab): ImageVector = when (tab) {
    PhoneTab.RADAR -> Icons.Filled.Radar
    PhoneTab.RECORDINGS -> Icons.Filled.FolderOpen
    PhoneTab.VIEWER -> Icons.Filled.Insights
    PhoneTab.BELT -> Icons.Filled.SettingsInputAntenna
}
```

Create `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/PhoneApp.kt`. Besides the scaffold, it owns four effects:
- **Scene visibility**, keyed by the selected tab (Review Focus 7).
- **The firmware support** learned from each `info` read, stored in the phone preferences.
- **The shared-settings publisher**, which runs while the app is open, like the watch's.
- **The auto-start**, gated by that firmware support.

```kotlin
package io.github.santiquiroz.blindside.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.santiquiroz.blindside.phone.PhoneDeps
import io.github.santiquiroz.blindside.phone.bridge.WatchStatusStore
import io.github.santiquiroz.blindside.phone.nav.PhoneLaunch
import io.github.santiquiroz.blindside.phone.nav.PhoneNav
import io.github.santiquiroz.blindside.phone.nav.PhoneTab
import io.github.santiquiroz.blindside.phone.nav.afterDelete
import io.github.santiquiroz.blindside.phone.nav.backFrom
import io.github.santiquiroz.blindside.phone.nav.navFromStrings
import io.github.santiquiroz.blindside.phone.nav.navToStrings
import io.github.santiquiroz.blindside.phone.nav.openRecording
import io.github.santiquiroz.blindside.phone.nav.phoneLaunch
import io.github.santiquiroz.blindside.phone.nav.sceneWanted
import io.github.santiquiroz.blindside.phone.nav.selectTab
import io.github.santiquiroz.blindside.phone.session.PhoneStore
import io.github.santiquiroz.blindside.phone.settings.PhonePrefs
import io.github.santiquiroz.blindside.phone.settings.linkSupportAfterInfo
import io.github.santiquiroz.blindside.phone.ui.belt.BeltTab
import io.github.santiquiroz.blindside.phone.ui.common.ReportSceneVisibility
import io.github.santiquiroz.blindside.phone.ui.radar.RadarTab
import io.github.santiquiroz.blindside.phone.ui.recordings.RecordingsTab
import io.github.santiquiroz.blindside.phone.ui.theme.rememberMotionDurationMs
import io.github.santiquiroz.blindside.phone.ui.viewer.ViewerTab
import io.github.santiquiroz.blindside.shared.permissions.bluetoothGranted
import io.github.santiquiroz.blindside.shared.session.SessionStore
import io.github.santiquiroz.blindside.shared.session.activeRecordingName
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import io.github.santiquiroz.blindside.shared.settings.adoptingNewer

private const val TAB_FADE_MS = 200
private val PHONE_NAV_SAVER = listSaver<PhoneNav, String>(save = { navToStrings(it) }, restore = { navFromStrings(it) })

@Composable
fun PhoneApp(deps: PhoneDeps) {
    var bluetoothBlocked by remember { mutableStateOf(false) }
    val state = collectPhoneUiState(deps, bluetoothBlocked)
    val actions = rememberPhoneActions(deps, onBluetoothBlocked = { bluetoothBlocked = it })
    var nav by rememberSaveable(stateSaver = PHONE_NAV_SAVER) { mutableStateOf(PhoneNav()) }
    ReportSceneVisibility(sceneWanted(nav.tab))
    RememberLinkSupport(deps, state.phone.infoJson)
    BridgeWhileOpen(deps)
    AutoStartOnOpen(deps, actions.startRadar)
    BackHandler(enabled = backFrom(nav) != null) { backFrom(nav)?.let { nav = it } }
    Scaffold(bottomBar = { PhoneNavigationBar(nav.tab) { nav = selectTab(nav, it) } }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            TabContent(nav, state, actions, deps) { nav = it }
        }
    }
}

@Composable
private fun collectPhoneUiState(deps: PhoneDeps, bluetoothBlocked: Boolean): PhoneUiState {
    val session by SessionStore.state.collectAsStateWithLifecycle()
    val phone by PhoneStore.state.collectAsStateWithLifecycle()
    val settings by deps.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val prefs by deps.prefs.prefs.collectAsStateWithLifecycle(initialValue = PhonePrefs())
    val watchStatus by WatchStatusStore.state.collectAsStateWithLifecycle()
    return PhoneUiState(session, phone, settings, prefs, watchStatus, bluetoothBlocked)
}

@Composable
private fun TabContent(nav: PhoneNav, state: PhoneUiState, actions: PhoneActions, deps: PhoneDeps, onNav: (PhoneNav) -> Unit) {
    val fadeMs = rememberMotionDurationMs(TAB_FADE_MS)
    Crossfade(targetState = nav.tab, animationSpec = tween(fadeMs), label = "tab") { tab ->
        when (tab) {
            PhoneTab.RADAR -> RadarTab(state, actions)
            PhoneTab.RECORDINGS -> RecordingsTab(
                repository = deps.recordings,
                bridge = deps.bridge,
                activeName = activeRecordingName(state.session),
                onOpen = { onNav(openRecording(nav, it)) },
                onDeleted = { onNav(afterDelete(nav, it)) },
            )
            PhoneTab.VIEWER -> ViewerTab(nav.viewing, deps.recordings, onPickRecording = { onNav(selectTab(nav, PhoneTab.RECORDINGS)) })
            PhoneTab.BELT -> BeltTab(state, actions, deps.bridge)
        }
    }
}

@Composable
private fun RememberLinkSupport(deps: PhoneDeps, infoJson: String?) {
    LaunchedEffect(infoJson) {
        if (infoJson != null) deps.prefs.update { it.copy(linkSupport = linkSupportAfterInfo(it.linkSupport, infoJson)) }
    }
}

// The watch may have changed the shared settings while the phone was closed; its status may already be waiting too.
@Composable
private fun BridgeWhileOpen(deps: PhoneDeps) {
    LaunchedEffect(deps) {
        deps.bridge.latestSharedSettings()?.let { remote -> deps.settings.update { it.adoptingNewer(remote) } }
        deps.bridge.latestStatus()?.let(WatchStatusStore::offer)
    }
    LaunchedEffect(deps) { deps.bridge.publishSharedSettings(deps.settings) }
}

// Runs once per opening: a radar the user stopped must not restart itself when the app comes back.
@Composable
private fun AutoStartOnOpen(deps: PhoneDeps, startRadar: () -> Unit) {
    val context = LocalContext.current
    var handled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (handled) return@LaunchedEffect
        handled = true
        val launch = phoneLaunch(
            deps.settings.current(),
            SessionStore.state.value.running,
            bluetoothGranted(grantsOf(context)),
            deps.prefs.current().linkSupport,
        )
        if (launch == PhoneLaunch.AUTO_START) startRadar()
    }
}
```

- [ ] **Step 7: Point the activity at the app**

Replace `watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/MainActivity.kt` with:

```kotlin
package io.github.santiquiroz.blindside.phone

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.santiquiroz.blindside.phone.ui.PhoneApp
import io.github.santiquiroz.blindside.phone.ui.theme.BlindsideTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
        )
        val deps = PhoneDeps(applicationContext)
        setContent { BlindsideTheme { PhoneApp(deps) } }
    }
}
```

- [ ] **Step 8: Run every phone test and build**

Run: `cd /c/personal/blindside/watch && ./gradlew :phone-app:testDebugUnitTest :phone-app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`, 159 phone tests pass.

```bash
cd /c/personal/blindside/watch
grep -rn "ReportSceneVisibility(" phone-app/src/main | grep -v "fun ReportSceneVisibility"
```

Expected: exactly one call, in `PhoneApp.kt`.

- [ ] **Step 9: Commit**

```bash
cd /c/personal/blindside
git add watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/nav \
  watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/PhoneDeps.kt \
  watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/PhoneActionsFactory.kt \
  watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/PhoneNavigationBar.kt \
  watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/ui/PhoneApp.kt \
  watch/phone-app/src/main/kotlin/io/github/santiquiroz/blindside/phone/MainActivity.kt \
  watch/phone-app/src/test/kotlin/io/github/santiquiroz/blindside/phone/nav
git commit -m "feat: navegación inferior del celular con arranque automático según el firmware, permisos y puente al abrir"
```

---

### Task 15: Guide, CI and full verification

**Files:**
- Create: `watch/phone-app/README.md`
- Modify: `.github/workflows/ci.yml` (append a `phone-app` job)

**Interfaces:**
- Consumes: everything above.
- Produces:
  - The on-device checklist Santiago follows. Each item names the spec §7 step it covers; Task 16 automates the ones marked "E2E".
  - A CI job that runs `:phone-app:testDebugUnitTest :phone-app:assembleDebug`.

- [ ] **Step 1: Write the guide**

Create `watch/phone-app/README.md`:

````markdown
# Blindside: app del celular (`phone-app`)

App para el Galaxy S25 Ultra. Se conecta al cinturón con el **rol celular** (`06 01`), corre su propio `RadarPipeline` sobre la misma sesión compartida que el reloj, graba como el reloj, trae grabaciones del reloj por el Data Layer de Wear OS, las reproduce con mapa de calor y resumen, y muestra el diagnóstico del cinturón.

El reloj tiene prioridad:
- el celular no pide intervalos de conexión después de conectar, así el cinturón le deja 60-100 ms;
- el cinturón descarta los paquetes del celular antes que los del reloj;
- el celular solo arranca solo y solo guía el emparejamiento con el firmware 0.2.0 del cinturón.

## Desviaciones frente al spec

- **`minSdk 33`, no 31.** `android-shared` usa las sobrecargas GATT de API 33 (plan 05, D1); con 31 el manifiesto no fusiona y Android 12 fallaría.
- **"Conectar para diagnóstico".** Enlace con rol celular que escribe `04 00` y no graba, para que "Identificar" funcione.
- **Sin sensores del celular.** El radar del celular usa solo las IMUs del cinturón (spec §8), y sus grabaciones no traen registros del reloj. No pide "actividad física".
- **Arranque automático y guía de emparejamiento solo con firmware 0.2.0.** Con el 0.1.0 el cinturón tiene una conexión y un emparejamiento: el celular le quitaría el lugar al reloj.
- **Fuentes.** Las de `android-shared` (JetBrains Mono NL e IBM Plex Sans).

## Compilar y probar

Desde `watch/`:

```bash
./gradlew :phone-app:testDebugUnitTest   # pruebas JVM
./gradlew :phone-app:assembleDebug       # APK en phone-app/build/outputs/apk/debug/phone-app-debug.apk
```

## Instalar en el celular (depuración por Wi-Fi)

1. En el celular: Ajustes → Acerca del teléfono → Información de software → tocar "Número de compilación" 7 veces. Luego Opciones de desarrollador → Depuración inalámbrica.
2. Emparejar una vez: `adb pair IP:PUERTO_DE_EMPAREJAMIENTO` (el S25 suele estar en `192.168.10.119`).
3. Conectar: `adb connect 192.168.10.119:PUERTO`. Si el puerto rota, usa el nombre mDNS que muestra `adb mdns services`.
4. `adb -s 192.168.10.119:PUERTO install -r phone-app/build/outputs/apk/debug/phone-app-debug.apk`

**El reloj y el celular deben llevar APKs firmados con la misma clave**: el Data Layer solo une apps con el mismo paquete y la misma firma. Compílalos e instálalos desde el mismo PC: los dos usan `~/.android/debug.keystore`.

## Emparejar el celular con el cinturón (sin tocar el botón)

Requiere el firmware 0.2.0 del cinturón. Con el 0.1.0, emparejar el celular desempareja el reloj.

1. Con el radar del reloj en marcha, en el celular: Cinturón → **Pedir al reloj que abra la ventana**. El reloj envía `05` y el cinturón abre su ventana 60 s.
   - Sin reloj: mantén BOOT 3 s en el primer minuto tras encender el cinturón.
2. En el celular: **Iniciar radar**. Concede "Dispositivos cercanos" y, si quieres, las notificaciones.
3. Cuando Android pida la clave, escribe los 6 dígitos de la etiqueta del cinturón.
4. El estado pasa a "Recibiendo datos". En el diagnóstico del cinturón aparecen dos conexiones: reloj y celular.

Si el reloj y otro celular ya están conectados, no queda espacio: desconecta uno.

## Pestañas

- **Radar:**
  - Sin radar en marcha: el botón grande "Iniciar radar".
  - Con el radar en marcha: el abanico compartido con el reloj, la lista de contactos (distancia, rumbo, confianza y antigüedad), el estado del enlace y de los sensores, y el botón grande de modo eliminado.
  - La notificación tiene "Eliminado" y "Detener".
  - Arranca solo al abrir si el cinturón ya está emparejado, tiene el firmware 0.2.0 e "Iniciar radar al abrir" está activo.
- **Grabaciones:**
  - Las `.bsrec` de este celular (`blindside-phone-…`) y las traídas del reloj (`blindside-belt-…`).
  - "Traer del reloj" lista las del reloj y baja la que elijas con barra de progreso y "Cancelar". Una descarga incompleta, cortada o detenida 15 s no deja archivo.
  - Compartir abre el diálogo de Android; Borrar pide confirmación.
  - La grabación en curso dice "Grabando…" y no se abre, comparte ni borra hasta detener el radar.
- **Visor:** reproducción 1×/2×/4×/8× con línea de tiempo deslizable, mapa de calor de contactos (celdas de 0,5 m en el marco del cuerpo) y resumen: duración, contactos por minuto, alertas, huecos de enlace, % caminando/quieto y latencia de confirmación. Un archivo dañado muestra "No se pudo abrir…" y vuelve a Grabaciones.
- **Cinturón:**
  - "Conectar para diagnóstico" conecta sin marcar partida activa, así "Identificar" funciona.
  - Muestra `info` legible, contadores y señal.
  - Permite reiniciar cada radar e identificar el cinturón. "Identificar" se deshabilita con una partida activa en el reloj o en el celular.
  - Incluye los ajustes compartidos con el reloj (mano, ángulos, signos) y los del celular (vibración, apagada por defecto; arranque automático).

## Grabaciones en el celular

```bash
MSYS_NO_PATHCONV=1 adb shell ls -l /sdcard/Android/data/io.github.santiquiroz.blindside/files/recordings/
MSYS_NO_PATHCONV=1 adb pull /sdcard/Android/data/io.github.santiquiroz.blindside/files/recordings/ ./recordings/
```

## Logs útiles

```bash
adb logcat -s BlindsideSession BlindsideService PhoneBridge PhoneBridgeListener BeltGatt BeltLink
```

## E2E autónomo (spec §7, pasos 3-7)

```bash
cd watch && ./gradlew :phone-app:assembleDebug :wear-app:assembleDebug
BELT_PORT=COM6 PASSKEY=799334 bash phone-app/e2e/phone_e2e.sh
```

El informe queda en `phone-app/build/e2e/report.txt`: una línea por paso con PASA, FALLA o NO EJECUTADO y el motivo. Lo que no se ejecute se revisa a mano con la lista de abajo.

## Fuentes

JetBrains Mono NL 2.304 e IBM Plex Sans 1.1.0, ambas bajo SIL Open Font License 1.1. Viven en `watch/android-shared/src/main/res/font` y sus licencias en `licenses/`, en la raíz del repositorio.

## Lista de verificación en el dispositivo

"E2E" marca lo que automatiza `phone_e2e.sh`; repítelo a mano solo si el informe dice NO EJECUTADO.

- [ ] (§7 paso 3, E2E) La app se instala y abre con fondo negro y una barra inferior de 4 pestañas (Radar, Grabaciones, Visor, Cinturón) con icono y texto.
- [ ] Radar sin sesión: botón redondo grande "Iniciar radar" y la pista de emparejamiento.
- [ ] (§7 paso 4, E2E) Emparejamiento sin manos:
  - "Pedir al reloj que abra la ventana" responde "Pedido enviado…".
  - El diálogo de clave aparece y acepta la clave de la etiqueta.
  - Con el radar del reloj detenido, el mismo botón dice "En el reloj, inicia el radar primero…".
- [ ] (§7 paso 5, E2E) La línea `diag` muestra `link0`/`link1` con `role=watch` y `role=phone`, los dos `trusted=1 sub=1`, `sent` subiendo en ambos y `dropped=0` en el reloj. Además:
  - El `itvl` del celular está entre 48 y 80 unidades (60-100 ms).
  - En Cinturón → Diagnóstico, "Conexión celular" está entre 60,0 y 100,0 ms.
- [ ] Radar en vivo:
  - Contactos con forma según la confianza (relleno, contorno, punteado), y los que esperan medida en verde atenuado.
  - Números en monoespaciada; chips de enlace, radares e IMUs con icono.
  - Botón "ME DIERON"/"REAPARECÍ" de 64 dp.
- [ ] Cambiar entre Radar y Cinturón varias veces con el radar en marcha: el radar y los contadores siguen moviéndose.
- [ ] La vibración del celular está apagada por defecto; al activarla en Cinturón → Este celular, vibra en el siguiente radar.
- [ ] Notificación persistente con "Eliminado" y "Detener". "Detener" cierra la grabación y suelta el cinturón; el reloj sigue recibiendo sin cortes.
- [ ] (§7 paso 6, E2E) `am force-stop io.github.santiquiroz.blindside` en el celular: el `diag` muestra el enlace del celular como `[-]`, el reloj sigue con `sent` subiendo y `dropped=0`, y nRF Connect vuelve a ver el cinturón anunciándose.
- [ ] (§7 paso 7, E2E) Grabaciones → Traer del reloj:
  - Aparece la lista y la descarga avanza con progreso hasta que el archivo aparece en Grabaciones; "Ya está" marca las que ya existen.
  - Abrirla en el Visor muestra el resumen sin errores.
- [ ] Una descarga se puede cancelar: no queda archivo ni `.part`. Alejar el reloj a mitad de descarga termina en "el reloj dejó de enviar datos" en unos 15 s.
- [ ] Sin reloj conectado: "Traer del reloj" y "Pedir al reloj…" muestran un mensaje en menos de 10 s, y el radar del celular sigue funcionando.
- [ ] Con el radar del celular en marcha, su grabación dice "Grabando…" y no tiene Compartir ni Borrar.
- [ ] Compartir abre el diálogo de Android. Borrar pide confirmación, la grabación desaparece y, si estaba abierta, el Visor se vacía.
- [ ] Visor:
  - Barra de análisis, play/pausa y 1×/2×/4×/8×.
  - Deslizar hacia atrás muestra "Buscando…" y luego la escena correcta.
  - Mapa de calor sobre el abanico, con leyenda en segundos, y resumen con las 7 filas.
- [ ] Cinturón: "Conectar para diagnóstico" muestra el info legible (firmware, radares, IMUs, conexiones, potencia, MTU), los contadores y "Actualizar".
- [ ] "Reiniciar radar A/B": el chip del radar cae y vuelve en pocos segundos.
- [ ] "Identificar":
  - Está deshabilitado con el radar del celular en marcha o con el reloj en partida.
  - En diagnóstico, con el reloj sin partida, el LED parpadea 3 veces. Si no parpadea, el reloj pudo quedar con una partida abierta: detenla en el reloj.
- [ ] Ajustes compartidos:
  - Cambiar la mano o un ángulo en el celular se ve en Ajustes del reloj, y al revés, con el ángulo exacto.
  - Un celular con el reloj del sistema atrasado no pierde su cambio.
- [ ] Con el firmware 0.1.0 en el cinturón:
  - El celular no arranca solo y Cinturón muestra "Actualiza el firmware…".
  - "Pedir al reloj…" está deshabilitado.
- [ ] Con "Quitar animaciones" activo en Accesibilidad, el cambio de pestaña no tiene fundido.
- [ ] TalkBack lee las pestañas, los botones, las filas de contactos y la descripción del radar.
````

- [ ] **Step 2: Add the CI job**

Append to `.github/workflows/ci.yml` (under `jobs:`, after the last job, keeping two-space indentation):

```yaml
  phone-app:
    name: phone-app unit tests and assembleDebug
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - id: scaffold
        run: |
          if [ -f watch/gradlew ] && [ -f watch/phone-app/build.gradle.kts ] && [ -f watch/android-shared/build.gradle.kts ]; then
            echo "present=true" >> "$GITHUB_OUTPUT"
          else
            echo "present=false" >> "$GITHUB_OUTPUT"
            echo "::notice::watch/phone-app or watch/android-shared not present yet; skipping"
          fi
      - if: steps.scaffold.outputs.present == 'true'
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - if: steps.scaffold.outputs.present == 'true'
        name: install SDK packages with the preinstalled sdkmanager
        run: yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" 'platforms;android-36' 'build-tools;35.0.0' > /dev/null
      - if: steps.scaffold.outputs.present == 'true'
        name: point gradle at the preinstalled SDK
        working-directory: watch
        run: echo "sdk.dir=$ANDROID_HOME" > local.properties
      - if: steps.scaffold.outputs.present == 'true'
        uses: gradle/actions/setup-gradle@v4
      - if: steps.scaffold.outputs.present == 'true'
        working-directory: watch
        run: chmod +x gradlew && ./gradlew :phone-app:testDebugUnitTest :phone-app:assembleDebug --no-daemon
```

Validate the YAML: `cd /c/personal/blindside && python -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci.yml')); print('ok')"`
Expected: `ok`. If PyYAML is missing, run `python -m pip install pyyaml` first.

- [ ] **Step 3: Run the whole watch build**

Run: `cd /c/personal/blindside/watch && ./gradlew :radar-core:test :android-shared:testDebugUnitTest :wear-app:testDebugUnitTest :phone-app:testDebugUnitTest :wear-app:assembleDebug :phone-app:assembleDebug --console=plain`
Expected:
- `BUILD SUCCESSFUL`, and both APKs exist (`ls -l wear-app/build/outputs/apk/debug/ phone-app/build/outputs/apk/debug/`).
- Counts: radar-core 258, android-shared 280, wear-app 46, phone-app 159.

- [ ] **Step 4: Check the owner rules mechanically**

```bash
cd /c/personal/blindside
find watch/phone-app/src watch/android-shared/src -name '*.kt' | xargs wc -l | sort -n | tail -5
grep -rn "/\*\*" watch/phone-app/src watch/android-shared/src || echo "no doc comments"
git log p2/android-shared-watch..HEAD --format=%B | grep -ci "co-authored-by" || true
```

Expected:
- The largest file is under 400 lines.
- `no doc comments`.
- The last line prints `0`.

- [ ] **Step 5: Commit**

```bash
cd /c/personal/blindside
git add watch/phone-app/README.md .github/workflows/ci.yml
git commit -m "docs: guía de la app del celular con desviaciones, CI y lista de verificación ligada al spec §7"
```

---

### Task 16: Autonomous E2E for spec §7 steps 3-7 (adb + serial)

Spec §7 lists the E2E among the phone app's tests (§4), and plan 04's hand-off leaves steps 4-6 to "the phone-app/E2E plan", so this task scripts them.
- **What it does:** it installs and opens the phone app, pairs it hands-free, checks two links in `diag`, force-stops the phone and pulls a watch recording into the Visor.
- **Safety:** it never flashes the belt, never erases bonds and never pairs the phone unless `diag` shows the dual-link firmware. On 0.1.0 a phone bond would replace the watch's.
- **When a device is missing:** each step reports `NO EJECUTADO` with the reason, as spec §7 allows, and the README checklist covers it by hand.
- **Watch regression:** step 2 is plan 05's. This script only requires the watch link to stream before it pairs the phone.

**Files:**
- Create: `watch/phone-app/e2e/e2e_tools.py` (pure helpers: `diag` parsing and pass rules, uiautomator lookups, a self-test)
- Create: `watch/phone-app/e2e/phone_e2e.sh` (the run)

**Interfaces:**
- Consumes:
  - The APKs from Task 15 Step 3.
  - Plan 04's `diag` format (Task 7): ` link0[role=watch trusted=1 sub=1 mtu=255 itvl=36 lat=0 sup=400 sent=1234 dropped=0] link1[-] bonds=1 …`, printed every 5 s at 115200 baud.
  - The phone UI texts:
    - "Cinturón", "Pedir al reloj que abra la ventana", "Pedido enviado…";
    - "Radar", "Iniciar radar";
    - "Grabaciones", "Traer del reloj", "Traer", "Traída: …", "Cerrar";
    - "Reloj · …", "Resumen", "No se pudo abrir…".
- Produces:
  - `python watch/phone-app/e2e/e2e_tools.py check|tap-target|has-text|edit-field|selftest …`.
  - `bash watch/phone-app/e2e/phone_e2e.sh`. It writes `watch/phone-app/build/e2e/report.txt` (gitignored with `build/`) with one line per step: `PASA`, `FALLA` or `NO EJECUTADO` and the reason. The exit code is 1 if any step failed.
  - Environment variables: `BELT_PORT` (default `COM6`), `PASSKEY` (default `799334`), `PHONE_IP` (default `192.168.10.119`).

- [ ] **Step 1: Write the helpers with their self-test**

Create `watch/phone-app/e2e/e2e_tools.py`:

```python
import re
import sys
import time
import xml.etree.ElementTree as ElementTree

BAUD = 115200
DIAG_PREFIX = "diag "
SENT_MODULO = 1_000_000
PHONE_ITVL_UNITS = range(48, 81)
EDIT_TEXT_CLASS = "android.widget.EditText"
LINK = re.compile(r"link(\d)\[([^\]]*)\]")
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def open_without_reset(port):
    import serial

    link = serial.Serial()
    link.port = port
    link.baudrate = BAUD
    link.timeout = 0.5
    # Opening the port with DTR/RTS asserted resets the ESP32 and would drop every BLE link mid-test.
    link.dtr = False
    link.rts = False
    link.open()
    return link


def read_diag_lines(port, seconds):
    deadline = time.monotonic() + seconds
    lines = []
    with open_without_reset(port) as link:
        while time.monotonic() < deadline:
            text = link.readline().decode("utf-8", "replace").strip()
            if text.startswith(DIAG_PREFIX):
                lines.append(text)
    return lines


def parse_links(line):
    return {int(slot): parse_fields(body) for slot, body in LINK.findall(line)}


def parse_fields(body):
    if body.strip() == "-":
        return None
    return dict(pair.split("=", 1) for pair in body.split() if "=" in pair)


def link_by_role(line, role):
    return next((fields for fields in parse_links(line).values() if fields and fields.get("role") == role), None)


def is_streaming(fields):
    return fields is not None and fields.get("trusted") == "1" and fields.get("sub") == "1"


# Per-link counters wrap at 1 000 000 (plan 04 Task 6), so growth is measured modulo that.
def sent_grew(first, last, role):
    before, after = link_by_role(first, role), link_by_role(last, role)
    if not (is_streaming(before) and is_streaming(after)):
        return False
    return (int(after["sent"]) - int(before["sent"])) % SENT_MODULO > 0


def watch_drops_nothing(line):
    watch = link_by_role(line, "watch")
    return watch is not None and watch.get("dropped") == "0"


def watch_streams(lines):
    return len(lines) >= 2 and sent_grew(lines[0], lines[-1], "watch")


def two_links_stream(lines):
    if len(lines) < 2:
        return False
    first, last = lines[0], lines[-1]
    return sent_grew(first, last, "watch") and sent_grew(first, last, "phone") and watch_drops_nothing(last)


def phone_interval_ok(lines):
    phone = link_by_role(lines[-1], "phone") if lines else None
    return phone is not None and int(phone.get("itvl", "-1")) in PHONE_ITVL_UNITS


# A free slot means the belt advertises again (plan 04: advertising while connections < capacity).
def phone_gone(lines):
    if len(lines) < 2:
        return False
    last = lines[-1]
    free_slot = None in parse_links(last).values()
    return link_by_role(last, "phone") is None and free_slot and watch_streams(lines) and watch_drops_nothing(last)


def dual_link_firmware(lines):
    return any("link0[" in line for line in lines)


CHECKS = {
    "watch": watch_streams,
    "two-links": two_links_stream,
    "phone-interval": phone_interval_ok,
    "phone-gone": phone_gone,
    "dual-firmware": dual_link_firmware,
}


def ui_nodes(xml_text):
    return ElementTree.fromstring(xml_text).iter("node")


def label_of(node):
    return node.get("text") or node.get("content-desc") or ""


def center_of(node):
    left, top, right, bottom = map(int, BOUNDS.match(node.get("bounds")).groups())
    return (left + right) // 2, (top + bottom) // 2


def find_center(xml_text, pattern):
    match = next((node for node in ui_nodes(xml_text) if re.fullmatch(pattern, label_of(node))), None)
    return center_of(match) if match is not None else None


def edit_field_center(xml_text):
    match = next((node for node in ui_nodes(xml_text) if node.get("class") == EDIT_TEXT_CLASS), None)
    return center_of(match) if match is not None else None


def read_text(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def print_center(center):
    if center is None:
        return 1
    print(f"{center[0]} {center[1]}")
    return 0


def cmd_check(args):
    name, port, seconds = args
    lines = read_diag_lines(port, float(seconds))
    for line in lines[-2:]:
        print(line)
    return 0 if CHECKS[name](lines) else 1


def cmd_tap_target(args):
    xml_file, pattern = args
    return print_center(find_center(read_text(xml_file), pattern))


def cmd_has_text(args):
    xml_file, pattern = args
    return 0 if find_center(read_text(xml_file), pattern) else 1


def cmd_edit_field(args):
    return print_center(edit_field_center(read_text(args[0])))


SAMPLE_WATCH = ("diag up=10 link0[role=watch trusted=1 sub=1 mtu=255 itvl=36 lat=0 sup=400 sent=100 dropped=0] "
                "link1[-] bonds=1 disc=0 tx[fail=0 dropped=0 skipped=0]")
SAMPLE_BOTH_A = ("diag up=15 link0[role=watch trusted=1 sub=1 mtu=255 itvl=36 lat=0 sup=400 sent=160 dropped=0] "
                 "link1[role=phone trusted=1 sub=1 mtu=247 itvl=64 lat=0 sup=400 sent=90 dropped=0] bonds=2 disc=0")
SAMPLE_BOTH_B = ("diag up=20 link0[role=watch trusted=1 sub=1 mtu=255 itvl=36 lat=0 sup=400 sent=220 dropped=0] "
                 "link1[role=phone trusted=1 sub=1 mtu=247 itvl=64 lat=0 sup=400 sent=150 dropped=2] bonds=2 disc=0")
SAMPLE_GONE = ("diag up=25 link0[role=watch trusted=1 sub=1 mtu=255 itvl=36 lat=0 sup=400 sent=280 dropped=0] "
               "link1[-] bonds=2 disc=0")
SAMPLE_MVP = "diag up=10 link[trusted=1 sub=1 mtu=255] tx[sent=100]"
SAMPLE_UI = ('<hierarchy><node text="Radar" content-desc="" class="android.widget.TextView" bounds="[0,2000][270,2100]" />'
             '<node text="" content-desc="Compartir" class="android.view.View" bounds="[900,500][1000,600]" />'
             '<node text="" class="android.widget.EditText" bounds="[100,800][900,900]" /></hierarchy>')


def cmd_selftest(_args):
    assert watch_streams([SAMPLE_WATCH, SAMPLE_BOTH_A])
    assert two_links_stream([SAMPLE_BOTH_A, SAMPLE_BOTH_B])
    assert not two_links_stream([SAMPLE_WATCH, SAMPLE_BOTH_A])
    assert phone_interval_ok([SAMPLE_BOTH_B])
    assert phone_gone([SAMPLE_BOTH_B, SAMPLE_GONE])
    assert not phone_gone([SAMPLE_BOTH_A, SAMPLE_BOTH_B])
    assert dual_link_firmware([SAMPLE_WATCH]) and not dual_link_firmware([SAMPLE_MVP])
    assert find_center(SAMPLE_UI, "Radar") == (135, 2050)
    assert find_center(SAMPLE_UI, "Compartir") == (950, 550)
    assert find_center(SAMPLE_UI, "Rad") is None
    assert edit_field_center(SAMPLE_UI) == (500, 850)
    print("selftest ok")
    return 0


COMMANDS = {
    "check": cmd_check,
    "tap-target": cmd_tap_target,
    "has-text": cmd_has_text,
    "edit-field": cmd_edit_field,
    "selftest": cmd_selftest,
}


def main(argv):
    if not argv or argv[0] not in COMMANDS:
        print("usage: e2e_tools.py check|tap-target|has-text|edit-field|selftest ...", file=sys.stderr)
        return 2
    try:
        return COMMANDS[argv[0]](argv[1:])
    except Exception as error:  # a busy port, a bad dump or a failed assert is a failed probe, never a crash of the run
        print(f"e2e_tools: {error!r}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
```

- [ ] **Step 2: Run the self-test**

Run: `cd /c/personal/blindside/watch && python phone-app/e2e/e2e_tools.py selftest`
Expected: `selftest ok` (exit 0). It needs no device and no `pyserial`.

- [ ] **Step 3: Write the run**

Create `watch/phone-app/e2e/phone_e2e.sh`:

```bash
#!/usr/bin/env bash
# Spec §7 steps 3-7 without Santiago: install, hands-free pairing, two links, phone force-stop, a recording pulled from the watch.
set -u
export MSYS_NO_PATHCONV=1

HERE="$(cd "$(dirname "$0")" && pwd)"
WATCH_DIR="$(cd "$HERE/../.." && pwd)"
TOOLS=(python "$HERE/e2e_tools.py")
PKG=io.github.santiquiroz.blindside
PHONE_ACTIVITY="$PKG/io.github.santiquiroz.blindside.phone.MainActivity"
WATCH_ACTIVITY="$PKG/io.github.santiquiroz.blindside.wear.MainActivity"
PHONE_APK="$WATCH_DIR/phone-app/build/outputs/apk/debug/phone-app-debug.apk"
WEAR_APK="$WATCH_DIR/wear-app/build/outputs/apk/debug/wear-app-debug.apk"
BELT_PORT="${BELT_PORT:-COM6}"
PASSKEY="${PASSKEY:-799334}"
PHONE_IP="${PHONE_IP:-192.168.10.119}"
CONFIRM_PATTERN='(?i)(vincular|emparejar|ok|aceptar|pair)'
OUT="$WATCH_DIR/phone-app/build/e2e"
REPORT="$OUT/report.txt"
FAILED=0
WATCH=""
PHONE=""
WATCH_INSTALLED=0

mkdir -p "$OUT"
: > "$REPORT"

report() {
    printf 'PASO %s: %s — %s\n' "$1" "$2" "$3" | tee -a "$REPORT"
    if [ "$2" = "FALLA" ]; then FAILED=1; fi
}

connect_wifi_devices() {
    adb connect "$PHONE_IP" >/dev/null 2>&1
    adb mdns services 2>/dev/null | awk '/_adb-tls-connect/ {print $NF}' | while read -r target; do
        adb connect "$target" >/dev/null 2>&1
    done
}

online_serials() {
    adb devices | awk 'NR > 1 && $2 == "device" {print $1}'
}

is_watch() {
    adb -s "$1" shell getprop ro.build.characteristics 2>/dev/null | grep -q watch
}

find_devices() {
    local serial
    for serial in $(online_serials); do
        if is_watch "$serial"; then
            WATCH="${WATCH:-$serial}"
        elif [ -z "$PHONE" ] || [[ "$serial" == "$PHONE_IP"* ]]; then
            PHONE="$serial"
        fi
    done
}

ui_dump() {
    adb -s "$1" shell uiautomator dump /sdcard/e2e-ui.xml >/dev/null 2>&1
    adb -s "$1" exec-out cat /sdcard/e2e-ui.xml > "$OUT/ui.xml" 2>/dev/null
}

tap() {
    local coords
    ui_dump "$1"
    coords=$("${TOOLS[@]}" tap-target "$OUT/ui.xml" "$2") || return 1
    adb -s "$1" shell input tap $coords
    sleep 1
}

wait_text() {
    local deadline=$((SECONDS + $3))
    while [ "$SECONDS" -lt "$deadline" ]; do
        ui_dump "$1"
        "${TOOLS[@]}" has-text "$OUT/ui.xml" "$2" && return 0
        sleep 2
    done
    return 1
}

screen_shows() {
    "${TOOLS[@]}" has-text "$OUT/ui.xml" "$1"
}

diag_check() {
    "${TOOLS[@]}" check "$1" "$BELT_PORT" "$2" >> "$OUT/diag.log" 2>&1
}

# diag comes every 5 s, so each probe reads 12 s to see at least two lines.
wait_diag() {
    local deadline=$((SECONDS + $2))
    while [ "$SECONDS" -lt "$deadline" ]; do
        diag_check "$1" 12 && return 0
    done
    return 1
}

belt_firmware() {
    diag_check dual-firmware 12
    case $? in
        0) echo dual ;;
        1) echo single ;;
        *) echo unreachable ;;
    esac
}

crashed() {
    adb -s "$1" logcat -d -b crash 2>/dev/null | grep -q "$PKG"
}

preflight() {
    command -v adb >/dev/null || { report 0 "NO EJECUTADO" "adb no está en el PATH"; return 1; }
    python -c "import serial" 2>/dev/null || python -m pip install --quiet pyserial
    "${TOOLS[@]}" selftest >/dev/null || { report 0 "FALLA" "e2e_tools.py selftest"; return 1; }
    [ -f "$PHONE_APK" ] && [ -f "$WEAR_APK" ] || { report 0 "NO EJECUTADO" "faltan los APK: ./gradlew :phone-app:assembleDebug :wear-app:assembleDebug"; return 1; }
    connect_wifi_devices
    find_devices
    printf 'reloj=%s celular=%s cinturón=%s\n' "${WATCH:--}" "${PHONE:--}" "$BELT_PORT" | tee -a "$REPORT"
}

step3_install() {
    [ -n "$PHONE" ] || { report 3 "NO EJECUTADO" "no hay celular por adb ($PHONE_IP o mDNS)"; return 1; }
    adb -s "$PHONE" install -r "$PHONE_APK" >/dev/null || { report 3 "FALLA" "adb install del celular"; return 1; }
    for permission in BLUETOOTH_SCAN BLUETOOTH_CONNECT POST_NOTIFICATIONS; do
        adb -s "$PHONE" shell pm grant "$PKG" "android.permission.$permission" >/dev/null 2>&1
    done
    [ "$(adb -s "$PHONE" shell settings get global bluetooth_on | tr -d '\r')" = 1 ] || adb -s "$PHONE" shell svc bluetooth enable >/dev/null 2>&1
    adb -s "$PHONE" logcat -b crash -c >/dev/null 2>&1
    adb -s "$PHONE" shell am start -n "$PHONE_ACTIVITY" >/dev/null
    sleep 5
    if adb -s "$PHONE" shell pidof "$PKG" >/dev/null && ! crashed "$PHONE"; then
        report 3 "PASA" "instalada, permisos concedidos y abierta"
    else
        report 3 "FALLA" "la app no quedó abierta (adb logcat -b crash)"
        return 1
    fi
}

ensure_watch_app() {
    [ "$WATCH_INSTALLED" = 1 ] && return 0
    adb -s "$WATCH" install -r "$WEAR_APK" >/dev/null && WATCH_INSTALLED=1
}

start_watch_game() {
    ensure_watch_app || return 1
    adb -s "$WATCH" shell am start -n "$WATCH_ACTIVITY" >/dev/null
    wait_diag watch 36
}

enter_passkey() {
    local coords deadline=$((SECONDS + $2))
    while [ "$SECONDS" -lt "$deadline" ]; do
        ui_dump "$1"
        if coords=$("${TOOLS[@]}" edit-field "$OUT/ui.xml"); then
            adb -s "$1" shell input tap $coords
            adb -s "$1" shell input text "$PASSKEY"
            tap "$1" "$CONFIRM_PATTERN" || adb -s "$1" shell input keyevent KEYCODE_ENTER
            return 0
        fi
        sleep 2
    done
    return 1
}

pair_or_reuse() {
    if enter_passkey "$PHONE" 60; then echo "clave escrita"; return 0; fi
    wait_diag two-links 24 && { echo "el celular ya estaba emparejado"; return 0; }
    return 1
}

step4_pair() {
    [ -n "$WATCH" ] || { report 4 "NO EJECUTADO" "sin reloj por adb: empareja a mano con BOOT 3 s (lista de verificación)"; return 1; }
    case "$(belt_firmware)" in
        unreachable) report 4 "NO EJECUTADO" "no se pudo leer $BELT_PORT (¿monitor serie abierto?)"; return 1 ;;
        single) report 4 "NO EJECUTADO" "el cinturón tiene el firmware 0.1.0: emparejar el celular desemparejaría el reloj"; return 1 ;;
    esac
    start_watch_game || { report 4 "FALLA" "el reloj no transmite en 36 s (regresión del paso 2)"; return 1; }
    { tap "$PHONE" "Cintur.n" && tap "$PHONE" "Pedir al reloj que abra la ventana"; } || { report 4 "FALLA" "no se encontró el botón en el celular"; return 1; }
    wait_text "$PHONE" "Pedido enviado.*" 15 || { report 4 "FALLA" "el reloj no respondió REQUESTED (ver $OUT/ui.xml)"; return 1; }
    { tap "$PHONE" "Radar" && tap "$PHONE" "Iniciar radar"; } || { report 4 "FALLA" "no se encontró Iniciar radar"; return 1; }
    local detail
    detail=$(pair_or_reuse) || { report 4 "FALLA" "no apareció el diálogo de clave ni conectó en 84 s"; return 1; }
    report 4 "PASA" "ventana abierta desde el reloj (05); $detail"
}

step5_two_links() {
    wait_diag two-links 60 || { report 5 "FALLA" "no hay dos enlaces transmitiendo con dropped(watch)=0 (ver $OUT/diag.log)"; return 1; }
    diag_check phone-interval 12 || { report 5 "FALLA" "el intervalo del celular no está en 60-100 ms (itvl 48-80)"; return 1; }
    report 5 "PASA" "reloj y celular trusted y sub, sent subiendo en ambos, dropped(watch)=0, itvl del celular en 60-100 ms"
}

step6_force_stop() {
    adb -s "$PHONE" shell am force-stop "$PKG"
    wait_diag phone-gone 36 || { report 6 "FALLA" "la ranura del celular no se liberó o el reloj perdió datos (ver $OUT/diag.log)"; return 1; }
    report 6 "PASA" "ranura del celular libre (el cinturón vuelve a anunciarse) y el reloj sigue con sent subiendo y dropped=0"
}

download_first_listed() {
    tap "$PHONE" "Traer" || return 0
    wait_text "$PHONE" "(Tra.da|No se pudo traer).*" 900 || return 1
    screen_shows "Tra.da.*"
}

step7_pull_recording() {
    { [ -n "$PHONE" ] && [ -n "$WATCH" ]; } || { report 7 "NO EJECUTADO" "hace falta el reloj y el celular por adb"; return 1; }
    ensure_watch_app || { report 7 "FALLA" "adb install del reloj"; return 1; }
    adb -s "$PHONE" shell am start -n "$PHONE_ACTIVITY" >/dev/null
    sleep 3
    { tap "$PHONE" "Grabaciones" && tap "$PHONE" "Traer del reloj"; } || { report 7 "FALLA" "no se encontró Traer del reloj"; return 1; }
    wait_text "$PHONE" "Traer|Ya est.|El reloj no tiene grabaciones terminadas\." 20 || { report 7 "FALLA" "el reloj no respondió a la lista en 20 s"; return 1; }
    if screen_shows "El reloj no tiene grabaciones terminadas\."; then
        report 7 "NO EJECUTADO" "el reloj no tiene grabaciones terminadas: detén una partida en el reloj y repite"
        return 1
    fi
    download_first_listed || { report 7 "FALLA" "la descarga no terminó bien (ver $OUT/ui.xml)"; return 1; }
    tap "$PHONE" "Cerrar"
    tap "$PHONE" "Reloj .+" || { report 7 "FALLA" "la grabación del reloj no aparece en Grabaciones"; return 1; }
    wait_text "$PHONE" "Resumen|No se pudo abrir.*" 180 || { report 7 "FALLA" "el Visor no terminó el análisis en 3 min"; return 1; }
    if screen_shows "No se pudo abrir.*" || crashed "$PHONE"; then
        report 7 "FALLA" "el Visor no abrió la grabación (logcat -b crash)"
        return 1
    fi
    report 7 "PASA" "grabación del reloj traída por el Data Layer y abierta en el Visor"
}

run_chain() {
    local blocked="" step
    for step in step3_install step4_pair step5_two_links step6_force_stop; do
        if [ -n "$blocked" ]; then
            report "${step:4:1}" "NO EJECUTADO" "depende del paso $blocked"
            continue
        fi
        "$step" || blocked="${step:4:1}"
    done
}

main() {
    if preflight; then
        run_chain
        step7_pull_recording
    fi
    printf '\nInforme: %s\n' "$REPORT"
    exit "$FAILED"
}

main
```

Check the syntax without running it:

```bash
cd /c/personal/blindside/watch && bash -n phone-app/e2e/phone_e2e.sh && echo "syntax ok"
```

Expected: `syntax ok`.

- [ ] **Step 4: Run the E2E**

Run it with the Bash tool **in the background** (`run_in_background: true`): it can take up to 20 min, and a foreground call stops at 10.

```bash
cd /c/personal/blindside/watch && ./gradlew :phone-app:assembleDebug :wear-app:assembleDebug --console=plain && BELT_PORT=COM6 PASSKEY=799334 bash phone-app/e2e/phone_e2e.sh
```

A background job only notifies the session that started it. If this task runs in a subagent, poll for the report instead of waiting for a notification. Then read `watch/phone-app/build/e2e/report.txt`:
- **`PASA`** on a step: nothing to do.
- **`NO EJECUTADO`** (a device, the port or a finished watch recording is missing, or the belt is still on 0.1.0): not a failure. The matching README checklist item stays for Santiago. Note the reason in the hand-off.
- **`FALLA`**: debug it with superpowers:systematic-debugging using `watch/phone-app/build/e2e/diag.log`, `ui.xml` and `adb logcat -s BlindsideSession BlindsideService PhoneBridge PhoneBridgeListener BeltGatt BeltLink`. Fix it in its own `fix:` commit, rerun the affected tasks' tests, the watch gate, and this E2E. If the cause is plan 04 or plan 05 code, stop and report it as a cross-plan flag instead of patching it here.

- [ ] **Step 5: Commit**

```bash
cd /c/personal/blindside
git add watch/phone-app/e2e/e2e_tools.py watch/phone-app/e2e/phone_e2e.sh
git commit -m "test: E2E autónomo del celular para el spec §7 (pasos 3-7) con adb y el puerto serie"
```

---

## Hand-off

The branch `p2/phone-app` ends with 16 commits (plus any `fix:` commits from Task 16), stacked on plan 05's `p2/android-shared-watch`. Final counts: radar-core 258, android-shared 280, wear-app 46, phone-app 159, all green. It is **not** merged and never pushed.

In the final report, list:
- the branch, its base and the commits;
- the counts above;
- Deviations P1-P6;
- Cross-plan flags 1-3 (above all flag 1: the watch's "Emparejar celular" chip cannot be reached during a game);
- the Task 16 report line by line.

The orchestrator merges, in order (04, 05, 06), only after every test passes **and** spec §7 passes: the watch regression of step 2 (plan 05), and steps 3-7 here or their checklist items done by Santiago.
