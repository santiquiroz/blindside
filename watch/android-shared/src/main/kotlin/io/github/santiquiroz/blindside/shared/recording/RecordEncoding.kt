package io.github.santiquiroz.blindside.shared.recording

import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.alerts.TrackConfirmed
import io.github.santiquiroz.blindside.core.replay.BsrecPayloads
import io.github.santiquiroz.blindside.core.replay.BsrecRecord
import io.github.santiquiroz.blindside.core.replay.RecordType
import io.github.santiquiroz.blindside.core.replay.SessionMode
import io.github.santiquiroz.blindside.shared.session.SessionInput
import io.github.santiquiroz.blindside.shared.settings.ScreenMode

private const val NANOS_PER_MS = 1_000_000L

fun recordFor(input: SessionInput, startNanos: Long): BsrecRecord? = when (input) {
    is SessionInput.Packet -> record(RecordType.BLE_PACKET, input.arrivalNanos, startNanos, input.bytes.copyOf())
    is SessionInput.Gravity ->
        record(RecordType.WATCH_GRAVITY, input.eventNanos, startNanos, BsrecPayloads.gravity(input.x, input.y, input.z, input.eventNanos))
    is SessionInput.Gyro ->
        record(RecordType.WATCH_GYRO, input.eventNanos, startNanos, BsrecPayloads.watchGyro(input.x, input.y, input.z, input.eventNanos))
    is SessionInput.Step -> record(RecordType.WATCH_STEP, input.eventNanos, startNanos, BsrecPayloads.step(input.eventNanos))
    is SessionInput.BeltInfo -> record(RecordType.INFO_REREAD, input.nowNanos, startNanos, BsrecPayloads.info(input.json))
    is SessionInput.Rssi -> record(RecordType.RSSI, input.nowNanos, startNanos, BsrecPayloads.rssi(input.dbm))
    is SessionInput.Marker -> record(RecordType.MANUAL_MARKER, input.nowNanos, startNanos, ByteArray(0))
    is SessionInput.Link -> modeRecord(BsrecPayloads.linkChange(input.connected), input.nowNanos, startNanos)
    is SessionInput.ModeChanged ->
        modeRecord(BsrecPayloads.modeChange(screenModeToSessionMode(input.screenMode)), input.nowNanos, startNanos)
    is SessionInput.Tick, is SessionInput.PlayDeferred, SessionInput.Flush -> null
}

fun trackConfirmedRecord(event: TrackConfirmed, startNanos: Long): BsrecRecord =
    record(RecordType.TRACK_CONFIRMED, event.tNanos, startNanos, BsrecPayloads.trackConfirmed(event.displayId))

fun vibrationStartedRecord(alert: ContactAlert, startedNanos: Long, startNanos: Long): BsrecRecord =
    record(RecordType.VIBRATION_STARTED, startedNanos, startNanos, BsrecPayloads.vibrationStarted(alert.displayId, alert.side))

fun screenModeToSessionMode(screenMode: ScreenMode): SessionMode =
    if (screenMode == ScreenMode.VISTA) SessionMode.VIEW else SessionMode.STEALTH

fun msSinceStart(eventNanos: Long, startNanos: Long): Long =
    ((eventNanos - startNanos) / NANOS_PER_MS).coerceAtLeast(0L)

private fun modeRecord(payload: ByteArray, nowNanos: Long, startNanos: Long): BsrecRecord =
    record(RecordType.MODE_CHANGE, nowNanos, startNanos, payload)

private fun record(type: RecordType, nanos: Long, startNanos: Long, payload: ByteArray): BsrecRecord =
    BsrecRecord(type, msSinceStart(nanos, startNanos), payload)
