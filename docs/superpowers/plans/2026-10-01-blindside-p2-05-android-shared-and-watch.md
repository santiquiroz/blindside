# Blindside Phase 2 — Plan 05: `android-shared` module and the watch side (bridge + visual pass) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the code of `watch/wear-app` that does not depend on Wear OS into a new Android library, `watch/android-shared`, without changing how the watch behaves. Then build the watch's side of phase 2: `SET_ROLE` on connect, Ajustes → "Emparejar celular" (control `05`) and the Wear Data Layer bridge. Then do the §6 OLED visual pass. Finish with the spec §7 step 2 watch regression on the real belt and watch, which is the merge gate.

**Architecture:**
- **The new library.** `android-shared` is an Android library with the package `io.github.santiquiroz.blindside.shared.*`. It takes `permissions`, `sensors`, `settings`, `haptics`, `ble`, the session runtime with `recording` and `demo`, and the radar drawing. Each task moves one package group, guarded by the JVM tests that move with it.
- **What stays in `wear-app`.** `MainActivity`, the Wear Compose screens, the Wear service subclass, its Ongoing Activity notification, `practice/` and `SpikeScreen`.
- **The session service.** It becomes `abstract class SessionService` in the library. Each app subclasses it and supplies a `SessionHost`: its notification, app version, BLE link profile and companion jobs. `SessionCommands` becomes a class bound to the app's own service class.
- **The BLE surface both apps use.** `shared/ble` gains `BeltRole`, a sealed `BeltCommand` (`RestartRadar`, `Identify`, `SetRole`, `OpenPairingWindow`) written through one `GattOp.WriteCommand`, per-role connection priorities, and `BeltLinkProfile(role, activatesSession)`. `BeltLink` takes the profile and offers `send(command)` and `refreshInfo()`. Every command write reports a `CommandResult` to the listener. The watch passes `BeltLinkProfile(WATCH)` through its `SessionHost`. The phone plan (06) passes its own profile and adds nothing to `shared/ble`.
- **The bridge has two halves:**
  - `shared/bridge` holds the **whole** Data Layer contract, so the phone plan imports it and never redefines it: the paths, the JSON codecs, the DataItem wrapping (`jsonDataRequest`, `jsonIn`, `publishJson`), the last-write-wins rule, the path validation and a literal-payload fixture test.
  - The watch's Android wiring lives in `wear/bridge`: a `WearableListenerService` and the publishers.
- **The visual system.** The visual tokens, the contrast math and the OFL fonts live in `shared/theme`. The watch maps them into a Wear `MaterialTheme`. The radar Canvas takes a `RadarColors` value, so the phone and the watch draw it alike. Contact colour is a per-device setting, green (default) or red (spec §6 and v1 §5.3).

**Tech Stack:**
- Kotlin 2.2.21, AGP 8.10.1, Gradle 8.11.1, JDK 17.
- `com.android.library` (new) and `com.android.application`.
- Compose BOM 2025.05.00 (`compose-ui` only in the library), Wear Compose 1.4.0 (only in `wear-app`).
- coroutines 1.9.0, DataStore Preferences 1.1.4.
- `com.google.android.gms:play-services-wearable` 18.2.0, as an `api` dependency of `android-shared` (Task 13), so both apps wrap DataItems with the same code.
- JUnit 5 for the JVM tests, and `radar-core`.
- Fonts: JetBrains Mono NL 2.304 and IBM Plex Sans 1.1.0, both OFL.
- Hardware E2E (Task 18): Python 3.11 with `pyserial` 3.5 and PlatformIO Core 6.2 (`python -m platformio`), `adb` from the Android SDK.

**Spec:** [`docs/superpowers/specs/2026-10-01-blindside-android-companion-design.md`](../specs/2026-10-01-blindside-android-companion-design.md). This plan covers §3, the watch half of §5, the watch half of §6 and §7 steps 1-2. Background: the MVP spec [`2026-09-30-blindside-v1-design.md`](../specs/2026-09-30-blindside-v1-design.md) and the binding contracts [`2026-09-30-blindside-mvp-00-contracts.md`](2026-09-30-blindside-mvp-00-contracts.md).

## Global Constraints

- **Golden rule (spec, header):** nothing in this phase may break the watch on its own. This branch merges into `main` only after **all** tests pass **and** the watch regression E2E (spec §7 step 2) passes on the real belt and watch. Task 18 runs that E2E over ADB and the serial port and commits the evidence. The executor **never merges**: the orchestrator merges `p2/android-shared-watch` after it reads a `REGRESSION PASS` evidence file on the branch. Without the hardware, Task 18 reports `E2E PENDING` and the branch stays unmerged.
- **Repo and branch:**
  - `C:/personal/blindside`, working branch `p2/android-shared-watch` (Task 1 creates it from `main`).
  - If another phase-2 plan runs at the same time, work in a worktree instead: `git -C C:/personal/blindside worktree add ../blindside-p2-shared -b p2/android-shared-watch main`. Then read `C:/personal/blindside-p2-shared` wherever a command below says `C:/personal/blindside`. Task 1 Step 1 writes the gitignored `watch/local.properties` in whichever tree you use.
  - **Stacked branches:** plan 06 (phone app) branches from `p2/android-shared-watch`, not from `main`, so it can start before this branch merges. The orchestrator merges this branch first, then plan 06's.
  - Never push. Destructive git is forbidden (`reset --hard`, `clean`, `checkout -- .`, `branch -D`, `worktree remove`), and so is `--no-verify`.
- **Commits:**
  - Spanish conventional messages (`feat:`, `fix:`, `refactor:`, `test:`, `chore:`, `docs:`). The author is preconfigured.
  - **Never** a `Co-Authored-By` line.
  - If a task records a deviation, it goes in the commit body.
- **Owner code rules (mandatory):**
  - Atomic functions whose name says what they do. Low cyclomatic complexity: extract branches into named functions, prefer early returns.
  - **No doc comments** (`/** */`). Only a one-line comment when the *why* is not obvious.
  - Pure functions and immutable data (`data class`, `val`, `copy`). Mutable state lives only in Android adapter classes and `SessionStore`.
  - Files of 400 lines or fewer.
- **Toolchain (do not upgrade):**
  - Gradle wrapper in `watch/` (8.11.1), AGP 8.10.1, Kotlin 2.2.21, JDK 17.
  - `compileSdk 36`, `targetSdk 36`. `wear-app` keeps `minSdk 34`. `android-shared` uses `minSdk 33` (Deviation D1).
  - Compose BOM `2025.05.00`, Wear Compose `1.4.0`, coroutines `1.9.0`, datastore `1.1.4`, play-services-wearable `18.2.0` (already in the Gradle cache).
  - Android SDK at `C:/Users/santi/AppData/Local/Android/Sdk`. If `watch/local.properties` is missing, write `sdk.dir=C:/Users/santi/AppData/Local/Android/Sdk` with forward slashes. The file is gitignored.
- **Identifiers (spec §3, §4, §5):**
  - Library package `io.github.santiquiroz.blindside.shared.*`, which is also the library `namespace` (`io.github.santiquiroz.blindside.shared`).
  - `applicationId` stays `io.github.santiquiroz.blindside`, the same on both apps, as the Data Layer requires.
  - `WatchSensors` is renamed to `DeviceSensors`.
- **Control commands (spec §2):**
  - `05` = OPEN_PAIRING_WINDOW, accepted only from a trusted link.
  - `06 <0=reloj, 1=celular>` = SET_ROLE. A link that never sends it is treated as the watch.
  - **Settled here (spec §3: "el rol BLE se envía al conectar, según la app"):** every app writes its own role on every connection, the watch included. The watch writes `06 00` after the encrypted `info` read and before the subscription. The MVP firmware ignores it, and plan 04 (cross-plan flag 6) already accepts it as the watch role.
- **Data Layer (spec §5, `play-services-wearable` 18.2.0). This plan owns the contract; plan 06 imports it from `shared.bridge` and never redefines it:**

  | Path | Phone sends | Watch answers | Payload |
  |---|---|---|---|
  | `/recordings/list` | `MessageClient.sendRequest` | `onRequest` reply | UTF-8 JSON `[{"nombre":…,"bytes":…,"inicio":<epoch ms>}]` |
  | `/recordings/get/<name>` | `ChannelClient.openChannel` | `onChannelOpened`: `sendFile`, or `close(channel, RECORDING_REFUSED_CODE)` | Raw `.bsrec` bytes. A refusal sends no bytes and closes with code 1 |
  | `/settings` | `DataClient` item (both apps write it) | `onDataChanged` (both apps read it) | DataMap key `json` (`DATA_JSON_KEY`), written with `jsonDataRequest`/`publishJson`, read with `jsonIn`. Last write wins on `updated_ms`; a payload that does not decode completely is ignored |
  | `/status` | (reads it) | `DataClient` item every **5 s** while a session runs, and once more on stop | DataMap key `json`, same helpers |
  | `/belt/open-pairing` | `MessageClient.sendRequest` (recommended; the reply says `REQUESTED` or `NO_LINK`) or `sendMessage` | `onRequest` **and** `onMessageReceived` | Request empty; reply the UTF-8 name of `OpenPairingReply` |

  The JSON shapes are pinned by literal fixtures in `BridgeContractTest` (Task 12). **Each app works alone if the other is missing:** a failed bridge call is logged and ignored, never fatal.
- **Visual tokens (spec §6, verbatim):**

  | Token | Value |
  |---|---|
  | `bg` | `#000000` |
  | `surface` | `#0E1111` |
  | `surface-2` | `#161B1A` |
  | `ring` | `#25302C` |
  | `accent` | `#3BE37A` |
  | `accent-dim` | `#1E7A43` |
  | `alert-red` | `#FF5A4E` (dim `#8C2A24`) |
  | `warn` | `#F2B84B` |
  | `text` | `#E8ECEA` (≥ 7:1 on `bg`) |
  | `text-2` | `#9AA5A0` (≥ 4.5:1) |

  - **Fonts:** JetBrains Mono for numbers, with tabular figures. IBM Plex Sans for labels and text.
- **Component rules (spec §6):**
  - Touch targets ≥ 48 dp.
  - Animations of 150-300 ms that respect "reduce motion". This pass adds no animation to the watch.
  - Colour is never the only indicator: confidence is also coded by shape.
  - Empty states carry an action. Vector icons, never emojis. Black background everywhere.
  - User-facing strings are in Spanish; code identifiers are in English.
- **Shell:** run every shell step in Git Bash (the Bash tool), from the directory the step names. Use `./gradlew` from `C:/personal/blindside/watch`. Working-tree files are CRLF, so `sed` patterns here never anchor on `$`.
- **Verification command** (every task that touches Kotlin runs it; it deletes old test XML first so the counts are exact):

  ```bash
  cd C:/personal/blindside/watch && ./gradlew :android-shared:cleanTestDebugUnitTest :wear-app:cleanTestDebugUnitTest :android-shared:testDebugUnitTest :wear-app:testDebugUnitTest :wear-app:assembleDebug --console=plain
  ```

- **Test-count one-liner** (run right after the verification command):

  ```bash
  cd C:/personal/blindside/watch && for m in android-shared wear-app; do printf '%s %s\n' "$m" "$(cat $m/build/test-results/testDebugUnitTest/*.xml 2>/dev/null | grep -o '<testsuite [^>]*' | grep -o ' tests="[0-9]*"' | grep -o '[0-9]*' | awk '{s+=$1} END {print s+0}')"; done
  ```

- **Baseline (measured on 2026-10-01 at commit `3860a95`):**
  - `wear-app` has **244** JVM tests. The spec says 242; the real number is 244 (Deviation D2).
  - `radar-core` has 254.
  - Through Task 8, `android-shared` + `wear-app` must always add up to 244.
  - Task 18 adds 7 Python tests under `e2e/` (`python -m unittest discover -s e2e`), outside Gradle.

## Deviations from the spec (decided here; repeat them in the final report)

- **D1, `minSdk 33` for `android-shared` instead of 31 (spec §3).** `BeltGatt` calls API 33 overloads: `writeCharacteristic(c, bytes, type)`, `writeDescriptor(d, bytes)`, and `onCharacteristicRead/Changed` with a value. Declaring 31 would crash Android 12 phones at runtime. The S25 Ultra and the Watch 7 are far above 33. **Every module that depends on `:android-shared` must declare `minSdk` ≥ 33.** A lower value fails the manifest merger ("minSdkVersion 31 cannot be smaller than version 33 declared in library [:android-shared]"), and `tools:overrideLibrary` is not an acceptable fix. Plan 06 still says 31: see Cross-plan contract item 1. Task 17 checks every consumer.
- **D2, the test baseline is 244, not 242.**
- **D3, `RadarLabels` is split.** `radarMessage` and `linkMessage` stay in `wear-app` as `RadarLinkMessage.kt`, because they use `bleStatusLabel` and `startErrorMessage`, whose text is worded for the watch ("Este reloj…"). The rest moves.
- **D4, `SessionWakeLock` moves.** It uses only `PowerManager`, not Wear. `practice/` stays in `wear-app`, since spec §3 does not list it.
- **D5, `WatchSensorListener` is also renamed, to `DeviceSensorListener`,** to match `DeviceSensors`.
- **D6, `/belt/open-pairing` is served both ways.** Spec §5 calls it a `MessageClient` message. The watch answers `sendRequest` in `onRequest` with `REQUESTED`/`NO_LINK`, so the phone can show a result within its timeout. It also acts on a plain `sendMessage` in `onMessageReceived`. `/recordings/list` is request/response only. Both APIs are verified in 18.2.0.
- **D7, JSON keys.** The recordings list uses the spec's keys (`nombre`, `bytes`, `inicio`); `inicio` is epoch milliseconds. The spec gives no keys for `/status` and `/settings`, so this plan defines them in English, aligned with plan 06's draft (`session_active`, `link`, `yaw_deg`), plus `link_up`. `BridgeContractTest` pins the literal payloads. `/settings` and `/status` travel as the DataMap string key `json`, not as raw item bytes.
- **D8, the `05` result on the watch only confirms the write was delivered.** Both the MVP and the phase-2 firmware accept every control write at the ATT level. Plan 04 stores the write in a mailbox and later, in `loop()`, ignores `05` from an untrusted link. So a status of 0 never proves the window opened. The watch shows "Pedida al cinturón (60 s)", never "Abierta". `pairingStateAt` turns that state back into "idle" after 60 s, which is how long the belt keeps the window open (it also closes it at the first bond).
- **D9, contact colour default.** Spec §6 makes contacts `accent` green, and the v1 spec (§5.3, §5.6) makes the colour selectable, red or green. The watch draws red contacts today. Task 15 adds the setting "Color de contactos" (default **Verde**, `Rojo` uses `alert-red`/dim). It is a per-device display preference, so it stays out of `/settings`.

## Cross-plan contract (blocking items for the orchestrator; repeat them in the final report)

This plan runs before plan 06 and owns everything in `android-shared`. Plan 06 consumes it. The items below must hold before plan 06 merges. Plan 04 needs only a note.

1. **Plan 06, `minSdk`:** `phone-app` must declare `minSdk = 33` (D1). Its Global Constraints, its Task 1 `build.gradle.kts` and its README all say 31.
2. **Plan 06, BLE (its Task 3):** this plan already provides `BeltRole(code: Int)`, `BeltCommand` (`RestartRadar`, `Identify`, `SetRole`, `OpenPairingWindow`), `commandBytes`, `LinkPriority`, `connectPriorityFor`, `settledPriorityFor`, `BeltLinkProfile(role, activatesSession)`, `GattOp.WriteCommand`, `setupOpsAfterDiscovery(profile)`, `effectsAfter(op, status, profile)`, `isControlWrite`, `CommandResult`/`commandResultOf`, `BeltLink(context, listener, onBeltFound, profile)`, `BeltLink.send` and `BeltLink.refreshInfo` (Tasks 9-10). Plan 06 must **not** create `BeltCommands.kt` again, nor replace `effectsAfter` or `isControlWrite`. It must not expect "no role support yet", nor assert that the watch writes no `06`: the watch writes `06 00` (settled above). `BeltListener.onCommandWritten` has a default body, so plan 06's own listeners compile unchanged.
3. **Plan 06, bridge (its Tasks 4 and 7):** import every path, codec and helper from `shared.bridge`: `BridgePaths.kt`, `RecordingListCodec.kt`, `RecordingChannel.kt`, `SharedSettingsCodec.kt`, `WatchStatus.kt`, `OpenPairingReply.kt`, `DataLayerJson.kt`. Do not create files or top-level functions with those names in `shared.bridge`; phone-only helpers go in new phone files. Write `/settings` with `jsonDataRequest`/`publishJson`, read items with `jsonIn`, ask `/belt/open-pairing` with `sendRequest` and decode the reply with `decodeOpenPairingReply`. Keep `BridgeContractTest` green and never edit its literals on one side only.
4. **Plan 06, downloads:** a valid `.bsrec` is never empty. A channel closed with `RECORDING_REFUSED_CODE` (1), or one that delivered 0 bytes, is a refusal: `downloadWasServed(byteCount, appErrorCode)` returns false, and `finishDownload` must delete the `.part` file and show the refusal. Add that case to its `finishDownload` tests.
5. **Plan 06, reuse instead of copies:** subclass `SessionService` with a `PhoneSessionHost` (its notification, `beltProfile = BeltLinkProfile(BeltRole.PHONE, …)`) instead of a second service. Use `shared.theme` (`Tokens`, `BlindsideColors`, `BlindsideFonts`, the fonts in `android-shared/src/main/res/font`) instead of `ThemeTokens.kt` and a second set of font files. Same-named font resources in `phone-app` silently override the library's. Draw with `shared.radar.drawRadar(model, colors)` and a phone `RadarColors` value. The radar package is `io.github.santiquiroz.blindside.shared.radar`, not `shared.ui.radar`: fix plan 06's surface table and its `BuildSmokeTest` import.
6. **Plan 06, branch:** branch from `p2/android-shared-watch` (stacked). Its Task 1 "BLOCKED if plan 05 is not merged" check then passes on the stacked branch.
7. **Plan 06, signing:** the phone must be signed with the same debug key as the watch, or the Data Layer never links the two apps.
8. **Plan 04, notes only:**
   - Flag 1 says "The watch app needs no change". It should read "the watch app writes `06 00` (plan 05), which is compatible (flag 6)".
   - **Control mailbox:** each slot keeps one `control_mailbox` that a later write overwrites while it is unread. The risk is accepted here and not asked of plan 04. `loop()` drains every slot every 5 ms (`kLoopPeriodMs`). Two control writes from one link always have a full ATT round trip between them, because Android waits for each write's response before it sends the next op: `06`, then the CCCD write, then `04 01`. So they arrive at least two connection events apart. That is ≥ 15 ms even at BLE's 7.5 ms minimum interval, three loop periods. It is ≥ 60 ms at the watch's 30-50 ms interval and ≥ 120 ms at the phone's 60-100 ms interval. A per-slot queue in plan 04 would remove the risk entirely, and is recommended if `loop()` ever gains a blocking step.
9. **Spec §7 ownership:** this plan runs steps 1-2 (Task 18). Steps 3-7 (phone install, hands-free pairing, two links, phone force-stop, pulling a recording) belong to plan 06 or a separate E2E plan, and need plan 06's app. `e2e/watch_regression.py` is reusable for step 6: the watch link must keep streaming after the phone disconnects. Every plan that drives the belt or the watch takes the lock directory `/c/personal/.blindside-hardware.lock` first (`mkdir`, Task 18 Step 4), so two E2E runs never flash or reconnect the same hardware at once.

## Review Focus

1. **An old firmware or a refused control write must not change how the watch connects.** The watch may run the current MVP firmware (no `05`/`06`), or a belt may refuse a control write. Either way the link must still reach `STREAMING`, with the watch's original priorities (HIGH while connecting, BALANCED once the session is active). A failed or ignored role or pairing write never disconnects. Pinned by Task 9 (`a failed role write keeps the setup going`, `a command write reports whether the belt took it`, `the watch connects fast and settles balanced`) and, on the hardware, by Task 18.
2. **A bad `/settings` payload from the phone must never change the watch's settings.** That covers an older, partial, malformed or out-of-range payload, and one from a newer schema with an unknown hand or posture. It must never roll back or wipe hand, mounts, signs or posture. A local edit after adopting a stamp from a clock running ahead must still win. Pinned by Task 12 (`only a strictly newer remote is adopted`, `a local edit never moves the stamp backwards`, `a payload missing a radar, the stamp or json is rejected`, `an unknown or missing hand or posture is rejected`, `a partial or malformed radar is rejected`, `an out of range yaw is clamped`).
3. **A `/recordings/get` request must only ever serve a finished recording, and a refusal must not look like an empty file.** Requests for the recording being written, a missing file or a crafted path (`..`, a nested path, another prefix) are refused with `RECORDING_REFUSED_CODE`. The list never offers the active recording. Pinned by Task 11 (`paths outside the prefix, nested or traversing are refused`, `the list skips the active recording…`, `a refused or empty download is not a recording`, and in wear `the active, missing or unsafe recording is not served`).
4. **"Emparejar celular" without a link sends nothing, and never claims the window is open.** It may be tapped, or asked for by the phone, with no running belt game, during the demo or while reconnecting. The watch must show a clear message, send nothing, and leave no service running. With a link, it only says the request reached the belt, and that label expires after 60 s. Pinned by Task 10 (`only a streaming belt game can open the window`, `a delivered request reads as idle once the 60 s window is over`) and Task 12 (`the reply asks the belt only when its link streams`).
5. **Stopping a game must leave the phone seeing "inactive",** never a stale "active" status. Pinned by Task 12 (`a stopped session publishes an inactive status`). Task 13 publishes that status from the companion's `finally` block.
6. **The phone and the watch must speak the same Data Layer contract.** Pinned by Task 12 (`BridgeContractTest` literal payloads) and by the single DataItem wrapper in Task 13 (`jsonDataRequest`/`jsonIn`).

## File structure

`android-shared` (all under `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/`, tests mirrored under `src/test/kotlin/...`):

| Package | Files (origin) | Responsibility |
|---|---|---|
| `permissions` | `SessionPermissions.kt` (moved) | Runtime permission names and start decision |
| `sensors` | `SensorMath.kt`, `DeviceSensors.kt` (moved, renamed) | Gravity/gyro/step adapter and its pure math |
| `settings` | `AppSettings.kt` (+ `ContactColor`, Task 15), `PipelineConfigMapping.kt`, `SettingsPreferences.kt`, `SettingsRepository.kt` (moved), `SharedSettings.kt` (new, Task 12) | Settings model, DataStore, last-write-wins stamping |
| `haptics` | 5 files (moved) | Vibration vocabulary and player |
| `ble` | 11 files (moved), `BeltCommands.kt` (new, Tasks 9-10) | GATT link, op queue, link profiles, role and command writes |
| `session` | 14 files (moved; `SessionHost`, `SessionActions`, `SessionService` new in Task 6), `PhonePairing.kt` (Task 10) | Session runtime shared by both apps |
| `recording` | 4 files (moved), `RecordingCatalog.kt` (Task 11) | `.bsrec` writing and the list of finished recordings |
| `demo` | 2 files (moved) | Simulated belt |
| `radar` | `RadarGeometry.kt`, `DeviceRotation.kt`, `RadarCanvas.kt`, `RadarLabels.kt` (moved), `RadarColors.kt` (new) | Radar draw model, Canvas, labels and contact palettes |
| `bridge` | `BridgePaths.kt`, `JsonFields.kt`, `RecordingListCodec.kt`, `RecordingChannel.kt` (Task 11), `SharedSettingsCodec.kt`, `WatchStatus.kt`, `OpenPairingReply.kt` (Task 12), `DataLayerJson.kt` (Task 13) | The whole Data Layer contract shared with the phone |
| `theme` | `Tokens.kt`, `Contrast.kt`, `BlindsideColors.kt`, `BlindsideFonts.kt` + `res/font/*.ttf` (Task 14) | §6 tokens and fonts |

`wear-app` keeps or gains:
- `MainActivity.kt` and `SpikeScreen.kt`.
- `session/`: `BlindsideSessionService.kt` (a thin subclass), `SessionNotification.kt` and `WearSession.kt` (new).
- `ui/`: all screens, plus `HomeItems.kt`, `PhonePairingClock.kt` and `theme/WearTheme.kt` (new).
- `ui/radar/`: `RadarScreen.kt` and `RadarLinkMessage.kt` (new).
- `practice/`.
- `bridge/` (new): `BridgeListenerService.kt`, `RecordingServing.kt`, `SettingsPublisher.kt` and `StatusPublisher.kt`.

Repo root gains `licenses/JetBrainsMono-OFL.txt`, `licenses/IBMPlexSans-OFL.txt`, `e2e/watch_regression.py` with its test (Task 18), and `docs/superpowers/e2e/2026-10-01-p2-05-watch-regression.md` (the evidence, Task 18).

---

## Part A — Extraction (behaviour-preserving)

### Task 1: Create `android-shared` and move `permissions`

