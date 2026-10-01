package io.github.santiquiroz.blindside.shared.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContrastTest {
    @Test
    fun `white on black is the maximum ratio`() {
        assertEquals(21.0, contrastRatio(0xFFFFFFFF, Tokens.BG), 0.01)
    }

    @Test
    fun `main text passes seven to one on the background`() {
        assertTrue(contrastRatio(Tokens.TEXT, Tokens.BG) >= 7.0)
    }

    @Test
    fun `secondary text passes four and a half to one on every surface`() {
        listOf(Tokens.BG, Tokens.SURFACE, Tokens.SURFACE_2).forEach {
            assertTrue(contrastRatio(Tokens.TEXT_2, it) >= 4.5, "on ${it.toString(16)}")
        }
    }

    @Test
    fun `accent, warning and alert red stand out on black`() {
        listOf(Tokens.ACCENT, Tokens.WARN, Tokens.ALERT_RED).forEach {
            assertTrue(contrastRatio(it, Tokens.BG) >= 4.5, "colour ${it.toString(16)}")
        }
    }

    @Test
    fun `black labels on the accent button are readable`() {
        assertTrue(contrastRatio(Tokens.BG, Tokens.ACCENT) >= 4.5)
    }

    @Test
    fun `tokens match the spec table`() {
        val spec = listOf(
            0xFF000000, 0xFF0E1111, 0xFF161B1A, 0xFF25302C, 0xFF3BE37A, 0xFF1E7A43,
            0xFFFF5A4E, 0xFF8C2A24, 0xFFF2B84B, 0xFFE8ECEA, 0xFF9AA5A0,
        )
        val tokens = listOf(
            Tokens.BG, Tokens.SURFACE, Tokens.SURFACE_2, Tokens.RING, Tokens.ACCENT, Tokens.ACCENT_DIM,
            Tokens.ALERT_RED, Tokens.ALERT_RED_DIM, Tokens.WARN, Tokens.TEXT, Tokens.TEXT_2,
        )
        assertEquals(spec, tokens)
    }
}
