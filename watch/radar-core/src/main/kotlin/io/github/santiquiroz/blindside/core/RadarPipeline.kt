package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.alerts.PipelineEvent
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.imu.Vec3
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.scene.SceneInputs
import io.github.santiquiroz.blindside.core.scene.buildScene

class RadarPipeline(private val config: PipelineConfig) {
    private var state: PipelineState = PipelineState.initial(config)

    fun onBlePacket(bytes: ByteArray, arrivalNanos: Long): List<PipelineEvent> {
        val previousFlags = state.flags
        return commit(
            ingestPacket(state, bytes, arrivalNanos, config)
                .then { withSystemAlerts(it, radarDownAlerts(previousFlags, it.flags, config, arrivalNanos), config) },
        )
    }

    fun onWatchGravity(x: Float, y: Float, z: Float, eventNanos: Long) {
        state = state.copy(watchGravity = Vec3(x.toDouble(), y.toDouble(), z.toDouble()))
    }

    fun onWatchStep(eventNanos: Long) {
        state = withWatchStep(state, eventNanos)
    }

    fun onWatchGyro(x: Float, y: Float, z: Float, eventNanos: Long) {
        state = withWatchGyro(state, x, y, z, eventNanos, config)
    }

    fun onBeltInfo(json: String, nowNanos: Long) {
        state = withBeltInfo(state, json)
    }

    fun onLinkState(connected: Boolean, nowNanos: Long): List<PipelineEvent> {
        val alerts = linkLostAlerts(state.connected, connected, nowNanos)
        return commit(withSystemAlerts(withLinkState(state, connected), alerts, config))
    }

    fun setEliminated(on: Boolean) {
        state = state.copy(eliminated = on)
    }

    fun scene(nowNanos: Long): RadarScene {
        val linkUp = isLinkUp(state, nowNanos, config)
        val nowMs = state.clock.toEspMs(nowNanos) ?: state.lastHeaderMs ?: 0L
        val inputs = SceneInputs(
            nowMs = nowMs,
            tracks = state.tracker.tracks,
            yawDeg = state.yaw.displayYawAt(nowMs, config.tuning.imu),
            mounts = config.mounts,
            radars = radarStatuses(state, linkUp, config),
            imus = imuStatuses(state, linkUp),
            motion = state.motion.state(nowMs, config.tuning.motion),
            warnings = warningsAt(state, nowNanos, config),
            linkUp = linkUp,
            eliminated = state.eliminated,
        )
        return buildScene(inputs, config.tuning.tracking, config.tuning.decode)
    }

    fun counters(): PipelineCounters = state.counters

    private fun commit(stage: Stage): List<PipelineEvent> {
        state = stage.state
        return stage.events
    }
}
