package io.github.santiquiroz.blindside.phone.ui.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MotionTest {
    @Test
    fun `reduced motion turns every animation off`() {
        assertEquals(0, motionDurationMs(200, animatorScale = 0f))
    }

    @Test
    fun `animations stay between 150 and 300 ms`() {
        assertEquals(200, motionDurationMs(200, animatorScale = 1f))
        assertEquals(MOTION_MIN_MS, motionDurationMs(80, animatorScale = 1f))
        assertEquals(MOTION_MAX_MS, motionDurationMs(900, animatorScale = 2f))
    }
}
