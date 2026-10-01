package io.github.santiquiroz.blindside.phone.viewer

import io.github.santiquiroz.blindside.core.replay.BsrecPayloads
import io.github.santiquiroz.blindside.core.replay.BsrecRecord
import io.github.santiquiroz.blindside.core.replay.RecordType
import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.scene.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RecordingSummaryTest {
    private fun record(type: RecordType, tMs: Long, payload: ByteArray = ByteArray(0)) = BsrecRecord(type, tMs, payload)
    private fun confirmed(id: Int, tMs: Long) = record(RecordType.TRACK_CONFIRMED, tMs, BsrecPayloads.trackConfirmed(id))
    private fun vibrated(id: Int, tMs: Long) = record(RecordType.VIBRATION_STARTED, tMs, BsrecPayloads.vibrationStarted(id, Side.LEFT))
    private fun link(up: Boolean, tMs: Long) = record(RecordType.MODE_CHANGE, tMs, BsrecPayloads.linkChange(up))
    private fun packet(tMs: Long) = record(RecordType.BLE_PACKET, tMs, byteArrayOf(1))

    private val session = listOf(
        link(true, 0), packet(500), confirmed(1, 1_000), vibrated(1, 1_200), vibrated(1, 3_000),
        confirmed(2, 5_000), vibrated(2, 5_600), link(false, 10_000), link(true, 12_500),
        confirmed(3, 20_000), link(false, 50_000), packet(60_000),
    )

    private fun summarize(records: List<BsrecRecord>, motions: List<MotionState> = emptyList()): RecordingSummary {
        val afterRecords = records.fold(SummaryAccumulator(), ::afterRecord)
        return summaryOf(motions.fold(afterRecords, ::afterMotion))
    }

    @Test
    fun `duration, confirmations and alerts come from the records`() {
        val summary = summarize(session)
        assertEquals(60_000L, summary.durationMs)
        assertEquals(3, summary.confirmed)
        assertEquals(3.0, summary.confirmedPerMinute, 1e-9)
        assertEquals(3, summary.alerts)
    }

    @Test
    fun `each drop is a gap and an open gap runs to the end`() {
        val summary = summarize(session)
        assertEquals(2, summary.linkGaps)
        assertEquals(2_500L + 10_000L, summary.linkGapMs)
    }

    @Test
    fun `searching before the first connection is not a gap`() {
        val summary = summarize(listOf(link(false, 0), link(true, 4_000), packet(5_000)))
        assertEquals(0, summary.linkGaps)
        assertEquals(0L, summary.linkGapMs)
    }

    @Test
    fun `confirmation latency pairs each confirmation with its first buzz`() {
        val summary = summarize(session)
        assertEquals(200L, summary.latencyMedianMs)
        assertEquals(600L, summary.latencyP90Ms)
    }

    @Test
    fun `without buzzes there is no latency`() {
        assertNull(summarize(listOf(confirmed(1, 100))).latencyMedianMs)
    }

    @Test
    fun `walking and still shares come from the motion samples`() {
        val summary = summarize(emptyList(), listOf(MotionState.STILL, MotionState.STILL, MotionState.STILL, MotionState.WALKING))
        assertEquals(0.25, summary.walkingFraction, 1e-9)
        assertEquals(0.75, summary.stillFraction, 1e-9)
        assertEquals(4, summary.motionSamples)
    }

    @Test
    fun `an empty recording summarizes to zeros`() {
        val summary = summarize(emptyList())
        assertEquals(0L, summary.durationMs)
        assertEquals(0.0, summary.confirmedPerMinute)
        assertEquals(0.0, summary.walkingFraction)
    }

    @Test
    fun `nearest rank always picks a real sample`() {
        assertEquals(200L, nearestRank(listOf(200L, 600L), 50))
        assertEquals(600L, nearestRank(listOf(200L, 600L), 90))
        assertEquals(30L, nearestRank(listOf(10L, 20L, 30L, 40L), 75))
        assertNull(nearestRank(emptyList(), 50))
    }
}
