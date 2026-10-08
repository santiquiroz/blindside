package io.github.santiquiroz.blindside.shared.radar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.santiquiroz.blindside.shared.theme.BlindsideColors

private const val LINE_WIDTH_PX = 2f
private const val BLIP_STROKE_PX = 3f
private const val EDGE_MARKER_WIDTH_PX = 5f
private const val ALLY_RING_GAP_PX = 5f
private val DASHED = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))

fun DrawScope.drawRadar(model: RadarDrawModel, colors: RadarColors, allyIds: Set<Int> = emptySet()) {
    drawFan(model, colors)
    drawRings(model, colors)
    model.blips.forEach { drawBlip(it, model.blipRadiusPx, colors, it.id in allyIds) }
    model.edgeMarkers.forEach { drawEdgeMarker(it, colors) }
}

private fun DrawScope.drawFan(model: RadarDrawModel, colors: RadarColors) {
    val color = if (model.dimmed) colors.dimmed else colors.fan
    model.sectors.forEach { drawSectorArc(model.origin, model.radiusPx, it, color, useCenter = true) }
}

private fun DrawScope.drawRings(model: RadarDrawModel, colors: RadarColors) {
    val color = if (model.dimmed) colors.dimmed else colors.ring
    model.ringRadiiPx.forEach { radius ->
        model.sectors.forEach { drawSectorArc(model.origin, radius, it, color, useCenter = false) }
    }
}

private fun DrawScope.drawSectorArc(origin: PointPx, radius: Float, sector: SectorDraw, color: Color, useCenter: Boolean) {
    drawArc(
        color = color,
        startAngle = sector.startAngleDeg,
        sweepAngle = sector.sweepDeg,
        useCenter = useCenter,
        topLeft = Offset(origin.x - radius, origin.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = LINE_WIDTH_PX),
    )
}

private fun DrawScope.drawBlip(blip: BlipDraw, radius: Float, colors: RadarColors, ally: Boolean) {
    val base = if (ally) BlindsideColors.Ally else toneColor(contactTone(blip.style), colors)
    val color = base.copy(alpha = blip.alpha)
    val center = Offset(blip.center.x, blip.center.y)
    if (ally) drawAllyRing(center, radius, color)
    when (blip.style) {
        BlipStyle.FILLED -> drawCircle(color, radius, center)
        BlipStyle.OUTLINE -> drawCircle(color, radius, center, style = Stroke(width = BLIP_STROKE_PX))
        BlipStyle.DASHED -> drawCircle(color, radius, center, style = Stroke(width = BLIP_STROKE_PX, pathEffect = DASHED))
    }
}

// DrawScope has no text measurer, so the likely-ally "?" is a ring around the dot instead.
private fun DrawScope.drawAllyRing(center: Offset, radius: Float, color: Color) {
    drawCircle(color, radius + ALLY_RING_GAP_PX, center, style = Stroke(width = LINE_WIDTH_PX))
}

private fun toneColor(tone: ContactTone, colors: RadarColors): Color = when (tone) {
    ContactTone.FULL -> colors.contact
    ContactTone.DIM -> colors.contactDim
}

private fun DrawScope.drawEdgeMarker(marker: EdgeMarkerDraw, colors: RadarColors) {
    drawLine(
        color = colors.contact.copy(alpha = marker.alpha),
        start = Offset(marker.inner.x, marker.inner.y),
        end = Offset(marker.outer.x, marker.outer.y),
        strokeWidth = EDGE_MARKER_WIDTH_PX,
        cap = StrokeCap.Round,
    )
}
