package io.github.santiquiroz.blindside.core.clock

import io.github.santiquiroz.blindside.core.config.ClockParams
import kotlin.math.roundToLong

data class OffsetPoint(val tMs: Long, val offsetNanos: Long)

data class ClockMapper(
    val params: ClockParams = ClockParams(),
    val completed: List<OffsetPoint> = emptyList(),
    val current: OffsetPoint? = null,
    val currentWindow: Long? = null,
) {
    val isReady: Boolean get() = current != null

    fun observe(tMs: Long, arrivalNanos: Long): ClockMapper {
        val point = OffsetPoint(tMs, arrivalNanos - tMs * NANOS_PER_MS)
        val window = tMs / params.windowMs
        return when {
            current == null || currentWindow == null -> copy(current = point, currentWindow = window)
            window == currentWindow -> copy(current = minOf(current, point))
            window > currentWindow -> copy(completed = (completed + current).takeLast(params.maxWindows), current = point, currentWindow = window)
            else -> this
        }
    }

    fun toNanos(tMs: Long): Long? {
        val anchor = anchor() ?: return null
        return tMs * NANOS_PER_MS + anchor.offsetNanos + (driftNanosPerMs() * (tMs - anchor.tMs)).roundToLong()
    }

    fun toEspMs(nanos: Long): Long? {
        val anchor = anchor() ?: return null
        val drift = driftNanosPerMs()
        return ((nanos - anchor.offsetNanos + drift * anchor.tMs) / (NANOS_PER_MS + drift)).toLong()
    }

    fun driftNanosPerMs(): Double {
        if (completed.size < 2) return 0.0
        val first = completed.first()
        val last = completed.last()
        val slope = (last.offsetNanos - first.offsetNanos).toDouble() / (last.tMs - first.tMs)
        return slope.coerceIn(-params.maxDriftPpm, params.maxDriftPpm)
    }

    private fun anchor(): OffsetPoint? {
        val now = current ?: return null
        val drift = driftNanosPerMs()
        return listOfNotNull(completed.lastOrNull(), now).minBy { it.offsetNanos + drift * (now.tMs - it.tMs) }
    }

    private fun minOf(a: OffsetPoint, b: OffsetPoint) = if (b.offsetNanos < a.offsetNanos) b else a

    companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
