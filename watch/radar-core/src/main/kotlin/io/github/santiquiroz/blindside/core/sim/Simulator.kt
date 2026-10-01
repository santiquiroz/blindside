package io.github.santiquiroz.blindside.core.sim

import io.github.santiquiroz.blindside.core.protocol.Bundle
import io.github.santiquiroz.blindside.core.protocol.BundleEncoder
import io.github.santiquiroz.blindside.core.protocol.IMU_SAMPLE_PERIOD_MS
import io.github.santiquiroz.blindside.core.protocol.ImuBatch
import io.github.santiquiroz.blindside.core.protocol.ImuSample
import io.github.santiquiroz.blindside.core.protocol.PROTOCOL_VERSION
import io.github.santiquiroz.blindside.core.protocol.RAW_READINGS_PER_SAMPLE
import io.github.santiquiroz.blindside.core.protocol.RadarFrame

data class SimPacket(val bytes: ByteArray, val arrivalNanos: Long)

data class SimWatchGyro(val x: Float, val y: Float, val z: Float, val eventNanos: Long)

const val SIM_ESP_START_MS = 10_000L
const val SIM_PHONE_START_NANOS = 1_000_000_000_000L
const val SIM_PACKET_PERIOD_MS = 100L
private const val NANOS_PER_MS = 1_000_000L
private val RADAR_PHASE_MS = mapOf(0 to 5L, 1 to 55L)
private val IMU_IDS = listOf(0, 1)

fun simEspMs(scenarioMs: Long): Long = SIM_ESP_START_MS + scenarioMs

fun simArrivalNanos(scenarioMs: Long, bleDelayMs: Long = 20): Long = SIM_PHONE_START_NANOS + (scenarioMs + bleDelayMs) * NANOS_PER_MS

fun simulate(scenario: Scenario): List<SimPacket> {
    val samples = IMU_IDS.associateWith { imuId -> imuTimeline(scenario, imuId) }
    val cuts = (1..scenario.durationMs / SIM_PACKET_PERIOD_MS).map { it * SIM_PACKET_PERIOD_MS }
    return cuts.mapIndexedNotNull { index, cutMs ->
        val seq = (index + 1) and 0xFFFF
        if (seq in scenario.droppedSeqs) return@mapIndexedNotNull null
        val bundle = bundleAt(scenario, cutMs, seq, samples)
        SimPacket(BundleEncoder.encode(bundle), simArrivalNanos(cutMs, scenario.bleDelayMs))
    }
}

// TYPE_GYROSCOPE on the watch: rad/s, delivered without the BLE delay.
fun simulateWatchGyro(scenario: Scenario): List<SimWatchGyro> {
    val period = scenario.watchGyroPeriodMs ?: return emptyList()
    return (period / 2 until scenario.durationMs step period).map { tMs ->
        val rate = Math.toRadians(poseAt(scenario.player, tMs).yawRateDps).toFloat()
        SimWatchGyro(0f, 0f, -rate, SIM_PHONE_START_NANOS + tMs * NANOS_PER_MS)
    }
}

private data class TimedSample(val centreMs: Long, val sample: ImuSample, val sums: List<Long>) {
    val blockEndMs: Long get() = centreMs + IMU_SAMPLE_PERIOD_MS / 2
}

// Each IMU task runs on its own phase: block k covers [phase + 20k, phase + 20k + 20) and is stamped at its centre.
private fun imuTimeline(scenario: Scenario, imuId: Int): List<TimedSample> {
    val phase = scenario.imuPhaseOffsetMs[imuId]
    val centres = (phase + IMU_SAMPLE_PERIOD_MS / 2..scenario.durationMs - IMU_SAMPLE_PERIOD_MS / 2 step IMU_SAMPLE_PERIOD_MS).toList()
    val raw = centres.map { imuSample(scenario, imuId, it) }
    val sums = raw.runningFold(listOf(0L, 0L, 0L)) { acc, s ->
        listOf(acc[0] + s.gx * RAW_READINGS_PER_SAMPLE, acc[1] + s.gy * RAW_READINGS_PER_SAMPLE, acc[2] + s.gz * RAW_READINGS_PER_SAMPLE)
    }.drop(1)
    return centres.indices.map { TimedSample(centres[it], raw[it], sums[it].map { v -> v and 0xFFFFFFFFL }) }
}

private fun bundleAt(scenario: Scenario, cutMs: Long, seq: Int, samples: Map<Int, List<TimedSample>>): Bundle {
    val fromMs = cutMs - SIM_PACKET_PERIOD_MS
    val radarsUp = scenario.mounts.map { it.radarId }.filter { isRadarUp(scenario, it, cutMs) }
    val frames = scenario.mounts.filter { it.radarId in radarsUp }.map { mount ->
        val tMs = fromMs + (RADAR_PHASE_MS[mount.radarId] ?: 0L)
        RadarFrame(mount.radarId, simEspMs(tMs), radarTargets(scenario, mount, tMs))
    }
    val batches = IMU_IDS.mapNotNull { imuId -> batchBetween(imuId, samples.getValue(imuId), fromMs, cutMs) }
    val flags = radarsUp.fold(0b1100) { acc, id -> acc or (1 shl id) }
    return Bundle(PROTOCOL_VERSION, flags, seq, simEspMs(cutMs), frames.sortedBy { it.tMs }, batches, emptyList())
}

private fun batchBetween(imuId: Int, timeline: List<TimedSample>, fromMs: Long, toMs: Long): ImuBatch? {
    val inside = timeline.filter { it.blockEndMs > fromMs && it.blockEndMs <= toMs }
    if (inside.isEmpty()) return null
    return ImuBatch(imuId, simEspMs(inside.first().centreMs), inside.map { it.sample }, inside.last().sums)
}

private fun isRadarUp(scenario: Scenario, radarId: Int, cutMs: Long): Boolean {
    val downFrom = scenario.radarDownFromMs[radarId] ?: return true
    return cutMs < downFrom
}
