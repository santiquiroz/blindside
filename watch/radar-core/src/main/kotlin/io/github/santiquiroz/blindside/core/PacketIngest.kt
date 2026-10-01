package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.alerts.TrackConfirmed
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.geometry.StaleMemory
import io.github.santiquiroz.blindside.core.geometry.filterFrame
import io.github.santiquiroz.blindside.core.imu.ChannelUpdate
import io.github.santiquiroz.blindside.core.imu.ImuChannel
import io.github.santiquiroz.blindside.core.imu.ImuReading
import io.github.santiquiroz.blindside.core.imu.MotionDetector
import io.github.santiquiroz.blindside.core.imu.RestEvidence
import io.github.santiquiroz.blindside.core.imu.ingestBatch
import io.github.santiquiroz.blindside.core.imu.mergeIncrements
import io.github.santiquiroz.blindside.core.protocol.Bundle
import io.github.santiquiroz.blindside.core.protocol.BundleDecoder
import io.github.santiquiroz.blindside.core.protocol.RadarFrame
import io.github.santiquiroz.blindside.core.tracking.MotionContext
import io.github.santiquiroz.blindside.core.tracking.Tracker
import io.github.santiquiroz.blindside.core.tracking.TrackerContext
import io.github.santiquiroz.blindside.core.tracking.TrackerStep

internal fun ingestPacket(state: PipelineState, bytes: ByteArray, arrivalNanos: Long, config: PipelineConfig): Stage {
    val received = state.copy(lastPacketNanos = arrivalNanos, counters = state.counters.copy(packets = state.counters.packets + 1))
    val bundle = BundleDecoder.decode(bytes) ?: return Stage(malformed(received, arrivalNanos, config))
    return Stage(received)
        .then { Stage(resetIfEspRebooted(it, bundle)) }
        .then { Stage(withHeader(it, bundle, arrivalNanos, config)) }
        .then { Stage(withImus(it, bundle, config)) }
        .then { Stage(withMovingMark(it, bundle.tMs, config)) }
        .then { Stage(withStatuses(it, bundle, arrivalNanos, config)) }
        .then { withFrames(it, bundle, arrivalNanos, config) }
        .then { advanceTracker(it, bundle.tMs - config.tuning.tracking.windowMs, arrivalNanos, config) }
}

internal fun trackerContext(state: PipelineState, config: PipelineConfig): TrackerContext {
    val tuning = config.tuning
    return TrackerContext(
        mounts = config.mounts,
        aliveRadars = aliveRadars(state, config),
        yawAt = { tMs -> state.yaw.yawAt(tMs, tuning.imu) },
        motionAt = { tMs -> motionContext(state, tMs, config) },
    )
}

// Spec §6.6 "detenerse y escanear": the gate stays shut while moving and for the 0.5 s tail after the last moving packet.
internal fun motionContext(state: PipelineState, tMs: Long, config: PipelineConfig): MotionContext {
    val gateOpenFromMs = state.lastMovingMs?.let { it + config.tuning.tracking.stopScanTailMs } ?: Long.MIN_VALUE
    return MotionContext(moving = state.motion.isMoving(tMs, config.tuning.motion), gateOpenFromMs = gateOpenFromMs)
}

private fun malformed(state: PipelineState, nowNanos: Long, config: PipelineConfig): PipelineState =
    state.copy(counters = state.counters.copy(malformedPackets = state.counters.malformedPackets + 1))
        .markCorrupt(nowNanos, 1, config.tuning.status.corruptWindowMs)

private fun resetIfEspRebooted(state: PipelineState, bundle: Bundle): PipelineState {
    val last = state.lastHeaderMs ?: return state
    return if (bundle.tMs < last) state.withRestartedTimeReferences() else state
}

private fun withHeader(state: PipelineState, bundle: Bundle, arrivalNanos: Long, config: PipelineConfig): PipelineState {
    val seq = state.seq.observe(bundle.seq, bundle.tMs)
    val truncated = if (bundle.truncated) 1 else 0
    return state.copy(
        clock = state.clock.observe(bundle.tMs, arrivalNanos),
        seq = seq,
        lastHeaderMs = bundle.tMs,
        flags = bundle.flags,
        counters = state.counters.copy(
            lostPackets = seq.lostPackets,
            truncatedPackets = state.counters.truncatedPackets + truncated,
            lastLink = bundle.links.lastOrNull() ?: state.counters.lastLink,
        ),
    ).markCorrupt(arrivalNanos, truncated, config.tuning.status.corruptWindowMs)
}

