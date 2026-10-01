package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.scene.Warning

internal fun warningsAt(state: PipelineState, nowNanos: Long, config: PipelineConfig): Set<Warning> {
    val linkUp = isLinkUp(state, nowNanos, config)
    return linkWarnings(linkUp) + sensorWarnings(state, linkUp, config) + sessionWarnings(state, nowNanos, config)
}

private fun linkWarnings(linkUp: Boolean): Set<Warning> = if (linkUp) emptySet() else setOf(Warning.LINK_LOST)

private fun sensorWarnings(state: PipelineState, linkUp: Boolean, config: PipelineConfig): Set<Warning> {
    if (!linkUp) return emptySet()
    val usable = usableImus(state)
    return buildSet {
        if (aliveRadars(state, config).size < config.mounts.size) add(Warning.RADAR_DOWN)
        if (usable.size == 1) add(Warning.IMU_DOWN)
        if (usable.isEmpty()) add(Warning.NO_IMU_COMPENSATION)
        if (usable.isNotEmpty() && usable.none { isYawCalibrated(state, it) }) add(Warning.YAW_UNCALIBRATED)
    }
}

private fun sessionWarnings(state: PipelineState, nowNanos: Long, config: PipelineConfig): Set<Warning> {
    val status = config.tuning.status
    return buildSet {
        if (state.motion.prone) add(Warning.PRONE)
        if (state.corruptCount(nowNanos, status.corruptWindowMs) >= status.corruptEventsThreshold) add(Warning.CORRUPT_FRAMES)
        if (state.limiter.isSaturated(nowNanos, config.tuning.alerts)) add(Warning.ALERT_OVERFLOW)
    }
}

// Spec §6.3: an unverified bias ("sin verificar") keeps the "stay still" prompt up, same as no bias at all.
private fun isYawCalibrated(state: PipelineState, imuId: Int): Boolean {
    val channel = state.imus[imuId] ?: return false
    return channel.isReady && channel.biasVerified
}
