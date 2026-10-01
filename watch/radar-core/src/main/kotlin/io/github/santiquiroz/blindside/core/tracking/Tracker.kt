package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.RadarMount
import io.github.santiquiroz.blindside.core.config.TuningParams
import io.github.santiquiroz.blindside.core.geometry.FilteredFrame
import io.github.santiquiroz.blindside.core.geometry.coveringRadars
import io.github.santiquiroz.blindside.core.geometry.trackingToBody

data class TrackerContext(
    val mounts: List<RadarMount>,
    val aliveRadars: Set<Int>,
    val yawAt: (Long) -> Double,
    val motionAt: (Long) -> MotionContext,
)

data class TrackerState(
    val tracks: List<Track> = emptyList(),
    val nextTrackId: Int = 1,
    val ids: DisplayIds = DisplayIds(),
    val windowIndex: Long? = null,
    val evidenceRadars: Set<Int> = emptySet(),
    val lastFrameMs: Long? = null,
)

data class TrackerStep(val state: TrackerState, val confirmed: List<Track> = emptyList(), val nis: List<Double> = emptyList()) {
    fun then(next: (TrackerState) -> TrackerStep): TrackerStep {
        val step = next(state)
        return TrackerStep(step.state, confirmed + step.confirmed, nis + step.nis)
    }
}

object Tracker {
    fun isOutOfOrder(state: TrackerState, tMs: Long): Boolean = state.lastFrameMs != null && tMs < state.lastFrameMs

    fun onFrame(state: TrackerState, frame: FilteredFrame, ctx: TrackerContext, tuning: TuningParams): TrackerStep {
        if (isOutOfOrder(state, frame.tMs)) return TrackerStep(state)
        return TrackerStep(state)
            .then { closeWindowsBefore(it, frame.tMs, ctx, tuning) }
            .then { TrackerStep(withEvidence(it, frame)) }
            .then { TrackerStep(predictAll(it, frame.tMs, tuning)) }
            .then { associateFrame(it, frame, ctx, tuning) }
    }

    fun advanceTo(state: TrackerState, tMs: Long, ctx: TrackerContext, tuning: TuningParams): TrackerStep =
        closeWindowsBefore(state, tMs, ctx, tuning)

    private fun withEvidence(state: TrackerState, frame: FilteredFrame): TrackerState =
        if (frame.canReportMiss) state.copy(evidenceRadars = state.evidenceRadars + frame.radarId) else state

    private fun predictAll(state: TrackerState, tMs: Long, tuning: TuningParams): TrackerState =
        state.copy(tracks = state.tracks.map { it.predictedTo(tMs, tuning.tracking) })

    private fun closeWindowsBefore(state: TrackerState, tMs: Long, ctx: TrackerContext, tuning: TuningParams): TrackerStep {
        val window = tMs / tuning.tracking.windowMs
        val open = state.windowIndex ?: return TrackerStep(state.copy(windowIndex = window))
        if (window <= open) return TrackerStep(state)
        val closed = closeWindow(state, (open + 1) * tuning.tracking.windowMs, ctx, tuning)
        return closed.copy(state = closed.state.copy(windowIndex = window, evidenceRadars = emptySet()))
    }

    private fun closeWindow(state: TrackerState, endMs: Long, ctx: TrackerContext, tuning: TuningParams): TrackerStep {
        val motion = ctx.motionAt(endMs)
        val yaw = ctx.yawAt(endMs)
        val results = state.tracks.map { it to closeTrack(it, state.evidenceRadars, endMs, yaw, ctx, tuning) }
        val dead = results.filter { it.second == null }.map { it.first.predictedTo(endMs, tuning.tracking) }
        val survivors = results.mapNotNull { it.second }
        val confirmed = survivors.filter { canConfirm(it, it.outcomes, motion, tuning.tracking) }.map { it.copy(status = TrackStatus.CONFIRMED) }
        val tracks = survivors.map { s -> confirmed.firstOrNull { it.id == s.id } ?: s }
        val ids = dead.fold(state.ids) { acc, track -> acc.bury(track, endMs, tuning.tracking) }
        return TrackerStep(state.copy(tracks = tracks, ids = ids), confirmed)
    }

