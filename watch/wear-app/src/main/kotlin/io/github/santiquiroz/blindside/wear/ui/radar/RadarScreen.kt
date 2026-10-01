package io.github.santiquiroz.blindside.wear.ui.radar

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import io.github.santiquiroz.blindside.shared.compass.CompassReading
import io.github.santiquiroz.blindside.shared.compass.compassColors
import io.github.santiquiroz.blindside.shared.compass.compassWarningLabel
import io.github.santiquiroz.blindside.shared.compass.frontHeadingDeg
import io.github.santiquiroz.blindside.shared.compass.headingText
import io.github.santiquiroz.blindside.shared.radar.DND_RADAR_WARNING
import io.github.santiquiroz.blindside.shared.radar.PointPx
import io.github.santiquiroz.blindside.shared.radar.StatusItem
import io.github.santiquiroz.blindside.shared.radar.StatusMark
import io.github.santiquiroz.blindside.shared.radar.centerLabel
import io.github.santiquiroz.blindside.shared.radar.drawRadar
import io.github.santiquiroz.blindside.shared.radar.eliminatedActionLabel
import io.github.santiquiroz.blindside.shared.radar.radarColorsFor
import io.github.santiquiroz.blindside.shared.radar.rotatedAbout
import io.github.santiquiroz.blindside.shared.radar.screenCenter
import io.github.santiquiroz.blindside.shared.radar.showContacts
import io.github.santiquiroz.blindside.shared.radar.statusItems
import io.github.santiquiroz.blindside.shared.radar.statusMark
import io.github.santiquiroz.blindside.shared.radar.statusRows
import io.github.santiquiroz.blindside.shared.radar.toDrawModel
import io.github.santiquiroz.blindside.shared.radar.warningLabel
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import io.github.santiquiroz.blindside.shared.theme.BlindsideColors
import io.github.santiquiroz.blindside.shared.theme.BlindsideFonts
import io.github.santiquiroz.blindside.wear.ui.KeepScreenOn
import io.github.santiquiroz.blindside.wear.ui.ReportRadarVisibility
import io.github.santiquiroz.blindside.wear.ui.burnInOffset
import io.github.santiquiroz.blindside.wear.ui.keepScreenOn
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val BURN_IN_CLOCK_TICK_MS = 30_000L
private val CENTER_LABEL_SIZE = 26.sp
private val LINK_MESSAGE_SIZE = 14.sp
private val LINK_MESSAGE_SIDE_PADDING = 28.dp
private val STATUS_DOT_SIZE = 4.dp
private val STATUS_DOT_STROKE = 1.dp
private val COMPASS_BAND = 16.dp
private val PANEL_BOTTOM_PADDING = 22.dp

@Composable
fun RadarScreen(
    session: SessionUiState,
    settings: AppSettings,
    ambient: Boolean,
    onToggleEliminated: () -> Unit,
) {
    ReportRadarVisibility()
    KeepScreenOn(keepScreenOn(settings.screenMode, session.eliminated))
    val elapsedMs by rememberElapsedMs()
    val shift = burnInOffset(settings.screenMode, elapsedMs)
    val contacts = showContacts(session.scene, ambient)
    val rotationDeg = settings.posture.rotationDeg
    val compassOn = settings.compass && !ambient
    val compass by rememberCompassReading(compassOn)
    val reading = compass.takeIf { compassOn }
    val bandPx = with(LocalDensity.current) { COMPASS_BAND.toPx() }
    val measurer = rememberTextMeasurer()
    Box(Modifier.fillMaxSize().background(BlindsideColors.Bg)) {
        Canvas(Modifier.fillMaxSize()) {
            val margin = if (compassOn) bandPx else 0f
            val pivot = screenCenter(size.width, size.height, shift)
            val logical = toDrawModel(session.scene, size.width, size.height, shift, contacts, margin)
            drawRadar(logical.rotatedAbout(pivot, rotationDeg), radarColorsFor(settings.contactColor))
            reading?.let {
                val ring = RingGeometry(pivot, size.minDimension / 2f, bandPx)
                drawCompassRing(it.azimuthDeg, ring, rotationDeg, compassColors(settings.screenMode, it.trust), measurer)
            }
        }
        RadarOverlay(session, ambient, shift, rotationDeg, reading, onToggleEliminated)
    }
}

