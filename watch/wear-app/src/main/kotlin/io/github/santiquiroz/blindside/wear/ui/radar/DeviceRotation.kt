package io.github.santiquiroz.blindside.wear.ui.radar

import kotlin.math.cos
import kotlin.math.sin

fun screenCenter(widthPx: Float, heightPx: Float, offset: PointPx): PointPx =
    PointPx(widthPx / 2f + offset.x, heightPx / 2f + offset.y)

fun RadarDrawModel.rotatedAbout(pivot: PointPx, rotationDeg: Float): RadarDrawModel {
    // Normal posture returns the model untouched, so float round-off never moves the default drawing.
    if (rotationDeg == 0f) return this
    return copy(
        origin = rotatePoint(origin, pivot, rotationDeg),
        sectors = sectors.map { it.copy(startAngleDeg = it.startAngleDeg + rotationDeg) },
        blips = blips.map { it.copy(center = rotatePoint(it.center, pivot, rotationDeg)) },
        edgeMarkers = edgeMarkers.map { rotatedMarker(it, pivot, rotationDeg) },
    )
}

fun rotatePoint(point: PointPx, pivot: PointPx, rotationDeg: Float): PointPx {
    val radians = Math.toRadians(rotationDeg.toDouble())
    val dx = (point.x - pivot.x).toDouble()
    val dy = (point.y - pivot.y).toDouble()
    return PointPx(
        pivot.x + (dx * cos(radians) - dy * sin(radians)).toFloat(),
        pivot.y + (dx * sin(radians) + dy * cos(radians)).toFloat(),
    )
}

private fun rotatedMarker(marker: EdgeMarkerDraw, pivot: PointPx, rotationDeg: Float): EdgeMarkerDraw =
    marker.copy(inner = rotatePoint(marker.inner, pivot, rotationDeg), outer = rotatePoint(marker.outer, pivot, rotationDeg))
