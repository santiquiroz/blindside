package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.imu.BiasStatus
import io.github.santiquiroz.blindside.core.scene.SensorStatus

internal val IMU_IDS = listOf(0, 1)

internal fun flagBit(flags: Int?, bit: Int): Boolean = flags != null && flags and (1 shl bit) != 0

internal fun isLinkUp(state: PipelineState, nowNanos: Long, config: PipelineConfig): Boolean {
    val last = state.lastPacketNanos ?: return false
    return state.connected && nowNanos - last <= config.tuning.status.linkStaleMs * PipelineState.NANOS_PER_MS
}

internal fun aliveRadars(state: PipelineState, config: PipelineConfig): Set<Int> =
    config.mounts.map { it.radarId }.filter { flagBit(state.flags, it) }.toSet()

internal fun isImuUsable(state: PipelineState, imuId: Int): Boolean =
    flagBit(state.flags, 2 + imuId) && state.imus[imuId]?.status != BiasStatus.DEFECTIVE

internal fun usableImus(state: PipelineState): List<Int> = IMU_IDS.filter { isImuUsable(state, it) }

internal fun radarStatuses(state: PipelineState, linkUp: Boolean, config: PipelineConfig): List<SensorStatus> =
    config.mounts.map { SensorStatus(it.radarId, linkUp && flagBit(state.flags, it.radarId)) }

internal fun imuStatuses(state: PipelineState, linkUp: Boolean): List<SensorStatus> =
    IMU_IDS.map { SensorStatus(it, linkUp && isImuUsable(state, it)) }
