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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import io.github.santiquiroz.blindside.shared.radar.CLASSIC_RADAR_COLORS
import io.github.santiquiroz.blindside.shared.radar.DND_RADAR_WARNING
import io.github.santiquiroz.blindside.shared.radar.PointPx
import io.github.santiquiroz.blindside.shared.radar.StatusItem
import io.github.santiquiroz.blindside.shared.radar.centerLabel
import io.github.santiquiroz.blindside.shared.radar.drawRadar
import io.github.santiquiroz.blindside.shared.radar.eliminatedActionLabel
import io.github.santiquiroz.blindside.shared.radar.rotatedAbout
import io.github.santiquiroz.blindside.shared.radar.screenCenter
import io.github.santiquiroz.blindside.shared.radar.showContacts
import io.github.santiquiroz.blindside.shared.radar.statusItems
import io.github.santiquiroz.blindside.shared.radar.toDrawModel
import io.github.santiquiroz.blindside.shared.radar.warningLabel
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import io.github.santiquiroz.blindside.wear.ui.KeepScreenOn
import io.github.santiquiroz.blindside.wear.ui.LABEL_GRAY
import io.github.santiquiroz.blindside.wear.ui.ReportRadarVisibility
import io.github.santiquiroz.blindside.wear.ui.STATUS_BAD
import io.github.santiquiroz.blindside.wear.ui.STATUS_OK
import io.github.santiquiroz.blindside.wear.ui.WARNING_AMBER
import io.github.santiquiroz.blindside.wear.ui.burnInOffset
import io.github.santiquiroz.blindside.wear.ui.keepScreenOn
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val BURN_IN_CLOCK_TICK_MS = 30_000L
private val CENTER_LABEL_SIZE = 26.sp
private val LINK_MESSAGE_SIZE = 14.sp
private val LINK_MESSAGE_SIDE_PADDING = 28.dp

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
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            val logical = toDrawModel(session.scene, size.width, size.height, shift, contacts)
            drawRadar(logical.rotatedAbout(screenCenter(size.width, size.height, shift), rotationDeg), CLASSIC_RADAR_COLORS)
        }
        RadarOverlay(session, ambient, shift, rotationDeg, onToggleEliminated)
    }
}

@Composable
private fun RadarOverlay(
    session: SessionUiState,
    ambient: Boolean,
    shift: PointPx,
    rotationDeg: Float,
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
        if (!ambient) BottomPanel(session, onToggleEliminated, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun CenterLabel(label: String, isLinkMessage: Boolean, modifier: Modifier) {
    Text(
        label,
        modifier.padding(horizontal = LINK_MESSAGE_SIDE_PADDING),
        color = LABEL_GRAY,
        fontSize = if (isLinkMessage) LINK_MESSAGE_SIZE else CENTER_LABEL_SIZE,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun BottomPanel(session: SessionUiState, onToggleEliminated: () -> Unit, modifier: Modifier) {
    Column(modifier = modifier.padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (session.dndMaySilenceAlerts) Text(DND_RADAR_WARNING, color = WARNING_AMBER, fontSize = 11.sp, maxLines = 1)
        warningLabel(session.scene?.warnings.orEmpty())?.let { Text(it, color = WARNING_AMBER, fontSize = 11.sp) }
        StatusRow(statusItems(session.scene, session.watchSteps))
        EliminatedChip(session.eliminated, onToggleEliminated)
    }
}

@Composable
private fun StatusRow(items: List<StatusItem>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            Text(item.label, color = if (item.ok) STATUS_OK else STATUS_BAD, fontSize = 10.sp)
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