**Files:**
- Modify: `watch/gradle/libs.versions.toml` (add the library plugin)
- Modify: `watch/build.gradle.kts`
- Modify: `watch/settings.gradle.kts`
- Create: `watch/android-shared/build.gradle.kts`
- Move: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/permissions/` → `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/permissions/`
- Move: `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/permissions/` → `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/permissions/`
- Modify (imports via `sed`): `wear-app/.../ui/HomeScreen.kt`, `ui/LaunchDecision.kt`, `ui/RadarLaunch.kt`, `SpikeScreen.kt`, `session/BlindsideSessionService.kt`, `session/RunningSession.kt`, test `ui/LaunchDecisionTest.kt`
- Modify: `watch/wear-app/build.gradle.kts`, `.github/workflows/ci.yml`, `watch/wear-app/README.md`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - Gradle module `:android-shared`. `wear-app` depends on it with `implementation(project(":android-shared"))`.
  - Package `io.github.santiquiroz.blindside.shared.permissions` with the same signatures as before: `SESSION_PERMISSIONS: Array<String>`, `PERMISSION_*` constants, `sealed interface StartDecision`, `startDecision(grants: Map<String, Boolean>): StartDecision`, `bluetoothGranted(grants): Boolean` and `shouldRequestPermissions(grants): Boolean`.

- [ ] **Step 1: Branch and baseline**

Use the worktree (Global Constraints) when another phase-2 plan may run at the same time: the orchestrator says so, or `git -C C:/personal/blindside branch --show-current` prints anything but `main`. Never switch another plan's branch. Untracked plan files under `docs/` are normal and do not count. With the worktree, read `C:/personal/blindside-p2-shared` wherever a command says `C:/personal/blindside` from here on.

```bash
# Main tree, when nothing else is running there:
cd C:/personal/blindside && git switch -c p2/android-shared-watch
# Or, when another plan uses the main tree:
#   git -C C:/personal/blindside worktree add ../blindside-p2-shared -b p2/android-shared-watch main && cd C:/personal/blindside-p2-shared
cd watch && test -f local.properties || printf 'sdk.dir=C:/Users/santi/AppData/Local/Android/Sdk\n' > local.properties
./gradlew :wear-app:cleanTestDebugUnitTest :wear-app:testDebugUnitTest --console=plain
cat wear-app/build/test-results/testDebugUnitTest/*.xml | grep -o '<testsuite [^>]*' | grep -o ' tests="[0-9]*"' | grep -o '[0-9]*' | awk '{s+=$1} END {print s+0}'
```

Expected: `BUILD SUCCESSFUL` and `244`. If the number differs, write it down: every later expected count shifts by the same difference.

- [ ] **Step 2: Gradle scaffold**

In `watch/gradle/libs.versions.toml`, under `[plugins]`, add after the `android-application` line:

```toml
android-library = { id = "com.android.library", version.ref = "agp" }
```

In `watch/build.gradle.kts`, add inside `plugins { }` after the `android.application` line:

```kotlin
    alias(libs.plugins.android.library) apply false
```

In `watch/settings.gradle.kts`, add after `include(":radar-core")`:

```kotlin
include(":android-shared")
```

Create `watch/android-shared/build.gradle.kts`:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.santiquiroz.blindside.shared"
    compileSdk = 36

    defaultConfig {
        // BeltGatt uses the API 33 GATT overloads (Deviation D1).
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":radar-core"))

    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.launcher)
}
```

In `watch/wear-app/build.gradle.kts`, add inside `dependencies { }` after `implementation(project(":radar-core"))`:

```kotlin
    implementation(project(":android-shared"))
```

- [ ] **Step 3: Move the test first and watch it fail**

```bash
cd C:/personal/blindside/watch
TST=wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear
SH_TST=android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared
mkdir -p "$SH_TST"
git mv "$TST/permissions" "$SH_TST/permissions"
sed -i -E 's/blindside\.wear\.permissions\b/blindside.shared.permissions/g' "$SH_TST"/permissions/*.kt
./gradlew :android-shared:testDebugUnitTest --console=plain
```

Expected: FAIL at `:android-shared:compileDebugUnitTestKotlin` with `Unresolved reference 'startDecision'` (and the other permission symbols).

- [ ] **Step 4: Move the code and rewrite every import**

```bash
cd C:/personal/blindside/watch
SRC=wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear
SH_SRC=android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared
mkdir -p "$SH_SRC"
git mv "$SRC/permissions" "$SH_SRC/permissions"
grep -rlE 'blindside\.wear\.permissions\b' --include='*.kt' wear-app android-shared | xargs -r sed -i -E 's/blindside\.wear\.permissions\b/blindside.shared.permissions/g'
grep -rn 'blindside\.wear\.permissions' wear-app android-shared || echo "no leftovers"
```

Expected: `no leftovers`.

- [ ] **Step 5: Run the verification command and the test-count one-liner (Global Constraints)**

Expected: `BUILD SUCCESSFUL`; counts `android-shared 8`, `wear-app 236` (total 244).

- [ ] **Step 6: CI and README**

In `.github/workflows/ci.yml`, job `wear-app`:
- Replace `name: wear-app assembleDebug` with `name: watch apps unit tests and assembleDebug`.
- Replace the last `run:` line, `run: chmod +x gradlew && ./gradlew :wear-app:assembleDebug --no-daemon`, with:

```yaml
        run: chmod +x gradlew && ./gradlew :android-shared:testDebugUnitTest :wear-app:testDebugUnitTest :wear-app:assembleDebug --no-daemon
```

In `watch/wear-app/README.md`, replace the `## Compilar y probar` code block with:

````markdown
```bash
./gradlew :android-shared:testDebugUnitTest :wear-app:testDebugUnitTest   # pruebas JVM (código compartido y del reloj)
./gradlew :wear-app:assembleDebug       # APK en wear-app/build/outputs/apk/debug/wear-app-debug.apk
```

El código que no depende de Wear OS (BLE, grabación, ajustes, vibración, sesión y dibujo del radar) vive en `watch/android-shared` y lo comparte la app del celular.
````

- [ ] **Step 7: Commit**

```bash
cd C:/personal/blindside
git add watch/android-shared watch/wear-app watch/settings.gradle.kts watch/build.gradle.kts watch/gradle/libs.versions.toml .github/workflows/ci.yml
git commit -m "refactor: crea el módulo android-shared y mueve permissions desde wear-app"
```

---

### Task 2: Move `sensors` and rename `WatchSensors` → `DeviceSensors`

**Files:**
- Move: `wear-app/.../wear/sensors/SensorMath.kt` → `android-shared/.../shared/sensors/SensorMath.kt`
- Move + rename: `wear-app/.../wear/sensors/WatchSensors.kt` → `android-shared/.../shared/sensors/DeviceSensors.kt`
- Move: `wear-app/src/test/.../wear/sensors/SensorMathTest.kt` → `android-shared/src/test/.../shared/sensors/SensorMathTest.kt`
- Modify (via `sed`): `wear-app/.../session/InputAdapters.kt`, `session/RunningSession.kt`

**Interfaces:**
- Consumes: module `:android-shared` (Task 1).
- Produces:
  - Package `io.github.santiquiroz.blindside.shared.sensors`.
  - `class DeviceSensors(sensorManager: SensorManager, listener: DeviceSensorListener)` with `start(stepsAllowed: Boolean): SensorAvailability` and `stop()`.
  - `interface DeviceSensorListener { onGravity(x, y, z, eventNanos); onGyro(x, y, z, eventNanos); onStep(eventNanos) }`.
  - `Vec3`, `GravitySource`, `SensorAvailability`, `passesGate`, `lowPass`, `preferWakeUp` and `gravitySourceFor`, all unchanged.

- [ ] **Step 1: Move the test and watch it fail**

```bash
cd C:/personal/blindside/watch
TST=wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear
SH_TST=android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared
git mv "$TST/sensors" "$SH_TST/sensors"
sed -i -E 's/blindside\.wear\.sensors\b/blindside.shared.sensors/g' "$SH_TST"/sensors/*.kt
./gradlew :android-shared:testDebugUnitTest --console=plain
```

Expected: FAIL with `Unresolved reference 'passesGate'` (and the other sensor symbols).

- [ ] **Step 2: Move the code, rename and rewrite imports**

```bash
cd C:/personal/blindside/watch
SRC=wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear
SH_SRC=android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared
mkdir -p "$SH_SRC/sensors"
git mv "$SRC/sensors/SensorMath.kt" "$SH_SRC/sensors/SensorMath.kt"
git mv "$SRC/sensors/WatchSensors.kt" "$SH_SRC/sensors/DeviceSensors.kt"
rmdir "$SRC/sensors"
grep -rlE 'blindside\.wear\.sensors\b|\bWatchSensors\b|\bWatchSensorListener\b' --include='*.kt' wear-app android-shared \
  | xargs -r sed -i -E 's/blindside\.wear\.sensors\b/blindside.shared.sensors/g; s/\bWatchSensors\b/DeviceSensors/g; s/\bWatchSensorListener\b/DeviceSensorListener/g'
grep -rnE 'blindside\.wear\.sensors|WatchSensors|WatchSensorListener' wear-app/src android-shared/src || echo "no leftovers"
```

Expected: `no leftovers`. `DeviceSensors.kt` now declares `interface DeviceSensorListener` and `class DeviceSensors(...) : SensorEventListener`.

- [ ] **Step 3: Run the verification command and the test-count one-liner**

Expected: `BUILD SUCCESSFUL`; `android-shared 16`, `wear-app 228`.

- [ ] **Step 4: Commit**

```bash
cd C:/personal/blindside
git add watch/android-shared watch/wear-app
git commit -m "refactor: mueve sensors a android-shared y renombra WatchSensors a DeviceSensors"
```

---

### Task 3: Move `settings`

**Files:**
- Move: `wear-app/.../wear/settings/` (4 files) → `android-shared/.../shared/settings/`
- Move: `wear-app/src/test/.../wear/settings/` → `android-shared/src/test/.../shared/settings/`: 4 test files (`AppSettingsTest`, `PipelineConfigMappingTest`, `SettingsPreferencesTest`, `SettingsRepositoryTest`, 19 tests)
- Keep in `wear-app` until Task 7: `wear-app/src/test/.../wear/settings/WatchPostureTest.kt` (5 tests). It runs `demoPackets()` from `wear.demo` (moves in Task 7) and imports `wear.haptics` (moves in Task 4), so it cannot compile in the library yet. It keeps the rewritten package `shared.settings`, which is legal in Kotlin from another module because every symbol it uses is public.
- Modify: `watch/android-shared/build.gradle.kts` (dependencies)
- Modify (via `sed`): every `wear-app` file that imports `wear.settings`

**Interfaces:**
- Consumes: `:android-shared` (Task 1).
- Produces:
  - Package `io.github.santiquiroz.blindside.shared.settings` with unchanged signatures:
    - `AppSettings`, `RadarSettings`, `ScreenMode`, `VibrationUsage`, `WatchPosture`, `SettingsTransform` and `DEFAULT_RADARS`.
    - `toPipelineConfig(settings)`, `mountsFor`, `effectiveYawDeg` and `withYawNudged`.
    - `settingsFrom(prefs)`, `writeSettings(prefs, settings)`, `enumOrDefault` and `parseSpeedSign`.
    - `class SettingsRepository(store, onWriteFailed)` with `settings: Flow<AppSettings>`, `current()` and `update(transform): Boolean`, plus `Context.settingsRepository()`.
  - The DataStore file name stays `blindside_settings`, so settings on the watch survive the update.

- [ ] **Step 1: Move the tests and watch them fail**

```bash
cd C:/personal/blindside/watch
TST=wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear
SH_TST=android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared
git mv "$TST/settings" "$SH_TST/settings"
sed -i -E 's/blindside\.wear\.settings\b/blindside.shared.settings/g' "$SH_TST"/settings/*.kt
# WatchPostureTest still needs wear.demo and wear.haptics: it goes back to wear-app until Task 7.
mkdir -p "$TST/settings"
git mv "$SH_TST/settings/WatchPostureTest.kt" "$TST/settings/WatchPostureTest.kt"
grep -n '^package\|blindside\.wear\.' "$TST/settings/WatchPostureTest.kt"
./gradlew :android-shared:testDebugUnitTest --console=plain
```

Expected:
- The `grep` prints `package io.github.santiquiroz.blindside.shared.settings` and the three `wear.demo` / `wear.haptics` imports.
- Gradle FAILS with `Unresolved reference 'AppSettings'` and `Unresolved reference 'datastore'`.

- [ ] **Step 2: Add the library dependencies**

In `watch/android-shared/build.gradle.kts`, add inside `dependencies { }` after `api(project(":radar-core"))`:

```kotlin
    api(libs.coroutines.android)
    api(libs.datastore.preferences)
```

- [ ] **Step 3: Move the code and rewrite imports**

```bash
cd C:/personal/blindside/watch
SRC=wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear
SH_SRC=android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared
git mv "$SRC/settings" "$SH_SRC/settings"
grep -rlE 'blindside\.wear\.settings\b' --include='*.kt' wear-app android-shared | xargs -r sed -i -E 's/blindside\.wear\.settings\b/blindside.shared.settings/g'
grep -rn 'blindside\.wear\.settings' wear-app/src android-shared/src || echo "no leftovers"
```

Expected: `no leftovers`.

- [ ] **Step 4: Run the verification command and the test-count one-liner**

Expected: `BUILD SUCCESSFUL`; `android-shared 35`, `wear-app 209` (`WatchPostureTest` still runs in `wear-app`).

- [ ] **Step 5: Commit**

```bash
cd C:/personal/blindside
git add watch/android-shared watch/wear-app
git commit -m "refactor: mueve settings a android-shared"
```

---

### Task 4: Move `haptics`

**Files:**
- Move: `wear-app/.../wear/haptics/` (5 files) → `android-shared/.../shared/haptics/`
- Move: `wear-app/src/test/.../wear/haptics/` (3 tests) → `android-shared/src/test/.../shared/haptics/`
- Modify (via `sed`): `wear-app/.../session/RunningSession.kt`, `session/SessionEngine.kt`, `ui/Labels.kt`, `ui/PracticeScreen.kt`, tests `session/SessionEngineTest.kt` and `settings/WatchPostureTest.kt` (still in `wear-app`; Step 2's repo-wide `sed` rewrites its two `wear.haptics` imports)

**Interfaces:**
- Consumes: `shared.settings.VibrationUsage` (Task 3).
- Produces: package `io.github.santiquiroz.blindside.shared.haptics` with unchanged signatures: `HapticPattern`, `LEFT/CENTER/RIGHT/SYSTEM_PATTERN`, `patternFor`, `hapticFor`, `HapticGate`, `afterSystemBuzz`, `contactStartNanos`, `systemFirst`, `millisUntil`, `fun interface HapticSink`, `HapticPlayer.create(context, usage)`, `dndMaySilence`, `dndMaySilenceNow` and `SYSTEM_BUZZ_MS`.

- [ ] **Step 1: Move the tests and watch them fail**

```bash
cd C:/personal/blindside/watch
TST=wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear
SH_TST=android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared
git mv "$TST/haptics" "$SH_TST/haptics"
sed -i -E 's/blindside\.wear\.haptics\b/blindside.shared.haptics/g' "$SH_TST"/haptics/*.kt
./gradlew :android-shared:testDebugUnitTest --console=plain
```

Expected: FAIL with `Unresolved reference 'HapticPattern'`.

- [ ] **Step 2: Move the code and rewrite imports**

```bash
cd C:/personal/blindside/watch
SRC=wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear
SH_SRC=android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared
git mv "$SRC/haptics" "$SH_SRC/haptics"
grep -rlE 'blindside\.wear\.haptics\b' --include='*.kt' wear-app android-shared | xargs -r sed -i -E 's/blindside\.wear\.haptics\b/blindside.shared.haptics/g'
grep -rn 'blindside\.wear\.haptics' wear-app/src android-shared/src || echo "no leftovers"
```

Expected: `no leftovers`.

- [ ] **Step 3: Run the verification command and the test-count one-liner**

Expected: `BUILD SUCCESSFUL`; `android-shared 54`, `wear-app 190`.

- [ ] **Step 4: Commit**

```bash
cd C:/personal/blindside
git add watch/android-shared watch/wear-app
git commit -m "refactor: mueve haptics a android-shared"
```

---

### Task 5: Move `ble`

**Files:**
- Move: `wear-app/.../wear/ble/` (11 files) → `android-shared/.../shared/ble/`
- Move: `wear-app/src/test/.../wear/ble/` (7 tests) → `android-shared/src/test/.../shared/ble/`
- Modify (via `sed`): `session/InputAdapters.kt`, `session/RunningSession.kt`, `session/SessionStore.kt`, `ui/Labels.kt`, `ui/HomeScreen.kt`, `ui/radar/RadarLabels.kt`, `SpikeScreen.kt` and the tests importing `BleStatus`

**Interfaces:**
- Consumes: nothing beyond Task 1.
- Produces: package `io.github.santiquiroz.blindside.shared.ble` with unchanged signatures:
  - `BeltLink(context, listener: BeltListener, onBeltFound: (String) -> Unit)` with `start(address)`, `retry()` and `stop()`.
  - `interface BeltListener`, `BeltGatt` / `BeltGattEvents`, `GattOp`, `SetupEffect`, `effectsAfter`, `setupOpsAfterDiscovery()`, `timeoutMsFor`, `enqueue` and `completeInFlight`.
  - `BleStatus`, `needsRetry`, `sessionActiveCommand`, `isBlindsideName`, `mtuAction`, `nextReconnectAction`, `tryStartScan` and the UUIDs.

- [ ] **Step 1: Move the tests and watch them fail**

```bash
cd C:/personal/blindside/watch
TST=wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear
SH_TST=android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared
git mv "$TST/ble" "$SH_TST/ble"
sed -i -E 's/blindside\.wear\.ble\b/blindside.shared.ble/g' "$SH_TST"/ble/*.kt
./gradlew :android-shared:testDebugUnitTest --console=plain
```

Expected: FAIL with `Unresolved reference 'GattOp'`.

- [ ] **Step 2: Move the code and rewrite imports**

```bash
cd C:/personal/blindside/watch
SRC=wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear
SH_SRC=android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared
git mv "$SRC/ble" "$SH_SRC/ble"
grep -rlE 'blindside\.wear\.ble\b' --include='*.kt' wear-app android-shared | xargs -r sed -i -E 's/blindside\.wear\.ble\b/blindside.shared.ble/g'
grep -rn 'blindside\.wear\.ble' wear-app/src android-shared/src || echo "no leftovers"
```

Expected: `no leftovers`.

- [ ] **Step 3: Run the verification command and the test-count one-liner**

Expected: `BUILD SUCCESSFUL`; `android-shared 97`, `wear-app 147`.

- [ ] **Step 4: Commit**

```bash
cd C:/personal/blindside
git add watch/android-shared watch/wear-app
git commit -m "refactor: mueve ble a android-shared"
```

---

### Task 6: Generalise the session service in place (`SessionHost`, `SessionService`, `SessionCommands` per app)

This task is a refactor inside `wear-app`, guarded by the existing tests and the build. It adds no new tests, because every class it touches is an Android `Service` or builds `Intent`s. It removes the session code's last two links to the Wear app, `BlindsideSessionService::class` and `BuildConfig`, so Task 7 can move the session package.

**Files:**
- Create: `wear-app/.../wear/session/SessionHost.kt`
- Create: `wear-app/.../wear/session/SessionActions.kt`
- Rewrite: `wear-app/.../wear/session/SessionCommands.kt` (object → class)
- Rename + rewrite: `wear-app/.../wear/session/BlindsideSessionService.kt` → `SessionService.kt` (abstract)
- Create: `wear-app/.../wear/session/BlindsideSessionService.kt` (thin subclass)
- Create: `wear-app/.../wear/session/WearSession.kt`
- Modify: `wear-app/.../wear/session/RunningSession.kt`
- Modify (via `sed`): `ui/HomeScreen.kt`, `ui/RadarLaunch.kt`, `SpikeScreen.kt`, `session/SessionNotification.kt`

**Interfaces:**
- Consumes: `shared.permissions`, `shared.settings` (Tasks 1, 3).
- Produces (moved to `shared.session` in Task 7, same names):
  - `interface SessionHost { val appVersion: String; val notificationId: Int; fun ensureNotificationChannel(context: Context); fun notification(context: Context, status: String): Notification }`. Task 9 adds `val beltProfile: BeltLinkProfile`. Task 13 adds `fun launchCompanions(context: Context, scope: CoroutineScope): List<Job>`.
  - `object SessionActions` with the same action strings as before: `ACTION_START`, `ACTION_STOP`, `ACTION_MARKER`, `ACTION_RETRY_LINK`, `ACTION_TOGGLE_ELIMINATED`, `EXTRA_SOURCE` and `EXTRA_WAKE_LOCK`.
  - `class SessionCommands(serviceClass: Class<out Service>)` with `start(context, source, wakeLock = true)`, `stop(context)`, `marker(context)`, `retryLink(context)` and `toggleEliminatedIntent(context): Intent`.
  - `abstract class SessionService : Service()` with `protected abstract val host: SessionHost`.
  - `RunningSession(context, source, settings, scope, useWakeLock, host: SessionHost)`.
  - In `wear-app`: `val WearSessionCommands: SessionCommands` and `object WearSessionHost : SessionHost`.

- [ ] **Step 1: Create `SessionHost.kt`**

```kotlin
package io.github.santiquiroz.blindside.wear.session

import android.app.Notification
import android.content.Context

interface SessionHost {
    val appVersion: String
    val notificationId: Int

    fun ensureNotificationChannel(context: Context)

    fun notification(context: Context, status: String): Notification
}
```

- [ ] **Step 2: Create `SessionActions.kt` and rewrite `SessionCommands.kt`**

`SessionActions.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.session

object SessionActions {
    const val ACTION_START = "io.github.santiquiroz.blindside.action.START"
    const val ACTION_STOP = "io.github.santiquiroz.blindside.action.STOP"
    const val ACTION_MARKER = "io.github.santiquiroz.blindside.action.MARKER"
    const val ACTION_RETRY_LINK = "io.github.santiquiroz.blindside.action.RETRY_LINK"
    const val ACTION_TOGGLE_ELIMINATED = "io.github.santiquiroz.blindside.action.TOGGLE_ELIMINATED"
    const val EXTRA_SOURCE = "source"
    const val EXTRA_WAKE_LOCK = "wake_lock"
}
```

`SessionCommands.kt` (whole file):

```kotlin
package io.github.santiquiroz.blindside.wear.session

import android.app.Service
import android.content.Context
import android.content.Intent

class SessionCommands(private val serviceClass: Class<out Service>) {
    fun start(context: Context, source: SessionSource, wakeLock: Boolean = true) {
        val intent = serviceIntent(context, SessionActions.ACTION_START)
            .putExtra(SessionActions.EXTRA_SOURCE, source.name)
            .putExtra(SessionActions.EXTRA_WAKE_LOCK, wakeLock)
        context.startForegroundService(intent)
    }

    fun stop(context: Context) {
        context.startService(serviceIntent(context, SessionActions.ACTION_STOP))
    }

    fun marker(context: Context) {
        context.startService(serviceIntent(context, SessionActions.ACTION_MARKER))
    }

    fun retryLink(context: Context) {
        context.startService(serviceIntent(context, SessionActions.ACTION_RETRY_LINK))
    }

    fun toggleEliminatedIntent(context: Context): Intent = serviceIntent(context, SessionActions.ACTION_TOGGLE_ELIMINATED)

    private fun serviceIntent(context: Context, action: String): Intent =
        Intent(context, serviceClass).setAction(action)
}
```

- [ ] **Step 3: Turn the service into `SessionService` and add the thin Wear subclass**

```bash
cd C:/personal/blindside/watch
git mv wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/BlindsideSessionService.kt wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/SessionService.kt
```

Write `wear-app/.../wear/session/SessionService.kt` (whole file):

```kotlin
package io.github.santiquiroz.blindside.wear.session

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
    private var notificationSync: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            SessionActions.ACTION_START -> onStart(sourceOf(intent), intent.getBooleanExtra(SessionActions.EXTRA_WAKE_LOCK, true))
            SessionActions.ACTION_STOP -> onStop()
            SessionActions.ACTION_MARKER -> session?.mark() ?: stopSelf()
            SessionActions.ACTION_RETRY_LINK -> session?.retryLink() ?: stopSelf()
            SessionActions.ACTION_TOGGLE_ELIMINATED -> if (session != null) toggleEliminated(scope, settingsRepository()) else stopSelf()
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun sourceOf(intent: Intent): SessionSource = sourceFrom(intent.getStringExtra(SessionActions.EXTRA_SOURCE))

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
        val created = RunningSession(this, source, settingsRepository(), scope, wakeLock, host)
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

    private fun goForeground(source: SessionSource) {
        host.ensureNotificationChannel(this)
        val notification = host.notification(this, ongoingStatus(eliminated = false, source = source))
        startForeground(host.notificationId, notification, foregroundTypesFor(source))
    }

    private suspend fun syncNotification(source: SessionSource) {
        SessionStore.state.map { it.eliminated }.distinctUntilChanged().collect { eliminated ->
            val notification = host.notification(this, ongoingStatus(eliminated, source))
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

Create `wear-app/.../wear/session/BlindsideSessionService.kt`. The manifest entry `.session.BlindsideSessionService` stays as it is:

```kotlin
package io.github.santiquiroz.blindside.wear.session

class BlindsideSessionService : SessionService() {
    override val host: SessionHost = WearSessionHost
}
```

Create `wear-app/.../wear/session/WearSession.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.session

import android.app.Notification
import android.content.Context
import io.github.santiquiroz.blindside.wear.BuildConfig

val WearSessionCommands = SessionCommands(BlindsideSessionService::class.java)

object WearSessionHost : SessionHost {
    override val appVersion: String = BuildConfig.VERSION_NAME
    override val notificationId: Int = SessionNotification.NOTIFICATION_ID

    override fun ensureNotificationChannel(context: Context) = SessionNotification.ensureChannel(context)

    override fun notification(context: Context, status: String): Notification = SessionNotification.build(context, status)
}
```

- [ ] **Step 4: `RunningSession` takes the host instead of `BuildConfig`**

In `wear-app/.../wear/session/RunningSession.kt`:
- Delete the line `import io.github.santiquiroz.blindside.wear.BuildConfig`.
- In the constructor, replace

```kotlin
    private val useWakeLock: Boolean,
) {
```

with

```kotlin
    private val useWakeLock: Boolean,
    private val host: SessionHost,
) {
```

- In `openRecorder`, replace `initial, source.name, stamp, Build.MODEL, BuildConfig.VERSION_NAME,` with `initial, source.name, stamp, Build.MODEL, host.appVersion,`.

- [ ] **Step 5: Point the call sites at `WearSessionCommands`**

```bash
cd C:/personal/blindside/watch
W=wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear
sed -i -E 's/\bSessionCommands\b/WearSessionCommands/g' "$W/ui/HomeScreen.kt" "$W/ui/RadarLaunch.kt" "$W/SpikeScreen.kt" "$W/session/SessionNotification.kt"
grep -rnE 'SessionCommands\.(ACTION|EXTRA)|BuildConfig' "$W/session" ; grep -rn 'WearSessionCommands' "$W" | wc -l
```

Expected:
- The first grep prints only `WearSession.kt`'s `BuildConfig` import and its use.
- The count is 14: the `WearSession.kt` declaration, the 2 imports in `HomeScreen.kt`/`RadarLaunch.kt`, the 1 in `SpikeScreen.kt`, the uses at the call sites, and `SessionNotification.kt`. If it differs, check that every former `SessionCommands.` call now reads `WearSessionCommands.`.

- [ ] **Step 6: Run the verification command and the test-count one-liner**

Expected: `BUILD SUCCESSFUL`; `android-shared 97`, `wear-app 147` (no test changed).

- [ ] **Step 7: Commit**

```bash
cd C:/personal/blindside
git add watch/wear-app
git commit -m "refactor: generaliza el servicio de sesión con SessionHost y SessionCommands por app"
```

---

### Task 7: Move `session`, `recording` and `demo`

**Files:**
- Move to `android-shared/.../shared/session/`: `SessionHost.kt`, `SessionActions.kt`, `SessionCommands.kt`, `SessionService.kt`, `RunningSession.kt`, `SessionEngine.kt`, `SessionInput.kt`, `InputAdapters.kt`, `PipelinePort.kt`, `ScenePacing.kt`, `SessionSource.kt`, `SessionStore.kt`, `EliminatedToggle.kt`, `SessionWakeLock.kt`
- Stay in `wear-app/.../wear/session/`: `BlindsideSessionService.kt`, `SessionNotification.kt`, `WearSession.kt`
- Move: `wear-app/.../wear/recording/` (4 files) and `wear/demo/` (2 files) → `android-shared/.../shared/recording/`, `shared/demo/`
- Move tests: `wear-app/src/test/.../wear/session/` (5), `recording/` (3), `demo/` (2) → `android-shared/src/test/.../shared/...`
- Move test: `wear-app/src/test/.../wear/settings/WatchPostureTest.kt` (left behind in Task 3) → `android-shared/src/test/.../shared/settings/WatchPostureTest.kt`. Its `wear.demo` import becomes `shared.demo`; its package is already `shared.settings`.
- Modify: `watch/android-shared/build.gradle.kts`

**Interfaces:**
- Consumes: Tasks 1-6.
- Produces (unchanged signatures, new package `io.github.santiquiroz.blindside.shared.session`):
  - `SessionService`, `SessionHost`, `SessionActions`, `SessionCommands`.
  - `RunningSession(context, source, settings, scope, useWakeLock, host)` with `start()`, `mark()`, `retryLink()` and `stop()`.
  - `SessionEngine`, `SessionInput`, `SensorInputs` / `BeltInputs`.
  - `SessionStore` (`state`, `radarVisible`, `ambient`, `update`, `toggleEliminated`, `setRadarVisible`, `setAmbient`), `SessionUiState`, `startedState`, `stoppedState` and `blockedState`.
  - `SessionSource`, `StartError`, `foregroundTypesFor`, `startBlocker`, `sourceFrom`, `ongoingStatus`, `scenePeriodMs`, `toggleEliminated(scope, settings)` and `SessionWakeLock`.
  - `shared.recording`: `recordFor`, `RecordSink`, `InfoHeaderSink`, `openRecordingSink`, `recordingsDir(context)`, `recordingFileName`, `headerJson`, `recordingMeta` and `RecordingMeta`.
  - `shared.demo`: `demoPackets()` and `DemoSource`.

- [ ] **Step 1: Move the tests and watch them fail**

```bash
cd C:/personal/blindside/watch
TST=wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear
SH_TST=android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared
git mv "$TST/session" "$SH_TST/session"
git mv "$TST/recording" "$SH_TST/recording"
git mv "$TST/demo" "$SH_TST/demo"
git mv "$TST/settings/WatchPostureTest.kt" "$SH_TST/settings/WatchPostureTest.kt"
rmdir "$TST/settings"
sed -i -E 's/blindside\.wear\.(session|recording|demo)\b/blindside.shared.\1/g' "$SH_TST"/session/*.kt "$SH_TST"/recording/*.kt "$SH_TST"/demo/*.kt "$SH_TST/settings/WatchPostureTest.kt"
grep -rn 'blindside\.wear\.' "$SH_TST" || echo "library tests are app-free"
./gradlew :android-shared:testDebugUnitTest --console=plain
```

Expected: `library tests are app-free`, then FAIL with `Unresolved reference 'SessionInput'` (and others, `demoPackets` among them).

- [ ] **Step 2: Add the dependency `RunningSession` needs**

In `watch/android-shared/build.gradle.kts`, add inside `dependencies { }` after `api(libs.datastore.preferences)`:

```kotlin
    implementation(libs.androidx.core.ktx)
```

- [ ] **Step 3: Move the code and rewrite imports, keeping the three Wear files in `wear.session`**

```bash
cd C:/personal/blindside/watch
SRC=wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear
SH_SRC=android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared
mkdir -p "$SH_SRC/session"
for f in SessionHost SessionActions SessionCommands SessionService RunningSession SessionEngine SessionInput InputAdapters PipelinePort ScenePacing SessionSource SessionStore EliminatedToggle SessionWakeLock; do
  git mv "$SRC/session/$f.kt" "$SH_SRC/session/$f.kt"
done
git mv "$SRC/recording" "$SH_SRC/recording"
git mv "$SRC/demo" "$SH_SRC/demo"
grep -rlE 'blindside\.wear\.(session|recording|demo)\b' --include='*.kt' wear-app android-shared \
  | grep -vE '/(BlindsideSessionService|SessionNotification|WearSession)\.kt' \
  | xargs -r sed -i -E 's/blindside\.wear\.(session|recording|demo)\b/blindside.shared.\1/g'
grep -rl 'blindside\.shared\.session\.WearSessionCommands' --include='*.kt' wear-app | xargs -r sed -i 's/blindside\.shared\.session\.WearSessionCommands/blindside.wear.session.WearSessionCommands/g'
ls "$SRC/session"
```

Expected: `ls` lists exactly `BlindsideSessionService.kt`, `SessionNotification.kt` and `WearSession.kt`.

- [ ] **Step 4: Give the two remaining Wear session files their new imports**

`wear-app/.../wear/session/BlindsideSessionService.kt` (whole file):

```kotlin
package io.github.santiquiroz.blindside.wear.session

import io.github.santiquiroz.blindside.shared.session.SessionHost
import io.github.santiquiroz.blindside.shared.session.SessionService

class BlindsideSessionService : SessionService() {
    override val host: SessionHost = WearSessionHost
}
```

`wear-app/.../wear/session/WearSession.kt` (whole file):

```kotlin
package io.github.santiquiroz.blindside.wear.session

import android.app.Notification
import android.content.Context
import io.github.santiquiroz.blindside.shared.session.SessionCommands
import io.github.santiquiroz.blindside.shared.session.SessionHost
import io.github.santiquiroz.blindside.wear.BuildConfig

val WearSessionCommands = SessionCommands(BlindsideSessionService::class.java)

object WearSessionHost : SessionHost {
    override val appVersion: String = BuildConfig.VERSION_NAME
    override val notificationId: Int = SessionNotification.NOTIFICATION_ID

    override fun ensureNotificationChannel(context: Context) = SessionNotification.ensureChannel(context)

    override fun notification(context: Context, status: String): Notification = SessionNotification.build(context, status)
}
```

- [ ] **Step 5: Check that nothing in the library still points at the app**

```bash
cd C:/personal/blindside/watch
grep -rn 'blindside\.wear\.' android-shared/src || echo "library is app-free"
```

Expected: `library is app-free`.

- [ ] **Step 6: Run the verification command and the test-count one-liner**

Expected: `BUILD SUCCESSFUL`; `android-shared 169`, `wear-app 75`.

- [ ] **Step 7: Commit**

```bash
cd C:/personal/blindside
git add watch/android-shared watch/wear-app
git commit -m "refactor: mueve sesión, grabación y demo a android-shared"
```

---

### Task 8: Move the radar geometry, rotation, labels and Canvas

**Files:**
- Move: `wear-app/.../wear/ui/radar/RadarGeometry.kt`, `DeviceRotation.kt`, `RadarCanvas.kt` and `RadarLabels.kt` → `android-shared/.../shared/radar/`
- Create: `android-shared/.../shared/radar/RadarColors.kt`
- Create: `wear-app/.../wear/ui/radar/RadarLinkMessage.kt` (Deviation D3)
- Move tests: `RadarGeometryTest.kt`, `DeviceRotationTest.kt` and `RadarLabelsTest.kt` → `android-shared/src/test/.../shared/radar/`
- Create test: `wear-app/src/test/.../wear/ui/radar/RadarLinkMessageTest.kt`
- Modify: `wear-app/.../ui/radar/RadarScreen.kt`, `ui/ScreenPolicy.kt`, `ui/HomeScreen.kt`, `ui/Palette.kt`, test `ui/ScreenPolicyTest.kt`, `android-shared/build.gradle.kts`

**Interfaces:**
- Consumes: `shared.session.SessionUiState`, `shared.ble.BleStatus`, `shared.settings.WatchPosture` (in tests).
- Produces (package `io.github.santiquiroz.blindside.shared.radar`):
  - Unchanged: `PointPx`, `BlipStyle`, `BlipDraw`, `EdgeMarkerDraw`, `SectorDraw`, `RadarDrawModel`, `toDrawModel(scene, widthPx, heightPx, offset, showContacts)`, `showContacts(scene, ambient)`, `polarToPx`, `sectorArc`, `blipStyle`, `blipAlpha`, `screenCenter`, `RadarDrawModel.rotatedAbout(pivot, rotationDeg)` and `rotatePoint`.
  - From the labels: `StatusItem`, `centerLabel(scene, ambient, linkMessage)`, `warningLabel`, `labelFor`, `statusItems(scene, watchSteps)` and `eliminatedActionLabel`, plus the constants `NO_DATA_LABEL`, `ELIMINATED_LABEL`, `NO_WATCH_STEPS_LABEL`, `CONNECTING_TO_BELT_LABEL` and `DND_RADAR_WARNING`.
  - **Changed:** `fun DrawScope.drawRadar(model: RadarDrawModel, colors: RadarColors)`.
  - `data class RadarColors(contact: Color, contactDim: Color, fan: Color, ring: Color, dimmed: Color)` and `val CLASSIC_RADAR_COLORS` (today's palette).
  - `wear.ui.radar` keeps `radarMessage(session: SessionUiState): String?` and `linkMessage(source, ble, linkUp): String?`.

- [ ] **Step 1: Move the tests, write the two label test files and watch them fail**

```bash
cd C:/personal/blindside/watch
TST=wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear
SH_TST=android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared
mkdir -p "$SH_TST/radar"
git mv "$TST/ui/radar/DeviceRotationTest.kt" "$SH_TST/radar/DeviceRotationTest.kt"
git mv "$TST/ui/radar/RadarGeometryTest.kt" "$SH_TST/radar/RadarGeometryTest.kt"
git mv "$TST/ui/radar/RadarLabelsTest.kt" "$SH_TST/radar/RadarLabelsTest.kt"
sed -i 's/package io\.github\.santiquiroz\.blindside\.wear\.ui\.radar/package io.github.santiquiroz.blindside.shared.radar/' "$SH_TST/radar/DeviceRotationTest.kt" "$SH_TST/radar/RadarGeometryTest.kt"
```

Overwrite `android-shared/src/test/.../shared/radar/RadarLabelsTest.kt` with the 13 tests that move. These are the original tests minus the five link-message tests:

```kotlin
package io.github.santiquiroz.blindside.shared.radar

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
    fun `the link message replaces the dashes while connecting`() {
        assertEquals(CONNECTING_TO_BELT_LABEL, centerLabel(null, ambient = false, linkMessage = CONNECTING_TO_BELT_LABEL))
        assertEquals(CONNECTING_TO_BELT_LABEL, centerLabel(scene(linkUp = false), ambient = false, linkMessage = CONNECTING_TO_BELT_LABEL))
    }

    @Test
    fun `ambient and eliminated still win over the link message`() {
        assertEquals(NO_DATA_LABEL, centerLabel(null, ambient = true, linkMessage = CONNECTING_TO_BELT_LABEL))
        assertEquals(ELIMINATED_LABEL, centerLabel(scene(linkUp = false, eliminated = true), ambient = false, linkMessage = CONNECTING_TO_BELT_LABEL))
    }

    @Test
    fun `the do not disturb warning fits one short line`() {
        assertTrue(DND_RADAR_WARNING.length <= 30)
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

Create `wear-app/src/test/.../wear/ui/radar/RadarLinkMessageTest.kt` with the five link-message tests:

```kotlin
package io.github.santiquiroz.blindside.wear.ui.radar

import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.radar.CONNECTING_TO_BELT_LABEL
import io.github.santiquiroz.blindside.shared.session.SessionSource
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.session.StartError
import io.github.santiquiroz.blindside.wear.ui.bleStatusLabel
import io.github.santiquiroz.blindside.wear.ui.startErrorMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RadarLinkMessageTest {
    private val liveScene = RadarScene(
        blips = emptyList(),
        coverage = emptyList(),
        linkUp = true,
        radars = listOf(SensorStatus(0, true), SensorStatus(1, true)),
        imus = listOf(SensorStatus(0, true), SensorStatus(1, true)),
        motion = MotionState.STILL,
        warnings = emptySet(),
        eliminated = false,
    )

    @Test
    fun `a belt game says it is connecting until the link is up`() {
        listOf(BleStatus.IDLE, BleStatus.CONNECTING, BleStatus.STREAMING).forEach { status ->
            assertEquals(CONNECTING_TO_BELT_LABEL, linkMessage(SessionSource.BELT, status, linkUp = false))
        }
        assertNull(linkMessage(SessionSource.BELT, BleStatus.STREAMING, linkUp = true))
    }

    @Test
    fun `searching, pairing and halted links keep their own instructions`() {
        listOf(BleStatus.SEARCHING, BleStatus.PAIRING, BleStatus.RECONNECTING, BleStatus.BOND_LOST, BleStatus.BLUETOOTH_OFF).forEach { status ->
            assertEquals(bleStatusLabel(status), linkMessage(SessionSource.BELT, status, linkUp = false))
        }
    }

    @Test
    fun `demo and idle screens have no link message`() {
        assertNull(linkMessage(SessionSource.DEMO, BleStatus.IDLE, linkUp = false))
        assertNull(linkMessage(null, BleStatus.IDLE, linkUp = false))
    }

    @Test
    fun `the radar of a starting belt game says it is connecting`() {
        val starting = SessionUiState(running = true, source = SessionSource.BELT, ble = BleStatus.IDLE)
        assertEquals(CONNECTING_TO_BELT_LABEL, radarMessage(starting))
        assertNull(radarMessage(starting.copy(ble = BleStatus.STREAMING, scene = liveScene)))
    }

    @Test
    fun `a refused start is explained on the radar`() {
        val refused = SessionUiState(startError = StartError.BLUETOOTH_UNAVAILABLE)
        assertEquals(startErrorMessage(StartError.BLUETOOTH_UNAVAILABLE), radarMessage(refused))
    }
}
```

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest --console=plain`
Expected: FAIL with `Unresolved reference 'toDrawModel'` / `'centerLabel'`.

- [ ] **Step 2: Add `compose-ui` to the library (no Wear Material, spec §3)**

In `watch/android-shared/build.gradle.kts`, add inside `dependencies { }` after `implementation(libs.androidx.core.ktx)`:

```kotlin
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
```

- [ ] **Step 3: Move the code**

```bash
cd C:/personal/blindside/watch
SRC=wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear
SH_SRC=android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared
mkdir -p "$SH_SRC/radar"
for f in RadarGeometry DeviceRotation RadarCanvas RadarLabels; do git mv "$SRC/ui/radar/$f.kt" "$SH_SRC/radar/$f.kt"; done
sed -i 's/package io\.github\.santiquiroz\.blindside\.wear\.ui\.radar/package io.github.santiquiroz.blindside.shared.radar/' "$SH_SRC/radar/RadarGeometry.kt" "$SH_SRC/radar/DeviceRotation.kt"
sed -i 's/blindside\.wear\.ui\.radar\.PointPx/blindside.shared.radar.PointPx/g' "$SRC/ui/ScreenPolicy.kt" wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ui/ScreenPolicyTest.kt
sed -i 's/blindside\.wear\.ui\.radar\.eliminatedActionLabel/blindside.shared.radar.eliminatedActionLabel/g' "$SRC/ui/HomeScreen.kt"
```

- [ ] **Step 4: Write `RadarColors.kt`, the new `RadarCanvas.kt` and the shared `RadarLabels.kt`**

`android-shared/.../shared/radar/RadarColors.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.radar

import androidx.compose.ui.graphics.Color

data class RadarColors(
    val contact: Color,
    val contactDim: Color,
    val fan: Color,
    val ring: Color,
    val dimmed: Color,
)

val CLASSIC_RADAR_COLORS = RadarColors(
    contact = Color(0xFFE53935),
    contactDim = Color(0xFFE53935),
    fan = Color(0xFF3A3A3A),
    ring = Color(0xFF2C2C2C),
    dimmed = Color(0xFF1F1F1F),
)
```

`android-shared/.../shared/radar/RadarCanvas.kt` (whole file):

```kotlin
package io.github.santiquiroz.blindside.shared.radar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

private const val LINE_WIDTH_PX = 2f
private const val BLIP_STROKE_PX = 3f
private const val EDGE_MARKER_WIDTH_PX = 5f
private val DASHED = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))

fun DrawScope.drawRadar(model: RadarDrawModel, colors: RadarColors) {
    drawFan(model, colors)
    drawRings(model, colors)
    model.blips.forEach { drawBlip(it, model.blipRadiusPx, colors) }
    model.edgeMarkers.forEach { drawEdgeMarker(it, colors) }
}

private fun DrawScope.drawFan(model: RadarDrawModel, colors: RadarColors) {
    val color = if (model.dimmed) colors.dimmed else colors.fan
    model.sectors.forEach { drawSectorArc(model.origin, model.radiusPx, it, color, useCenter = true) }
}

private fun DrawScope.drawRings(model: RadarDrawModel, colors: RadarColors) {
    val color = if (model.dimmed) colors.dimmed else colors.ring
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

private fun DrawScope.drawBlip(blip: BlipDraw, radius: Float, colors: RadarColors) {
    val color = colors.contact.copy(alpha = blip.alpha)
    val center = Offset(blip.center.x, blip.center.y)
    when (blip.style) {
        BlipStyle.FILLED -> drawCircle(color, radius, center)
        BlipStyle.OUTLINE -> drawCircle(color, radius, center, style = Stroke(width = BLIP_STROKE_PX))
        BlipStyle.DASHED -> drawCircle(color, radius, center, style = Stroke(width = BLIP_STROKE_PX, pathEffect = DASHED))
    }
}

private fun DrawScope.drawEdgeMarker(marker: EdgeMarkerDraw, colors: RadarColors) {
    drawLine(
        color = colors.contact.copy(alpha = marker.alpha),
        start = Offset(marker.inner.x, marker.inner.y),
        end = Offset(marker.outer.x, marker.outer.y),
        strokeWidth = EDGE_MARKER_WIDTH_PX,
        cap = StrokeCap.Round,
    )
}
```

`android-shared/.../shared/radar/RadarLabels.kt` (whole file; `radarMessage` and `linkMessage` leave it):

```kotlin
package io.github.santiquiroz.blindside.shared.radar

import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import io.github.santiquiroz.blindside.core.scene.Warning

data class StatusItem(val label: String, val ok: Boolean)

const val NO_DATA_LABEL = "--"
const val ELIMINATED_LABEL = "ELIMINADO"
const val NO_WATCH_STEPS_LABEL = "SIN PASOS"
const val CONNECTING_TO_BELT_LABEL = "Conectando con el cinturón…"
const val DND_RADAR_WARNING = "NO MOLESTAR: PUEDE NO VIBRAR"

private val WARNING_PRIORITY = listOf(
    Warning.RADAR_DOWN,
    Warning.IMU_DOWN,
    Warning.NO_IMU_COMPENSATION,
    Warning.PRONE,
    Warning.ALERT_OVERFLOW,
    Warning.CORRUPT_FRAMES,
    Warning.YAW_UNCALIBRATED,
)

fun centerLabel(scene: RadarScene?, ambient: Boolean, linkMessage: String? = null): String? = when {
    scene?.eliminated == true -> ELIMINATED_LABEL
    ambient -> NO_DATA_LABEL
    linkMessage != null -> linkMessage
    scene == null || !scene.linkUp -> NO_DATA_LABEL
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

- [ ] **Step 5: Keep the watch-worded link messages in `wear-app`**

Create `wear-app/.../wear/ui/radar/RadarLinkMessage.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui.radar

import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.radar.CONNECTING_TO_BELT_LABEL
import io.github.santiquiroz.blindside.shared.session.SessionSource
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.wear.ui.bleStatusLabel
import io.github.santiquiroz.blindside.wear.ui.startErrorMessage

private val CONNECTING_STATUSES = setOf(BleStatus.IDLE, BleStatus.CONNECTING, BleStatus.STREAMING)

// The radar opens before the service answers, so a refused start must be explained here and not only on the home.
fun radarMessage(session: SessionUiState): String? =
    session.startError?.let(::startErrorMessage) ?: linkMessage(session.source, session.ble, session.scene?.linkUp == true)

// Searching and pairing keep their own instructions: the first pairing needs the BOOT hint and the passkey prompt.
fun linkMessage(source: SessionSource?, ble: BleStatus, linkUp: Boolean): String? = when {
    source != SessionSource.BELT || linkUp -> null
    ble in CONNECTING_STATUSES -> CONNECTING_TO_BELT_LABEL
    else -> bleStatusLabel(ble)
}
```

- [ ] **Step 6: Wire `RadarScreen` and drop the moved colours from `Palette.kt`**

In `wear-app/.../wear/ui/radar/RadarScreen.kt`, add these imports right after `import androidx.wear.compose.material.Text`:

```kotlin
import io.github.santiquiroz.blindside.shared.radar.CLASSIC_RADAR_COLORS
import io.github.santiquiroz.blindside.shared.radar.DND_RADAR_WARNING
import io.github.santiquiroz.blindside.shared.radar.PointPx
import io.github.santiquiroz.blindside.shared.radar.StatusItem
import io.github.santiquiroz.blindside.shared.radar.centerLabel
import io.github.santiquiroz.blindside.shared.radar.drawRadar
import io.github.santiquiroz.blindside.shared.radar.eliminatedActionLabel
import io.github.santiquiroz.blindside.shared.radar.rotatedAbout
import io.github.santiquiroz.blindside.shared.radar.screenCenter
import io.github.santiquiroz.blindside.shared.radar.showContacts
import io.github.santiquiroz.blindside.shared.radar.statusItems
import io.github.santiquiroz.blindside.shared.radar.toDrawModel
import io.github.santiquiroz.blindside.shared.radar.warningLabel
```

Then replace

```kotlin
            drawRadar(logical.rotatedAbout(screenCenter(size.width, size.height, shift), rotationDeg))
```

with

```kotlin
            drawRadar(logical.rotatedAbout(screenCenter(size.width, size.height, shift), rotationDeg), CLASSIC_RADAR_COLORS)
```

In `wear-app/.../wear/ui/Palette.kt`, delete the four lines that declare `CONTACT_RED`, `FAN_LINE`, `RING_LINE` and `DIMMED_LINE`. The file keeps `LABEL_GRAY`, `WARNING_AMBER`, `STATUS_OK` and `STATUS_BAD`.

```bash
cd C:/personal/blindside/watch
grep -rnE 'wear\.ui\.radar\.(PointPx|eliminatedActionLabel)|CONTACT_RED|FAN_LINE|RING_LINE|DIMMED_LINE' wear-app/src android-shared/src || echo "no leftovers"
```

Expected: `no leftovers`.

- [ ] **Step 7: Run the verification command and the test-count one-liner**

Expected: `BUILD SUCCESSFUL`; `android-shared 205`, `wear-app 39` (the total is still 244).

- [ ] **Step 8: Commit**

```bash
cd C:/personal/blindside
git add watch/android-shared watch/wear-app
git commit -m "refactor: mueve la geometría, las etiquetas y el Canvas del radar a android-shared"
```

---

## Part B — Watch side of the bridge (spec §2 commands, §5)

### Task 9: Belt commands, link profiles and `SET_ROLE` on every connection

This task builds the generic BLE surface that both apps use (Cross-plan contract item 2). The watch's only visible change is the `06 00` write. Its priorities stay HIGH while connecting and BALANCED once the session is active.

**Files:**
- Create: `android-shared/.../shared/ble/BeltCommands.kt`
- Modify: `android-shared/.../shared/ble/GattOps.kt`, `ble/BeltGatt.kt`, `ble/BeltLink.kt`, `ble/BeltListener.kt`
- Modify: `android-shared/.../shared/session/SessionHost.kt`, `session/RunningSession.kt`
- Modify: `wear-app/.../wear/session/WearSession.kt`
- Test: `android-shared/src/test/.../shared/ble/BeltCommandsTest.kt` (new), `ble/GattOpsTest.kt`
- Modify: `watch/wear-app/README.md` (checklist)

**Interfaces:**
- Consumes: `SessionHost` and `RunningSession` (Task 7); `GattOp`, `SetupEffect`, `OpQueue`, `BeltGatt`, `BeltLink`, `REQUESTED_MTU` and `GATT_SUCCESS_STATUS` (Task 5).
- Produces (package `io.github.santiquiroz.blindside.shared.ble`):
  - `enum class BeltRole(val code: Int) { WATCH(0), PHONE(1) }`.
  - `sealed interface BeltCommand` with `RestartRadar(radarId: Int)`, `Identify` and `SetRole(role: BeltRole)` (Task 10 adds `OpenPairingWindow`), and `commandBytes(command: BeltCommand): ByteArray`.
  - `enum class LinkPriority { HIGH, BALANCED, LOW_POWER }`, `connectPriorityFor(role): LinkPriority` and `settledPriorityFor(role): LinkPriority`.
  - `data class BeltLinkProfile(val role: BeltRole = BeltRole.WATCH, val activatesSession: Boolean = true)`.
  - `GattOp.WriteCommand(command: BeltCommand)`, `setupOpsAfterDiscovery(profile: BeltLinkProfile = BeltLinkProfile())`, `effectsAfter(op, status, profile: BeltLinkProfile = BeltLinkProfile())` and `isControlWrite(op): Boolean`.
  - `data class CommandResult(val command: BeltCommand, val delivered: Boolean)` and `commandResultOf(op: GattOp, status: Int): CommandResult?`.
  - `BeltGattEvents.onCommandWritten(source: BeltGatt, result: CommandResult, nowNanos: Long)`, and `BeltListener.onCommandWritten(result: CommandResult, nowNanos: Long)` with an empty default body.
  - `BeltGatt(context, device, handler, events, profile = BeltLinkProfile())` with `send(command)` and `readInfo()`.
  - `BeltLink(context, listener, onBeltFound, profile = BeltLinkProfile())` with `send(command): Boolean` and `refreshInfo(): Boolean`. Both return `false` and send nothing unless the link streams.
  - `SessionHost.beltProfile: BeltLinkProfile`. `WearSessionHost.beltProfile = BeltLinkProfile(BeltRole.WATCH, activatesSession = true)`.

- [ ] **Step 1: Write the failing tests**

Create `android-shared/src/test/.../shared/ble/BeltCommandsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.ble

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BeltCommandsTest {
    @Test
    fun `restart radar carries the radar id`() {
        assertArrayEquals(byteArrayOf(0x01, 0x00), commandBytes(BeltCommand.RestartRadar(0)))
        assertArrayEquals(byteArrayOf(0x01, 0x01), commandBytes(BeltCommand.RestartRadar(1)))
    }

    @Test
    fun `identify is a single byte`() {
        assertArrayEquals(byteArrayOf(0x03), commandBytes(BeltCommand.Identify))
    }

    @Test
    fun `set role sends 06 with the firmware role code`() {
        assertArrayEquals(byteArrayOf(0x06, 0x00), commandBytes(BeltCommand.SetRole(BeltRole.WATCH)))
        assertArrayEquals(byteArrayOf(0x06, 0x01), commandBytes(BeltCommand.SetRole(BeltRole.PHONE)))
    }

    @Test
    fun `the watch connects fast and settles balanced`() {
        assertEquals(LinkPriority.HIGH, connectPriorityFor(BeltRole.WATCH))
        assertEquals(LinkPriority.BALANCED, settledPriorityFor(BeltRole.WATCH))
    }

    @Test
    fun `the phone never asks for the fast interval the watch needs`() {
        assertEquals(LinkPriority.BALANCED, connectPriorityFor(BeltRole.PHONE))
        assertEquals(LinkPriority.LOW_POWER, settledPriorityFor(BeltRole.PHONE))
    }
}
```

In `GattOpsTest.kt`, replace the test `setup requests the mtu before enabling notifications and activates the session last` with:

```kotlin
    @Test
    fun `the watch announces its role after the info read and activates the session last`() {
        val expected = listOf(
            GattOp.RequestMtu(REQUESTED_MTU),
            GattOp.ReadInfo,
            GattOp.WriteCommand(BeltCommand.SetRole(BeltRole.WATCH)),
            GattOp.EnableStreamNotify,
            GattOp.WriteSessionActive(true),
        )
        assertEquals(BeltLinkProfile(BeltRole.WATCH, activatesSession = true), BeltLinkProfile())
        assertEquals(expected, setupOpsAfterDiscovery(BeltLinkProfile()))
    }
```

In the test `the mtu wait is two seconds and other ops five`, add the line:

```kotlin
        assertEquals(5_000L, timeoutMsFor(GattOp.WriteCommand(BeltCommand.SetRole(BeltRole.WATCH))))
```

Add five tests:

```kotlin
    @Test
    fun `the phone announces its own role`() {
        val ops = setupOpsAfterDiscovery(BeltLinkProfile(BeltRole.PHONE))
        assertTrue(GattOp.WriteCommand(BeltCommand.SetRole(BeltRole.PHONE)) in ops)
        assertFalse(GattOp.WriteCommand(BeltCommand.SetRole(BeltRole.WATCH)) in ops)
    }

    @Test
    fun `a diagnostic link never marks the session active and settles once subscribed`() {
        val diagnostic = BeltLinkProfile(BeltRole.PHONE, activatesSession = false)
        assertFalse(setupOpsAfterDiscovery(diagnostic).any { it is GattOp.WriteSessionActive })
        assertEquals(
            listOf(SetupEffect.REPORT_LINK_UP, SetupEffect.LOWER_PRIORITY),
            effectsAfter(GattOp.EnableStreamNotify, GATT_SUCCESS_STATUS, diagnostic),
        )
    }

    @Test
    fun `a failed role write keeps the setup going`() {
        val setRole = GattOp.WriteCommand(BeltCommand.SetRole(BeltRole.WATCH))
        assertEquals(nothing, effectsAfter(setRole, 133))
        assertEquals(nothing, effectsAfter(setRole, LOCAL_FAILURE_STATUS))
        assertEquals(nothing, effectsAfter(setRole, GATT_SUCCESS_STATUS))
    }

    @Test
    fun `control writes are the session write and every command`() {
        assertTrue(isControlWrite(GattOp.WriteSessionActive(false)))
        assertTrue(isControlWrite(GattOp.WriteCommand(BeltCommand.Identify)))
        assertFalse(isControlWrite(GattOp.ReadInfo))
        assertFalse(isControlWrite(GattOp.EnableStreamNotify))
    }

    @Test
    fun `a command write reports whether the belt took it`() {
        val identify = GattOp.WriteCommand(BeltCommand.Identify)
        assertEquals(CommandResult(BeltCommand.Identify, delivered = true), commandResultOf(identify, GATT_SUCCESS_STATUS))
        assertEquals(CommandResult(BeltCommand.Identify, delivered = false), commandResultOf(identify, 3))
        assertEquals(CommandResult(BeltCommand.Identify, delivered = false), commandResultOf(identify, LOCAL_FAILURE_STATUS))
        assertNull(commandResultOf(GattOp.ReadInfo, GATT_SUCCESS_STATUS))
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest --console=plain`
Expected: FAIL with `Unresolved reference 'BeltCommand'` (and `'BeltLinkProfile'`, `'commandResultOf'`).

- [ ] **Step 3: The commands, roles, priorities and profile**

Create `android-shared/.../shared/ble/BeltCommands.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.ble

enum class BeltRole(val code: Int) { WATCH(0), PHONE(1) }

sealed interface BeltCommand {
    data class RestartRadar(val radarId: Int) : BeltCommand
    data object Identify : BeltCommand
    data class SetRole(val role: BeltRole) : BeltCommand
}

enum class LinkPriority { HIGH, BALANCED, LOW_POWER }

data class BeltLinkProfile(val role: BeltRole = BeltRole.WATCH, val activatesSession: Boolean = true)

private const val CMD_RESTART_RADAR: Byte = 0x01
private const val CMD_IDENTIFY: Byte = 0x03
private const val CMD_SET_ROLE: Byte = 0x06

fun commandBytes(command: BeltCommand): ByteArray = when (command) {
    is BeltCommand.RestartRadar -> byteArrayOf(CMD_RESTART_RADAR, command.radarId.toByte())
    BeltCommand.Identify -> byteArrayOf(CMD_IDENTIFY)
    is BeltCommand.SetRole -> byteArrayOf(CMD_SET_ROLE, command.role.code.toByte())
}

fun connectPriorityFor(role: BeltRole): LinkPriority =
    if (role == BeltRole.WATCH) LinkPriority.HIGH else LinkPriority.BALANCED

// Spec §2: the belt gives the phone 60-100 ms; LOW_POWER lets the belt's request win instead of competing with the watch.
fun settledPriorityFor(role: BeltRole): LinkPriority =
    if (role == BeltRole.WATCH) LinkPriority.BALANCED else LinkPriority.LOW_POWER
```

- [ ] **Step 4: The op queue learns commands and profiles**

In `GattOps.kt`:
- Add `data class WriteCommand(val command: BeltCommand) : GattOp` as the last member of `sealed interface GattOp`.
- Add after `data class QueueStep(...)`:

```kotlin
data class CommandResult(val command: BeltCommand, val delivered: Boolean)
```

- Replace `fun setupOpsAfterDiscovery(): List<GattOp> = listOf(...)` with:

```kotlin
// The role write needs the encrypted link the info read sets up, and goes before the subscription so the belt applies the role's connection parameters before it streams.
fun setupOpsAfterDiscovery(profile: BeltLinkProfile = BeltLinkProfile()): List<GattOp> =
    listOf(
        GattOp.RequestMtu(REQUESTED_MTU),
        GattOp.ReadInfo,
        GattOp.WriteCommand(BeltCommand.SetRole(profile.role)),
        GattOp.EnableStreamNotify,
    ) + sessionOps(profile.activatesSession)
```

- Replace the whole `fun effectsAfter(op: GattOp, status: Int): List<SetupEffect> = when { ... }` with:

```kotlin
fun effectsAfter(op: GattOp, status: Int, profile: BeltLinkProfile = BeltLinkProfile()): List<SetupEffect> = when {
    status != GATT_SUCCESS_STATUS && endsSetupOnFailure(op, status) -> listOf(SetupEffect.DISCONNECT)
    op == GattOp.EnableStreamNotify -> linkUpEffects(profile)
    op == GattOp.WriteSessionActive(true) -> listOf(SetupEffect.LOWER_PRIORITY)
    else -> emptyList()
}

fun isControlWrite(op: GattOp): Boolean = op is GattOp.WriteSessionActive || op is GattOp.WriteCommand

fun commandResultOf(op: GattOp, status: Int): CommandResult? =
    (op as? GattOp.WriteCommand)?.let { CommandResult(it.command, delivered = status == GATT_SUCCESS_STATUS) }
```

- Add at the end of the file:

```kotlin
private fun sessionOps(activatesSession: Boolean): List<GattOp> =
    if (activatesSession) listOf(GattOp.WriteSessionActive(true)) else emptyList()

// Without a session write nothing else settles the link, so the subscription that brings it up also lowers its priority.
private fun linkUpEffects(profile: BeltLinkProfile): List<SetupEffect> =
    if (profile.activatesSession) listOf(SetupEffect.REPORT_LINK_UP) else listOf(SetupEffect.REPORT_LINK_UP, SetupEffect.LOWER_PRIORITY)
```

- [ ] **Step 5: `BeltGatt` takes the profile, writes commands and reports their result**

In `BeltGatt.kt`:
- In `interface BeltGattEvents`, add `fun onCommandWritten(source: BeltGatt, result: CommandResult, nowNanos: Long)` after `onRssi`.
- Add `private val profile: BeltLinkProfile = BeltLinkProfile(),` as the last constructor parameter, after `private val events: BeltGattEvents,`.
- After `fun deactivateSession() = enqueueOp(GattOp.WriteSessionActive(false))`, add:

```kotlin
    fun send(command: BeltCommand) = enqueueOp(GattOp.WriteCommand(command))

    fun readInfo() = enqueueOp(GattOp.ReadInfo)
```

- In `start(op)`, add the branch `is GattOp.WriteCommand -> writeControl(current, commandBytes(op.command))` before `GattOp.ReadRssi -> current.readRemoteRssi()`.
- Replace `finish` with:

```kotlin
    private fun finish(op: GattOp, status: Int) {
        if (queue.inFlight != op) return
        handler.removeCallbacksAndMessages(timeoutToken)
        logResult(op, status)
        val effects = effectsAfter(op, status, profile)
        if (SetupEffect.DISCONNECT in effects) return events.onSetupFailed(this, op, status)
        applyEffects(effects)
        reportCommandResult(op, status)
        advanceQueue()
    }

    private fun reportCommandResult(op: GattOp, status: Int) {
        commandResultOf(op, status)?.let { events.onCommandWritten(this, it, SystemClock.elapsedRealtimeNanos()) }
    }
```

- In `applyEffects`, replace `gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)` with `gatt?.requestConnectionPriority(androidPriority(settledPriorityFor(profile.role)))`.
- In `handleConnected`, replace `gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)` with `gatt?.requestConnectionPriority(androidPriority(connectPriorityFor(profile.role)))`.
- In `handleDiscovery`, replace `setupOpsAfterDiscovery().forEach(::enqueueOp)` with `setupOpsAfterDiscovery(profile).forEach(::enqueueOp)`.
- Replace `handleControlWrite` with:

```kotlin
    private fun handleControlWrite(status: Int) {
        val op = queue.inFlight?.takeIf(::isControlWrite) ?: return
        finish(op, status)
    }
```

- Add before `private val callback = object : BluetoothGattCallback() {`:

```kotlin
    private fun androidPriority(priority: LinkPriority): Int = when (priority) {
        LinkPriority.HIGH -> BluetoothGatt.CONNECTION_PRIORITY_HIGH
        LinkPriority.BALANCED -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        LinkPriority.LOW_POWER -> BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
    }
```

- [ ] **Step 6: `BeltLink` passes the profile and offers commands**

`BeltListener.kt`: add as the last member:

```kotlin
    // Only the apps that act on a command result override this; the default keeps every other listener unchanged.
    fun onCommandWritten(result: CommandResult, nowNanos: Long) {}
```

In `BeltLink.kt`:
- Add `private val profile: BeltLinkProfile = BeltLinkProfile(),` as the last constructor parameter, after `private val onBeltFound: (String) -> Unit,`.
- In `openGatt`, replace `BeltGatt(context, target, handler, this)` with `BeltGatt(context, target, handler, this, profile)`.
- Add after `fun stop() { ... }`:

```kotlin
    fun send(command: BeltCommand): Boolean = withStreamingGatt { it.send(command) }

    fun refreshInfo(): Boolean = withStreamingGatt { it.readInfo() }
```

- Add after `override fun onRssi(...)`:

```kotlin
    override fun onCommandWritten(source: BeltGatt, result: CommandResult, nowNanos: Long) {
        if (source === gatt) listener.onCommandWritten(result, nowNanos)
    }
```

- Add before `private fun connectingStatus()`:

```kotlin
    // Commands ride only on the encrypted, subscribed link; before that the setup queue owns the GATT.
    private fun withStreamingGatt(action: (BeltGatt) -> Unit): Boolean {
        val current = gatt?.takeIf { reporter.up } ?: return false
        action(current)
        return true
    }
```

- [ ] **Step 7: Each app chooses its profile through `SessionHost`**

In `android-shared/.../shared/session/SessionHost.kt`:
- Add the import `import io.github.santiquiroz.blindside.shared.ble.BeltLinkProfile`.
- Add `val beltProfile: BeltLinkProfile` after `val notificationId: Int`.

In `RunningSession.kt`, replace

```kotlin
        belt = BeltLink(context, BeltInputs(inputs), onBeltFound = ::rememberBelt).also { it.start(savedAddress) }
```

with

```kotlin
        belt = BeltLink(context, BeltInputs(inputs), onBeltFound = ::rememberBelt, profile = host.beltProfile).also { it.start(savedAddress) }
```

In `wear-app/.../wear/session/WearSession.kt`:
- Add the imports `io.github.santiquiroz.blindside.shared.ble.BeltLinkProfile` and `io.github.santiquiroz.blindside.shared.ble.BeltRole`.
- Add `override val beltProfile: BeltLinkProfile = BeltLinkProfile(BeltRole.WATCH, activatesSession = true)` after the `notificationId` line.

- [ ] **Step 8: Run the verification command and the test-count one-liner**

Expected: `BUILD SUCCESSFUL`; `android-shared 215`, `wear-app 39`.

- [ ] **Step 9: Device checklist item**

In `watch/wear-app/README.md`, append at the end of `## Lista de verificación en el reloj`:

```markdown

**Fase 2: reloj y celular**
20. [ ] **Rol del enlace:** al conectar, `adb logcat -s BeltGatt` muestra `WriteCommand(command=SetRole(role=WATCH)) -> status 0` después de `ReadInfo` y antes de `EnableStreamNotify`. Con el firmware del MVP, que ignora `06`, el radar recibe datos igual que antes.
```

- [ ] **Step 10: Commit**

```bash
cd C:/personal/blindside
git add watch/android-shared watch/wear-app
git commit -m "feat: comandos del cinturón y perfil de enlace compartidos; el reloj envía SET_ROLE 0 al conectar"
```

---

### Task 10: Ajustes → "Emparejar celular" sends `05 OPEN_PAIRING_WINDOW`

**Files:**
- Modify: `android-shared/.../shared/ble/BeltCommands.kt`
- Create: `android-shared/.../shared/session/PhonePairing.kt`
- Modify: `android-shared/.../shared/session/SessionStore.kt`, `InputAdapters.kt`, `RunningSession.kt`, `SessionActions.kt`, `SessionCommands.kt`, `SessionService.kt`
- Create: `wear-app/.../wear/ui/PhonePairingClock.kt`
- Modify: `wear-app/.../wear/ui/Labels.kt`, `ui/SettingsScreen.kt`, `ui/BlindsideApp.kt`
- Test: `android-shared/src/test/.../shared/ble/BeltCommandsTest.kt`, `session/PhonePairingTest.kt` (new), `session/SessionStoreTest.kt`, `session/InputAdaptersTest.kt`
- Test: `wear-app/src/test/.../wear/ui/PairPhoneLabelsTest.kt` (new)
- Modify: `watch/wear-app/README.md`

**Interfaces:**
- Consumes: `BeltCommand`, `commandBytes`, `CommandResult`, `BeltListener.onCommandWritten` and `BeltLink.send` (Task 9).
- Produces:
  - `BeltCommand.OpenPairingWindow` (`[0x05]`).
  - `enum class PhonePairing { IDLE, REQUESTED, DELIVERED, REFUSED, NO_LINK }` and `const val PAIRING_WINDOW_MS = 60_000L`.
  - `pairingAfterRequest(queued: Boolean)`, `pairingAfterWrite(delivered: Boolean)`, `recordPairingWrite(session, delivered, nowNanos): SessionUiState`, `pairingStateAt(state, deliveredAtMs: Long?, nowMs: Long): PhonePairing` and `canOpenPairingWindow(session: SessionUiState): Boolean`.
  - `SessionUiState.phonePairing: PhonePairing` (default `IDLE`) and `SessionUiState.phonePairingAtMs: Long?`, the elapsed-realtime milliseconds of the delivered write.
  - `RunningSession.openPairingWindow(): Boolean`, `SessionActions.ACTION_OPEN_PAIRING` and `SessionCommands.openPairing(context)`.
  - In `wear-app`: `PAIR_PHONE_LABEL`, `phonePairingLabel(state: PhonePairing): String` and `@Composable rememberPhonePairing(session: SessionUiState): PhonePairing`.

- [ ] **Step 1: Write the failing tests**

`BeltCommandsTest.kt`, add:

```kotlin
    @Test
    fun `open pairing window is command five alone`() {
        assertArrayEquals(byteArrayOf(0x05), commandBytes(BeltCommand.OpenPairingWindow))
    }
```

Create `android-shared/src/test/.../shared/session/PhonePairingTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.session

import io.github.santiquiroz.blindside.shared.ble.BleStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhonePairingTest {
    private val deliveredAtMs = 1_000_000L

    @Test
    fun `a request waits for the belt only when the write was queued`() {
        assertEquals(PhonePairing.REQUESTED, pairingAfterRequest(queued = true))
        assertEquals(PhonePairing.NO_LINK, pairingAfterRequest(queued = false))
    }

    @Test
    fun `the write result says delivered or refused, never open`() {
        assertEquals(PhonePairing.DELIVERED, pairingAfterWrite(delivered = true))
        assertEquals(PhonePairing.REFUSED, pairingAfterWrite(delivered = false))
    }

    @Test
    fun `the pairing write records when it was delivered`() {
        val recorded = recordPairingWrite(SessionUiState(running = true), delivered = true, nowNanos = 5_000_000_000L)
        assertEquals(PhonePairing.DELIVERED, recorded.phonePairing)
        assertEquals(5_000L, recorded.phonePairingAtMs)
        assertTrue(recorded.running)
    }

    @Test
    fun `a delivered request reads as idle once the 60 s window is over`() {
        assertEquals(PhonePairing.DELIVERED, pairingStateAt(PhonePairing.DELIVERED, deliveredAtMs, deliveredAtMs + 59_999L))
        assertEquals(PhonePairing.IDLE, pairingStateAt(PhonePairing.DELIVERED, deliveredAtMs, deliveredAtMs + PAIRING_WINDOW_MS))
        assertEquals(PhonePairing.IDLE, pairingStateAt(PhonePairing.DELIVERED, null, deliveredAtMs))
        assertEquals(PhonePairing.REFUSED, pairingStateAt(PhonePairing.REFUSED, deliveredAtMs, deliveredAtMs + PAIRING_WINDOW_MS))
    }

    @Test
    fun `only a streaming belt game can open the window`() {
        val streaming = SessionUiState(running = true, source = SessionSource.BELT, ble = BleStatus.STREAMING)
        assertTrue(canOpenPairingWindow(streaming))
        assertFalse(canOpenPairingWindow(streaming.copy(source = SessionSource.DEMO)))
        assertFalse(canOpenPairingWindow(streaming.copy(ble = BleStatus.RECONNECTING)))
        assertFalse(canOpenPairingWindow(SessionUiState()))
    }
}
```

`SessionStoreTest.kt`, add:

```kotlin
    @Test
    fun `a new session forgets the last pairing request`() {
        val previous = SessionUiState(phonePairing = PhonePairing.DELIVERED, phonePairingAtMs = 7L)
        val started = startedState(previous, SessionSource.BELT)
        assertEquals(PhonePairing.IDLE, started.phonePairing)
        assertNull(started.phonePairingAtMs)
    }
```

(The file already imports `assertNull`.)

`InputAdaptersTest.kt`, add the imports `io.github.santiquiroz.blindside.shared.ble.BeltCommand`, `io.github.santiquiroz.blindside.shared.ble.BeltRole` and `io.github.santiquiroz.blindside.shared.ble.CommandResult`, and:

```kotlin
    @Test
    fun `only the pairing write result reaches the session store`() {
        SessionStore.update { SessionUiState() }
        val belt = BeltInputs(inputs)
        belt.onCommandWritten(CommandResult(BeltCommand.SetRole(BeltRole.WATCH), delivered = true), 1_000_000L)
        assertEquals(PhonePairing.IDLE, SessionStore.state.value.phonePairing)
        belt.onCommandWritten(CommandResult(BeltCommand.OpenPairingWindow, delivered = true), 2_000_000L)
        assertEquals(PhonePairing.DELIVERED, SessionStore.state.value.phonePairing)
        assertEquals(2L, SessionStore.state.value.phonePairingAtMs)
        belt.onCommandWritten(CommandResult(BeltCommand.OpenPairingWindow, delivered = false), 3_000_000L)
        assertEquals(PhonePairing.REFUSED, SessionStore.state.value.phonePairing)
        SessionStore.update { SessionUiState() }
    }
```

Create `wear-app/src/test/.../wear/ui/PairPhoneLabelsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui

import io.github.santiquiroz.blindside.shared.session.PhonePairing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PairPhoneLabelsTest {
    @Test
    fun `every pairing state has its own short label and none claims the window is open`() {
        val labels = PhonePairing.entries.map(::phonePairingLabel)
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(labels.all { it.isNotBlank() && it.length <= 26 })
        assertFalse(labels.any { it.contains("Abierta") })
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest :wear-app:testDebugUnitTest --console=plain`
Expected: FAIL with `Unresolved reference 'OpenPairingWindow'`, `'PhonePairing'`.

- [ ] **Step 3: The command**

`BeltCommands.kt`:
- Add `data object OpenPairingWindow : BeltCommand` as the last member of `sealed interface BeltCommand`.
- Add `private const val CMD_OPEN_PAIRING_WINDOW: Byte = 0x05` after `CMD_SET_ROLE`.
- In `commandBytes`, add the branch `BeltCommand.OpenPairingWindow -> byteArrayOf(CMD_OPEN_PAIRING_WINDOW)`.

- [ ] **Step 4: Session state, service action and command**

Create `android-shared/.../shared/session/PhonePairing.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.session

import io.github.santiquiroz.blindside.shared.ble.BleStatus

enum class PhonePairing { IDLE, REQUESTED, DELIVERED, REFUSED, NO_LINK }

// Spec §2: the belt keeps the window open 60 s (or until the first bond), whoever asked for it.
const val PAIRING_WINDOW_MS = 60_000L
private const val NANOS_PER_MS = 1_000_000L

fun pairingAfterRequest(queued: Boolean): PhonePairing = if (queued) PhonePairing.REQUESTED else PhonePairing.NO_LINK

// The belt answers every control write at the ATT level, so success only means the request arrived (Deviation D8).
fun pairingAfterWrite(delivered: Boolean): PhonePairing = if (delivered) PhonePairing.DELIVERED else PhonePairing.REFUSED

fun recordPairingWrite(session: SessionUiState, delivered: Boolean, nowNanos: Long): SessionUiState =
    session.copy(phonePairing = pairingAfterWrite(delivered), phonePairingAtMs = nowNanos / NANOS_PER_MS)

fun pairingStateAt(state: PhonePairing, deliveredAtMs: Long?, nowMs: Long): PhonePairing =
    if (state == PhonePairing.DELIVERED && isWindowOver(deliveredAtMs, nowMs)) PhonePairing.IDLE else state

// Spec §2: the belt accepts 05 only from a trusted link, so the window can be asked for only while the belt streams to us.
fun canOpenPairingWindow(session: SessionUiState): Boolean =
    session.source == SessionSource.BELT && session.ble == BleStatus.STREAMING

private fun isWindowOver(deliveredAtMs: Long?, nowMs: Long): Boolean =
    deliveredAtMs == null || nowMs - deliveredAtMs >= PAIRING_WINDOW_MS
```

`SessionStore.kt`: in `data class SessionUiState`, add after `val dndMaySilenceAlerts: Boolean = false,`:

```kotlin
    val phonePairing: PhonePairing = PhonePairing.IDLE,
    val phonePairingAtMs: Long? = null,
```

`InputAdapters.kt`:
- Add the imports `io.github.santiquiroz.blindside.shared.ble.BeltCommand` and `io.github.santiquiroz.blindside.shared.ble.CommandResult`.
- In `class BeltInputs`, add:

```kotlin
    override fun onCommandWritten(result: CommandResult, nowNanos: Long) {
        if (result.command == BeltCommand.OpenPairingWindow) SessionStore.update { recordPairingWrite(it, result.delivered, nowNanos) }
    }
```

`RunningSession.kt`:
- Add the import `io.github.santiquiroz.blindside.shared.ble.BeltCommand`.
- Add after `fun retryLink() { ... }`:

```kotlin
    fun openPairingWindow(): Boolean = belt?.send(BeltCommand.OpenPairingWindow) ?: false
```

`SessionActions.kt`, add:

```kotlin
    const val ACTION_OPEN_PAIRING = "io.github.santiquiroz.blindside.action.OPEN_PAIRING"
```

`SessionCommands.kt`, add after `retryLink`:

```kotlin
    fun openPairing(context: Context) {
        context.startService(serviceIntent(context, SessionActions.ACTION_OPEN_PAIRING))
    }
```

`SessionService.kt`:
- In `onStartCommand`'s `when`, add the branch `SessionActions.ACTION_OPEN_PAIRING -> requestPairingWindow()` before `else -> stopIfIdle()`.
- Add the method:

```kotlin
    private fun requestPairingWindow() {
        val queued = session?.openPairingWindow() ?: false
        SessionStore.update { it.copy(phonePairing = pairingAfterRequest(queued)) }
        stopIfIdle()
    }
```

- [ ] **Step 5: The Ajustes chip**

`wear-app/.../wear/ui/Labels.kt`:
- Add the import `import io.github.santiquiroz.blindside.shared.session.PhonePairing`.
- Add at the end:

```kotlin
const val PAIR_PHONE_LABEL = "Emparejar celular"

fun phonePairingLabel(state: PhonePairing): String = when (state) {
    PhonePairing.IDLE -> "Abre la ventana 60 s"
    PhonePairing.REQUESTED -> "Enviando al cinturón…"
    PhonePairing.DELIVERED -> "Pedida al cinturón (60 s)"
    PhonePairing.REFUSED -> "El cinturón no respondió"
    PhonePairing.NO_LINK -> "Primero inicia el radar"
}
```

Create `wear-app/.../wear/ui/PhonePairingClock.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import io.github.santiquiroz.blindside.shared.session.PhonePairing
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.session.pairingStateAt
import kotlinx.coroutines.delay

private const val PAIRING_TICK_MS = 1_000L

// The belt closes the window on its own after 60 s, so the chip re-reads the clock instead of trusting the stored state.
@Composable
fun rememberPhonePairing(session: SessionUiState): PhonePairing {
    val state by produceState(session.phonePairing, session.phonePairing, session.phonePairingAtMs) {
        while (true) {
            value = pairingStateAt(session.phonePairing, session.phonePairingAtMs, SystemClock.elapsedRealtime())
            delay(PAIRING_TICK_MS)
        }
    }
    return state
}
```

`wear-app/.../wear/ui/SettingsScreen.kt`:
- Add the import `import io.github.santiquiroz.blindside.shared.session.PhonePairing`.
- Replace the signature

```kotlin
fun SettingsScreen(
    settings: AppSettings,
    onUpdate: (SettingsTransform) -> Unit,
    onNavigate: (String) -> Unit,
    onStartDemo: (() -> Unit)?,
) {
```

with

```kotlin
fun SettingsScreen(
    settings: AppSettings,
    onUpdate: (SettingsTransform) -> Unit,
    onNavigate: (String) -> Unit,
    onStartDemo: (() -> Unit)?,
    phonePairing: PhonePairing,
    onPairPhone: () -> Unit,
) {
```

- After the `item { SettingChip("Cinturón", ...) }` line, add:

```kotlin
        item { SettingChip(PAIR_PHONE_LABEL, phonePairingLabel(phonePairing), onPairPhone) }
```

`wear-app/.../wear/ui/BlindsideApp.kt`:
- Add the import `import io.github.santiquiroz.blindside.wear.session.WearSessionCommands`.
- After `val onToggleEliminated: () -> Unit = ...`, add:

```kotlin
    val onPairPhone: () -> Unit = { WearSessionCommands.openPairing(context) }
```

- Replace the `ROUTE_SETTINGS` destination with:

```kotlin
            composable(ROUTE_SETTINGS) {
                SettingsScreen(settings, update, navigate, startDemo.takeUnless { session.running }, rememberPhonePairing(session), onPairPhone)
            }
```

- [ ] **Step 6: Run the verification command and the test-count one-liner**

Expected: `BUILD SUCCESSFUL`; `android-shared 223`, `wear-app 40`.

- [ ] **Step 7: Device checklist item**

Append to `watch/wear-app/README.md` (under **Fase 2: reloj y celular**):

```markdown
21. [ ] **Emparejar celular:** con el radar conectado, Ajustes → "Emparejar celular" pasa a "Pedida al cinturón (60 s)" y `adb logcat -s BeltGatt` muestra `WriteCommand(command=OpenPairingWindow) -> status 0`. Al minuto el chip vuelve a "Abre la ventana 60 s". Con el firmware de fase 2, la línea `diag` del cinturón muestra la ventana abierta 60 s; con el del MVP no se abre nada aunque el chip diga "Pedida". Sin partida, el chip dice "Primero inicia el radar", no se envía nada y no queda ninguna notificación.
```

- [ ] **Step 8: Commit**

```bash
cd C:/personal/blindside
git add watch/android-shared watch/wear-app
git commit -m "feat: Ajustes → Emparejar celular pide al cinturón la ventana de emparejamiento (05)"
```

---

### Task 11: Bridge contract for recordings (list and download)

**Files:**
- Create: `android-shared/.../shared/bridge/BridgePaths.kt`, `bridge/JsonFields.kt`, `bridge/RecordingListCodec.kt`, `bridge/RecordingChannel.kt`
- Create: `android-shared/.../shared/recording/RecordingCatalog.kt`
- Modify: `android-shared/.../shared/recording/RecordingHeader.kt`, `session/SessionStore.kt`
- Create: `wear-app/.../wear/bridge/RecordingServing.kt`
- Test: `android-shared/src/test/.../shared/bridge/BridgePathsTest.kt`, `bridge/RecordingListCodecTest.kt`, `bridge/RecordingChannelTest.kt`, `recording/RecordingCatalogTest.kt`, and `session/SessionStoreTest.kt` (add)
- Test: `wear-app/src/test/.../wear/bridge/RecordingServingTest.kt`

**Interfaces:**
- Consumes: `recordingFileName` and `FILE_STAMP` in `RecordingHeader.kt`, `SessionUiState`, and `MiniJson` from `radar-core` (`parseOrNull`, `quote`, `obj`, `array`).
- Produces:
  - Paths and keys: `RECORDINGS_LIST_PATH`, `RECORDINGS_GET_PATH`, `SETTINGS_PATH`, `STATUS_PATH`, `OPEN_PAIRING_PATH`, `STATUS_PERIOD_MS = 5_000L`, `DATA_JSON_KEY = "json"` and `RECORDING_REFUSED_CODE = 1`.
  - `internal fun longField(fields: Map<*, *>, key: String): Long?` in `shared.bridge`.
  - Recordings: `data class RecordingFile(name: String, bytes: Long, lastModifiedMs: Long)`, `data class RecordingEntry(name: String, bytes: Long, startEpochMs: Long)`, `isRecordingFileName(name: String): Boolean`, `recordingStartFromName(name: String, zone: ZoneId): Long?` and `shareableRecordings(files: List<RecordingFile>, activeName: String?, zone: ZoneId): List<RecordingEntry>`.
  - Codec: `encodeRecordingList(entries): String`, `decodeRecordingList(json): List<RecordingEntry>`, `recordingChannelPath(name): String`, `recordingNameFromChannelPath(path): String?` and `downloadWasServed(byteCount: Long, appErrorCode: Int): Boolean` (for the phone, Cross-plan contract item 4).
  - `activeRecordingName(session: SessionUiState): String?`.
  - In `wear-app`: `recordingFilesIn(dir: File): List<RecordingFile>` and `servedRecording(dir: File, channelPath: String, activeName: String?): File?`.

- [ ] **Step 1: Write the failing shared tests**

`android-shared/src/test/.../shared/bridge/BridgePathsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BridgePathsTest {
    @Test
    fun `paths, keys and codes match the bridge contract`() {
        assertEquals("/recordings/list", RECORDINGS_LIST_PATH)
        assertEquals("/recordings/get", RECORDINGS_GET_PATH)
        assertEquals("/settings", SETTINGS_PATH)
        assertEquals("/status", STATUS_PATH)
        assertEquals("/belt/open-pairing", OPEN_PAIRING_PATH)
        assertEquals(5_000L, STATUS_PERIOD_MS)
        assertEquals("json", DATA_JSON_KEY)
        assertEquals(1, RECORDING_REFUSED_CODE)
    }
}
```

`android-shared/src/test/.../shared/recording/RecordingCatalogTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.recording

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId

class RecordingCatalogTest {
    private val zone = ZoneId.of("America/Bogota")
    private val startMs = 1_727_790_153_000L

    @Test
    fun `the start comes back from the file name`() {
        assertEquals(startMs, recordingStartFromName(recordingFileName(startMs, zone, "BELT"), zone))
    }

    @Test
    fun `only recording names are recognised`() {
        assertTrue(isRecordingFileName("blindside-belt-20261001-142233.bsrec"))
        assertTrue(isRecordingFileName("blindside-demo-20261001-142233.bsrec"))
        assertFalse(isRecordingFileName("../blindside-belt-20261001-142233.bsrec"))
        assertFalse(isRecordingFileName("blindside-belt-20261001-142233.bsrec.tmp"))
        assertFalse(isRecordingFileName("notes.txt"))
        assertNull(recordingStartFromName("notes.txt", zone))
    }

    @Test
    fun `an impossible stamp falls back to the file time`() {
        val odd = "blindside-belt-20261399-999999.bsrec"
        assertEquals(listOf(RecordingEntry(odd, 10L, 42L)), shareableRecordings(listOf(RecordingFile(odd, 10L, 42L)), null, zone))
    }

    @Test
    fun `the list skips the active recording and other files, newest first`() {
        val older = recordingFileName(startMs, zone, "BELT")
        val newer = recordingFileName(startMs + 60_000L, zone, "DEMO")
        val active = recordingFileName(startMs + 120_000L, zone, "BELT")
        val files = listOf(
            RecordingFile(older, 100L, 0L),
            RecordingFile("notes.txt", 5L, 0L),
            RecordingFile(newer, 200L, 0L),
            RecordingFile(active, 300L, 0L),
        )
        val entries = shareableRecordings(files, activeName = active, zone = zone)
        assertEquals(listOf(newer, older), entries.map { it.name })
        assertEquals(listOf(startMs + 60_000L, startMs), entries.map { it.startEpochMs })
    }
}
```

`android-shared/src/test/.../shared/bridge/RecordingListCodecTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.bridge

import io.github.santiquiroz.blindside.shared.recording.RecordingEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RecordingListCodecTest {
    private val entries = listOf(
        RecordingEntry("blindside-belt-20261001-142233.bsrec", 53_000_000L, 1_727_790_153_000L),
        RecordingEntry("blindside-demo-20261001-150000.bsrec", 1_024L, 1_727_792_400_000L),
    )

    @Test
    fun `a list survives encode and decode`() {
        assertEquals(entries, decodeRecordingList(encodeRecordingList(entries)))
    }

    @Test
    fun `the json uses the spec field names`() {
        assertEquals(
            """[{"nombre":"blindside-belt-20261001-142233.bsrec","bytes":53000000,"inicio":1727790153000}]""",
            encodeRecordingList(entries.take(1)),
        )
    }

    @Test
    fun `malformed or unsafe entries are dropped`() {
        val json = """[{"nombre":"../etc/passwd","bytes":1,"inicio":2},""" +
            """{"nombre":"blindside-belt-20261001-142233.bsrec","bytes":"x","inicio":2},{"bytes":1},7,""" +
            """{"nombre":"blindside-belt-20261001-142233.bsrec","bytes":3,"inicio":4}]"""
        assertEquals(listOf(RecordingEntry("blindside-belt-20261001-142233.bsrec", 3L, 4L)), decodeRecordingList(json))
    }

    @Test
    fun `text that is not a json list decodes to no recordings`() {
        assertEquals(emptyList<RecordingEntry>(), decodeRecordingList("not json"))
        assertEquals(emptyList<RecordingEntry>(), decodeRecordingList("""{"nombre":"x"}"""))
    }
}
```

`android-shared/src/test/.../shared/bridge/RecordingChannelTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecordingChannelTest {
    private val name = "blindside-belt-20261001-142233.bsrec"

    @Test
    fun `a recording name round-trips through the channel path`() {
        assertEquals("/recordings/get/$name", recordingChannelPath(name))
        assertEquals(name, recordingNameFromChannelPath(recordingChannelPath(name)))
    }

    @Test
    fun `paths outside the prefix, nested or traversing are refused`() {
        assertNull(recordingNameFromChannelPath("/recordings/list/$name"))
        assertNull(recordingNameFromChannelPath("/recordings/get/../$name"))
        assertNull(recordingNameFromChannelPath("/recordings/get/x/$name"))
        assertNull(recordingNameFromChannelPath("/recordings/get/"))
        assertNull(recordingNameFromChannelPath("/recordings/get$name"))
    }

    @Test
    fun `a refused or empty download is not a recording`() {
        assertTrue(downloadWasServed(byteCount = 1_024L, appErrorCode = 0))
        assertFalse(downloadWasServed(byteCount = 0L, appErrorCode = 0))
        assertFalse(downloadWasServed(byteCount = 0L, appErrorCode = RECORDING_REFUSED_CODE))
        assertFalse(downloadWasServed(byteCount = 1_024L, appErrorCode = RECORDING_REFUSED_CODE))
    }
}
```

`SessionStoreTest.kt`, add:

```kotlin
    @Test
    fun `only a running session has an active recording`() {
        assertEquals("a.bsrec", activeRecordingName(SessionUiState(running = true, recordingName = "a.bsrec")))
        assertNull(activeRecordingName(SessionUiState(running = false, recordingName = "a.bsrec")))
    }
```

- [ ] **Step 2: Write the failing wear test**

`wear-app/src/test/.../wear/bridge/RecordingServingTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.bridge

import io.github.santiquiroz.blindside.shared.bridge.recordingChannelPath
import io.github.santiquiroz.blindside.shared.recording.RecordingFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RecordingServingTest {
    private val name = "blindside-belt-20261001-142233.bsrec"

    @Test
    fun `an existing recording is served`(@TempDir dir: File) {
        File(dir, name).writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(File(dir, name), servedRecording(dir, recordingChannelPath(name), activeName = null))
    }

    @Test
    fun `the active, missing or unsafe recording is not served`(@TempDir dir: File) {
        File(dir, name).writeBytes(byteArrayOf(1))
        assertNull(servedRecording(dir, recordingChannelPath(name), activeName = name))
        assertNull(servedRecording(dir, recordingChannelPath("blindside-belt-20261001-000000.bsrec"), activeName = null))
        assertNull(servedRecording(dir, "/recordings/get/../$name", activeName = null))
    }

    @Test
    fun `recording files are listed with their size and time`(@TempDir dir: File) {
        val file = File(dir, name).apply { writeBytes(ByteArray(5)) }
        File(dir, "sub").mkdir()
        assertEquals(listOf(RecordingFile(name, 5L, file.lastModified())), recordingFilesIn(dir))
    }
}
```

- [ ] **Step 3: Run them and watch them fail**

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest :wear-app:testDebugUnitTest --console=plain`
Expected: FAIL with `Unresolved reference 'RECORDINGS_LIST_PATH'`, `'shareableRecordings'`, `'servedRecording'`.

- [ ] **Step 4: Implement the shared contract**

`android-shared/.../shared/bridge/BridgePaths.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.bridge

const val RECORDINGS_LIST_PATH = "/recordings/list"
const val RECORDINGS_GET_PATH = "/recordings/get"
const val SETTINGS_PATH = "/settings"
const val STATUS_PATH = "/status"
const val OPEN_PAIRING_PATH = "/belt/open-pairing"
const val STATUS_PERIOD_MS = 5_000L

// /settings and /status travel as this string key of a DataMap, never as raw item bytes.
const val DATA_JSON_KEY = "json"

// A channel the watch refuses closes with this app code, so the phone never mistakes a refusal for a finished stream.
const val RECORDING_REFUSED_CODE = 1
```

`android-shared/.../shared/bridge/JsonFields.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.bridge

// MiniJson reads every number as Double; epoch milliseconds stay exact below 2^53.
internal fun longField(fields: Map<*, *>, key: String): Long? = (fields[key] as? Double)?.toLong()
```

In `android-shared/.../shared/recording/RecordingHeader.kt`:
- Add the import `import java.time.LocalDateTime`.
- Add after `fun recordingFileName(...)`:

```kotlin
private val RECORDING_NAME = Regex("blindside-[a-z]+-(\\d{8}-\\d{6})\\.bsrec")

fun isRecordingFileName(name: String): Boolean = RECORDING_NAME.matches(name)

fun recordingStartFromName(name: String, zone: ZoneId): Long? {
    val stamp = RECORDING_NAME.matchEntire(name)?.groupValues?.get(1) ?: return null
    return runCatching { LocalDateTime.parse(stamp, FILE_STAMP).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
}
```

`android-shared/.../shared/recording/RecordingCatalog.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.recording

import java.time.ZoneId

data class RecordingFile(val name: String, val bytes: Long, val lastModifiedMs: Long)

data class RecordingEntry(val name: String, val bytes: Long, val startEpochMs: Long)

fun shareableRecordings(files: List<RecordingFile>, activeName: String?, zone: ZoneId): List<RecordingEntry> =
    files.filter { isShareable(it, activeName) }.map { entryFor(it, zone) }.sortedByDescending { it.startEpochMs }

// The file being written has no header end yet; offering it would hand the phone a truncated recording.
private fun isShareable(file: RecordingFile, activeName: String?): Boolean =
    isRecordingFileName(file.name) && file.name != activeName

private fun entryFor(file: RecordingFile, zone: ZoneId): RecordingEntry =
    RecordingEntry(file.name, file.bytes, recordingStartFromName(file.name, zone) ?: file.lastModifiedMs)
```

`android-shared/.../shared/bridge/RecordingListCodec.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.bridge

import io.github.santiquiroz.blindside.core.protocol.MiniJson
import io.github.santiquiroz.blindside.shared.recording.RecordingEntry
import io.github.santiquiroz.blindside.shared.recording.isRecordingFileName

fun encodeRecordingList(entries: List<RecordingEntry>): String = MiniJson.array(entries.map(::encodeEntry))

fun decodeRecordingList(json: String): List<RecordingEntry> =
    (MiniJson.parseOrNull(json) as? List<*>).orEmpty().mapNotNull(::decodeEntry)

private fun encodeEntry(entry: RecordingEntry): String = MiniJson.obj(
    listOf(
        "nombre" to MiniJson.quote(entry.name),
        "bytes" to entry.bytes.toString(),
        "inicio" to entry.startEpochMs.toString(),
    ),
)

// The name becomes a file name on the phone, so anything that is not a recording name is dropped here.
private fun decodeEntry(item: Any?): RecordingEntry? {
    val fields = item as? Map<*, *> ?: return null
    val name = (fields["nombre"] as? String)?.takeIf(::isRecordingFileName) ?: return null
    val bytes = longField(fields, "bytes") ?: return null
    val start = longField(fields, "inicio") ?: return null
    return RecordingEntry(name, bytes, start)
}
```

`android-shared/.../shared/bridge/RecordingChannel.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.bridge

import io.github.santiquiroz.blindside.shared.recording.isRecordingFileName

fun recordingChannelPath(name: String): String = "$RECORDINGS_GET_PATH/$name"

fun recordingNameFromChannelPath(path: String): String? {
    val prefix = "$RECORDINGS_GET_PATH/"
    if (!path.startsWith(prefix)) return null
    return path.removePrefix(prefix).takeIf(::isRecordingFileName)
}

// Every .bsrec starts with a header, so zero bytes is a refusal even when the close code was lost.
fun downloadWasServed(byteCount: Long, appErrorCode: Int): Boolean =
    byteCount > 0L && appErrorCode != RECORDING_REFUSED_CODE
```

In `android-shared/.../shared/session/SessionStore.kt`, add at the end:

```kotlin
fun activeRecordingName(session: SessionUiState): String? = session.recordingName.takeIf { session.running }
```

- [ ] **Step 5: Implement the wear file helpers**

`wear-app/.../wear/bridge/RecordingServing.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.bridge

import io.github.santiquiroz.blindside.shared.bridge.recordingNameFromChannelPath
import io.github.santiquiroz.blindside.shared.recording.RecordingFile
import java.io.File

fun recordingFilesIn(dir: File): List<RecordingFile> =
    dir.listFiles().orEmpty().filter(File::isFile).map { RecordingFile(it.name, it.length(), it.lastModified()) }

fun servedRecording(dir: File, channelPath: String, activeName: String?): File? {
    val name = recordingNameFromChannelPath(channelPath)?.takeUnless { it == activeName } ?: return null
    return File(dir, name).takeIf(File::isFile)
}
```

- [ ] **Step 6: Run the verification command and the test-count one-liner**

Expected: `BUILD SUCCESSFUL`; `android-shared 236`, `wear-app 43`.

- [ ] **Step 7: Commit**

```bash
cd C:/personal/blindside
git add watch/android-shared watch/wear-app
git commit -m "feat: contrato del puente para listar y descargar grabaciones del reloj"
```

---

### Task 12: Bridge contract for shared settings, watch status and the open-pairing reply

**Files:**
- Modify: `android-shared/.../shared/settings/AppSettings.kt`, `settings/SettingsPreferences.kt`, `settings/SettingsRepository.kt`
- Create: `android-shared/.../shared/settings/SharedSettings.kt`
- Create: `android-shared/.../shared/bridge/SharedSettingsCodec.kt`, `bridge/WatchStatus.kt`, `bridge/OpenPairingReply.kt`
- Modify: `android-shared/.../shared/bridge/JsonFields.kt`
- Test: `android-shared/src/test/.../shared/settings/SharedSettingsTest.kt` (new), `settings/SettingsRepositoryTest.kt`, `settings/SettingsPreferencesTest.kt`, `bridge/SharedSettingsCodecTest.kt` (new), `bridge/WatchStatusTest.kt` (new), `bridge/OpenPairingReplyTest.kt` (new), `bridge/BridgeContractTest.kt` (new, the literal-payload fixture plan 06 keeps)

**Interfaces:**
- Consumes: `longField`, `encodeRecordingList`, `RecordingEntry`, `SETTINGS_PATH` and `STATUS_PATH` (Task 11); `canOpenPairingWindow` (Task 10); `enumOrDefault`, `DEFAULT_RADARS` and `YAW_LIMIT_DEG` (`shared.settings`); `stoppedState` (`shared.session`).
- Produces:
  - `AppSettings.sharedUpdatedMs: Long = 0L`, persisted under the key `shared_updated_ms`.
  - `data class SharedSettings(handedness: Handedness, radars: List<RadarSettings>, posture: WatchPosture, updatedMs: Long)`.
  - `sharedSettingsOf(settings: AppSettings): SharedSettings`, `AppSettings.withShared(shared): AppSettings`, `shouldAdopt(local: AppSettings, remote: SharedSettings): Boolean`, `AppSettings.adoptingNewer(remote: SharedSettings): AppSettings`, `stampSharedEdit(before: AppSettings, after: AppSettings, nowMs: Long): AppSettings` and `isStamped(shared: SharedSettings): Boolean`. A local edit is stamped `max(nowMs, previous stamp + 1)`, so the stamp never goes backwards.
  - `SettingsRepository(store, onWriteFailed, clock: () -> Long = System::currentTimeMillis)`. `update` now stamps shared edits.
  - `internal fun wholeField(fields: Map<*, *>, key: String): Long?` in `JsonFields.kt` (`null` for a fractional number).
  - Codecs: `encodeSharedSettings(shared): String` and `decodeSharedSettings(json): SharedSettings?`. Decoding returns `null` for anything short of a complete, known payload; only a yaw beyond ±90° is repaired (clamped).
  - Status: `data class WatchStatus(sessionActive: Boolean, ble: BleStatus, linkUp: Boolean, radars: List<SensorStatus>, updatedMs: Long)`, `watchStatusOf(session: SessionUiState, nowMs: Long): WatchStatus`, `encodeWatchStatus(status): String` and `decodeWatchStatus(json): WatchStatus?`.
  - Pairing reply: `enum class OpenPairingReply { REQUESTED, NO_LINK }`, `openPairingReplyFor(session): OpenPairingReply`, `encodeOpenPairingReply(reply): ByteArray` and `decodeOpenPairingReply(bytes): OpenPairingReply`.
  - The JSON shapes (Deviation D7), pinned by `BridgeContractTest`:
    - `/settings`: `{"handedness":"RIGHT","posture":"NORMAL","updated_ms":<long>,"radars":[{"id":0,"yaw_deg":<double|null>,"flip_x":<bool>,"speed_sign":<1|-1>},…]}`. The decoder rejects:
      - an unknown or missing `handedness` or `posture`;
      - a missing `updated_ms`;
      - a radar without the `yaw_deg` key, with a non-boolean `flip_x` or with a `speed_sign` other than ±1;
      - a fractional `id`, or ids that are not exactly the two radars.
    - `/status`: `{"session_active":<bool>,"link":"<BleStatus>","link_up":<bool>,"radars":[{"id":0,"alive":<bool>},…],"updated_ms":<long>}`. It is display-only, so an unknown `link` reads as `IDLE` instead of hiding the status.
    - Pairing reply bytes: the UTF-8 name of the `OpenPairingReply`.

- [ ] **Step 1: Write the failing tests**

`android-shared/src/test/.../shared/settings/SharedSettingsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.settings

import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SharedSettingsTest {
    private val base = AppSettings(sharedUpdatedMs = 100L)

    @Test
    fun `a local edit of a shared field is stamped now`() {
        assertEquals(500L, stampSharedEdit(base, base.copy(posture = WatchPosture.TACTICAL_RIGHT), nowMs = 500L).sharedUpdatedMs)
        assertEquals(500L, stampSharedEdit(base, base.withFlipXToggled(RADAR_A), nowMs = 500L).sharedUpdatedMs)
    }

    @Test
    fun `a local edit never moves the stamp backwards`() {
        val adoptedFromAClockAhead = AppSettings(sharedUpdatedMs = 9_000L)
        val edited = adoptedFromAClockAhead.copy(posture = WatchPosture.TACTICAL_LEFT)
        assertEquals(9_001L, stampSharedEdit(adoptedFromAClockAhead, edited, nowMs = 5_000L).sharedUpdatedMs)
    }

    @Test
    fun `a non shared edit keeps the stamp`() {
        val edited = base.copy(screenMode = ScreenMode.VISTA, eliminated = true, beltAddress = "AA:BB")
        assertEquals(100L, stampSharedEdit(base, edited, nowMs = 500L).sharedUpdatedMs)
    }

    @Test
    fun `an adoption keeps the remote stamp`() {
        val remote = SharedSettings(Handedness.LEFT, DEFAULT_RADARS, WatchPosture.NORMAL, updatedMs = 300L)
        assertEquals(300L, stampSharedEdit(base, base.adoptingNewer(remote), nowMs = 500L).sharedUpdatedMs)
    }

    @Test
    fun `only a strictly newer remote is adopted`() {
        val older = SharedSettings(Handedness.LEFT, DEFAULT_RADARS, WatchPosture.NORMAL, updatedMs = 99L)
        assertEquals(base, base.adoptingNewer(older))
        assertEquals(base, base.adoptingNewer(older.copy(updatedMs = 100L)))
        assertEquals(Handedness.LEFT, base.adoptingNewer(older.copy(updatedMs = 101L)).handedness)
    }

    @Test
    fun `adopting copies hand, mounts, signs and posture only`() {
        val local = AppSettings(screenMode = ScreenMode.VISTA, beltAddress = "AA:BB", eliminated = true, sharedUpdatedMs = 1L)
        val radars = listOf(RadarSettings(RADAR_A, -30.0, flipX = true, speedSign = -1), RadarSettings(RADAR_B))
        val remote = SharedSettings(Handedness.SWITCHER, radars, WatchPosture.TACTICAL_LEFT, updatedMs = 2L)
        val expected = local.copy(
            handedness = Handedness.SWITCHER,
            radars = radars,
            posture = WatchPosture.TACTICAL_LEFT,
            sharedUpdatedMs = 2L,
        )
        assertEquals(expected, local.adoptingNewer(remote))
    }

    @Test
    fun `only stamped settings are published`() {
        assertFalse(isStamped(sharedSettingsOf(AppSettings())))
        assertTrue(isStamped(sharedSettingsOf(base)))
    }
}
```

`SettingsRepositoryTest.kt`, add:

```kotlin
    @Test
    fun `shared edits are stamped with the repository clock`(@TempDir dir: File) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") }
        val repository = SettingsRepository(store, onWriteFailed = {}, clock = { 1_234L })
        val read = runBlocking {
            repository.update { it.copy(posture = WatchPosture.TACTICAL_LEFT) }
            repository.current()
        }
        scope.cancel()
        assertEquals(1_234L, read.sharedUpdatedMs)
    }
```

`SettingsPreferencesTest.kt`: in `every field survives a write and read`, add `sharedUpdatedMs = 1_727_790_153_123L,` to the `AppSettings(...)` after `autoStartRadar = false,`.

`android-shared/src/test/.../shared/bridge/SharedSettingsCodecTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.bridge

import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import io.github.santiquiroz.blindside.shared.settings.RadarSettings
import io.github.santiquiroz.blindside.shared.settings.SharedSettings
import io.github.santiquiroz.blindside.shared.settings.WatchPosture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private const val RADAR_A_JSON = """{"id":0,"yaw_deg":-35.0,"flip_x":true,"speed_sign":1}"""
private const val RADAR_B_JSON = """{"id":1,"yaw_deg":null,"flip_x":false,"speed_sign":-1}"""

class SharedSettingsCodecTest {
    private val shared = SharedSettings(
        handedness = Handedness.LEFT,
        radars = listOf(
            RadarSettings(RADAR_A, -35.0, flipX = true, speedSign = -1),
            RadarSettings(RADAR_B, null, flipX = false, speedSign = 1),
        ),
        posture = WatchPosture.TACTICAL_RIGHT,
        updatedMs = 1_727_790_153_123L,
    )

    private fun payload(hand: String = "\"LEFT\"", posture: String = "\"NORMAL\"", radarA: String = RADAR_A_JSON): String =
        """{"handedness":$hand,"posture":$posture,"updated_ms":5,"radars":[$radarA,$RADAR_B_JSON]}"""

    @Test
    fun `shared settings survive encode and decode`() {
        assertEquals(shared, decodeSharedSettings(encodeSharedSettings(shared)))
    }

    @Test
    fun `a payload missing a radar, the stamp or json is rejected`() {
        assertNull(decodeSharedSettings(encodeSharedSettings(shared.copy(radars = shared.radars.take(1)))))
        assertNull(decodeSharedSettings("""{"handedness":"LEFT","posture":"NORMAL","radars":[$RADAR_A_JSON,$RADAR_B_JSON]}"""))
        assertNull(decodeSharedSettings("garbage"))
    }

    @Test
    fun `an out of range yaw is clamped`() {
        val decoded = decodeSharedSettings(payload(radarA = """{"id":0,"yaw_deg":500.0,"flip_x":true,"speed_sign":1}"""))!!
        assertEquals(RadarSettings(RADAR_A, 90.0, flipX = true, speedSign = 1), decoded.radars[0])
        assertEquals(RadarSettings(RADAR_B, null, flipX = false, speedSign = -1), decoded.radars[1])
    }

    @Test
    fun `an unknown or missing hand or posture is rejected`() {
        assertNull(decodeSharedSettings(payload(hand = "\"AMBIDEXTROUS\"")))
        assertNull(decodeSharedSettings(payload(hand = "null")))
        assertNull(decodeSharedSettings(payload(posture = "\"UPSIDE_DOWN\"")))
        assertNull(decodeSharedSettings("""{"posture":"NORMAL","updated_ms":5,"radars":[$RADAR_A_JSON,$RADAR_B_JSON]}"""))
    }

    @Test
    fun `a partial or malformed radar is rejected`() {
        listOf(
            """{"id":0}""",
            """{"id":0,"flip_x":true,"speed_sign":1}""",
            """{"id":0,"yaw_deg":"x","flip_x":true,"speed_sign":1}""",
            """{"id":0,"yaw_deg":null,"flip_x":"yes","speed_sign":1}""",
            """{"id":0,"yaw_deg":null,"speed_sign":1}""",
            """{"id":0,"yaw_deg":null,"flip_x":true,"speed_sign":7}""",
            """{"id":0,"yaw_deg":null,"flip_x":true,"speed_sign":0.5}""",
            """{"id":0.5,"yaw_deg":null,"flip_x":true,"speed_sign":1}""",
            RADAR_B_JSON,
        ).forEach { radar -> assertNull(decodeSharedSettings(payload(radarA = radar)), radar) }
    }
}
```

`android-shared/src/test/.../shared/bridge/WatchStatusTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.bridge

import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.session.SessionSource
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.session.stoppedState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WatchStatusTest {
    private val scene = RadarScene(
        blips = emptyList(),
        coverage = emptyList(),
        linkUp = true,
        radars = listOf(SensorStatus(0, true), SensorStatus(1, false)),
        imus = emptyList(),
        motion = MotionState.STILL,
        warnings = emptySet(),
        eliminated = false,
    )
    private val running = SessionUiState(running = true, source = SessionSource.BELT, ble = BleStatus.STREAMING, scene = scene)

    @Test
    fun `the status reflects the running session`() {
        val expected = WatchStatus(sessionActive = true, ble = BleStatus.STREAMING, linkUp = true, radars = scene.radars, updatedMs = 7L)
        assertEquals(expected, watchStatusOf(running, nowMs = 7L))
    }

    @Test
    fun `a stopped session publishes an inactive status`() {
        val status = watchStatusOf(stoppedState(running), nowMs = 8L)
        assertFalse(status.sessionActive)
        assertFalse(status.linkUp)
        assertEquals(emptyList<SensorStatus>(), status.radars)
    }

    @Test
    fun `the status survives encode and decode`() {
        val status = watchStatusOf(running, nowMs = 1_727_790_153_000L)
        assertEquals(status, decodeWatchStatus(encodeWatchStatus(status)))
    }

    @Test
    fun `a malformed status decodes to null`() {
        assertNull(decodeWatchStatus("{}"))
        assertNull(decodeWatchStatus("[1,2]"))
        assertNull(decodeWatchStatus("nope"))
    }
}
```

`android-shared/src/test/.../shared/bridge/OpenPairingReplyTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.bridge

import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.session.SessionSource
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OpenPairingReplyTest {
    @Test
    fun `the reply asks the belt only when its link streams`() {
        val streaming = SessionUiState(running = true, source = SessionSource.BELT, ble = BleStatus.STREAMING)
        assertEquals(OpenPairingReply.REQUESTED, openPairingReplyFor(streaming))
        assertEquals(OpenPairingReply.NO_LINK, openPairingReplyFor(streaming.copy(ble = BleStatus.RECONNECTING)))
        assertEquals(OpenPairingReply.NO_LINK, openPairingReplyFor(SessionUiState()))
    }

    @Test
    fun `replies survive the byte encoding and unknown bytes mean no link`() {
        OpenPairingReply.entries.forEach { assertEquals(it, decodeOpenPairingReply(encodeOpenPairingReply(it))) }
        assertEquals(OpenPairingReply.NO_LINK, decodeOpenPairingReply(byteArrayOf(1, 2, 3)))
    }
}
```

`android-shared/src/test/.../shared/bridge/BridgeContractTest.kt` (the fixture plan 06 keeps; Cross-plan contract item 3):

```kotlin
package io.github.santiquiroz.blindside.shared.bridge

import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.recording.RecordingEntry
import io.github.santiquiroz.blindside.shared.settings.RadarSettings
import io.github.santiquiroz.blindside.shared.settings.SharedSettings
import io.github.santiquiroz.blindside.shared.settings.WatchPosture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// The wire contract between the watch and the phone app (plan 06). Change a literal only together with both apps.
private const val SETTINGS_JSON = """{"handedness":"LEFT","posture":"TACTICAL_RIGHT","updated_ms":1727790153123,"radars":[{"id":0,"yaw_deg":-35.0,"flip_x":true,"speed_sign":-1},{"id":1,"yaw_deg":null,"flip_x":false,"speed_sign":1}]}"""
private const val STATUS_JSON = """{"session_active":true,"link":"STREAMING","link_up":true,"radars":[{"id":0,"alive":true},{"id":1,"alive":false}],"updated_ms":1727790153000}"""
private const val LIST_JSON = """[{"nombre":"blindside-belt-20261001-142233.bsrec","bytes":53000000,"inicio":1727790153000}]"""

class BridgeContractTest {
    private val settings = SharedSettings(
        handedness = Handedness.LEFT,
        radars = listOf(
            RadarSettings(RADAR_A, -35.0, flipX = true, speedSign = -1),
            RadarSettings(RADAR_B, null, flipX = false, speedSign = 1),
        ),
        posture = WatchPosture.TACTICAL_RIGHT,
        updatedMs = 1_727_790_153_123L,
    )
    private val status = WatchStatus(
        sessionActive = true,
        ble = BleStatus.STREAMING,
        linkUp = true,
        radars = listOf(SensorStatus(0, true), SensorStatus(1, false)),
        updatedMs = 1_727_790_153_000L,
    )

    @Test
    fun `the settings payload is pinned`() {
        assertEquals(SETTINGS_JSON, encodeSharedSettings(settings))
        assertEquals(settings, decodeSharedSettings(SETTINGS_JSON))
    }

    @Test
    fun `the status payload is pinned`() {
        assertEquals(STATUS_JSON, encodeWatchStatus(status))
        assertEquals(status, decodeWatchStatus(STATUS_JSON))
    }

    @Test
    fun `the recording list and the pairing reply are pinned`() {
        val entry = RecordingEntry("blindside-belt-20261001-142233.bsrec", 53_000_000L, 1_727_790_153_000L)
        assertEquals(LIST_JSON, encodeRecordingList(listOf(entry)))
        assertEquals(listOf(entry), decodeRecordingList(LIST_JSON))
        assertEquals("REQUESTED", encodeOpenPairingReply(OpenPairingReply.REQUESTED).toString(Charsets.UTF_8))
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest --console=plain`
Expected: FAIL with `No parameter with name 'sharedUpdatedMs'`, `Unresolved reference 'SharedSettings'`.

- [ ] **Step 3: Persist the stamp and stamp shared edits**

`AppSettings.kt`: add `val sharedUpdatedMs: Long = 0L,` as the last field of `data class AppSettings`, after `val autoStartRadar: Boolean = true,`.

`SettingsPreferences.kt`:
- Add `import androidx.datastore.preferences.core.longPreferencesKey`.
- In `Keys`, add `val SHARED_UPDATED_MS = longPreferencesKey("shared_updated_ms")`.
- In `settingsFrom`, add `sharedUpdatedMs = prefs[Keys.SHARED_UPDATED_MS] ?: defaults.sharedUpdatedMs,` after the `autoStartRadar` line.
- In `writeSettings`, add `prefs[Keys.SHARED_UPDATED_MS] = settings.sharedUpdatedMs` after the `AUTO_START_RADAR` line.

Create `android-shared/.../shared/settings/SharedSettings.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.settings

import io.github.santiquiroz.blindside.core.config.Handedness

data class SharedSettings(
    val handedness: Handedness,
    val radars: List<RadarSettings>,
    val posture: WatchPosture,
    val updatedMs: Long,
)

fun sharedSettingsOf(settings: AppSettings): SharedSettings =
    SharedSettings(settings.handedness, settings.radars, settings.posture, settings.sharedUpdatedMs)

fun AppSettings.withShared(shared: SharedSettings): AppSettings =
    copy(handedness = shared.handedness, radars = shared.radars, posture = shared.posture, sharedUpdatedMs = shared.updatedMs)

// Strictly newer only: equal stamps are the echo of our own write coming back from the other device.
fun shouldAdopt(local: AppSettings, remote: SharedSettings): Boolean = remote.updatedMs > local.sharedUpdatedMs

fun AppSettings.adoptingNewer(remote: SharedSettings): AppSettings = if (shouldAdopt(this, remote)) withShared(remote) else this

// A transform that brings its own stamp (an adoption) keeps it; a local edit of a shared field is stamped now.
fun stampSharedEdit(before: AppSettings, after: AppSettings, nowMs: Long): AppSettings = when {
    after.sharedUpdatedMs != before.sharedUpdatedMs -> after
    sharedSettingsOf(after) != sharedSettingsOf(before) -> after.copy(sharedUpdatedMs = nextStamp(before.sharedUpdatedMs, nowMs))
    else -> after
}

fun isStamped(shared: SharedSettings): Boolean = shared.updatedMs > 0L

// An adopted stamp may come from a clock running ahead; our own later edit must still be the newest write on both devices.
private fun nextStamp(previousMs: Long, nowMs: Long): Long = maxOf(nowMs, previousMs + 1)
```

`SettingsRepository.kt`, replace the class with:

```kotlin
class SettingsRepository(
    private val store: DataStore<Preferences>,
    private val onWriteFailed: (IOException) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val settings: Flow<AppSettings> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map(::settingsFrom)

    suspend fun current(): AppSettings = settings.first()

    // DataStore throws IOException (CorruptionException included) on a full or broken disk; callers run without a handler.
    suspend fun update(transform: SettingsTransform): Boolean = try {
        store.edit { prefs -> writeSettings(prefs, edited(settingsFrom(prefs), transform)) }
        true
    } catch (error: IOException) {
        onWriteFailed(error)
        false
    }

    private fun edited(before: AppSettings, transform: SettingsTransform): AppSettings =
        stampSharedEdit(before, transform(before), clock())
}
```

- [ ] **Step 4: The codecs and the reply**

`android-shared/.../shared/bridge/JsonFields.kt`, add at the end:

```kotlin
// A fractional id or sign is malformed, not a number to round.
internal fun wholeField(fields: Map<*, *>, key: String): Long? =
    (fields[key] as? Double)?.takeIf { it % 1.0 == 0.0 }?.toLong()
```

`android-shared/.../shared/bridge/SharedSettingsCodec.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.bridge

import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.protocol.MiniJson
import io.github.santiquiroz.blindside.shared.settings.DEFAULT_RADARS
import io.github.santiquiroz.blindside.shared.settings.RadarSettings
import io.github.santiquiroz.blindside.shared.settings.SharedSettings
import io.github.santiquiroz.blindside.shared.settings.WatchPosture
import io.github.santiquiroz.blindside.shared.settings.YAW_LIMIT_DEG

private const val YAW_KEY = "yaw_deg"
private val SPEED_SIGNS = setOf(1, -1)

fun encodeSharedSettings(shared: SharedSettings): String = MiniJson.obj(
    listOf(
        "handedness" to MiniJson.quote(shared.handedness.name),
        "posture" to MiniJson.quote(shared.posture.name),
        "updated_ms" to shared.updatedMs.toString(),
        "radars" to MiniJson.array(shared.radars.map(::encodeRadar)),
    ),
)

// Anything short of a complete, known payload is refused whole: a guessed default would overwrite hand, mounts, signs or posture.
fun decodeSharedSettings(json: String): SharedSettings? {
    val fields = MiniJson.parseOrNull(json) as? Map<*, *> ?: return null
    val handedness = enumNamed<Handedness>(fields["handedness"]) ?: return null
    val posture = enumNamed<WatchPosture>(fields["posture"]) ?: return null
    val updatedMs = longField(fields, "updated_ms") ?: return null
    val radars = decodeRadars(fields["radars"]) ?: return null
    return SharedSettings(handedness, radars, posture, updatedMs)
}

private fun encodeRadar(radar: RadarSettings): String = MiniJson.obj(
    listOf(
        "id" to radar.radarId.toString(),
        YAW_KEY to (radar.yawDegOverride?.toString() ?: "null"),
        "flip_x" to radar.flipX.toString(),
        "speed_sign" to radar.speedSign.toString(),
    ),
)

private fun decodeRadars(value: Any?): List<RadarSettings>? {
    val items = value as? List<*> ?: return null
    val radars = items.mapNotNull(::decodeRadar)
    if (radars.size != items.size) return null
    return radars.sortedBy { it.radarId }.takeIf(::coversEveryRadar)
}

private fun decodeRadar(item: Any?): RadarSettings? {
    val fields = (item as? Map<*, *>)?.takeIf(::hasValidYaw) ?: return null
    val id = wholeField(fields, "id")?.toInt() ?: return null
    val flipX = fields["flip_x"] as? Boolean ?: return null
    val speedSign = speedSignIn(fields) ?: return null
    return RadarSettings(id, clampedYaw(fields[YAW_KEY] as? Double), flipX, speedSign)
}

// The key must be there: null means "use the geometry's yaw", a missing key means a partial payload.
private fun hasValidYaw(fields: Map<*, *>): Boolean =
    fields.containsKey(YAW_KEY) && fields[YAW_KEY].let { it == null || it is Double }

private fun speedSignIn(fields: Map<*, *>): Int? = wholeField(fields, "speed_sign")?.toInt()?.takeIf { it in SPEED_SIGNS }

private fun clampedYaw(deg: Double?): Double? = deg?.coerceIn(-YAW_LIMIT_DEG, YAW_LIMIT_DEG)

private fun coversEveryRadar(radars: List<RadarSettings>): Boolean =
    radars.map { it.radarId } == DEFAULT_RADARS.map { it.radarId }.sorted()

private inline fun <reified E : Enum<E>> enumNamed(value: Any?): E? = enumValues<E>().firstOrNull { it.name == value }
```

`android-shared/.../shared/bridge/WatchStatus.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.bridge

import io.github.santiquiroz.blindside.core.protocol.MiniJson
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.settings.enumOrDefault

data class WatchStatus(
    val sessionActive: Boolean,
    val ble: BleStatus,
    val linkUp: Boolean,
    val radars: List<SensorStatus>,
    val updatedMs: Long,
)

fun watchStatusOf(session: SessionUiState, nowMs: Long): WatchStatus = WatchStatus(
    sessionActive = session.running,
    ble = session.ble,
    linkUp = session.scene?.linkUp == true,
    radars = session.scene?.radars.orEmpty(),
    updatedMs = nowMs,
)

fun encodeWatchStatus(status: WatchStatus): String = MiniJson.obj(
    listOf(
        "session_active" to status.sessionActive.toString(),
        "link" to MiniJson.quote(status.ble.name),
        "link_up" to status.linkUp.toString(),
        "radars" to MiniJson.array(status.radars.map(::encodeSensor)),
        "updated_ms" to status.updatedMs.toString(),
    ),
)

// Display-only: a link state a newer watch adds reads as IDLE instead of hiding the whole status.
fun decodeWatchStatus(json: String): WatchStatus? {
    val fields = MiniJson.parseOrNull(json) as? Map<*, *> ?: return null
    val updatedMs = longField(fields, "updated_ms") ?: return null
    return WatchStatus(
        sessionActive = fields["session_active"] == true,
        ble = enumOrDefault(fields["link"] as? String, BleStatus.IDLE),
        linkUp = fields["link_up"] == true,
        radars = (fields["radars"] as? List<*>).orEmpty().mapNotNull(::decodeSensor),
        updatedMs = updatedMs,
    )
}

private fun encodeSensor(sensor: SensorStatus): String =
    MiniJson.obj(listOf("id" to sensor.id.toString(), "alive" to sensor.alive.toString()))

private fun decodeSensor(item: Any?): SensorStatus? {
    val fields = item as? Map<*, *> ?: return null
    val id = longField(fields, "id")?.toInt() ?: return null
    return SensorStatus(id, fields["alive"] == true)
}
```

`android-shared/.../shared/bridge/OpenPairingReply.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.bridge

import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.session.canOpenPairingWindow
import io.github.santiquiroz.blindside.shared.settings.enumOrDefault

enum class OpenPairingReply { REQUESTED, NO_LINK }

fun openPairingReplyFor(session: SessionUiState): OpenPairingReply =
    if (canOpenPairingWindow(session)) OpenPairingReply.REQUESTED else OpenPairingReply.NO_LINK

fun encodeOpenPairingReply(reply: OpenPairingReply): ByteArray = reply.name.toByteArray(Charsets.UTF_8)

fun decodeOpenPairingReply(bytes: ByteArray): OpenPairingReply =
    enumOrDefault(bytes.toString(Charsets.UTF_8), OpenPairingReply.NO_LINK)
```

- [ ] **Step 5: Run the verification command and the test-count one-liner**

Expected: `BUILD SUCCESSFUL`; `android-shared 258`, `wear-app 43`.

- [ ] **Step 6: Commit**

```bash
cd C:/personal/blindside
git add watch/android-shared
git commit -m "feat: contrato del puente para ajustes compartidos, estado del reloj y apertura de emparejamiento"
```

---

### Task 13: The watch serves the Wear Data Layer bridge

No new JVM tests here. Every decision this task wires up is already pinned in Tasks 10-12. This task is Android glue, verified by the build, the merged manifest and the device checklist. The DataItem wrapping goes into `android-shared`, so the phone app (plan 06) writes and reads `/settings` and `/status` with the very same three functions.

**Files:**
- Modify: `watch/gradle/libs.versions.toml`, `watch/android-shared/build.gradle.kts`, `watch/wear-app/src/main/AndroidManifest.xml`
- Create: `android-shared/.../shared/bridge/DataLayerJson.kt`
- Create: `wear-app/.../wear/bridge/StatusPublisher.kt`, `bridge/SettingsPublisher.kt`, `bridge/BridgeListenerService.kt`
- Modify: `android-shared/.../shared/session/SessionHost.kt`, `session/SessionService.kt`
- Modify: `wear-app/.../wear/session/WearSession.kt`, `ui/BlindsideApp.kt`
- Modify: `watch/wear-app/README.md`

**Interfaces:**
- Consumes:
  - Task 11: `RECORDINGS_LIST_PATH`, `OPEN_PAIRING_PATH`, `SETTINGS_PATH`, `STATUS_PATH`, `STATUS_PERIOD_MS`, `DATA_JSON_KEY`, `RECORDING_REFUSED_CODE`, `encodeRecordingList`, `shareableRecordings`, `recordingFilesIn`, `servedRecording` and `activeRecordingName`.
  - Task 12: `decodeSharedSettings`, `encodeSharedSettings`, `sharedSettingsOf`, `isStamped`, `adoptingNewer`, `watchStatusOf`, `encodeWatchStatus`, `openPairingReplyFor` and `encodeOpenPairingReply`.
  - Elsewhere: `stoppedState`, `SessionCommands.openPairing` (Task 10) and `recordingsDir(context)`.
- Produces:
  - In `shared.bridge` (`DataLayerJson.kt`): `jsonDataRequest(path: String, json: String): PutDataRequest`, `jsonIn(item: DataItem): String?` and `publishJson(context: Context, path: String, json: String)`.
  - `SessionHost.launchCompanions(context: Context, scope: CoroutineScope): List<Job>`, which defaults to `emptyList()`. `SessionService` cancels these jobs on stop.
  - `class BridgeListenerService : WearableListenerService` (wear). It answers `onRequest` for `/recordings/list` and `/belt/open-pairing`, acts on `onMessageReceived` for `/belt/open-pairing`, serves or refuses `/recordings/get/<name>` channels, and adopts newer `/settings`.
  - `publishWatchStatus(context, clock)` and `publishSharedSettings(context, repository)` (wear).

- [ ] **Step 1: Dependency, in the library so both apps share it**

In `watch/gradle/libs.versions.toml`:
- Under `[versions]`, add `playServicesWearable = "18.2.0"`.
- Under `[libraries]`, add:

```toml
play-services-wearable = { group = "com.google.android.gms", name = "play-services-wearable", version.ref = "playServicesWearable" }
```

In `watch/android-shared/build.gradle.kts`, add inside `dependencies { }` after `api(libs.compose.ui)`:

```kotlin
    api(libs.play.services.wearable)
```

`wear-app` gets it through `implementation(project(":android-shared"))`. Do not add it to `wear-app` again.

- [ ] **Step 2: One DataItem wrapper for both apps**

Create `android-shared/.../shared/bridge/DataLayerJson.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.bridge

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable

private const val TAG = "DataLayer"

fun jsonDataRequest(path: String, json: String): PutDataRequest {
    val request = PutDataMapRequest.create(path)
    request.dataMap.putString(DATA_JSON_KEY, json)
    return request.asPutDataRequest().setUrgent()
}

// An item that is not a DataMap (an older build, another writer) is treated like any malformed payload: ignored.
fun jsonIn(item: DataItem): String? =
    runCatching { DataMapItem.fromDataItem(item).dataMap.getString(DATA_JSON_KEY) }.getOrNull()

// Spec §5: the bridge is an extra, so a missing peer or a failed write is only logged.
fun publishJson(context: Context, path: String, json: String) {
    Wearable.getDataClient(context).putDataItem(jsonDataRequest(path, json))
        .addOnFailureListener { Log.w(TAG, "publish $path failed", it) }
}
```

- [ ] **Step 3: Companion jobs in the shared service**

`android-shared/.../shared/session/SessionHost.kt`:
- Add the imports `android.content.Context` (if missing), `kotlinx.coroutines.CoroutineScope` and `kotlinx.coroutines.Job`.
- Add as the last member:

```kotlin
    fun launchCompanions(context: Context, scope: CoroutineScope): List<Job> = emptyList()
```

`android-shared/.../shared/session/SessionService.kt`:
- Add the field `private var companions: List<Job> = emptyList()` after `notificationSync`.
- In `beginSession`, after `notificationSync = scope.launch { syncNotification(source) }`, add:

```kotlin
        companions = host.launchCompanions(this, scope)
```

- In `onStop`, after `notificationSync?.cancel()`, add:

```kotlin
        companions.forEach { it.cancel() }
        companions = emptyList()
```

- [ ] **Step 4: Publishers**

`wear-app/.../wear/bridge/StatusPublisher.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.bridge

import android.content.Context
import io.github.santiquiroz.blindside.shared.bridge.STATUS_PATH
import io.github.santiquiroz.blindside.shared.bridge.STATUS_PERIOD_MS
import io.github.santiquiroz.blindside.shared.bridge.encodeWatchStatus
import io.github.santiquiroz.blindside.shared.bridge.publishJson
import io.github.santiquiroz.blindside.shared.bridge.watchStatusOf
import io.github.santiquiroz.blindside.shared.session.SessionStore
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.session.stoppedState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

suspend fun publishWatchStatus(context: Context, clock: () -> Long) {
    try {
        while (currentCoroutineContext().isActive) {
            publishStatus(context, SessionStore.state.value, clock())
            delay(STATUS_PERIOD_MS)
        }
    } finally {
        // The companion is cancelled before the store resets, so the last word is published as stopped explicitly.
        publishStatus(context, stoppedState(SessionStore.state.value), clock())
    }
}

private fun publishStatus(context: Context, session: SessionUiState, nowMs: Long) {
    publishJson(context, STATUS_PATH, encodeWatchStatus(watchStatusOf(session, nowMs)))
}
```

`wear-app/.../wear/bridge/SettingsPublisher.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.bridge

import android.content.Context
import io.github.santiquiroz.blindside.shared.bridge.SETTINGS_PATH
import io.github.santiquiroz.blindside.shared.bridge.encodeSharedSettings
import io.github.santiquiroz.blindside.shared.bridge.publishJson
import io.github.santiquiroz.blindside.shared.settings.SettingsRepository
import io.github.santiquiroz.blindside.shared.settings.isStamped
import io.github.santiquiroz.blindside.shared.settings.sharedSettingsOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

suspend fun publishSharedSettings(context: Context, repository: SettingsRepository) {
    repository.settings
        .map(::sharedSettingsOf)
        .distinctUntilChanged()
        .filter(::isStamped)
        .collect { publishJson(context, SETTINGS_PATH, encodeSharedSettings(it)) }
}
```

- [ ] **Step 5: The listener service**

`wear-app/.../wear/bridge/BridgeListenerService.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.bridge

import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import io.github.santiquiroz.blindside.shared.bridge.OPEN_PAIRING_PATH
import io.github.santiquiroz.blindside.shared.bridge.OpenPairingReply
import io.github.santiquiroz.blindside.shared.bridge.RECORDINGS_LIST_PATH
import io.github.santiquiroz.blindside.shared.bridge.RECORDING_REFUSED_CODE
import io.github.santiquiroz.blindside.shared.bridge.SETTINGS_PATH
import io.github.santiquiroz.blindside.shared.bridge.decodeSharedSettings
import io.github.santiquiroz.blindside.shared.bridge.encodeOpenPairingReply
import io.github.santiquiroz.blindside.shared.bridge.encodeRecordingList
import io.github.santiquiroz.blindside.shared.bridge.jsonIn
import io.github.santiquiroz.blindside.shared.bridge.openPairingReplyFor
import io.github.santiquiroz.blindside.shared.recording.recordingsDir
import io.github.santiquiroz.blindside.shared.recording.shareableRecordings
import io.github.santiquiroz.blindside.shared.session.SessionStore
import io.github.santiquiroz.blindside.shared.session.activeRecordingName
import io.github.santiquiroz.blindside.shared.settings.SharedSettings
import io.github.santiquiroz.blindside.shared.settings.adoptingNewer
import io.github.santiquiroz.blindside.shared.settings.settingsRepository
import io.github.santiquiroz.blindside.wear.session.WearSessionCommands
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.ZoneId

private const val TAG = "BridgeListener"

class BridgeListenerService : WearableListenerService() {
    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray> = when (path) {
        RECORDINGS_LIST_PATH -> Tasks.forResult(recordingListReply())
        OPEN_PAIRING_PATH -> Tasks.forResult(encodeOpenPairingReply(askBeltForPairing()))
        else -> Tasks.forResult(ByteArray(0))
    }

    // Spec §5 names this path a message, so a phone that sends it without waiting for a reply gets the same action (Deviation D6).
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == OPEN_PAIRING_PATH) askBeltForPairing()
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val file = servedRecording(recordingsDir(this), channel.path, activeRecordingName(SessionStore.state.value))
        if (file == null) refuse(channel) else send(channel, file)
    }

    // Listener callbacks run on a background thread, and the event buffer dies when this returns.
    override fun onDataChanged(events: DataEventBuffer) {
        events.mapNotNull(::sharedSettingsIn).lastOrNull()?.let(::adopt)
    }

    private fun recordingListReply(): ByteArray {
        val files = recordingFilesIn(recordingsDir(this))
        val entries = shareableRecordings(files, activeRecordingName(SessionStore.state.value), ZoneId.systemDefault())
        return encodeRecordingList(entries).toByteArray(Charsets.UTF_8)
    }

    private fun askBeltForPairing(): OpenPairingReply {
        val reply = openPairingReplyFor(SessionStore.state.value)
        if (reply == OpenPairingReply.REQUESTED) WearSessionCommands.openPairing(this)
        return reply
    }

    // No bytes plus a non-zero code: the phone must never keep a refusal as an empty recording (Cross-plan contract item 4).
    private fun refuse(channel: ChannelClient.Channel) {
        Wearable.getChannelClient(this).close(channel, RECORDING_REFUSED_CODE)
            .addOnFailureListener { Log.w(TAG, "closing a refused channel failed", it) }
    }

    private fun send(channel: ChannelClient.Channel, file: File) {
        Wearable.getChannelClient(this).sendFile(channel, Uri.fromFile(file))
            .addOnFailureListener { Log.w(TAG, "sending ${file.name} failed", it) }
    }

    private fun adopt(remote: SharedSettings) {
        runBlocking { settingsRepository().update { it.adoptingNewer(remote) } }
    }
}

private fun sharedSettingsIn(event: DataEvent): SharedSettings? {
    if (!isSettingsChange(event)) return null
    return jsonIn(event.dataItem)?.let(::decodeSharedSettings)
}

private fun isSettingsChange(event: DataEvent): Boolean =
    event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == SETTINGS_PATH
```

- [ ] **Step 6: Register the service and start the publishers**

In `watch/wear-app/src/main/AndroidManifest.xml`, add inside `<application>` after the `BlindsideSessionService` `<service>`:

```xml
        <service
            android:name=".bridge.BridgeListenerService"
            android:exported="true">
            <intent-filter>
                <action android:name="com.google.android.gms.wearable.REQUEST_RECEIVED" />
                <data android:scheme="wear" android:host="*" android:path="/recordings/list" />
            </intent-filter>
            <intent-filter>
                <action android:name="com.google.android.gms.wearable.REQUEST_RECEIVED" />
                <data android:scheme="wear" android:host="*" android:path="/belt/open-pairing" />
            </intent-filter>
            <intent-filter>
                <action android:name="com.google.android.gms.wearable.MESSAGE_RECEIVED" />
                <data android:scheme="wear" android:host="*" android:path="/belt/open-pairing" />
            </intent-filter>
            <intent-filter>
                <action android:name="com.google.android.gms.wearable.CHANNEL_EVENT" />
                <data android:scheme="wear" android:host="*" android:pathPrefix="/recordings/get/" />
            </intent-filter>
            <intent-filter>
                <action android:name="com.google.android.gms.wearable.DATA_CHANGED" />
                <data android:scheme="wear" android:host="*" android:path="/settings" />
            </intent-filter>
        </service>
```

`wear-app/.../wear/session/WearSession.kt`:
- Add the imports `io.github.santiquiroz.blindside.wear.bridge.publishWatchStatus`, `kotlinx.coroutines.CoroutineScope`, `kotlinx.coroutines.Job` and `kotlinx.coroutines.launch`.
- Add to `WearSessionHost`:

```kotlin
    override fun launchCompanions(context: Context, scope: CoroutineScope): List<Job> =
        listOf(scope.launch { publishWatchStatus(context, System::currentTimeMillis) })
```

`wear-app/.../wear/ui/BlindsideApp.kt`:
- Add the imports `androidx.compose.runtime.LaunchedEffect` and `io.github.santiquiroz.blindside.wear.bridge.publishSharedSettings`.
- After `LaunchRadarOnOpen(settingsRepository, showRadar)`, add:

```kotlin
    LaunchedEffect(settingsRepository) { publishSharedSettings(context, settingsRepository) }
```

- [ ] **Step 7: Run the verification command and the test-count one-liner, then check the merged manifest**

Expected: `BUILD SUCCESSFUL`; `android-shared 258`, `wear-app 43`.

```bash
cd C:/personal/blindside/watch
M=wear-app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml
for p in 'BridgeListenerService' 'wearable.REQUEST_RECEIVED' 'wearable.MESSAGE_RECEIVED' 'wearable.CHANNEL_EVENT' 'wearable.DATA_CHANGED' '"/recordings/list"' '"/belt/open-pairing"' '"/recordings/get/"' '"/settings"'; do
  printf '%-28s %s\n' "$p" "$(grep -c -- "$p" "$M")"
done
```

Expected, exactly (the merged manifest puts each attribute on its own line, so every count is a number of declarations):

```
BridgeListenerService        1
wearable.REQUEST_RECEIVED    2
wearable.MESSAGE_RECEIVED    1
wearable.CHANNEL_EVENT       1
wearable.DATA_CHANGED        1
"/recordings/list"           1
"/belt/open-pairing"         2
"/recordings/get/"           1
"/settings"                  1
```

A 0 means that filter is missing. A higher count means a filter was pasted twice.

- [ ] **Step 8: Device checklist items**

Append to `watch/wear-app/README.md` (under **Fase 2: reloj y celular**):

```markdown
22. [ ] **Puente con el celular.** Requiere la app del celular, instalada con la misma clave de depuración que el reloj.
    - "Traer del reloj" lista las grabaciones del reloj, sin la que se está grabando, y descarga una completa. Pedir una que no existe termina en un aviso, nunca en un archivo vacío.
    - Al cambiar la mano en el celular, Ajustes del reloj la muestra.
    - Con la partida corriendo, el celular muestra el estado del reloj cada 5 s, y "sesión inactiva" al detenerla.
    - "Emparejar este celular" desde el celular pide la ventana igual que el chip del reloj.
23. [ ] **Sin celular:** con el Bluetooth del celular apagado, una partida de 10 min en el reloj se comporta igual. `adb logcat -s BridgeListener DataLayer` puede mostrar avisos, pero nada se detiene.
```

- [ ] **Step 9: Commit**

```bash
cd C:/personal/blindside
git add watch/gradle/libs.versions.toml watch/android-shared watch/wear-app
git commit -m "feat: el reloj atiende el puente de Wear Data Layer (grabaciones, ajustes, estado y emparejamiento)"
```

---

## Part C — Visual pass (spec §6, watch)

### Task 14: Tokens, verified contrast and the OFL fonts

**Files:**
- Create: `android-shared/.../shared/theme/Tokens.kt`, `theme/Contrast.kt`, `theme/BlindsideColors.kt`, `theme/BlindsideFonts.kt`
- Create: `watch/android-shared/src/main/res/font/jetbrains_mono_nl_regular.ttf`, `jetbrains_mono_nl_bold.ttf`, `ibm_plex_sans_regular.ttf`, `ibm_plex_sans_medium.ttf`
- Create: `licenses/JetBrainsMono-OFL.txt`, `licenses/IBMPlexSans-OFL.txt`
- Modify: `.gitattributes`
- Test: `android-shared/src/test/.../shared/theme/ContrastTest.kt`

**Interfaces:**
- Consumes: `compose-ui` (Task 8).
- Produces:
  - `object Tokens` with ARGB `Long` constants `BG`, `SURFACE`, `SURFACE_2`, `RING`, `ACCENT`, `ACCENT_DIM`, `ALERT_RED`, `ALERT_RED_DIM`, `WARN`, `TEXT` and `TEXT_2`.
  - `relativeLuminance(argb: Long): Double` and `contrastRatio(foreground: Long, background: Long): Double`.
  - `object BlindsideColors` with Compose `Color`s `Bg`, `Surface`, `Surface2`, `Ring`, `Accent`, `AccentDim`, `AlertRed`, `AlertRedDim`, `Warn`, `Text` and `Text2`.
  - `object BlindsideFonts` with `Mono: FontFamily`, `Sans: FontFamily` and `Numbers: TextStyle`.

- [ ] **Step 1: Write the failing test**

`android-shared/src/test/.../shared/theme/ContrastTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContrastTest {
    @Test
    fun `white on black is the maximum ratio`() {
        assertEquals(21.0, contrastRatio(0xFFFFFFFF, Tokens.BG), 0.01)
    }

    @Test
    fun `main text passes seven to one on the background`() {
        assertTrue(contrastRatio(Tokens.TEXT, Tokens.BG) >= 7.0)
    }

    @Test
    fun `secondary text passes four and a half to one on every surface`() {
        listOf(Tokens.BG, Tokens.SURFACE, Tokens.SURFACE_2).forEach {
            assertTrue(contrastRatio(Tokens.TEXT_2, it) >= 4.5, "on ${it.toString(16)}")
        }
    }

    @Test
    fun `accent, warning and alert red stand out on black`() {
        listOf(Tokens.ACCENT, Tokens.WARN, Tokens.ALERT_RED).forEach {
            assertTrue(contrastRatio(it, Tokens.BG) >= 4.5, "colour ${it.toString(16)}")
        }
    }

    @Test
    fun `black labels on the accent button are readable`() {
        assertTrue(contrastRatio(Tokens.BG, Tokens.ACCENT) >= 4.5)
    }

    @Test
    fun `tokens match the spec table`() {
        val spec = listOf(
            0xFF000000, 0xFF0E1111, 0xFF161B1A, 0xFF25302C, 0xFF3BE37A, 0xFF1E7A43,
            0xFFFF5A4E, 0xFF8C2A24, 0xFFF2B84B, 0xFFE8ECEA, 0xFF9AA5A0,
        )
        val tokens = listOf(
            Tokens.BG, Tokens.SURFACE, Tokens.SURFACE_2, Tokens.RING, Tokens.ACCENT, Tokens.ACCENT_DIM,
            Tokens.ALERT_RED, Tokens.ALERT_RED_DIM, Tokens.WARN, Tokens.TEXT, Tokens.TEXT_2,
        )
        assertEquals(spec, tokens)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest --console=plain`
Expected: FAIL with `Unresolved reference 'contrastRatio'`.

- [ ] **Step 3: Tokens, contrast and Compose colours**

`android-shared/.../shared/theme/Tokens.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.theme

object Tokens {
    const val BG = 0xFF000000
    const val SURFACE = 0xFF0E1111
    const val SURFACE_2 = 0xFF161B1A
    const val RING = 0xFF25302C
    const val ACCENT = 0xFF3BE37A
    const val ACCENT_DIM = 0xFF1E7A43
    const val ALERT_RED = 0xFFFF5A4E
    const val ALERT_RED_DIM = 0xFF8C2A24
    const val WARN = 0xFFF2B84B
    const val TEXT = 0xFFE8ECEA
    const val TEXT_2 = 0xFF9AA5A0
}
```

`android-shared/.../shared/theme/Contrast.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.theme

import kotlin.math.pow

private const val RED_WEIGHT = 0.2126
private const val GREEN_WEIGHT = 0.7152
private const val BLUE_WEIGHT = 0.0722
private const val FLARE = 0.05

fun relativeLuminance(argb: Long): Double =
    RED_WEIGHT * linear(channel(argb, 16)) + GREEN_WEIGHT * linear(channel(argb, 8)) + BLUE_WEIGHT * linear(channel(argb, 0))

fun contrastRatio(foreground: Long, background: Long): Double {
    val first = relativeLuminance(foreground)
    val second = relativeLuminance(background)
    return (maxOf(first, second) + FLARE) / (minOf(first, second) + FLARE)
}

private fun channel(argb: Long, shift: Int): Double = ((argb shr shift) and 0xFF) / 255.0

// WCAG 2.x sRGB linearisation.
private fun linear(value: Double): Double = if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
```

`android-shared/.../shared/theme/BlindsideColors.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.theme

import androidx.compose.ui.graphics.Color

object BlindsideColors {
    val Bg = Color(Tokens.BG)
    val Surface = Color(Tokens.SURFACE)
    val Surface2 = Color(Tokens.SURFACE_2)
    val Ring = Color(Tokens.RING)
    val Accent = Color(Tokens.ACCENT)
    val AccentDim = Color(Tokens.ACCENT_DIM)
    val AlertRed = Color(Tokens.ALERT_RED)
    val AlertRedDim = Color(Tokens.ALERT_RED_DIM)
    val Warn = Color(Tokens.WARN)
    val Text = Color(Tokens.TEXT)
    val Text2 = Color(Tokens.TEXT_2)
}
```

- [ ] **Step 4: Download the fonts and their licences**

These are the official release assets. Their paths inside the zips were checked on 2026-10-01.

```bash
cd C:/personal/blindside
TMP=$(mktemp -d)
curl -fsSL -o "$TMP/jbm.zip" https://github.com/JetBrains/JetBrainsMono/releases/download/v2.304/JetBrainsMono-2.304.zip
curl -fsSL -o "$TMP/plex.zip" "https://github.com/IBM/plex/releases/download/%40ibm%2Fplex-sans%401.1.0/ibm-plex-sans.zip"
FONT=watch/android-shared/src/main/res/font
mkdir -p "$FONT" licenses
unzip -p "$TMP/jbm.zip" fonts/ttf/JetBrainsMonoNL-Regular.ttf > "$FONT/jetbrains_mono_nl_regular.ttf"
unzip -p "$TMP/jbm.zip" fonts/ttf/JetBrainsMonoNL-Bold.ttf > "$FONT/jetbrains_mono_nl_bold.ttf"
unzip -p "$TMP/jbm.zip" OFL.txt > licenses/JetBrainsMono-OFL.txt
unzip -p "$TMP/plex.zip" ibm-plex-sans/fonts/complete/ttf/IBMPlexSans-Regular.ttf > "$FONT/ibm_plex_sans_regular.ttf"
unzip -p "$TMP/plex.zip" ibm-plex-sans/fonts/complete/ttf/IBMPlexSans-Medium.ttf > "$FONT/ibm_plex_sans_medium.ttf"
unzip -p "$TMP/plex.zip" ibm-plex-sans/LICENSE.txt > licenses/IBMPlexSans-OFL.txt
rm -rf "$TMP"
wc -c "$FONT"/*.ttf licenses/*.txt
```

Expected sizes:
- `jetbrains_mono_nl_regular.ttf` 208576, `jetbrains_mono_nl_bold.ttf` 210988.
- `ibm_plex_sans_regular.ttf` 200500, `ibm_plex_sans_medium.ttf` 202460.
- The licence files are about 4.4 KB each, and both start with `Copyright` and contain `SIL OPEN FONT LICENSE Version 1.1`.

Append to `.gitattributes`:

```
*.ttf binary
```

**If a download fails** (no network, a 404, or wrong sizes):
- Delete `watch/android-shared/src/main/res/font`.
- Do not create the `licenses/` files.
- Use the fallback `BlindsideFonts.kt` in Step 5.
- Put this deviation in the commit body: `Desviación: sin red para descargar las fuentes OFL; se usa FontFamily.Monospace/SansSerif.`

- [ ] **Step 5: `BlindsideFonts.kt`**

With the fonts downloaded, `android-shared/.../shared/theme/BlindsideFonts.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.santiquiroz.blindside.shared.R

object BlindsideFonts {
    val Mono = FontFamily(
        Font(R.font.jetbrains_mono_nl_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_nl_bold, FontWeight.Bold),
    )
    val Sans = FontFamily(
        Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    )

    // Distances, bearings and counters must not change width as their digits change.
    val Numbers = TextStyle(fontFamily = Mono, fontFeatureSettings = "tnum")
}
```

Fallback version (only if Step 4 failed):

```kotlin
package io.github.santiquiroz.blindside.shared.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

object BlindsideFonts {
    val Mono = FontFamily.Monospace
    val Sans = FontFamily.SansSerif

    // Distances, bearings and counters must not change width as their digits change.
    val Numbers = TextStyle(fontFamily = Mono, fontFeatureSettings = "tnum")
}
```

- [ ] **Step 6: Run the verification command and the test-count one-liner**

Expected: `BUILD SUCCESSFUL`; `android-shared 264`, `wear-app 43`.

- [ ] **Step 7: Commit**

```bash
cd C:/personal/blindside
git add watch/android-shared licenses .gitattributes
git commit -m "feat: tokens de color con contraste verificado y fuentes JetBrains Mono e IBM Plex Sans"
```

---

### Task 15: Radar with tactical tokens, shape-coded contacts, status chips and a contact colour setting

**Files:**
- Modify: `android-shared/.../shared/radar/RadarColors.kt`, `radar/RadarCanvas.kt`, `radar/RadarGeometry.kt`, `radar/RadarLabels.kt`
- Modify: `android-shared/.../shared/settings/AppSettings.kt`, `settings/SettingsPreferences.kt`
- Rewrite: `wear-app/.../wear/ui/radar/RadarScreen.kt`
- Modify: `wear-app/.../wear/ui/Palette.kt`, `ui/Labels.kt`, `ui/SettingsScreen.kt`
- Test: `android-shared/src/test/.../shared/radar/RadarGeometryTest.kt`, `radar/RadarLabelsTest.kt`, `radar/RadarColorsTest.kt` (new), `settings/AppSettingsTest.kt`, `settings/SettingsPreferencesTest.kt`
- Test: `wear-app/src/test/.../wear/ui/LabelsTest.kt`

**Interfaces:**
- Consumes: `BlindsideColors` and `BlindsideFonts` (Task 14); `RadarColors` and `drawRadar(model, colors)` (Task 8).
- Produces:
  - `enum class ContactTone { FULL, DIM }` and `contactTone(style: BlipStyle): ContactTone` (`RadarGeometry.kt`).
  - `enum class StatusMark { FILLED, HOLLOW }`, `statusMark(item: StatusItem): StatusMark` and `statusRows(items: List<StatusItem>): List<List<StatusItem>>` (`RadarLabels.kt`).
  - `val TACTICAL_RADAR_COLORS: RadarColors` (green `accent`) and `val RED_RADAR_COLORS: RadarColors` (`alert-red`). They replace `CLASSIC_RADAR_COLORS`, which is deleted.
  - `enum class ContactColor { GREEN, RED }`, `AppSettings.contactColor: ContactColor = GREEN` (DataStore key `contact_color`), `toggledContactColor(color)` and `radarColorsFor(color: ContactColor): RadarColors`.
  - In `wear-app`: `contactColorLabel(color: ContactColor): String` and the Ajustes chip "Color de contactos" (Deviation D9).

- [ ] **Step 1: Write the failing tests**

`RadarGeometryTest.kt`, add:

```kotlin
    @Test
    fun `coasting contacts use the dim tone`() {
        assertEquals(ContactTone.FULL, contactTone(BlipStyle.FILLED))
        assertEquals(ContactTone.FULL, contactTone(BlipStyle.OUTLINE))
        assertEquals(ContactTone.DIM, contactTone(BlipStyle.DASHED))
    }
```

`RadarLabelsTest.kt`, add:

```kotlin
    @Test
    fun `status chips mark ok with a filled dot and faults with a hollow one`() {
        assertEquals(StatusMark.FILLED, statusMark(StatusItem("BLE", ok = true)))
        assertEquals(StatusMark.HOLLOW, statusMark(StatusItem("BLE", ok = false)))
    }

    @Test
    fun `long status labels move to their own row`() {
        val rows = statusRows(statusItems(scene(), watchSteps = false))
        assertEquals(listOf(listOf("BLE", "R-A", "R-B", "I-A", "I-B"), listOf(NO_WATCH_STEPS_LABEL)), rows.map { row -> row.map { it.label } })
        assertEquals(1, statusRows(statusItems(scene(), watchSteps = true)).size)
    }
```

Create `android-shared/src/test/.../shared/radar/RadarColorsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.radar

import io.github.santiquiroz.blindside.shared.settings.ContactColor
import io.github.santiquiroz.blindside.shared.theme.BlindsideColors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RadarColorsTest {
    @Test
    fun `each contact colour draws with its own palette and shares the rings`() {
        assertEquals(TACTICAL_RADAR_COLORS, radarColorsFor(ContactColor.GREEN))
        assertEquals(RED_RADAR_COLORS, radarColorsFor(ContactColor.RED))
        assertEquals(BlindsideColors.Accent, radarColorsFor(ContactColor.GREEN).contact)
        assertEquals(BlindsideColors.AlertRed, radarColorsFor(ContactColor.RED).contact)
        assertEquals(TACTICAL_RADAR_COLORS.ring, RED_RADAR_COLORS.ring)
    }
}
```

`AppSettingsTest.kt` (`shared.settings`), add:

```kotlin
    @Test
    fun `contacts are green by default and the colour toggles with red`() {
        assertEquals(ContactColor.GREEN, AppSettings().contactColor)
        assertEquals(ContactColor.RED, toggledContactColor(ContactColor.GREEN))
        assertEquals(ContactColor.GREEN, toggledContactColor(ContactColor.RED))
    }
```

`SettingsPreferencesTest.kt`: in `every field survives a write and read`, add `contactColor = ContactColor.RED,` to the `AppSettings(...)` after the `sharedUpdatedMs` line.

`wear-app/src/test/.../wear/ui/LabelsTest.kt`, add the import `io.github.santiquiroz.blindside.shared.settings.ContactColor` and:

```kotlin
    @Test
    fun `contact colour labels name the colour`() {
        assertEquals("Verde", contactColorLabel(ContactColor.GREEN))
        assertEquals("Rojo", contactColorLabel(ContactColor.RED))
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest :wear-app:testDebugUnitTest --console=plain`
Expected: FAIL with `Unresolved reference 'contactTone'`, `'StatusMark'`, `'ContactColor'`, `'radarColorsFor'`.

- [ ] **Step 3: Pure mappings**

`RadarGeometry.kt`, add after `fun blipStyle(...)`:

```kotlin
enum class ContactTone { FULL, DIM }

// Spec §6: accent-dim marks contacts kept alive without a fresh measurement.
fun contactTone(style: BlipStyle): ContactTone = if (style == BlipStyle.DASHED) ContactTone.DIM else ContactTone.FULL
```

`RadarLabels.kt`, add at the end:

```kotlin
enum class StatusMark { FILLED, HOLLOW }

private const val SHORT_STATUS_LABEL = 3

// Colour is never the only signal (spec §6), so each chip also carries a filled or hollow dot.
fun statusMark(item: StatusItem): StatusMark = if (item.ok) StatusMark.FILLED else StatusMark.HOLLOW

// The round screen narrows at the bottom: long labels such as "SIN PASOS" go on their own row.
fun statusRows(items: List<StatusItem>): List<List<StatusItem>> =
    items.partition { it.label.length <= SHORT_STATUS_LABEL }.toList().filter { it.isNotEmpty() }
```

`RadarColors.kt` (whole file):

```kotlin
package io.github.santiquiroz.blindside.shared.radar

import androidx.compose.ui.graphics.Color
import io.github.santiquiroz.blindside.shared.settings.ContactColor
import io.github.santiquiroz.blindside.shared.theme.BlindsideColors

data class RadarColors(
    val contact: Color,
    val contactDim: Color,
    val fan: Color,
    val ring: Color,
    val dimmed: Color,
)

val TACTICAL_RADAR_COLORS = RadarColors(
    contact = BlindsideColors.Accent,
    contactDim = BlindsideColors.AccentDim,
    fan = BlindsideColors.Ring,
    ring = BlindsideColors.Ring,
    dimmed = BlindsideColors.Surface2,
)

// v1 spec §5.3: red against the rivals' view, green against night-vision tubes. Only the contacts change.
val RED_RADAR_COLORS = TACTICAL_RADAR_COLORS.copy(contact = BlindsideColors.AlertRed, contactDim = BlindsideColors.AlertRedDim)

fun radarColorsFor(color: ContactColor): RadarColors = when (color) {
    ContactColor.GREEN -> TACTICAL_RADAR_COLORS
    ContactColor.RED -> RED_RADAR_COLORS
}
```

`android-shared/.../shared/settings/AppSettings.kt`:
- Add after `enum class VibrationUsage { ALARM, NOTIFICATION }`:

```kotlin
enum class ContactColor { GREEN, RED }
```

- Add `val contactColor: ContactColor = ContactColor.GREEN,` as the last field of `data class AppSettings`, after `val sharedUpdatedMs: Long = 0L,`.
- Add at the end of the file:

```kotlin
fun toggledContactColor(color: ContactColor): ContactColor =
    if (color == ContactColor.GREEN) ContactColor.RED else ContactColor.GREEN
```

`android-shared/.../shared/settings/SettingsPreferences.kt`:
- In `Keys`, add `val CONTACT_COLOR = stringPreferencesKey("contact_color")`.
- In `settingsFrom`, add `contactColor = enumOrDefault(prefs[Keys.CONTACT_COLOR], defaults.contactColor),` after the `sharedUpdatedMs` line.
- In `writeSettings`, add `prefs[Keys.CONTACT_COLOR] = settings.contactColor.name` after the `SHARED_UPDATED_MS` line.

The colour is a per-device display preference, so it is not part of `SharedSettings`, and changing it does not stamp `sharedUpdatedMs`.

`wear-app/.../wear/ui/Labels.kt`:
- Add the import `io.github.santiquiroz.blindside.shared.settings.ContactColor`.
- Add after `fun usageLabel(...)`:

```kotlin
fun contactColorLabel(color: ContactColor): String = when (color) {
    ContactColor.GREEN -> "Verde"
    ContactColor.RED -> "Rojo"
}
```

`wear-app/.../wear/ui/SettingsScreen.kt`:
- Add the import `io.github.santiquiroz.blindside.shared.settings.toggledContactColor`.
- After the `item { SettingChip("Pantalla", ...) }` line, add:

```kotlin
        item { SettingChip("Color de contactos", contactColorLabel(settings.contactColor)) { onUpdate { it.copy(contactColor = toggledContactColor(it.contactColor)) } } }
```

`RadarCanvas.kt`: replace `drawBlip` with the version below and add `toneColor`:

```kotlin
private fun DrawScope.drawBlip(blip: BlipDraw, radius: Float, colors: RadarColors) {
    val color = toneColor(contactTone(blip.style), colors).copy(alpha = blip.alpha)
    val center = Offset(blip.center.x, blip.center.y)
    when (blip.style) {
        BlipStyle.FILLED -> drawCircle(color, radius, center)
        BlipStyle.OUTLINE -> drawCircle(color, radius, center, style = Stroke(width = BLIP_STROKE_PX))
        BlipStyle.DASHED -> drawCircle(color, radius, center, style = Stroke(width = BLIP_STROKE_PX, pathEffect = DASHED))
    }
}

private fun toneColor(tone: ContactTone, colors: RadarColors): Color = when (tone) {
    ContactTone.FULL -> colors.contact
    ContactTone.DIM -> colors.contactDim
}
```

- [ ] **Step 4: The watch radar screen**

`wear-app/.../wear/ui/radar/RadarScreen.kt` (whole file):

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import io.github.santiquiroz.blindside.shared.radar.DND_RADAR_WARNING
import io.github.santiquiroz.blindside.shared.radar.PointPx
import io.github.santiquiroz.blindside.shared.radar.StatusItem
import io.github.santiquiroz.blindside.shared.radar.StatusMark
import io.github.santiquiroz.blindside.shared.radar.centerLabel
import io.github.santiquiroz.blindside.shared.radar.drawRadar
import io.github.santiquiroz.blindside.shared.radar.eliminatedActionLabel
import io.github.santiquiroz.blindside.shared.radar.radarColorsFor
import io.github.santiquiroz.blindside.shared.radar.rotatedAbout
import io.github.santiquiroz.blindside.shared.radar.screenCenter
import io.github.santiquiroz.blindside.shared.radar.showContacts
import io.github.santiquiroz.blindside.shared.radar.statusItems
import io.github.santiquiroz.blindside.shared.radar.statusMark
import io.github.santiquiroz.blindside.shared.radar.statusRows
import io.github.santiquiroz.blindside.shared.radar.toDrawModel
import io.github.santiquiroz.blindside.shared.radar.warningLabel
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import io.github.santiquiroz.blindside.shared.theme.BlindsideColors
import io.github.santiquiroz.blindside.shared.theme.BlindsideFonts
import io.github.santiquiroz.blindside.wear.ui.KeepScreenOn
import io.github.santiquiroz.blindside.wear.ui.ReportRadarVisibility
import io.github.santiquiroz.blindside.wear.ui.burnInOffset
import io.github.santiquiroz.blindside.wear.ui.keepScreenOn
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val BURN_IN_CLOCK_TICK_MS = 30_000L
private val CENTER_LABEL_SIZE = 26.sp
private val LINK_MESSAGE_SIZE = 14.sp
private val LINK_MESSAGE_SIDE_PADDING = 28.dp
private val STATUS_DOT_SIZE = 4.dp
private val STATUS_DOT_STROKE = 1.dp

@Composable
fun RadarScreen(
    session: SessionUiState,
    settings: AppSettings,
    ambient: Boolean,
    onToggleEliminated: () -> Unit,
) {
    ReportRadarVisibility()
    KeepScreenOn(keepScreenOn(settings.screenMode, session.eliminated))
    val elapsedMs by rememberElapsedMs()
    val shift = burnInOffset(settings.screenMode, elapsedMs)
    val contacts = showContacts(session.scene, ambient)
    val rotationDeg = settings.posture.rotationDeg
    Box(Modifier.fillMaxSize().background(BlindsideColors.Bg)) {
        Canvas(Modifier.fillMaxSize()) {
            val logical = toDrawModel(session.scene, size.width, size.height, shift, contacts)
            drawRadar(logical.rotatedAbout(screenCenter(size.width, size.height, shift), rotationDeg), radarColorsFor(settings.contactColor))
        }
        RadarOverlay(session, ambient, shift, rotationDeg, onToggleEliminated)
    }
}

@Composable
private fun RadarOverlay(
    session: SessionUiState,
    ambient: Boolean,
    shift: PointPx,
    rotationDeg: Float,
    onToggleEliminated: () -> Unit,
) {
    val link = radarMessage(session)
    val placement = Modifier.fillMaxSize()
        .offset { IntOffset(shift.x.roundToInt(), shift.y.roundToInt()) }
        // Turns after the shift, about the shifted center like the drawing, so the panel stays over the rear and off the flanks.
        .graphicsLayer { rotationZ = rotationDeg }
    Box(placement) {
        centerLabel(session.scene, ambient, link)?.let { label ->
            CenterLabel(label, isLinkMessage = label == link, Modifier.align(Alignment.Center))
        }
        // Spec §5.4: the dimmed screen keeps ≤ 15 % lit pixels, so ambient shows only the fan and "--".
        if (!ambient) BottomPanel(session, onToggleEliminated, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun CenterLabel(label: String, isLinkMessage: Boolean, modifier: Modifier) {
    Text(
        label,
        modifier.padding(horizontal = LINK_MESSAGE_SIDE_PADDING),
        color = BlindsideColors.Text2,
        fontSize = if (isLinkMessage) LINK_MESSAGE_SIZE else CENTER_LABEL_SIZE,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun BottomPanel(session: SessionUiState, onToggleEliminated: () -> Unit, modifier: Modifier) {
    Column(modifier = modifier.padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (session.dndMaySilenceAlerts) Text(DND_RADAR_WARNING, color = BlindsideColors.Warn, fontSize = 11.sp, maxLines = 1)
        warningLabel(session.scene?.warnings.orEmpty())?.let { Text(it, color = BlindsideColors.Warn, fontSize = 11.sp) }
        statusRows(statusItems(session.scene, session.watchSteps)).forEach { StatusRow(it) }
        EliminatedChip(session.eliminated, onToggleEliminated)
    }
}

@Composable
private fun StatusRow(items: List<StatusItem>) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        items.forEach { StatusChip(it) }
    }
}

@Composable
private fun StatusChip(item: StatusItem) {
    val tint = if (item.ok) BlindsideColors.Accent else BlindsideColors.AlertRed
    Row(
        Modifier.background(BlindsideColors.Surface, CircleShape).padding(horizontal = 3.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        StatusDot(statusMark(item), tint)
        Text(item.label, color = tint, fontSize = 9.sp, fontFamily = BlindsideFonts.Mono, maxLines = 1)
    }
}

@Composable
private fun StatusDot(mark: StatusMark, tint: Color) {
    Canvas(Modifier.size(STATUS_DOT_SIZE)) {
        when (mark) {
            StatusMark.FILLED -> drawCircle(tint)
            StatusMark.HOLLOW -> drawCircle(tint, style = Stroke(width = STATUS_DOT_STROKE.toPx()))
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

In `wear-app/.../wear/ui/Palette.kt`, delete the `STATUS_OK` and `STATUS_BAD` lines. Only `RadarScreen` used them.

```bash
cd C:/personal/blindside/watch
grep -rnE 'CLASSIC_RADAR_COLORS|STATUS_OK|STATUS_BAD' wear-app/src android-shared/src || echo "no leftovers"
```

Expected: `no leftovers`.

- [ ] **Step 5: Run the verification command and the test-count one-liner**

Expected: `BUILD SUCCESSFUL`; `android-shared 269`, `wear-app 44`.

- [ ] **Step 6: Commit**

```bash
cd C:/personal/blindside
git add watch/android-shared watch/wear-app
git commit -m "feat: radar del reloj con tokens tácticos, contactos con forma por confianza, chips de estado y color de contactos verde o rojo"
```

---

### Task 16: Watch home with a big circular "Iniciar radar" and the OLED theme

**Files:**
- Create: `wear-app/.../wear/ui/HomeItems.kt`, `wear/ui/theme/WearTheme.kt`
- Rewrite: `wear-app/.../wear/ui/HomeScreen.kt`
- Modify: `wear-app/.../wear/ui/BlindsideApp.kt`, `ui/SettingsScreen.kt`, `ui/PracticeScreen.kt`, `ui/Widgets.kt`
- Delete: `wear-app/.../wear/ui/Palette.kt`
- Test: `wear-app/src/test/.../wear/ui/HomeItemsTest.kt`
- Modify: `watch/wear-app/README.md`

**Interfaces:**
- Consumes: `BlindsideColors` and `BlindsideFonts` (Task 14); `WearSessionCommands` (Task 6); `SessionUiState`.
- Produces:
  - `enum class HomeItem { HEADER, START, BLUETOOTH_BLOCKED, START_ERROR, SETTINGS, LAST_RECORDING }` and `idleHomeItems(session: SessionUiState, bluetoothBlocked: Boolean): List<HomeItem>`.
  - `@Composable fun BlindsideWearTheme(content: @Composable () -> Unit)`.

- [ ] **Step 1: Write the failing test**

`wear-app/src/test/.../wear/ui/HomeItemsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui

import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.session.StartError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HomeItemsTest {
    @Test
    fun `the start button is the second item so the list centres it`() {
        assertEquals(HomeItem.START, idleHomeItems(SessionUiState(), bluetoothBlocked = false)[1])
        val blocked = SessionUiState(startError = StartError.BLUETOOTH_UNAVAILABLE)
        assertEquals(HomeItem.START, idleHomeItems(blocked, bluetoothBlocked = true)[1])
    }

    @Test
    fun `notices sit between the start button and the settings chip`() {
        val items = idleHomeItems(SessionUiState(startError = StartError.BLUETOOTH_UNAVAILABLE), bluetoothBlocked = true)
        assertEquals(listOf(HomeItem.HEADER, HomeItem.START, HomeItem.BLUETOOTH_BLOCKED, HomeItem.START_ERROR, HomeItem.SETTINGS), items)
    }

    @Test
    fun `the last recording closes the list`() {
        val items = idleHomeItems(SessionUiState(lastRecordingName = "x.bsrec"), bluetoothBlocked = false)
        assertEquals(listOf(HomeItem.HEADER, HomeItem.START, HomeItem.SETTINGS, HomeItem.LAST_RECORDING), items)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd C:/personal/blindside/watch && ./gradlew :wear-app:testDebugUnitTest --console=plain`
Expected: FAIL with `Unresolved reference 'idleHomeItems'`.

- [ ] **Step 3: The pure item order**

`wear-app/.../wear/ui/HomeItems.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui

import io.github.santiquiroz.blindside.shared.session.SessionUiState

enum class HomeItem { HEADER, START, BLUETOOTH_BLOCKED, START_ERROR, SETTINGS, LAST_RECORDING }

// ScalingLazyColumn centres item 1 on open, so the start button keeps that slot and notices go below it.
fun idleHomeItems(session: SessionUiState, bluetoothBlocked: Boolean): List<HomeItem> = listOfNotNull(
    HomeItem.HEADER,
    HomeItem.START,
    HomeItem.BLUETOOTH_BLOCKED.takeIf { bluetoothBlocked },
    HomeItem.START_ERROR.takeIf { session.startError != null },
    HomeItem.SETTINGS,
    HomeItem.LAST_RECORDING.takeIf { session.lastRecordingName != null },
)
```

- [ ] **Step 4: The theme**

`wear-app/.../wear/ui/theme/WearTheme.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Typography
import io.github.santiquiroz.blindside.shared.theme.BlindsideColors
import io.github.santiquiroz.blindside.shared.theme.BlindsideFonts

private val WearColors = Colors(
    primary = BlindsideColors.Accent,
    primaryVariant = BlindsideColors.AccentDim,
    secondary = BlindsideColors.Accent,
    secondaryVariant = BlindsideColors.AccentDim,
    background = BlindsideColors.Bg,
    surface = BlindsideColors.Surface,
    error = BlindsideColors.AlertRed,
    onPrimary = BlindsideColors.Bg,
    onSecondary = BlindsideColors.Bg,
    onBackground = BlindsideColors.Text,
    onSurface = BlindsideColors.Text,
    onSurfaceVariant = BlindsideColors.Text2,
    onError = BlindsideColors.Bg,
)

private val WearTypography = Typography(defaultFontFamily = BlindsideFonts.Sans)

@Composable
fun BlindsideWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = WearColors, typography = WearTypography, content = content)
}
```

- [ ] **Step 5: The home screen**

`wear-app/.../wear/ui/HomeScreen.kt` (whole file):

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
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import io.github.santiquiroz.blindside.shared.ble.needsRetry
import io.github.santiquiroz.blindside.shared.permissions.SESSION_PERMISSIONS
import io.github.santiquiroz.blindside.shared.permissions.StartDecision
import io.github.santiquiroz.blindside.shared.permissions.startDecision
import io.github.santiquiroz.blindside.shared.radar.eliminatedActionLabel
import io.github.santiquiroz.blindside.shared.session.SessionSource
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.theme.BlindsideColors
import io.github.santiquiroz.blindside.wear.R
import io.github.santiquiroz.blindside.wear.session.WearSessionCommands

private val START_BUTTON_SIZE = 104.dp
private val START_ICON_SIZE = 32.dp

private data class IdleHomeActions(
    val onStart: () -> Unit,
    val onSettings: () -> Unit,
    val onOpenAppSettings: () -> Unit,
)

@Composable
fun HomeScreen(
    session: SessionUiState,
    onNavigate: (String) -> Unit,
    onShowRadar: () -> Unit,
    onToggleEliminated: () -> Unit,
) {
    if (session.running) RunningHome(session, onShowRadar, onToggleEliminated) else IdleHome(session, onNavigate, onShowRadar)
}

@Composable
private fun IdleHome(session: SessionUiState, onNavigate: (String) -> Unit, onShowRadar: () -> Unit) {
    val context = LocalContext.current
    var bluetoothBlocked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        bluetoothBlocked = !startBeltSession(context, startDecision(grants), onShowRadar)
    }
    val actions = IdleHomeActions(
        onStart = { startOrAskPermissions(context, onShowRadar) { launcher.launch(SESSION_PERMISSIONS) } },
        onSettings = { onNavigate(ROUTE_SETTINGS) },
        onOpenAppSettings = { openAppSettings(context) },
    )
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        idleHomeItems(session, bluetoothBlocked).forEach { kind -> item { IdleHomeItem(kind, session, actions) } }
    }
}

@Composable
private fun IdleHomeItem(kind: HomeItem, session: SessionUiState, actions: IdleHomeActions) {
    when (kind) {
        HomeItem.HEADER -> ListHeader { Text("Blindside") }
        HomeItem.START -> StartRadarButton(actions.onStart)
        HomeItem.BLUETOOTH_BLOCKED -> BlockedNotice(actions.onOpenAppSettings)
        HomeItem.START_ERROR -> session.startError?.let { Notice(startErrorMessage(it)) }
        HomeItem.SETTINGS -> CompactChip(onClick = actions.onSettings, label = { Text(SETTINGS_ENTRY_LABEL) })
        HomeItem.LAST_RECORDING -> session.lastRecordingName?.let { Notice("Última grabación: $it") }
    }
}

@Composable
private fun RunningHome(
    session: SessionUiState,
    onShowRadar: () -> Unit,
    onToggleEliminated: () -> Unit,
) {
    val context = LocalContext.current
    var confirmingStop by remember { mutableStateOf(false) }
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { ListHeader { Text(sessionHeadline(session)) } }
        if (needsRetry(session.ble)) item { RetryNotice(bleStatusLabel(session.ble)) { WearSessionCommands.retryLink(context) } }
        if (session.recordingFailed) item { Text(RECORDING_FAILED_MESSAGE, color = BlindsideColors.Warn) }
        item { NavChip("Ver radar", onShowRadar) }
        item { NavChip(eliminatedActionLabel(session.eliminated), onToggleEliminated) }
        item { NavChip("Marcar rival") { WearSessionCommands.marker(context) } }
        item { NavChip(stopLabel(confirmingStop)) { confirmingStop = handleStopTap(context, confirmingStop) } }
        session.recordingName?.let { name -> item { Notice(name) } }
    }
}

@Composable
private fun StartRadarButton(onStart: () -> Unit) {
    Button(onClick = onStart, modifier = Modifier.size(START_BUTTON_SIZE), colors = ButtonDefaults.primaryButtonColors()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_radar), contentDescription = null, modifier = Modifier.size(START_ICON_SIZE))
            Text(START_RADAR_LABEL, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
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
        Text(message, color = BlindsideColors.Warn)
        NavChip("Reintentar", onRetry)
    }
}

private fun startOrAskPermissions(context: Context, onShowRadar: () -> Unit, requestPermissions: () -> Unit) {
    when (startTapAction(sessionGrants(context))) {
        StartTapAction.START_SESSION -> startRadar(context, SessionSource.BELT, onShowRadar)
        StartTapAction.REQUEST_PERMISSIONS -> requestPermissions()
    }
}

private fun startBeltSession(context: Context, decision: StartDecision, onShowRadar: () -> Unit): Boolean =
    when (decision) {
        is StartDecision.Start -> {
            startRadar(context, SessionSource.BELT, onShowRadar)
            true
        }
        StartDecision.BlockedBluetoothDenied -> false
    }

private fun handleStopTap(context: Context, confirming: Boolean): Boolean {
    if (confirming) WearSessionCommands.stop(context)
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

- [ ] **Step 6: Theme the app, black navigation background, Mono yaw and no more `Palette.kt`**

`wear-app/.../wear/ui/BlindsideApp.kt`:
- Replace the import `androidx.wear.compose.material.MaterialTheme` with `io.github.santiquiroz.blindside.wear.ui.theme.BlindsideWearTheme`.
- Add the imports `androidx.compose.foundation.background`, `androidx.compose.ui.Modifier` and `io.github.santiquiroz.blindside.shared.theme.BlindsideColors`.
- Replace `MaterialTheme {` with `BlindsideWearTheme {`.
- Replace `SwipeDismissableNavHost(navController = navController, startDestination = ROUTE_HOME) {` with:

```kotlin
        SwipeDismissableNavHost(navController = navController, startDestination = ROUTE_HOME, modifier = Modifier.background(BlindsideColors.Bg)) {
```

`wear-app/.../wear/ui/SettingsScreen.kt`:
- Add the import `io.github.santiquiroz.blindside.shared.theme.BlindsideFonts`.
- In `YawRow`, replace `Text(yawLabel(radarId, effectiveYawDeg(settings, radarId)), fontSize = 12.sp)` with:

```kotlin
        Text(yawLabel(radarId, effectiveYawDeg(settings, radarId)), fontSize = 12.sp, fontFamily = BlindsideFonts.Mono)
```

`wear-app/.../wear/ui/PracticeScreen.kt`:
- Replace `color = WARNING_AMBER` with `color = BlindsideColors.Warn`.
- Add `import io.github.santiquiroz.blindside.shared.theme.BlindsideColors` after `import io.github.santiquiroz.blindside.shared.settings.AppSettings`.

`wear-app/.../wear/ui/Widgets.kt`:
- Replace `color = LABEL_GRAY` with `color = BlindsideColors.Text2`.
- Add `import io.github.santiquiroz.blindside.shared.theme.BlindsideColors` after `import androidx.wear.compose.material.Text`.

```bash
cd C:/personal/blindside/watch
git rm wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/Palette.kt
# WearTheme.kt is the one file that must keep importing the Wear MaterialTheme.
grep -rnE 'LABEL_GRAY|WARNING_AMBER|STATUS_OK|STATUS_BAD|wear\.compose\.material\.MaterialTheme' wear-app/src --exclude=WearTheme.kt || echo "no leftovers"
grep -c 'androidx.wear.compose.material.MaterialTheme' wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/theme/WearTheme.kt
```

Expected: `no leftovers`, then `1`.

- [ ] **Step 7: Run the verification command and the test-count one-liner**

Expected: `BUILD SUCCESSFUL`; `android-shared 269`, `wear-app 47`.

- [ ] **Step 8: Device checklist**

In `watch/wear-app/README.md`:
- Replace checklist item 1's first sentence, `La app abre y muestra "Blindside" con Iniciar/Demo/Práctica/Ajustes.`, with:

```markdown
La app abre en Inicio con el botón circular verde "Iniciar radar" en el centro y el chip "Ajustes" debajo.
```

- In item 4, replace `contactos rojos` with `contactos verdes (rojos con Ajustes → Color de contactos → Rojo)`.
- Append under **Fase 2: reloj y celular**:

```markdown
24. [ ] **Pasada visual:** todas las pantallas tienen fondo negro.
    - En el radar, los anillos son verde oscuro. Los contactos son verdes: relleno = los dos radares, contorno = uno, punteado y más oscuro = sin medida nueva.
    - Ajustes → "Color de contactos" cambia a "Rojo": los contactos pasan a rojo (`alert-red`), con las mismas formas, y el ajuste sobrevive a reiniciar la app.
    - Los chips de estado llevan un punto lleno (bien) o hueco (falla), y "SIN PASOS" va en su propia fila.
    - Los ángulos de Ajustes y los chips usan letra monoespaciada.
    - El botón "Iniciar radar" y el resto de controles se pueden tocar sin errar con el guante.
```

- [ ] **Step 9: Commit**

```bash
cd C:/personal/blindside
git add watch/wear-app
git commit -m "feat: inicio del reloj con botón circular Iniciar radar y tema oscuro OLED"
```

---

### Task 16b: Radar a pantalla completa y anillo de brújula (spec §6, added 1-oct at Santiago's request)

The orchestrator added this task after the plan review, because the spec gained the "Radar a pantalla completa con brújula" block in §6. It runs after Task 16 and uses the files as Tasks 8, 14, 15 and 16 leave them.

**Geometry behind it:** on a round screen with a usable radius `L` (screen radius minus the compass band), a fan with half-angle `A` is largest when its origin drops `L·cot(A)` below the centre. Its radius is then `L / sin(A)`, and both the flank edge and the 6 m arc touch the limit circle. The right-handed belt covers −100..+80°, which clamps to `A = 90°`. So the origin moves to the centre and the 6 m arc reaches the compass band (today the origin is at 0.58·h with a radius of 0.42·side). The rear half that is now free holds the heading window, the status chips and the eliminated chip. `A` is clamped to 45..90° so the origin never leaves the screen.

**Files:**
- Create: `android-shared/.../shared/radar/FanLayout.kt`
- Modify: `android-shared/.../shared/radar/RadarGeometry.kt` (`toDrawModel` gains `edgeMarginPx`; `ORIGIN_Y_FRACTION` and `RADIUS_FRACTION` are deleted)
- Create: `android-shared/.../shared/compass/CompassMath.kt`, `compass/CompassRing.kt`, `compass/CompassColors.kt`
- Modify: `android-shared/.../shared/settings/AppSettings.kt`, `settings/SettingsPreferences.kt`
- Create: `wear-app/.../wear/ui/radar/CompassSensor.kt`, `wear-app/.../wear/ui/radar/CompassRingCanvas.kt`
- Modify: `wear-app/.../wear/ui/radar/RadarScreen.kt`, `wear-app/.../wear/ui/SettingsScreen.kt`, `watch/wear-app/README.md`
- Test (new): `android-shared/src/test/.../shared/radar/FanLayoutTest.kt`, `android-shared/src/test/.../shared/compass/CompassMathTest.kt`, `compass/CompassRingTest.kt`, `compass/CompassColorsTest.kt`
- Test (modify): `android-shared/src/test/.../shared/radar/RadarGeometryTest.kt`, `settings/AppSettingsTest.kt`, `settings/SettingsPreferencesTest.kt`

**Interfaces:**
- Consumes: `PointPx`, `RadarDrawModel`, `toDrawModel`, `screenCenter`, `rotatedAbout`, `drawRadar(model, colors)`, `radarColorsFor` (Tasks 8 and 15); `BlindsideColors` and `BlindsideFonts` (Task 14); `ScreenMode` and `WatchPosture.rotationDeg` (moved in Task 3); `core.scene.CoverageSector`.
- Produces:
  - Package `io.github.santiquiroz.blindside.shared.radar`: `data class FanFit(originYOffsetPx: Float, radiusPx: Float)`, `fanHalfAngleDeg(sectors: List<CoverageSector>): Double`, `fitFan(sidePx: Float, edgeMarginPx: Float, halfAngleDeg: Double): FanFit`, and `toDrawModel(scene, widthPx, heightPx, offset, showContacts, edgeMarginPx: Float = 0f)`. The new last parameter has a default, so p2-06 callers compile unchanged.
  - Package `io.github.santiquiroz.blindside.shared.compass`: `enum class CompassTrust { GOOD, CALIBRATE }`, `data class CompassReading(azimuthDeg: Double, trust: CompassTrust)`, `azimuthFromRotationVector(values: FloatArray): Double`, `frontHeadingDeg(azimuthDeg: Double, postureRotationDeg: Float): Double`, `smoothedHeadingDeg(previousDeg: Double?, sampleDeg: Double, dtMs: Long, tauMs: Double = HEADING_TAU_MS): Double`, `shortestTurnDeg`, `normalizedDeg`, `cardinalLabel(headingDeg): String`, `headingText(headingDeg): String`, `compassTrust(accuracy: Int): CompassTrust`, `compassWarningLabel(reading: CompassReading?): String?`, `data class CompassTick(angleDeg: Float, major: Boolean)`, `compassTicks(): List<CompassTick>`, `data class CardinalMark(label: String, angleDeg: Float)`, `CARDINAL_MARKS`, `markScreenAngleDeg(markAngleDeg: Float, azimuthDeg: Double): Float`, `pointOnRing(center: PointPx, radiusPx: Float, angleDeg: Float): PointPx`, `data class CompassColors(tick, major, north, letter, index: Color)` and `compassColors(screenMode: ScreenMode, trust: CompassTrust): CompassColors`.
  - `AppSettings.compass: Boolean = true` (DataStore key `compass`). Like the contact colour, it is a per-device display preference: it stays out of `SharedSettings` and does not stamp `sharedUpdatedMs`.
  - In `wear-app`: `rememberCompassReading(active: Boolean): State<CompassReading?>` and `DrawScope.drawCompassRing(...)`.

- [ ] **Step 1: Write the failing tests**

`android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/radar/FanLayoutTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.radar

import io.github.santiquiroz.blindside.core.scene.CoverageSector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class FanLayoutTest {
    @Test
    fun `the half angle is the widest sector edge clamped to forty five and ninety degrees`() {
        assertEquals(90.0, fanHalfAngleDeg(listOf(CoverageSector(-100.0, 20.0), CoverageSector(-40.0, 80.0))))
        assertEquals(60.0, fanHalfAngleDeg(listOf(CoverageSector(-60.0, 60.0))))
        assertEquals(45.0, fanHalfAngleDeg(listOf(CoverageSector(-30.0, 30.0))))
        assertEquals(90.0, fanHalfAngleDeg(emptyList()))
    }

    @Test
    fun `a half disc fan sits on the centre and reaches the usable edge`() {
        val fit = fitFan(480f, 32f, 90.0)
        assertEquals(0f, fit.originYOffsetPx, 1e-3f)
        assertEquals(208f, fit.radiusPx, 1e-3f)
    }

    @Test
    fun `a sixty degree fan drops its origin and grows`() {
        val fit = fitFan(480f, 32f, 60.0)
        assertEquals(120.089f, fit.originYOffsetPx, 1e-2f)
        assertEquals(240.177f, fit.radiusPx, 1e-2f)
    }

    @Test
    fun `flank edge and six metre arc stay inside the usable circle for every half angle`() {
        val limit = 208.0
        (45..90 step 5).forEach { halfAngle ->
            val fit = fitFan(480f, 32f, halfAngle.toDouble())
            val radians = Math.toRadians(halfAngle.toDouble())
            val flank = hypot(fit.radiusPx * sin(radians), fit.originYOffsetPx - fit.radiusPx * cos(radians))
            val tip = kotlin.math.abs(fit.originYOffsetPx - fit.radiusPx)
            assertTrue(flank <= limit + 1e-2, "flank at $halfAngle")
            assertTrue(tip <= limit + 1e-2, "tip at $halfAngle")
        }
    }

    @Test
    fun `a negative usable radius collapses to zero instead of drawing inside out`() {
        assertEquals(0f, fitFan(20f, 32f, 90.0).radiusPx, 1e-3f)
    }
}
```

`RadarGeometryTest.kt`: replace the whole test `` `the origin sits below the centre and rings mark two and four metres` `` with these two tests:

```kotlin
    @Test
    fun `the right handed half disc fan is centred and rings mark two and four metres`() {
        val model = toDrawModel(scene(emptyList()), 480f, 480f, noShift, showContacts = true, edgeMarginPx = 32f)
        assertPoint(PointPx(240f, 240f), model.origin)
        assertEquals(208f, model.radiusPx, 1e-3f)
        assertEquals(2, model.ringRadiiPx.size)
        assertEquals(model.radiusPx / 3f, model.ringRadiiPx[0], 1e-3f)
        assertEquals(model.radiusPx * 2f / 3f, model.ringRadiiPx[1], 1e-3f)
    }

    @Test
    fun `a narrower fan drops the origin below the centre`() {
        val narrow = scene(emptyList()).copy(coverage = listOf(CoverageSector(-60.0, 60.0)))
        val model = toDrawModel(narrow, 480f, 480f, noShift, showContacts = true)
        assertTrue(model.origin.y > 240f)
    }
```

`android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/compass/CompassMathTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.compass

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin

class CompassMathTest {
    private fun aboutUp(deg: Double): FloatArray {
        val half = Math.toRadians(deg) / 2.0
        return floatArrayOf(0f, 0f, sin(half).toFloat(), cos(half).toFloat())
    }

    @Test
    fun `a level watch with twelve o'clock to the north reads zero`() {
        assertEquals(0.0, azimuthFromRotationVector(floatArrayOf(0f, 0f, 0f, 1f)), 1e-6)
    }

    @Test
    fun `turning the watch left a quarter points twelve o'clock to the west`() {
        assertEquals(270.0, azimuthFromRotationVector(aboutUp(90.0)), 1e-3)
    }

    @Test
    fun `a three value rotation vector rebuilds its scalar part`() {
        val full = aboutUp(-30.0)
        assertEquals(azimuthFromRotationVector(full), azimuthFromRotationVector(full.copyOf(3)), 1e-3)
        assertEquals(30.0, azimuthFromRotationVector(full), 1e-3)
    }

    @Test
    fun `the tactical posture adds its drawing rotation to the heading`() {
        assertEquals(80.0, frontHeadingDeg(350.0, 90f), 1e-6)
        assertEquals(280.0, frontHeadingDeg(10.0, -90f), 1e-6)
        assertEquals(10.0, frontHeadingDeg(10.0, 0f), 1e-6)
    }

    @Test
    fun `smoothing starts on the first sample and turns through north`() {
        assertEquals(12.0, smoothedHeadingDeg(null, 12.0, 0L), 1e-9)
        assertEquals(10.0, smoothedHeadingDeg(350.0, 10.0, 10_000L), 1e-6)
        assertEquals(2.642, smoothedHeadingDeg(350.0, 10.0, 150L), 1e-3)
        assertEquals(350.0, smoothedHeadingDeg(350.0, 10.0, 0L), 1e-9)
    }

    @Test
    fun `the shortest turn never exceeds half a circle`() {
        assertEquals(20.0, shortestTurnDeg(350.0, 10.0), 1e-9)
        assertEquals(-20.0, shortestTurnDeg(10.0, 350.0), 1e-9)
        assertEquals(180.0, shortestTurnDeg(0.0, 180.0), 1e-9)
    }

    @Test
    fun `eight spanish cardinal sectors`() {
        val labels = listOf(0.0, 22.4, 22.6, 90.0, 180.0, 225.0, 270.0, 318.0, 359.0).map(::cardinalLabel)
        assertEquals(listOf("N", "N", "NE", "E", "S", "SO", "O", "NO", "N"), labels)
        assertEquals("N", cardinalLabel(-1.0))
    }

    @Test
    fun `the heading text has three digits and the cardinal`() {
        assertEquals("318° NO", headingText(318.4))
        assertEquals("000° N", headingText(359.7))
        assertEquals("005° N", headingText(5.0))
    }

    @Test
    fun `only medium or high accuracy is trusted`() {
        assertEquals(listOf(CompassTrust.CALIBRATE, CompassTrust.CALIBRATE, CompassTrust.CALIBRATE, CompassTrust.GOOD, CompassTrust.GOOD), listOf(-1, 0, 1, 2, 3).map(::compassTrust))
    }

    @Test
    fun `an untrusted compass asks for the figure eight`() {
        assertNull(compassWarningLabel(null))
        assertNull(compassWarningLabel(CompassReading(10.0, CompassTrust.GOOD)))
        assertEquals("Brújula: calibra (mueve en 8)", compassWarningLabel(CompassReading(10.0, CompassTrust.CALIBRATE)))
    }
}
```

`android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/compass/CompassRingTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.compass

import io.github.santiquiroz.blindside.shared.radar.PointPx
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CompassRingTest {
    @Test
    fun `ticks every fifteen degrees leave room for the four letters`() {
        val ticks = compassTicks()
        assertEquals(20, ticks.size)
        assertEquals(emptyList<CompassTick>(), ticks.filter { it.angleDeg.toInt() % 90 == 0 })
        assertEquals(listOf(45f, 135f, 225f, 315f), ticks.filter { it.major }.map { it.angleDeg })
    }

    @Test
    fun `the letters are the spanish cardinals`() {
        assertEquals(listOf("N" to 0f, "E" to 90f, "S" to 180f, "O" to 270f), CARDINAL_MARKS.map { it.label to it.angleDeg })
    }

    @Test
    fun `facing east puts north at nine o'clock`() {
        assertEquals(270f, markScreenAngleDeg(0f, 90.0), 1e-4f)
        assertEquals(0f, markScreenAngleDeg(90f, 90.0), 1e-4f)
    }

    @Test
    fun `ring points go clockwise from twelve o'clock`() {
        val center = PointPx(100f, 100f)
        assertEquals(PointPx(100f, 50f), rounded(pointOnRing(center, 50f, 0f)))
        assertEquals(PointPx(150f, 100f), rounded(pointOnRing(center, 50f, 90f)))
        assertEquals(PointPx(50f, 100f), rounded(pointOnRing(center, 50f, 270f)))
    }

    private fun rounded(point: PointPx) = PointPx(Math.round(point.x * 1000f) / 1000f, Math.round(point.y * 1000f) / 1000f)
}
```

`android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/compass/CompassColorsTest.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.compass

import io.github.santiquiroz.blindside.shared.settings.ScreenMode
import io.github.santiquiroz.blindside.shared.theme.BlindsideColors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CompassColorsTest {
    @Test
    fun `stealth keeps north on the dim accent`() {
        assertEquals(BlindsideColors.AccentDim, compassColors(ScreenMode.SIGILO, CompassTrust.GOOD).north)
        assertEquals(BlindsideColors.Accent, compassColors(ScreenMode.VISTA, CompassTrust.GOOD).north)
    }

    @Test
    fun `an uncalibrated ring fades but the front index stays solid`() {
        val faded = compassColors(ScreenMode.VISTA, CompassTrust.CALIBRATE)
        assertEquals(CALIBRATE_ALPHA, faded.tick.alpha, 1e-3f)
        assertEquals(CALIBRATE_ALPHA, faded.north.alpha, 1e-3f)
        assertEquals(1f, faded.index.alpha, 1e-3f)
    }
}
```

`AppSettingsTest.kt`, add:

```kotlin
    @Test
    fun `the compass ring is on by default`() {
        assertEquals(true, AppSettings().compass)
    }
```

`SettingsPreferencesTest.kt`: in `every field survives a write and read`, add `compass = false,` to the `AppSettings(...)` after the `contactColor = ContactColor.RED,` line.

- [ ] **Step 2: Run the tests and watch them fail**

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest`
Expected: compilation FAILS on `fitFan`, `fanHalfAngleDeg`, `edgeMarginPx`, the `compass` package and `AppSettings.compass`.

- [ ] **Step 3: Implement the pure parts in `android-shared`**

`android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/radar/FanLayout.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.radar

import io.github.santiquiroz.blindside.core.scene.CoverageSector
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class FanFit(val originYOffsetPx: Float, val radiusPx: Float)

const val MIN_FIT_HALF_ANGLE_DEG = 45.0
const val MAX_FIT_HALF_ANGLE_DEG = 90.0

fun fanHalfAngleDeg(sectors: List<CoverageSector>): Double =
    (sectors.flatMap { listOf(abs(it.fromDeg), abs(it.toDeg)) }.maxOrNull() ?: MAX_FIT_HALF_ANGLE_DEG)
        .coerceIn(MIN_FIT_HALF_ANGLE_DEG, MAX_FIT_HALF_ANGLE_DEG)

// Largest fan on a round screen: dropping the origin by L·cot(A) lets the flank edge and the 6 m arc both touch the usable circle.
fun fitFan(sidePx: Float, edgeMarginPx: Float, halfAngleDeg: Double): FanFit {
    val limit = (sidePx / 2f - edgeMarginPx).coerceAtLeast(0f).toDouble()
    val radians = Math.toRadians(halfAngleDeg.coerceIn(MIN_FIT_HALF_ANGLE_DEG, MAX_FIT_HALF_ANGLE_DEG))
    return FanFit(
        originYOffsetPx = (limit * cos(radians) / sin(radians)).toFloat(),
        radiusPx = (limit / sin(radians)).toFloat(),
    )
}
```

`RadarGeometry.kt`:
- Delete the constants `ORIGIN_Y_FRACTION` and `RADIUS_FRACTION` (keep `BLIP_RADIUS_FRACTION`).
- Replace the signature and the first lines of `toDrawModel` with:

```kotlin
fun toDrawModel(
    scene: RadarScene?,
    widthPx: Float,
    heightPx: Float,
    offset: PointPx,
    showContacts: Boolean,
    edgeMarginPx: Float = 0f,
): RadarDrawModel {
    val side = min(widthPx, heightPx)
    val fit = fitFan(side, edgeMarginPx, fanHalfAngleDeg(scene?.coverage.orEmpty()))
    val origin = PointPx(widthPx / 2f + offset.x, heightPx / 2f + fit.originYOffsetPx + offset.y)
    val radius = fit.radiusPx
```

The rest of the function is unchanged.

`android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/compass/CompassMath.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.compass

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class CompassTrust { GOOD, CALIBRATE }

data class CompassReading(val azimuthDeg: Double, val trust: CompassTrust)

const val HEADING_TAU_MS = 150.0
const val CALIBRATE_WARNING = "Brújula: calibra (mueve en 8)"

private const val FULL_TURN_DEG = 360.0
private const val HALF_TURN_DEG = 180.0
private const val CARDINAL_SECTOR_DEG = 45.0
private const val SENSOR_ACCURACY_MEDIUM = 2
private val CARDINALS = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")

fun azimuthFromRotationVector(values: FloatArray): Double {
    val x = values[0].toDouble()
    val y = values[1].toDouble()
    val z = values[2].toDouble()
    val w = if (values.size > 3) values[3].toDouble() else scalarPart(x, y, z)
    // Same as SensorManager.getOrientation()[0]: heading of the 12 o'clock axis in the East-North-Up frame.
    val east = 2.0 * (x * y - z * w)
    val north = 1.0 - 2.0 * (x * x + z * z)
    return normalizedDeg(Math.toDegrees(atan2(east, north)))
}

fun frontHeadingDeg(azimuthDeg: Double, postureRotationDeg: Float): Double =
    normalizedDeg(azimuthDeg + postureRotationDeg)

fun smoothedHeadingDeg(previousDeg: Double?, sampleDeg: Double, dtMs: Long, tauMs: Double = HEADING_TAU_MS): Double {
    if (previousDeg == null) return normalizedDeg(sampleDeg)
    val alpha = 1.0 - exp(-dtMs.coerceAtLeast(0L) / tauMs)
    return normalizedDeg(previousDeg + alpha * shortestTurnDeg(previousDeg, sampleDeg))
}

fun shortestTurnDeg(fromDeg: Double, toDeg: Double): Double {
    val raw = normalizedDeg(toDeg - fromDeg)
    return if (raw > HALF_TURN_DEG) raw - FULL_TURN_DEG else raw
}

fun normalizedDeg(deg: Double): Double {
    val wrapped = deg % FULL_TURN_DEG
    return if (wrapped < 0.0) wrapped + FULL_TURN_DEG else wrapped
}

fun cardinalLabel(headingDeg: Double): String {
    val index = ((normalizedDeg(headingDeg) + CARDINAL_SECTOR_DEG / 2.0) / CARDINAL_SECTOR_DEG).toInt() % CARDINALS.size
    return CARDINALS[index]
}

fun headingText(headingDeg: Double): String {
    val whole = normalizedDeg(headingDeg).roundToInt() % FULL_TURN_DEG.toInt()
    return String.format(Locale.ROOT, "%03d° %s", whole, cardinalLabel(headingDeg))
}

fun compassTrust(accuracy: Int): CompassTrust =
    if (accuracy >= SENSOR_ACCURACY_MEDIUM) CompassTrust.GOOD else CompassTrust.CALIBRATE

fun compassWarningLabel(reading: CompassReading?): String? =
    if (reading?.trust == CompassTrust.CALIBRATE) CALIBRATE_WARNING else null

private fun scalarPart(x: Double, y: Double, z: Double): Double = sqrt((1.0 - x * x - y * y - z * z).coerceAtLeast(0.0))
```

`android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/compass/CompassRing.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.compass

import io.github.santiquiroz.blindside.shared.radar.PointPx
import kotlin.math.cos
import kotlin.math.sin

data class CompassTick(val angleDeg: Float, val major: Boolean)

data class CardinalMark(val label: String, val angleDeg: Float)

private const val TICK_STEP_DEG = 15
private const val LETTER_STEP_DEG = 90
private const val MAJOR_STEP_DEG = 45

val CARDINAL_MARKS = listOf(CardinalMark("N", 0f), CardinalMark("E", 90f), CardinalMark("S", 180f), CardinalMark("O", 270f))

fun compassTicks(): List<CompassTick> =
    (0 until 360 step TICK_STEP_DEG)
        .filter { it % LETTER_STEP_DEG != 0 }
        .map { CompassTick(it.toFloat(), major = it % MAJOR_STEP_DEG == 0) }

// The ring turns against the watch azimuth, so on the glass each mark points at its real direction in every posture.
fun markScreenAngleDeg(markAngleDeg: Float, azimuthDeg: Double): Float =
    normalizedDeg(markAngleDeg - azimuthDeg).toFloat()

fun pointOnRing(center: PointPx, radiusPx: Float, angleDeg: Float): PointPx {
    val radians = Math.toRadians(angleDeg.toDouble())
    return PointPx(center.x + (radiusPx * sin(radians)).toFloat(), center.y - (radiusPx * cos(radians)).toFloat())
}
```

`android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/compass/CompassColors.kt`:

```kotlin
package io.github.santiquiroz.blindside.shared.compass

import androidx.compose.ui.graphics.Color
import io.github.santiquiroz.blindside.shared.settings.ScreenMode
import io.github.santiquiroz.blindside.shared.theme.BlindsideColors

data class CompassColors(val tick: Color, val major: Color, val north: Color, val letter: Color, val index: Color)

const val CALIBRATE_ALPHA = 0.4f

fun compassColors(screenMode: ScreenMode, trust: CompassTrust): CompassColors {
    val alpha = if (trust == CompassTrust.GOOD) 1f else CALIBRATE_ALPHA
    val north = if (screenMode == ScreenMode.VISTA) BlindsideColors.Accent else BlindsideColors.AccentDim
    return CompassColors(
        tick = BlindsideColors.Ring.copy(alpha = alpha),
        major = BlindsideColors.AccentDim.copy(alpha = alpha),
        north = north.copy(alpha = alpha),
        letter = BlindsideColors.Text2.copy(alpha = alpha),
        index = BlindsideColors.Accent,
    )
}
```

`AppSettings.kt`: add `val compass: Boolean = true,` as the last field of `data class AppSettings`, after `val contactColor: ContactColor = ContactColor.GREEN,`.

`SettingsPreferences.kt`:
- In `Keys`, add `val COMPASS = booleanPreferencesKey("compass")`.
- In `settingsFrom`, add `compass = prefs[Keys.COMPASS] ?: defaults.compass,` after the `contactColor` line.
- In `writeSettings`, add `prefs[Keys.COMPASS] = settings.compass` after the `CONTACT_COLOR` line.

- [ ] **Step 4: Run the shared tests and watch them pass**

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`. `android-shared` gains 23 tests over the Task 16 count: FanLayout 5, CompassMath 10, CompassRing 4, CompassColors 2, AppSettings 1, and RadarGeometry +1 (one test replaced by two).

- [ ] **Step 5: Wire the sensor, the ring and the setting into `wear-app`**

`wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/CompassSensor.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui.radar

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.santiquiroz.blindside.shared.compass.CompassReading
import io.github.santiquiroz.blindside.shared.compass.azimuthFromRotationVector
import io.github.santiquiroz.blindside.shared.compass.compassTrust
import io.github.santiquiroz.blindside.shared.compass.smoothedHeadingDeg

private const val NANOS_PER_MS = 1_000_000L

@Composable
fun rememberCompassReading(active: Boolean): State<CompassReading?> {
    val context = LocalContext.current
    val reading = remember { mutableStateOf<CompassReading?>(null) }
    DisposableEffect(active) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (!active || manager == null || sensor == null) {
            reading.value = null
            return@DisposableEffect onDispose { }
        }
        val listener = CompassListener { reading.value = it }
        // UI rate only while the radar is on screen: ambient and Sigilo screen-off unregister it, so the fusion costs nothing in a long game.
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { manager.unregisterListener(listener) }
    }
    return reading
}

private class CompassListener(private val emit: (CompassReading) -> Unit) : SensorEventListener {
    private var smoothedDeg: Double? = null
    private var lastNanos: Long? = null

    override fun onSensorChanged(event: SensorEvent) {
        val dtMs = lastNanos?.let { (event.timestamp - it) / NANOS_PER_MS } ?: 0L
        lastNanos = event.timestamp
        val next = smoothedHeadingDeg(smoothedDeg, azimuthFromRotationVector(event.values), dtMs)
        smoothedDeg = next
        emit(CompassReading(next, compassTrust(event.accuracy)))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
```

`wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/CompassRingCanvas.kt`:

```kotlin
package io.github.santiquiroz.blindside.wear.ui.radar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.santiquiroz.blindside.shared.compass.CARDINAL_MARKS
import io.github.santiquiroz.blindside.shared.compass.CardinalMark
import io.github.santiquiroz.blindside.shared.compass.CompassColors
import io.github.santiquiroz.blindside.shared.compass.CompassTick
import io.github.santiquiroz.blindside.shared.compass.compassTicks
import io.github.santiquiroz.blindside.shared.compass.markScreenAngleDeg
import io.github.santiquiroz.blindside.shared.compass.pointOnRing
import io.github.santiquiroz.blindside.shared.radar.PointPx
import io.github.santiquiroz.blindside.shared.theme.BlindsideFonts

private const val MINOR_TICK_FRACTION = 0.3f
private const val MAJOR_TICK_FRACTION = 0.5f
private const val TICK_STROKE_PX = 2f
private const val INDEX_STROKE_PX = 5f
private val LETTER_SIZE = 10.sp

data class RingGeometry(val center: PointPx, val outerRadiusPx: Float, val bandPx: Float)

fun DrawScope.drawCompassRing(
    azimuthDeg: Double,
    ring: RingGeometry,
    postureRotationDeg: Float,
    colors: CompassColors,
    measurer: TextMeasurer,
) {
    compassTicks().forEach { drawTick(it, azimuthDeg, ring, colors) }
    CARDINAL_MARKS.forEach { drawCardinal(it, azimuthDeg, ring, postureRotationDeg, colors, measurer) }
    drawFrontIndex(ring, postureRotationDeg, colors.index)
}

private fun DrawScope.drawTick(tick: CompassTick, azimuthDeg: Double, ring: RingGeometry, colors: CompassColors) {
    val angle = markScreenAngleDeg(tick.angleDeg, azimuthDeg)
    val length = ring.bandPx * if (tick.major) MAJOR_TICK_FRACTION else MINOR_TICK_FRACTION
    drawLine(
        color = if (tick.major) colors.major else colors.tick,
        start = offsetOf(pointOnRing(ring.center, ring.outerRadiusPx, angle)),
        end = offsetOf(pointOnRing(ring.center, ring.outerRadiusPx - length, angle)),
        strokeWidth = TICK_STROKE_PX,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawCardinal(
    mark: CardinalMark,
    azimuthDeg: Double,
    ring: RingGeometry,
    postureRotationDeg: Float,
    colors: CompassColors,
    measurer: TextMeasurer,
) {
    val color = if (mark.label == "N") colors.north else colors.letter
    val layout = measurer.measure(mark.label, TextStyle(color = color, fontSize = LETTER_SIZE, fontFamily = BlindsideFonts.Mono, fontWeight = FontWeight.Bold))
    val anchor = offsetOf(pointOnRing(ring.center, ring.outerRadiusPx - ring.bandPx / 2f, markScreenAngleDeg(mark.angleDeg, azimuthDeg)))
    val topLeft = Offset(anchor.x - layout.size.width / 2f, anchor.y - layout.size.height / 2f)
    // Letters stay upright for the eye that reads the watch in this posture, not for the glass.
    rotate(postureRotationDeg, pivot = anchor) { drawText(layout, topLeft = topLeft) }
}

private fun DrawScope.drawFrontIndex(ring: RingGeometry, postureRotationDeg: Float, color: Color) {
    val inner = ring.outerRadiusPx - ring.bandPx
    drawLine(
        color = color,
        start = offsetOf(pointOnRing(ring.center, inner, postureRotationDeg)),
        end = offsetOf(pointOnRing(ring.center, inner + ring.bandPx * MINOR_TICK_FRACTION, postureRotationDeg)),
        strokeWidth = INDEX_STROKE_PX,
        cap = StrokeCap.Round,
    )
}

private fun offsetOf(point: PointPx) = Offset(point.x, point.y)
```

`RadarScreen.kt` (as Task 15 left it):
- Add the imports `androidx.compose.ui.platform.LocalDensity`, `androidx.compose.ui.text.rememberTextMeasurer`, `androidx.compose.foundation.shape.RoundedCornerShape`, `io.github.santiquiroz.blindside.shared.compass.CompassReading`, `io.github.santiquiroz.blindside.shared.compass.compassColors`, `io.github.santiquiroz.blindside.shared.compass.compassWarningLabel`, `io.github.santiquiroz.blindside.shared.compass.frontHeadingDeg` and `io.github.santiquiroz.blindside.shared.compass.headingText`.
- Add the constants `private val COMPASS_BAND = 16.dp` and `private val PANEL_BOTTOM_PADDING = 22.dp`.
- Replace the body of `RadarScreen` from `val rotationDeg = settings.posture.rotationDeg` to the end with:

```kotlin
    val rotationDeg = settings.posture.rotationDeg
    val compassOn = settings.compass && !ambient
    val compass by rememberCompassReading(compassOn)
    val reading = compass.takeIf { compassOn }
    val bandPx = with(LocalDensity.current) { COMPASS_BAND.toPx() }
    val measurer = rememberTextMeasurer()
    Box(Modifier.fillMaxSize().background(BlindsideColors.Bg)) {
        Canvas(Modifier.fillMaxSize()) {
            val margin = if (compassOn) bandPx else 0f
            val pivot = screenCenter(size.width, size.height, shift)
            val logical = toDrawModel(session.scene, size.width, size.height, shift, contacts, margin)
            drawRadar(logical.rotatedAbout(pivot, rotationDeg), radarColorsFor(settings.contactColor))
            reading?.let {
                val ring = RingGeometry(pivot, size.minDimension / 2f, bandPx)
                drawCompassRing(it.azimuthDeg, ring, rotationDeg, compassColors(settings.screenMode, it.trust), measurer)
            }
        }
        RadarOverlay(session, ambient, shift, rotationDeg, reading, onToggleEliminated)
    }
}
```

- Change `RadarOverlay` to take `reading: CompassReading?` after `rotationDeg: Float`. Inside its `Box(placement)`, after the `BottomPanel` line, add:

```kotlin
        reading?.let { HeadingWindow(headingText(frontHeadingDeg(it.azimuthDeg, rotationDeg)), Modifier.align(Alignment.BottomCenter)) }
```

  Also change the `BottomPanel(...)` call to `BottomPanel(session, compassWarningLabel(reading), onToggleEliminated, Modifier.align(Alignment.BottomCenter))`.
- Change `BottomPanel` to `private fun BottomPanel(session: SessionUiState, compassWarning: String?, onToggleEliminated: () -> Unit, modifier: Modifier)`. Change its `padding(bottom = 12.dp)` to `padding(bottom = PANEL_BOTTOM_PADDING)`, and add as the first line inside its `Column`:

```kotlin
        compassWarning?.let { Text(it, color = BlindsideColors.Warn, fontSize = 11.sp, maxLines = 1) }
```

- Add:

```kotlin
@Composable
private fun HeadingWindow(text: String, modifier: Modifier) {
    // A window in the bezel at the rear, like a dive watch date: the letters pass under it and the fan keeps the front.
    Text(
        text,
        modifier.padding(bottom = 4.dp).background(BlindsideColors.Bg, RoundedCornerShape(6.dp)).padding(horizontal = 4.dp),
        color = BlindsideColors.Text,
        fontFamily = BlindsideFonts.Mono,
        fontSize = 11.sp,
        maxLines = 1,
    )
}
```

`SettingsScreen.kt`: after the `item { SettingChip("Color de contactos", ...) }` line, add:

```kotlin
        item { SettingChip("Brújula", yesNo(settings.compass)) { onUpdate { it.copy(compass = !it.compass) } } }
```

- [ ] **Step 6: Build and run the whole watch suite**

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest :wear-app:testDebugUnitTest :wear-app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. `android-shared` is up 23 over Task 16 and `wear-app` is unchanged. **Update Task 17's expected `android-shared` count by +23.** If `DeviceRotationTest` fails, the cause is the centred origin. Its assertions compare angles about the origin and the pivot, so they must hold; fix the code, not the test.

- [ ] **Step 7: Device checklist**

In `watch/wear-app/README.md`, under **Fase 2: reloj y celular**, append:

```markdown
25. [ ] **Pantalla completa y brújula:**
    - Con el cinturón diestro, el abanico nace en el centro de la pantalla y llega hasta el anillo de la brújula. La mitad de atrás muestra el estado y "Eliminado".
    - El anillo gira al girar el cuerpo: la N señala el norte real y la raya verde de arriba marca el frente. La ventanita de abajo muestra el rumbo (por ejemplo `318° NO`).
    - Comparar el rumbo con la brújula del celular: ±15° lejos de metales.
    - Con la brújula sin calibrar, el anillo se ve tenue y sale "Brújula: calibra (mueve en 8)". Después de mover el reloj en ocho, desaparece.
    - En postura táctica, las letras se leen derechas para quien mira y la raya de frente sigue al abanico.
    - En ambiente o con la pantalla apagada (Sigilo) no hay anillo ni rumbo.
    - Ajustes → "Brújula: no" quita el anillo y el abanico crece hasta el borde.
    - Si el reloj reporta siempre precisión baja con el sensor de rotación, anotarlo como desviación: el anillo quedaría tenue todo el tiempo.
```

- [ ] **Step 8: Commit**

```bash
cd C:/personal/blindside
git add watch/android-shared watch/wear-app
git commit -m "feat: radar a pantalla completa con anillo de brújula en el bisel y ajuste para desactivarlo"
```

---

## Part D — Gates (tests, then the watch regression on the hardware, spec §7)

### Task 17: Whole-branch gate

This task commits nothing unless a check fails and you fix it. Any fix gets its own `fix:` commit.

**Files:** none new.

**Interfaces:**
- Consumes: everything above.
- Produces: a green branch and the debug APK `watch/wear-app/build/outputs/apk/debug/wear-app-debug.apk` that Task 18 installs on the watch.

- [ ] **Step 1: Every test and both builds**

```bash
cd C:/personal/blindside/watch && ./gradlew :radar-core:cleanTest :android-shared:cleanTestDebugUnitTest :wear-app:cleanTestDebugUnitTest :radar-core:test :android-shared:testDebugUnitTest :wear-app:testDebugUnitTest :wear-app:assembleDebug --console=plain
```

Expected: `BUILD SUCCESSFUL`. The test-count one-liner prints `android-shared 269` and `wear-app 47`. `radar-core` is still 254: run `cat radar-core/build/test-results/test/*.xml | grep -o '<testsuite [^>]*' | grep -o ' tests="[0-9]*"' | grep -o '[0-9]*' | awk '{s+=$1} END {print s+0}'`.

- [ ] **Step 2: Structural checks**

```bash
cd C:/personal/blindside/watch
grep -rn 'blindside\.wear\.' android-shared/src || echo "library never reaches into the app"
grep -rln 'androidx\.wear' android-shared/src || echo "library has no Wear UI dependency"
grep -rn '/\*\*' android-shared/src wear-app/src || echo "no doc comments"
find android-shared/src wear-app/src -name '*.kt' -exec wc -l {} + | sort -n | tail -3
# Deviation D1: every module that uses the library needs minSdk 33 or more.
for f in */build.gradle.kts; do
  grep -q 'project(":android-shared")' "$f" || continue
  sdk=$(grep -oE 'minSdk *= *[0-9]+' "$f" | grep -oE '[0-9]+$')
  printf '%s minSdk %s\n' "$f" "${sdk:-?}"
  [ "${sdk:-0}" -ge 33 ] || echo "BLOCKING: $f declares minSdk ${sdk:-?}, below the library's 33 (Deviation D1)"
