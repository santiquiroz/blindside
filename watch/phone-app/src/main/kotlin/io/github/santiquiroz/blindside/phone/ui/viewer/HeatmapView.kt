package io.github.santiquiroz.blindside.phone.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.santiquiroz.blindside.phone.ui.common.NumberText
import io.github.santiquiroz.blindside.phone.ui.theme.Text2Color
import io.github.santiquiroz.blindside.phone.viewer.ANALYSIS_SAMPLE_MS
import io.github.santiquiroz.blindside.phone.viewer.HEAT_RAMP
import io.github.santiquiroz.blindside.phone.viewer.HeatGrid
import io.github.santiquiroz.blindside.phone.viewer.RecordingAnalysis
import io.github.santiquiroz.blindside.phone.viewer.cellRectPx
import io.github.santiquiroz.blindside.phone.viewer.heatDescription
import io.github.santiquiroz.blindside.phone.viewer.heatRingRadiiPx
import io.github.santiquiroz.blindside.phone.viewer.heatLegendLabels
import io.github.santiquiroz.blindside.phone.viewer.heatLevel
import io.github.santiquiroz.blindside.phone.viewer.hotCells
import io.github.santiquiroz.blindside.shared.radar.PointPx
import io.github.santiquiroz.blindside.shared.radar.TACTICAL_RADAR_COLORS
import io.github.santiquiroz.blindside.shared.radar.drawRadar
import io.github.santiquiroz.blindside.shared.radar.sectorArc
import io.github.santiquiroz.blindside.shared.radar.toDrawModel

private const val NO_HEAT_TEXT = "Sin contactos en esta grabación."
private const val HEAT_EXPLANATION = "Tiempo con un contacto en cada celda de 0,5 m, en el marco del cuerpo."
private val SWATCH = 20.dp

@Composable
fun HeatmapView(analysis: RecordingAnalysis) {
    if (analysis.heat.total == 0) {
        Text(NO_HEAT_TEXT, color = Text2Color)
        return
    }
    val sectors = remember(analysis) { analysis.coverage.map(::sectorArc) }
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).semantics { contentDescription = heatDescription(analysis.heat) }) {
        val fan = toDrawModel(null, size.width, size.height, PointPx(0f, 0f), showContacts = false)
        val frame = fan.copy(sectors = sectors, dimmed = false, ringRadiiPx = heatRingRadiiPx(fan.radiusPx))
        drawRadar(frame, TACTICAL_RADAR_COLORS)
        drawHeatCells(analysis.heat, frame.origin, frame.radiusPx)
    }
    HeatLegend(analysis.heat.max)
    Text(HEAT_EXPLANATION, style = MaterialTheme.typography.bodySmall, color = Text2Color)
}

private fun DrawScope.drawHeatCells(grid: HeatGrid, origin: PointPx, radiusPx: Float) {
    val max = grid.max
    hotCells(grid).forEach { (cell, count) ->
        val level = heatLevel(count, max) ?: return@forEach
        val rect = cellRectPx(cell, origin, radiusPx)
        drawRect(Color(HEAT_RAMP[level]), topLeft = Offset(rect.left, rect.top), size = Size(rect.size, rect.size))
    }
}

@Composable
private fun HeatLegend(max: Int) {
    val labels = heatLegendLabels(max, ANALYSIS_SAMPLE_MS)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        HEAT_RAMP.forEachIndexed { level, argb ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(SWATCH).background(Color(argb)))
                NumberText(labels[level], style = MaterialTheme.typography.labelSmall, color = Text2Color)
            }
        }
    }
}
