package io.github.santiquiroz.blindside.wear.bridge

import android.content.Context
import io.github.santiquiroz.blindside.shared.bridge.STATUS_PATH
import io.github.santiquiroz.blindside.shared.bridge.STATUS_PERIOD_MS
import io.github.santiquiroz.blindside.shared.bridge.encodeWatchStatus
import io.github.santiquiroz.blindside.shared.bridge.publishJson
import io.github.santiquiroz.blindside.shared.bridge.watchStatusOf
import io.github.santiquiroz.blindside.shared.session.SessionStore
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.session.stoppedState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

suspend fun publishWatchStatus(context: Context, clock: () -> Long) {
    try {
        while (currentCoroutineContext().isActive) {
            publishStatus(context, SessionStore.state.value, clock())
            delay(STATUS_PERIOD_MS)
        }
    } finally {
        // The companion is cancelled before the store resets, so the last word is published as stopped explicitly.
        publishStatus(context, stoppedState(SessionStore.state.value), clock())
    }
}

private fun publishStatus(context: Context, session: SessionUiState, nowMs: Long) {
    publishJson(context, STATUS_PATH, encodeWatchStatus(watchStatusOf(session, nowMs)))
}