done
cd C:/personal/blindside && git log main..HEAD --format=%B | grep -i 'co-authored' || echo "no co-author lines"
```

Expected:
- The four `echo` lines print. (`play-services-wearable` is `com.google.android.gms`, not `androidx.wear`: the library may use the Data Layer, never Wear Compose.)
- The longest Kotlin file is 400 lines or fewer. Today `BeltLink.kt` has about 310.
- The `minSdk` loop prints `wear-app/build.gradle.kts minSdk 34` and no `BLOCKING` line. If a `phone-app` already exists in this tree with 31, report it as Cross-plan contract item 1; do not change plan 06's module from here.

---

### Task 18: Watch regression E2E on the hardware (spec §7 steps 1-2), the merge gate

Spec §7 runs over ADB and the serial port without Santiago. This task flashes the belt with the firmware on `main`, installs this branch's watch app, starts it, and reads the belt's `diag` line. Within 30 s, the line must show a trusted, subscribed watch link whose `sent` counter rises. A pass is committed as evidence, and the orchestrator merges the branch after reading it. A missing device or a busy belt is **E2E PENDING**: the plan is not failed, and the branch stays unmerged. Once the new app is installed, any verdict other than PASS puts the watch back on the app it had before.

**Files:**
- Create: `e2e/watch_regression.py`, `e2e/test_watch_regression.py`
- Create (only on PASS): `docs/superpowers/e2e/2026-10-01-p2-05-watch-regression.md`

**Interfaces:**
- Consumes:
  - the debug APK from Task 17;
  - the firmware on `main` (`firmware/`, PlatformIO env `esp32dev`, 115200 baud);
  - the two `diag` formats: firmware 0.1.0 (`link[conn=… sub=… trusted=… …] tx[sent=N …]`) and firmware 0.2.0 from plan 04 (`link0[role=watch trusted=1 sub=1 … sent=N dropped=N]`);
  - the launcher activity `io.github.santiquiroz.blindside/io.github.santiquiroz.blindside.wear.MainActivity`.
- Produces:
  - `watch_link_in(line) -> WatchLink | None` and `verdict(readings) -> str` (`PASS …` or `FAIL …`).
  - The CLI `python e2e/watch_regression.py --watch <adb serial> [--port COM6]`. It prints every `diag` line with its time since the app start, then one `REGRESSION PASS|FAIL …` line. It exits 0 only on PASS.
  - Plan 06 / a later E2E plan can reuse it for spec §7 step 6.

- [ ] **Step 1: Write the failing test**

Create `e2e/test_watch_regression.py`:

```python
import unittest

