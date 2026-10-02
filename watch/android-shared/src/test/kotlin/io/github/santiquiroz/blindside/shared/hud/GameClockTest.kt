package io.github.santiquiroz.blindside.shared.hud

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GameClockTest {
    @Test
    fun `remaining counts down and clamps at zero, never negative`() {
        assertEquals(18_000_000L, gameRemainingMs(1_000L, 1_000L, 18_000_000L))
        assertEquals(17_999_000L, gameRemainingMs(1_000L, 2_000L, 18_000_000L))
        assertEquals(0L, gameRemainingMs(1_000L, 99_999_999L, 18_000_000L))
    }

    @Test
    fun `clock skew backward and zero duration both clamp to zero`() {
        assertEquals(18_000_000L, gameRemainingMs(5_000L, 1_000L, 18_000_000L))
        assertEquals(0L, gameRemainingMs(0L, 10_000L, 0L))
    }

    @Test
    fun `the clock text is h mm ss above an hour and mm ss below`() {
        assertEquals("1:00:00", gameClockText(3_600_000L))
        assertEquals("04:09", gameClockText(249_000L))
        assertEquals("00:00", gameClockText(0L))
    }

    @Test
    fun `a threshold fires once as remaining crosses it and not again`() {
        assertTrue(crossedThreshold(FIVE_MIN_MS + 1_000L, FIVE_MIN_MS - 1_000L, FIVE_MIN_MS))
        assertFalse(crossedThreshold(FIVE_MIN_MS - 1_000L, FIVE_MIN_MS - 2_000L, FIVE_MIN_MS))
        assertTrue(crossedThreshold(1_000L, 0L, 0L))
        assertFalse(crossedThreshold(0L, 0L, 0L))
    }

    @Test
    fun `durations cycle through the fixed options`() {
        assertEquals(7_200_000L, nextGameDuration(3_600_000L))
        assertEquals(0L, nextGameDuration(18_000_000L))
        assertEquals(3_600_000L, nextGameDuration(0L))
    }
}
