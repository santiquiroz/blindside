package io.github.santiquiroz.blindside.phone.ui.radar

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.santiquiroz.blindside.phone.ui.PhoneActions
import io.github.santiquiroz.blindside.phone.ui.PhoneUiState
import io.github.santiquiroz.blindside.phone.ui.common.KeepScreenOn
import io.github.santiquiroz.blindside.phone.ui.common.MIN_TOUCH
import io.github.santiquiroz.blindside.phone.ui.common.NumberText
import io.github.santiquiroz.blindside.phone.ui.common.StatusChip
import io.github.santiquiroz.blindside.phone.ui.theme.AlertRedColor
import io.github.santiquiroz.blindside.phone.ui.theme.Text2Color
import io.github.santiquiroz.blindside.phone.ui.theme.WarnColor
import io.github.santiquiroz.blindside.shared.ble.needsRetry
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.radar.warningLabel

private val START_BUTTON_SIZE = 200.dp
private val START_ICON_SIZE = 56.dp
private val CONTACT_LIST_MAX_HEIGHT = 200.dp

@Composable
fun RadarTab(state: PhoneUiState, actions: PhoneActions) {
    if (isLiveRadar(state.session)) LiveRadar(state.session, actions) else IdleRadar(state, actions)
}

@Composable
private fun IdleRadar(state: PhoneUiState, actions: PhoneActions) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    ) {
        StartRadarButton(actions.startRadar)
        Text(
            idleRadarHint(state.settings.beltAddress != null, state.session.purpose, state.prefs.linkSupport),
            style = MaterialTheme.typography.bodyMedium,
            color = Text2Color,
            textAlign = TextAlign.Center,
        )
        state.session.startError?.let { Text(startErrorText(it), color = AlertRedColor, textAlign = TextAlign.Center) }
        if (state.bluetoothBlocked) BluetoothBlockedNotice(actions.openAppSettings)
    }
}

@Composable
private fun StartRadarButton(onStart: () -> Unit) {
    Button(onClick = onStart, shape = CircleShape, modifier = Modifier.size(START_BUTTON_SIZE)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(START_ICON_SIZE))
            Spacer(Modifier.height(8.dp))
            Text(START_RADAR_LABEL, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun BluetoothBlockedNotice(onOpenSettings: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(BLUETOOTH_DENIED_TEXT, color = WarnColor, textAlign = TextAlign.Center)
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = MIN_TOUCH)) { Text("Abrir ajustes") }
    }
}

@Composable
private fun LiveRadar(session: SessionUiState, actions: PhoneActions) {
    KeepScreenOn()
    var confirmingStop by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RadarHeader(session, onStop = { confirmingStop = true })
        SensorChipRow(sensorChips(session.scene))
        radarBanner(session)?.let { LinkBanner(it, needsRetry(session.ble), actions.retryLink) }
        warningLabel(session.scene?.warnings.orEmpty())?.let { Text(it, color = WarnColor, style = MaterialTheme.typography.labelLarge) }
        RadarView(session.scene, Modifier.weight(1f).fillMaxWidth())
        ContactList(contactRows(session.scene), Modifier.fillMaxWidth().heightIn(max = CONTACT_LIST_MAX_HEIGHT))
    }
    if (confirmingStop) {
        StopDialog(
            onConfirm = {
                confirmingStop = false
                actions.stop()
            },
            onDismiss = { confirmingStop = false },
        )
    }
}

@Composable
private fun RadarHeader(session: SessionUiState, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Radar en vivo", style = MaterialTheme.typography.titleLarge)
            recordingLine(session)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Text2Color) }
        }
        TextButton(onClick = onStop, modifier = Modifier.heightIn(min = MIN_TOUCH)) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Detener")
        }
    }
}

@Composable
fun SensorChipRow(chips: List<SensorChip>) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { StatusChip(it.label, it.ok) }
    }
}

@Composable
private fun LinkBanner(message: String, canRetry: Boolean, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f), color = WarnColor, style = MaterialTheme.typography.bodyMedium)
        if (canRetry) TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = MIN_TOUCH)) { Text("Reintentar") }
    }
}

@Composable
private fun ContactList(rows: List<ContactRow>, modifier: Modifier) {
    if (rows.isEmpty()) {
        Text("Sin contactos", modifier.padding(vertical = 8.dp), color = Text2Color)
        return
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(rows, key = { it.id }) { ContactLine(it) }
    }
}

@Composable
private fun ContactLine(row: ContactRow) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConfidenceGlyph(row.style)
        NumberText(row.id, Modifier.width(44.dp))
        NumberText(row.distance, Modifier.width(72.dp))
        NumberText(row.bearing, Modifier.width(56.dp))
        Text(row.confidence, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Text2Color)
        NumberText(row.age, style = MaterialTheme.typography.bodyMedium, color = Text2Color)
    }
}

@Composable
private fun StopDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text("Detener") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Seguir") } },
        title = { Text("¿Detener el radar?") },
        text = { Text("Se cierra la grabación y el celular suelta el cinturón; el reloj sigue igual.") },
    )
}
