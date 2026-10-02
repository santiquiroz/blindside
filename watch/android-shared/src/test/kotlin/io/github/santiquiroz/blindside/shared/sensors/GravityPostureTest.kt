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
