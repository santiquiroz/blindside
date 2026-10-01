package io.github.santiquiroz.blindside.phone.viewer

import io.github.santiquiroz.blindside.core.protocol.i32le
import io.github.santiquiroz.blindside.core.replay.BsrecPayloads
import io.github.santiquiroz.blindside.core.replay.BsrecRecord
import io.github.santiquiroz.blindside.core.replay.RecordType
import io.github.santiquiroz.blindside.core.scene.MotionState
import kotlin.math.ceil

data class SummaryAccumulator(
    val lastTMs: Long = 0L,
    val confirmed: Int = 0,
    val alerts: Int = 0,
    val linkSeenUp: Boolean = false,
    val linkDownSinceMs: Long? = null,
    val linkGaps: Int = 0,
    val linkGapMs: Long = 0L,
    val pendingConfirms: Map<Int, Long> = emptyMap(),
    val latenciesMs: List<Long> = emptyList(),
    val motionCounts: Map<MotionState, Int> = emptyMap(),
)

data class RecordingSummary(
    val durationMs: Long,
    val confirmed: Int,
    val confirmedPerMinute: Double,
    val alerts: Int,
    val linkGaps: Int,
    val linkGapMs: Long,
    val walkingFraction: Double,
    val stillFraction: Double,
    val motionSamples: Int,
    val latencyMedianMs: Long?,
    val latencyP90Ms: Long?,
)

private const val MS_PER_MINUTE = 60_000.0

fun afterRecord(acc: SummaryAccumulator, record: BsrecRecord): SummaryAccumulator {
    val timed = acc.copy(lastTMs = maxOf(acc.lastTMs, record.tMsSinceStart))
    return when (record.type) {
        RecordType.TRACK_CONFIRMED -> afterConfirm(timed, BsrecPayloads.readTrackConfirmed(record.payload), record.tMsSinceStart)
        RecordType.VIBRATION_STARTED -> afterVibration(timed, record.payload.i32le(0), record.tMsSinceStart)
        RecordType.MODE_CHANGE -> afterLinkChange(timed, BsrecPayloads.readLinkChange(record.payload), record.tMsSinceStart)
        else -> timed
    }
}

fun afterMotion(acc: SummaryAccumulator, motion: MotionState): SummaryAccumulator =
    acc.copy(motionCounts = acc.motionCounts + (motion to (acc.motionCounts[motion] ?: 0) + 1))

fun summaryOf(acc: SummaryAccumulator): RecordingSummary {
    val samples = acc.motionCounts.values.sum()
    val latencies = acc.latenciesMs.sorted()
    return RecordingSummary(
        durationMs = acc.lastTMs,
        confirmed = acc.confirmed,
        confirmedPerMinute = perMinute(acc.confirmed, acc.lastTMs),
        alerts = acc.alerts,
        linkGaps = acc.linkGaps,
        linkGapMs = acc.linkGapMs + openGapMs(acc),
        walkingFraction = fraction(acc.motionCounts[MotionState.WALKING], samples),
        stillFraction = fraction(acc.motionCounts[MotionState.STILL], samples),
        motionSamples = samples,
        latencyMedianMs = nearestRank(latencies, 50),
        latencyP90Ms = nearestRank(latencies, 90),
    )
}

fun nearestRank(sortedMs: List<Long>, percent: Int): Long? {
    if (sortedMs.isEmpty()) return null
    val rank = ceil(percent / 100.0 * sortedMs.size).toInt().coerceIn(1, sortedMs.size)
    return sortedMs[rank - 1]
}

private fun afterConfirm(acc: SummaryAccumulator, displayId: Int, tMs: Long): SummaryAccumulator = acc.copy(
    confirmed = acc.confirmed + 1,
    pendingConfirms = if (displayId in acc.pendingConfirms) acc.pendingConfirms else acc.pendingConfirms + (displayId to tMs),
)

// Only the first buzz after a confirmation measures its latency (records 5 and 6); later buzzes are plain alerts.
private fun afterVibration(acc: SummaryAccumulator, displayId: Int, tMs: Long): SummaryAccumulator {
    val counted = acc.copy(alerts = acc.alerts + 1)
    val confirmedAt = acc.pendingConfirms[displayId] ?: return counted
    return counted.copy(pendingConfirms = acc.pendingConfirms - displayId, latenciesMs = acc.latenciesMs + (tMs - confirmedAt))
}

private fun afterLinkChange(acc: SummaryAccumulator, up: Boolean?, tMs: Long): SummaryAccumulator = when (up) {
    true -> linkCameUp(acc, tMs)
    false -> linkWentDown(acc, tMs)
    null -> acc
}

private fun linkCameUp(acc: SummaryAccumulator, tMs: Long): SummaryAccumulator =
    acc.copy(linkSeenUp = true, linkDownSinceMs = null, linkGapMs = acc.linkGapMs + (acc.linkDownSinceMs?.let { tMs - it } ?: 0L))

// Searching before the first connection is not a gap; only a drop of a link that was up counts.
private fun linkWentDown(acc: SummaryAccumulator, tMs: Long): SummaryAccumulator =
    if (!acc.linkSeenUp || acc.linkDownSinceMs != null) acc else acc.copy(linkGaps = acc.linkGaps + 1, linkDownSinceMs = tMs)

private fun openGapMs(acc: SummaryAccumulator): Long = acc.linkDownSinceMs?.let { acc.lastTMs - it } ?: 0L

private fun perMinute(count: Int, durationMs: Long): Double = if (durationMs <= 0L) 0.0 else count * MS_PER_MINUTE / durationMs

private fun fraction(count: Int?, total: Int): Double = if (total == 0) 0.0 else (count ?: 0).toDouble() / total
