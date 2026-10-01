package io.github.santiquiroz.blindside.core.alerts

import io.github.santiquiroz.blindside.core.config.AlertParams
import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.geometry.trackingToBody
import io.github.santiquiroz.blindside.core.scene.Side
import kotlin.math.abs

data class AlertCandidate(val displayId: Int, val side: Side, val rangeM: Double, val position: Point2)

data class LostContact(val displayId: Int, val position: Point2, val lostNanos: Long)

data class AlertFrame(val candidates: List<AlertCandidate>, val nowNanos: Long, val yawDeg: Double = 0.0, val playerMoving: Boolean = false)

data class LimiterOutcome(val limiter: AlertLimiter, val fired: ContactAlert?)

data class AlertLimiter(
    val handled: Set<Int> = emptySet(),
    val pending: Set<Int> = emptySet(),
    val visible: Map<Int, AlertCandidate> = emptyMap(),
    val lost: List<LostContact> = emptyList(),
    val lastFiredNanos: Long? = null,
    val firedNanos: List<Long> = emptyList(),
    val systemBusyUntilNanos: Long = Long.MIN_VALUE,
) {
    fun step(frame: AlertFrame, params: AlertParams): LimiterOutcome {
        val outcome = withLosses(frame.candidates, frame.nowNanos, params)
            .withReacquired(frame.candidates, frame.yawDeg, params)
            .withPending(frame.candidates)
            .fireIfDue(frame.candidates, frame.nowNanos, params)
        return outcome.copy(limiter = outcome.limiter.withVisible(frame.candidates, frame.playerMoving))
    }

    fun silence(displayIds: Collection<Int>): AlertLimiter = copy(handled = handled + displayIds, pending = pending - displayIds.toSet())

    // Spec §5.5: a system pattern fires at once and never moves the 1 s gap; contacts wait until it ends.
    fun withSystemAlert(nowNanos: Long, params: AlertParams): AlertLimiter =
        copy(systemBusyUntilNanos = maxOf(systemBusyUntilNanos, nowNanos + params.systemPatternMs * NANOS_PER_MS))

    fun isSaturated(nowNanos: Long, params: AlertParams): Boolean =
        firedNanos.count { nowNanos - it < MINUTE_NANOS } >= params.maxPerMinute

    private fun withLosses(candidates: List<AlertCandidate>, nowNanos: Long, params: AlertParams): AlertLimiter {
        val present = candidates.ids().toSet()
        val gone = visible.values.filter { it.displayId !in present }.map { LostContact(it.displayId, it.position, nowNanos) }
        val recent = (lost + gone).filter { it.displayId !in present && nowNanos - it.lostNanos < params.sectorPauseMs * NANOS_PER_MS }
        return copy(lost = recent)
    }

    // Spec §5.5 sector pause: a new track born < 1.5 m from a contact of the same sector that already alerted and was lost < 5 s ago is that contact.
    private fun withReacquired(candidates: List<AlertCandidate>, yawDeg: Double, params: AlertParams): AlertLimiter =
        candidates.filter { isNew(it) }.fold(this) { limiter, candidate -> limiter.inheritIfReacquired(candidate, yawDeg, params) }

    // Positions live in the yaw-compensated tracking frame, so the lost contact's sector is recomputed with today's yaw.
    private fun inheritIfReacquired(candidate: AlertCandidate, yawDeg: Double, params: AlertParams): AlertLimiter {
        val match = lost.firstOrNull { sideNow(it.position, yawDeg, params) == candidate.side && (it.position - candidate.position).norm < params.reacquireDistanceM }
            ?: return this
        return copy(handled = handled + candidate.displayId, lost = lost - match)
    }

    private fun withPending(candidates: List<AlertCandidate>): AlertLimiter =
        copy(pending = pending + candidates.filter { it.displayId !in handled }.ids())

    private fun fireIfDue(candidates: List<AlertCandidate>, nowNanos: Long, params: AlertParams): LimiterOutcome {
        if (pending.isEmpty() || !isSlotOpen(nowNanos, params)) return LimiterOutcome(this, null)
        val waiting = candidates.filter { it.displayId in pending }
        val cleared = silence(pending - waiting.ids().toSet())
        if (cleared.isSaturated(nowNanos, params)) return LimiterOutcome(cleared.silence(cleared.pending), null)
        val chosen = waiting.minWithOrNull(PRIORITY) ?: return LimiterOutcome(cleared, null)
        return LimiterOutcome(cleared.fire(chosen, nowNanos, params), ContactAlert(chosen.displayId, chosen.side, nowNanos))
    }

    private fun isSlotOpen(nowNanos: Long, params: AlertParams): Boolean {
        if (nowNanos < systemBusyUntilNanos) return false
        val last = lastFiredNanos ?: return true
        return nowNanos - last >= params.minGapMs * NANOS_PER_MS
    }

    private fun fire(chosen: AlertCandidate, nowNanos: Long, params: AlertParams) = copy(
        handled = handled + chosen.displayId,
        pending = pending - chosen.displayId,
        lastFiredNanos = nowNanos,
        firedNanos = (firedNanos + nowNanos).filter { nowNanos - it < MINUTE_NANOS }.takeLast(params.maxPerMinute),
    )

    // Positions measured while the player turns or walks carry the τ and ego-motion errors, so the last still one is kept.
    private fun withVisible(candidates: List<AlertCandidate>, playerMoving: Boolean): AlertLimiter =
        copy(visible = candidates.filter { it.displayId in handled }.associate { it.displayId to referenceOf(it, playerMoving) })

    private fun referenceOf(candidate: AlertCandidate, playerMoving: Boolean): AlertCandidate =
        if (playerMoving) visible[candidate.displayId] ?: candidate else candidate

    private fun isNew(candidate: AlertCandidate): Boolean = candidate.displayId !in handled && candidate.displayId !in pending

    private fun List<AlertCandidate>.ids() = map { it.displayId }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val MINUTE_NANOS = 60_000L * NANOS_PER_MS
        val PRIORITY = compareBy<AlertCandidate>({ it.side != Side.CENTER }, { it.rangeM }, { it.displayId })
    }
}

private fun sideNow(trackingPosition: Point2, yawDeg: Double, params: AlertParams): Side =
    sideOf(trackingToBody(trackingPosition, yawDeg).bearingDeg, params)

fun sideOf(bearingDeg: Double, params: AlertParams): Side = when {
    abs(bearingDeg) <= params.centerHalfWidthDeg -> Side.CENTER
    bearingDeg < 0 -> Side.LEFT
    else -> Side.RIGHT
}
