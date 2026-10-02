package io.github.santiquiroz.blindside.shared.hud

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BezelWindowTest {
    @Test
    fun `the window rotates through the three fields on the period`() {
        assertEquals(BezelField.HEADING, bezelFieldAt(0, null))
        assertEquals(BezelField.CLOCK, bezelFieldAt(4_000, null))
        assertEquals(BezelField.GAME_TIME, bezelFieldAt(8_500, null))
        assertEquals(BezelField.HEADING, bezelFieldAt(12_000, null))
    }

    @Test
    fun `a pin freezes the shown field regardless of time`() {
        assertEquals(BezelField.CLOCK, bezelFieldAt(0, BezelField.CLOCK))
        assertEquals(BezelField.CLOCK, bezelFieldAt(9_000, BezelField.CLOCK))
    }

    @Test
    fun `a tap pins what is showing and a second tap unpins`() {
        assertEquals(BezelField.GAME_TIME, toggledPin(null, BezelField.GAME_TIME))
        assertNull(toggledPin(BezelField.GAME_TIME, BezelField.GAME_TIME))
    }
}
