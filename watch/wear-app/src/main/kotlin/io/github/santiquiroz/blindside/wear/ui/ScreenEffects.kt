package io.github.santiquiroz.blindside.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LifecycleStartEffect
import io.github.santiquiroz.blindside.wear.session.SessionStore

@Composable
fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
fun ReportRadarVisibility() {
    LifecycleStartEffect(Unit) {
        SessionStore.setRadarVisible(true)
        onStopOrDispose { SessionStore.setRadarVisible(false) }
    }
}
