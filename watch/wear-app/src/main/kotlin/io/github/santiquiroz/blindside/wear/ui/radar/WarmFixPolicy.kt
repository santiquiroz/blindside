package io.github.santiquiroz.blindside.wear.ui.radar

private const val FRESH_FIX_MS = 20_000L
private const val FAILED_WARM_BACKOFF_MS = 90_000L

// A fresh fix (or a fresh phone fix) makes the warm-up pointless, and right after a timeout under cover a new one would only search to the timeout again.
fun shouldWarmFix(lastFixAgeMs: Long?, lastFailedAtMs: Long?, nowMs: Long, phoneFixFresh: Boolean = false): Boolean {
    if (phoneFixFresh) return false
    if (lastFixAgeMs != null && lastFixAgeMs < FRESH_FIX_MS) return false
    return lastFailedAtMs == null || nowMs - lastFailedAtMs >= FAILED_WARM_BACKOFF_MS
}
