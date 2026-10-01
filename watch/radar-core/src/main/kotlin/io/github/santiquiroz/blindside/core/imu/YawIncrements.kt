package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.config.ImuParams
import io.github.santiquiroz.blindside.core.protocol.IMU_SAMPLE_PERIOD_MS
import io.github.santiquiroz.blindside.core.protocol.ImuBatch
import io.github.santiquiroz.blindside.core.protocol.RAW_GYRO_RATE_HZ
import io.github.santiquiroz.blindside.core.protocol.RAW_READINGS_PER_SAMPLE

data class YawIncrement(val tEndMs: Long, val durationMs: Long, val deltaDeg: Double) {
    val rateDps: Double get() = deltaDeg * 1000.0 / durationMs
}

data class ChannelUpdate(val channel: ImuChannel, val increments: List<YawIncrement>, val readings: List<ImuReading>)

private const val HALF_SAMPLE_MS = IMU_SAMPLE_PERIOD_MS / 2

fun ImuChannel.ingestBatch(batch: ImuBatch, evidence: RestEvidence, params: ImuParams): ChannelUpdate {
    val readings = batch.readings(gyroLsbPerDps, accelLsbPerG).filter { lastSampleMs == null || it.tMs > lastSampleMs }
    if (readings.isEmpty()) return ChannelUpdate(this, emptyList(), emptyList())
    val gap = gapIncrement(batch, params)
    val start = ChannelUpdate(this, listOfNotNull(gap), emptyList())
    val folded = readings.fold(start) { acc, reading -> acc.withReading(reading, evidence, params) }
    return folded.copy(channel = folded.channel.copy(lastSums = batch.gyroSums, lastSampleMs = batch.tLastMs))
}

fun missingSamples(lastSampleMs: Long, firstSampleMs: Long): Int =
    ((firstSampleMs - lastSampleMs + HALF_SAMPLE_MS) / IMU_SAMPLE_PERIOD_MS - 1).toInt()

// Spec §6.3: Δψ ≈ ĝᵀ·(ΔΣω − N·b)·scale/200, using the firmware's cumulative raw 200 Hz sums.
fun ImuChannel.gapIncrement(batch: ImuBatch, params: ImuParams): YawIncrement? {
    val previousSums = lastSums ?: return null
    val previousSampleMs = lastSampleMs ?: return null
    val missing = missingSamples(previousSampleMs, batch.tFirstMs)
    val b = bias ?: return null
    val g = gravity ?: return null
    if (missing <= 0 || !isReady) return null
    val sumsDelta = Vec3(wrappedDelta(batch.gyroSums[0], previousSums[0]), wrappedDelta(batch.gyroSums[1], previousSums[1]), wrappedDelta(batch.gyroSums[2], previousSums[2]))
    val gapRaw = sumsDelta - batchRawSum(batch)
    val rawReadings = (missing * RAW_READINGS_PER_SAMPLE).toDouble()
    val angle = (gapRaw / gyroLsbPerDps - b * rawReadings) / RAW_GYRO_RATE_HZ
    val delta = -(g.normalized() dot angle) * params.gyroScale
    return YawIncrement(batch.tFirstMs - HALF_SAMPLE_MS, missing * IMU_SAMPLE_PERIOD_MS, delta)
}

// Each IMU task samples on its own phase, so increments are paired on a shared 20 ms grid before averaging.
fun mergeIncrements(perImu: List<List<YawIncrement>>): List<YawIncrement> =
    perImu.flatten()
        .flatMap { splitIntoSamples(it) }
        .groupBy { gridEndMs(it.tEndMs) }
        .map { (gridEnd, group) -> YawIncrement(gridEnd, IMU_SAMPLE_PERIOD_MS, group.map { it.deltaDeg }.average()) }
        .sortedBy { it.tEndMs }

fun gridEndMs(tEndMs: Long): Long = Math.floorDiv(tEndMs + IMU_SAMPLE_PERIOD_MS - 1, IMU_SAMPLE_PERIOD_MS) * IMU_SAMPLE_PERIOD_MS

// The firmware sums wrap as u32; the difference read as int32 survives one wrap.
fun wrappedDelta(now: Long, previous: Long): Double = ((now - previous) and 0xFFFFFFFFL).toInt().toDouble()

private fun splitIntoSamples(increment: YawIncrement): List<YawIncrement> {
    val pieces = (increment.durationMs / IMU_SAMPLE_PERIOD_MS).coerceAtLeast(1)
    return (0 until pieces).map { k ->
        YawIncrement(increment.tEndMs - k * IMU_SAMPLE_PERIOD_MS, IMU_SAMPLE_PERIOD_MS, increment.deltaDeg / pieces)
    }
}

private fun batchRawSum(batch: ImuBatch): Vec3 = batch.samples.fold(Vec3.ZERO) { acc, s ->
    acc + Vec3(s.gx.toDouble(), s.gy.toDouble(), s.gz.toDouble()) * RAW_READINGS_PER_SAMPLE.toDouble()
}

private fun ChannelUpdate.withReading(reading: ImuReading, evidence: RestEvidence, params: ImuParams): ChannelUpdate {
    val next = channel.ingest(reading, evidence, params)
    val rate = next.yawRateDps(reading.gyroDps, params)
    val increment = rate?.let { YawIncrement(reading.tMs + HALF_SAMPLE_MS, IMU_SAMPLE_PERIOD_MS, it * IMU_SAMPLE_PERIOD_MS / 1000.0) }
    return ChannelUpdate(next, increments + listOfNotNull(increment), readings + reading)
}
