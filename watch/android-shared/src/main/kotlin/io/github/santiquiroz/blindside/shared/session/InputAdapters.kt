package io.github.santiquiroz.blindside.shared.session

import io.github.santiquiroz.blindside.shared.ble.BeltCommand
import io.github.santiquiroz.blindside.shared.ble.BeltListener
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.ble.CommandResult
import io.github.santiquiroz.blindside.shared.sensors.DeviceSensorListener
import kotlinx.coroutines.channels.SendChannel

class SensorInputs(private val inputs: SendChannel<SessionInput>) : DeviceSensorListener {
    override fun onGravity(x: Float, y: Float, z: Float, eventNanos: Long) {
        inputs.trySend(SessionInput.Gravity(x, y, z, eventNanos))
    }

    override fun onGyro(x: Float, y: Float, z: Float, eventNanos: Long) {
        inputs.trySend(SessionInput.Gyro(x, y, z, eventNanos))
    }

    override fun onStep(eventNanos: Long) {
        inputs.trySend(SessionInput.Step(eventNanos))
    }
}

class BeltInputs(private val inputs: SendChannel<SessionInput>) : BeltListener {
    override fun onPacket(bytes: ByteArray, arrivalNanos: Long) {
        inputs.trySend(SessionInput.Packet(bytes, arrivalNanos))
    }

    override fun onBeltInfo(json: String, nowNanos: Long) {
        inputs.trySend(SessionInput.BeltInfo(json, nowNanos))
    }

    override fun onRssi(dbm: Int, nowNanos: Long) {
        inputs.trySend(SessionInput.Rssi(dbm, nowNanos))
    }

    override fun onLinkChanged(connected: Boolean, nowNanos: Long) {
        inputs.trySend(SessionInput.Link(connected, nowNanos))
    }

    override fun onStatus(status: BleStatus) {
        SessionStore.update { it.copy(ble = status) }
    }

    override fun onCommandWritten(result: CommandResult, nowNanos: Long) {
        if (result.command == BeltCommand.OpenPairingWindow) SessionStore.update { recordPairingWrite(it, result.delivered, nowNanos) }
    }
}
