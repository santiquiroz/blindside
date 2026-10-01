package io.github.santiquiroz.blindside.core.protocol

import java.io.ByteArrayOutputStream

object BundleEncoder {
    // Contract fill order (spec §4.2): IMU, STATUS and LINK first, then RADAR sorted by t_ms, as the belt sends it.
    fun encode(bundle: Bundle): ByteArray = buildBytes {
        putU8(bundle.version)
        putU8(bundle.flags)
        putU16le(bundle.seq and 0xFFFF)
        putU32le(bundle.tMs and 0xFFFFFFFFL)
        bundle.imuBatches.sortedBy { it.imuId }.forEach { putSection(TLV_IMU, imuPayload(it)) }
        if (bundle.statuses.isNotEmpty()) putSection(TLV_STATUS, statusPayload(bundle.statuses))
        bundle.links.forEach { putSection(TLV_LINK, linkPayload(it)) }
        bundle.radarFrames.sortedBy { it.tMs }.forEach { putSection(TLV_RADAR, radarPayload(it)) }
    }

    fun radarPayload(frame: RadarFrame): ByteArray = buildBytes {
        putU8(frame.radarId)
        putU32le(frame.tMs and 0xFFFFFFFFL)
        write(Ld2450Codec.encodeTargets(frame.targets.filterNot { it.isEmpty }))
    }

    fun imuPayload(batch: ImuBatch): ByteArray = buildBytes {
        putU8(batch.imuId)
        putU32le(batch.tFirstMs and 0xFFFFFFFFL)
        putU8(batch.samples.size)
        batch.samples.forEach { s -> listOf(s.ax, s.ay, s.az, s.gx, s.gy, s.gz).forEach { putU16le(it and 0xFFFF) } }
        batch.gyroSums.forEach { putU32le(it and 0xFFFFFFFFL) }
    }

    fun statusPayload(statuses: List<RadarStatus>): ByteArray = buildBytes {
        statuses.forEach { s ->
            putU8(s.radarId)
            putU16le(s.badFrames)
            putU8(s.restarts)
            putU8(s.baudIndex)
        }
    }

    fun linkPayload(link: LinkParams): ByteArray = buildBytes {
        putU16le(link.intervalUnits)
        putU16le(link.latency)
        putU16le(link.timeoutUnits)
    }

    private fun ByteArrayOutputStream.putSection(type: Int, payload: ByteArray) {
        putU8(type)
        putU8(payload.size)
        write(payload)
    }
}
