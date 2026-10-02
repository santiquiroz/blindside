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
