package io.github.santiquiroz.blindside.phone.bridge

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import io.github.santiquiroz.blindside.shared.bridge.SETTINGS_PATH
import io.github.santiquiroz.blindside.shared.bridge.STATUS_PATH
import io.github.santiquiroz.blindside.shared.bridge.decodeSharedSettings
import io.github.santiquiroz.blindside.shared.bridge.decodeWatchStatus
import io.github.santiquiroz.blindside.shared.bridge.jsonIn
import io.github.santiquiroz.blindside.shared.settings.adoptingNewer
import io.github.santiquiroz.blindside.shared.settings.settingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

private const val TAG = "PhoneBridgeListener"

class PhoneBridgeListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        // The event buffer dies when this returns, so the changes are copied out first; the DataStore write is short.
        val changes = events.mapNotNull(::changeOf)
        runBlocking { changes.forEach { applySafely(it) } }
    }

    private suspend fun applySafely(change: BridgeChange) {
        try {
            apply(change)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "bridge change at ${change.path} ignored", error)
        }
    }

    private suspend fun apply(change: BridgeChange) {
        when (change.path) {
            SETTINGS_PATH -> decodeSharedSettings(change.json)?.let { remote -> settingsRepository().update { it.adoptingNewer(remote) } }
            STATUS_PATH -> decodeWatchStatus(change.json)?.let(WatchStatusStore::offer)
        }
    }
}

private fun changeOf(event: DataEvent): BridgeChange? =
    runCatching { readChange(event) }.onFailure { Log.w(TAG, "unreadable data event", it) }.getOrNull()

private fun readChange(event: DataEvent): BridgeChange? {
    if (event.type != DataEvent.TYPE_CHANGED) return null
    val path = event.dataItem.uri.path ?: return null
    return jsonIn(event.dataItem)?.let { BridgeChange(path, it) }
}
