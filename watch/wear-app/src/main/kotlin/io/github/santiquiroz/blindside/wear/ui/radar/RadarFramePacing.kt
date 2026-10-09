package io.github.santiquiroz.blindside.wear.ui.radar

import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.shared.compass.HeadingAnimation
import io.github.santiquiroz.blindside.shared.compass.advanceHeading
import io.github.santiquiroz.blindside.shared.compass.shortestTurnDeg
import io.github.santiquiroz.blindside.shared.radar.advanceSceneSpinDeg
import kotlin.math.abs

private const val NANOS_PER_MS = 1_000_000L
private const val HEADING_STEP_DEG = 0.15
private const val SPIN_VISIBLE_DEG = 0.01f

// Per-frame drawing offsets: the eased heading the ring turns by and the transient gyro spin the scene turns by.
// glideTick differs on every frame of a glide so each one is a new value and the radar redraws it.
data class RadarFrame(val azimuthDeg: Float = 0f, val spinDeg: Float = 0f, val glideTick: Long = 0L)

// A glide forces every frame; otherwise only a heading step or a spin the eye could see, or the spin's return to zero, is written.
fun frameNeedsWrite(current: RadarFrame, candidate: RadarFrame, glideActive: Boolean): Boolean =
    glideActive || headingStepped(current, candidate) || abs(candidate.spinDeg) >= SPIN_VISIBLE_DEG || spinSettled(current, candidate)

// The glide spans one publication period, plus the first frame past it so the contacts land exactly on the new scene.
fun glideNeedsFrame(sinceSceneMs: Long, periodMs: Long, landed: Boolean): Boolean = sinceSceneMs < periodMs || !landed

// Below the visible threshold the spin is drawn as zero, so the washout ends with the scene exactly where the belt puts it.
fun frameSpinDeg(spinDeg: Double): Float = if (abs(spinDeg) < SPIN_VISIBLE_DEG) 0f else spinDeg.toFloat()

private fun headingStepped(current: RadarFrame, candidate: RadarFrame): Boolean =
    abs(shortestTurnDeg(current.azimuthDeg.toDouble(), candidate.azimuthDeg.toDouble())) >= HEADING_STEP_DEG

private fun spinSettled(current: RadarFrame, candidate: RadarFrame): Boolean = current.spinDeg != 0f && candidate.spinDeg == 0f

// The last two belt scenes plus when the newest one arrived, so contacts glide from the old to the new between frames.
data class ScenePair(val prev: RadarScene? = null, val next: RadarScene? = null, val frameStartMs: Long = 0L) {
    fun advance(scene: RadarScene, nowMs: Long): ScenePair = ScenePair(prev = next, next = scene, frameStartMs = nowMs)

    // A scene that draws the same as the current target keeps the pair, and with it the glide already under way.
    fun receive(scene: RadarScene, nowMs: Long): ScenePair = if (sameDrawing(scene, next)) this else advance(scene, nowMs)

    // With no contact on either side every frame of the glide draws the same picture.
    fun hasContacts(): Boolean = !prev?.blips.isNullOrEmpty() || !next?.blips.isNullOrEmpty()
}

// Body yaw rides every belt frame with IMU noise but feeds only heading anchoring, ally hints and TAK, never the drawing.
fun sameDrawing(a: RadarScene?, b: RadarScene?): Boolean = drawnPart(a) == drawnPart(b)

private fun drawnPart(scene: RadarScene?): RadarScene? = scene?.copy(bodyYawDeg = 0.0, yawFromBelt = false)

// When the current scene pair arrived, whether it has contacts to slide, and how long its glide lasts.
data class SceneGlide(val startMs: Long, val hasContacts: Boolean, val periodMs: Long)

// The heading ease and the spin washout advance on every frame, written or not; only the published frame is paced.
class RadarFrameDriver {
    private var heading: HeadingAnimation? = null
    private var spin = 0.0
    private var lastNanos = 0L
    private var landedSceneMs: Long? = null

    var gliding = false
        private set

    fun advance(frameNanos: Long, targetDeg: Double?, yawRate: Float, glide: SceneGlide, nowMs: Long): RadarFrame {
        val dtMs = frameDeltaMs(lastNanos, frameNanos)
        lastNanos = frameNanos
        val next = advanceHeading(heading, targetDeg ?: heading?.currentDeg ?: 0.0, frameNanos)
        heading = next
        spin = advanceSceneSpinDeg(spin, yawRate.toDouble(), dtMs)
        gliding = advanceGlide(nowMs - glide.startMs, glide.startMs, glide.periodMs) && glide.hasContacts
        return RadarFrame(next.currentDeg.toFloat(), frameSpinDeg(spin), if (gliding) frameNanos else 0L)
    }

    private fun advanceGlide(sinceSceneMs: Long, sceneStartMs: Long, periodMs: Long): Boolean {
        val needed = glideNeedsFrame(sinceSceneMs, periodMs, landed = landedSceneMs == sceneStartMs)
        if (sinceSceneMs >= periodMs) landedSceneMs = sceneStartMs
        return needed
    }
}

private fun frameDeltaMs(lastNanos: Long, nowNanos: Long): Long =
    if (lastNanos == 0L) 0L else (nowNanos - lastNanos) / NANOS_PER_MS
