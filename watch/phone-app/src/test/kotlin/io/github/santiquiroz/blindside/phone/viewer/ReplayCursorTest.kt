package io.github.santiquiroz.blindside.phone.viewer

import io.github.santiquiroz.blindside.core.sim.Scenarios
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class ReplayCursorTest {
    private val scenario = Scenarios.crossing()
    private val bytes = recordingBytes(headerFor(scenario), simulatedRecords(scenario))

    private fun cursor() = ReplayCursor { ByteArrayInputStream(bytes) }

    @Test
    fun `seeking back shows exactly what a fresh replay shows at that time`() {
        val replay = cursor()
        replay.seekTo(7_000)
        assertEquals(cursor().seekTo(4_000), replay.seekTo(4_000))
    }

    @Test
    fun `seeking forward in small steps matches one long seek`() {
        val stepped = cursor()
        (0L..6_000L step 500L).forEach { stepped.seekTo(it) }
        assertEquals(cursor().seekTo(6_000), stepped.seekTo(6_000))
    }

    @Test
    fun `only an earlier time needs a restart`() {
        val replay = cursor()
        replay.seekTo(3_000)
        assertTrue(replay.needsRestart(2_999))
        assertFalse(replay.needsRestart(3_000))
        assertFalse(replay.needsRestart(5_000))
    }

    @Test
    fun `the walker crossing behind the player shows up during the replay`() {
        val replay = cursor()
        assertTrue((0L..8_500L step 250L).any { replay.seekTo(it).blips.isNotEmpty() })
    }

    @Test
    fun `seeking past the end keeps the last state`() {
        val replay = cursor()
        replay.seekTo(60_000)
        assertEquals(60_000L, replay.positionMs)
    }

    @Test
    fun `closing the cursor closes the recording`() {
        var closed = false
        val replay = ReplayCursor {
            object : ByteArrayInputStream(bytes) {
                override fun close() {
                    closed = true
                }
            }
        }
        replay.close()
        assertTrue(closed)
    }
}
