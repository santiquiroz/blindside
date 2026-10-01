package io.github.santiquiroz.blindside.shared.bridge

import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import io.github.santiquiroz.blindside.shared.settings.ScreenMode
import io.github.santiquiroz.blindside.shared.settings.SharedSettings
import io.github.santiquiroz.blindside.shared.settings.YAW_STEP_DEG
import io.github.santiquiroz.blindside.shared.settings.adoptingNewer
import io.github.santiquiroz.blindside.shared.settings.radar
import io.github.santiquiroz.blindside.shared.settings.sharedSettingsOf
import io.github.santiquiroz.blindside.shared.settings.stampSharedEdit
import io.github.santiquiroz.blindside.shared.settings.withFlipXToggled
import io.github.santiquiroz.blindside.shared.settings.withHandedness
import io.github.santiquiroz.blindside.shared.settings.withSpeedSignFlipped
import io.github.santiquiroz.blindside.shared.settings.withYawNudged
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SharedSettingsCrossDeviceTest {
    private val watch = AppSettings(screenMode = ScreenMode.VISTA, beltAddress = "AA:BB", sharedUpdatedMs = 1_000L)
    private val phone = AppSettings(sharedUpdatedMs = 1_000L)

    // withHandedness clears yaw overrides, so the hand changes first and the yaw is nudged after it.
    private fun edited(before: AppSettings, nowMs: Long): AppSettings = stampSharedEdit(
        before,
        before.withHandedness(Handedness.LEFT).withYawNudged(RADAR_A, -YAW_STEP_DEG).withFlipXToggled(RADAR_B).withSpeedSignFlipped(RADAR_A),
        nowMs,
    )

    private fun overTheBridge(settings: AppSettings): SharedSettings = decodeSharedSettings(encodeSharedSettings(sharedSettingsOf(settings)))!!

    @Test
    fun `a phone edit reaches the watch with its hand, yaw, flip and sign`() {
        val phoneEdit = edited(phone, nowMs = 2_000L)
        val atWatch = watch.adoptingNewer(overTheBridge(phoneEdit))
        assertEquals(phoneEdit.radars, atWatch.radars)
        assertEquals(Handedness.LEFT, atWatch.handedness)
        assertEquals(ScreenMode.VISTA, atWatch.screenMode)
        assertEquals("AA:BB", atWatch.beltAddress)
    }

    @Test
    fun `a custom yaw survives the trip in both directions`() {
        val watchEdit = edited(watch, nowMs = 2_000L)
        assertNotNull(watchEdit.radar(RADAR_A).yawDegOverride)
        assertEquals(watchEdit.radar(RADAR_A).yawDegOverride, phone.adoptingNewer(overTheBridge(watchEdit)).radar(RADAR_A).yawDegOverride)
    }

    @Test
    fun `the echo of a phone edit never undoes it`() {
        val phoneEdit = edited(phone, nowMs = 2_000L)
        val atWatch = watch.adoptingNewer(overTheBridge(phoneEdit))
        assertEquals(phoneEdit, phoneEdit.adoptingNewer(overTheBridge(atWatch)))
    }

    @Test
    fun `an older watch write arriving after a phone edit is ignored`() {
        val phoneEdit = edited(phone, nowMs = 2_000L)
        val olderWatch = stampSharedEdit(watch, watch.withHandedness(Handedness.SWITCHER), nowMs = 1_500L)
        assertEquals(phoneEdit, phoneEdit.adoptingNewer(overTheBridge(olderWatch)))
    }
}