from watch_regression import Reading, WatchLink, verdict, watch_link_in

DUAL = (
    "diag up=40s radar0[alive=1] imu0[ok=1] "
    "link0[role=watch trusted=1 sub=1 mtu=255 itvl=36 lat=0 sup=400 sent=1234 dropped=0] link1[-] bonds=1 disc=19 "
    "tx[fail=0 dropped=0 skipped=0]"
)
MVP = (
    "diag up=40s radar0[alive=1] imu0[ok=1] "
    "link[conn=1 sub=1 trusted=1 mtu=255 itvl=36 lat=0 sup=400 disc=19] tx[sent=812 fail=0 dropped=0 skipped=0]"
)


def good(seconds, sent):
    return Reading(seconds, WatchLink(trusted=True, subscribed=True, sent=sent))


class WatchLinkTest(unittest.TestCase):
    def test_the_dual_link_format_is_read(self):
        self.assertEqual(WatchLink(True, True, 1234), watch_link_in(DUAL))

    def test_the_mvp_link_format_is_read(self):
        self.assertEqual(WatchLink(True, True, 812), watch_link_in(MVP))

    def test_lines_without_a_watch_link_are_skipped(self):
        self.assertIsNone(watch_link_in("diag up=5s link0[-] link1[-] bonds=1 tx[fail=0 dropped=0 skipped=0]"))
        self.assertIsNone(watch_link_in("diag up=5s link0[role=phone trusted=1 sub=1 sent=9 dropped=0] link1[-]"))
        self.assertIsNone(watch_link_in("blindside passkey: 123456"))


