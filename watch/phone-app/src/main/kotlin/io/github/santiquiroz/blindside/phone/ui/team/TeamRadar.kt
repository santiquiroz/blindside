package io.github.santiquiroz.blindside.phone.ui.team

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.santiquiroz.blindside.phone.tak.TeamPicture
import io.github.santiquiroz.blindside.shared.compass.CALIBRATE_WARNING
import io.github.santiquiroz.blindside.shared.compass.CompassReading
import io.github.santiquiroz.blindside.shared.compass.CompassTrust
import io.github.santiquiroz.blindside.shared.compass.normalizedDeg
import io.github.santiquiroz.blindside.shared.theme.BlindsideColors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val RING_COUNT = 3
private const val NO_GPS_TEXT = "Sin GPS: esperando posición"
private const val NORTH_TEXT = "N"
private val EDGE_PAD = 8.dp
private val RING_WIDTH = 1.dp
private val DOT_RADIUS = 7.dp
private val SELF_SIZE = 9.dp
private val MARK_GAP = 6.dp
private val LABEL_INSET = 14.dp
private val TOP_PAD = 20.dp
private val ringStyle = TextStyle(fontSize = 11.sp, color = BlindsideColors.Text2)
private val labelStyle = TextStyle(fontSize = 12.sp, color = BlindsideColors.Text)
private val northStyle = TextStyle(fontSize = 14.sp, color = BlindsideColors.Text)
private val centerStyle = TextStyle(fontSize = 14.sp, color = BlindsideColors.Text)
private val warnStyle = TextStyle(fontSize = 14.sp, color = BlindsideColors.Warn)

@Composable
fun TeamRadar(
    picture: TeamPicture,
    heading: CompassReading?,
    rangeM: Double,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val headingDeg = heading?.azimuthDeg ?: 0.0
    val here = picture.self?.point
    val marks = if (here == null) emptyList() else teamMarks(here, headingDeg, rangeM, picture.mates, picture.contacts)
    Canvas(modifier = modifier.clickable(onClick = onTap)) {
        drawRect(Color.Black)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = min(size.width, size.height) / 2f - EDGE_PAD.toPx()
        drawRings(cx, cy, radius, rangeM, measurer)
        drawNorth(cx, cy, radius, headingDeg, measurer)
        if (here == null) {
            drawCentered(measurer, NO_GPS_TEXT, centerStyle, cx, cy)
            return@Canvas
        }
        drawSelf(cx, cy)
        marks.forEach { drawMark(it, cx, cy, radius, measurer) }
        if (heading == null || heading.trust != CompassTrust.GOOD) drawCentered(measurer, CALIBRATE_WARNING, warnStyle, cx, TOP_PAD.toPx())
    }
}

private fun DrawScope.drawRings(cx: Float, cy: Float, radius: Float, rangeM: Double, measurer: TextMeasurer) {
    val ring = BlindsideColors.Text2.copy(alpha = 0.4f)
    for (i in 1..RING_COUNT) {
        val ringR = radius * i / RING_COUNT
        drawCircle(ring, ringR, Offset(cx, cy), style = Stroke(width = RING_WIDTH.toPx()))
        val label = "${(rangeM * i / RING_COUNT).roundToInt()} m"
        val layout = measurer.measure(label, ringStyle)
        drawText(measurer, label, Offset(cx + MARK_GAP.toPx(), cy - ringR - layout.size.height / 2f), ringStyle)
    }
}

private fun DrawScope.drawNorth(cx: Float, cy: Float, radius: Float, headingDeg: Double, measurer: TextMeasurer) {
    val pos = screenPos(normalizedDeg(0.0 - headingDeg), radius - LABEL_INSET.toPx(), cx, cy)
    drawCentered(measurer, NORTH_TEXT, northStyle, pos.x, pos.y)
}

private fun DrawScope.drawSelf(cx: Float, cy: Float) {
    val s = SELF_SIZE.toPx()
    drawPath(selfPath(cx, cy, s), Color.White)
}

private fun selfPath(cx: Float, cy: Float, s: Float): Path = Path().apply {
    moveTo(cx, cy - s)
    lineTo(cx + s * 0.7f, cy + s * 0.6f)
    lineTo(cx - s * 0.7f, cy + s * 0.6f)
    close()
}

private fun DrawScope.drawMark(mark: RadarMark, cx: Float, cy: Float, radius: Float, measurer: TextMeasurer) {
    val color = markColor(mark)
    if (mark.offScale) return drawEdgeTriangle(mark.screenAngleDeg, cx, cy, radius, color)
    val pos = screenPos(mark.screenAngleDeg, radius * mark.radiusFraction.toFloat(), cx, cy)
    drawCircle(color, DOT_RADIUS.toPx(), pos)
    if (mark.kind != MarkKind.CONTACT) drawAllyLabel(mark, pos, measurer)
}

private fun markColor(mark: RadarMark): Color = when (mark.kind) {
    MarkKind.ALLY -> BlindsideColors.Ally
    MarkKind.STATION -> BlindsideColors.Station
    MarkKind.CONTACT -> BlindsideColors.Warn.copy(alpha = contactAlpha(mark.ageS))
}

private fun DrawScope.drawAllyLabel(mark: RadarMark, pos: Offset, measurer: TextMeasurer) {
    val text = "${mark.label} ${mark.distanceM.roundToInt()} m"
    val layout = measurer.measure(text, labelStyle)
    val want = Offset(pos.x + MARK_GAP.toPx(), pos.y - layout.size.height / 2f)
    drawText(measurer, text, clamped(size, layout.size.width, layout.size.height, want), labelStyle)
}

private fun clamped(size: Size, w: Int, h: Int, want: Offset): Offset = Offset(
    want.x.coerceIn(0f, (size.width - w).coerceAtLeast(0f)),
    want.y.coerceIn(0f, (size.height - h).coerceAtLeast(0f)),
)

private fun DrawScope.drawEdgeTriangle(angleDeg: Double, cx: Float, cy: Float, radius: Float, color: Color) {
    val rad = Math.toRadians(angleDeg)
    val dir = Offset(sin(rad).toFloat(), -cos(rad).toFloat())
    val tip = Offset(cx + dir.x * radius, cy + dir.y * radius)
    val len = SELF_SIZE.toPx()
    val back = Offset(tip.x - dir.x * len, tip.y - dir.y * len)
    val perp = Offset(-dir.y, dir.x)
    val half = len * 0.7f
    drawPath(
        Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(back.x + perp.x * half, back.y + perp.y * half)
            lineTo(back.x - perp.x * half, back.y - perp.y * half)
            close()
        },
        color,
    )
}

private fun DrawScope.drawCentered(measurer: TextMeasurer, text: String, style: TextStyle, cx: Float, cy: Float) {
    val layout = measurer.measure(text, style)
    drawText(measurer, text, Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f), style)
}

private fun screenPos(angleDeg: Double, r: Float, cx: Float, cy: Float): Offset {
    val rad = Math.toRadians(angleDeg)
    return Offset(cx + r * sin(rad).toFloat(), cy - r * cos(rad).toFloat())
}
