package io.github.santiquiroz.blindside.phone.ui.belt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.core.config.RADAR_B
import io.github.santiquiroz.blindside.phone.bridge.PhoneBridge
import io.github.santiquiroz.blindside.phone.session.PhoneDiagnostics
import io.github.santiquiroz.blindside.phone.settings.LinkSupport
import io.github.santiquiroz.blindside.phone.settings.PhonePrefs
import io.github.santiquiroz.blindside.phone.ui.PhoneActions
import io.github.santiquiroz.blindside.phone.ui.PhoneUiState
import io.github.santiquiroz.blindside.phone.ui.common.MIN_TOUCH
import io.github.santiquiroz.blindside.phone.ui.common.NumberText
import io.github.santiquiroz.blindside.phone.ui.common.SectionCard
import io.github.santiquiroz.blindside.phone.ui.common.SwitchRow
import io.github.santiquiroz.blindside.phone.ui.radar.START_RADAR_LABEL
import io.github.santiquiroz.blindside.phone.ui.radar.SensorChipRow
import io.github.santiquiroz.blindside.phone.ui.radar.sensorChips
import io.github.santiquiroz.blindside.phone.ui.theme.AccentColor
import io.github.santiquiroz.blindside.phone.ui.theme.AlertRedColor
import io.github.santiquiroz.blindside.phone.ui.theme.Text2Color
import io.github.santiquiroz.blindside.shared.ble.BeltCommand
import io.github.santiquiroz.blindside.shared.session.SessionPurpose
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import kotlinx.coroutines.launch

private val RADARS = listOf(RADAR_A to "A", RADAR_B to "B")

@Composable
fun LinkSection(state: PhoneUiState, nowMs: Long, actions: PhoneActions) {
    SectionCard("Enlace") {
        Text(beltLinkText(state.session), style = MaterialTheme.typography.bodyLarge)
        Text(watchStatusLine(state.watchStatus, nowMs), style = MaterialTheme.typography.bodyMedium, color = Text2Color)
        if (state.session.running) SensorChipRow(sensorChips(state.session.scene))
        LinkButton(state, actions)
    }
}

@Composable
private fun LinkButton(state: PhoneUiState, actions: PhoneActions) {
    val full = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH)
    when {
        !state.session.running -> Button(onClick = actions.startDiagnostic, modifier = full) { Text("Conectar para diagnóstico") }
        state.session.purpose == SessionPurpose.DIAGNOSTIC -> OutlinedButton(onClick = actions.stop, modifier = full) { Text("Desconectar") }
    }
}

@Composable
fun DiagnosticSection(phone: PhoneDiagnostics, running: Boolean, linkUp: Boolean, onRefresh: () -> Unit, onConnect: () -> Unit) {
    SectionCard("Diagnóstico") {
        val live = liveDiagnostics(phone, running)
        val view = live.infoJson?.let(::parseBeltInfoView)
        if (view == null) NoDiagnostic(diagnosticPrompt(running), onConnect) else InfoDetails(infoRows(view), linkUp, onRefresh)
        val counters = counterRows(live.counters, live.rssiDbm)
        if (counters.isNotEmpty()) {
            Text("Contadores", style = MaterialTheme.typography.titleSmall)
            counters.forEach { InfoLine(it) }
        }
    }
}

@Composable
private fun NoDiagnostic(prompt: DiagnosticPrompt, onConnect: () -> Unit) {
    Text(prompt.text, color = Text2Color)
    if (prompt.offersConnect) {
        TextButton(onClick = onConnect, modifier = Modifier.heightIn(min = MIN_TOUCH)) { Text("Conectar para diagnóstico") }
    }
}

@Composable
private fun InfoDetails(rows: List<InfoRow>, linkUp: Boolean, onRefresh: () -> Unit) {
    rows.forEach { InfoLine(it) }
    OutlinedButton(onClick = onRefresh, enabled = linkUp, modifier = Modifier.heightIn(min = MIN_TOUCH)) {
        Icon(Icons.Filled.Refresh, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Actualizar")
    }
}

@Composable
private fun InfoLine(row: InfoRow) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (row.ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
            contentDescription = if (row.ok) "correcto" else "con fallo",
            tint = if (row.ok) AccentColor else AlertRedColor,
            modifier = Modifier.size(16.dp),
        )
        Text(row.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Text2Color)
        NumberText(row.value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ActionsSection(identify: ActionBlock?, restart: ActionBlock?, onCommand: (BeltCommand) -> Unit) {
    SectionCard("Acciones") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RADARS.forEach { (id, letter) ->
                OutlinedButton(
                    onClick = { onCommand(BeltCommand.RestartRadar(id)) },
                    enabled = restart == null,
                    modifier = Modifier.weight(1f).heightIn(min = MIN_TOUCH),
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Reiniciar radar $letter")
                }
            }
        }
        OutlinedButton(
            onClick = { onCommand(BeltCommand.Identify) },
            enabled = identify == null,
            modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH),
        ) {
            Icon(Icons.Filled.Lightbulb, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Identificar (parpadea el LED)")
        }
        (identify ?: restart)?.let { Text(actionBlockText(it), style = MaterialTheme.typography.bodySmall, color = Text2Color) }
    }
}

@Composable
fun PairingSection(bridge: PhoneBridge, linkSupport: LinkSupport, onStartRadar: () -> Unit) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    val guidance = pairingGuidance(linkSupport)
    SectionCard("Emparejar este celular") {
        Text(guidance.text, style = MaterialTheme.typography.bodyMedium, color = Text2Color)
        FilledTonalButton(
            onClick = {
                asking = true
                scope.launch {
                    message = pairingRequestMessage(bridge.requestOpenPairing())
                    asking = false
                }
            },
            enabled = guidance.canAskWatch && !asking,
            modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH),
        ) { Text("Pedir al reloj que abra la ventana") }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        OutlinedButton(onClick = onStartRadar, modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH)) { Text(START_RADAR_LABEL) }
    }
}

@Composable
fun PhoneSettingsSection(settings: AppSettings, prefs: PhonePrefs, actions: PhoneActions) {
    SectionCard("Este celular") {
        SwitchRow("Vibrar en el celular", prefs.vibrate) { actions.updatePrefs { it.copy(vibrate = !it.vibrate) } }
        SwitchRow("Iniciar radar al abrir", settings.autoStartRadar) { actions.updateSettings { it.copy(autoStartRadar = !it.autoStartRadar) } }
        Text(
            "La vibración se aplica al iniciar el radar. El arranque automático requiere el firmware 0.2.0 del cinturón.",
            style = MaterialTheme.typography.bodySmall,
            color = Text2Color,
        )
    }
}
