package io.github.santiquiroz.blindside.core.replay

import io.github.santiquiroz.blindside.core.protocol.buildBytes
import io.github.santiquiroz.blindside.core.protocol.f32le
import io.github.santiquiroz.blindside.core.protocol.i16le
import io.github.santiquiroz.blindside.core.protocol.i32le
import io.github.santiquiroz.blindside.core.protocol.i64le
import io.github.santiquiroz.blindside.core.protocol.putF32le
import io.github.santiquiroz.blindside.core.protocol.putI64le
import io.github.santiquiroz.blindside.core.protocol.putU16le
import io.github.santiquiroz.blindside.core.protocol.putU32le
import io.github.santiquiroz.blindside.core.protocol.putU8
import io.github.santiquiroz.blindside.core.scene.Side

enum class SessionMode { ELIMINATED, STEALTH, VIEW }

// Gravity and the watch gyroscope share the contract layout 3 × f32 + i64 event nanos.
data class GravitySample(val x: Float, val y: Float, val z: Float, val eventNanos: Long)

const val LINK_UP_MODE = "LINK_UP"
const val LINK_DOWN_MODE = "LINK_DOWN"

object BsrecPayloads {
    fun gravity(x: Float, y: Float, z: Float, eventNanos: Long): ByteArray = vector(x, y, z, eventNanos)

    fun readGravity(payload: ByteArray): GravitySample = GravitySample(payload.f32le(0), payload.f32le(4), payload.f32le(8), payload.i64le(12))

    fun watchGyro(x: Float, y: Float, z: Float, eventNanos: Long): ByteArray = vector(x, y, z, eventNanos)

    fun readWatchGyro(payload: ByteArray): GravitySample = readGravity(payload)

    fun step(eventNanos: Long): ByteArray = buildBytes { putI64le(eventNanos) }

    fun readStep(payload: ByteArray): Long = payload.i64le(0)

    fun trackConfirmed(displayId: Int): ByteArray = buildBytes { putU32le(displayId.toLong() and 0xFFFFFFFFL) }

    fun readTrackConfirmed(payload: ByteArray): Int = payload.i32le(0)

    fun vibrationStarted(displayId: Int, side: Side): ByteArray = buildBytes {
        putU32le(displayId.toLong() and 0xFFFFFFFFL)
        putU8(side.ordinal)
    }

    fun modeChange(mode: SessionMode): ByteArray = mode.name.toByteArray(Charsets.UTF_8)

    fun readModeChange(payload: ByteArray): SessionMode? =
        SessionMode.entries.firstOrNull { it.name == String(payload, Charsets.UTF_8) }

    fun linkChange(connected: Boolean): ByteArray = (if (connected) LINK_UP_MODE else LINK_DOWN_MODE).toByteArray(Charsets.UTF_8)

    fun readLinkChange(payload: ByteArray): Boolean? = when (String(payload, Charsets.UTF_8)) {
        LINK_UP_MODE -> true
        LINK_DOWN_MODE -> false
        else -> null
    }

    fun info(json: String): ByteArray = json.toByteArray(Charsets.UTF_8)

    fun readInfo(payload: ByteArray): String = String(payload, Charsets.UTF_8)

    fun rssi(dbm: Int): ByteArray = buildBytes { putU16le(dbm.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()) and 0xFFFF) }

    fun readRssi(payload: ByteArray): Int = payload.i16le(0)

    private fun vector(x: Float, y: Float, z: Float, eventNanos: Long): ByteArray = buildBytes {
        putF32le(x)
        putF32le(y)
        putF32le(z)
        putI64le(eventNanos)
    }
}
