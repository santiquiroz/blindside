package io.github.santiquiroz.blindside.core.replay

import io.github.santiquiroz.blindside.core.RadarPipeline
import io.github.santiquiroz.blindside.core.config.PipelineConfig

val DEFAULT_TAU_SWEEP_MS: List<Long> = (0L..200L step 10).toList()

// Spec §6.3 / §12: τ (radar → IMU delay) is the value that minimises the mean NIS of the updates made while turning.
fun sweepTau(records: List<BsrecRecord>, config: PipelineConfig, startNanos: Long, tausMs: List<Long> = DEFAULT_TAU_SWEEP_MS): Map<Long, Double?> =
    tausMs.associateWith { tau -> meanTurningNis(records, withTau(config, tau), startNanos) }

fun bestTau(sweep: Map<Long, Double?>): Long? =
    sweep.entries.mapNotNull { (tau, nis) -> nis?.let { tau to it } }.minByOrNull { it.second }?.first

private fun meanTurningNis(records: List<BsrecRecord>, config: PipelineConfig, startNanos: Long): Double? {
    val pipeline = RadarPipeline(config)
    replayRecording(records.asSequence(), pipeline, startNanos)
    return pipeline.counters().meanTurningNis
}

private fun withTau(config: PipelineConfig, tauMs: Long): PipelineConfig =
    config.copy(tuning = config.tuning.copy(imu = config.tuning.imu.copy(radarImuDelayMs = tauMs)))
