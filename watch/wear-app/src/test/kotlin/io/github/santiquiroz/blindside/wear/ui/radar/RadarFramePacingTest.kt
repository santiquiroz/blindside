package io.github.santiquiroz.blindside.wear.ui.radar

import io.github.santiquiroz.blindside.core.scene.Blip
import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RadarFramePacingTest {
    private val nanosPerMs = 1_000_000L

    @Test
    fun `an identical frame is not written`() {
        val frame = RadarFrame(azimuthDeg = 120f, spinDeg = 0f)
        assertFalse(frameNeedsWrite(frame, frame.copy(), glideActive = false))
    }

    @Test
    fun `a heading step below fifteen hundredths of a degree is not written`() {
        assertFalse(frameNeedsWrite(RadarFrame(azimuthDeg = 10f), RadarFrame(azimuthDeg = 10.1f), glideActive = false))
    }

    @Test
    fun `a heading step of two tenths of a degree is written`() {
        assertTrue(frameNeedsWrite(RadarFrame(azimuthDeg = 10f), RadarFrame(azimuthDeg = 10.2f), glideActive = false))
    }

    @Test
    fun `a small step across north is measured the short way round`() {
        assertFalse(frameNeedsWrite(RadarFrame(azimuthDeg = 359.95f), RadarFrame(azimuthDeg = 0.05f), glideActive = false))
        assertTrue(frameNeedsWrite(RadarFrame(azimuthDeg = 359.9f), RadarFrame(azimuthDeg = 0.2f), glideActive = false))
    }

    @Test
    fun `a visible gyro spin is written`() {
        assertTrue(frameNeedsWrite(RadarFrame(azimuthDeg = 10f, spinDeg = 0.5f), RadarFrame(azimuthDeg = 10f, spinDeg = 0.5f), glideActive = false))
    }

    @Test
    fun `the spin returning to zero is written so the scene settles exactly`() {
        assertTrue(frameNeedsWrite(RadarFrame(azimuthDeg = 10f, spinDeg = 0.02f), RadarFrame(azimuthDeg = 10f, spinDeg = 0f), glideActive = false))
    }

    @Test
    fun `an active glide writes even an identical frame`() {
        val frame = RadarFrame(azimuthDeg = 10f)
        assertTrue(frameNeedsWrite(frame, frame.copy(), glideActive = true))
    }

    @Test
    fun `a spin below the visible threshold is drawn as zero`() {
        assertEquals(0f, frameSpinDeg(0.009))
        assertEquals(0f, frameSpinDeg(-0.009))
        assertEquals(0.5f, frameSpinDeg(0.5))
    }

    @Test
    fun `the glide needs every frame of its period and the first one past it`() {
        assertTrue(glideNeedsFrame(sinceSceneMs = 0L, periodMs = 33L, landed = false))
        assertTrue(glideNeedsFrame(sinceSceneMs = 32L, periodMs = 33L, landed = false))
        assertTrue(glideNeedsFrame(sinceSceneMs = 40L, periodMs = 33L, landed = false))
        assertFalse(glideNeedsFrame(sinceSceneMs = 40L, periodMs = 33L, landed = true))
    }

    @Test
    fun `with contacts the driver glides through the period and lands on the first frame past it`() {
        val driver = RadarFrameDriver()
        val sceneStartMs = 1_000L
        val ticks = listOf(1_000L, 1_016L, 1_032L, 1_049L, 1_066L, 1_082L).map { nowMs ->
            driver.advance(nowMs * nanosPerMs, targetDeg = 0.0, yawRate = 0f, SceneGlide(sceneStartMs, hasContacts = true, periodMs = 33L), nowMs)
            driver.gliding
        }
        assertEquals(listOf(true, true, true, true, false, false), ticks)
    }

    @Test
    fun `a gliding frame carries a fresh tick so every frame of the glide is a new value`() {
        val driver = RadarFrameDriver()
        val first = driver.advance(1_000L * nanosPerMs, 0.0, 0f, SceneGlide(1_000L, hasContacts = true, periodMs = 33L), 1_000L)
        val second = driver.advance(1_016L * nanosPerMs, 0.0, 0f, SceneGlide(1_000L, hasContacts = true, periodMs = 33L), 1_016L)
        assertEquals(1_000L * nanosPerMs, first.glideTick)
        assertEquals(1_016L * nanosPerMs, second.glideTick)
    }

    @Test
    fun `a still wrist with a landed scene stops writing frames`() {
        val driver = RadarFrameDriver()
        var current = RadarFrame()
        val writes = (0 until 10).count { i ->
            val nowMs = 10_000L + i * 16L
            val candidate = driver.advance(nowMs * nanosPerMs, targetDeg = 90.0, yawRate = 0f, SceneGlide(0L, hasContacts = true, periodMs = 33L), nowMs)
            frameNeedsWrite(current, candidate, driver.gliding).also { if (it) current = candidate }
        }
        assertEquals(1, writes)
    }

    @Test
    fun `with no contact on either scene the driver never glides`() {
        val driver = RadarFrameDriver()
        val ticks = listOf(1_000L, 1_016L, 1_032L, 1_049L, 1_066L).map { nowMs ->
            driver.advance(nowMs * nanosPerMs, targetDeg = 0.0, yawRate = 0f, SceneGlide(1_000L, hasContacts = false, periodMs = 33L), nowMs)
            driver.gliding
        }
        assertEquals(listOf(false, false, false, false, false), ticks)
    }

    @Test
    fun `scenes that differ only in body yaw draw the same`() {
        val moved = scene(listOf(blip(1, 30.0)))
        assertTrue(sameDrawing(moved, moved.copy(bodyYawDeg = 87.5, yawFromBelt = true)))
    }

    @Test
    fun `scenes whose contacts differ do not draw the same`() {
        assertFalse(sameDrawing(scene(listOf(blip(1, 30.0))), scene(listOf(blip(1, 31.0)))))
        assertFalse(sameDrawing(scene(emptyList()), scene(listOf(blip(1, 30.0)))))
        assertFalse(sameDrawing(null, scene(emptyList())))
    }

    @Test
    fun `a body-yaw-only scene keeps the pair and its glide`() {
        val first = scene(listOf(blip(1, 30.0)))
        val pair = ScenePair().advance(first, nowMs = 1_000L)
        val kept = pair.receive(first.copy(bodyYawDeg = 12.0), nowMs = 1_020L)
        assertEquals(pair, kept)
        val moved = pair.receive(scene(listOf(blip(1, 35.0))), nowMs = 1_100L)
        assertEquals(1_100L, moved.frameStartMs)
        assertEquals(first, moved.prev)
    }

    @Test
    fun `a pair has contacts when either scene has a blip`() {
        assertFalse(ScenePair().hasContacts())
        assertFalse(ScenePair(prev = scene(emptyList()), next = scene(emptyList())).hasContacts())
        assertTrue(ScenePair(prev = scene(listOf(blip(1, 30.0))), next = scene(emptyList())).hasContacts())
        assertTrue(ScenePair(prev = scene(emptyList()), next = scene(listOf(blip(1, 30.0)))).hasContacts())
    }

    private fun blip(id: Int, bearingDeg: Double) =
        Blip(displayId = id, bearingDeg = bearingDeg, rangeM = 3.0, confidence = Confidence.BOTH, ageMs = 0L, outOfView = false)

    private fun scene(blips: List<Blip>) = RadarScene(
        blips = blips,
        coverage = emptyList(),
        linkUp = true,
        radars = listOf(SensorStatus(0, true)),
        imus = listOf(SensorStatus(0, true)),
        motion = MotionState.STILL,
        warnings = emptySet(),
        eliminated = false,
    )
}
