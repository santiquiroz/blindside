package io.github.santiquiroz.blindside.phone.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LifecycleStartEffect
import io.github.santiquiroz.blindside.shared.session.SessionStore
import kotlinx.coroutines.delay

private const val NOW_TICK_MS = 1_000L

@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

// A changed key first disposes the old effect (false) and then starts the new one, so the last word is always the current tab's.
@Composable
fun ReportSceneVisibility(wanted: Boolean) {
    LifecycleStartEffect(wanted) {
        SessionStore.setRadarVisible(wanted)
        onStopOrDispose { SessionStore.setRadarVisible(false) }
    }
}

@Composable
fun rememberNowMs(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(NOW_TICK_MS)
        value = System.currentTimeMillis()
    }
}
