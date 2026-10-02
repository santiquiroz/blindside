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
    fun `the degrees-only text is three digits without a cardinal`() {
        assertEquals("318°", headingDegreesText(318.4))
        assertEquals("000°", headingDegreesText(359.7))
        assertEquals("005°", headingDegreesText(5.0))
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
