package io.github.santiquiroz.blindside.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.santiquiroz.blindside.phone.PhoneDeps
import io.github.santiquiroz.blindside.phone.bridge.WatchStatusStore
import io.github.santiquiroz.blindside.phone.nav.PhoneLaunch
import io.github.santiquiroz.blindside.phone.nav.PhoneNav
import io.github.santiquiroz.blindside.phone.nav.PhoneTab
import io.github.santiquiroz.blindside.phone.nav.afterDelete
import io.github.santiquiroz.blindside.phone.nav.backFrom
import io.github.santiquiroz.blindside.phone.nav.navFromStrings
import io.github.santiquiroz.blindside.phone.nav.navToStrings
import io.github.santiquiroz.blindside.phone.nav.openRecording
import io.github.santiquiroz.blindside.phone.nav.phoneLaunch
import io.github.santiquiroz.blindside.phone.nav.sceneWanted
import io.github.santiquiroz.blindside.phone.nav.selectTab
import io.github.santiquiroz.blindside.phone.session.PhoneStore
import io.github.santiquiroz.blindside.phone.settings.PhonePrefs
import io.github.santiquiroz.blindside.phone.settings.linkSupportAfterInfo
import io.github.santiquiroz.blindside.phone.ui.belt.BeltTab
import io.github.santiquiroz.blindside.phone.ui.common.ReportSceneVisibility
import io.github.santiquiroz.blindside.phone.ui.radar.RadarTab
import io.github.santiquiroz.blindside.phone.ui.recordings.RecordingsTab
import io.github.santiquiroz.blindside.phone.ui.theme.rememberMotionDurationMs
import io.github.santiquiroz.blindside.phone.ui.viewer.ViewerTab
import io.github.santiquiroz.blindside.shared.permissions.bluetoothGranted
import io.github.santiquiroz.blindside.shared.session.SessionStore
import io.github.santiquiroz.blindside.shared.session.activeRecordingName
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import io.github.santiquiroz.blindside.shared.settings.adoptingNewer

private const val TAB_FADE_MS = 200
private val PHONE_NAV_SAVER = listSaver<PhoneNav, String>(save = { navToStrings(it) }, restore = { navFromStrings(it) })

@Composable
fun PhoneApp(deps: PhoneDeps) {
    var bluetoothBlocked by remember { mutableStateOf(false) }
    val state = collectPhoneUiState(deps, bluetoothBlocked)
    val actions = rememberPhoneActions(deps, onBluetoothBlocked = { bluetoothBlocked = it })
    var nav by rememberSaveable(stateSaver = PHONE_NAV_SAVER) { mutableStateOf(PhoneNav()) }
    ReportSceneVisibility(sceneWanted(nav.tab))
    RememberLinkSupport(deps, state.phone.infoJson)
    BridgeWhileOpen(deps)
    AutoStartOnOpen(deps, actions.startRadar)
    BackHandler(enabled = backFrom(nav) != null) { backFrom(nav)?.let { nav = it } }
    Scaffold(bottomBar = { PhoneNavigationBar(nav.tab) { nav = selectTab(nav, it) } }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            TabContent(nav, state, actions, deps) { nav = it }
        }
    }
}

@Composable
private fun collectPhoneUiState(deps: PhoneDeps, bluetoothBlocked: Boolean): PhoneUiState {
    val session by SessionStore.state.collectAsStateWithLifecycle()
    val phone by PhoneStore.state.collectAsStateWithLifecycle()
    val settings by deps.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val prefs by deps.prefs.prefs.collectAsStateWithLifecycle(initialValue = PhonePrefs())
    val watchStatus by WatchStatusStore.state.collectAsStateWithLifecycle()
    return PhoneUiState(session, phone, settings, prefs, watchStatus, bluetoothBlocked)
}

@Composable
private fun TabContent(nav: PhoneNav, state: PhoneUiState, actions: PhoneActions, deps: PhoneDeps, onNav: (PhoneNav) -> Unit) {
    val fadeMs = rememberMotionDurationMs(TAB_FADE_MS)
    Crossfade(targetState = nav.tab, animationSpec = tween(fadeMs), label = "tab") { tab ->
        when (tab) {
            PhoneTab.RADAR -> RadarTab(state, actions)
            PhoneTab.RECORDINGS -> RecordingsTab(
                repository = deps.recordings,
                bridge = deps.bridge,
                activeName = activeRecordingName(state.session),
                onOpen = { onNav(openRecording(nav, it)) },
                onDeleted = { onNav(afterDelete(nav, it)) },
            )
            PhoneTab.VIEWER -> ViewerTab(nav.viewing, deps.recordings, onPickRecording = { onNav(selectTab(nav, PhoneTab.RECORDINGS)) })
            PhoneTab.BELT -> BeltTab(state, actions, deps.bridge)
        }
    }
}

@Composable
private fun RememberLinkSupport(deps: PhoneDeps, infoJson: String?) {
    LaunchedEffect(infoJson) {
        if (infoJson != null) deps.prefs.update { it.copy(linkSupport = linkSupportAfterInfo(it.linkSupport, infoJson)) }
    }
}

// The watch may have changed the shared settings while the phone was closed; its status may already be waiting too.
@Composable
private fun BridgeWhileOpen(deps: PhoneDeps) {
    LaunchedEffect(deps) {
        deps.bridge.latestSharedSettings()?.let { remote -> deps.settings.update { it.adoptingNewer(remote) } }
        deps.bridge.latestStatus()?.let(WatchStatusStore::offer)
    }
    LaunchedEffect(deps) { deps.bridge.publishSharedSettings(deps.settings) }
}

// Runs once per opening: a radar the user stopped must not restart itself when the app comes back.
@Composable
private fun AutoStartOnOpen(deps: PhoneDeps, startRadar: () -> Unit) {
    val context = LocalContext.current
    var handled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (handled) return@LaunchedEffect
        handled = true
        val launch = phoneLaunch(
            deps.settings.current(),
            SessionStore.state.value.running,
            bluetoothGranted(grantsOf(context)),
            deps.prefs.current().linkSupport,
        )
        if (launch == PhoneLaunch.AUTO_START) startRadar()
    }
}
