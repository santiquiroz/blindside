package io.github.santiquiroz.blindside.wear.sensors

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SensorMathTest {
    @Test
    fun `the wake-up variant wins when it exists`() {
        assertEquals("wake", preferWakeUp("wake", "plain"))
        assertEquals("plain", preferWakeUp(null, "plain"))
        assertNull(preferWakeUp<String>(null, null))
    }

    @Test
    fun `the first event always passes the rate gate`() {
        assertTrue(passesGate(null, 5L, GRAVITY_MIN_INTERVAL_NANOS))
    }

    @Test
    fun `events closer than the minimum interval are dropped`() {
        assertFalse(passesGate(0L, 50_000_000L, GRAVITY_MIN_INTERVAL_NANOS))
        assertTrue(passesGate(0L, 100_000_000L, GRAVITY_MIN_INTERVAL_NANOS))
    }

    @Test
    fun `gravity and gyro are gated to at most ten hertz`() {
        assertEquals(100_000_000L, GRAVITY_MIN_INTERVAL_NANOS)
        assertEquals(100_000_000L, GYRO_MIN_INTERVAL_NANOS)
    }

    @Test
    fun `the low pass starts at the first sample`() {
        val sample = Vec3(1f, 2f, 9.8f)
        assertEquals(sample, lowPass(null, sample, 0L, ACCEL_LOW_PASS_TAU_NANOS))
    }

    @Test
    fun `the low pass moves halfway when dt equals tau`() {
        val result = lowPass(Vec3(0f, 0f, 0f), Vec3(2f, 4f, 6f), 300_000_000L, 300_000_000L)
        assertEquals(Vec3(1f, 2f, 3f), result)
    }

    @Test
    fun `an out of order sample leaves the filter unchanged`() {
        val previous = Vec3(0f, 0f, 9.8f)
        assertEquals(previous, lowPass(previous, Vec3(5f, 5f, 5f), -1_000L, ACCEL_LOW_PASS_TAU_NANOS))
    }

    @Test
    fun `gravity source prefers the real sensor and falls back to the accelerometer`() {
        assertEquals(GravitySource.GRAVITY_SENSOR, gravitySourceFor(hasGravity = true, hasAccelerometer = true))
        assertEquals(GravitySource.ACCELEROMETER_LOW_PASS, gravitySourceFor(hasGravity = false, hasAccelerometer = true))
        assertEquals(GravitySource.NONE, gravitySourceFor(hasGravity = false, hasAccelerometer = false))
    }
}
