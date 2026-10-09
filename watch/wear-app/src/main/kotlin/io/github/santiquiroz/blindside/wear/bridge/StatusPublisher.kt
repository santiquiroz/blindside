package io.github.santiquiroz.blindside.wear.bridge

import android.content.Context
import android.util.Log
import io.github.santiquiroz.blindside.shared.bridge.STATUS_PATH
import io.github.santiquiroz.blindside.shared.bridge.STATUS_PERIOD_MS
import io.github.santiquiroz.blindside.shared.bridge.encodeWatchStatus
import io.github.santiquiroz.blindside.shared.bridge.publishJson
import io.github.santiquiroz.blindside.shared.bridge.watchStatusOf
import io.github.santiquiroz.blindside.shared.session.SessionStore
import io.github.santiquiroz.blindside.shared.session.SessionUiState
import io.github.santiquiroz.blindside.shared.session.stoppedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val TAG = "WatchStatus"
// With no phone connected the node list is asked again every tick, so a phone that comes back gets the very next status.
private const val NO_PHONE_RECHECK_MS = 0L

suspend fun publishWatchStatus(context: Context, clock: () -> Long) {
    val nodes = NodeCache(emptyCacheMs = NO_PHONE_RECHECK_MS)
    try {
        while (currentCoroutineContext().isActive) {
            if (phoneConnected(context, nodes, clock())) publishStatus(context, SessionStore.state.value, clock())
            delay(STATUS_PERIOD_MS)
        }
    } finally {
        // The companion is cancelled before the store resets, so the last word is published as stopped explicitly.
        // It stays unconditional: a phone that reconnects later must sync "stopped", not the last running status.
        publishStatus(context, stoppedState(SessionStore.state.value), clock())
    }
}

// An urgent DataItem with no phone to receive it only wakes the data layer; a failed node query keeps publishing as before.
private suspend fun phoneConnected(context: Context, nodes: NodeCache, nowMs: Long): Boolean =
    try {
        nodes.ids(context, nowMs).isNotEmpty()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.w(TAG, "connected nodes query failed", error)
        true
    }

private fun publishStatus(context: Context, session: SessionUiState, nowMs: Long) {
    publishJson(context, STATUS_PATH, encodeWatchStatus(watchStatusOf(session, nowMs)))
}
