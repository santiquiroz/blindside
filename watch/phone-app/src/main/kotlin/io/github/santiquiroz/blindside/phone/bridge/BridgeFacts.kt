package io.github.santiquiroz.blindside.phone.bridge

import io.github.santiquiroz.blindside.shared.bridge.WatchStatus
import io.github.santiquiroz.blindside.shared.bridge.decodeSharedSettings
import io.github.santiquiroz.blindside.shared.bridge.decodeWatchStatus
import io.github.santiquiroz.blindside.shared.settings.SharedSettings

data class NodeFacts(val id: String, val nearby: Boolean)

sealed interface BridgeResult<out T> {
    data class Ok<out T>(val value: T) : BridgeResult<T>
    data object NoWatch : BridgeResult<Nothing>
    data class Failed(val reason: String) : BridgeResult<Nothing>
}

data class BridgeChange(val path: String, val json: String)

const val BRIDGE_REQUEST_TIMEOUT_MS = 10_000L

// The watch is the node close by; a cloud-relayed node is only a fallback.
fun pickWatchNode(nodes: List<NodeFacts>): String? = (nodes.firstOrNull { it.nearby } ?: nodes.firstOrNull())?.id

// Plan 05 Task 13 publishes /settings and /status as raw UTF-8 JSON (PutDataRequest.setData), never as a key-value map item.
fun jsonFromItemBytes(bytes: ByteArray?): String? = bytes?.takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8)

// The phone and the watch each own an item at the same path; the newest valid one wins and garbage is skipped.
fun newestSettings(jsons: List<String>): SharedSettings? = jsons.mapNotNull(::decodeSharedSettings).maxByOrNull { it.updatedMs }

fun newestStatus(jsons: List<String>): WatchStatus? = jsons.mapNotNull(::decodeWatchStatus).maxByOrNull { it.updatedMs }