private fun withImus(state: PipelineState, bundle: Bundle, config: PipelineConfig): PipelineState {
    val imuParams = config.tuning.imu
    val evidence = RestEvidence(state.watchGyro, state.motion.lastStepMs)
    val updates = bundle.imuBatches.filter { bundle.imuOk(it.imuId) }.associate { batch ->
        batch.imuId to (state.imus[batch.imuId] ?: ImuChannel()).ingestBatch(batch, evidence, imuParams)
    }
    val imus = state.imus + updates.mapValues { it.value.channel }
    val increments = mergeIncrements(updates.values.map { it.increments })
    val prone = imus.values.any { it.isReady && it.isProne(imuParams) }
    val motion = increments.fold(state.motion) { m, inc -> m.withYawIncrement(inc, config.tuning.motion) }
        .let { withAccelNorms(it, updates, config) }
        .withProne(prone)
    val next = state.copy(imus = imus, yaw = state.yaw.apply(increments, imuParams))
    return next.copy(motion = motion.withTurnSource(fromWatch = usableImus(next).isEmpty()))
}

private fun withAccelNorms(motion: MotionDetector, updates: Map<Int, ChannelUpdate>, config: PipelineConfig): MotionDetector =
    updates.entries.fold(motion) { m, (imuId, update) -> withImuAccel(m, imuId, update.readings, config) }

private fun withImuAccel(motion: MotionDetector, imuId: Int, readings: List<ImuReading>, config: PipelineConfig): MotionDetector =
    readings.fold(motion) { m, reading -> m.withAccelNorm(imuId, reading.tMs, reading.accelG.norm, config.tuning.motion) }

private fun withMovingMark(state: PipelineState, tMs: Long, config: PipelineConfig): PipelineState =
    if (state.motion.isMoving(tMs, config.tuning.motion)) state.copy(lastMovingMs = tMs) else state

private fun withStatuses(state: PipelineState, bundle: Bundle, arrivalNanos: Long, config: PipelineConfig): PipelineState =
    bundle.statuses.fold(state) { acc, status ->
        val previous = acc.lastBadFrames[status.radarId] ?: status.badFrames
        acc.copy(lastBadFrames = acc.lastBadFrames + (status.radarId to status.badFrames))
            .markCorrupt(arrivalNanos, status.badFrames - previous, config.tuning.status.corruptWindowMs)
    }

private fun withFrames(state: PipelineState, bundle: Bundle, arrivalNanos: Long, config: PipelineConfig): Stage =
    bundle.radarFrames
        .sortedWith(compareBy({ it.tMs }, { it.radarId }))
        .fold(Stage(state)) { stage, frame -> stage.then { withFrame(it, frame, arrivalNanos, config) } }

private fun withFrame(state: PipelineState, frame: RadarFrame, arrivalNanos: Long, config: PipelineConfig): Stage {
    val mount = config.mounts.firstOrNull { it.radarId == frame.radarId } ?: return Stage(state)
    if (Tracker.isOutOfOrder(state.tracker, frame.tMs)) {
        return Stage(state.copy(counters = state.counters.copy(outOfOrderFrames = state.counters.outOfOrderFrames + 1)))
    }
    val tuning = config.tuning
    val filtered = filterFrame(frame, mount, state.stale[frame.radarId] ?: StaleMemory(), tuning.decode)
    val counters = state.counters.copy(
        implausibleTargets = state.counters.implausibleTargets + filtered.frame.implausible,
        staleTargets = state.counters.staleTargets + filtered.frame.stale,
        nearFieldTargets = state.counters.nearFieldTargets + filtered.frame.nearField,
    )
    val step = Tracker.onFrame(state.tracker, filtered.frame, trackerContext(state, config), tuning)
    val next = state.copy(stale = state.stale + (frame.radarId to filtered.memory), counters = withNis(counters, step, state, config))
        .markCorrupt(arrivalNanos, filtered.frame.implausible, tuning.status.corruptWindowMs)
    return confirmationStage(next, step, arrivalNanos)
}

// Spec §6.3: τ is tuned by minimising the NIS while turning, so only turning updates are summed.
private fun withNis(counters: PipelineCounters, step: TrackerStep, state: PipelineState, config: PipelineConfig): PipelineCounters {
    if (step.nis.isEmpty() || !state.motion.isTurning(config.tuning.motion)) return counters
    return counters.copy(turningNisSum = counters.turningNisSum + step.nis.sum(), turningNisCount = counters.turningNisCount + step.nis.size)
}

private fun advanceTracker(state: PipelineState, tMs: Long, arrivalNanos: Long, config: PipelineConfig): Stage =
    confirmationStage(state, Tracker.advanceTo(state.tracker, tMs, trackerContext(state, config), config.tuning), arrivalNanos)

private fun confirmationStage(state: PipelineState, step: TrackerStep, arrivalNanos: Long): Stage =
    Stage(state.copy(tracker = step.state), step.confirmed.map { TrackConfirmed(it.displayId, arrivalNanos) })
