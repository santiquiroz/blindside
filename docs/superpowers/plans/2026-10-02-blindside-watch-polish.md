# Blindside Watch Polish Pass (spec §8) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the watch radar fluid (60 fps compass ring, interpolated contacts, instant gyro scene-spin), add an automatic tactical posture detector, add a tactical HUD (rotating bezel window, game clock, GPS tactical points, contextual alerts, one-tap glance), and remove the obsolete "eliminated" mode from live sessions.

**Architecture:** Every new pure calculation (smoothing, interpolation, posture hysteresis, screen-zone layout, bearing/distance, queues, formatting) lives in `watch/android-shared` under `io.github.santiquiroz.blindside.shared.*` with JVM (JUnit 5) tests. `watch/wear-app` only draws Compose and reads sensors: it calls the shared functions each frame. Nothing new reaches the pipeline (`radar-core`); the detector and HUD drive the drawing only, never the system screen orientation. `SessionMode.ELIMINATED` survives in `radar-core` purely so the phone viewer can replay old `.bsrec`.

**Tech Stack:** Kotlin 2.2.21, AGP 8.10.1, Gradle wrapper in `watch/` (8.11.1), JDK 17, Compose BOM 2025.05.00, Wear Compose 1.4.0, coroutines 1.9.0, DataStore Preferences 1.1.4, JUnit 5 (5.13.1). GPS via the platform `android.location.LocationManager` (no new Gradle dependency — see Global Constraints). Android SDK at `C:/Users/santi/AppData/Local/Android/Sdk`.

**Spec:** [`docs/superpowers/specs/2026-10-01-blindside-android-companion-design.md`](../specs/2026-10-01-blindside-android-companion-design.md) §8 ("Pasada de fluidez, postura automática y HUD táctico"). Background: plan 05 [`2026-10-01-blindside-p2-05-android-shared-and-watch.md`](2026-10-01-blindside-p2-05-android-shared-and-watch.md), already merged to `main`, which created `android-shared` and the watch radar/compass.

## Global Constraints

