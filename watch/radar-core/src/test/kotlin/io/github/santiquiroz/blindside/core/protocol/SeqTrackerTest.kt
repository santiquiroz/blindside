package io.github.santiquiroz.blindside.core.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SeqTrackerTest {
    @Test
    fun `consecutive sequence numbers lose nothing`() {
        assertEquals(0, lostBetween(41, 42))
    }

    @Test
    fun `a gap counts the missing packets`() {
        assertEquals(3, lostBetween(10, 14))
    }

    @Test
    fun `wrap around 65536 is not a gap`() {
        assertEquals(0, lostBetween(65535, 0))
        assertEquals(1, lostBetween(65535, 1))
    }

    @Test
    fun `tracker accumulates losses across packets`() {
        val tracker = SeqTracker().observe(1, 100).observe(2, 200).observe(5, 500)

        assertEquals(2, tracker.lostPackets)
    }

    @Test
    fun `time going backwards resets the reference without counting a gap`() {
        val tracker = SeqTracker().observe(500, 90_000).observe(0, 50)

        assertEquals(0, tracker.lostPackets)
        assertEquals(1, tracker.resets)
        assertTrue(SeqTracker().observe(1, 1000).isBackwards(999))
    }
}
