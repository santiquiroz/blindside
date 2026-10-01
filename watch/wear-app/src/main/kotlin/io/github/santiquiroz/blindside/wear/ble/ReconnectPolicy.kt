package io.github.santiquiroz.blindside.wear.ble

data class ReconnectState(val lostAtMs: Long, val lastDirectAtMs: Long? = null, val lastScanAtMs: Long? = null)

enum class ReconnectAction { DIRECT_CONNECT, SCAN }

const val DIRECT_ATTEMPT_AFTER_MS = 10_000L
const val DIRECT_ATTEMPT_EVERY_MS = 20_000L
const val SCAN_AFTER_MS = 30_000L
const val SCAN_EVERY_MS = 30_000L

fun nextReconnectAction(state: ReconnectState, nowMs: Long): ReconnectAction? = when {
    isDue(state.lostAtMs, state.lastScanAtMs, nowMs, SCAN_AFTER_MS, SCAN_EVERY_MS) -> ReconnectAction.SCAN
    isDue(state.lostAtMs, state.lastDirectAtMs, nowMs, DIRECT_ATTEMPT_AFTER_MS, DIRECT_ATTEMPT_EVERY_MS) ->
        ReconnectAction.DIRECT_CONNECT
    else -> null
}

fun recordAction(state: ReconnectState, action: ReconnectAction, nowMs: Long): ReconnectState = when (action) {
    ReconnectAction.DIRECT_CONNECT -> state.copy(lastDirectAtMs = nowMs)
    ReconnectAction.SCAN -> state.copy(lastScanAtMs = nowMs)
}

private fun isDue(lostAtMs: Long, lastAtMs: Long?, nowMs: Long, afterMs: Long, everyMs: Long): Boolean =
    nowMs - lostAtMs >= afterMs && (lastAtMs == null || nowMs - lastAtMs >= everyMs)
