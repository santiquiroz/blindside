package io.github.santiquiroz.blindside.core.protocol

object BundleDecoder {
    fun decode(bytes: ByteArray): Bundle? {
        if (bytes.size < HEADER_BYTES || bytes.u8(0) != PROTOCOL_VERSION) return null
        val scan = scanSections(bytes, HEADER_BYTES, SectionScan())
        return Bundle(
            version = bytes.u8(0),
            flags = bytes.u8(1),
            seq = bytes.u16le(2),
            tMs = bytes.u32le(4),
            radarFrames = scan.sections.filterIsInstance<Section.Radar>().map { it.frame },
            imuBatches = scan.sections.filterIsInstance<Section.Imu>().map { it.batch },
            statuses = scan.sections.filterIsInstance<Section.Status>().flatMap { it.entries },
            links = scan.sections.filterIsInstance<Section.Link>().map { it.params },
            truncated = scan.truncated,
            skippedSections = scan.skipped,
        )
    }

    private sealed interface Section {
        data class Radar(val frame: RadarFrame) : Section
        data class Imu(val batch: ImuBatch) : Section
        data class Status(val entries: List<RadarStatus>) : Section
        data class Link(val params: LinkParams) : Section
    }

    private data class SectionScan(
        val sections: List<Section> = emptyList(),
        val skipped: Int = 0,
        val truncated: Boolean = false,
    )

    private tailrec fun scanSections(bytes: ByteArray, offset: Int, scan: SectionScan): SectionScan {
        if (offset == bytes.size) return scan
        if (offset + 2 > bytes.size) return scan.copy(truncated = true)
        val length = bytes.u8(offset + 1)
        val payloadStart = offset + 2
        if (payloadStart + length > bytes.size) return scan.copy(truncated = true)
        val section = parseSection(bytes.u8(offset), bytes.copyOfRange(payloadStart, payloadStart + length))
        val next = if (section == null) scan.copy(skipped = scan.skipped + 1) else scan.copy(sections = scan.sections + section)
        return scanSections(bytes, payloadStart + length, next)
    }

    private fun parseSection(type: Int, payload: ByteArray): Section? = when (type) {
        TLV_RADAR -> parseRadar(payload)
        TLV_IMU -> parseImu(payload)
        TLV_STATUS -> parseStatus(payload)
        TLV_LINK -> parseLink(payload)
        else -> null
    }

    private fun parseRadar(payload: ByteArray): Section? {
        if (payload.size != RADAR_PAYLOAD_BYTES) return null
        val frame = RadarFrame(
            radarId = payload.u8(0),
            tMs = payload.u32le(1),
            targets = Ld2450Codec.decodeTargets(payload, 5),
        )
        return Section.Radar(frame)
    }

    private fun parseImu(payload: ByteArray): Section? {
        if (payload.size < IMU_FIXED_BYTES) return null
        val count = payload.u8(5)
        if (payload.size != IMU_FIXED_BYTES + IMU_SAMPLE_BYTES * count) return null
        val sumsAt = 6 + IMU_SAMPLE_BYTES * count
        val batch = ImuBatch(
            imuId = payload.u8(0),
            tFirstMs = payload.u32le(1),
            samples = List(count) { parseSample(payload, 6 + it * IMU_SAMPLE_BYTES) },
            gyroSums = List(3) { payload.u32le(sumsAt + it * 4) },
        )
        return Section.Imu(batch)
    }

    private fun parseSample(payload: ByteArray, at: Int) = ImuSample(
        ax = payload.i16le(at),
        ay = payload.i16le(at + 2),
        az = payload.i16le(at + 4),
        gx = payload.i16le(at + 6),
        gy = payload.i16le(at + 8),
        gz = payload.i16le(at + 10),
    )

    private fun parseStatus(payload: ByteArray): Section? {
        if (payload.isEmpty() || payload.size % STATUS_ENTRY_BYTES != 0) return null
        val entries = List(payload.size / STATUS_ENTRY_BYTES) { index ->
            val at = index * STATUS_ENTRY_BYTES
            RadarStatus(payload.u8(at), payload.u16le(at + 1), payload.u8(at + 3), payload.u8(at + 4))
        }
        return Section.Status(entries)
    }

    private fun parseLink(payload: ByteArray): Section? {
        if (payload.size != LINK_PAYLOAD_BYTES) return null
        return Section.Link(LinkParams(payload.u16le(0), payload.u16le(2), payload.u16le(4)))
    }
}
