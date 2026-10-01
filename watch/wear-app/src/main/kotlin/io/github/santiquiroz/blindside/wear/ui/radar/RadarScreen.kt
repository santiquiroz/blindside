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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.wear.session.SessionUiState
import io.github.santiquiroz.blindside.wear.settings.AppSettings
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

@Composable
fun RadarScreen(
    session: SessionUiState,
    settings: AppSettings,
    ambient: Boolean,
    onToggleEliminated: () -> Unit,
) {
    ReportRadarVisibility()
    KeepScreenOn(keepScreenOn(settings.screenMode, settings.eliminated))
    val elapsedMs by rememberElapsedMs()
    val shift = burnInOffset(settings.screenMode, elapsedMs)
    val scene = session.scene
    val contacts = showContacts(scene, ambient)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRadar(toDrawModel(scene, size.width, size.height, shift, contacts))
        }
        RadarOverlay(scene, ambient, session.watchSteps, settings.eliminated, shift, onToggleEliminated)
    }
}

@Composable
private fun RadarOverlay(
    scene: RadarScene?,
    ambient: Boolean,
    watchSteps: Boolean,
    eliminated: Boolean,
    shift: PointPx,
    onToggleEliminated: () -> Unit,
) {
    Box(Modifier.fillMaxSize().offset { IntOffset(shift.x.roundToInt(), shift.y.roundToInt()) }) {
        centerLabel(scene, ambient)?.let { label ->
            Text(label, Modifier.align(Alignment.Center), color = LABEL_GRAY, fontSize = 26.sp)
        }
        // Spec §5.4: the dimmed screen keeps ≤ 15 % lit pixels, so ambient shows only the fan and "--".
        if (!ambient) BottomPanel(scene, watchSteps, eliminated, onToggleEliminated, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun BottomPanel(
    scene: RadarScene?,
    watchSteps: Boolean,
    eliminated: Boolean,
    onToggleEliminated: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier.padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        warningLabel(scene?.warnings.orEmpty())?.let { Text(it, color = WARNING_AMBER, fontSize = 11.sp) }
        StatusRow(statusItems(scene, watchSteps))
        EliminatedChip(eliminated, onToggleEliminated)
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
