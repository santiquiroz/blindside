package io.github.santiquiroz.blindside.shared.bridge

const val STATUS_STALE_AFTER_MS = 3 * STATUS_PERIOD_MS

// The watch publishes every 5 s only while its game runs, so three missed periods mean it stopped or walked away.
fun isStatusFresh(status: WatchStatus, nowMs: Long): Boolean = nowMs - status.updatedMs <= STATUS_STALE_AFTER_MS

fun watchSessionActive(status: WatchStatus?, nowMs: Long): Boolean =
    status != null && status.sessionActive && isStatusFresh(status, nowMs)
