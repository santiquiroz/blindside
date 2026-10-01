package io.github.santiquiroz.blindside.wear.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.santiquiroz.blindside.shared.permissions.SESSION_PERMISSIONS
import io.github.santiquiroz.blindside.shared.permissions.bluetoothGranted
import io.github.santiquiroz.blindside.wear.session.SessionCommands
import io.github.santiquiroz.blindside.wear.session.SessionSource
import io.github.santiquiroz.blindside.wear.session.SessionStore
import io.github.santiquiroz.blindside.wear.settings.SettingsRepository

// Runs once per opening: a stopped game must not restart itself when the wrist comes back up.
@Composable
fun LaunchRadarOnOpen(settingsRepository: SettingsRepository, onShowRadar: () -> Unit) {
    val context = LocalContext.current
    var handled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (handled) return@LaunchedEffect
        val settings = settingsRepository.current()
        val action = launchAction(settings, SessionStore.state.value.running, bluetoothGranted(sessionGrants(context)))
        handled = true
        performLaunch(action, context, onShowRadar)
    }
}

fun startRadar(context: Context, source: SessionSource, onShowRadar: () -> Unit) {
    SessionCommands.start(context, source)
    onShowRadar()
}

fun sessionGrants(context: Context): Map<String, Boolean> =
    SESSION_PERMISSIONS.associateWith { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

private fun performLaunch(action: LaunchAction, context: Context, onShowRadar: () -> Unit) {
    when (action) {
        LaunchAction.START_RADAR -> startRadar(context, SessionSource.BELT, onShowRadar)
        LaunchAction.SHOW_RADAR -> onShowRadar()
        LaunchAction.SHOW_HOME -> Unit
    }
}
