package io.github.santiquiroz.blindside.wear.ui.radar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.santiquiroz.blindside.shared.compass.CARDINAL_MARKS
import io.github.santiquiroz.blindside.shared.compass.CardinalMark
import io.github.santiquiroz.blindside.shared.compass.CompassColors
import io.github.santiquiroz.blindside.shared.compass.CompassTick
import io.github.santiquiroz.blindside.shared.compass.compassTicks
import io.github.santiquiroz.blindside.shared.compass.markScreenAngleDeg
import io.github.santiquiroz.blindside.shared.compass.pointOnRing
import io.github.santiquiroz.blindside.shared.radar.PointPx
import io.github.santiquiroz.blindside.shared.tak.MateMark
import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import io.github.santiquiroz.blindside.shared.tactical.TacticalKind
import io.github.santiquiroz.blindside.shared.tactical.bearingDeg
import io.github.santiquiroz.blindside.shared.tactical.distanceM
import io.github.santiquiroz.blindside.shared.tactical.tacticalDistanceLabel
import io.github.santiquiroz.blindside.shared.tactical.wedgeScreenAngleDeg
import io.github.santiquiroz.blindside.shared.theme.BlindsideFonts

private const val MINOR_TICK_FRACTION = 0.3f
private const val MAJOR_TICK_FRACTION = 0.5f
private const val TICK_STROKE_PX = 2f
private const val INDEX_STROKE_PX = 5f
private const val WEDGE_SPREAD_DEG = 7f
private const val WEDGE_STROKE_PX = 2.5f
private const val WEDGE_BASE_FRACTION = 0.6f
private const val WEDGE_LABEL_FRACTION = 1.5f
private val WEDGE_DASH = floatArrayOf(6f, 4f)
private val LETTER_SIZE = 10.sp
private val WEDGE_LABEL_SIZE = 8.sp

data class TacticalWedgeColors(val base: Color, val spawn: Color, val objective: Color)

private enum class WedgeStyle { FILLED, HOLLOW, DASHED }

data class RingGeometry(val center: PointPx, val outerRadiusPx: Float, val bandPx: Float)

// The symmetric tick band carries no azimuth: the caller spins this whole layer by -azimuth on the compositor.
fun DrawScope.drawCompassTicks(ring: RingGeometry, colors: CompassColors) {
    compassTicks().forEach { drawTick(it, ring, colors) }
}

private fun DrawScope.drawTick(tick: CompassTick, ring: RingGeometry, colors: CompassColors) {
    val length = ring.bandPx * if (tick.major) MAJOR_TICK_FRACTION else MINOR_TICK_FRACTION
    drawLine(
        color = if (tick.major) colors.major else colors.tick,
        start = offsetOf(pointOnRing(ring.center, ring.outerRadiusPx, tick.angleDeg)),
        end = offsetOf(pointOnRing(ring.center, ring.outerRadiusPx - length, tick.angleDeg)),
        strokeWidth = TICK_STROKE_PX,
        cap = StrokeCap.Round,
    )
}

// Drawn in the non-rotated layer: the letters keep their heading-driven positions but stay upright for this posture.
fun DrawScope.drawCompassLetters(
    azimuthDeg: Double,
    ring: RingGeometry,
    postureRotationDeg: Float,
    colors: CompassColors,
    measurer: TextMeasurer,
) {
    CARDINAL_MARKS.forEach { drawCardinal(it, azimuthDeg, ring, postureRotationDeg, colors, measurer) }
}

private fun DrawScope.drawCardinal(
    mark: CardinalMark,
    azimuthDeg: Double,
    ring: RingGeometry,
    postureRotationDeg: Float,
    colors: CompassColors,
    measurer: TextMeasurer,
) {
    val color = if (mark.label == "N") colors.north else colors.letter
    val layout = measurer.measure(mark.label, TextStyle(color = color, fontSize = LETTER_SIZE, fontFamily = BlindsideFonts.Mono, fontWeight = FontWeight.Bold))
    val anchor = offsetOf(pointOnRing(ring.center, ring.outerRadiusPx - ring.bandPx / 2f, markScreenAngleDeg(mark.angleDeg, azimuthDeg)))
    val topLeft = Offset(anchor.x - layout.size.width / 2f, anchor.y - layout.size.height / 2f)
    // Letters stay upright for the eye that reads the watch in this posture, not for the glass.
    rotate(postureRotationDeg, pivot = anchor) { drawText(layout, topLeft = topLeft) }
}

// The front index marks the watch's 12 o'clock in the current posture, fixed independent of heading.
fun DrawScope.drawFrontIndex(ring: RingGeometry, postureRotationDeg: Float, color: Color) {
    val inner = ring.outerRadiusPx - ring.bandPx
    drawLine(
        color = color,
        start = offsetOf(pointOnRing(ring.center, inner, postureRotationDeg)),
        end = offsetOf(pointOnRing(ring.center, inner + ring.bandPx * MINOR_TICK_FRACTION, postureRotationDeg)),
        strokeWidth = INDEX_STROKE_PX,
        cap = StrokeCap.Round,
    )
}

