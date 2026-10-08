package io.github.santiquiroz.blindside.phone.ui.belt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.santiquiroz.blindside.phone.BuildConfig
import io.github.santiquiroz.blindside.phone.bridge.PhoneBridge
import io.github.santiquiroz.blindside.phone.ui.PhoneActions
import io.github.santiquiroz.blindside.phone.ui.PhoneUiState
import io.github.santiquiroz.blindside.phone.ui.common.MIN_TOUCH
import io.github.santiquiroz.blindside.phone.ui.common.SectionCard
import io.github.santiquiroz.blindside.phone.ui.common.rememberNowMs
import io.github.santiquiroz.blindside.phone.ui.theme.Text2Color
import io.github.santiquiroz.blindside.phone.update.UpdateChecker
import io.github.santiquiroz.blindside.phone.update.UpdateState
import io.github.santiquiroz.blindside.phone.update.UpdateStore
import io.github.santiquiroz.blindside.shared.bridge.watchSessionActive
import kotlinx.coroutines.launch

@Composable
private fun VersionSection(updateChecker: UpdateChecker) {
    val scope = rememberCoroutineScope()
    val updateState by UpdateStore.state.collectAsStateWithLifecycle()
    SectionCard("Versión") {
        Text("Blindside ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(
            onClick = { scope.launch { updateChecker.check(force = true) } },
            modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH),
        ) { Text("Buscar actualizaciones") }
        updateStatusLine(updateState)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Text2Color) }
    }
}

private fun updateStatusLine(state: UpdateState): String? = when (state) {
    UpdateState.Checking -> "Buscando…"
    UpdateState.UpToDate -> "Ya tienes la última versión"
    is UpdateState.Available -> "Hay una versión nueva: ${state.info.version}"
    is UpdateState.Failed -> if (state.info == null) state.reason else null
    else -> null
}

@Composable
fun BeltTab(state: PhoneUiState, actions: PhoneActions, bridge: PhoneBridge, updateChecker: UpdateChecker) {
    val nowMs by rememberNowMs()
    val linkUp = phoneLinkUp(state.session)
    val identify = identifyBlock(linkUp, state.session.purpose, watchSessionActive(state.watchStatus, nowMs))
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Cinturón", style = MaterialTheme.typography.headlineSmall)
        LinkSection(state, nowMs, actions)
        DiagnosticSection(state.phone, state.session.running, linkUp, actions.refreshInfo, actions.startDiagnostic)
        ActionsSection(identify, restartBlock(linkUp), actions.sendCommand)
        PairingSection(bridge, state.prefs.linkSupport, actions.startRadar)
        SharedSettingsSection(state.settings, actions.updateSettings)
        PhoneSettingsSection(state.settings, state.prefs, actions)
        VersionSection(updateChecker)
    }
}
