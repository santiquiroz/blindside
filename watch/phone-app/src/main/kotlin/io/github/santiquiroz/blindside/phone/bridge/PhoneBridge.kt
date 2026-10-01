package io.github.santiquiroz.blindside.phone.bridge

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import io.github.santiquiroz.blindside.shared.bridge.OPEN_PAIRING_PATH
import io.github.santiquiroz.blindside.shared.bridge.OpenPairingReply
import io.github.santiquiroz.blindside.shared.bridge.RECORDINGS_LIST_PATH
import io.github.santiquiroz.blindside.shared.bridge.SETTINGS_PATH
import io.github.santiquiroz.blindside.shared.bridge.STATUS_PATH
import io.github.santiquiroz.blindside.shared.bridge.WatchStatus
import io.github.santiquiroz.blindside.shared.bridge.decodeOpenPairingReply
import io.github.santiquiroz.blindside.shared.bridge.decodeRecordingList
import io.github.santiquiroz.blindside.shared.bridge.encodeSharedSettings
import io.github.santiquiroz.blindside.shared.recording.RecordingEntry
import io.github.santiquiroz.blindside.shared.settings.SettingsRepository
import io.github.santiquiroz.blindside.shared.settings.SharedSettings
import io.github.santiquiroz.blindside.shared.settings.isStamped
import io.github.santiquiroz.blindside.shared.settings.sharedSettingsOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

private const val TAG = "PhoneBridge"
private const val TIMEOUT_REPLY = "sin respuesta a tiempo"

class PhoneBridge(context: Context) {
    private val nodes = Wearable.getNodeClient(context)
    private val messages = Wearable.getMessageClient(context)
    private val data = Wearable.getDataClient(context)

    suspend fun listRecordings(): BridgeResult<List<RecordingEntry>> = withWatch { node ->
        BridgeResult.Ok(decodeRecordingList(request(node, RECORDINGS_LIST_PATH).toString(Charsets.UTF_8)))
    }

    // Plan 05 D6: the watch answers this path only through onRequest, so it must be a request, not a fire-and-forget message.
    suspend fun requestOpenPairing(): BridgeResult<OpenPairingReply> = withWatch { node ->
        BridgeResult.Ok(decodeOpenPairingReply(request(node, OPEN_PAIRING_PATH)))
    }

    // Same rule as the watch's publisher (plan 05 Task 13): every stamped change of the shared part goes out; an adopted remote goes out as its own echo.
    suspend fun publishSharedSettings(repository: SettingsRepository) {
        repository.settings
            .map(::sharedSettingsOf)
            .distinctUntilChanged()
            .filter(::isStamped)
            .collect { publish(SETTINGS_PATH, encodeSharedSettings(it)) }
    }

    suspend fun latestSharedSettings(): SharedSettings? = newestSettings(itemJsons(SETTINGS_PATH))

    suspend fun latestStatus(): WatchStatus? = newestStatus(itemJsons(STATUS_PATH))

    private suspend fun request(node: String, path: String): ByteArray =
        withTimeout(BRIDGE_REQUEST_TIMEOUT_MS) { messages.sendRequest(node, path, ByteArray(0)).await() }

    private suspend fun publish(path: String, json: String) {
        try {
            data.putDataItem(PutDataRequest.create(path).setData(json.toByteArray(Charsets.UTF_8)).setUrgent()).await()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "publish $path failed", error)
        }
    }

    private suspend fun itemJsons(path: String): List<String> = try {
        val buffer = data.dataItems.await()
        try {
            buffer.filter { it.uri.path == path }.mapNotNull { jsonFromItemBytes(it.data) }
        } finally {
            buffer.release()
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.w(TAG, "data items unavailable", error)
        emptyList()
    }

    private suspend fun <T> withWatch(block: suspend (String) -> BridgeResult<T>): BridgeResult<T> = try {
        pickWatchNode(connectedNodes())?.let { block(it) } ?: BridgeResult.NoWatch
    } catch (error: TimeoutCancellationException) {
        BridgeResult.Failed(TIMEOUT_REPLY)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.w(TAG, "bridge call failed", error)
        BridgeResult.Failed(error.message ?: error.javaClass.simpleName)
    }

    private suspend fun connectedNodes(): List<NodeFacts> =
        nodes.connectedNodes.await().map { NodeFacts(it.id, it.isNearby) }
}
