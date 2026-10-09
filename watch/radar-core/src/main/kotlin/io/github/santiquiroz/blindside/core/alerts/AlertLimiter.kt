package io.github.santiquiroz.blindside.core.alerts

import io.github.santiquiroz.blindside.core.config.AlertParams
import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.geometry.trackingToBody
import io.github.santiquiroz.blindside.core.scene.Side
import kotlin.math.abs

data class AlertCandidate(val displayId: Int, val side: Side, val rangeM: Double, val position: Point2)

data class LostContact(val displayId: Int, val position: Point2, val lostNanos: Long)

data class AlertFrame(
    val candidates: List<AlertCandidate>,
    val nowNanos: Long,
    val yawDeg: Double = 0.0,
    val playerMoving: Boolean = false,
    val heldIds: Set<Int> = emptySet(),
)

data class LimiterOutcome(val limiter: AlertLimiter, val fired: ContactAlert?)

data class AlertLimiter(
    val handled: Set<Int> = emptySet(),
    val pending: Map<Int, AlertCandidate> = emptyMap(),
    val visible: Map<Int, AlertCandidate> = emptyMap(),
    val lost: List<LostContact> = emptyList(),
    val lastFiredNanos: Long? = null,
    val firedNanos: List<Long> = emptyList(),
    val systemBusyUntilNanos: Long = Long.MIN_VALUE,
    val farSeen: Set<Int> = emptySet(),
    val farHandled: Set<Int> = emptySet(),
    val lastFarFiredNanos: Long? = null,
    val farFiredNanos: List<Long> = emptyList(),
) {
    fun step(frame: AlertFrame, params: AlertParams): LimiterOutcome {
        val near = withLosses(frame.candidates, frame.nowNanos, params)
            .withReacquired(frame.candidates, frame.yawDeg, params)
            .withPending(frame.candidates, params)
            .withFarSeen(frame.candidates, params)
            .fireIfDue(frame, params)
        val outcome = if (near.fired != null) near else near.limiter.fireFarIfDue(frame, params)
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

    // A pending contact keeps its last confirmed candidate, so it holds its place in the queue while it is missing.
    private fun withPending(candidates: List<AlertCandidate>, params: AlertParams): AlertLimiter =
        copy(pending = pending + candidates.filter { joinsQueue(it, params) }.associateBy { it.displayId })

    // A far contact joins the near queue once it comes within nearRangeM; one queued while near keeps its place wherever it goes.
    private fun joinsQueue(candidate: AlertCandidate, params: AlertParams): Boolean =
        candidate.displayId !in handled && (!isFar(candidate, params) || candidate.displayId in pending)

    private fun withFarSeen(candidates: List<AlertCandidate>, params: AlertParams): AlertLimiter =
        copy(farSeen = farSeen + candidates.filter { isFarContact(it, params) }.ids())

    // Spec §5.5 (b): only the pending contacts whose turn came and that are gone turn screen-only; held ones keep waiting.
    private fun fireIfDue(frame: AlertFrame, params: AlertParams): LimiterOutcome {
        if (pending.isEmpty() || !isSlotOpen(frame.nowNanos, params)) return LimiterOutcome(this, null)
        if (isSaturated(frame.nowNanos, params)) return LimiterOutcome(silence(pending.keys), null)
        val present = frame.candidates.ids().toSet()
        val queue = pending.values.sortedWith(PRIORITY)
        val cleared = silence(goneBeforeFirstPresent(queue, present, frame.heldIds))
        val chosen = queue.firstOrNull { it.displayId in present } ?: return LimiterOutcome(cleared, null)
        return LimiterOutcome(cleared.fire(chosen, frame.nowNanos, params), ContactAlert(chosen.displayId, chosen.side, frame.nowNanos))
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

    // Runs only in a frame with no near alert and only while the near slot is free too, so a far pattern never cuts a near one short.
    // A saturated far budget skips the far contacts for good instead of queueing them.
    private fun fireFarIfDue(frame: AlertFrame, params: AlertParams): LimiterOutcome {
        val due = frame.candidates.filter { isFarContact(it, params) && it.displayId !in farHandled }
        if (due.isEmpty() || !isFarSlotOpen(frame.nowNanos, params)) return LimiterOutcome(this, null)
        if (isFarSaturated(frame.nowNanos, params)) return LimiterOutcome(copy(farHandled = farHandled + due.ids()), null)
        val chosen = due.sortedWith(PRIORITY).first()
        return LimiterOutcome(fireFar(chosen, frame.nowNanos, params), ContactAlert(chosen.displayId, chosen.side, frame.nowNanos, far = true))
    }

    private fun isFarSlotOpen(nowNanos: Long, params: AlertParams): Boolean {
        val last = lastFarFiredNanos ?: return isSlotOpen(nowNanos, params)
        return isSlotOpen(nowNanos, params) && nowNanos - last >= params.farMinGapMs * NANOS_PER_MS
    }

    private fun isFarSaturated(nowNanos: Long, params: AlertParams): Boolean =
        farFiredNanos.count { nowNanos - it < MINUTE_NANOS } >= params.maxFarPerMinute

    private fun fireFar(chosen: AlertCandidate, nowNanos: Long, params: AlertParams) = copy(
        farHandled = farHandled + chosen.displayId,
        lastFarFiredNanos = nowNanos,
        farFiredNanos = (farFiredNanos + nowNanos).filter { nowNanos - it < MINUTE_NANOS }.takeLast(params.maxFarPerMinute),
    )

    private fun isFarContact(candidate: AlertCandidate, params: AlertParams): Boolean =
        isFar(candidate, params) && candidate.displayId !in handled && candidate.displayId !in pending

    private fun isFar(candidate: AlertCandidate, params: AlertParams): Boolean = candidate.rangeM > params.nearRangeM

    // Positions measured while the player turns or walks carry the τ and ego-motion errors, so the last still one is kept.
    private fun withVisible(candidates: List<AlertCandidate>, playerMoving: Boolean): AlertLimiter =
        copy(visible = candidates.filter { it.displayId in handled }.associate { it.displayId to referenceOf(it, playerMoving) })

    private fun referenceOf(candidate: AlertCandidate, playerMoving: Boolean): AlertCandidate =
        if (playerMoving) visible[candidate.displayId] ?: candidate else candidate

    // A far contact already seen is not born again when it comes near, so the sector pause never hides its near alert.
    private fun isNew(candidate: AlertCandidate): Boolean =
        candidate.displayId !in handled && candidate.displayId !in pending && candidate.displayId !in farSeen

    private fun List<AlertCandidate>.ids() = map { it.displayId }

    private fun goneBeforeFirstPresent(queue: List<AlertCandidate>, present: Set<Int>, held: Set<Int>): List<Int> =
        queue.takeWhile { it.displayId !in present }.ids().filter { it !in held }

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
