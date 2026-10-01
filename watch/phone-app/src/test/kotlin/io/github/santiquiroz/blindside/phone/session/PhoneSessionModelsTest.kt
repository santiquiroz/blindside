package io.github.santiquiroz.blindside.phone.session

import io.github.santiquiroz.blindside.shared.ble.BeltLinkProfile
import io.github.santiquiroz.blindside.shared.ble.BeltRole
import io.github.santiquiroz.blindside.shared.session.SessionPurpose
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhoneSessionModelsTest {
    @Test
    fun `a radar game announces the phone, activates its session and records as the phone`() {
        val traits = phoneTraits(SessionPurpose.GAME, vibrate = false)
        assertEquals(BeltLinkProfile(BeltRole.PHONE, activatesSession = true), traits.link)
        assertTrue(traits.records)
        assertEquals(PHONE_RECORDING_TAG, traits.recordingTag)
    }

    @Test
    fun `a diagnostic link announces the phone without a session, records nothing and never vibrates`() {
        val traits = phoneTraits(SessionPurpose.DIAGNOSTIC, vibrate = true)
        assertEquals(BeltLinkProfile(BeltRole.PHONE, activatesSession = false), traits.link)
        assertFalse(traits.records)
        assertFalse(traits.vibrates)
    }

    @Test
    fun `the phone never feeds its own sensors to the pipeline`() {
        SessionPurpose.entries.forEach { assertFalse(phoneTraits(it, vibrate = true).readsDeviceSensors) }
    }

    @Test
    fun `the phone vibrates only when its setting is on`() {
        assertFalse(phoneTraits(SessionPurpose.GAME, vibrate = false).vibrates)
        assertTrue(phoneTraits(SessionPurpose.GAME, vibrate = true).vibrates)
    }

    @Test
    fun `the phone draws about 30 scenes per second while a radar view is on screen`() {
        assertEquals(PHONE_FRAME_MS, phoneTraits(SessionPurpose.GAME, vibrate = false).framePeriodMs)
    }

    @Test
    fun `the phone service runs only as a connected device`() {
        assertEquals(0x10, PHONE_FOREGROUND_TYPES)
    }
}
