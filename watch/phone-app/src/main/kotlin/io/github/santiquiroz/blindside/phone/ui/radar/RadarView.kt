package io.github.santiquiroz.blindside.phone.ui.radar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.phone.ui.theme.AccentColor
import io.github.santiquiroz.blindside.phone.ui.theme.AccentDimColor
import io.github.santiquiroz.blindside.shared.radar.BlipStyle
import io.github.santiquiroz.blindside.shared.radar.ContactTone
import io.github.santiquiroz.blindside.shared.radar.PointPx
import io.github.santiquiroz.blindside.shared.radar.TACTICAL_RADAR_COLORS
import io.github.santiquiroz.blindside.shared.radar.contactTone
import io.github.santiquiroz.blindside.shared.radar.drawRadar
import io.github.santiquiroz.blindside.shared.radar.showContacts
import io.github.santiquiroz.blindside.shared.radar.toDrawModel

private val GLYPH_SIZE = 18.dp
private val GLYPH_STROKE = 2.dp
private val GLYPH_DASH = 3.dp
private const val GLYPH_RADIUS_FRACTION = 0.33f

@Composable
fun RadarView(scene: RadarScene?, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.semantics { contentDescription = radarDescription(scene) }) {
        val model = toDrawModel(scene, size.width, size.height, PointPx(0f, 0f), showContacts(scene, ambient = false))
        drawRadar(model, TACTICAL_RADAR_COLORS, measurer = measurer)
    }
}

@Composable
fun ConfidenceGlyph(style: BlipStyle) {
    val color = if (contactTone(style) == ContactTone.DIM) AccentDimColor else AccentColor
    Canvas(Modifier.size(GLYPH_SIZE)) { drawGlyph(style, color, size.minDimension * GLYPH_RADIUS_FRACTION) }
}

private fun DrawScope.drawGlyph(style: BlipStyle, color: Color, radius: Float) {
    if (style == BlipStyle.FILLED) return drawCircle(color, radius)
    val dash = if (style == BlipStyle.DASHED) PathEffect.dashPathEffect(floatArrayOf(GLYPH_DASH.toPx(), GLYPH_DASH.toPx())) else null
    drawCircle(color, radius, style = Stroke(width = GLYPH_STROKE.toPx(), pathEffect = dash))
}
