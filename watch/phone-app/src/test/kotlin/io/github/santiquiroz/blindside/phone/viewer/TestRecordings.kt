package io.github.santiquiroz.blindside.phone.viewer

import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.config.toJson
import io.github.santiquiroz.blindside.core.protocol.MiniJson
import io.github.santiquiroz.blindside.core.replay.BsrecPayloads
import io.github.santiquiroz.blindside.core.replay.BsrecRecord
import io.github.santiquiroz.blindside.core.replay.BsrecWriter
import io.github.santiquiroz.blindside.core.replay.RecordType
import io.github.santiquiroz.blindside.core.sim.Scenario
import io.github.santiquiroz.blindside.core.sim.simulate
import io.github.santiquiroz.blindside.core.sim.simulateWatchGyro
import java.io.ByteArrayOutputStream

fun headerFor(scenario: Scenario): String = MiniJson.obj(listOf("config" to PipelineConfig(mounts = scenario.mounts).toJson()))

fun simulatedRecords(scenario: Scenario): List<BsrecRecord> {
    val packets = simulate(scenario)
    val startNanos = packets.first().arrivalNanos
    val packetRecords = packets.map { BsrecRecord(RecordType.BLE_PACKET, msSince(it.arrivalNanos, startNanos), it.bytes) }
    val gyroRecords = simulateWatchGyro(scenario).filter { it.eventNanos >= startNanos }.map {
        BsrecRecord(RecordType.WATCH_GYRO, msSince(it.eventNanos, startNanos), BsrecPayloads.watchGyro(it.x, it.y, it.z, it.eventNanos))
    }
    return (packetRecords + gyroRecords).sortedBy { it.tMsSinceStart }
}

fun recordingBytes(headerJson: String, records: List<BsrecRecord>): ByteArray {
    val out = ByteArrayOutputStream()
    val writer = BsrecWriter(out, headerJson)
    records.forEach(writer::write)
    writer.close()
    return out.toByteArray()
}

private fun msSince(nanos: Long, startNanos: Long): Long = (nanos - startNanos) / 1_000_000L
