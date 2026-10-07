package io.github.santiquiroz.blindside.wear.bridge

import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import io.github.santiquiroz.blindside.shared.bridge.OPEN_PAIRING_PATH
import io.github.santiquiroz.blindside.shared.bridge.OpenPairingReply
import io.github.santiquiroz.blindside.shared.bridge.RECORDINGS_LIST_PATH
import io.github.santiquiroz.blindside.shared.bridge.RECORDING_REFUSED_CODE
import io.github.santiquiroz.blindside.shared.bridge.SETTINGS_PATH
import io.github.santiquiroz.blindside.shared.bridge.decodeSharedSettings
import io.github.santiquiroz.blindside.shared.bridge.encodeOpenPairingReply
import io.github.santiquiroz.blindside.shared.bridge.encodeRecordingList
import io.github.santiquiroz.blindside.shared.bridge.jsonIn
import io.github.santiquiroz.blindside.shared.bridge.openPairingReplyFor
import io.github.santiquiroz.blindside.shared.recording.recordingsDir
import io.github.santiquiroz.blindside.shared.recording.shareableRecordings
import io.github.santiquiroz.blindside.shared.session.SessionStore
import io.github.santiquiroz.blindside.shared.session.activeRecordingName
import io.github.santiquiroz.blindside.shared.tak.TAK_TEAM_PATH
import io.github.santiquiroz.blindside.shared.tak.decodeTeamUpdate
import io.github.santiquiroz.blindside.shared.settings.SharedSettings
import io.github.santiquiroz.blindside.shared.settings.adoptingNewer
import io.github.santiquiroz.blindside.shared.settings.settingsRepository
import io.github.santiquiroz.blindside.wear.session.WearSessionCommands
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.ZoneId

private const val TAG = "BridgeListener"

class BridgeListenerService : WearableListenerService() {
    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray> = when (path) {
        RECORDINGS_LIST_PATH -> Tasks.forResult(recordingListReply())
        OPEN_PAIRING_PATH -> Tasks.forResult(encodeOpenPairingReply(askBeltForPairing()))
        else -> Tasks.forResult(ByteArray(0))
    }

    // Spec §5 names this path a message, so a phone that sends it without waiting for a reply gets the same action (Deviation D6).
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == OPEN_PAIRING_PATH) askBeltForPairing()
        if (event.path == TAK_TEAM_PATH) receiveTeam(event.data)
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val file = servedRecording(recordingsDir(this), channel.path, activeRecordingName(SessionStore.state.value))
        if (file == null) refuse(channel) else send(channel, file)
    }

    // Listener callbacks run on a background thread, and the event buffer dies when this returns.
    override fun onDataChanged(events: DataEventBuffer) {
        events.mapNotNull(::sharedSettingsIn).lastOrNull()?.let(::adopt)
    }

    private fun recordingListReply(): ByteArray {
        val files = recordingFilesIn(recordingsDir(this))
        val entries = shareableRecordings(files, activeRecordingName(SessionStore.state.value), ZoneId.systemDefault())
        return encodeRecordingList(entries).toByteArray(Charsets.UTF_8)
    }

    private fun askBeltForPairing(): OpenPairingReply {
        val reply = openPairingReplyFor(SessionStore.state.value)
        if (reply == OpenPairingReply.REQUESTED) WearSessionCommands.openPairing(this)
        return reply
    }

    private fun receiveTeam(data: ByteArray) {
        decodeTeamUpdate(data.toString(Charsets.UTF_8))?.let { SessionStore.receiveTeam(it, System.currentTimeMillis()) }
    }

    // No bytes plus a non-zero code: the phone must never keep a refusal as an empty recording (Cross-plan contract item 4).
    private fun refuse(channel: ChannelClient.Channel) {
        Wearable.getChannelClient(this).close(channel, RECORDING_REFUSED_CODE)
            .addOnFailureListener { Log.w(TAG, "closing a refused channel failed", it) }
    }

    private fun send(channel: ChannelClient.Channel, file: File) {
        Wearable.getChannelClient(this).sendFile(channel, Uri.fromFile(file))
            .addOnFailureListener { Log.w(TAG, "sending ${file.name} failed", it) }
    }

    private fun adopt(remote: SharedSettings) {
        runBlocking { settingsRepository().update { it.adoptingNewer(remote) } }
    }
}

private fun sharedSettingsIn(event: DataEvent): SharedSettings? {
    if (!isSettingsChange(event)) return null
    return jsonIn(event.dataItem)?.let(::decodeSharedSettings)
}

private fun isSettingsChange(event: DataEvent): Boolean =
    event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == SETTINGS_PATH
