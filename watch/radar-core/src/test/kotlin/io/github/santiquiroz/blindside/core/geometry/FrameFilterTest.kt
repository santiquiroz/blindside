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
