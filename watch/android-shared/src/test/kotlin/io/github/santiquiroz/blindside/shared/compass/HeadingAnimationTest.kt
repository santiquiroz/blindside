package io.github.santiquiroz.blindside.shared.compass

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HeadingAnimationTest {
    private val ms = 1_000_000L

    @Test
    fun `the first frame snaps to the target and stores its time`() {
        val a = advanceHeading(null, 123.4, 5 * ms)
        assertEquals(123.4, a.currentDeg, 1e-9)
        assertEquals(5 * ms, a.lastFrameNanos)
    }

    @Test
    fun `a frame step eases toward the target through north and records the frame time`() {
        val start = HeadingAnimation(350.0, 0L)
        val stepped = advanceHeading(start, 10.0, 150 * ms)
        assertEquals(2.642, stepped.currentDeg, 1e-3)
        assertEquals(150 * ms, stepped.lastFrameNanos)
    }

    @Test
    fun `a huge gap after resume lands on the target without overshoot`() {
        // dt = 5000 ms ⇒ alpha = 1 - exp(-5000/150) ≈ 1, so the ring eases the whole short way (10°→200° is -170°)
        // and settles on the target 200°, never past it.
        val resumed = advanceHeading(HeadingAnimation(10.0, 0L), 200.0, 5_000 * ms)
        assertEquals(200.0, resumed.currentDeg, 1e-6)
    }
}
