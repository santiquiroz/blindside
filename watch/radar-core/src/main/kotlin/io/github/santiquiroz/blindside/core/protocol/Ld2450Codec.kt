package io.github.santiquiroz.blindside.core.protocol

import kotlin.math.abs

data class RawTarget(val xMm: Int, val yMm: Int, val speedCms: Int, val resolutionMm: Int) {
    val isEmpty: Boolean get() = xMm == 0 && yMm == 0 && speedCms == 0
}

object Ld2450Codec {
    const val TARGET_COUNT = 3
    const val TARGET_BYTES = 8
    const val BLOCK_BYTES = TARGET_COUNT * TARGET_BYTES
    private const val SIGN_BIT = 0x8000
    private const val MAGNITUDE_MASK = 0x7FFF

    // LD2450 quirk: bit 15 set means POSITIVE (sign-magnitude, not two's complement).
    fun decodeSignMagnitude(raw: Int): Int {
        val magnitude = raw and MAGNITUDE_MASK
        return if (raw and SIGN_BIT != 0) magnitude else -magnitude
    }

    fun encodeSignMagnitude(value: Int): Int {
        require(abs(value) <= MAGNITUDE_MASK) { "magnitude out of range: $value" }
        return if (value > 0) SIGN_BIT or value else -value
    }

    fun decodeTargets(bytes: ByteArray, offset: Int): List<RawTarget> =
        List(TARGET_COUNT) { index -> decodeTarget(bytes, offset + index * TARGET_BYTES) }

    fun encodeTargets(targets: List<RawTarget>): ByteArray {
        require(targets.size <= TARGET_COUNT) { "at most $TARGET_COUNT targets" }
        val padded = targets + List(TARGET_COUNT - targets.size) { RawTarget(0, 0, 0, 0) }
        return buildBytes { padded.forEach { putTarget(it) } }
    }

    private fun decodeTarget(bytes: ByteArray, at: Int) = RawTarget(
        xMm = decodeSignMagnitude(bytes.u16le(at)),
        yMm = decodeSignMagnitude(bytes.u16le(at + 2)),
        speedCms = decodeSignMagnitude(bytes.u16le(at + 4)),
        resolutionMm = bytes.u16le(at + 6),
    )

    private fun java.io.ByteArrayOutputStream.putTarget(target: RawTarget) {
        putU16le(encodeSignMagnitude(target.xMm))
        putU16le(encodeSignMagnitude(target.yMm))
        putU16le(encodeSignMagnitude(target.speedCms))
        putU16le(target.resolutionMm)
    }
}
