package io.github.santiquiroz.blindside.wear.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import io.github.santiquiroz.blindside.wear.ble.needsRetry
import io.github.santiquiroz.blindside.wear.permissions.SESSION_PERMISSIONS
import io.github.santiquiroz.blindside.wear.permissions.StartDecision
import io.github.santiquiroz.blindside.wear.permissions.startDecision
import io.github.santiquiroz.blindside.wear.practice.needsPractice
import io.github.santiquiroz.blindside.wear.session.SessionCommands
import io.github.santiquiroz.blindside.wear.session.SessionSource
import io.github.santiquiroz.blindside.wear.session.SessionUiState
import io.github.santiquiroz.blindside.wear.settings.AppSettings
import io.github.santiquiroz.blindside.wear.settings.SettingsTransform
import io.github.santiquiroz.blindside.wear.ui.radar.eliminatedActionLabel

@Composable
fun HomeScreen(
    session: SessionUiState,
    settings: AppSettings,
    onNavigate: (String) -> Unit,
    onUpdateSettings: (SettingsTransform) -> Unit,
) {
    if (session.running) RunningHome(session, settings, onNavigate, onUpdateSettings) else IdleHome(session, settings, onNavigate)
}

@Composable
private fun IdleHome(session: SessionUiState, settings: AppSettings, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    var bluetoothBlocked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        bluetoothBlocked = !startBeltSession(context, startDecision(grants), onNavigate)
    }
    val practiceDue = needsPractice(settings.quizPassedAtEpochMs, System.currentTimeMillis())
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { ListHeader { Text("Blindside") } }
        if (bluetoothBlocked) item { BlockedNotice { openAppSettings(context) } }
        session.startError?.let { error -> item { Notice(startErrorMessage(error)) } }
        item {
            StartChip(
                practiceDue = practiceDue,
                onStart = { launcher.launch(SESSION_PERMISSIONS) },
                onPractice = { onNavigate(ROUTE_PRACTICE) },
            )
        }
        item { NavChip("Demo") { startDemo(context, onNavigate) } }
        item { NavChip("Práctica") { onNavigate(ROUTE_PRACTICE) } }
        item { NavChip("Ajustes") { onNavigate(ROUTE_SETTINGS) } }
        session.lastRecordingName?.let { name -> item { Notice("Última grabación: $name") } }
    }
}

@Composable
private fun RunningHome(
    session: SessionUiState,
    settings: AppSettings,
    onNavigate: (String) -> Unit,
    onUpdateSettings: (SettingsTransform) -> Unit,
) {
    val context = LocalContext.current
    var confirmingStop by remember { mutableStateOf(false) }
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { ListHeader { Text(sessionHeadline(session)) } }
        if (needsRetry(session.ble)) item { RetryNotice(bleStatusLabel(session.ble)) { SessionCommands.retryLink(context) } }
        if (session.recordingFailed) item { Text(RECORDING_FAILED_MESSAGE, color = WARNING_AMBER) }
        item { NavChip("Ver radar") { onNavigate(ROUTE_RADAR) } }
        item { NavChip(eliminatedActionLabel(settings.eliminated)) { onUpdateSettings { it.copy(eliminated = !it.eliminated) } } }
        item { NavChip("Marcar rival") { SessionCommands.marker(context) } }
        item { NavChip(stopLabel(confirmingStop)) { confirmingStop = handleStopTap(context, confirmingStop) } }
        session.recordingName?.let { name -> item { Notice(name) } }
    }
}

@Composable
private fun StartChip(practiceDue: Boolean, onStart: () -> Unit, onPractice: () -> Unit) {
    Chip(
        label = { Text(startChipLabel(practiceDue)) },
        onClick = if (practiceDue) onPractice else onStart,
        colors = ChipDefaults.primaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BlockedNotice(onOpenSettings: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Notice(BLUETOOTH_DENIED_MESSAGE)
        NavChip("Abrir ajustes", onOpenSettings)
    }
}

@Composable
private fun RetryNotice(message: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = WARNING_AMBER)
        NavChip("Reintentar", onRetry)
    }
}

private fun startBeltSession(context: Context, decision: StartDecision, onNavigate: (String) -> Unit): Boolean =
    when (decision) {
        is StartDecision.Start -> {
            SessionCommands.start(context, SessionSource.BELT)
            onNavigate(ROUTE_RADAR)
            true
        }
        StartDecision.BlockedBluetoothDenied -> false
    }

private fun startDemo(context: Context, onNavigate: (String) -> Unit) {
    SessionCommands.start(context, SessionSource.DEMO)
    onNavigate(ROUTE_RADAR)
}

private fun handleStopTap(context: Context, confirming: Boolean): Boolean {
    if (confirming) SessionCommands.stop(context)
    return !confirming
}

private fun openAppSettings(context: Context) {
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    if (!tryStartActivity(context, details)) tryStartActivity(context, Intent(Settings.ACTION_SETTINGS))
}

// Wear OS builds may not ship the per-app details screen, so fall back to the general settings.
private fun tryStartActivity(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (error: ActivityNotFoundException) {
    false
}