class VerdictTest(unittest.TestCase):
    def test_a_trusted_link_with_rising_sent_passes(self):
        self.assertTrue(verdict([good(12.0, 10), good(17.0, 60)]).startswith("PASS"))

    def test_a_link_that_comes_up_after_30_seconds_fails(self):
        self.assertTrue(verdict([good(31.0, 10), good(36.0, 60)]).startswith("FAIL"))

    def test_a_link_that_never_sends_more_fails(self):
        self.assertTrue(verdict([good(12.0, 10), good(17.0, 10)]).startswith("FAIL"))

    def test_an_untrusted_or_unsubscribed_link_fails(self):
        readings = [Reading(5.0, WatchLink(False, True, 10)), Reading(10.0, WatchLink(True, False, 20))]
        self.assertTrue(verdict(readings).startswith("FAIL"))


if __name__ == "__main__":
    unittest.main()
```

Run: `cd C:/personal/blindside && python -m unittest discover -s e2e -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'watch_regression'`.

- [ ] **Step 2: Write the script**

Create `e2e/watch_regression.py`:

```python
import argparse
import re
import subprocess
import sys
import time
from dataclasses import dataclass

LINK_WINDOW_S = 30.0
DIAG_PERIOD_S = 5.0
BOOT_WAIT_S = 15.0
BAUD = 115200
APP_PACKAGE = "io.github.santiquiroz.blindside"
APP_COMPONENT = APP_PACKAGE + "/io.github.santiquiroz.blindside.wear.MainActivity"

