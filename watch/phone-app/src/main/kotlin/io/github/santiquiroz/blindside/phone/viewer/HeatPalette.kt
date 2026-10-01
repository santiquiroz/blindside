package io.github.santiquiroz.blindside.phone.viewer

import io.github.santiquiroz.blindside.phone.ui.common.formatSeconds

// Upper half of viridis: colour-blind safe, ≥ 3:1 on black and brighter at every step.
val HEAT_RAMP: List<Long> = listOf(0xFF31688EL, 0xFF21918CL, 0xFF35B779L, 0xFF90D743L, 0xFFFDE725L)

fun heatLevel(count: Int, max: Int): Int? {
    if (count <= 0 || max <= 0) return null
    return (ceilDiv(count.toLong() * HEAT_RAMP.size, max.toLong()) - 1).toInt().coerceIn(0, HEAT_RAMP.lastIndex)
}

fun heatLegendLabels(max: Int, sampleMs: Long): List<String> =
    HEAT_RAMP.indices.map { level -> "≤ ${formatSeconds(levelUpperCount(level, max) * sampleMs)}" }

fun heatDescription(grid: HeatGrid): String = "Mapa de calor con ${hotCells(grid).size} celdas con contactos"

private fun levelUpperCount(level: Int, max: Int): Long = ceilDiv((level + 1).toLong() * max, HEAT_RAMP.size.toLong())

private fun ceilDiv(numerator: Long, denominator: Long): Long = (numerator + denominator - 1) / denominator
