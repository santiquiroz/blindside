package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.clock.ClockMapper
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.imu.ImuChannel
import io.github.santiquiroz.blindside.core.protocol.ImuScale
import io.github.santiquiroz.blindside.core.protocol.parseBeltInfo
import kotlin.math.sqrt

internal fun withWatchStep(state: PipelineState, eventNanos: Long): PipelineState {
    val espMs = state.clock.toEspMs(eventNanos) ?: return state
    return state.copy(motion = state.motion.withStep(espMs))
}

// Android delivers TYPE_GYROSCOPE in rad/s; the core works in °/s on the ESP32 clock.
internal fun withWatchGyro(state: PipelineState, x: Float, y: Float, z: Float, eventNanos: Long, config: PipelineConfig): PipelineState {
    val espMs = state.clock.toEspMs(eventNanos) ?: return state
    val rateDps = Math.toDegrees(sqrt((x * x + y * y + z * z).toDouble()))
    return state.copy(
        watchGyro = state.watchGyro.withSample(espMs, rateDps, config.tuning.imu),
        motion = state.motion.withWatchRate(espMs, rateDps, config.tuning.motion),
    )
}

// Spec §6.3: a different boot_id means the ESP32 rebooted even if t_ms did not go backwards.
internal fun withBeltInfo(state: PipelineState, json: String): PipelineState {
    val info = parseBeltInfo(json) ?: return state
    val rebooted = state.bootId != null && info.bootId != null && info.bootId != state.bootId
    val reset = if (rebooted) state.withRestartedTimeReferences() else state
    return reset.copy(bootId = info.bootId ?: state.bootId, imus = withScales(reset.imus, info.imuScales))
}

// Spec §6.3: the clock mapping restarts on every (re)connection.
internal fun withLinkState(state: PipelineState, connected: Boolean): PipelineState =
    if (connected) state.copy(connected = true, clock = ClockMapper(state.clock.params)) else state.copy(connected = false)

private fun withScales(imus: Map<Int, ImuChannel>, scales: List<ImuScale>): Map<Int, ImuChannel> =
    imus.mapValues { (id, channel) ->
        scales.firstOrNull { it.imuId == id }?.let { channel.withScales(it.gyroLsbPerDps, it.accelLsbPerG) } ?: channel
    }
