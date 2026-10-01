package io.github.santiquiroz.blindside.shared.radar

import io.github.santiquiroz.blindside.shared.settings.ContactColor
import io.github.santiquiroz.blindside.shared.theme.BlindsideColors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RadarColorsTest {
    @Test
    fun `each contact colour draws with its own palette and shares the rings`() {
        assertEquals(TACTICAL_RADAR_COLORS, radarColorsFor(ContactColor.GREEN))
        assertEquals(RED_RADAR_COLORS, radarColorsFor(ContactColor.RED))
        assertEquals(BlindsideColors.Accent, radarColorsFor(ContactColor.GREEN).contact)
        assertEquals(BlindsideColors.AlertRed, radarColorsFor(ContactColor.RED).contact)
        assertEquals(TACTICAL_RADAR_COLORS.ring, RED_RADAR_COLORS.ring)
    }
}