DUAL_LINK = re.compile(r"link\d\[role=watch trusted=(\d) sub=(\d)[^\]]* sent=(\d+)")
MVP_LINK = re.compile(r" link\[conn=\d sub=(\d) trusted=(\d)")
MVP_SENT = re.compile(r" tx\[sent=(\d+)")


@dataclass(frozen=True)
class WatchLink:
    trusted: bool
    subscribed: bool
    sent: int


@dataclass(frozen=True)
class Reading:
    seconds: float
    link: WatchLink


def watch_link_in(line):
    return dual_watch_link(line) or mvp_watch_link(line)


def dual_watch_link(line):
    match = DUAL_LINK.search(line)
    if match is None:
        return None
    return WatchLink(match.group(1) == "1", match.group(2) == "1", int(match.group(3)))


# Firmware 0.1.0 has one link and no role: that link is the watch.
def mvp_watch_link(line):
    link = MVP_LINK.search(line)
    sent = MVP_SENT.search(line)
    if link is None or sent is None:
        return None
    return WatchLink(link.group(2) == "1", link.group(1) == "1", int(sent.group(1)))


def verdict(readings):
    streaming = [r for r in readings if r.link.trusted and r.link.subscribed]
    if not streaming or streaming[0].seconds > LINK_WINDOW_S:
        return "FAIL no trusted, subscribed watch link within 30 s"
    first = streaming[0]
    rising = [r for r in streaming[1:] if r.link.sent > first.link.sent]
    if not rising:
        return "FAIL the watch link never sent more packets"
    return f"PASS trusted+sub at {first.seconds:.0f} s, sent {first.link.sent} -> {rising[0].link.sent}"


