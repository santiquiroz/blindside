package io.github.santiquiroz.blindside.wear.bridge

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val NODE_CACHE_MS = 30_000L

// The phone set barely changes, so the node list is cached; Tasks.await runs on IO because wear-app has no coroutines-play-services.
// emptyCacheMs lets a caller re-ask sooner while no phone is connected, so a phone that comes back is seen on the next tick.
internal class NodeCache(private val emptyCacheMs: Long = NODE_CACHE_MS) {
    private var ids: List<String> = emptyList()
    private var atMs: Long? = null

    suspend fun ids(context: Context, nowMs: Long): List<String> {
        if (nodeListStale(atMs, nowMs, if (ids.isEmpty()) emptyCacheMs else NODE_CACHE_MS)) {
            ids = connectedIds(context)
            atMs = nowMs
        }
        return ids
    }
}

internal fun nodeListStale(cachedAtMs: Long?, nowMs: Long, ttlMs: Long): Boolean =
    cachedAtMs == null || nowMs - cachedAtMs >= ttlMs

private suspend fun connectedIds(context: Context): List<String> =
    withContext(Dispatchers.IO) { Tasks.await(Wearable.getNodeClient(context).connectedNodes) }.map { it.id }
