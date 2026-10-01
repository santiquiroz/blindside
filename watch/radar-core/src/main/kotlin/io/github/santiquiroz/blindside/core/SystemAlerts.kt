package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.alerts.SystemAlert
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.scene.Warning

internal fun radarDownAlerts(previousFlags: Int?, flags: Int?, config: PipelineConfig, nowNanos: Long): List<SystemAlert> {
    if (previousFlags == null) return emptyList()
    val wentDown = config.mounts.map { it.radarId }.any { flagBit(previousFlags, it) && !flagBit(flags, it) }
    return if (wentDown) listOf(SystemAlert(Warning.RADAR_DOWN, nowNanos)) else emptyList()
}

internal fun linkLostAlerts(wasConnected: Boolean, connected: Boolean, nowNanos: Long): List<SystemAlert> =
    if (wasConnected && !connected) listOf(SystemAlert(Warning.LINK_LOST, nowNanos)) else emptyList()

// Spec §5.5: a system pattern holds back pending contact alerts until it has finished.
internal fun withSystemAlerts(state: PipelineState, alerts: List<SystemAlert>, config: PipelineConfig): Stage {
    val last = alerts.maxOfOrNull { it.tNanos } ?: return Stage(state)
    return Stage(state.copy(limiter = state.limiter.withSystemAlert(last, config.tuning.alerts)), alerts)
}
