package io.github.santiquiroz.blindside.phone.ui.belt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.santiquiroz.blindside.phone.bridge.PhoneBridge
import io.github.santiquiroz.blindside.phone.ui.PhoneActions
import io.github.santiquiroz.blindside.phone.ui.PhoneUiState
import io.github.santiquiroz.blindside.phone.ui.common.rememberNowMs
import io.github.santiquiroz.blindside.shared.bridge.watchSessionActive

@Composable
fun BeltTab(state: PhoneUiState, actions: PhoneActions, bridge: PhoneBridge) {
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
    }
}
