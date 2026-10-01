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
