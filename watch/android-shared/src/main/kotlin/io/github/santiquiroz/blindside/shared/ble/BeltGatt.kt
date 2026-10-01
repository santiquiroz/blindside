package io.github.santiquiroz.blindside.shared.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import java.util.UUID

private const val TAG = "BeltGatt"

interface BeltGattEvents {
    fun onConnected(source: BeltGatt)
    fun onStreamReady(source: BeltGatt)
    fun onSetupFailed(source: BeltGatt, op: GattOp, status: Int)
    fun onDisconnected(source: BeltGatt, status: Int)
    fun acceptMtu(source: BeltGatt, mtu: Int?): Boolean
    fun onPacket(bytes: ByteArray, arrivalNanos: Long)
    fun onInfo(source: BeltGatt, json: String, nowNanos: Long)
    fun onRssi(source: BeltGatt, dbm: Int, nowNanos: Long)
}

@SuppressLint("MissingPermission")
class BeltGatt(
    private val context: Context,
    val device: BluetoothDevice,
    private val handler: Handler,
    private val events: BeltGattEvents,
) {
    @Volatile private var gatt: BluetoothGatt? = null
    private var queue = OpQueue()
    private var negotiatedMtu: Int? = null
    private var infoMtu: Int? = null
    private val timeoutToken = Any()

    fun connect(autoConnect: Boolean) {
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.disconnect()
    }

    fun close() {
        stopQueue()
        gatt?.close()
        gatt = null
    }

    fun requestRssi() {
        if (shouldQueueRssi(queue)) enqueueOp(GattOp.ReadRssi)
    }

    fun deactivateSession() = enqueueOp(GattOp.WriteSessionActive(false))

    private fun enqueueOp(op: GattOp) {
        val step = enqueue(queue, op)
        queue = step.queue
        step.start?.let(::execute)
    }

    private fun execute(op: GattOp) {
        if (subscriptionBlockedByMtu(op)) return stopQueue()
        handler.postAtTime({ finish(op, LOCAL_FAILURE_STATUS) }, timeoutToken, SystemClock.uptimeMillis() + timeoutMsFor(op))
        if (!start(op)) finish(op, LOCAL_FAILURE_STATUS)
    }

    // Spec §5.2: the ESP32 never notifies below MTU 247, so the CCCD write waits for the MTU verdict.
    private fun subscriptionBlockedByMtu(op: GattOp): Boolean =
        op == GattOp.EnableStreamNotify && !events.acceptMtu(this, effectiveMtu(negotiatedMtu, infoMtu))

    private fun start(op: GattOp): Boolean {
        val current = gatt ?: return false
        return when (op) {
            GattOp.DiscoverServices -> current.discoverServices()
            is GattOp.RequestMtu -> current.requestMtu(op.mtu)
            GattOp.ReadInfo -> characteristic(current, INFO_UUID)?.let { current.readCharacteristic(it) } ?: false
            GattOp.EnableStreamNotify -> enableStreamNotify(current)
            is GattOp.WriteSessionActive -> writeControl(current, sessionActiveCommand(op.active))
            GattOp.ReadRssi -> current.readRemoteRssi()
        }
    }

    private fun finish(op: GattOp, status: Int) {
        if (queue.inFlight != op) return
        handler.removeCallbacksAndMessages(timeoutToken)
        logResult(op, status)
        val effects = effectsAfter(op, status)
        if (SetupEffect.DISCONNECT in effects) return events.onSetupFailed(this, op, status)
        applyEffects(effects)
        advanceQueue()
    }

    private fun advanceQueue() {
        val step = completeInFlight(queue)
        queue = step.queue
        step.start?.let(::execute)
    }

    private fun stopQueue() {
        handler.removeCallbacksAndMessages(timeoutToken)
        queue = OpQueue()
    }

    private fun logResult(op: GattOp, status: Int) {
        if (op != GattOp.ReadRssi) Log.i(TAG, "$op -> status $status")
    }

    private fun applyEffects(effects: List<SetupEffect>) {
        if (SetupEffect.REPORT_LINK_UP in effects) events.onStreamReady(this)
        if (SetupEffect.LOWER_PRIORITY in effects) gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
    }

    private fun handleConnectionState(status: Int, newState: Int) {
        if (gatt == null) return
        val connected = newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS
        if (connected) handleConnected() else events.onDisconnected(this, status)
    }

    private fun handleConnected() {
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        events.onConnected(this)
        enqueueOp(GattOp.DiscoverServices)
    }

    private fun rediscover() {
        if (gatt == null) return
        stopQueue()
        enqueueOp(GattOp.DiscoverServices)
    }

    private fun handleDiscovery(status: Int) {
        if (queue.inFlight != GattOp.DiscoverServices) return
        val result = if (hasBlindsideService()) status else LOCAL_FAILURE_STATUS
        finish(GattOp.DiscoverServices, result)
        if (result == GATT_SUCCESS_STATUS) setupOpsAfterDiscovery().forEach(::enqueueOp)
    }

    private fun handleMtu(mtu: Int, status: Int) {
        if (status == GATT_SUCCESS_STATUS) negotiatedMtu = mtu
        finish(GattOp.RequestMtu(REQUESTED_MTU), status)
    }

    private fun handleInfoRead(value: ByteArray, status: Int, nowNanos: Long) {
        if (queue.inFlight != GattOp.ReadInfo) return
        if (status == GATT_SUCCESS_STATUS) onInfoJson(value.toString(Charsets.UTF_8), nowNanos)
        finish(GattOp.ReadInfo, status)
    }

    private fun onInfoJson(json: String, nowNanos: Long) {
        infoMtu = mtuFromInfo(json)
        events.onInfo(this, json, nowNanos)
    }

    private fun handleControlWrite(status: Int) {
        val op = queue.inFlight as? GattOp.WriteSessionActive ?: return
        finish(op, status)
    }

    private fun handleRssi(rssi: Int, status: Int, nowNanos: Long) {
        if (queue.inFlight != GattOp.ReadRssi) return
        if (status == GATT_SUCCESS_STATUS) events.onRssi(this, rssi, nowNanos)
        finish(GattOp.ReadRssi, status)
    }

    private fun enableStreamNotify(current: BluetoothGatt): Boolean {
        val stream = characteristic(current, STREAM_UUID) ?: return false
        val cccd = stream.getDescriptor(CCCD_UUID) ?: return false
        if (!current.setCharacteristicNotification(stream, true)) return false
        return current.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
    }

    private fun writeControl(current: BluetoothGatt, bytes: ByteArray): Boolean {
        val control = characteristic(current, CONTROL_UUID) ?: return false
        return current.writeCharacteristic(control, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
            BluetoothStatusCodes.SUCCESS
    }

    private fun characteristic(current: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? =
        current.getService(SERVICE_UUID)?.getCharacteristic(uuid)

    private fun hasBlindsideService(): Boolean {
        val current = gatt ?: return false
        return REQUIRED_CHARACTERISTICS.all { characteristic(current, it) != null }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post { handleConnectionState(status, newState) }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post { handleDiscovery(status) }
        }

        override fun onServiceChanged(g: BluetoothGatt) {
            handler.post { rediscover() }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post { handleMtu(mtu, status) }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            val now = SystemClock.elapsedRealtimeNanos()
            handler.post { handleInfoRead(value, status, now) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            handler.post { finish(GattOp.EnableStreamNotify, status) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post { handleControlWrite(status) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val arrival = SystemClock.elapsedRealtimeNanos()
            if (gatt != null && c.uuid == STREAM_UUID) events.onPacket(value.copyOf(), arrival)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            val now = SystemClock.elapsedRealtimeNanos()
            handler.post { handleRssi(rssi, status, now) }
        }
    }
}
