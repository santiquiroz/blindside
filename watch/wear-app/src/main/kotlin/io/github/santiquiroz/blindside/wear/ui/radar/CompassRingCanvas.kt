package io.github.santiquiroz.blindside.wear.ui.radar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import io.github.santiquiroz.blindside.shared.theme.BlindsideFonts

private const val MINOR_TICK_FRACTION = 0.3f
private const val MAJOR_TICK_FRACTION = 0.5f
private const val TICK_STROKE_PX = 2f
private const val INDEX_STROKE_PX = 5f
private val LETTER_SIZE = 10.sp

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

private fun offsetOf(point: PointPx) = Offset(point.x, point.y)
