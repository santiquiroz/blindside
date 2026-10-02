package io.github.santiquiroz.blindside.shared.hud

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GlancePanelTest {
    @Test
    fun `the panel shows for the hold window after a centre tap and then hides`() {
        assertFalse(glanceVisible(null, 10_000L))
        assertTrue(glanceVisible(10_000L, 10_000L))
        assertTrue(glanceVisible(10_000L, 12_999L))
        assertFalse(glanceVisible(10_000L, 13_000L))
    }

    @Test
    fun `rows carry the three cards and show a dash for what is unknown`() {
        val rows = glanceRows(
            GlanceData("14:05", "1:59:30", heartRate = 132, steps = 4210, distanceM = 2400.0,
                watchBattery = 61, phoneBattery = null, beltBattery = 88),
        )
        assertEquals("Pulso", rows[1].label)
        assertEquals("132", rows[1].value)
        assertTrue(rows.any { it.label == "Baterías" && it.value == "61% / --% / 88%" })
    }
}
