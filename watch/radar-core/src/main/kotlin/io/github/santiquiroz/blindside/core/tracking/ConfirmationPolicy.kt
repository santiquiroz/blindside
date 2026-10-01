package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.TrackingParams

data class MotionContext(val moving: Boolean, val gateOpenFromMs: Long) {
    companion object {
        val STILL = MotionContext(moving = false, gateOpenFromMs = Long.MIN_VALUE)
    }
}

enum class WindowOutcome { HIT, MISS, NOT_EVALUABLE }

// Spec §6.6 MVP "detenerse y escanear": nothing is promoted while moving or in the 0.5 s tail; only later windows count.
fun canConfirm(track: Track, outcomes: List<WindowMark>, motion: MotionContext, params: TrackingParams): Boolean {
    if (track.status != TrackStatus.TENTATIVE || motion.moving) return false
    return hitsAfterGate(outcomes, motion.gateOpenFromMs, params) >= params.confirmHits
}

fun hitsAfterGate(outcomes: List<WindowMark>, gateOpenFromMs: Long, params: TrackingParams): Int =
    outcomes.filter { it.startMs >= gateOpenFromMs }.takeLast(params.confirmWindows).count { it.hit }

fun windowOutcome(track: Track, evidenceRadars: Set<Int>, coveringRadars: Set<Int>): WindowOutcome = when {
    track.hitThisWindow -> WindowOutcome.HIT
    coveringRadars.any { it in evidenceRadars } -> WindowOutcome.MISS
    else -> WindowOutcome.NOT_EVALUABLE
}
