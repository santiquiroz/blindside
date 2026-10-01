package io.github.santiquiroz.blindside.wear.session

import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.alerts.PipelineEvent
import io.github.santiquiroz.blindside.core.alerts.SystemAlert
import io.github.santiquiroz.blindside.core.alerts.TrackConfirmed
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.wear.haptics.HapticGate
import io.github.santiquiroz.blindside.wear.haptics.HapticSink
import io.github.santiquiroz.blindside.wear.haptics.afterSystemBuzz
import io.github.santiquiroz.blindside.wear.haptics.contactStartNanos
import io.github.santiquiroz.blindside.wear.haptics.hapticFor
import io.github.santiquiroz.blindside.wear.haptics.systemFirst
import io.github.santiquiroz.blindside.wear.recording.RecordSink
import io.github.santiquiroz.blindside.wear.recording.recordFor
import io.github.santiquiroz.blindside.wear.recording.trackConfirmedRecord
import io.github.santiquiroz.blindside.wear.recording.vibrationStartedRecord

fun interface SceneSink {
    fun publish(scene: RadarScene)
}

fun interface NanoClock {
    fun nowNanos(): Long
}

fun interface DeferredPlayback {
    fun playAt(alert: ContactAlert, atNanos: Long)
}

class SessionEngine(
    private val pipeline: PipelinePort,
    private val records: RecordSink,
    private val haptics: HapticSink,
    private val scenes: SceneSink,
    private val clock: NanoClock,
    private val startNanos: Long,
    private val deferred: DeferredPlayback,
    private val onError: (Throwable) -> Unit,
) {
    private var gate = HapticGate()
    private var eliminated = false

    fun handle(input: SessionInput) {
        try {
            process(input)
        } catch (error: Exception) {
            onError(error)
        }
    }

    fun close() = records.close()

    private fun process(input: SessionInput) {
        recordFor(input, startNanos)?.let(records::record)
        systemFirst(feed(input)).forEach(::react)
        trackEliminated(input)
        replayDeferred(input)
        publishSceneOnTick(input)
        flushOnRequest(input)
    }

    private fun feed(input: SessionInput): List<PipelineEvent> = when (input) {
        is SessionInput.Packet -> pipeline.onBlePacket(input.bytes, input.arrivalNanos)
        is SessionInput.Link -> pipeline.onLinkState(input.connected, input.nowNanos)
        else -> feedWithoutEvents(input)
    }

    private fun feedWithoutEvents(input: SessionInput): List<PipelineEvent> {
        when (input) {
            is SessionInput.Gravity -> pipeline.onWatchGravity(input.x, input.y, input.z, input.eventNanos)
            is SessionInput.Gyro -> pipeline.onWatchGyro(input.x, input.y, input.z, input.eventNanos)
            is SessionInput.Step -> pipeline.onWatchStep(input.eventNanos)
            is SessionInput.BeltInfo -> pipeline.onBeltInfo(input.json, input.nowNanos)
            is SessionInput.ModeChanged -> pipeline.setEliminated(input.eliminated)
            else -> Unit
        }
        return emptyList()
    }

    private fun react(event: PipelineEvent) {
        when (event) {
            is TrackConfirmed -> records.record(trackConfirmedRecord(event, startNanos))
            is SystemAlert -> buzzSystem(event)
            is ContactAlert -> playOrDefer(event)
        }
    }

    private fun buzzSystem(alert: SystemAlert) {
        hapticFor(alert)?.let(haptics::play)
        gate = afterSystemBuzz(gate, clock.nowNanos())
    }

    private fun playOrDefer(alert: ContactAlert) {
        val now = clock.nowNanos()
        val startAt = contactStartNanos(gate, now)
        if (startAt <= now) playContact(alert, now) else deferred.playAt(alert, startAt)
    }

    private fun playContact(alert: ContactAlert, nowNanos: Long) {
        hapticFor(alert)?.let(haptics::play)
        records.record(vibrationStartedRecord(alert, nowNanos, startNanos))
    }

    private fun trackEliminated(input: SessionInput) {
        if (input is SessionInput.ModeChanged) eliminated = input.eliminated
    }

    private fun replayDeferred(input: SessionInput) {
        if (input is SessionInput.PlayDeferred && !eliminated) playOrDefer(input.alert)
    }

    private fun publishSceneOnTick(input: SessionInput) {
        if (input is SessionInput.Tick) scenes.publish(pipeline.scene(input.nowNanos))
    }

    private fun flushOnRequest(input: SessionInput) {
        if (input == SessionInput.Flush) records.flush()
    }
}