// Each marked point is a coloured wedge at its real bearing minus the heading, so it keeps pointing the right way as the body turns.
fun DrawScope.drawTacticalWedges(
    points: Map<TacticalKind, GeoPoint>,
    here: GeoPoint?,
    azimuthDeg: Double,
    ring: RingGeometry,
    colors: TacticalWedgeColors,
    measurer: TextMeasurer,
) {
    val origin = here ?: return
    points.forEach { (kind, point) -> drawWedge(kind, point, origin, azimuthDeg, ring, colors, measurer) }
}

private fun DrawScope.drawWedge(
    kind: TacticalKind,
    point: GeoPoint,
    origin: GeoPoint,
    azimuthDeg: Double,
    ring: RingGeometry,
    colors: TacticalWedgeColors,
    measurer: TextMeasurer,
) {
    val screenAngle = wedgeScreenAngleDeg(bearingDeg(origin, point), azimuthDeg)
    val color = wedgeColor(kind, colors)
    drawWedgeShape(screenAngle, ring, color, wedgeStyle(kind))
    drawWedgeLabel(tacticalDistanceLabel(distanceM(origin, point)), screenAngle, ring, color, measurer)
}

// Allies share one colour and the filled shape; the two-letter label tells them apart.
fun DrawScope.drawMateWedges(
    marks: List<MateMark>,
    azimuthDeg: Double,
    ring: RingGeometry,
    color: Color,
    measurer: TextMeasurer,
) {
    marks.forEach { drawMate(it, azimuthDeg, ring, color, measurer) }
}

private fun DrawScope.drawMate(
    mark: MateMark,
    azimuthDeg: Double,
    ring: RingGeometry,
    color: Color,
    measurer: TextMeasurer,
) {
    val screenAngle = wedgeScreenAngleDeg(mark.bearingDeg, azimuthDeg)
    drawWedgeShape(screenAngle, ring, color, WedgeStyle.FILLED)
    drawWedgeLabel("${mark.label} ${tacticalDistanceLabel(mark.distanceM)}", screenAngle, ring, color, measurer)
}

private fun DrawScope.drawWedgeShape(angleDeg: Float, ring: RingGeometry, color: Color, style: WedgeStyle) {
    val apex = pointOnRing(ring.center, ring.outerRadiusPx, angleDeg)
    val base = ring.outerRadiusPx - ring.bandPx * WEDGE_BASE_FRACTION
    val left = pointOnRing(ring.center, base, angleDeg - WEDGE_SPREAD_DEG)
    val right = pointOnRing(ring.center, base, angleDeg + WEDGE_SPREAD_DEG)
    val path = Path().apply {
        moveTo(apex.x, apex.y)
        lineTo(left.x, left.y)
        lineTo(right.x, right.y)
        close()
    }
    when (style) {
        WedgeStyle.FILLED -> drawPath(path, color)
        WedgeStyle.HOLLOW -> drawPath(path, color, style = Stroke(WEDGE_STROKE_PX))
        WedgeStyle.DASHED -> drawPath(path, color, style = Stroke(WEDGE_STROKE_PX, pathEffect = PathEffect.dashPathEffect(WEDGE_DASH)))
    }
}

private fun DrawScope.drawWedgeLabel(label: String, angleDeg: Float, ring: RingGeometry, color: Color, measurer: TextMeasurer) {
    val layout = measurer.measure(label, TextStyle(color = color, fontSize = WEDGE_LABEL_SIZE, fontFamily = BlindsideFonts.Mono))
    val anchor = pointOnRing(ring.center, ring.outerRadiusPx - ring.bandPx * WEDGE_LABEL_FRACTION, angleDeg)
    drawText(layout, topLeft = Offset(anchor.x - layout.size.width / 2f, anchor.y - layout.size.height / 2f))
}

// Colour and shape both distinguish the kinds, so the wedge is never read by colour alone.
private fun wedgeColor(kind: TacticalKind, colors: TacticalWedgeColors): Color = when (kind) {
    TacticalKind.BASE -> colors.base
    TacticalKind.SPAWN -> colors.spawn
    TacticalKind.OBJECTIVE -> colors.objective
}

private fun wedgeStyle(kind: TacticalKind): WedgeStyle = when (kind) {
    TacticalKind.BASE -> WedgeStyle.FILLED
    TacticalKind.SPAWN -> WedgeStyle.HOLLOW
    TacticalKind.OBJECTIVE -> WedgeStyle.DASHED
}

private fun offsetOf(point: PointPx) = Offset(point.x, point.y)
