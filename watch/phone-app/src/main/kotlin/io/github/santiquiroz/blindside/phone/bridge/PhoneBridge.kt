package io.github.santiquiroz.blindside.phone.bridge

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import io.github.santiquiroz.blindside.phone.recordings.finishDownload
import io.github.santiquiroz.blindside.phone.recordings.partFileName
import io.github.santiquiroz.blindside.shared.bridge.OPEN_PAIRING_PATH
import io.github.santiquiroz.blindside.shared.bridge.OpenPairingReply
import io.github.santiquiroz.blindside.shared.bridge.RECORDINGS_LIST_PATH
import io.github.santiquiroz.blindside.shared.bridge.SETTINGS_PATH
import io.github.santiquiroz.blindside.shared.bridge.STATUS_PATH
import io.github.santiquiroz.blindside.shared.bridge.WatchStatus
import io.github.santiquiroz.blindside.shared.bridge.decodeOpenPairingReply
import io.github.santiquiroz.blindside.shared.bridge.decodeRecordingList
import io.github.santiquiroz.blindside.shared.bridge.encodeSharedSettings
import io.github.santiquiroz.blindside.shared.bridge.recordingChannelPath
import io.github.santiquiroz.blindside.shared.recording.RecordingEntry
import io.github.santiquiroz.blindside.shared.recording.isRecordingFileName
import io.github.santiquiroz.blindside.shared.settings.SettingsRepository
import io.github.santiquiroz.blindside.shared.settings.SharedSettings
import io.github.santiquiroz.blindside.shared.settings.isStamped
import io.github.santiquiroz.blindside.shared.settings.sharedSettingsOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "PhoneBridge"
private const val TIMEOUT_REPLY = "sin respuesta a tiempo"
private const val UNSAFE_NAME = "nombre no válido"
private const val CORRUPT_DOWNLOAD = "archivo dañado"

private data class CopyOutcome(val copiedBytes: Long, val timedOut: Boolean)

class PhoneBridge(context: Context) {
    private val nodes = Wearable.getNodeClient(context)
    private val messages = Wearable.getMessageClient(context)
    private val data = Wearable.getDataClient(context)
    private val channels = Wearable.getChannelClient(context)
    @Volatile private var activeDownload: ChannelClient.Channel? = null
    @Volatile private var cancelRequested = false

    suspend fun listRecordings(): BridgeResult<List<RecordingEntry>> = withWatch { node ->
        BridgeResult.Ok(decodeRecordingList(request(node, RECORDINGS_LIST_PATH).toString(Charsets.UTF_8)))
    }

    // Plan 05 D6: the watch answers this path only through onRequest, so it must be a request, not a fire-and-forget message.
    suspend fun requestOpenPairing(): BridgeResult<OpenPairingReply> = withWatch { node ->
        BridgeResult.Ok(decodeOpenPairingReply(request(node, OPEN_PAIRING_PATH)))
    }

    suspend fun download(entry: RecordingEntry, dir: File, onProgress: (Long) -> Unit): BridgeResult<File> {
        if (!isRecordingFileName(entry.name)) return BridgeResult.Failed(UNSAFE_NAME)
        cancelRequested = false
        return withWatch { node ->
            val channel = withTimeout(BRIDGE_REQUEST_TIMEOUT_MS) { channels.openChannel(node, recordingChannelPath(entry.name)).await() }
            activeDownload = channel
            try {
                receive(channel, entry, dir, onProgress)
            } finally {
                activeDownload = null
                channels.close(channel)
            }
        }
    }

    // Closing the channel is the only way to unblock a ChannelClient read; the copy then ends short and is discarded.
    fun cancelDownload() {
        cancelRequested = true
        activeDownload?.let { channels.close(it) }
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

    private suspend fun receive(channel: ChannelClient.Channel, entry: RecordingEntry, dir: File, onProgress: (Long) -> Unit): BridgeResult<File> {
        if (cancelRequested) return BridgeResult.Failed(verdictReason(DownloadVerdict.CANCELLED))
        dir.mkdirs()
        val part = File(dir, partFileName(entry.name))
        val input = withTimeout(BRIDGE_REQUEST_TIMEOUT_MS) { channels.getInputStream(channel).await() }
        val outcome = copyWithWatchdog(channel, input, part, onProgress)
        val verdict = downloadVerdict(outcome.copiedBytes, entry.bytes, outcome.timedOut, cancelRequested)
        return settle(verdict, part, File(dir, entry.name), entry.bytes)
    }

    private fun settle(verdict: DownloadVerdict, part: File, target: File, expectedBytes: Long): BridgeResult<File> {
        if (verdict != DownloadVerdict.COMPLETE) {
            part.delete()
            return BridgeResult.Failed(verdictReason(verdict))
        }
        return if (finishDownload(part, target, expectedBytes)) BridgeResult.Ok(target) else BridgeResult.Failed(CORRUPT_DOWNLOAD)
    }

    private suspend fun copyWithWatchdog(
        channel: ChannelClient.Channel,
        input: InputStream,
        part: File,
        onProgress: (Long) -> Unit,
    ): CopyOutcome = coroutineScope {
        val lastProgressMs = AtomicLong(SystemClock.elapsedRealtime())
        val timedOut = AtomicBoolean(false)
        val watchdog = launch { closeWhenStalled(channel, lastProgressMs, timedOut) }
        try {
            val copied = withContext(Dispatchers.IO) { copyToPart(input, part, lastProgressMs, onProgress) }
            CopyOutcome(copied, timedOut.get())
        } finally {
            watchdog.cancel()
        }
    }

    // A closed or broken channel either ends the stream early or throws; both leave a short copy that the verdict rejects.
    private fun copyToPart(input: InputStream, part: File, lastProgressMs: AtomicLong, onProgress: (Long) -> Unit): Long {
        var copied = 0L
        val track: (Long) -> Unit = { total ->
            copied = total
            lastProgressMs.set(SystemClock.elapsedRealtime())
            onProgress(total)
        }
        try {
            input.use { source -> part.outputStream().use { copyStream(source, it, track) } }
        } catch (error: IOException) {
            Log.w(TAG, "transfer interrupted after $copied bytes", error)
        }
        return copied
    }

    private suspend fun closeWhenStalled(channel: ChannelClient.Channel, lastProgressMs: AtomicLong, timedOut: AtomicBoolean) {
        while (!stalled(lastProgressMs.get(), SystemClock.elapsedRealtime())) delay(STALL_CHECK_EVERY_MS)
        timedOut.set(true)
        channels.close(channel)
    }

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
