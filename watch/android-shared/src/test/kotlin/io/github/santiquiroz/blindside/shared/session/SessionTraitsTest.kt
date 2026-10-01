package io.github.santiquiroz.blindside.shared.session

import io.github.santiquiroz.blindside.shared.ble.BeltLinkProfile
import io.github.santiquiroz.blindside.shared.ble.BeltRole
import io.github.santiquiroz.blindside.shared.haptics.HapticSink
import io.github.santiquiroz.blindside.shared.haptics.LEFT_PATTERN
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionTraitsTest {
    private val watchGame = SessionTraits(BeltLinkProfile(BeltRole.WATCH))

    @Test
    fun `the default traits are the watch game exactly as it ran before`() {
        assertTrue(watchGame.link.activatesSession)
        assertTrue(watchGame.records)
        assertTrue(watchGame.readsDeviceSensors)
        assertTrue(watchGame.vibrates)
        assertNull(watchGame.framePeriodMs)
        assertEquals("BELT", recordingTagFor(watchGame, SessionSource.BELT))
        assertEquals("DEMO", recordingTagFor(watchGame, SessionSource.DEMO))
    }

    @Test
    fun `a session that must not vibrate gets a silent sink`() {
        val player = HapticSink { }
        assertSame(player, hapticsFor(watchGame, player))
        assertSame(SILENT_HAPTICS, hapticsFor(watchGame.copy(vibrates = false), player))
        SILENT_HAPTICS.play(LEFT_PATTERN)
    }

    @Test
    fun `a host tag renames the recording file without touching the source`() {
        assertEquals("PHONE", recordingTagFor(watchGame.copy(recordingTag = "PHONE"), SessionSource.BELT))
    }
}
