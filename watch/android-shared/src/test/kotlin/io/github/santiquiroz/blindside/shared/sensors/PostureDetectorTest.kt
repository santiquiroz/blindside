package io.github.santiquiroz.blindside.shared.sensors

import io.github.santiquiroz.blindside.shared.settings.WatchPosture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PostureDetectorTest {
    private fun step(state: PostureDetectorState, angle: Double, now: Long) =
        stepPostureDetector(state, angle, now, POSTURE_ENTER_DEG, POSTURE_EXIT_DEG, POSTURE_DWELL_MS)

    @Test
    fun `tactical engages only after staying inside the cone for the dwell`() {
        var s = PostureDetectorState()
        s = step(s, 10.0, 0L); assertFalse(s.tactical)
        s = step(s, 10.0, 200L); assertFalse(s.tactical)
        s = step(s, 10.0, 400L); assertTrue(s.tactical)
    }

    @Test
    fun `a glance outside the cone before the dwell resets the timer`() {
        var s = PostureDetectorState()
        s = step(s, 10.0, 0L)
        s = step(s, 40.0, 200L); assertFalse(s.tactical)
        s = step(s, 10.0, 300L)
        s = step(s, 10.0, 600L); assertFalse(s.tactical)
        s = step(s, 10.0, 700L); assertTrue(s.tactical)
    }

    @Test
    fun `hysteresis holds tactical until the angle passes the wider exit`() {
        val engaged = PostureDetectorState(tactical = true)
        assertTrue(step(engaged, 30.0, 999L).tactical)
        assertFalse(step(engaged, 36.0, 999L).tactical)
    }

    @Test
    fun `auto uses the template rotation only while tactical, fixed postures ignore detection`() {
        val template = GravityTemplate(1f, 0f, 9f, TACTICAL_LEFT_ROTATION_DEG)
        assertEquals(0f, effectivePostureRotationDeg(WatchPosture.AUTO, tactical = false, template = template))
        assertEquals(90f, effectivePostureRotationDeg(WatchPosture.AUTO, tactical = true, template = template))
        assertEquals(0f, effectivePostureRotationDeg(WatchPosture.AUTO, tactical = true, template = null))
        assertEquals(-90f, effectivePostureRotationDeg(WatchPosture.TACTICAL_RIGHT, tactical = false, template = template))
    }
}
