package io.github.santiquiroz.blindside.shared.recording

import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.alerts.TrackConfirmed
import io.github.santiquiroz.blindside.core.replay.BsrecPayloads
import io.github.santiquiroz.blindside.core.replay.BsrecRecord
import io.github.santiquiroz.blindside.core.replay.GravitySample
import io.github.santiquiroz.blindside.core.replay.LINK_DOWN_MODE
import io.github.santiquiroz.blindside.core.replay.RecordType
import io.github.santiquiroz.blindside.core.replay.SessionMode
import io.github.santiquiroz.blindside.core.scene.Side
import io.github.santiquiroz.blindside.shared.session.SessionInput
import io.github.santiquiroz.blindside.shared.settings.ScreenMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RecordEncodingTest {
    private val start = 5_000_000_000L

    private fun at(ms: Long): Long = start + ms * 1_000_000L

    private fun utf8(record: BsrecRecord?) = record!!.payload.toString(Charsets.UTF_8)

    private fun modeOf(input: SessionInput): SessionMode? = BsrecPayloads.readModeChange(recordFor(input, start)!!.payload)

    @Test
    fun `packets are recorded raw with their offset from the session start`() {
        val bytes = byteArrayOf(1, 2, 3)
        val record = recordFor(SessionInput.Packet(bytes, at(250)), start)!!
        assertEquals(RecordType.BLE_PACKET, record.type)
        assertEquals(250L, record.tMsSinceStart)
        assertEquals(bytes.toList(), record.payload.toList())
    }

    @Test
    fun `events stamped before the session start are clamped to zero`() {
        val record = recordFor(SessionInput.Step(start - 3_000_000_000L), start)!!
        assertEquals(0L, record.tMsSinceStart)
    }

    @Test
    fun `gravity uses the radar-core payload layout`() {
        val record = recordFor(SessionInput.Gravity(0.5f, -9.8f, 1.25f, at(10)), start)!!
        assertEquals(RecordType.WATCH_GRAVITY, record.type)
        assertEquals(GravitySample(0.5f, -9.8f, 1.25f, at(10)), BsrecPayloads.readGravity(record.payload))
    }

    @Test
    fun `gyro uses its own record type with the gravity layout`() {
        val record = recordFor(SessionInput.Gyro(0.1f, 0.2f, 0.3f, at(5)), start)!!
        assertEquals(RecordType.WATCH_GYRO, record.type)
        assertEquals(GravitySample(0.1f, 0.2f, 0.3f, at(5)), BsrecPayloads.readWatchGyro(record.payload))
    }

    @Test
    fun `steps carry the event nanos`() {
        val record = recordFor(SessionInput.Step(at(30)), start)!!
        assertEquals(RecordType.WATCH_STEP, record.type)
        assertEquals(at(30), BsrecPayloads.readStep(record.payload))
    }

    @Test
    fun `belt info is recorded as utf8 json`() {
        val record = recordFor(SessionInput.BeltInfo("""{"proto":1}""", at(1)), start)
        assertEquals(RecordType.INFO_REREAD, record!!.type)
        assertEquals("""{"proto":1}""", BsrecPayloads.readInfo(record.payload))
    }

    @Test
    fun `rssi is a little-endian i16`() {
        val record = recordFor(SessionInput.Rssi(-70, at(1)), start)!!
        assertEquals(RecordType.RSSI, record.type)
        assertEquals(listOf(0xBA.toByte(), 0xFF.toByte()), record.payload.toList())
        assertEquals(-70, BsrecPayloads.readRssi(record.payload))
    }

    @Test
    fun `markers have an empty payload`() {
        val record = recordFor(SessionInput.Marker(at(2)), start)!!
        assertEquals(RecordType.MANUAL_MARKER, record.type)
        assertEquals(0, record.payload.size)
    }

    @Test
    fun `link changes are recorded as link mode names the replay understands`() {
        assertEquals(LINK_DOWN_MODE, utf8(recordFor(SessionInput.Link(false, at(1)), start)))
        assertEquals(true, BsrecPayloads.readLinkChange(recordFor(SessionInput.Link(true, at(1)), start)!!.payload))
        assertEquals(RecordType.MODE_CHANGE, recordFor(SessionInput.Link(true, at(1)), start)!!.type)
    }

    @Test
    fun `a screen-mode change records view or stealth`() {
        assertEquals(SessionMode.VIEW, modeOf(SessionInput.ModeChanged(screenMode = ScreenMode.VISTA, nowNanos = at(1))))
        assertEquals(SessionMode.STEALTH, modeOf(SessionInput.ModeChanged(screenMode = ScreenMode.SIGILO, nowNanos = at(1))))
    }

    @Test
    fun `ticks, flushes and deferred plays are not recorded`() {
        assertNull(recordFor(SessionInput.Tick(at(1)), start))
        assertNull(recordFor(SessionInput.Flush, start))
        assertNull(recordFor(SessionInput.PlayDeferred(ContactAlert(1, Side.LEFT, at(1)), at(2)), start))
    }

    @Test
    fun `vibration start records display id and side`() {
        val record = vibrationStartedRecord(ContactAlert(displayId = 7, side = Side.RIGHT, tNanos = at(1)), at(40), start)
        assertEquals(RecordType.VIBRATION_STARTED, record.type)
        assertEquals(40L, record.tMsSinceStart)
        assertEquals(listOf<Byte>(7, 0, 0, 0, 2), record.payload.toList())
    }

    @Test
    fun `track confirmations carry the display id at the event time`() {
        val record = trackConfirmedRecord(TrackConfirmed(displayId = 258, tNanos = at(90)), start)
        assertEquals(RecordType.TRACK_CONFIRMED, record.type)
        assertEquals(90L, record.tMsSinceStart)
        assertEquals(258, BsrecPayloads.readTrackConfirmed(record.payload))
    }
}
