package io.github.santiquiroz.blindside.wear.ui

import io.github.santiquiroz.blindside.shared.settings.ScreenMode
import io.github.santiquiroz.blindside.wear.ui.radar.PointPx
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScreenPolicyTest {
    @Test
    fun `only vista keeps the screen on`() {
        assertTrue(keepScreenOn(ScreenMode.VISTA, eliminated = false))
        assertFalse(keepScreenOn(ScreenMode.SIGILO, eliminated = false))
    }

    @Test
    fun `eliminated lets the screen turn off even in vista`() {
        assertFalse(keepScreenOn(ScreenMode.VISTA, eliminated = true))
    }

    @Test
    fun `sigilo never shifts`() {
        assertEquals(PointPx(0f, 0f), burnInOffset(ScreenMode.SIGILO, 7 * BURN_IN_STEP_MS))
    }

    @Test
    fun `vista shifts two pixels every few minutes and cycles`() {
        assertEquals(PointPx(0f, 0f), burnInOffset(ScreenMode.VISTA, 0L))
        assertEquals(PointPx(2f, 0f), burnInOffset(ScreenMode.VISTA, BURN_IN_STEP_MS))
        assertEquals(PointPx(2f, 2f), burnInOffset(ScreenMode.VISTA, 2 * BURN_IN_STEP_MS))
        assertEquals(PointPx(0f, 0f), burnInOffset(ScreenMode.VISTA, 8 * BURN_IN_STEP_MS))
    }
}