@Composable
private fun RadarOverlay(
    session: SessionUiState,
    ambient: Boolean,
    shift: PointPx,
    rotationDeg: Float,
    reading: CompassReading?,
    onToggleEliminated: () -> Unit,
) {
    val link = radarMessage(session)
    val placement = Modifier.fillMaxSize()
        .offset { IntOffset(shift.x.roundToInt(), shift.y.roundToInt()) }
        // Turns after the shift, about the shifted center like the drawing, so the panel stays over the rear and off the flanks.
        .graphicsLayer { rotationZ = rotationDeg }
    Box(placement) {
        centerLabel(session.scene, ambient, link)?.let { label ->
            CenterLabel(label, isLinkMessage = label == link, Modifier.align(Alignment.Center))
        }
        // Spec §5.4: the dimmed screen keeps ≤ 15 % lit pixels, so ambient shows only the fan and "--".
        if (!ambient) BottomPanel(session, compassWarningLabel(reading), onToggleEliminated, Modifier.align(Alignment.BottomCenter))
        reading?.let { HeadingWindow(headingText(frontHeadingDeg(it.azimuthDeg, rotationDeg)), Modifier.align(Alignment.BottomCenter)) }
    }
}

@Composable
private fun CenterLabel(label: String, isLinkMessage: Boolean, modifier: Modifier) {
    Text(
        label,
        modifier.padding(horizontal = LINK_MESSAGE_SIDE_PADDING),
        color = BlindsideColors.Text2,
        fontSize = if (isLinkMessage) LINK_MESSAGE_SIZE else CENTER_LABEL_SIZE,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun BottomPanel(session: SessionUiState, compassWarning: String?, onToggleEliminated: () -> Unit, modifier: Modifier) {
    Column(modifier = modifier.padding(bottom = PANEL_BOTTOM_PADDING), horizontalAlignment = Alignment.CenterHorizontally) {
        compassWarning?.let { Text(it, color = BlindsideColors.Warn, fontSize = 11.sp, maxLines = 1) }
        if (session.dndMaySilenceAlerts) Text(DND_RADAR_WARNING, color = BlindsideColors.Warn, fontSize = 11.sp, maxLines = 1)
        warningLabel(session.scene?.warnings.orEmpty())?.let { Text(it, color = BlindsideColors.Warn, fontSize = 11.sp) }
        statusRows(statusItems(session.scene, session.watchSteps)).forEach { StatusRow(it) }
        EliminatedChip(session.eliminated, onToggleEliminated)
    }
}

@Composable
private fun HeadingWindow(text: String, modifier: Modifier) {
    // A window in the bezel at the rear, like a dive watch date: the letters pass under it and the fan keeps the front.
    Text(
        text,
        modifier.padding(bottom = 4.dp).background(BlindsideColors.Bg, RoundedCornerShape(6.dp)).padding(horizontal = 4.dp),
        color = BlindsideColors.Text,
        fontFamily = BlindsideFonts.Mono,
        fontSize = 11.sp,
        maxLines = 1,
    )
}

@Composable
private fun StatusRow(items: List<StatusItem>) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        items.forEach { StatusChip(it) }
    }
}

@Composable
private fun StatusChip(item: StatusItem) {
    val tint = if (item.ok) BlindsideColors.Accent else BlindsideColors.AlertRed
    Row(
        Modifier.background(BlindsideColors.Surface, CircleShape).padding(horizontal = 3.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        StatusDot(statusMark(item), tint)
        Text(item.label, color = tint, fontSize = 9.sp, fontFamily = BlindsideFonts.Mono, maxLines = 1)
    }
}

@Composable
private fun StatusDot(mark: StatusMark, tint: Color) {
    Canvas(Modifier.size(STATUS_DOT_SIZE)) {
        when (mark) {
            StatusMark.FILLED -> drawCircle(tint)
            StatusMark.HOLLOW -> drawCircle(tint, style = Stroke(width = STATUS_DOT_STROKE.toPx()))
        }
    }
}

@Composable
private fun EliminatedChip(eliminated: Boolean, onToggle: () -> Unit) {
    Chip(
        label = { Text(eliminatedActionLabel(eliminated), maxLines = 1) },
        onClick = onToggle,
        colors = if (eliminated) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(0.6f),
    )
}

@Composable
private fun rememberElapsedMs(): State<Long> = produceState(SystemClock.elapsedRealtime()) {
    while (true) {
        delay(BURN_IN_CLOCK_TICK_MS)
        value = SystemClock.elapsedRealtime()
    }
}
