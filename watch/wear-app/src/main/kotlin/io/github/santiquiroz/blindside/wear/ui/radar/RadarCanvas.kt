package io.github.santiquiroz.blindside.wear.ui.radar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.santiquiroz.blindside.wear.ui.CONTACT_RED
import io.github.santiquiroz.blindside.wear.ui.DIMMED_LINE
import io.github.santiquiroz.blindside.wear.ui.FAN_LINE
import io.github.santiquiroz.blindside.wear.ui.RING_LINE

private const val LINE_WIDTH_PX = 2f
private const val BLIP_STROKE_PX = 3f
private const val EDGE_MARKER_WIDTH_PX = 5f
private val DASHED = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))

fun DrawScope.drawRadar(model: RadarDrawModel) {
    drawFan(model)
    drawRings(model)
    model.blips.forEach { drawBlip(it, model.blipRadiusPx) }
    model.edgeMarkers.forEach { drawEdgeMarker(it) }
}

private fun DrawScope.drawFan(model: RadarDrawModel) {
    val color = if (model.dimmed) DIMMED_LINE else FAN_LINE
    model.sectors.forEach { drawSectorArc(model.origin, model.radiusPx, it, color, useCenter = true) }
}

private fun DrawScope.drawRings(model: RadarDrawModel) {
    val color = if (model.dimmed) DIMMED_LINE else RING_LINE
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

private fun DrawScope.drawBlip(blip: BlipDraw, radius: Float) {
    val color = CONTACT_RED.copy(alpha = blip.alpha)
    val center = Offset(blip.center.x, blip.center.y)
    when (blip.style) {
        BlipStyle.FILLED -> drawCircle(color, radius, center)
        BlipStyle.OUTLINE -> drawCircle(color, radius, center, style = Stroke(width = BLIP_STROKE_PX))
        BlipStyle.DASHED -> drawCircle(color, radius, center, style = Stroke(width = BLIP_STROKE_PX, pathEffect = DASHED))
    }
}

private fun DrawScope.drawEdgeMarker(marker: EdgeMarkerDraw) {
    drawLine(
        color = CONTACT_RED.copy(alpha = marker.alpha),
        start = Offset(marker.inner.x, marker.inner.y),
        end = Offset(marker.outer.x, marker.outer.y),
        strokeWidth = EDGE_MARKER_WIDTH_PX,
        cap = StrokeCap.Round,
    )
}
