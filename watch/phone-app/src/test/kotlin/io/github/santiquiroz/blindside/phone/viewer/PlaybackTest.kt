package io.github.santiquiroz.blindside.phone.viewer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaybackTest {
    private val ten = Playback(positionMs = 0, durationMs = 10_000)

    @Test
    fun `a paused playback does not move`() {
        assertEquals(ten, advanced(ten, wallDeltaMs = 500))
    }

    @Test
    fun `playing advances by wall time times the speed`() {
        assertEquals(400L, advanced(ten.copy(playing = true, speed = 4), wallDeltaMs = 100).positionMs)
    }

    @Test
    fun `playback stops exactly at the end`() {
        val end = advanced(ten.copy(positionMs = 9_950, playing = true, speed = 8), wallDeltaMs = 100)
        assertEquals(10_000L, end.positionMs)
        assertFalse(end.playing)
    }

    @Test
    fun `a backwards wall step never rewinds`() {
        assertEquals(2_000L, advanced(ten.copy(positionMs = 2_000, playing = true), wallDeltaMs = -50).positionMs)
    }

    @Test
    fun `play at the end starts over and pause keeps the position`() {
        val restarted = toggledPlay(ten.copy(positionMs = 10_000))
        assertEquals(0L, restarted.positionMs)
        assertTrue(restarted.playing)
        assertEquals(ten.copy(positionMs = 3_000), toggledPlay(ten.copy(positionMs = 3_000, playing = true)))
    }

    @Test
    fun `only 1, 2, 4 and 8 times are accepted`() {
        assertEquals(listOf(1, 2, 4, 8), PLAYBACK_SPEEDS)
        assertEquals(8, withSpeed(ten, 8).speed)
        assertEquals(1, withSpeed(ten, 3).speed)
    }

    @Test
    fun `seeking is clamped to the recording`() {
        assertEquals(0L, seekedTo(ten, -10).positionMs)
        assertEquals(10_000L, seekedTo(ten, 99_000).positionMs)
        assertEquals(4_200L, seekedTo(ten, 4_200).positionMs)
    }

    @Test
    fun `an empty recording plays and stops at once`() {
        val empty = toggledPlay(Playback())
        assertFalse(advanced(empty, wallDeltaMs = 16).playing)
    }
}
