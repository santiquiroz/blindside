package io.github.santiquiroz.blindside.shared.settings

import androidx.datastore.preferences.core.preferencesOf
import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
        val edited = base.copy(screenMode = ScreenMode.VISTA, beltAddress = "AA:BB")
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
        val local = AppSettings(screenMode = ScreenMode.VISTA, beltAddress = "AA:BB", sharedUpdatedMs = 1L)
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

    private val legacyWatch = settingsFrom(
        preferencesOf(
            Keys.HANDEDNESS to "LEFT",
            Keys.POSTURE to "TACTICAL_LEFT",
            Keys.yaw(RADAR_A) to -25.0,
            Keys.flipX(RADAR_A) to true,
            Keys.speedSign(RADAR_B) to -1,
        ),
    )

    private fun published(vararg settings: AppSettings): List<SharedSettings> =
        runBlocking { publishableSharedSettings(flowOf(*settings)).toList() }

    @Test
    fun `settings saved before stamps existed are published and a fresh peer adopts them`() {
        val fromWatch = published(legacyWatch).single()
        assertEquals(sharedSettingsOf(legacyWatch), sharedSettingsOf(AppSettings().adoptingNewer(fromWatch)))
    }

    @Test
    fun `a fresh peer's defaults never replace a migrated calibration`() {
        assertTrue(published(AppSettings()).isEmpty())
        assertEquals(legacyWatch, legacyWatch.adoptingNewer(sharedSettingsOf(AppSettings()).copy(updatedMs = MIGRATED_STAMP_MS)))
    }

    @Test
    fun `a real edit after adopting a migrated calibration still wins`() {
        val atPhone = AppSettings().adoptingNewer(sharedSettingsOf(legacyWatch))
        val phoneEdit = stampSharedEdit(atPhone, atPhone.copy(posture = WatchPosture.NORMAL), nowMs = 5_000L)
        val atWatch = legacyWatch.adoptingNewer(published(phoneEdit).single())
        assertEquals(WatchPosture.NORMAL, atWatch.posture)
        assertEquals(legacyWatch.radars, atWatch.radars)
    }

    @Test
    fun `the publisher sends each stamped change once`() {
        assertEquals(listOf(sharedSettingsOf(base)), published(base, base.copy(screenMode = ScreenMode.VISTA)))
    }
}
