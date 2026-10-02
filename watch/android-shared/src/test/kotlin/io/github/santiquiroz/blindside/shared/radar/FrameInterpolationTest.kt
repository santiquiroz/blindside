package io.github.santiquiroz.blindside.shared.radar

import io.github.santiquiroz.blindside.core.scene.Blip
import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.shared.session.SIGILO_FRAME_MS
import io.github.santiquiroz.blindside.shared.session.VISTA_FRAME_MS
import io.github.santiquiroz.blindside.shared.session.modeFramePeriodMs
import io.github.santiquiroz.blindside.shared.settings.ScreenMode
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
    fun `a full vista frame glides the whole way when the period tracks the mode, not the sigilo constant`() {
        // The bug: dividing a 33 ms Vista frame by the 100 ms Sigilo period only reaches ~0.33 before the scene shifts.
        assertEquals(0.33f, frameFraction(VISTA_FRAME_MS, SIGILO_FRAME_MS), 0.01f)
        assertEquals(1f, frameFraction(VISTA_FRAME_MS, modeFramePeriodMs(ScreenMode.VISTA)), 1e-6f)
        assertEquals(1f, frameFraction(SIGILO_FRAME_MS, modeFramePeriodMs(ScreenMode.SIGILO)), 1e-6f)
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
