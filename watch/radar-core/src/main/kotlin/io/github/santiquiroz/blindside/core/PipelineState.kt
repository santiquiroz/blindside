package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.alerts.AlertLimiter
import io.github.santiquiroz.blindside.core.alerts.PipelineEvent
import io.github.santiquiroz.blindside.core.clock.ClockMapper
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.geometry.StaleMemory
import io.github.santiquiroz.blindside.core.imu.ImuChannel
import io.github.santiquiroz.blindside.core.imu.MotionDetector
import io.github.santiquiroz.blindside.core.imu.Vec3
import io.github.santiquiroz.blindside.core.imu.WatchWitness
import io.github.santiquiroz.blindside.core.imu.YawTracker
import io.github.santiquiroz.blindside.core.protocol.LinkParams
import io.github.santiquiroz.blindside.core.protocol.SeqTracker
import io.github.santiquiroz.blindside.core.tracking.TrackerState

data class PipelineCounters(
    val packets: Long = 0,
    val malformedPackets: Long = 0,
    val truncatedPackets: Long = 0,
    val lostPackets: Long = 0,
    val implausibleTargets: Long = 0,
    val staleTargets: Long = 0,
    val nearFieldTargets: Long = 0,
    val outOfOrderFrames: Long = 0,
    val espResets: Int = 0,
    val lastLink: LinkParams? = null,
    val turningNisSum: Double = 0.0,
    val turningNisCount: Long = 0,
) {
    val meanTurningNis: Double? get() = if (turningNisCount == 0L) null else turningNisSum / turningNisCount
}

internal data class TimedCount(val nanos: Long, val count: Int)

internal data class PipelineState(
    val clock: ClockMapper,
    val seq: SeqTracker = SeqTracker(),
    val lastHeaderMs: Long? = null,
    val flags: Int? = null,
    val bootId: String? = null,
    val imus: Map<Int, ImuChannel> = mapOf(0 to ImuChannel(), 1 to ImuChannel()),
    val yaw: YawTracker = YawTracker(),
    val motion: MotionDetector = MotionDetector(),
    val lastMovingMs: Long? = null,
    val watchGyro: WatchWitness = WatchWitness(),
    val stale: Map<Int, StaleMemory> = emptyMap(),
    val tracker: TrackerState = TrackerState(),
    val limiter: AlertLimiter = AlertLimiter(),
    val connected: Boolean = false,
    val lastPacketNanos: Long? = null,
    val corruption: List<TimedCount> = emptyList(),
    val lastBadFrames: Map<Int, Int> = emptyMap(),
    val eliminated: Boolean = false,
    val watchGravity: Vec3? = null,
    val counters: PipelineCounters = PipelineCounters(),
) {
    fun markCorrupt(nanos: Long, count: Int, windowMs: Long): PipelineState {
        if (count <= 0) return this
        val kept = corruption.filter { nanos - it.nanos < windowMs * NANOS_PER_MS }
        return copy(corruption = kept + TimedCount(nanos, count))
    }

    fun corruptCount(nowNanos: Long, windowMs: Long): Int =
        corruption.filter { nowNanos - it.nanos < windowMs * NANOS_PER_MS }.sumOf { it.count }

    // Spec §6.3 and §4.2: after an ESP32 reboot every time reference restarts; display ids and handled alerts carry on.
    fun withRestartedTimeReferences(): PipelineState = copy(
        clock = ClockMapper(clock.params),
        seq = SeqTracker(),
        lastHeaderMs = null,
        imus = imus.mapValues { (_, channel) -> channel.copy(lastSums = null, lastSampleMs = null) },
        yaw = YawTracker(),
        motion = MotionDetector(),
        lastMovingMs = null,
        watchGyro = WatchWitness(),
        stale = emptyMap(),
        tracker = TrackerState(ids = tracker.ids.copy(graveyard = emptyList())),
        lastBadFrames = emptyMap(),
        counters = counters.copy(espResets = counters.espResets + 1),
    )

    companion object {
        const val NANOS_PER_MS = 1_000_000L

        fun initial(config: PipelineConfig) = PipelineState(clock = ClockMapper(config.tuning.clock))
    }
}

internal data class Stage(val state: PipelineState, val events: List<PipelineEvent> = emptyList()) {
    fun then(next: (PipelineState) -> Stage): Stage {
        val stage = next(state)
        return Stage(stage.state, events + stage.events)
    }
}
