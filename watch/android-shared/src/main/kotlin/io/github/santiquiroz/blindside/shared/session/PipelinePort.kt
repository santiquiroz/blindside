package io.github.santiquiroz.blindside.shared.session

import io.github.santiquiroz.blindside.core.RadarPipeline
import io.github.santiquiroz.blindside.core.alerts.PipelineEvent
import io.github.santiquiroz.blindside.core.scene.RadarScene

interface PipelinePort {
    fun onBlePacket(bytes: ByteArray, arrivalNanos: Long): List<PipelineEvent>
    fun onWatchGravity(x: Float, y: Float, z: Float, eventNanos: Long)
    fun onWatchGyro(x: Float, y: Float, z: Float, eventNanos: Long)
    fun onWatchStep(eventNanos: Long)
    fun onBeltInfo(json: String, nowNanos: Long)
    fun onLinkState(connected: Boolean, nowNanos: Long): List<PipelineEvent>
    fun setEliminated(on: Boolean)
    fun scene(nowNanos: Long): RadarScene
}

class RadarPipelineAdapter(private val pipeline: RadarPipeline) : PipelinePort {
    override fun onBlePacket(bytes: ByteArray, arrivalNanos: Long) = pipeline.onBlePacket(bytes, arrivalNanos)

    override fun onWatchGravity(x: Float, y: Float, z: Float, eventNanos: Long) =
        pipeline.onWatchGravity(x, y, z, eventNanos)

    override fun onWatchGyro(x: Float, y: Float, z: Float, eventNanos: Long) = pipeline.onWatchGyro(x, y, z, eventNanos)

    override fun onWatchStep(eventNanos: Long) = pipeline.onWatchStep(eventNanos)

    override fun onBeltInfo(json: String, nowNanos: Long) = pipeline.onBeltInfo(json, nowNanos)

    override fun onLinkState(connected: Boolean, nowNanos: Long) = pipeline.onLinkState(connected, nowNanos)

    override fun setEliminated(on: Boolean) = pipeline.setEliminated(on)

    override fun scene(nowNanos: Long) = pipeline.scene(nowNanos)
}
