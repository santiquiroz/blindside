package io.github.santiquiroz.blindside.phone.viewer

import io.github.santiquiroz.blindside.core.scene.Blip
import io.github.santiquiroz.blindside.shared.radar.MAX_RANGE_M
import io.github.santiquiroz.blindside.shared.radar.PointPx
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

const val HEAT_CELL_M = 0.5
const val HEAT_EXTENT_M = MAX_RANGE_M
val HEAT_COLUMNS: Int = (2 * HEAT_EXTENT_M / HEAT_CELL_M).toInt()
val HEAT_ROWS: Int = HEAT_COLUMNS

data class HeatCell(val col: Int, val row: Int)

data class HeatGrid(val counts: List<Int>) {
    val total: Int get() = counts.sum()
    val max: Int get() = counts.maxOrNull() ?: 0
}

data class CellRectPx(val left: Float, val top: Float, val size: Float)

// Counting tens of thousands of samples into a fresh immutable grid each time would copy the grid per sample.
class HeatCounter {
    private val counts = IntArray(HEAT_COLUMNS * HEAT_ROWS)

    fun add(cell: HeatCell) {
        counts[indexOf(cell)]++
    }

    fun toGrid(): HeatGrid = HeatGrid(counts.toList())
}

fun heatCellOf(blip: Blip): HeatCell? {
    if (blip.outOfView) return null
    val radians = Math.toRadians(blip.bearingDeg)
    return cellAt(blip.rangeM * sin(radians), blip.rangeM * cos(radians))
}

fun cellAt(xM: Double, yM: Double): HeatCell? =
    HeatCell(axisIndex(xM), axisIndex(yM)).takeIf { it.col in 0 until HEAT_COLUMNS && it.row in 0 until HEAT_ROWS }

fun heatGridOf(cells: Iterable<HeatCell>): HeatGrid = HeatCounter().apply { cells.forEach(::add) }.toGrid()

fun countAt(grid: HeatGrid, cell: HeatCell): Int = grid.counts[indexOf(cell)]

fun hotCells(grid: HeatGrid): List<Pair<HeatCell, Int>> =
    grid.counts.mapIndexedNotNull { index, count -> if (count > 0) cellOfIndex(index) to count else null }

fun hottestCell(grid: HeatGrid): HeatCell? = hotCells(grid).maxByOrNull { it.second }?.first

fun cellCenterM(cell: HeatCell): Pair<Double, Double> = edgeM(cell.col) + HEAT_CELL_M / 2 to edgeM(cell.row) + HEAT_CELL_M / 2

// Same mapping as the radar drawing: +x to the right, +y up the screen, MAX_RANGE_M at the fan radius.
fun cellRectPx(cell: HeatCell, origin: PointPx, radiusPx: Float): CellRectPx {
    val pxPerM = radiusPx / HEAT_EXTENT_M
    val left = origin.x + edgeM(cell.col) * pxPerM
    val top = origin.y - edgeM(cell.row + 1) * pxPerM
    return CellRectPx(left.toFloat(), top.toFloat(), (HEAT_CELL_M * pxPerM).toFloat())
}

private fun axisIndex(meters: Double): Int = floor((meters + HEAT_EXTENT_M) / HEAT_CELL_M).toInt()

private fun edgeM(index: Int): Double = index * HEAT_CELL_M - HEAT_EXTENT_M

private fun indexOf(cell: HeatCell): Int = cell.row * HEAT_COLUMNS + cell.col

private fun cellOfIndex(index: Int): HeatCell = HeatCell(index % HEAT_COLUMNS, index / HEAT_COLUMNS)
