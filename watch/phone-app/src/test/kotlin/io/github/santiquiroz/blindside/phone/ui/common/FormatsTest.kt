package io.github.santiquiroz.blindside.phone.ui.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FormatsTest {
    @Test
    fun `decimals use a comma and one digit`() {
        assertEquals("2,4", formatDecimal(2.44))
        assertEquals("3,0 m", formatMeters(3.0))
        assertEquals("1,2 s", formatSeconds(1_200))
        assertEquals("0,0 s", formatSeconds(0))
    }

    @Test
    fun `percentages are whole numbers with a spaced sign`() {
        assertEquals("25 %", formatPercent(0.25))
        assertEquals("0 %", formatPercent(0.0))
    }

    @Test
    fun `clock shows hours only when there are some`() {
        assertEquals("01:05", formatClock(65_000))
        assertEquals("1:02:05", formatClock(3_725_000))
        assertEquals("00:00", formatClock(-5))
    }

    @Test
    fun `uptime picks the two largest units`() {
        assertEquals("42 s", formatUptime(42))
        assertEquals("3 min 05 s", formatUptime(185))
        assertEquals("1 h 02 min", formatUptime(3_720))
    }

    @Test
    fun `sizes use binary units`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1,5 KB", formatBytes(1_536))
        assertEquals("5,0 MB", formatBytes(5 * 1_048_576L))
    }

    @Test
    fun `ages read as time ago`() {
        assertEquals("hace 3 s", formatAgo(3_000))
        assertEquals("hace 0 s", formatAgo(-50))
    }
}
