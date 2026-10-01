package io.github.santiquiroz.blindside.phone.viewer

import io.github.santiquiroz.blindside.shared.theme.Tokens
import io.github.santiquiroz.blindside.shared.theme.contrastRatio
import io.github.santiquiroz.blindside.shared.theme.relativeLuminance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeatPaletteTest {
    @Test
    fun `every heat colour stands out on black`() {
        HEAT_RAMP.forEach { assertTrue(contrastRatio(it, Tokens.BG) >= 3.0, it.toString(16)) }
    }

    @Test
    fun `heat gets brighter at every step so it also reads without colour`() {
        val luminances = HEAT_RAMP.map(::relativeLuminance)
        assertEquals(luminances.sorted(), luminances)
        assertEquals(luminances.size, luminances.toSet().size)
    }

    @Test
    fun `counts map to five levels and zero to none`() {
        assertNull(heatLevel(0, 10))
        assertNull(heatLevel(3, 0))
        assertEquals(0, heatLevel(1, 10))
        assertEquals(2, heatLevel(5, 10))
        assertEquals(4, heatLevel(10, 10))
    }

    @Test
    fun `the legend tells the time each level stands for`() {
        assertEquals(listOf("≤ 0,5 s", "≤ 1,0 s", "≤ 1,5 s", "≤ 2,0 s", "≤ 2,5 s"), heatLegendLabels(max = 10, sampleMs = 250))
    }

    @Test
    fun `screen readers hear how many cells are hot`() {
        assertEquals("Mapa de calor con 1 celdas con contactos", heatDescription(heatGridOf(listOf(HeatCell(1, 1)))))
    }
}
