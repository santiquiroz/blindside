package io.github.santiquiroz.blindside.core.replay

import io.github.santiquiroz.blindside.core.scene.Side
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BsrecTest {
    @Test
    fun `header and records round trip`() {
        val bytes = record(
            BsrecRecord(RecordType.BLE_PACKET, 10, byteArrayOf(1, 2, 3)),
            BsrecRecord(RecordType.MANUAL_MARKER, 20, ByteArray(0)),
        )

        val reader = BsrecReader(ByteArrayInputStream(bytes))
        val records = reader.records().toList()

        assertEquals("""{"proto":1}""", reader.headerJson)
        assertEquals(listOf(RecordType.BLE_PACKET, RecordType.MANUAL_MARKER), records.map { it.type })
        assertEquals(listOf(10L, 20L), records.map { it.tMsSinceStart })
        assertArrayEquals(byteArrayOf(1, 2, 3), records[0].payload)
    }

    @Test
    fun `record types 8 to 10 survive a round trip`() {
        val bytes = record(
            BsrecRecord(RecordType.INFO_REREAD, 1, BsrecPayloads.info("""{"boot_id":"9f3a12c4"}""")),
            BsrecRecord(RecordType.WATCH_GYRO, 2, BsrecPayloads.watchGyro(0.1f, 0.2f, 0.3f, 99L)),
            BsrecRecord(RecordType.RSSI, 3, BsrecPayloads.rssi(-71)),
        )

        val records = BsrecReader(ByteArrayInputStream(bytes)).records().toList()

        assertEquals(listOf(8, 9, 10), records.map { it.type.code })
    }

    @Test
    fun `the file starts with BSREC, version 1 and the header length`() {
        val bytes = record()

        assertEquals("BSREC", String(bytes, 0, 5, Charsets.US_ASCII))
        assertEquals(1, bytes[5].toInt())
        assertEquals(11, bytes[6].toInt())
    }

    @Test
    fun `a record cut by a crash ends the sequence without failing`() {
        val bytes = record(BsrecRecord(RecordType.BLE_PACKET, 10, byteArrayOf(1, 2, 3)), BsrecRecord(RecordType.BLE_PACKET, 20, ByteArray(40)))

        val records = BsrecReader(ByteArrayInputStream(bytes.copyOfRange(0, bytes.size - 10))).records().toList()

        assertEquals(1, records.size)
    }

    @Test
    fun `an unknown record type is skipped`() {
        val known = record(BsrecRecord(RecordType.MANUAL_MARKER, 5, ByteArray(0)))
        val unknown = byteArrayOf(99, 6, 0, 0, 0, 2, 0, 7, 7)

        val records = BsrecReader(ByteArrayInputStream(known + unknown + recordBytes(RecordType.WATCH_STEP, 7))).records().toList()

        assertEquals(listOf(RecordType.MANUAL_MARKER, RecordType.WATCH_STEP), records.map { it.type })
    }

    @Test
    fun `a file that is not a recording is rejected`() {
        assertThrows<IllegalArgumentException> { BsrecReader(ByteArrayInputStream("NOPE!\u0001\u0000\u0000\u0000\u0000".toByteArray())) }
    }

    @Test
    fun `sensor payload helpers round trip`() {
        val gravity = BsrecPayloads.readGravity(BsrecPayloads.gravity(0.1f, -9.7f, 1.2f, 123_456_789_000L))
        val gyro = BsrecPayloads.readWatchGyro(BsrecPayloads.watchGyro(0.5f, -0.25f, 1.5f, 987_654_321_000L))

        assertEquals(GravitySample(0.1f, -9.7f, 1.2f, 123_456_789_000L), gravity)
        assertEquals(GravitySample(0.5f, -0.25f, 1.5f, 987_654_321_000L), gyro)
        assertEquals(42L, BsrecPayloads.readStep(BsrecPayloads.step(42L)))
    }

    @Test
    fun `event and link payload helpers round trip`() {
        assertEquals(7, BsrecPayloads.readTrackConfirmed(BsrecPayloads.trackConfirmed(7)))
        assertEquals(5, BsrecPayloads.vibrationStarted(7, Side.RIGHT).size)
        assertEquals(SessionMode.ELIMINATED, BsrecPayloads.readModeChange(BsrecPayloads.modeChange(SessionMode.ELIMINATED)))
        assertEquals("""{"mtu":255}""", BsrecPayloads.readInfo(BsrecPayloads.info("""{"mtu":255}""")))
        assertEquals(listOf(0xBA.toByte(), 0xFF.toByte()), BsrecPayloads.rssi(-70).toList())
        assertEquals(-71, BsrecPayloads.readRssi(BsrecPayloads.rssi(-71)))
    }

    @Test
    fun `link changes are mode changes named LINK_UP and LINK_DOWN`() {
        assertEquals(LINK_UP_MODE, String(BsrecPayloads.linkChange(true), Charsets.UTF_8))
        assertEquals(false, BsrecPayloads.readLinkChange(BsrecPayloads.linkChange(false)))
        assertNull(BsrecPayloads.readLinkChange(BsrecPayloads.modeChange(SessionMode.VIEW)))
        assertNull(BsrecPayloads.readModeChange(BsrecPayloads.linkChange(true)))
    }

    @Test
    fun `a corrupt header length is refused before anything is allocated`() {
        val bytes = BSREC_MAGIC.toByteArray(Charsets.US_ASCII) + byteArrayOf(BSREC_FORMAT_VERSION.toByte(), -1, -1, -1, -1)
        assertThrows<IllegalArgumentException> { BsrecReader(ByteArrayInputStream(bytes)) }
    }

    @Test
    fun `a header longer than the caller allows is refused`() {
        assertThrows<IllegalArgumentException> { BsrecReader(ByteArrayInputStream(record()), maxHeaderBytes = 3) }
    }

    @Test
    fun `a zero-filled tail is skipped without overflowing the stack`() {
        val reader = BsrecReader(ByteArrayInputStream(record() + ByteArray(1_000_000)))
        assertEquals(0, reader.records().count())
    }

    private fun record(vararg records: BsrecRecord): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = BsrecWriter(out, """{"proto":1}""")
        records.forEach { writer.write(it) }
        writer.close()
        return out.toByteArray()
    }

    private fun recordBytes(type: RecordType, tMs: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = BsrecWriter(out, "")
        writer.write(BsrecRecord(type, tMs, BsrecPayloads.step(1L)))
        return out.toByteArray().copyOfRange(10, out.size())
    }
}
