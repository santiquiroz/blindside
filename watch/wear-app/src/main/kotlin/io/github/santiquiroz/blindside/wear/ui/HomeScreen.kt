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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import io.github.santiquiroz.blindside.wear.R
import io.github.santiquiroz.blindside.shared.ble.needsRetry
import io.github.santiquiroz.blindside.shared.permissions.SESSION_PERMISSIONS
import io.github.santiquiroz.blindside.shared.permissions.StartDecision
import io.github.santiquiroz.blindside.shared.permissions.startDecision
import io.github.santiquiroz.blindside.wear.session.WearSessionCommands
import io.github.santiquiroz.blindside.shared.session.SessionSource
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.radar.eliminatedActionLabel

private val START_CHIP_HEIGHT = 72.dp
private val START_ICON_SIZE = 28.dp

@Composable
fun HomeScreen(
    session: SessionUiState,
    onNavigate: (String) -> Unit,
    onShowRadar: () -> Unit,
    onToggleEliminated: () -> Unit,
) {
    if (session.running) RunningHome(session, onShowRadar, onToggleEliminated) else IdleHome(session, onNavigate, onShowRadar)
}

@Composable
private fun IdleHome(session: SessionUiState, onNavigate: (String) -> Unit, onShowRadar: () -> Unit) {
    val context = LocalContext.current
    var bluetoothBlocked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        bluetoothBlocked = !startBeltSession(context, startDecision(grants), onShowRadar)
    }
    val onStart = { startOrAskPermissions(context, onShowRadar) { launcher.launch(SESSION_PERMISSIONS) } }
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { ListHeader { Text("Blindside") } }
        if (bluetoothBlocked) item { BlockedNotice { openAppSettings(context) } }
        session.startError?.let { error -> item { Notice(startErrorMessage(error)) } }
        item { StartRadarChip(onStart) }
        item { CompactChip(onClick = { onNavigate(ROUTE_SETTINGS) }, label = { Text(SETTINGS_ENTRY_LABEL) }) }
        session.lastRecordingName?.let { name -> item { Notice("Última grabación: $name") } }
    }
}

@Composable
private fun RunningHome(
    session: SessionUiState,
    onShowRadar: () -> Unit,
    onToggleEliminated: () -> Unit,
) {
    val context = LocalContext.current
    var confirmingStop by remember { mutableStateOf(false) }
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { ListHeader { Text(sessionHeadline(session)) } }
        if (needsRetry(session.ble)) item { RetryNotice(bleStatusLabel(session.ble)) { WearSessionCommands.retryLink(context) } }
        if (session.recordingFailed) item { Text(RECORDING_FAILED_MESSAGE, color = WARNING_AMBER) }
        item { NavChip("Ver radar", onShowRadar) }
        item { NavChip(eliminatedActionLabel(session.eliminated), onToggleEliminated) }
        item { NavChip("Marcar rival") { WearSessionCommands.marker(context) } }
        item { NavChip(stopLabel(confirmingStop)) { confirmingStop = handleStopTap(context, confirmingStop) } }
        session.recordingName?.let { name -> item { Notice(name) } }
    }
}

@Composable
private fun StartRadarChip(onStart: () -> Unit) {
    Chip(
        label = { Text(START_RADAR_LABEL, fontSize = 18.sp, maxLines = 1) },
        icon = { Icon(painterResource(R.drawable.ic_radar), contentDescription = null, Modifier.size(START_ICON_SIZE)) },
        onClick = onStart,
        colors = ChipDefaults.primaryChipColors(),
        modifier = Modifier.fillMaxWidth().height(START_CHIP_HEIGHT),
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

private fun startOrAskPermissions(context: Context, onShowRadar: () -> Unit, requestPermissions: () -> Unit) {
    when (startTapAction(sessionGrants(context))) {
        StartTapAction.START_SESSION -> startRadar(context, SessionSource.BELT, onShowRadar)
        StartTapAction.REQUEST_PERMISSIONS -> requestPermissions()
    }
}

private fun startBeltSession(context: Context, decision: StartDecision, onShowRadar: () -> Unit): Boolean =
    when (decision) {
        is StartDecision.Start -> {
            startRadar(context, SessionSource.BELT, onShowRadar)
            true
        }
        StartDecision.BlockedBluetoothDenied -> false
    }

private fun handleStopTap(context: Context, confirming: Boolean): Boolean {
    if (confirming) WearSessionCommands.stop(context)
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
