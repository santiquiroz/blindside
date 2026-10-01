package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.TrackingParams

fun Track.closeWindow(outcome: WindowOutcome, covered: Boolean, windowEndMs: Long, params: TrackingParams): Track? {
    val rolled = recordOutcome(outcome, windowEndMs - params.windowMs, params).copy(previousWindowRadars = windowRadars, windowRadars = emptySet())
    return when (status) {
        TrackStatus.TENTATIVE -> if (rolled.isStaleTentative(windowEndMs, params)) null else rolled
        TrackStatus.CONFIRMED -> if (hitThisWindow) rolled else rolled.startCoasting(params)
        TrackStatus.COASTING, TrackStatus.OUT_OF_VIEW -> rolled.coastOrExpire(covered, windowEndMs, params)
    }
}

fun Track.reacquired(): Track = if (isLost) copy(status = TrackStatus.CONFIRMED, lostStill = false) else this

fun Track.coastLimitMs(params: TrackingParams): Long = if (lostStill) params.coastStillMs else params.coastMovingMs

private fun Track.recordOutcome(outcome: WindowOutcome, windowStartMs: Long, params: TrackingParams): Track = when (outcome) {
    WindowOutcome.NOT_EVALUABLE -> this
    else -> copy(outcomes = (outcomes + WindowMark(windowStartMs, outcome == WindowOutcome.HIT)).takeLast(params.confirmWindows))
}

private fun Track.missedLast(count: Int): Boolean =
    outcomes.size >= count && outcomes.takeLast(count).none { it.hit }

// Saturated frames are never evaluable, so a silence timeout keeps tentatives from piling up while walking.
private fun Track.isStaleTentative(windowEndMs: Long, params: TrackingParams): Boolean =
    missedLast(params.tentativeMaxMisses) || windowEndMs - lastHitMs > params.tentativeMaxSilenceMs

private fun Track.startCoasting(params: TrackingParams): Track {
    val still = kalman.speed < params.stillSpeedMps
    val frozen = if (still) CvKalman.freezeVelocity(kalman) else kalman
    return copy(status = TrackStatus.COASTING, lostStill = still, kalman = frozen)
}

private fun Track.coastOrExpire(covered: Boolean, windowEndMs: Long, params: TrackingParams): Track? {
    val elapsed = windowEndMs - lastHitMs
    if (covered) return if (elapsed > coastLimitMs(params)) null else copy(status = TrackStatus.COASTING)
    return when {
        elapsed > params.coastExitMs + params.outOfViewMs -> null
        elapsed > params.coastExitMs -> copy(status = TrackStatus.OUT_OF_VIEW)
        else -> copy(status = TrackStatus.COASTING)
    }
}