- **Golden rule (spec, header):** nothing in this pass may break the watch on its own. A task merges only if **all** JVM tests pass **and** `:wear-app:assembleDebug` succeeds. On-device behavior (fluidity, GPS, posture, vibration) has no executor hardware, so it becomes a checklist in `watch/wear-app/README.md` (created in Task 1) and never blocks a merge.
- **Pure logic in `android-shared`, drawing in `wear-app` (spec §8 intro):** "Toda la lógica pura (suavizados, zonas de pantalla, formato) vive en `android-shared` con pruebas JVM; `wear-app` solo dibuja y lee sensores." Every new calculation gets a JVM test. A wear file never contains a formula a JVM test could have pinned.
- **Already done on `main` — do NOT redo:** `wear-app/src/main/AndroidManifest.xml` `MainActivity` already has `android:screenOrientation="nosensor"`. The circular low-pass `smoothedHeadingDeg` and the posture rotation `WatchPosture.rotationDeg` already exist.
- **Toolchain (do not upgrade):** Gradle 8.11.1 (`./gradlew` from `C:/personal/blindside/watch`), AGP 8.10.1, Kotlin 2.2.21, JDK 17. `compileSdk 36`, `targetSdk 36`. `wear-app` keeps `minSdk 34`; `android-shared` keeps `minSdk 33`; `phone-app` keeps `minSdk 33`. Compose BOM `2025.05.00`, Wear Compose `1.4.0`.
- **Android SDK:** at `C:/Users/santi/AppData/Local/Android/Sdk`. If `watch/local.properties` is missing, write `sdk.dir=C:/Users/santi/AppData/Local/Android/Sdk` with forward slashes. The file is gitignored (Task 1 verifies it).
- **Owner code rules (mandatory):** atomic functions whose name says what they do; low cyclomatic complexity (extract branches into named functions, prefer early returns); **no doc comments** (`/** */`) — only a one-line comment when the *why* is not obvious; pure functions and immutable data (`data class`, `val`, `copy`); mutable state only in Android adapter classes and `SessionStore`; files ≤ 400 lines.
- **Commits:** Spanish conventional messages (`feat:`, `fix:`, `refactor:`, `test:`, `chore:`, `docs:`). **Never** a `Co-Authored-By` line (user global rule). A deviation goes in the commit body.
- **Visual rules (spec §6, §8):** numbers in JetBrains Mono (`BlindsideFonts.Mono`) with tabular figures; black background everywhere; light discipline (no white backgrounds); in Sigilo the screen stays off by default and only critical alerts vibrate; animations 150–300 ms; colour is never the only indicator; touch targets ≥ 48 dp; vector icons, never emojis; user-facing strings in Spanish, code identifiers in English.
- **HUD placement (spec §8.3):** nothing in the HUD may cover the contact zone; everything goes in the bezel or the rear (bottom) half of the fan.
- **GPS (spec §8.3, this plan's decision):** tactical points read the watch's last known fix through the platform `android.location.LocationManager` (`getLastKnownLocation`, `requestLocationUpdates` with `GPS_PROVIDER`/`FUSED_PROVIDER` when present). This adds **one** new permission, `ACCESS_FINE_LOCATION` (Task 13), and **no** new Gradle dependency. We deliberately do **not** add `com.google.android.gms:play-services-location`: `FusedLocationProviderClient` would pull a new dependency for a feature that needs only a coarse fix once per long-press, and the watch exposes GPS through the platform `LocationManager` already. The pure bearing/distance math (Task 10) does not depend on the source. If a later field test shows `LocationManager` fixes are too slow on the Watch 7, the fallback is to add `play-services-location` and swap only the wear wiring in Task 13 — the shared math is unchanged.
- **Shell:** run every shell step in Git Bash (the Bash tool) from `C:/personal/blindside/watch`. Working-tree files are CRLF, so never anchor `sed` patterns on `$`.
- **Mandatory per-task gate** (every task that touches Kotlin; it deletes old test XML first so counts are exact):

  ```bash
  cd C:/personal/blindside/watch && ./gradlew :android-shared:cleanTestDebugUnitTest :wear-app:cleanTestDebugUnitTest :android-shared:testDebugUnitTest :wear-app:testDebugUnitTest :wear-app:assembleDebug --console=plain
  ```

  Tasks 14 and 15 remove shared symbols the phone app uses, so they additionally exercise the phone:

  ```bash
  cd C:/personal/blindside/watch && ./gradlew :phone-app:cleanTestDebugUnitTest :phone-app:testDebugUnitTest :phone-app:assembleDebug --console=plain
  ```

  Task 14 deliberately leaves the phone broken (it removes the shared symbols and the watch UI, but the phone fix is Task 15), so there it runs only `:phone-app:compileDebugKotlin` and **expects FAIL** — the golden rule gates on `:wear-app:assembleDebug`, which Task 14 keeps green by removing the watch UI in the same commit. Task 15 is the one that must see this full phone gate pass.

- **Test-count one-liner** (run right after the gate to confirm the suite grew/shrank as the task says):

  ```bash
  cd C:/personal/blindside/watch && for m in android-shared wear-app phone-app; do printf '%s %s\n' "$m" "$(cat $m/build/test-results/testDebugUnitTest/*.xml 2>/dev/null | grep -o '<testsuite [^>]*' | grep -o ' tests="[0-9]*"' | grep -o '[0-9]*' | awk '{s+=$1} END {print s+0}')"; done
  ```

## Review Focus

These are the input classes §8 implies but no task's happy-path test exercises, most likely to bite first. Each one's test is added to the task that owns the code (named below, in that task's step style).

1. **A zero-length (degenerate) gravity vector** — a watch calibrated or sampled while moving hard gives a near-zero mean vector. `angleBetweenDeg` must return a finite angle, never `NaN` from a divide-by-zero, and `captureGravityTemplate` must reject it. → Task 5.
2. **Clock skew / zero game duration** — `nowElapsedMs < startElapsedMs`, or a duration of 0 (the "sin límite" option). `gameRemainingMs` must clamp to 0 and never go negative, and the end-pulse must not fire forever. → Task 9.
3. **A contact that dies or is born between belt frames** — a `displayId` present in the previous scene but absent in the next (or vice-versa). `interpolatedBlips` must draw the next frame's set exactly, inventing no ghost and dropping no newborn. → Task 3.
4. **A huge frame gap after resume** — the activity was backgrounded and `withFrameNanos` delivers a multi-second `dt`. `advanceSceneSpinDeg` must wash fully to ~0 and stay clamped, and `advanceHeading` must snap toward the target without overshoot or overflow. → Tasks 2.
5. **Several alerts raised in one tick, in Sigilo** — three batteries low at once while the belt link drops. The queue must show them one at a time for 4 s each, de-duplicate repeats, and in Sigilo vibrate only the critical one (belt link down). → Task 11.

---

### Task 1: Branch, SDK file, README skeleton, baseline

**Files:**
- Create: `watch/wear-app/README.md`
- Verify/Create: `watch/local.properties` (gitignored)

**Interfaces:**
- Consumes: nothing.
- Produces: the working branch `feat/watch-polish`, the on-device checklist file every wear task appends to, and the recorded test baseline later tasks compare against.

- [ ] **Step 1: Branch from main**

```bash
cd C:/personal/blindside && git checkout main && git pull --ff-only && git checkout -b feat/watch-polish
```

- [ ] **Step 2: Ensure the SDK file exists**

```bash
cd C:/personal/blindside && test -f watch/local.properties || printf 'sdk.dir=C:/Users/santi/AppData/Local/Android/Sdk\n' > watch/local.properties
cat watch/local.properties
```

Expected: a single line `sdk.dir=C:/Users/santi/AppData/Local/Android/Sdk`.

- [ ] **Step 3: Record the baseline**

```bash
cd C:/personal/blindside/watch && ./gradlew :android-shared:cleanTestDebugUnitTest :wear-app:cleanTestDebugUnitTest :android-shared:testDebugUnitTest :wear-app:testDebugUnitTest :wear-app:assembleDebug --console=plain
cd C:/personal/blindside/watch && for m in android-shared wear-app phone-app; do printf '%s %s\n' "$m" "$(cat $m/build/test-results/testDebugUnitTest/*.xml 2>/dev/null | grep -o '<testsuite [^>]*' | grep -o ' tests="[0-9]*"' | grep -o '[0-9]*' | awk '{s+=$1} END {print s+0}')"; done
```

Expected: BUILD SUCCESSFUL. Write the three counts into the README (Step 4) as "Baseline at <short sha>".

- [ ] **Step 4: Create the wear on-device checklist file**

```markdown
# Blindside watch — polish pass (spec §8)

Pure logic is JVM-tested in `android-shared`. The items below need the real Watch 7 + belt and are checked by hand; they do not block a merge.

## Baseline
- Tests at <short sha>: android-shared=<n>, wear-app=<n>, phone-app=<n>.

## On-device checklist (§8)
- [ ] 8.1 Compass ring animates at ~60 fps, no per-sample jumps: `adb shell dumpsys gfxinfo io.github.santiquiroz.blindside` in demo before/after; record janky-frame %.
- [ ] 8.1 Contacts glide between belt frames; a fast body turn rotates the scene instantly (gyro), then settles.
- [ ] 8.1 Gyro scene-spin turns the right way (turning the body right keeps a static target where it is).
- [ ] 8.2 "Calibrar postura táctica" captures in 3 s; raising the replica into the grip rotates only the radar within ~0.4 s; lowering it returns to normal.
- [ ] 8.2 AUTO picks the correct side (left/right) for how this player wears the watch.
- [ ] 8.3 Bezel window cycles rumbo → hora → tiempo de partida; a short tap pins/unpins it.
- [ ] 8.3 Game clock counts down; vibrates once at 5 min left and once at 0.
- [ ] 8.3 Long-press marks base/reaparición/objetivo at the real GPS bearing; the wedge tracks north as the body turns.
- [ ] 8.3 Contextual alerts show one at a time for 4 s in the rear half; in Sigilo only the belt-link-down vibrates.
- [ ] 8.3 A centre tap shows the glance panel for 3 s, then it hides itself.
- [ ] 8.4 No "ME DIERON"/"REAPARECÍ" chip anywhere; an old `.bsrec` with an eliminated stretch still replays as eliminated in the phone viewer.
```

- [ ] **Step 5: Commit**

```bash
cd C:/personal/blindside && git add watch/wear-app/README.md && git commit -m "docs: checklist en dispositivo y baseline de la pasada del reloj (§8)"
```

---

### Task 2: Compass frame animation + gyro scene-spin (pure)

**Files:**
- Create: `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/compass/HeadingAnimation.kt`
- Create: `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/radar/SceneSpin.kt`
- Test: `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/compass/HeadingAnimationTest.kt`
- Test: `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/radar/SceneSpinTest.kt`

**Interfaces:**
- Consumes: `smoothedHeadingDeg`, `normalizedDeg`, `HEADING_TAU_MS` from `shared.compass.CompassMath` (already on `main`).
- Produces:
  - `data class HeadingAnimation(val currentDeg: Double, val lastFrameNanos: Long)`
  - `fun advanceHeading(animation: HeadingAnimation?, targetDeg: Double, frameNanos: Long, tauMs: Double = HEADING_TAU_MS): HeadingAnimation`
  - `const val SCENE_SPIN_WASHOUT_TAU_MS = 500.0`, `const val MAX_SCENE_SPIN_DEG = 45.0`
  - `fun advanceSceneSpinDeg(currentDeg: Double, yawRateRadPerSec: Double, dtMs: Long, washoutTauMs: Double = SCENE_SPIN_WASHOUT_TAU_MS): Double`

- [ ] **Step 1: Write the failing test for the heading animation**

```kotlin
package io.github.santiquiroz.blindside.shared.compass

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HeadingAnimationTest {
    private val ms = 1_000_000L

    @Test
    fun `the first frame snaps to the target and stores its time`() {
        val a = advanceHeading(null, 123.4, 5 * ms)
        assertEquals(123.4, a.currentDeg, 1e-9)
        assertEquals(5 * ms, a.lastFrameNanos)
    }

    @Test
    fun `a frame step eases toward the target through north and records the frame time`() {
        val start = HeadingAnimation(350.0, 0L)
        val stepped = advanceHeading(start, 10.0, 150 * ms)
        assertEquals(2.642, stepped.currentDeg, 1e-3)
        assertEquals(150 * ms, stepped.lastFrameNanos)
    }

    @Test
    fun `a huge gap after resume lands on the target without overshoot`() {
        // dt = 5000 ms ⇒ alpha = 1 - exp(-5000/150) ≈ 1, so the ring eases the whole short way (10°→200° is -170°)
        // and settles on the target 200°, never past it.
        val resumed = advanceHeading(HeadingAnimation(10.0, 0L), 200.0, 5_000 * ms)
        assertEquals(200.0, resumed.currentDeg, 1e-6)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest --tests '*HeadingAnimationTest' --console=plain`
Expected: FAIL — `advanceHeading`/`HeadingAnimation` unresolved.

- [ ] **Step 3: Implement the heading animation**

```kotlin
package io.github.santiquiroz.blindside.shared.compass

private const val NANOS_PER_MS = 1_000_000L

data class HeadingAnimation(val currentDeg: Double, val lastFrameNanos: Long)

// Called once per Compose frame: the ring eases toward the latest sensor heading with the existing circular low-pass,
// so it moves at the display's frame rate instead of jumping once per sensor sample.
fun advanceHeading(animation: HeadingAnimation?, targetDeg: Double, frameNanos: Long, tauMs: Double = HEADING_TAU_MS): HeadingAnimation {
    if (animation == null) return HeadingAnimation(normalizedDeg(targetDeg), frameNanos)
    val dtMs = (frameNanos - animation.lastFrameNanos).coerceAtLeast(0L) / NANOS_PER_MS
    return HeadingAnimation(smoothedHeadingDeg(animation.currentDeg, targetDeg, dtMs, tauMs), frameNanos)
}
```

- [ ] **Step 4: Run it and watch it pass**

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest --tests '*HeadingAnimationTest' --console=plain`
Expected: PASS.

- [ ] **Step 5: Write the failing test for the gyro scene-spin (Review Focus 4)**

```kotlin
package io.github.santiquiroz.blindside.shared.radar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class SceneSpinTest {
    @Test
    fun `no rotation and no time leaves the spin where it was`() {
        assertEquals(0.0, advanceSceneSpinDeg(0.0, 0.0, 0L), 1e-9)
        assertEquals(10.0, advanceSceneSpinDeg(10.0, 0.0, 0L), 1e-9)
    }

    @Test
    fun `a body turn adds immediate rotation then washes out`() {
        val afterTurn = advanceSceneSpinDeg(0.0, Math.toRadians(90.0), 100L)
        assertTrue(afterTurn > 0.0, "a turn adds spin: $afterTurn")
        val resting = advanceSceneSpinDeg(afterTurn, 0.0, 400L)
        assertTrue(abs(resting) < abs(afterTurn), "spin decays toward zero: $resting")
    }

    @Test
    fun `a huge gap washes the spin fully out and clamps`() {
        assertEquals(0.0, advanceSceneSpinDeg(40.0, 0.0, 10_000L), 1e-6)
        assertTrue(advanceSceneSpinDeg(0.0, Math.toRadians(10_000.0), 100L) <= MAX_SCENE_SPIN_DEG)
    }
}
```

- [ ] **Step 6: Run it and watch it fail**

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest --tests '*SceneSpinTest' --console=plain`
Expected: FAIL — `advanceSceneSpinDeg` unresolved.

- [ ] **Step 7: Implement the scene-spin washout**

```kotlin
package io.github.santiquiroz.blindside.shared.radar

import kotlin.math.exp

const val SCENE_SPIN_WASHOUT_TAU_MS = 500.0
const val MAX_SCENE_SPIN_DEG = 45.0

// The belt corrects body yaw at 10 Hz; between its frames the watch gyro turns the drawing at once, then this
// washout decays the extra spin toward zero so the belt's own compensation takes back over without a fight.
fun advanceSceneSpinDeg(
    currentDeg: Double,
    yawRateRadPerSec: Double,
    dtMs: Long,
    washoutTauMs: Double = SCENE_SPIN_WASHOUT_TAU_MS,
): Double {
    val dt = dtMs.coerceAtLeast(0L).toDouble()
    val added = Math.toDegrees(yawRateRadPerSec * dt / 1_000.0)
    val decayed = (currentDeg + added) * exp(-dt / washoutTauMs)
    return decayed.coerceIn(-MAX_SCENE_SPIN_DEG, MAX_SCENE_SPIN_DEG)
}
```

- [ ] **Step 8: Run the full gate**

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:cleanTestDebugUnitTest :wear-app:cleanTestDebugUnitTest :android-shared:testDebugUnitTest :wear-app:testDebugUnitTest :wear-app:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL; android-shared grew by 6 tests.

- [ ] **Step 9: Commit**

```bash
cd C:/personal/blindside && git add watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/compass/HeadingAnimation.kt watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/radar/SceneSpin.kt watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/compass/HeadingAnimationTest.kt watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/radar/SceneSpinTest.kt && git commit -m "feat: animación de rumbo por cuadro y giro de escena del giroscopio (§8.1)"
```

---

### Task 3: Contact frame interpolation (pure)

**Files:**
- Create: `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/radar/FrameInterpolation.kt`
- Test: `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/radar/FrameInterpolationTest.kt`

**Interfaces:**
- Consumes: `io.github.santiquiroz.blindside.core.scene.Blip`, and `normalizedDeg`/`shortestTurnDeg` from `shared.compass.CompassMath`.
- Produces:
  - `fun frameFraction(sinceFrameMs: Long, framePeriodMs: Long): Float`
  - `fun interpolatedBlips(previous: List<Blip>, next: List<Blip>, t: Float): List<Blip>`

- [ ] **Step 1: Write the failing test (includes Review Focus 3)**

```kotlin
package io.github.santiquiroz.blindside.shared.radar

import io.github.santiquiroz.blindside.core.scene.Blip
import io.github.santiquiroz.blindside.core.scene.Confidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FrameInterpolationTest {
    private fun blip(id: Int, bearing: Double, range: Double) =
        Blip(displayId = id, bearingDeg = bearing, rangeM = range, confidence = Confidence.BOTH, ageMs = 0, outOfView = false)

    @Test
    fun `the fraction climbs from zero to one across a belt frame and clamps`() {
        assertEquals(0f, frameFraction(0, 100), 1e-6f)
        assertEquals(0.5f, frameFraction(50, 100), 1e-6f)
        assertEquals(1f, frameFraction(250, 100), 1e-6f)
        assertEquals(1f, frameFraction(10, 0), 1e-6f)
    }

    @Test
    fun `a matched contact eases halfway in range and the short way around in bearing`() {
        val mid = interpolatedBlips(listOf(blip(1, 350.0, 2.0)), listOf(blip(1, 10.0, 4.0)), 0.5f).single()
        assertEquals(0.0, mid.bearingDeg, 1e-6)
        assertEquals(3.0, mid.rangeM, 1e-6)
        assertEquals(Confidence.BOTH, mid.confidence)
    }

    @Test
    fun `an unmatched newborn is drawn as the next frame, and a dead contact never lingers`() {
        val born = interpolatedBlips(listOf(blip(1, 0.0, 1.0)), listOf(blip(2, 90.0, 5.0)), 0.5f)
        assertEquals(listOf(2), born.map { it.displayId })
        assertEquals(90.0, born.single().bearingDeg, 1e-6)
        assertEquals(5.0, born.single().rangeM, 1e-6)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest --tests '*FrameInterpolationTest' --console=plain`
Expected: FAIL — `frameFraction`/`interpolatedBlips` unresolved.

- [ ] **Step 3: Implement the interpolation**

```kotlin
package io.github.santiquiroz.blindside.shared.radar

import io.github.santiquiroz.blindside.core.scene.Blip
import io.github.santiquiroz.blindside.shared.compass.normalizedDeg
import io.github.santiquiroz.blindside.shared.compass.shortestTurnDeg

// How far between the last belt frame and the next the display is, 0..1, so contacts glide at frame rate.
fun frameFraction(sinceFrameMs: Long, framePeriodMs: Long): Float {
    if (framePeriodMs <= 0L) return 1f
    return (sinceFrameMs.toFloat() / framePeriodMs).coerceIn(0f, 1f)
}

// The next frame is the truth: its set of ids is drawn exactly. A contact also present last frame starts from there,
// so it slides instead of teleporting; a newborn or a vanished one is never invented or kept.
fun interpolatedBlips(previous: List<Blip>, next: List<Blip>, t: Float): List<Blip> =
    next.map { target -> previous.firstOrNull { it.displayId == target.displayId }?.let { easedBlip(it, target, t) } ?: target }

private fun easedBlip(from: Blip, to: Blip, t: Float): Blip = to.copy(
    bearingDeg = normalizedDeg(from.bearingDeg + shortestTurnDeg(from.bearingDeg, to.bearingDeg) * t),
    rangeM = from.rangeM + (to.rangeM - from.rangeM) * t,
)
```

- [ ] **Step 4: Run it and watch it pass**

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest --tests '*FrameInterpolationTest' --console=plain`
Expected: PASS.

- [ ] **Step 5: Run the full gate**

Run: the mandatory per-task gate.
Expected: BUILD SUCCESSFUL; android-shared grew by 3 tests.

- [ ] **Step 6: Commit**

```bash
cd C:/personal/blindside && git add watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/radar/FrameInterpolation.kt watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/radar/FrameInterpolationTest.kt && git commit -m "feat: interpolación de contactos entre cuadros del cinturón (§8.1)"
```

---

### Task 4: Wear fluidity wiring (RadarScreen, sensors)

**Files:**
- Modify: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/CompassSensor.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/GyroSpinSensor.kt`
- Modify: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/CompassRingCanvas.kt`
- Modify: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/RadarScreen.kt`
- Modify: `watch/wear-app/README.md`

**Interfaces:**
- Consumes: `advanceHeading`/`HeadingAnimation` (Task 2), `advanceSceneSpinDeg`/`MAX_SCENE_SPIN_DEG` (Task 2), `frameFraction`/`interpolatedBlips` (Task 3), the existing `rememberCompassReading`, `drawRadar`, `toDrawModel` (takes a `RadarScene?`), `rotatedAbout`. `drawCompassRing` is split in Step 3 into `drawCompassTicks` (azimuth-rotated layer), `drawCompassLetters` (non-rotated, upright) and `drawFrontIndex` (non-rotated, fixed at the front).
- Produces: no shared API; this is the drawing/sensor layer. On-device behavior is checked in the README.

This task has no JVM test (wear drawing/sensors only). It is gated by `:wear-app:assembleDebug` and the README checklist.

- [ ] **Step 1: Raise the compass sensor rate and expose the raw azimuth**

In `CompassSensor.kt`, change the registration from `SENSOR_DELAY_UI` to `SENSOR_DELAY_GAME`, and keep emitting `CompassReading` (raw azimuth + trust) — the per-frame easing now happens in the composable, not in the listener. Replace the listener's internal `smoothedHeadingDeg` call so it emits the **raw** azimuth (the ring easing moved to the frame loop):

```kotlin
manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
```

```kotlin
private class CompassListener(private val emit: (CompassReading) -> Unit) : SensorEventListener {
    override fun onSensorChanged(event: SensorEvent) {
        emit(CompassReading(azimuthFromRotationVector(event.values), compassTrust(event.accuracy)))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
```

(Delete the now-unused `smoothedDeg`/`lastNanos`/`NANOS_PER_MS` and the `smoothedHeadingDeg` import.)

- [ ] **Step 2: Add a gyro listener that reports the latest yaw rate**

Create `GyroSpinSensor.kt`, mirroring `rememberCompassReading` (a `LifecycleStartEffect` that registers `TYPE_GYROSCOPE` at `SENSOR_DELAY_GAME` and writes the Z-axis rad/s into a `State<Float>`; 0f when inactive). The composable returns `State<Float>` (latest yaw rate). Keep the body ≤ 60 lines, one listener class, early-return when the sensor is absent.

- [ ] **Step 3: Split the ring so only the symmetric tick band spins with heading**

In `CompassRingCanvas.kt`, split `drawCompassRing` into three draws so heading rotation never tilts the letters or drags the front index. The current single call rotates nothing with azimuth except the tick/letter *positions* (`markScreenAngleDeg`); the letter glyphs stay upright (`rotate(postureRotationDeg)`) and the front index sits at `postureRotationDeg`. Preserve exactly that, but move the heavy symmetric band onto the compositor:

- `drawCompassTicks(ring, colors)` — the tick band only, each tick drawn at its own `tick.angleDeg` (no azimuth term). The caller draws this into its **own** `Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = -animatedAzimuthDeg.toFloat() })`, so the whole band turns against the heading on the compositor, once per frame, without recomposition. Ticks are rotationally symmetric lines, so rotating them wholesale is correct and needs no counter-rotation.
- `drawCompassLetters(azimuthDeg, ring, postureRotationDeg, colors, measurer)` — the four cardinals, positioned at `markScreenAngleDeg(mark.angleDeg, azimuthDeg)` (unchanged) with the glyph rotated **only** by `postureRotationDeg` (`rotate(postureRotationDeg, pivot = anchor)`, unchanged). Drawn in the **non-rotated** Canvas (not the `-azimuth` layer) so the letters stay upright for the posture and never spin with heading. They cannot ride the tick layer: that layer would tilt the glyphs and `drawCompassTicks` carries no azimuth with which to counter-rotate them.
- `drawFrontIndex(ring, postureRotationDeg, colors.index)` — unchanged, drawn in the **non-rotated** Canvas. The front index marks the watch's 12 o'clock in the current posture, so it must stay fixed at `postureRotationDeg`, independent of heading, and never go inside the `-azimuth` layer.

Net: the tick band turns with heading via the `graphicsLayer`; the cardinal letters keep their heading-driven positions but upright glyphs; the front index and the `HeadingWindow` (Step 4) stay at the front. Do **not** introduce a `drawCompassRingStatic` that bakes azimuth into a single rotated layer — that is what tilts the letters and swings the index.

- [ ] **Step 4: Drive the ring, contacts and spin from the frame clock in `RadarScreen`**

In `RadarScreen.kt`:
- Keep the last two **non-null** scenes (`remember` a small holder, `prev`/`next`, updated on each `session.scene` change) and a `frameStartNanos` captured when a new scene arrives. Each frame compute `t = frameFraction(sinceMs, SIGILO_FRAME_MS)` and fold the interpolated contacts back into the newest scene before drawing: `val drawnScene = next.copy(blips = interpolatedBlips(prev.blips, next.blips, t))`. Then pass `drawnScene` (a whole `RadarScene`) to `toDrawModel` — `toDrawModel` takes a `RadarScene?` and reads `scene.blips` itself, so do **not** try to hand it the `List<Blip>` that `interpolatedBlips` returns; `interpolatedBlips` is fed `prev.blips`/`next.blips`, not whole scenes. With only one scene so far (`prev == null`), draw `next` unchanged (no interpolation).
- Run one `LaunchedEffect(compassOn)` loop using `withFrameNanos` that advances `HeadingAnimation` via `advanceHeading(anim, reading.azimuthDeg, frameNanos)` and advances the spin via `advanceSceneSpinDeg(spin, yawRate, dtMs)`; expose both as `State`.
- Draw the radar with `rotationDeg = settings.posture.rotationDeg + spin` (posture is replaced by the auto-resolved value in Task 7) applied through `rotatedAbout(pivot, rotationDeg)` for the contacts/fan.
- Rotate the pre-drawn **tick** layer (its own `graphicsLayer` Canvas, Step 3) by `-animatedAzimuth`; draw the cardinal letters (upright, positioned by `markScreenAngleDeg(mark.angleDeg, animatedAzimuth)`) and the front index in the non-rotated Canvas. Neither the letters' glyphs nor the front index rotate with heading.
- The `HeadingWindow` text still uses `frontHeadingDeg(animatedAzimuth, rotationDeg)`; it stays at the front (its overlay rotates by `rotationDeg` = posture only, never by azimuth).
- Keep `will-change`-style discipline: no animation when `ambient` or `!compassOn`.

- [ ] **Step 5: Build**

Run: `cd C:/personal/blindside/watch && ./gradlew :wear-app:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Tick the README items that this task enables**

Leave the 8.1 checkboxes unchecked (they need the device) but confirm the file lists the `dumpsys gfxinfo` before/after step and the gyro-direction step. Add a one-line "wired in Task 4" note under 8.1.

- [ ] **Step 7: Run the full gate and commit**

```bash
cd C:/personal/blindside/watch && ./gradlew :android-shared:cleanTestDebugUnitTest :wear-app:cleanTestDebugUnitTest :android-shared:testDebugUnitTest :wear-app:testDebugUnitTest :wear-app:assembleDebug --console=plain
cd C:/personal/blindside && git add watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/radar watch/wear-app/README.md && git commit -m "feat: reloj dibuja la brújula y los contactos a 60 fps con giro del giroscopio (§8.1)"
```

---

### Task 5: Gravity template + angle math (pure)

**Files:**
- Create: `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/sensors/GravityPosture.kt`
- Test: `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/sensors/GravityPostureTest.kt`

**Interfaces:**
- Consumes: `io.github.santiquiroz.blindside.shared.sensors.Vec3` (already on `main`).
- Produces:
  - `data class GravityTemplate(val x: Float, val y: Float, val z: Float, val rotationDeg: Float)`
  - `const val MIN_GRAVITY_MAGNITUDE = 3f`
  - `fun captureGravityTemplate(samples: List<Vec3>): GravityTemplate?`
  - `fun angleBetweenDeg(a: Vec3, b: Vec3): Double`
  - `const val TACTICAL_LEFT_ROTATION_DEG = 90f`, `const val TACTICAL_RIGHT_ROTATION_DEG = -90f`

- [ ] **Step 1: Write the failing test (includes Review Focus 1)**

```kotlin
package io.github.santiquiroz.blindside.shared.sensors

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GravityPostureTest {
    @Test
    fun `an empty or near-zero sample set yields no template`() {
        assertNull(captureGravityTemplate(emptyList()))
        assertNull(captureGravityTemplate(listOf(Vec3(1f, 0f, -1f), Vec3(-1f, 0f, 1f))))
    }

    @Test
    fun `the template averages the samples and picks the side from the roll sign`() {
        val positiveRoll = captureGravityTemplate(listOf(Vec3(6f, 0f, 7f), Vec3(8f, 0f, 7f)))!!
        assertEquals(7f, positiveRoll.x, 1e-4f)
        assertEquals(TACTICAL_LEFT_ROTATION_DEG, positiveRoll.rotationDeg)
        val negativeRoll = captureGravityTemplate(listOf(Vec3(-7f, 0f, 7f)))!!
        assertEquals(TACTICAL_RIGHT_ROTATION_DEG, negativeRoll.rotationDeg)
    }

    @Test
    fun `the angle between two vectors is finite, symmetric and never NaN on a zero vector`() {
        assertEquals(0.0, angleBetweenDeg(Vec3(0f, 0f, 9.8f), Vec3(0f, 0f, 2f)), 1e-6)
        assertEquals(90.0, angleBetweenDeg(Vec3(0f, 0f, 9.8f), Vec3(9.8f, 0f, 0f)), 1e-6)
        assertTrue(angleBetweenDeg(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 9.8f)) in 0.0..180.0)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest --tests '*GravityPostureTest' --console=plain`
Expected: FAIL — symbols unresolved.

- [ ] **Step 3: Implement the gravity math**

```kotlin
package io.github.santiquiroz.blindside.shared.sensors

import kotlin.math.acos
import kotlin.math.sqrt

data class GravityTemplate(val x: Float, val y: Float, val z: Float, val rotationDeg: Float)

const val MIN_GRAVITY_MAGNITUDE = 3f
const val TACTICAL_LEFT_ROTATION_DEG = 90f
const val TACTICAL_RIGHT_ROTATION_DEG = -90f

// Holding the replica for 3 s averages to a steady gravity vector; moving the whole time averages toward zero and is rejected.
fun captureGravityTemplate(samples: List<Vec3>): GravityTemplate? {
    if (samples.isEmpty()) return null
    val mean = Vec3(
        samples.sumOf { it.x.toDouble() }.toFloat() / samples.size,
        samples.sumOf { it.y.toDouble() }.toFloat() / samples.size,
        samples.sumOf { it.z.toDouble() }.toFloat() / samples.size,
    )
    if (magnitude(mean) < MIN_GRAVITY_MAGNITUDE) return null
    return GravityTemplate(mean.x, mean.y, mean.z, tacticalRotationDeg(mean))
}

// The grip rolls the watch; the sign of the x-gravity says which wrist it rolled toward, so which way to turn the radar.
private fun tacticalRotationDeg(g: Vec3): Float = if (g.x >= 0f) TACTICAL_LEFT_ROTATION_DEG else TACTICAL_RIGHT_ROTATION_DEG

fun angleBetweenDeg(a: Vec3, b: Vec3): Double {
    val mags = magnitude(a).toDouble() * magnitude(b).toDouble()
    if (mags <= 1e-6) return 180.0
    val cosine = ((a.x * b.x + a.y * b.y + a.z * b.z).toDouble() / mags).coerceIn(-1.0, 1.0)
    return Math.toDegrees(acos(cosine))
}

private fun magnitude(v: Vec3): Float = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
```

- [ ] **Step 4: Run it and watch it pass**

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest --tests '*GravityPostureTest' --console=plain`
Expected: PASS.

- [ ] **Step 5: Run the full gate and commit**

```bash
cd C:/personal/blindside/watch && ./gradlew :android-shared:cleanTestDebugUnitTest :wear-app:cleanTestDebugUnitTest :android-shared:testDebugUnitTest :wear-app:testDebugUnitTest :wear-app:assembleDebug --console=plain
cd C:/personal/blindside && git add watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/sensors/GravityPosture.kt watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/sensors/GravityPostureTest.kt && git commit -m "feat: plantilla de gravedad y ángulo contra ella para la postura táctica (§8.2)"
```

---

### Task 6: Posture detector + `WatchPosture.AUTO` + settings (pure)

**Files:**
- Create: `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/sensors/PostureDetector.kt`
- Modify: `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/settings/AppSettings.kt`
- Modify: `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/settings/SettingsPreferences.kt`
- Modify: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/Labels.kt`
- Create: `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/sensors/PostureDetectorTest.kt`
- Modify: `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/settings/WatchPostureTest.kt`
- Modify: `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/settings/SettingsPreferencesTest.kt`

**Interfaces:**
- Consumes: `GravityTemplate` (Task 5), `WatchPosture` (existing).
- Produces:
  - `WatchPosture.AUTO` (first entry, `rotationDeg = 0f`); `AppSettings.posture` default becomes `AUTO`; new field `AppSettings.postureTemplate: GravityTemplate? = null`.
  - `data class PostureDetectorState(val tactical: Boolean = false, val enteringSinceMs: Long? = null)`
  - `const val POSTURE_ENTER_DEG = 25.0`, `POSTURE_EXIT_DEG = 35.0`, `POSTURE_DWELL_MS = 400L`
  - `fun stepPostureDetector(state, angleDeg, nowMs, enterDeg, exitDeg, dwellMs): PostureDetectorState`
  - `fun effectivePostureRotationDeg(posture: WatchPosture, tactical: Boolean, template: GravityTemplate?): Float`
  - `postureLabel(WatchPosture.AUTO)` returns "Automática".

- [ ] **Step 1: Write the failing posture-detector test**

```kotlin
package io.github.santiquiroz.blindside.shared.sensors

import io.github.santiquiroz.blindside.shared.settings.WatchPosture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PostureDetectorTest {
    private fun step(state: PostureDetectorState, angle: Double, now: Long) =
        stepPostureDetector(state, angle, now, POSTURE_ENTER_DEG, POSTURE_EXIT_DEG, POSTURE_DWELL_MS)

    @Test
    fun `tactical engages only after staying inside the cone for the dwell`() {
        var s = PostureDetectorState()
        s = step(s, 10.0, 0L); assertFalse(s.tactical)
        s = step(s, 10.0, 200L); assertFalse(s.tactical)
        s = step(s, 10.0, 400L); assertTrue(s.tactical)
    }

    @Test
    fun `a glance outside the cone before the dwell resets the timer`() {
        var s = PostureDetectorState()
        s = step(s, 10.0, 0L)
        s = step(s, 40.0, 200L); assertFalse(s.tactical)
        s = step(s, 10.0, 300L)
        s = step(s, 10.0, 600L); assertFalse(s.tactical)
        s = step(s, 10.0, 700L); assertTrue(s.tactical)
    }

    @Test
    fun `hysteresis holds tactical until the angle passes the wider exit`() {
        val engaged = PostureDetectorState(tactical = true)
        assertTrue(step(engaged, 30.0, 999L).tactical)
        assertFalse(step(engaged, 36.0, 999L).tactical)
    }

    @Test
    fun `auto uses the template rotation only while tactical, fixed postures ignore detection`() {
        val template = GravityTemplate(1f, 0f, 9f, TACTICAL_LEFT_ROTATION_DEG)
        assertEquals(0f, effectivePostureRotationDeg(WatchPosture.AUTO, tactical = false, template = template))
        assertEquals(90f, effectivePostureRotationDeg(WatchPosture.AUTO, tactical = true, template = template))
        assertEquals(0f, effectivePostureRotationDeg(WatchPosture.AUTO, tactical = true, template = null))
        assertEquals(-90f, effectivePostureRotationDeg(WatchPosture.TACTICAL_RIGHT, tactical = false, template = template))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest --tests '*PostureDetectorTest' --console=plain`
Expected: FAIL — `WatchPosture.AUTO`, detector symbols unresolved.

- [ ] **Step 3: Add `AUTO` to the enum and the template to settings**

In `AppSettings.kt`, make `AUTO` the first entry and default, and add the template field:

```kotlin
enum class WatchPosture(val rotationDeg: Float) {
    AUTO(0f),
    NORMAL(0f),
    TACTICAL_LEFT(90f),
    TACTICAL_RIGHT(-90f),
}
```

```kotlin
import io.github.santiquiroz.blindside.shared.sensors.GravityTemplate
```

In `data class AppSettings(...)` change `val posture: WatchPosture = WatchPosture.AUTO,` and add `val postureTemplate: GravityTemplate? = null,`. `nextPosture` is unchanged (it already cycles `WatchPosture.entries`, now four long).

- [ ] **Step 4: Persist the template (watch-local)**

In `SettingsPreferences.kt`, add keys and round-trip. The template is a device-specific grip, so it stays local (not in `SharedSettings`):

```kotlin
val POSTURE_TEMPLATE_SET = booleanPreferencesKey("posture_template_set")
val POSTURE_TEMPLATE_X = doublePreferencesKey("posture_template_x")
val POSTURE_TEMPLATE_Y = doublePreferencesKey("posture_template_y")
val POSTURE_TEMPLATE_Z = doublePreferencesKey("posture_template_z")
val POSTURE_TEMPLATE_ROT = doublePreferencesKey("posture_template_rot")
```

In `settingsFrom`, read `postureTemplate = templateFrom(prefs)`; in `writeSettings`, call `writeTemplate(prefs, settings.postureTemplate)`. Add the two private helpers (build `GravityTemplate` from the four doubles only when `POSTURE_TEMPLATE_SET` is true; write all five, or remove all five when null). Import `GravityTemplate`.

- [ ] **Step 5: Implement the detector**

```kotlin
package io.github.santiquiroz.blindside.shared.sensors

import io.github.santiquiroz.blindside.shared.settings.WatchPosture

const val POSTURE_ENTER_DEG = 25.0
const val POSTURE_EXIT_DEG = 35.0
const val POSTURE_DWELL_MS = 400L

data class PostureDetectorState(val tactical: Boolean = false, val enteringSinceMs: Long? = null)

fun stepPostureDetector(
    state: PostureDetectorState,
    angleDeg: Double,
    nowMs: Long,
    enterDeg: Double = POSTURE_ENTER_DEG,
    exitDeg: Double = POSTURE_EXIT_DEG,
    dwellMs: Long = POSTURE_DWELL_MS,
): PostureDetectorState {
    if (state.tactical) return if (angleDeg > exitDeg) PostureDetectorState(false, null) else state
    if (angleDeg >= enterDeg) return PostureDetectorState(false, null)
    val since = state.enteringSinceMs ?: nowMs
    if (nowMs - since >= dwellMs) return PostureDetectorState(true, null)
    return state.copy(enteringSinceMs = since)
}

// AUTO follows the detector and the calibrated side; the three fixed choices turn the drawing by their own angle.
fun effectivePostureRotationDeg(posture: WatchPosture, tactical: Boolean, template: GravityTemplate?): Float = when (posture) {
    WatchPosture.AUTO -> if (tactical && template != null) template.rotationDeg else 0f
    else -> posture.rotationDeg
}
```

- [ ] **Step 6: Add the label**

In `Labels.kt`, extend `postureLabel`:

```kotlin
fun postureLabel(posture: WatchPosture): String = when (posture) {
    WatchPosture.AUTO -> "Automática"
    WatchPosture.NORMAL -> "Normal"
    WatchPosture.TACTICAL_LEFT -> "Táctica izquierda (+90°)"
    WatchPosture.TACTICAL_RIGHT -> "Táctica derecha (-90°)"
}
```

- [ ] **Step 7: Update the tests the new default/cycle break**

In `WatchPostureTest.kt`, replace the two assertions that encode the old default and cycle:

```kotlin
    @Test
    fun `the default posture is automatic`() {
        assertEquals(WatchPosture.AUTO, AppSettings().posture)
    }

    @Test
    fun `posture cycles through automatic, normal, tactical left and tactical right`() {
        assertEquals(WatchPosture.NORMAL, nextPosture(WatchPosture.AUTO))
        assertEquals(WatchPosture.TACTICAL_LEFT, nextPosture(WatchPosture.NORMAL))
        assertEquals(WatchPosture.TACTICAL_RIGHT, nextPosture(WatchPosture.TACTICAL_LEFT))
        assertEquals(WatchPosture.AUTO, nextPosture(WatchPosture.TACTICAL_RIGHT))
    }
```

In `SettingsPreferencesTest.kt`, add a round-trip for the template (write an `AppSettings` with a `GravityTemplate`, read it back, assert equal; and assert a settings with `postureTemplate = null` reads back null).

- [ ] **Step 8: Run the full gate**

Run: the mandatory per-task gate.
Expected: BUILD SUCCESSFUL. android-shared gains the detector tests and the preferences round-trip; the two edited `WatchPostureTest` tests stay green. Confirm no other `android-shared` test referenced `WatchPosture.NORMAL` as the default.

- [ ] **Step 9: Commit**

```bash
cd C:/personal/blindside && git add watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/sensors/PostureDetector.kt watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/settings/AppSettings.kt watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/settings/SettingsPreferences.kt watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/Labels.kt watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/sensors/PostureDetectorTest.kt watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/settings/WatchPostureTest.kt watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/settings/SettingsPreferencesTest.kt && git commit -m "feat: postura automática con histéresis, opción AUTO y plantilla persistida (§8.2)"
```

---

### Task 7: Wear posture wiring (calibration + auto rotation)

**Files:**
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/PostureSensor.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/CalibratePostureScreen.kt`
- Modify: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/Routes.kt`
- Modify: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/BlindsideApp.kt`
- Modify: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/SettingsScreen.kt`
- Modify: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/RadarScreen.kt`
- Modify: `watch/wear-app/README.md`

**Interfaces:**
- Consumes: `captureGravityTemplate`, `GravityTemplate`, `angleBetweenDeg`, `Vec3`, `stepPostureDetector`, `PostureDetectorState`, `effectivePostureRotationDeg` (Tasks 5–6); existing `rememberCompassReading` pattern.
- Produces: no shared API (wear-only). Gated by `:wear-app:assembleDebug` + README.

No JVM test (wear-only).

- [ ] **Step 1: Gravity listener for the radar**

Create `PostureSensor.kt`: a `rememberTacticalPosture(active: Boolean, template: GravityTemplate?): State<Boolean>` that registers `TYPE_GRAVITY` at `SENSOR_DELAY_UI`, feeds each sample's `angleBetweenDeg(Vec3(sample), Vec3(template))` into `stepPostureDetector` (keyed by `SystemClock.elapsedRealtime()`), and exposes `state.tactical`. When `template == null` or `!active`, it returns a constant `false` and registers nothing (AUTO without calibration never rotates).

- [ ] **Step 2: Calibration screen**

Create `CalibratePostureScreen.kt`: a full-screen Wear Compose screen with one primary action "Sostén la réplica" that, on tap, collects `TYPE_GRAVITY` samples for 3 s (a `LaunchedEffect` with `delay`), calls `captureGravityTemplate(samples)`, and on success saves it with `onUpdate { it.copy(postureTemplate = template) }` and shows "Postura guardada"; on a null result (moved too much) shows "No te muevas; vuelve a intentar". Progress is a 3 s indicator. Numbers (countdown) in `BlindsideFonts.Mono`.

- [ ] **Step 3: Route + settings entry**

In `Routes.kt` add `const val ROUTE_CALIBRATE_POSTURE = "calibrate_posture"`. In `BlindsideApp.kt` add the `composable(ROUTE_CALIBRATE_POSTURE) { CalibratePostureScreen(settings, update) { navController.popBackStack() } }`. In `SettingsScreen.kt`, under the existing "Postura del reloj" chip, add `item { NavChip(CALIBRATE_POSTURE_LABEL) { onNavigate(ROUTE_CALIBRATE_POSTURE) } }` and add `const val CALIBRATE_POSTURE_LABEL = "Calibrar postura táctica"` to `Labels.kt`. The posture chip already cycles through AUTO via `nextPosture`/`postureLabel`.

- [ ] **Step 4: Drive the radar rotation from the detector**

In `RadarScreen.kt`, replace `val rotationDeg = settings.posture.rotationDeg` with the auto-resolved value:

```kotlin
val tactical by rememberTacticalPosture(active = !ambient, template = settings.postureTemplate)
val postureDeg = effectivePostureRotationDeg(settings.posture, tactical, settings.postureTemplate)
val rotationDeg = postureDeg + spin   // spin from Task 4
```

Everything downstream (`rotatedAbout(pivot, rotationDeg)`, the overlay `graphicsLayer { rotationZ = rotationDeg }`, `frontHeadingDeg`) already takes `rotationDeg`.

- [ ] **Step 5: Build, check README, commit**

```bash
cd C:/personal/blindside/watch && ./gradlew :android-shared:cleanTestDebugUnitTest :wear-app:cleanTestDebugUnitTest :android-shared:testDebugUnitTest :wear-app:testDebugUnitTest :wear-app:assembleDebug --console=plain
cd C:/personal/blindside && git add watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear watch/wear-app/README.md && git commit -m "feat: calibración y postura automática mueven solo el radar en el reloj (§8.2)"
```

---

### Task 8: Bezel window field rotation (pure)

**Files:**
- Create: `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/hud/BezelWindow.kt`
- Test: `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/hud/BezelWindowTest.kt`

**Interfaces:**
- Produces:
  - `enum class BezelField { HEADING, CLOCK, GAME_TIME }`
  - `const val BEZEL_ROTATE_MS = 4_000L`
  - `fun bezelFieldAt(elapsedMs: Long, pinned: BezelField?, rotatePeriodMs: Long = BEZEL_ROTATE_MS): BezelField`
  - `fun toggledPin(pinned: BezelField?, showing: BezelField): BezelField?`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.santiquiroz.blindside.shared.hud

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BezelWindowTest {
    @Test
    fun `the window rotates through the three fields on the period`() {
        assertEquals(BezelField.HEADING, bezelFieldAt(0, null))
        assertEquals(BezelField.CLOCK, bezelFieldAt(4_000, null))
        assertEquals(BezelField.GAME_TIME, bezelFieldAt(8_500, null))
        assertEquals(BezelField.HEADING, bezelFieldAt(12_000, null))
    }

    @Test
    fun `a pin freezes the shown field regardless of time`() {
        assertEquals(BezelField.CLOCK, bezelFieldAt(0, BezelField.CLOCK))
        assertEquals(BezelField.CLOCK, bezelFieldAt(9_000, BezelField.CLOCK))
    }

    @Test
    fun `a tap pins what is showing and a second tap unpins`() {
        assertEquals(BezelField.GAME_TIME, toggledPin(null, BezelField.GAME_TIME))
        assertNull(toggledPin(BezelField.GAME_TIME, BezelField.GAME_TIME))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest --tests '*BezelWindowTest' --console=plain`
Expected: FAIL.

- [ ] **Step 3: Implement**

```kotlin
package io.github.santiquiroz.blindside.shared.hud

enum class BezelField { HEADING, CLOCK, GAME_TIME }

const val BEZEL_ROTATE_MS = 4_000L

// The bezel window (where the heading lives) cycles its content every few seconds; a short tap pins what is showing.
fun bezelFieldAt(elapsedMs: Long, pinned: BezelField?, rotatePeriodMs: Long = BEZEL_ROTATE_MS): BezelField {
    pinned?.let { return it }
    val index = ((elapsedMs.coerceAtLeast(0L) / rotatePeriodMs) % BezelField.entries.size).toInt()
    return BezelField.entries[index]
}

fun toggledPin(pinned: BezelField?, showing: BezelField): BezelField? = if (pinned == null) showing else null
```

- [ ] **Step 4: Run it and watch it pass, then the full gate**

Run: the `--tests '*BezelWindowTest'` run, then the mandatory gate.
Expected: PASS; android-shared grew by 3 tests.

- [ ] **Step 5: Commit**

```bash
cd C:/personal/blindside && git add watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/hud/BezelWindow.kt watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/hud/BezelWindowTest.kt && git commit -m "feat: rotación del contenido de la ventanita del bisel con fijado por toque (§8.3)"
```

---

### Task 9: Game clock + duration setting (pure)

**Files:**
- Create: `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/hud/GameClock.kt`
- Modify: `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/settings/AppSettings.kt`
- Modify: `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/settings/SettingsPreferences.kt`
- Modify: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/Labels.kt`
- Test: `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/hud/GameClockTest.kt`
- Modify: `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/settings/SettingsPreferencesTest.kt`

**Interfaces:**
- Produces:
  - `val GAME_DURATION_OPTIONS_MS = listOf(3_600_000L, 7_200_000L, 10_800_000L, 18_000_000L, 0L)` (1 h, 2 h, 3 h, 5 h, sin límite)
  - `const val DEFAULT_GAME_DURATION_MS = 18_000_000L`; `AppSettings.gameDurationMs: Long = DEFAULT_GAME_DURATION_MS`
  - `const val FIVE_MIN_MS = 300_000L`
  - `fun gameRemainingMs(startElapsedMs, nowElapsedMs, durationMs): Long`
  - `fun gameClockText(remainingMs: Long): String`
  - `fun crossedThreshold(prevRemainingMs, nowRemainingMs, thresholdMs): Boolean`
  - `fun nextGameDuration(durationMs: Long): Long`
  - `gameDurationLabel(durationMs)` in `Labels.kt`

- [ ] **Step 1: Write the failing test (includes Review Focus 2)**

```kotlin
package io.github.santiquiroz.blindside.shared.hud

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GameClockTest {
    @Test
    fun `remaining counts down and clamps at zero, never negative`() {
        assertEquals(18_000_000L, gameRemainingMs(1_000L, 1_000L, 18_000_000L))
        assertEquals(17_999_000L, gameRemainingMs(1_000L, 2_000L, 18_000_000L))
        assertEquals(0L, gameRemainingMs(1_000L, 99_999_999L, 18_000_000L))
    }

    @Test
    fun `clock skew backward and zero duration both clamp to zero`() {
        assertEquals(18_000_000L, gameRemainingMs(5_000L, 1_000L, 18_000_000L))
        assertEquals(0L, gameRemainingMs(0L, 10_000L, 0L))
    }

    @Test
    fun `the clock text is h mm ss above an hour and mm ss below`() {
        assertEquals("1:00:00", gameClockText(3_600_000L))
        assertEquals("04:09", gameClockText(249_000L))
        assertEquals("00:00", gameClockText(0L))
    }

    @Test
    fun `a threshold fires once as remaining crosses it and not again`() {
        assertTrue(crossedThreshold(FIVE_MIN_MS + 1_000L, FIVE_MIN_MS - 1_000L, FIVE_MIN_MS))
        assertFalse(crossedThreshold(FIVE_MIN_MS - 1_000L, FIVE_MIN_MS - 2_000L, FIVE_MIN_MS))
        assertTrue(crossedThreshold(1_000L, 0L, 0L))
        assertFalse(crossedThreshold(0L, 0L, 0L))
    }

    @Test
    fun `durations cycle through the fixed options`() {
        assertEquals(7_200_000L, nextGameDuration(3_600_000L))
        assertEquals(0L, nextGameDuration(18_000_000L))
        assertEquals(3_600_000L, nextGameDuration(0L))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest --tests '*GameClockTest' --console=plain`
Expected: FAIL.

- [ ] **Step 3: Implement the clock**

```kotlin
package io.github.santiquiroz.blindside.shared.hud

import java.util.Locale

const val DEFAULT_GAME_DURATION_MS = 18_000_000L
const val FIVE_MIN_MS = 300_000L

val GAME_DURATION_OPTIONS_MS = listOf(3_600_000L, 7_200_000L, 10_800_000L, 18_000_000L, 0L)

private const val MS_PER_SECOND = 1_000L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_MINUTE = 60L

// The game clock starts with "Iniciar radar"; before the duration elapses it shows what is left, clamped at zero.
fun gameRemainingMs(startElapsedMs: Long, nowElapsedMs: Long, durationMs: Long): Long =
    (durationMs - (nowElapsedMs - startElapsedMs)).coerceIn(0L, durationMs)

fun gameClockText(remainingMs: Long): String {
    val totalSeconds = remainingMs / MS_PER_SECOND
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    if (hours > 0) return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}

// True only on the tick that carries remaining from above the threshold to at-or-below it, so a pulse fires once.
fun crossedThreshold(prevRemainingMs: Long, nowRemainingMs: Long, thresholdMs: Long): Boolean =
    prevRemainingMs > thresholdMs && nowRemainingMs <= thresholdMs

fun nextGameDuration(durationMs: Long): Long {
    val index = GAME_DURATION_OPTIONS_MS.indexOf(durationMs)
    if (index < 0) return GAME_DURATION_OPTIONS_MS.first()
    return GAME_DURATION_OPTIONS_MS[(index + 1) % GAME_DURATION_OPTIONS_MS.size]
}
```

- [ ] **Step 4: Add the setting + persistence + label**

In `AppSettings.kt` add `val gameDurationMs: Long = DEFAULT_GAME_DURATION_MS,` (import `DEFAULT_GAME_DURATION_MS` from `shared.hud`). In `SettingsPreferences.kt` add `val GAME_DURATION_MS = longPreferencesKey("game_duration_ms")`, read `gameDurationMs = prefs[Keys.GAME_DURATION_MS] ?: defaults.gameDurationMs`, write it. In `Labels.kt`:

```kotlin
fun gameDurationLabel(durationMs: Long): String = when (durationMs) {
    0L -> "Sin límite"
    else -> "${durationMs / 3_600_000L} h"
}
```

- [ ] **Step 5: Run the clock test (pass), extend the preferences round-trip, run the full gate**

Add to `SettingsPreferencesTest.kt` a case asserting `gameDurationMs` round-trips. Run the mandatory gate.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd C:/personal/blindside && git add watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/hud/GameClock.kt watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/settings/AppSettings.kt watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/settings/SettingsPreferences.kt watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/Labels.kt watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/hud/GameClockTest.kt watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/settings/SettingsPreferencesTest.kt && git commit -m "feat: reloj de partida con duración configurable y pulsos de 5 min y fin (§8.3)"
```

---

### Task 10: Tactical points — geometry and state (pure)

**Files:**
- Create: `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/tactical/TacticalGeo.kt`
- Create: `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/tactical/TacticalPoints.kt`
- Test: `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/tactical/TacticalGeoTest.kt`
- Test: `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/tactical/TacticalPointsTest.kt`

**Interfaces:**
- Consumes: `normalizedDeg` from `shared.compass`.
- Produces:
  - `data class GeoPoint(val latDeg: Double, val lonDeg: Double)`
  - `fun bearingDeg(from: GeoPoint, to: GeoPoint): Double`
  - `fun distanceM(from: GeoPoint, to: GeoPoint): Double`
  - `fun wedgeScreenAngleDeg(bearingToPointDeg: Double, azimuthDeg: Double): Float`
  - `enum class TacticalKind { BASE, SPAWN, OBJECTIVE }`
  - `fun nextTacticalKind(current: TacticalKind): TacticalKind`
  - `fun withTacticalPoint(points: Map<TacticalKind, GeoPoint>, kind: TacticalKind, at: GeoPoint): Map<TacticalKind, GeoPoint>`
  - `fun tacticalDistanceLabel(meters: Double): String`

- [ ] **Step 1: Write the failing geo test (includes same-point edge)**

```kotlin
package io.github.santiquiroz.blindside.shared.tactical

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TacticalGeoTest {
    private val origin = GeoPoint(5.0689, -75.5174)   // Manizales

    @Test
    fun `due north and due east bearings come out at zero and ninety`() {
        assertEquals(0.0, bearingDeg(origin, GeoPoint(origin.latDeg + 0.01, origin.lonDeg)), 0.5)
        assertEquals(90.0, bearingDeg(origin, GeoPoint(origin.latDeg, origin.lonDeg + 0.01)), 0.5)
    }

    @Test
    fun `distance over a short hop matches the haversine to the metre and is zero at the same point`() {
        assertEquals(0.0, distanceM(origin, origin), 1e-6)
        assertTrue(distanceM(origin, GeoPoint(origin.latDeg + 0.001, origin.lonDeg)) in 110.0..112.0)
    }

    @Test
    fun `the wedge angle is the bearing minus the azimuth so it tracks north as the body turns`() {
        assertEquals(0f, wedgeScreenAngleDeg(90.0, 90.0), 1e-4f)
        assertEquals(315f, wedgeScreenAngleDeg(0.0, 45.0), 1e-4f)
    }

    @Test
    fun `an identical point gives a defined bearing, never NaN`() {
        assertTrue(bearingDeg(origin, origin) in 0.0..360.0)
    }

    @Test
    fun `the distance label is metres below a kilometre and kilometres above`() {
        assertEquals("040 m", tacticalDistanceLabel(40.4))
        assertEquals("1.2 km", tacticalDistanceLabel(1_240.0))
    }
}
```

- [ ] **Step 2: Write the failing points test**

```kotlin
package io.github.santiquiroz.blindside.shared.tactical

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TacticalPointsTest {
    @Test
    fun `a long-press cycles which slot it marks`() {
        assertEquals(TacticalKind.SPAWN, nextTacticalKind(TacticalKind.BASE))
        assertEquals(TacticalKind.OBJECTIVE, nextTacticalKind(TacticalKind.SPAWN))
        assertEquals(TacticalKind.BASE, nextTacticalKind(TacticalKind.OBJECTIVE))
    }

    @Test
    fun `marking a slot replaces only that slot`() {
        val base = withTacticalPoint(emptyMap(), TacticalKind.BASE, GeoPoint(5.0, -75.0))
        val both = withTacticalPoint(base, TacticalKind.OBJECTIVE, GeoPoint(5.1, -75.1))
        assertEquals(setOf(TacticalKind.BASE, TacticalKind.OBJECTIVE), both.keys)
        val moved = withTacticalPoint(both, TacticalKind.BASE, GeoPoint(5.2, -75.2))
        assertEquals(GeoPoint(5.2, -75.2), moved.getValue(TacticalKind.BASE))
        assertEquals(GeoPoint(5.1, -75.1), moved.getValue(TacticalKind.OBJECTIVE))
    }
}
```

- [ ] **Step 3: Run both and watch them fail**

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest --tests '*TacticalGeoTest' --tests '*TacticalPointsTest' --console=plain`
Expected: FAIL.

- [ ] **Step 4: Implement the geometry**

```kotlin
package io.github.santiquiroz.blindside.shared.tactical

import io.github.santiquiroz.blindside.shared.compass.normalizedDeg
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val latDeg: Double, val lonDeg: Double)

private const val EARTH_RADIUS_M = 6_371_000.0
private const val METRES_PER_KM = 1_000.0

fun bearingDeg(from: GeoPoint, to: GeoPoint): Double {
    val lat1 = Math.toRadians(from.latDeg)
    val lat2 = Math.toRadians(to.latDeg)
    val dLon = Math.toRadians(to.lonDeg - from.lonDeg)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return normalizedDeg(Math.toDegrees(atan2(y, x)))
}

fun distanceM(from: GeoPoint, to: GeoPoint): Double {
    val lat1 = Math.toRadians(from.latDeg)
    val lat2 = Math.toRadians(to.latDeg)
    val dLat = Math.toRadians(to.latDeg - from.latDeg)
    val dLon = Math.toRadians(to.lonDeg - from.lonDeg)
    val a = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
    return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
}

// The ring turns with north, so a wedge sits at the real bearing minus the watch azimuth, like the compass marks.
fun wedgeScreenAngleDeg(bearingToPointDeg: Double, azimuthDeg: Double): Float =
    normalizedDeg(bearingToPointDeg - azimuthDeg).toFloat()

fun tacticalDistanceLabel(meters: Double): String {
    if (meters < METRES_PER_KM) return String.format(Locale.ROOT, "%03d m", meters.toInt())
    return String.format(Locale.ROOT, "%.1f km", meters / METRES_PER_KM)
}
```

- [ ] **Step 5: Implement the points state**

```kotlin
package io.github.santiquiroz.blindside.shared.tactical

enum class TacticalKind { BASE, SPAWN, OBJECTIVE }

fun nextTacticalKind(current: TacticalKind): TacticalKind =
    TacticalKind.entries[(current.ordinal + 1) % TacticalKind.entries.size]

fun withTacticalPoint(points: Map<TacticalKind, GeoPoint>, kind: TacticalKind, at: GeoPoint): Map<TacticalKind, GeoPoint> =
    points + (kind to at)
```

- [ ] **Step 6: Run both (pass) and the full gate**

Run: the two `--tests` runs, then the mandatory gate.
Expected: PASS; android-shared grew by 7 tests.

- [ ] **Step 7: Commit**

```bash
cd C:/personal/blindside && git add watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/tactical watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/tactical && git commit -m "feat: geometría de puntos tácticos (rumbo, distancia, cuña) y su estado (§8.3)"
```

---

### Task 11: Contextual alert queue (pure)

**Files:**
- Create: `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/hud/AlertQueue.kt`
- Modify: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/Labels.kt`
- Test: `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/hud/AlertQueueTest.kt`

**Interfaces:**
- Consumes: `ScreenMode` from `shared.settings`.
- Produces:
  - `enum class AlertKind(val critical: Boolean) { BELT_LINK_DOWN(true), BATTERY_LOW_WATCH(false), BATTERY_LOW_PHONE(false), BATTERY_LOW_BELT(false), HYDRATION(false), DUSK_SOON(false) }`
  - `const val ALERT_SHOW_MS = 4_000L`, `HYDRATION_PERIOD_MS = 2_700_000L`
  - `data class AlertQueueState(val showing: AlertKind? = null, val shownSinceMs: Long? = null, val pending: List<AlertKind> = emptyList())`
  - `fun enqueueAlert(state: AlertQueueState, kind: AlertKind): AlertQueueState`
  - `fun stepAlertQueue(state: AlertQueueState, nowMs: Long, showMs: Long = ALERT_SHOW_MS): AlertQueueState`
  - `fun hydrationDue(lastHydrationMs: Long?, nowMs: Long, periodMs: Long = HYDRATION_PERIOD_MS): Boolean`
  - `fun alertVibrates(kind: AlertKind, mode: ScreenMode): Boolean`
  - `alertText(kind)` in `Labels.kt`

- [ ] **Step 1: Write the failing test (includes Review Focus 5)**

```kotlin
package io.github.santiquiroz.blindside.shared.hud

import io.github.santiquiroz.blindside.shared.settings.ScreenMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlertQueueTest {
    @Test
    fun `three alerts in one tick show one at a time, four seconds each`() {
        var s = AlertQueueState()
        s = enqueueAlert(s, AlertKind.BATTERY_LOW_WATCH)
        s = enqueueAlert(s, AlertKind.BATTERY_LOW_PHONE)
        s = enqueueAlert(s, AlertKind.BELT_LINK_DOWN)
        s = stepAlertQueue(s, 0L)
        assertEquals(AlertKind.BATTERY_LOW_WATCH, s.showing)
        s = stepAlertQueue(s, 3_999L)
        assertEquals(AlertKind.BATTERY_LOW_WATCH, s.showing)
        s = stepAlertQueue(s, 4_000L)
        assertEquals(AlertKind.BATTERY_LOW_PHONE, s.showing)
        s = stepAlertQueue(s, 8_000L)
        assertEquals(AlertKind.BELT_LINK_DOWN, s.showing)
        s = stepAlertQueue(s, 12_000L)
        assertEquals(null, s.showing)
    }

    @Test
    fun `an alert already queued or showing is not duplicated`() {
        var s = enqueueAlert(AlertQueueState(), AlertKind.HYDRATION)
        s = enqueueAlert(s, AlertKind.HYDRATION)
        assertEquals(1, s.pending.size)
        s = stepAlertQueue(s, 0L)
        s = enqueueAlert(s, AlertKind.HYDRATION)
        assertTrue(s.pending.isEmpty())
    }

    @Test
    fun `in Sigilo only the critical belt-link-down vibrates`() {
        assertTrue(alertVibrates(AlertKind.BELT_LINK_DOWN, ScreenMode.SIGILO))
        assertFalse(alertVibrates(AlertKind.BATTERY_LOW_WATCH, ScreenMode.SIGILO))
        assertTrue(alertVibrates(AlertKind.BATTERY_LOW_WATCH, ScreenMode.VISTA))
    }

    @Test
    fun `hydration is due first at startup and then every forty-five minutes`() {
        assertTrue(hydrationDue(null, 0L))
        assertFalse(hydrationDue(1_000L, 1_000L + 2_699_999L))
        assertTrue(hydrationDue(1_000L, 1_000L + 2_700_000L))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest --tests '*AlertQueueTest' --console=plain`
Expected: FAIL.

- [ ] **Step 3: Implement the queue**

```kotlin
package io.github.santiquiroz.blindside.shared.hud

import io.github.santiquiroz.blindside.shared.settings.ScreenMode

enum class AlertKind(val critical: Boolean) {
    BELT_LINK_DOWN(true),
    BATTERY_LOW_WATCH(false),
    BATTERY_LOW_PHONE(false),
    BATTERY_LOW_BELT(false),
    HYDRATION(false),
    DUSK_SOON(false),
}

const val ALERT_SHOW_MS = 4_000L
const val HYDRATION_PERIOD_MS = 2_700_000L

data class AlertQueueState(
    val showing: AlertKind? = null,
    val shownSinceMs: Long? = null,
    val pending: List<AlertKind> = emptyList(),
)

// Each kind lines up at most once; a repeat while it is still showing or waiting is dropped.
fun enqueueAlert(state: AlertQueueState, kind: AlertKind): AlertQueueState {
    if (state.showing == kind || kind in state.pending) return state
    return state.copy(pending = state.pending + kind)
}

// One alert at a time for its window; when it expires the next in line takes the rear slot.
fun stepAlertQueue(state: AlertQueueState, nowMs: Long, showMs: Long = ALERT_SHOW_MS): AlertQueueState {
    val expired = state.showing != null && state.shownSinceMs != null && nowMs - state.shownSinceMs >= showMs
    if (state.showing != null && !expired) return state
    val next = state.pending.firstOrNull() ?: return AlertQueueState()
    return AlertQueueState(showing = next, shownSinceMs = nowMs, pending = state.pending.drop(1))
}

fun hydrationDue(lastHydrationMs: Long?, nowMs: Long, periodMs: Long = HYDRATION_PERIOD_MS): Boolean {
    if (lastHydrationMs == null) return true
    return nowMs - lastHydrationMs >= periodMs
}

// Spec §8.3: in Sigilo only critical alerts vibrate; in Vista any alert may.
fun alertVibrates(kind: AlertKind, mode: ScreenMode): Boolean = kind.critical || mode == ScreenMode.VISTA
```

- [ ] **Step 4: Add the labels**

In `Labels.kt`:

```kotlin
fun alertText(kind: AlertKind): String = when (kind) {
    AlertKind.BELT_LINK_DOWN -> "Enlace del cinturón caído"
    AlertKind.BATTERY_LOW_WATCH -> "Batería baja: reloj"
    AlertKind.BATTERY_LOW_PHONE -> "Batería baja: celular"
    AlertKind.BATTERY_LOW_BELT -> "Batería baja: cinturón"
    AlertKind.HYDRATION -> "Hidrátate"
    AlertKind.DUSK_SOON -> "Atardecer próximo"
}
```

- [ ] **Step 5: Run it (pass) and the full gate**

Run: the `--tests '*AlertQueueTest'` run, then the mandatory gate.
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd C:/personal/blindside && git add watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/hud/AlertQueue.kt watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/Labels.kt watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/hud/AlertQueueTest.kt && git commit -m "feat: cola de avisos contextuales de a uno con regla de vibración por sigilo (§8.3)"
```

---

### Task 12: Glance panel model (pure)

**Files:**
- Create: `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/hud/GlancePanel.kt`
- Test: `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/hud/GlancePanelTest.kt`

**Interfaces:**
- Produces:
  - `const val GLANCE_HOLD_MS = 3_000L`
  - `fun glanceVisible(tapAtMs: Long?, nowMs: Long, holdMs: Long = GLANCE_HOLD_MS): Boolean`
  - `data class GlanceRow(val label: String, val value: String)`
  - `data class GlanceData(val clockText: String, val gameTimeText: String, val heartRate: Int?, val steps: Int?, val distanceM: Double?, val watchBattery: Int?, val phoneBattery: Int?, val beltBattery: Int?)`
  - `fun glanceRows(data: GlanceData): List<GlanceRow>`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.santiquiroz.blindside.shared.hud

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GlancePanelTest {
    @Test
    fun `the panel shows for the hold window after a centre tap and then hides`() {
        assertFalse(glanceVisible(null, 10_000L))
        assertTrue(glanceVisible(10_000L, 10_000L))
        assertTrue(glanceVisible(10_000L, 12_999L))
        assertFalse(glanceVisible(10_000L, 13_000L))
    }

    @Test
    fun `rows carry the three cards and show a dash for what is unknown`() {
        val rows = glanceRows(
            GlanceData("14:05", "1:59:30", heartRate = 132, steps = 4210, distanceM = 2400.0,
                watchBattery = 61, phoneBattery = null, beltBattery = 88),
        )
        assertEquals("Pulso", rows[1].label)
        assertEquals("132", rows[1].value)
        assertTrue(rows.any { it.label == "Baterías" && it.value == "61% / --% / 88%" })
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:testDebugUnitTest --tests '*GlancePanelTest' --console=plain`
Expected: FAIL.

- [ ] **Step 3: Implement**

```kotlin
package io.github.santiquiroz.blindside.shared.hud

import io.github.santiquiroz.blindside.shared.tactical.tacticalDistanceLabel

const val GLANCE_HOLD_MS = 3_000L

data class GlanceRow(val label: String, val value: String)

data class GlanceData(
    val clockText: String,
    val gameTimeText: String,
    val heartRate: Int?,
    val steps: Int?,
    val distanceM: Double?,
    val watchBattery: Int?,
    val phoneBattery: Int?,
    val beltBattery: Int?,
)

fun glanceVisible(tapAtMs: Long?, nowMs: Long, holdMs: Long = GLANCE_HOLD_MS): Boolean {
    if (tapAtMs == null) return false
    return nowMs - tapAtMs in 0 until holdMs
}

fun glanceRows(data: GlanceData): List<GlanceRow> = listOf(
    GlanceRow("Hora / partida", "${data.clockText} · ${data.gameTimeText}"),
    GlanceRow("Pulso", intOrDash(data.heartRate)),
    GlanceRow("Pasos / dist.", "${intOrDash(data.steps)} · ${data.distanceM?.let(::tacticalDistanceLabel) ?: "--"}"),
    GlanceRow("Baterías", "${pct(data.watchBattery)} / ${pct(data.phoneBattery)} / ${pct(data.beltBattery)}"),
)

private fun intOrDash(value: Int?): String = value?.toString() ?: "--"

private fun pct(value: Int?): String = value?.let { "$it%" } ?: "--%"
```

- [ ] **Step 4: Run it (pass) and the full gate**

Run: the `--tests '*GlancePanelTest'` run, then the mandatory gate.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd C:/personal/blindside && git add watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/hud/GlancePanel.kt watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/hud/GlancePanelTest.kt && git commit -m "feat: panel de vistazo de 3 s con hora, pulso, pasos y baterías (§8.3)"
```

---

### Task 13: Wear HUD wiring (bezel, clock, tactical points, alerts, glance)

**Files:**
- Modify: `watch/wear-app/src/main/AndroidManifest.xml`
- Modify: `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/permissions/SessionPermissions.kt` (add `ACCESS_FINE_LOCATION` to the request set — the only way the dangerous runtime permission is ever asked for)
- Modify: `watch/android-shared/src/test/kotlin/io/github/santiquiroz/blindside/shared/permissions/SessionPermissionsTest.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/TacticalLocation.kt`
- Create: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/HudOverlay.kt`
- Modify: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/RadarScreen.kt`
- Modify: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/CompassRingCanvas.kt`
- Modify: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/SettingsScreen.kt`
- Modify: `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/session/SessionStore.kt` (add `gameStartElapsedMs: Long? = null` to `SessionUiState`, set on `startedState`)
- Modify: `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/session/RunningSession.kt` (service-side game-clock pulse loop, screen-off-safe)
- Modify (if the critical-alert gate needs it): `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/session/SessionEngine.kt` (gate the system buzz with `alertVibrates(kind, screenMode)` so Sigilo buzzes only the critical alert — the engine already owns `buzzSystem` and the forwarded screen mode)
- Modify: `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/session/WearSession.kt` or the session start path
- Modify: `watch/wear-app/README.md`

**Interfaces:**
- Consumes: Tasks 8–12 shared APIs, plus `gameRemainingMs`/`gameClockText`/`crossedThreshold` and the session's `HapticPlayer`/`HapticSink` for the pulse; `alertVibrates` for the Sigilo vibration rule; `wedgeScreenAngleDeg`/`bearingDeg`/`distanceM` and the ring draws for the wedges; `SESSION_PERMISSIONS` for the runtime request.
- Produces: no new shared API; the only shared change is adding `ACCESS_FINE_LOCATION` to `SESSION_PERMISSIONS` (JVM-tested) and `SessionUiState.gameStartElapsedMs`. Gated by the full per-task gate (`:android-shared:testDebugUnitTest` + `:wear-app:assembleDebug`) + README.

The display wiring (bezel, wedges, alert line, glance) is wear-only and has no JVM test. The permission-set change is JVM-tested in `SessionPermissionsTest` (Step 1b). The game-clock pulses and the critical-alert vibration run from the always-running session service (Steps 3 and 5), not the Compose UI, because in Sigilo the screen is off and the frame clock stops.

- [ ] **Step 1: Declare the location permission**

In `AndroidManifest.xml`, add once, next to the other `uses-permission` lines:

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

- [ ] **Step 1b: Actually request it at runtime (otherwise it stays denied forever)**

`ACCESS_FINE_LOCATION` is a dangerous runtime permission (targetSdk 36); a manifest line alone never grants it. Tactical points are optional, so let it ride along with the existing session-start prompt exactly like steps/notifications do — it must not block the belt session when denied. In `SessionPermissions.kt`:

```kotlin
const val PERMISSION_ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"

val SESSION_PERMISSIONS: Array<String> = arrayOf(
    PERMISSION_BLUETOOTH_SCAN,
    PERMISSION_BLUETOOTH_CONNECT,
    PERMISSION_ACTIVITY_RECOGNITION,
    PERMISSION_POST_NOTIFICATIONS,
    PERMISSION_ACCESS_FINE_LOCATION,
)
```

Leave `startDecision`/`bluetoothGranted`/`shouldRequestPermissions` untouched: location is optional and must not gate the start (same as `POST_NOTIFICATIONS`). The existing `RequestMultiplePermissions` launcher in `HomeScreen` already launches `SESSION_PERMISSIONS`, so this is the prompt. In `SessionPermissionsTest.kt`, add `PERMISSION_ACCESS_FINE_LOCATION` to the `requests every permission the session can use` expected set, and add a case asserting `startDecision(allGranted + (PERMISSION_ACCESS_FINE_LOCATION to false))` still returns `StartDecision.Start` (denying location never blocks the start). The on-device `getLastKnownLocation` guard (Step 2) now has a permission that can actually be granted, so the §8.3 tactical-points feature is reachable instead of permanently "Sin GPS".

- [ ] **Step 2: Last-known-fix reader**

Create `TacticalLocation.kt`: a small composable/helper `rememberLastFix()` returning `() -> GeoPoint?` that, when permission is granted, reads `LocationManager.getLastKnownLocation(GPS_PROVIDER)` (falling back to `FUSED_PROVIDER` when present, then `NETWORK_PROVIDER`) and maps it to `GeoPoint`, and requests a single update to warm the fix. Guard every call with a permission check; return null when denied (the long-press then shows "Sin GPS" and, per spec, the phone bridge fix is a later lever — note it in the README, do not build it here).

- [ ] **Step 3: HUD overlay composable (display only) + screen-off-safe critical vibration**

Create `HudOverlay.kt` holding the rear-half + bezel Compose for **display**: the rotating bezel window (`bezelFieldAt` → HEADING uses `headingText`, CLOCK uses the wall clock, GAME_TIME uses `gameClockText`), a short-tap that calls `toggledPin`; the one-at-a-time alert line (`stepAlertQueue` stepped on the Compose second clock purely to *show* `alertText` in the rear half while the screen is on); and the glance panel (`glanceVisible` + `glanceRows`). All numbers in `BlindsideFonts.Mono`; rear-half placement reuses the existing bottom-aligned, `graphicsLayer`-rotated container so it tracks the posture rotation and never covers contacts.

Do **not** drive any vibration from this composable. In Sigilo the screen is off by default (spec §8, Global Constraints) and the Compose frame/second clock stops — which is exactly when the belt-link-down alert must still reach the player. Raise and vibrate alerts from the always-running session engine/service, the same path the existing contact haptics already use (`SessionEngine.react`/`buzzSystem` on the pipeline thread, driven by BLE link/system events, not frames): when an alert is raised, gate the buzz with `alertVibrates(kind, screenMode)` so in Sigilo only the critical `BELT_LINK_DOWN` vibrates and in Vista any alert may. The composable only renders the queue line; the service owns the buzz.

- [ ] **Step 4: Draw tactical wedges on the ring**

In `CompassRingCanvas.kt`, add `drawTacticalWedges(points: Map<TacticalKind, GeoPoint>, here: GeoPoint?, azimuthDeg: Double, ring: RingGeometry, colors)` that, for each marked point with a known `here`, places a coloured wedge at `wedgeScreenAngleDeg(bearingDeg(here, point), azimuthDeg)` with its `tacticalDistanceLabel(distanceM(here, point))` in Mono. One distinct colour per `TacticalKind` (reuse tokens: BASE `accent`, SPAWN `accent-dim`, OBJECTIVE `warn`), and a shape difference too (colour-is-never-the-only-signal): e.g. filled vs. hollow vs. dashed wedge. Call it from the ring layer in `RadarScreen` after the ring marks.

- [ ] **Step 5: Wire the game clock start + pulses (pulses from the service, not the UI)**

Store a `gameStartElapsedMs` when a session starts: a new nullable field on `SessionUiState` (defaulted null, set on `startedState` in `SessionStore`/the session start path). In the HUD, compute `gameClockText(gameRemainingMs(start, now, settings.gameDurationMs))` only to **display** the countdown.

Fire the 5-min and end pulses from the always-running session service, never from `RadarScreen`/`HudOverlay`: add a screen-off-safe loop in `RunningSession` (a coroutine on the session scope like the existing `repeatEvery(1_000L)`/`tickScenes` loops, independent of `withFrameNanos` and of whether the radar is visible) that reads `gameStartElapsedMs` + `settings.gameDurationMs`, computes `remaining = gameRemainingMs(start, now, duration)`, keeps the previous remaining, and plays one vibration through the session's `HapticPlayer` when `crossedThreshold(prev, remaining, FIVE_MIN_MS)` or `crossedThreshold(prev, remaining, 0L)` fires. This mirrors why contact haptics live in the engine: the clock must pulse at 5 min and 0 even with the screen off in Sigilo. Skip the loop entirely when `gameDurationMs == 0L` (sin límite): the HUD shows elapsed time and no pulses fire.

- [ ] **Step 6: Long-press marks a tactical point**

In `RadarScreen.kt`, add a `Modifier.pointerInput` detecting a long press over the fan that reads `rememberLastFix()` and, when non-null, calls `SessionStore` to store `withTacticalPoint(current, nextTacticalKind(lastKind), fix)` (add an in-memory `tacticalPoints`/`lastTacticalKind` to `SessionStore`, cleared on stop). A centre tap toggles the glance panel (`tapAtMs`). Keep the two gestures distinct (long-press vs. tap) and both off the contact zone's semantics.

- [ ] **Step 7: Settings entries**

In `SettingsScreen.kt` add `item { SettingChip("Duración de partida", gameDurationLabel(settings.gameDurationMs)) { onUpdate { it.copy(gameDurationMs = nextGameDuration(it.gameDurationMs)) } } }`.

- [ ] **Step 8: Build, README, commit**

```bash
cd C:/personal/blindside/watch && ./gradlew :android-shared:cleanTestDebugUnitTest :wear-app:cleanTestDebugUnitTest :android-shared:testDebugUnitTest :wear-app:testDebugUnitTest :wear-app:assembleDebug --console=plain
cd C:/personal/blindside && git add watch/wear-app watch/wear-app/README.md && git commit -m "feat: HUD táctico del reloj (bisel, reloj de partida, puntos GPS, avisos, vistazo) (§8.3)"
```

---

### Task 14: Remove live "eliminated" from shared session, settings, and the watch UI (§8.4)

**Files:**
- Delete: `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/session/EliminatedToggle.kt`
- Modify (shared): `AppSettings.kt`, `SettingsPreferences.kt`, `SessionStore.kt`, `SessionInput.kt`, `SessionEngine.kt`, `RunningSession.kt`, `SessionSource.kt`, `SessionService.kt`, `SessionCommands.kt`, `SessionActions.kt`, `PipelinePort.kt`, `RecordEncoding.kt` (all under `shared/...`)
- Modify (watch UI — the wear code that calls the removed symbols): `watch/wear-app/src/main/kotlin/io/github/santiquiroz/blindside/wear/ui/radar/RadarScreen.kt`, `ui/HomeScreen.kt`, `ui/BlindsideApp.kt`, `ui/ScreenPolicy.kt`, `session/SessionNotification.kt`
- Modify tests (shared): `SessionStoreTest.kt`, `SessionSourceTest.kt`, `SessionEngineTest.kt`, `AppSettingsTest.kt`, `RecordEncodingTest.kt`, `SettingsPreferencesTest.kt`, `SettingsRepositoryTest.kt`, `SharedSettingsTest.kt` (only where they reference the removed symbols)
- Modify tests (wear): `watch/wear-app/src/test/kotlin/io/github/santiquiroz/blindside/wear/ui/ScreenPolicyTest.kt` (`keepScreenOn` loses its `eliminated` arg)
- Keep in this task (deleted in Task 15, when the phone stops calling it): `shared/radar/RadarLabels.kt` `eliminatedActionLabel` and `RadarLabelsTest.kt`. Task 14 only removes the **watch's** calls to `eliminatedActionLabel`; the function itself still compiles because the phone viewer references it until Task 15.
- Keep untouched: `radar-core` `RadarPipeline.setEliminated`, `BsrecPayloads` `SessionMode.ELIMINATED`/`readModeChange`, `replay/Replay.kt` — old `.bsrec` must still replay eliminated (spec §8.4).

**Interfaces:**
- Consumes: nothing new.
- Produces (the new shapes later code compiles against):
  - `SessionInput.ModeChanged(val screenMode: ScreenMode, val nowNanos: Long)` — the `eliminated` field is gone.
  - `fun screenModeToSessionMode(screenMode: ScreenMode): SessionMode` replaces `sessionMode(eliminated, screenMode)`; returns `VIEW`/`STEALTH` only.
  - `fun ongoingStatus(source: SessionSource, purpose: SessionPurpose = SessionPurpose.GAME): String` — no `eliminated` parameter.
  - `PipelinePort` loses `setEliminated`; `RadarPipelineAdapter` loses its override. `RadarPipeline.setEliminated` stays (radar-core).
  - `SessionUiState` loses `eliminated`; `SessionStore` loses `toggleEliminated`/`eliminatedToggled`; `AppSettings` loses `eliminated`; `forNewSession` is deleted.
  - `SessionActions.ACTION_TOGGLE_ELIMINATED` and `SessionCommands.toggleEliminatedIntent` are deleted.
  - Watch UI: `RadarScreen`/`HomeScreen`/`BlindsideApp` drop the `onToggleEliminated` plumbing, `SessionNotification` drops the "Eliminado" action, and `keepScreenOn(mode)` drops the `eliminated` arg. `eliminatedActionLabel` is *not* deleted here (phone still uses it — Task 15).

This task is domain/session-state surgery (Never-delegate territory). **It must remove the watch UI that calls the removed symbols in the same task**, because every task's mandatory gate runs `:wear-app:testDebugUnitTest` + `:wear-app:assembleDebug` and the golden rule gates on `:wear-app:assembleDebug` — deleting the shared symbols without the wear edits would leave `BlindsideApp`/`RadarScreen`/`HomeScreen`/`SessionNotification` referencing gone symbols and the wear build would fail. Make the shared edits and the watch-UI edits together, then let the compiler and the tests drive the rest. The **phone** is the only thing left broken after this task; it is fixed in Task 15 (the golden rule does not gate on `:phone-app`).

- [ ] **Step 1: Pin the surviving behaviour first (RED by deletion)**

Before editing production code, update the tests that encode the removed behaviour so they state the new contract:
- In `SessionEngineTest.kt`: **delete** `a deferred contact is dropped once the player is eliminated`; **rewrite** `a mode change reaches the pipeline and is recorded` into `a screen-mode change is recorded and does not touch the pipeline`:

```kotlin
    @Test
    fun `a screen-mode change is recorded and does not touch the pipeline`() {
        engine.handle(SessionInput.ModeChanged(screenMode = ScreenMode.VISTA, nowNanos = at(7)))
        assertTrue(pipeline.calls.isEmpty())
        assertEquals(SessionMode.VIEW, BsrecPayloads.readModeChange(records.items.single().payload))
    }
```

  Remove the fake pipeline's `setEliminated` override and its `"eliminated:$on"` call recorder.
- In `SessionStoreTest.kt`: delete `toggling eliminated flips only that flag` and `a new session never starts eliminated`.
- In `SessionSourceTest.kt`: rewrite the ongoing-status tests to the new signature (`ongoingStatus(SessionSource.DEMO)` → "Demo en curso", `ongoingStatus(SessionSource.BELT)` → "Partida en curso", `ongoingStatus(SessionSource.BELT, SessionPurpose.DIAGNOSTIC)` → "Diagnóstico del cinturón"); drop the two "Eliminado" assertions.
- In `AppSettingsTest.kt`: delete `a new session never starts eliminated`.
- In `RecordEncodingTest.kt`: rewrite the mode test to the new `screenModeToSessionMode`:

```kotlin
    @Test
    fun `a screen-mode change records view or stealth`() {
        assertEquals(SessionMode.VIEW, modeOf(SessionInput.ModeChanged(screenMode = ScreenMode.VISTA, nowNanos = at(1))))
        assertEquals(SessionMode.STEALTH, modeOf(SessionInput.ModeChanged(screenMode = ScreenMode.SIGILO, nowNanos = at(1))))
    }
```

- In `SettingsPreferencesTest.kt`/`SettingsRepositoryTest.kt`/`SharedSettingsTest.kt`: drop any `eliminated = ...` from the `AppSettings(...)` fixtures (it is simply gone; `SharedSettings` never carried it).

- [ ] **Step 2: Run the tests to confirm they fail to compile / fail**

Run: `cd C:/personal/blindside/watch && ./gradlew :android-shared:compileDebugUnitTestKotlin --console=plain`
Expected: FAIL — references to removed symbols don't resolve yet.

- [ ] **Step 3: Edit the shared production code**

- `AppSettings.kt`: remove `val eliminated: Boolean = false,` and delete `fun AppSettings.forNewSession()`.
- `SettingsPreferences.kt`: remove `Keys.ELIMINATED`, its read in `settingsFrom`, and its write in `writeSettings`.
- `SessionStore.kt`: remove `eliminated` from `SessionUiState`, remove `toggleEliminated()`, remove `fun eliminatedToggled(...)`.
- `SessionInput.kt`: change `data class ModeChanged(val screenMode: ScreenMode, val nowNanos: Long) : SessionInput`.
- `SessionEngine.kt`: in `feedWithoutEvents`, delete the `is SessionInput.ModeChanged -> pipeline.setEliminated(...)` branch; delete the `eliminated` field, `trackEliminated(input)` and its call; make `replayDeferred` unconditional (`if (input is SessionInput.PlayDeferred) playOrDefer(input.alert)`).
- `PipelinePort.kt`: remove `fun setEliminated(on: Boolean)` from the interface and the `RadarPipelineAdapter` override.
- `RunningSession.kt`: delete the `forNewSession` call in `start()` (the first line `settings.update { it.forNewSession() }`); change `forwardMode`/`modeChanges` to track only screen mode:

```kotlin
    private suspend fun forwardMode() {
        settings.settings.map { it.screenMode }.distinctUntilChanged().collect { mode ->
            screenMode = mode
            inputs.trySend(SessionInput.ModeChanged(mode, nowNanos()))
        }
    }
```

  Remove the now-unused `combine`/`SessionStore` imports if nothing else uses them.
- `RecordEncoding.kt`: rename `sessionMode(eliminated, screenMode)` to `screenModeToSessionMode(screenMode)` returning `if (screenMode == ScreenMode.VISTA) SessionMode.VIEW else SessionMode.STEALTH`; update the `SessionInput.ModeChanged` branch in `recordFor` to call it with `input.screenMode`.
- `SessionSource.kt`: `ongoingStatus(source, purpose)` drops the `eliminated` branch (keep DIAGNOSTIC, DEMO, else).
- `SessionActions.kt`: delete `ACTION_TOGGLE_ELIMINATED`.
- `SessionCommands.kt`: delete `toggleEliminatedIntent`.
- `SessionService.kt`: delete the `ACTION_TOGGLE_ELIMINATED` branch in `handle`; in `goForeground` call `ongoingStatus(source, currentPurpose)`; delete `syncNotification` (it existed only to re-notify on the eliminated flag) and its launch; delete the `import ...toggleEliminated`.

- [ ] **Step 4: Remove the watch UI that called the removed symbols (same task, so wear still builds)**

This is the key ordering fix: the shared deletions above make `BlindsideApp`/`RadarScreen`/`HomeScreen`/`SessionNotification` reference gone symbols, so the wear edits must land in the **same** task/commit or `:wear-app:assembleDebug` (the golden-rule gate) fails.

- `RadarScreen.kt`: remove the `onToggleEliminated` parameter (from `RadarScreen`, `RadarOverlay`, and `BottomPanel`), the `EliminatedChip` composable and its call in `BottomPanel`, change `KeepScreenOn(keepScreenOn(settings.screenMode, session.eliminated))` to `KeepScreenOn(keepScreenOn(settings.screenMode))`, and drop the `import ...shared.radar.eliminatedActionLabel`.
- `HomeScreen.kt`: remove the `onToggleEliminated` parameter (from `HomeScreen` and `RunningHome`), the `item { NavChip(eliminatedActionLabel(session.eliminated), onToggleEliminated) }` row, and the `eliminatedActionLabel` import.
- `BlindsideApp.kt`: remove the `onToggleEliminated` val, the `import ...session.toggleEliminated`, and its two call sites (now that `HomeScreen`/`RadarScreen` no longer take it).
- `SessionNotification.kt`: remove the `.addAction(R.drawable.ic_radar, "Eliminado", toggleEliminatedIntent(context))` line and the private `toggleEliminatedIntent` helper (it called the now-deleted `WearSessionCommands.toggleEliminatedIntent` → `SessionCommands.toggleEliminatedIntent`).
- `ScreenPolicy.kt`: change `fun keepScreenOn(mode: ScreenMode)` to `mode == ScreenMode.VISTA` (drop the `eliminated` parameter).
- `ScreenPolicyTest.kt`: delete `eliminated lets the screen turn off even in vista`; rewrite `only vista keeps the screen on` to `assertTrue(keepScreenOn(ScreenMode.VISTA)); assertFalse(keepScreenOn(ScreenMode.SIGILO))`.
- Leave `eliminatedActionLabel` in `RadarLabels.kt` (and `RadarLabelsTest.kt`) in place — the phone still calls it until Task 15; the watch just stops importing it.

- [ ] **Step 5: Run the gate**

Run: the mandatory per-task gate (android-shared + wear-app).
Expected: BUILD SUCCESSFUL — `:wear-app:assembleDebug` succeeds and both modules' JVM tests pass (android-shared net test count drops by the deleted eliminated tests; wear-app drops the one `ScreenPolicyTest` case). `radar-core` is untouched and still green (`./gradlew :radar-core:testDebugUnitTest`), proving `SessionMode.ELIMINATED` and replay survive.

- [ ] **Step 6: Confirm the phone is the only thing left broken, then commit**

Run: `cd C:/personal/blindside/watch && ./gradlew :phone-app:compileDebugKotlin --console=plain`
Expected: FAIL — only the phone still references the removed symbols (`SessionUiState.eliminated`, `toggleEliminatedIntent`, `PhoneActions.toggleEliminated`). The wear build and both JVM suites already pass (Step 5), so the golden rule holds for this task; the phone is fixed immediately in Task 15.

Commit the shared surgery and the watch-UI removal together:

```bash
cd C:/personal/blindside && git rm watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/session/EliminatedToggle.kt && git add watch/android-shared watch/wear-app && git commit -m "refactor: quita el modo eliminado en vivo de sesión, ajustes y UI del reloj; conserva SessionMode.ELIMINATED solo para leer .bsrec (§8.4)"
```

---

### Task 15: Fix the phone app and delete `eliminatedActionLabel` (§8.4)

The watch UI was already removed in Task 14 (same commit as the shared symbols, so wear builds). This task fixes the only thing Task 14 left broken — the phone — and then deletes `eliminatedActionLabel`, which is now unused by both watch and phone.

**Files:**
- Modify: `watch/android-shared/src/main/kotlin/io/github/santiquiroz/blindside/shared/radar/RadarLabels.kt` (delete `eliminatedActionLabel`, only now that neither watch nor phone calls it)
- Modify: `watch/phone-app/.../ui/radar/RadarTab.kt`, `ui/PhoneActionsFactory.kt`, `ui/PhoneUiModels.kt` (the `PhoneActions.toggleEliminated` field), and any phone session command that referenced `toggleEliminatedIntent`
- Modify tests: `RadarLabelsTest.kt` (drop the `eliminatedActionLabel` assertions, keep the `centerLabel`/`ELIMINATED_LABEL` ones), phone `ContactRowsTest.kt`/`RadarStatusTest.kt`/`PhoneSessionModelsTest.kt` only where they drive the live toggle (keep the ones that assert `RadarScene.eliminated` rendering for replayed recordings).
- Keep: `RadarScene.eliminated`, `shared/radar/RadarGeometry.showContacts` (`!scene.eliminated`), `centerLabel` `ELIMINATED_LABEL`, phone `RadarStatus`/`ContactRows`/`RecordingAnalysis` reads of `scene.eliminated` — these render **replayed old recordings** in the viewer.

**Interfaces:**
- Consumes: the Task 14 shapes (no `SessionUiState.eliminated`, no `toggleEliminatedIntent`, no watch chip).
- Produces: a phone with no live "ME DIERON"/"REAPARECÍ" control and `eliminatedActionLabel` gone; the project compiles and all three modules' tests pass.

- [ ] **Step 1: Phone UI — drop the live toggle, keep the viewer**

- `RadarTab.kt`: remove `EliminatedButton` and its call `EliminatedButton(session.eliminated, actions.toggleEliminated)` and the `eliminatedActionLabel` import. The live phone radar no longer shows the button.
- `PhoneUiModels.kt`: remove the `toggleEliminated` field from `PhoneActions`.
- `PhoneActionsFactory.kt`: remove the `toggleEliminated = { ... toggleEliminatedIntent ... }` line.
- Any phone `SessionCommands` usage of `toggleEliminatedIntent`: remove it (the symbol is gone).
- Leave `RadarStatus.kt` (`scene.eliminated -> "Radar en modo eliminado"`), `ContactRows.kt` (`!it.eliminated`) and `viewer/RecordingAnalysis.kt` (`!scene.eliminated`) untouched: they read the replayed scene, not the live toggle.
- Update only the phone tests that drove the live toggle; keep the viewer/recording-analysis tests that assert replay rendering.

- [ ] **Step 2: Delete `eliminatedActionLabel` now that nothing calls it**

- `RadarLabels.kt`: delete `fun eliminatedActionLabel(...)` only. Keep `ELIMINATED_LABEL` — `centerLabel` still shows it for replayed `scene.eliminated`.
- `RadarLabelsTest.kt`: delete the `eliminatedActionLabel` assertions; keep the `centerLabel`/`ELIMINATED_LABEL` ones.

- [ ] **Step 3: Run the full gate for all three modules**

```bash
cd C:/personal/blindside/watch && ./gradlew :android-shared:cleanTestDebugUnitTest :wear-app:cleanTestDebugUnitTest :phone-app:cleanTestDebugUnitTest :android-shared:testDebugUnitTest :wear-app:testDebugUnitTest :phone-app:testDebugUnitTest :wear-app:assembleDebug :phone-app:assembleDebug --console=plain
```

Expected: BUILD SUCCESSFUL for all three. `:radar-core:testDebugUnitTest` still green.

- [ ] **Step 4: Verify replay still works end-to-end (JVM)**

Run: `cd C:/personal/blindside/watch && ./gradlew :radar-core:testDebugUnitTest --tests '*ReplayTest' --tests '*BsrecTest' --console=plain`
Expected: PASS — the `MODE_CHANGE`/`ELIMINATED` replay path is unchanged.

- [ ] **Step 5: Tick the README 8.4 item and commit**

```bash
cd C:/personal/blindside && git add watch && git commit -m "refactor: quita el control ME DIERON/REAPARECÍ del celular y borra eliminatedActionLabel; el visor sigue leyendo grabaciones eliminadas (§8.4)"
```

---

### Task 16: Final verification, counts, and merge-gate note

**Files:**
- Modify: `watch/wear-app/README.md`

**Interfaces:**
- Consumes: all prior tasks.
- Produces: a green full suite, recorded counts, and the hand-off note.

- [ ] **Step 1: Full clean run of all modules**

```bash
cd C:/personal/blindside/watch && ./gradlew clean :radar-core:testDebugUnitTest :android-shared:testDebugUnitTest :wear-app:testDebugUnitTest :phone-app:testDebugUnitTest :wear-app:assembleDebug :phone-app:assembleDebug --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Record the final counts**

Run the test-count one-liner. Write "Final at <short sha>: radar-core=<n>, android-shared=<n>, wear-app=<n>, phone-app=<n>" into the README under the baseline.

- [ ] **Step 3: Self-review against §8**

Re-read spec §8.1–§8.4 and confirm each bullet maps to a task: fluidity (2–4), auto posture (5–7), HUD bezel/clock/points/alerts/glance (8–13), eliminated removal (14–15). Confirm no new Gradle dependency was added (`git diff main -- watch/gradle/libs.versions.toml watch/*/build.gradle.kts` shows only the `ACCESS_FINE_LOCATION` permission and settings fields, no `play-services-location`). Confirm `android-shared` holds every new formula and every one has a JVM test.

- [ ] **Step 4: Merge-gate note + commit**

Append to the README: "Merge gate: all four modules green + `:wear-app:assembleDebug` and `:phone-app:assembleDebug` succeed. On-device items above are hand-checked by Santiago and do not block the merge (golden rule, spec §8 intro). The executor never merges."

```bash
cd C:/personal/blindside && git add watch/wear-app/README.md && git commit -m "docs: conteos finales y nota de criterio de fusión de la pasada del reloj (§8)"
```

---

## Self-Review

**Spec coverage.**
- §8.1 fluidez: `SENSOR_DELAY_GAME` + per-frame `withFrameNanos` ring with `graphicsLayer` (Task 4), `advanceHeading` (Task 2), contact interpolation (Task 3), immediate gyro scene-spin (Tasks 2+4), `dumpsys gfxinfo` measurement (README). ✓
- §8.2 postura automática: 3 s template capture (Tasks 5+7), 25°/35°/0.4 s hysteresis (Task 6), `WatchPosture.AUTO` default + the three fixed options (Task 6), drives only the drawing (Task 7, `effectivePostureRotationDeg` → `rotationDeg`, never the system). ✓
- §8.3 HUD: bezel window rotation + tap-pin (Task 8/13), game clock + configurable duration + 5-min/end pulse (Tasks 9/13), tactical points by long-press with GPS, wedges with distance (Tasks 10/13), one-at-a-time 4 s alerts in the rear half with Sigilo vibration rule (Tasks 11/13), centre-tap 3 s glance (Tasks 12/13). Numbers in Mono, no white, rear/bezel only. ✓
- §8.4 quitar eliminado: chip + persisted setting + session toggle removed (Tasks 14/15); `SessionMode.ELIMINATED` kept for reading old `.bsrec` (radar-core untouched, replay test in Task 15). ✓

**Placeholder scan.** No "TBD"/"handle edge cases"/"similar to Task N": every pure task carries full test and implementation code; wear tasks carry concrete file-by-file edits and are gated by `assembleDebug` + the README checklist, because the spec itself routes on-device behavior to a checklist.

**Type consistency.** `HeadingAnimation`/`advanceHeading`, `advanceSceneSpinDeg`/`MAX_SCENE_SPIN_DEG`, `frameFraction`/`interpolatedBlips`, `GravityTemplate`/`captureGravityTemplate`/`angleBetweenDeg`, `PostureDetectorState`/`stepPostureDetector`/`effectivePostureRotationDeg`, `BezelField`/`bezelFieldAt`/`toggledPin`, `gameRemainingMs`/`gameClockText`/`crossedThreshold`/`nextGameDuration`, `GeoPoint`/`bearingDeg`/`distanceM`/`wedgeScreenAngleDeg`/`TacticalKind`/`withTacticalPoint`, `AlertKind`/`AlertQueueState`/`enqueueAlert`/`stepAlertQueue`/`hydrationDue`/`alertVibrates`, `GlanceData`/`glanceRows`/`glanceVisible`, and the §8.4 renames (`ModeChanged(screenMode, nowNanos)`, `screenModeToSessionMode`, `ongoingStatus(source, purpose)`) are used identically everywhere they appear.

**Review Focus.** Degenerate gravity → Task 5 test; clock skew / zero duration → Task 9 test; born/dead contact between frames → Task 3 test; huge resume gap → Task 2 tests; many alerts + Sigilo → Task 11 test. All five pinned in the owning task.
