package io.github.santiquiroz.blindside.wear.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScanLimiterTest {
    private val full = ScanHistory(listOf(0L, 1_000L, 2_000L, 3_000L))

    @Test
    fun `four scans fit in thirty seconds and the fifth is refused`() {
        val history = (0 until 4).fold(ScanHistory()) { current, i ->
            val permit = tryStartScan(current, i * 1_000L)
            assertTrue(permit.allowed)
            permit.history
        }
        assertFalse(tryStartScan(history, 4_000L).allowed)
    }

    @Test
    fun `a slot frees up when the oldest scan leaves the window`() {
        assertFalse(tryStartScan(full, 29_999L).allowed)
        assertTrue(tryStartScan(full, 30_000L).allowed)
    }

    @Test
    fun `a refused scan does not use a slot`() {
        assertEquals(4, tryStartScan(full, 10_000L).history.startsMs.size)
    }
}