    private fun closeTrack(track: Track, evidence: Set<Int>, endMs: Long, yaw: Double, ctx: TrackerContext, tuning: TuningParams): Track? {
        val body = trackingToBody(track.predictedTo(endMs, tuning.tracking).kalman.position, yaw)
        val covering = coveringRadars(body, ctx.mounts.filter { it.radarId in ctx.aliveRadars }, tuning.decode)
        val outcome = windowOutcome(track, evidence, covering)
        return track.closeWindow(outcome, covering.isNotEmpty(), endMs, tuning.tracking)
    }

    private fun associateFrame(state: TrackerState, frame: FilteredFrame, ctx: TrackerContext, tuning: TuningParams): TrackerStep {
        val yaw = ctx.yawAt(frame.tMs - tuning.imu.radarImuDelayMs)
        val measurements = frame.detections.map { toMeasurement(it, yaw, ctx.mounts, tuning) }
        val assignment = associate(state.tracks, measurements, tuning.tracking)
        val pairs = assignment.mapIndexedNotNull { d, t -> t?.let { state.tracks[it] to measurements[d] } }
        val updated = state.tracks.map { track -> pairs.firstOrNull { it.first.id == track.id }?.let { hit(track, it.second, frame) } ?: track }
        val spawned = measurements.filterIndexed { i, _ -> assignment[i] == null }
            .fold(state.copy(tracks = updated, lastFrameMs = frame.tMs)) { acc, m -> spawn(acc, m, frame, tuning) }
        val windowStart = frame.tMs / tuning.tracking.windowMs * tuning.tracking.windowMs
        val nis = pairs.filter { it.first.status == TrackStatus.CONFIRMED }.map { (track, m) -> normalizedInnovation(track, m) }
        return confirmEagerly(spawned, pairs.map { it.first.id }.toSet(), windowStart, ctx.motionAt(frame.tMs), tuning).copy(nis = nis)
    }

    private fun hit(track: Track, measurement: Measurement, frame: FilteredFrame): Track =
        track.copy(
            kalman = CvKalman.update(track.kalman, measurement.position, measurement.r),
            lastHitMs = frame.tMs,
            windowRadars = track.windowRadars + frame.radarId,
        ).reacquired()

    private fun spawn(state: TrackerState, m: Measurement, frame: FilteredFrame, tuning: TuningParams): TrackerState {
        val (ids, displayId) = state.ids.assign(m.position, frame.tMs, tuning.tracking)
        val track = Track(
            id = state.nextTrackId,
            displayId = displayId,
            kalman = CvKalman.init(m.position, m.r, tuning.tracking.sigmaInitialSpeedMps),
            stateMs = frame.tMs,
            bornMs = frame.tMs,
            lastHitMs = frame.tMs,
            windowRadars = setOf(frame.radarId),
        )
        return state.copy(tracks = state.tracks + track, nextTrackId = state.nextTrackId + 1, ids = ids)
    }

    // A hit is final, so confirming on it instead of at the window close saves up to one packet of latency.
    private fun confirmEagerly(state: TrackerState, hitIds: Set<Int>, windowStartMs: Long, motion: MotionContext, tuning: TuningParams): TrackerStep {
        val current = WindowMark(windowStartMs, hit = true)
        val confirmed = state.tracks
            .filter { it.id in hitIds && canConfirm(it, it.outcomes + current, motion, tuning.tracking) }
            .map { it.copy(status = TrackStatus.CONFIRMED) }
        val tracks = state.tracks.map { t -> confirmed.firstOrNull { it.id == t.id } ?: t }
        return TrackerStep(state.copy(tracks = tracks), confirmed)
    }
}