def open_port(name):
    import serial

    port = serial.Serial()
    port.port = name
    port.baudrate = BAUD
    port.timeout = 1
    # Deasserted DTR and RTS keep the DevKit's auto-reset circuit from rebooting the belt.
    port.dtr = False
    port.rts = False
    port.open()
    return port


def read_line(port):
    return port.readline().decode("utf-8", errors="replace").strip()


def wait_for_diag(port):
    end = time.monotonic() + BOOT_WAIT_S
    while time.monotonic() < end:
        if read_line(port).startswith("diag "):
            return True
    return False


def start_watch_app(device):
    shell = ["adb", "-s", device, "shell"]
    subprocess.run(shell + ["input", "keyevent", "KEYCODE_WAKEUP"], check=True)
    subprocess.run(shell + ["am", "force-stop", APP_PACKAGE], check=True)
    subprocess.run(shell + ["am", "start", "-n", APP_COMPONENT], check=True)


def reading_from(line, seconds):
    link = watch_link_in(line)
    return None if link is None else Reading(seconds, link)


def collect(port, started):
    readings = []
    while time.monotonic() - started < LINK_WINDOW_S + DIAG_PERIOD_S + 1:
        line = read_line(port)
        seconds = time.monotonic() - started
        if line.startswith("diag "):
            print(f"[{seconds:5.1f} s] {line}", flush=True)
            readings.append(reading_from(line, seconds))
    return [r for r in readings if r is not None]


