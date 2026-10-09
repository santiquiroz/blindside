package io.github.santiquiroz.blindside.wear.bridge

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import io.github.santiquiroz.blindside.shared.session.SessionStore
import io.github.santiquiroz.blindside.shared.tak.TAK_TELEMETRY_PATH
import io.github.santiquiroz.blindside.shared.tak.TAK_TELEMETRY_PERIOD_MS
import io.github.santiquiroz.blindside.shared.tak.bodyHeadingDeg
import io.github.santiquiroz.blindside.shared.tak.encodeTelemetry
import io.github.santiquiroz.blindside.shared.tak.likelyAllyIdsOf
import io.github.santiquiroz.blindside.shared.tak.teamLinkActive
import io.github.santiquiroz.blindside.shared.tak.telemetryOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val TAG = "TakTelemetry"

suspend fun publishTakTelemetry(context: Context, clockMs: () -> Long, clockNanos: () -> Long) {
    val nodes = NodeCache()
    while (currentCoroutineContext().isActive) {
        try {
            publishOnce(context, nodes, clockMs(), clockNanos())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "publish telemetry failed", error)
        }
        delay(TAK_TELEMETRY_PERIOD_MS)
    }
}

private suspend fun publishOnce(context: Context, nodes: NodeCache, nowMs: Long, nowNanos: Long) {
    val session = SessionStore.state.value
    if (!teamLinkActive(session.teamAtMs, nowMs)) return
    val heading = session.scene?.let { bodyHeadingDeg(session.headingAnchor, it.bodyYawDeg, it.yawFromBelt, nowNanos) }
    val allies = likelyAllyIdsOf(session, nowMs, nowNanos)
    val bytes = encodeTelemetry(telemetryOf(session.scene, heading, session.tacticalPoints, excludeIds = allies)).toByteArray(Charsets.UTF_8)
    nodes.ids(context, nowMs).forEach { sendTo(context, it, bytes) }
}

private suspend fun sendTo(context: Context, nodeId: String, bytes: ByteArray) {
    withContext(Dispatchers.IO) { Tasks.await(Wearable.getMessageClient(context).sendMessage(nodeId, TAK_TELEMETRY_PATH, bytes)) }
}
