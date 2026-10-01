package io.github.santiquiroz.blindside.shared.ble

data class ScanHistory(val startsMs: List<Long> = emptyList())

data class ScanPermit(val allowed: Boolean, val history: ScanHistory)

// Android silently drops the 5th startScan in 30 s; we stay one below that.
const val MAX_SCAN_STARTS = 4
const val SCAN_WINDOW_MS = 30_000L

fun tryStartScan(history: ScanHistory, nowMs: Long): ScanPermit {
    val recent = history.startsMs.filter { nowMs - it < SCAN_WINDOW_MS }
    val allowed = recent.size < MAX_SCAN_STARTS
    return ScanPermit(allowed, ScanHistory(if (allowed) recent + nowMs else recent))
}
