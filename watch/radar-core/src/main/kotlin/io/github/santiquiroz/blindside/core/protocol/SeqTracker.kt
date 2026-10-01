package io.github.santiquiroz.blindside.core.protocol

data class SeqTracker(
    val lastSeq: Int? = null,
    val lastTMs: Long? = null,
    val lostPackets: Long = 0,
    val resets: Int = 0,
) {
    fun observe(seq: Int, tMs: Long): SeqTracker = when {
        lastSeq == null || lastTMs == null -> copy(lastSeq = seq, lastTMs = tMs)
        tMs < lastTMs -> copy(lastSeq = seq, lastTMs = tMs, resets = resets + 1)
        else -> copy(lastSeq = seq, lastTMs = tMs, lostPackets = lostPackets + lostBetween(lastSeq, seq))
    }

    fun isBackwards(tMs: Long): Boolean = lastTMs != null && tMs < lastTMs
}

fun lostBetween(previousSeq: Int, nextSeq: Int): Int =
    (((nextSeq - previousSeq) and 0xFFFF) - 1).coerceAtLeast(0)
