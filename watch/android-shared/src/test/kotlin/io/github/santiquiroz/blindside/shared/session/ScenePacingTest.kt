package io.github.santiquiroz.blindside.shared.session

import io.github.santiquiroz.blindside.shared.settings.ScreenMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ScenePacingTest {
    @Test
    fun `no scenes are computed while the radar is not on screen`() {
        assertNull(scenePeriodMs(radarVisible = false, mode = ScreenMode.VISTA, ambient = false))
    }

    @Test
    fun `no scenes are computed in ambient, where contacts are hidden`() {
        assertNull(scenePeriodMs(radarVisible = true, mode = ScreenMode.SIGILO, ambient = true))
    }

    @Test
    fun `vista draws at thirty frames per second`() {
        assertEquals(VISTA_FRAME_MS, scenePeriodMs(radarVisible = true, mode = ScreenMode.VISTA, ambient = false))
    }

    @Test
    fun `sigilo draws at the belt data rate`() {
        assertEquals(SIGILO_FRAME_MS, scenePeriodMs(radarVisible = true, mode = ScreenMode.SIGILO, ambient = false))
    }

    @Test
    fun `the mode frame period is the vista or sigilo cadence regardless of visibility`() {
        assertEquals(VISTA_FRAME_MS, modeFramePeriodMs(ScreenMode.VISTA))
        assertEquals(SIGILO_FRAME_MS, modeFramePeriodMs(ScreenMode.SIGILO))
    }

    @Test
    fun `a fixed frame period replaces the screen mode only while the radar is on screen`() {
        assertEquals(33L, pacedPeriodMs(33L, radarVisible = true, mode = ScreenMode.SIGILO, ambient = false))
        assertNull(pacedPeriodMs(33L, radarVisible = false, mode = ScreenMode.VISTA, ambient = false))
        assertNull(pacedPeriodMs(33L, radarVisible = true, mode = ScreenMode.VISTA, ambient = true))
        assertEquals(
            scenePeriodMs(radarVisible = true, mode = ScreenMode.SIGILO, ambient = false),
            pacedPeriodMs(null, radarVisible = true, mode = ScreenMode.SIGILO, ambient = false),
        )
    }
}