def run(port, device):
    if not wait_for_diag(port):
        return "FAIL the belt printed no diag line"
    started = time.monotonic()
    start_watch_app(device)
    return verdict(collect(port, started))


def main(argv):
    parser = argparse.ArgumentParser(description="Spec section 7 step 2: the watch alone reaches a trusted, streaming link.")
    parser.add_argument("--port", default="COM6")
    parser.add_argument("--watch", required=True, help="adb serial of the watch")
    args = parser.parse_args(argv)
    port = open_port(args.port)
    try:
        result = run(port, args.watch)
    finally:
        port.close()
    print(f"REGRESSION {result}", flush=True)
    return 0 if result.startswith("PASS") else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
```

Run: `cd C:/personal/blindside && python -m unittest discover -s e2e -v`
Expected: `Ran 7 tests` and `OK`.

- [ ] **Step 3: Commit the tool**

```bash
cd C:/personal/blindside
git add e2e/watch_regression.py e2e/test_watch_regression.py
git commit -m "test: E2E de regresión del reloj (spec §7 paso 2) por ADB y puerto serie"
```

- [ ] **Step 4: Take the hardware and find the devices**

Only one plan may drive the belt at a time, so the lock is a directory, created atomically by `mkdir`.

```bash
LOCK=/c/personal/.blindside-hardware.lock
find "$LOCK" -maxdepth 0 -mmin +30 2>/dev/null | grep -q . && rmdir "$LOCK" && echo "removed a stale lock"
mkdir "$LOCK" 2>/dev/null && echo "hardware lock taken" || echo "HARDWARE BUSY: $LOCK exists"
python -m serial.tools.list_ports | grep -qE '^COM6[[:space:]]*$' && echo "belt on COM6" || echo "NO BELT on COM6"
for s in $(adb devices | awk 'NR > 1 && $2 == "device" {print $1}'); do
  adb -s "$s" shell getprop ro.build.characteristics | grep -q watch && { echo "WATCH=$s"; break; }
done
```

Expected: `hardware lock taken`, `belt on COM6` and `WATCH=<serial>`. Write the serial down; every later step sets `W=<serial>` itself, because shell variables do not survive between steps.
- `HARDWARE BUSY`: another plan's E2E holds the belt. Stop here and report `E2E PENDING: hardware busy`. Do not touch the lock; the orchestrator reruns Task 18 later.
- `NO BELT on COM6`, or no `WATCH=` line: run `rmdir /c/personal/.blindside-hardware.lock`, stop, and report `E2E PENDING: <what is missing>`.

- [ ] **Step 5: Keep the watch's current app, then install this branch's app**

Git Bash rewrites a POSIX argument such as `/data/app/…` before `adb.exe` sees it. So the remote path goes through `MSYS_NO_PATHCONV=1`, and the local path is a `C:/…` path.

```bash
cd C:/personal/blindside/watch
W=<watch serial from Step 4>
SAVED="$(cygpath -m /tmp)/blindside-watch-before.apk"
BEFORE=$(adb -s "$W" shell pm path io.github.santiquiroz.blindside | tr -d '\r' | sed -n 's/^package://p' | head -1)
MSYS_NO_PATHCONV=1 adb -s "$W" pull "$BEFORE" "$SAVED"
ls -l "$SAVED"
adb -s "$W" install -r wear-app/build/outputs/apk/debug/wear-app-debug.apk
```

Expected: `ls` shows a non-empty APK, and `install` prints `Success`.
- Without an app on the watch (`BEFORE` empty), skip the pull. There is then nothing to restore in Step 7.
- On `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (another signing key) or `INSTALL_FAILED_VERSION_DOWNGRADE`: **never uninstall**, because that would erase the saved belt address and settings. Release the lock, stop, and report `E2E PENDING: <the install error>`.

- [ ] **Step 6: Flash the firmware on `main` (spec §7 step 1)**

```bash
cd C:/personal/blindside
FW=$(mktemp -d)
git archive main firmware | tar -x -C "$FW"
mkdir -p "$FW/firmware/.pio" && { cp -r firmware/.pio/libdeps "$FW/firmware/.pio/" 2>/dev/null || true; }
git show main:firmware/include/blindside_config.h | grep kFirmwareVersion
python -m platformio run -d "$(cygpath -m "$FW/firmware")" -e esp32dev -t upload --upload-port COM6
```

Expected: the firmware version line (`0.1.0` until plan 04 merges, then `0.2.0`), then `[SUCCESS]`.
- The upload rewrites only the app partition. NVS keeps the watch's bond, so no passkey is needed.
- `PlatformIO` must not be run from the plan's working tree: `main` may hold a newer `firmware/` than this branch.
- If the upload fails (port busy, no network to fetch NimBLE-Arduino), release the lock, stop, and report `E2E PENDING: flash failed` with the last 20 lines.

- [ ] **Step 7: The regression (spec §7 step 2)**

```bash
cd C:/personal/blindside
W=<watch serial from Step 4>
python e2e/watch_regression.py --watch "$W" --port COM6 > /tmp/blindside-watch-regression.log 2>&1; echo "exit $?"
cat /tmp/blindside-watch-regression.log
```

Expected: `diag` lines in which the watch link turns `trusted=1 sub=1`, then `REGRESSION PASS trusted+sub at <N> s, sent <A> -> <B>` with N ≤ 30, and `exit 0`.

On `REGRESSION FAIL`:
1. Save the evidence: `adb -s "$W" logcat -d -s BeltGatt BeltLink BlindsideService | tail -60`.
2. Run Step 7 once more. A first scan after a flash can be slow; a second failure is real. Never run it a third time.
3. On a second failure, report `E2E FAIL` with the log, the `logcat` tail and the diag lines. The branch stays unmerged. Diagnose the cause with superpowers:systematic-debugging before any fix.

If the script stops without a `REGRESSION` line (for example `could not open port 'COM6'`), the verdict is `E2E PENDING: serial port`.

**Unless the verdict is PASS, put the watch back on the app it had before.** That is the golden rule: the watch must keep working on its own, and this branch's app is unproven on the hardware.

```bash
W=<watch serial from Step 4>
adb -s "$W" install -r -d "$(cygpath -m /tmp)/blindside-watch-before.apk"
```

Expected: `Success`. Skip this when Step 5 found no app to keep.

- [ ] **Step 8: Release the hardware**

```bash
rmdir /c/personal/.blindside-hardware.lock && echo "hardware released"
```

Expected: `hardware released`. Run this on every path that took the lock.

- [ ] **Step 9: Record the PASS for the orchestrator (only after a PASS)**

```bash
cd C:/personal/blindside
W=<watch serial from Step 4>
OUT=docs/superpowers/e2e/2026-10-01-p2-05-watch-regression.md
mkdir -p docs/superpowers/e2e
{
  echo "# E2E de regresión del reloj (spec §7 paso 2), plan 05"
  echo
  echo "- Fecha: $(date -Iseconds)"
  echo "- Rama: p2/android-shared-watch @ $(git rev-parse --short HEAD)"
  echo "- Firmware flasheado: main @ $(git rev-parse --short main)"
  echo "- Reloj: $(adb -s "$W" shell getprop ro.product.model | tr -d '\r')"
  echo
  echo '```'
  git show main:firmware/include/blindside_config.h | grep kFirmwareVersion
  cat /tmp/blindside-watch-regression.log
  echo '```'
} > "$OUT"
grep -c '^REGRESSION PASS' "$OUT"
git add "$OUT"
git commit -m "docs: E2E de regresión del reloj (spec §7 paso 2) aprobado"
```

Expected: `1`, then the commit.

- [ ] **Step 10: Final report (do not merge)**

Never merge into `main`. The orchestrator merges `p2/android-shared-watch` only when `docs/superpowers/e2e/2026-10-01-p2-05-watch-regression.md` on the branch says `REGRESSION PASS`. Plan 06 branches from this branch (stacked). The README checklist items 20-24 stay for Santiago.

Report:
- the branch (and the worktree path, if you used one);
- the commits: 16 from Tasks 1-16, plus Task 18's tool commit, plus the evidence commit on PASS;
- the counts: `android-shared 269`, `wear-app 47`, `radar-core 254`, `e2e` 7;
- the E2E verdict: `PASS`, `FAIL` (with the evidence, and confirmation that the watch was restored) or `PENDING` (with the reason);
- Deviations D1-D9, plus the font fallback if Task 14 had to use it;
- the Cross-plan contract items 1-9, marked as blocking for plan 06.
