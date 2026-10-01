package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.alerts.AlertCandidate
import io.github.santiquiroz.blindside.core.alerts.AlertFrame
import io.github.santiquiroz.blindside.core.alerts.sideOf
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.scene.logicalPosition
import io.github.santiquiroz.blindside.core.tracking.Track
import io.github.santiquiroz.blindside.core.tracking.TrackStatus

// Stop and scan already lives in the tracker (nothing is confirmed while moving), so every confirmed track is a candidate.
// A coasting track missed a window or two but has not left: its pending alert waits instead of turning screen-only.
internal fun alertStage(state: PipelineState, nowMs: Long, nowNanos: Long, config: PipelineConfig): Stage {
    val confirmed = state.tracker.tracks.filter { it.status == TrackStatus.CONFIRMED }
    val held = displayIdsWith(state.tracker.tracks, TrackStatus.COASTING)
    if (state.eliminated) return Stage(state.copy(limiter = state.limiter.silence(confirmed.map { it.displayId } + held)))
    val yaw = state.yaw.yawAt(nowMs, config.tuning.imu)
    val candidates = confirmed.map { candidateOf(it, nowMs, yaw, config) }
    val frame = AlertFrame(candidates, nowNanos, yaw, playerMoving = state.motion.isMoving(nowMs, config.tuning.motion), heldIds = held)
    val outcome = state.limiter.step(frame, config.tuning.alerts)
    return Stage(state.copy(limiter = outcome.limiter), listOfNotNull(outcome.fired))
}

private fun displayIdsWith(tracks: List<Track>, status: TrackStatus): Set<Int> =
    tracks.filter { it.status == status }.map { it.displayId }.toSet()

private fun candidateOf(track: Track, nowMs: Long, yawDeg: Double, config: PipelineConfig): AlertCandidate {
    val tracking = config.tuning.tracking
    val logical = logicalPosition(track, nowMs, yawDeg, tracking)
    return AlertCandidate(
        displayId = track.displayId,
        side = sideOf(logical.bearingDeg, config.tuning.alerts),
        rangeM = logical.norm,
        position = track.predictedTo(nowMs, tracking).kalman.position,
    )
}
