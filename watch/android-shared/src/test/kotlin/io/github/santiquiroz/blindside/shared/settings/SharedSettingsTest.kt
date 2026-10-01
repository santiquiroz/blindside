package io.github.santiquiroz.blindside.shared.settings

import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SharedSettingsTest {
    private val base = AppSettings(sharedUpdatedMs = 100L)

    @Test
    fun `a local edit of a shared field is stamped now`() {
        assertEquals(500L, stampSharedEdit(base, base.copy(posture = WatchPosture.TACTICAL_RIGHT), nowMs = 500L).sharedUpdatedMs)
        assertEquals(500L, stampSharedEdit(base, base.withFlipXToggled(RADAR_A), nowMs = 500L).sharedUpdatedMs)
    }

    @Test
    fun `a local edit never moves the stamp backwards`() {
        val adoptedFromAClockAhead = AppSettings(sharedUpdatedMs = 9_000L)
        val edited = adoptedFromAClockAhead.copy(posture = WatchPosture.TACTICAL_LEFT)
        assertEquals(9_001L, stampSharedEdit(adoptedFromAClockAhead, edited, nowMs = 5_000L).sharedUpdatedMs)
    }

    @Test
    fun `a non shared edit keeps the stamp`() {
        val edited = base.copy(screenMode = ScreenMode.VISTA, eliminated = true, beltAddress = "AA:BB")
        assertEquals(100L, stampSharedEdit(base, edited, nowMs = 500L).sharedUpdatedMs)
    }

    @Test
    fun `an adoption keeps the remote stamp`() {
        val remote = SharedSettings(Handedness.LEFT, DEFAULT_RADARS, WatchPosture.NORMAL, updatedMs = 300L)
        assertEquals(300L, stampSharedEdit(base, base.adoptingNewer(remote), nowMs = 500L).sharedUpdatedMs)
    }

    @Test
    fun `only a strictly newer remote is adopted`() {
        val older = SharedSettings(Handedness.LEFT, DEFAULT_RADARS, WatchPosture.NORMAL, updatedMs = 99L)
        assertEquals(base, base.adoptingNewer(older))
        assertEquals(base, base.adoptingNewer(older.copy(updatedMs = 100L)))
        assertEquals(Handedness.LEFT, base.adoptingNewer(older.copy(updatedMs = 101L)).handedness)
    }

    @Test
    fun `adopting copies hand, mounts, signs and posture only`() {
        val local = AppSettings(screenMode = ScreenMode.VISTA, beltAddress = "AA:BB", eliminated = true, sharedUpdatedMs = 1L)
        val radars = listOf(RadarSettings(RADAR_A, -30.0, flipX = true, speedSign = -1), RadarSettings(RADAR_B))
        val remote = SharedSettings(Handedness.SWITCHER, radars, WatchPosture.TACTICAL_LEFT, updatedMs = 2L)
        val expected = local.copy(
            handedness = Handedness.SWITCHER,
            radars = radars,
            posture = WatchPosture.TACTICAL_LEFT,
            sharedUpdatedMs = 2L,
        )
        assertEquals(expected, local.adoptingNewer(remote))
    }

    @Test
    fun `only stamped settings are published`() {
        assertFalse(isStamped(sharedSettingsOf(AppSettings())))
        assertTrue(isStamped(sharedSettingsOf(base)))
    }
}
