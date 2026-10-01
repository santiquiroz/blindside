package io.github.santiquiroz.blindside.phone.viewer

import io.github.santiquiroz.blindside.core.scene.Blip
import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.shared.radar.PointPx
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HeatGridTest {
    private fun blip(bearingDeg: Double, rangeM: Double, outOfView: Boolean = false) =
        Blip(1, bearingDeg, rangeM, Confidence.BOTH, 0, outOfView)

    @Test
    fun `the grid covers the fan in half-metre cells`() {
        assertEquals(24, HEAT_COLUMNS)
        assertEquals(24, HEAT_ROWS)
    }

    @Test
    fun `a contact straight ahead lands in the centre column`() {
        assertEquals(HeatCell(12, 18), heatCellOf(blip(0.0, 3.2)))
    }

    @Test
    fun `positive bearings go right and negative bearings go left`() {
        assertEquals(HeatCell(14, 12), heatCellOf(blip(90.0, 1.0)))
        assertEquals(HeatCell(10, 12), heatCellOf(blip(-90.0, 1.0)))
    }

    @Test
    fun `contacts beyond the fan or out of view count nowhere`() {
        assertNull(heatCellOf(blip(0.0, 7.0)))
        assertNull(heatCellOf(blip(0.0, 2.0, outOfView = true)))
        assertNull(cellAt(-6.01, 0.0))
    }

    @Test
    fun `counts add up per cell`() {
        val a = HeatCell(12, 18)
        val b = HeatCell(3, 4)
        val grid = heatGridOf(listOf(a, a, b))
        assertEquals(2, countAt(grid, a))
        assertEquals(3, grid.total)
        assertEquals(2, grid.max)
        assertEquals(a, hottestCell(grid))
        assertEquals(listOf(b to 1, a to 2), hotCells(grid))
    }

    @Test
    fun `an empty grid has no hottest cell`() {
        val empty = heatGridOf(emptyList())
        assertEquals(0, empty.total)
        assertEquals(0, empty.max)
        assertNull(hottestCell(empty))
    }

    @Test
    fun `cell centres and pixel squares follow the radar drawing`() {
        assertEquals(0.25 to 3.25, cellCenterM(HeatCell(12, 18)))
        assertEquals(CellRectPx(left = 100f, top = 95f, size = 5f), cellRectPx(HeatCell(12, 12), PointPx(100f, 100f), radiusPx = 60f))
    }
}
