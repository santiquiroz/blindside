package io.github.santiquiroz.blindside.wear

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import io.github.santiquiroz.blindside.wear.ble.needsRetry
import io.github.santiquiroz.blindside.wear.permissions.SESSION_PERMISSIONS
import io.github.santiquiroz.blindside.wear.permissions.StartDecision
import io.github.santiquiroz.blindside.wear.permissions.startDecision
import io.github.santiquiroz.blindside.wear.session.SessionCommands
import io.github.santiquiroz.blindside.wear.session.SessionSource
import io.github.santiquiroz.blindside.wear.session.SessionStore
import io.github.santiquiroz.blindside.wear.session.SessionUiState
import io.github.santiquiroz.blindside.wear.settings.AppSettings
import io.github.santiquiroz.blindside.wear.settings.SettingsRepository
import io.github.santiquiroz.blindside.wear.settings.toggledUsage
import kotlinx.coroutines.launch

@Composable
fun SpikeScreen(settingsRepository: SettingsRepository) {
    val context = LocalContext.current
    val session by SessionStore.state.collectAsStateWithLifecycle()
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    var wakeLock by remember { mutableStateOf(true) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (startDecision(grants) is StartDecision.Start) SessionCommands.start(context, SessionSource.BELT, wakeLock)
    }
    val toggleUsage = { scope.launch { settingsRepository.update { it.copy(vibrationUsage = toggledUsage(it.vibrationUsage)) } } }
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { ListHeader { Text("Blindside (spikes)") } }
        item { Text(spikeStatusLine(session), fontSize = 12.sp) }
        if (session.running) {
            runningChips(context, session)
        } else {
            item { SpikeChip("Partida (cinturón)") { launcher.launch(SESSION_PERMISSIONS) } }
            item { SpikeChip("Demo") { SessionCommands.start(context, SessionSource.DEMO, wakeLock) } }
            item { SpikeChip("Wake lock: ${if (wakeLock) "sí" else "no (S1)"}") { wakeLock = !wakeLock } }
            item { SpikeChip("Vibración: ${settings.vibrationUsage.name}") { toggleUsage() } }
        }
        session.lastRecordingName?.let { name -> item { Text("Última: $name", fontSize = 11.sp) } }
    }
}

private fun ScalingLazyListScope.runningChips(context: Context, session: SessionUiState) {
    if (needsRetry(session.ble)) item { SpikeChip("Reintentar") { SessionCommands.retryLink(context) } }
    item { SpikeChip("Marcar") { SessionCommands.marker(context) } }
    item { SpikeChip("Detener") { SessionCommands.stop(context) } }
}

@Composable
private fun SpikeChip(label: String, onClick: () -> Unit) {
    Chip(
        label = { Text(label, maxLines = 2) },
        onClick = onClick,
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun spikeStatusLine(session: SessionUiState): String =
    "${session.ble.name} · ${session.recordingName ?: "sin grabación"}"
