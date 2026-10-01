package io.github.santiquiroz.blindside.core.protocol

data class RadarFrame(val radarId: Int, val tMs: Long, val targets: List<RawTarget>)

data class ImuSample(val ax: Int, val ay: Int, val az: Int, val gx: Int, val gy: Int, val gz: Int)

data class ImuBatch(val imuId: Int, val tFirstMs: Long, val samples: List<ImuSample>, val gyroSums: List<Long>) {
    fun sampleTimeMs(index: Int): Long = tFirstMs + index * IMU_SAMPLE_PERIOD_MS
    val tLastMs: Long get() = sampleTimeMs(samples.size - 1)
}

data class RadarStatus(val radarId: Int, val badFrames: Int, val restarts: Int, val baudIndex: Int)

data class LinkParams(val intervalUnits: Int, val latency: Int, val timeoutUnits: Int) {
    val intervalMs: Double get() = intervalUnits * 1.25
    val timeoutMs: Int get() = timeoutUnits * 10
}

data class Bundle(
    val version: Int,
    val flags: Int,
    val seq: Int,
    val tMs: Long,
    val radarFrames: List<RadarFrame>,
    val imuBatches: List<ImuBatch>,
    val statuses: List<RadarStatus>,
    val links: List<LinkParams> = emptyList(),
    val truncated: Boolean = false,
    val skippedSections: Int = 0,
) {
    fun radarAlive(radarId: Int): Boolean = flags and (1 shl radarId) != 0
    fun imuOk(imuId: Int): Boolean = flags and (1 shl (IMU_FLAG_SHIFT + imuId)) != 0
    val dataDropped: Boolean get() = flags and FLAG_DATA_DROPPED != 0
}

const val PROTOCOL_VERSION = 1
const val HEADER_BYTES = 8
const val TLV_RADAR = 0x01
const val TLV_IMU = 0x02
const val TLV_STATUS = 0x03
const val TLV_LINK = 0x04
const val RADAR_PAYLOAD_BYTES = 29
const val IMU_FIXED_BYTES = 18
const val IMU_SAMPLE_BYTES = 12
const val STATUS_ENTRY_BYTES = 5
const val LINK_PAYLOAD_BYTES = 6
const val IMU_SAMPLE_PERIOD_MS = 20L
const val RAW_READINGS_PER_SAMPLE = 4
const val RAW_GYRO_RATE_HZ = 200.0
const val GYRO_LSB_PER_DPS = 65.5
const val ACCEL_LSB_PER_G = 4096.0
private const val IMU_FLAG_SHIFT = 2
private const val FLAG_DATA_DROPPED = 0x10
