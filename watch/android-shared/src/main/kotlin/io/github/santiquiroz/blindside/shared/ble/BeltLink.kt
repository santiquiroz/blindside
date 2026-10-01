package io.github.santiquiroz.blindside.shared.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

private const val TAG = "BeltLink"

@SuppressLint("MissingPermission")
class BeltLink(
    private val context: Context,
    private val listener: BeltListener,
    private val onBeltFound: (String) -> Unit,
) : BeltGattEvents {
    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val scanner = BeltScanner(adapter, handler, ::onScanResult)
    private var savedAddress: String? = null
    private var device: BluetoothDevice? = null
    private var gatt: BeltGatt? = null
    private var attempt = ConnectionAttempt()
    private var bondHealth = BondHealth()
    private var reporter = LinkReporter()
    private var reconnect: ReconnectState? = null
    private var directDeadlineMs: Long? = null
    private var halted: BleStatus? = null
    private var mtuRetried = false
    private var everStreamed = false
    private var running = false

    private val supervisor = object : Runnable {
        override fun run() {
            if (!running) return
            supervise(SystemClock.elapsedRealtime())
            handler.postDelayed(this, SUPERVISE_EVERY_MS)
        }
    }

    fun start(address: String?) {
        running = true
        savedAddress = address
        acquire()
        handler.post(supervisor)
    }

    fun retry() {
        if (!running) return
        halted = null
        bondHealth = BondHealth()
        mtuRetried = false
        reconnect = null
        dropConnection()
        device = null
        acquire()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(supervisor)
        scanner.stop()
        gatt?.let(::closeGracefully)
        gatt = null
        listener.onStatus(BleStatus.IDLE)
    }

    override fun onConnected(source: BeltGatt) {
        if (source !== gatt) return
        directDeadlineMs = null
        attempt = attempt.copy(connected = true)
        listener.onStatus(if (attempt.pairing) BleStatus.PAIRING else connectingStatus())
    }

    override fun onStreamReady(source: BeltGatt) {
        if (source !== gatt) return
        attempt = attempt.copy(subscribed = true)
        bondHealth = nextBondHealth(bondHealth, AttemptVerdict.SUBSCRIBED)
        mtuRetried = false
        reconnect = null
        everStreamed = true
        rememberBelt(source.device)
        listener.onStatus(BleStatus.STREAMING)
        reportLink(LinkEvent.STREAM_READY)
    }

    override fun onSetupFailed(source: BeltGatt, op: GattOp, status: Int) {
        if (source === gatt) source.disconnect()
    }

    override fun onDisconnected(source: BeltGatt, status: Int) {
        if (source !== gatt || !running) return
        reportLink(LinkEvent.DISCONNECTED)
        val verdict = attemptVerdict(attempt)
        bondHealth = nextBondHealth(bondHealth, verdict)
        when {
            verdict == AttemptVerdict.PAIRING_FAILED -> halt(BleStatus.PAIRING_FAILED)
            bondHealth.isLost -> halt(BleStatus.BOND_LOST)
            else -> resumePending()
        }
    }

    override fun acceptMtu(source: BeltGatt, mtu: Int?): Boolean {
        if (source !== gatt) return false
        return when (mtuAction(mtu, mtuRetried)) {
            MtuAction.OK -> true
            MtuAction.RETRY_ONCE -> retryForMtu(source, mtu)
            MtuAction.FAIL -> haltForMtu(mtu)
        }
    }

    override fun onPacket(bytes: ByteArray, arrivalNanos: Long) = listener.onPacket(bytes, arrivalNanos)

    override fun onInfo(source: BeltGatt, json: String, nowNanos: Long) {
        if (source === gatt) listener.onBeltInfo(json, nowNanos)
    }

    override fun onRssi(source: BeltGatt, dbm: Int, nowNanos: Long) {
        if (source === gatt) listener.onRssi(dbm, nowNanos)
    }

    private fun supervise(nowMs: Long) {
        val known = device
        when {
            halted != null -> Unit
            adapter?.isEnabled != true -> onBluetoothOff(nowMs)
            known == null -> searchIfIdle(nowMs)
            gatt == null -> connectPending(known)
            else -> superviseConnection(nowMs)
        }
    }

    private fun superviseConnection(nowMs: Long) {
        if (reporter.up) gatt?.requestRssi() else reconnectIfDue(nowMs)
        expireDirectAttempt(nowMs)
    }

    private fun onBluetoothOff(nowMs: Long) {
        listener.onStatus(BleStatus.BLUETOOTH_OFF)
        dropConnection()
        reportLink(LinkEvent.BLUETOOTH_OFF)
        markLost(nowMs)
    }

    private fun acquire() {
        val bonded = bondedBelt(savedAddress)
        if (bonded != null) useDevice(bonded) else listener.onStatus(BleStatus.SEARCHING)
    }

    private fun bondedBelt(address: String?): BluetoothDevice? {
        val bonded = adapter?.bondedDevices.orEmpty()
        return bonded.firstOrNull { it.address == address } ?: bonded.firstOrNull { isBlindsideName(it.name) }
    }

    private fun searchIfIdle(nowMs: Long) {
        if (scanner.isScanning) return
        listener.onStatus(BleStatus.SEARCHING)
        scanner.start(nowMs)
    }

    private fun onScanResult(found: BluetoothDevice) {
        if (halted != null) return
        val known = device
        when {
            known == null -> adoptScanned(found)
            found.address == known.address && mayReplaceCurrentAttempt() -> connectScanned()
        }
    }

    private fun connectScanned() {
        scanner.stop()
        connectDirect(SystemClock.elapsedRealtime())
    }

    private fun adoptScanned(found: BluetoothDevice) {
        scanner.stop()
        if (found.bondState == BluetoothDevice.BOND_BONDED) useDevice(found) else beginPairing(found)
    }

    // Spec §5.2: no explicit bonding call; the encrypted info read makes Android show the passkey dialog.
    private fun beginPairing(found: BluetoothDevice) {
        device = found
        connectDirect(SystemClock.elapsedRealtime())
    }

    private fun useDevice(found: BluetoothDevice) {
        device = found
        markLost(SystemClock.elapsedRealtime())
        connectPending(found)
    }

    private fun connectPending(target: BluetoothDevice) {
        openGatt(target, autoConnect = true)
        listener.onStatus(connectingStatus())
    }

    private fun connectDirect(nowMs: Long) {
        val target = device ?: return
        openGatt(target, autoConnect = false)
        directDeadlineMs = nowMs + DIRECT_ATTEMPT_TIMEOUT_MS
    }

    private fun openGatt(target: BluetoothDevice, autoConnect: Boolean) {
        gatt?.close()
        attempt = ConnectionAttempt(pairing = target.bondState != BluetoothDevice.BOND_BONDED)
        gatt = BeltGatt(context, target, handler, this).also { it.connect(autoConnect) }
    }

    private fun resumePending() {
        markLost(SystemClock.elapsedRealtime())
        directDeadlineMs = null
        device?.let(::connectPending)
    }

    private fun reconnectIfDue(nowMs: Long) {
        val state = reconnect ?: return
        if (!mayReplaceCurrentAttempt()) return
        val action = nextReconnectAction(state, nowMs) ?: return
        reconnect = recordAction(state, action, nowMs)
        if (action == ReconnectAction.DIRECT_CONNECT) connectDirect(nowMs) else scanner.start(nowMs)
    }

    private fun expireDirectAttempt(nowMs: Long) {
        val deadline = directDeadlineMs ?: return
        if (nowMs < deadline) return
        directDeadlineMs = null
        device?.let(::connectPending)
    }

    private fun retryForMtu(source: BeltGatt, mtu: Int?): Boolean {
        Log.w(TAG, "mtu $mtu below $MIN_STREAM_MTU, reconnecting once")
        mtuRetried = true
        attempt = attempt.copy(endedByApp = true)
        listener.onStatus(BleStatus.MTU_TOO_LOW)
        source.disconnect()
        return false
    }

    private fun haltForMtu(mtu: Int?): Boolean {
        Log.w(TAG, "mtu $mtu below $MIN_STREAM_MTU again, link halted")
        halt(BleStatus.MTU_TOO_LOW)
        return false
    }

    private fun halt(status: BleStatus) {
        halted = status
        scanner.stop()
        dropConnection()
        reportLink(LinkEvent.HALTED)
        listener.onStatus(status)
    }

    private fun dropConnection() {
        gatt?.close()
        gatt = null
        attempt = ConnectionAttempt()
        directDeadlineMs = null
    }

    private fun rememberBelt(found: BluetoothDevice) {
        if (found.address == savedAddress) return
        savedAddress = found.address
        onBeltFound(found.address)
    }

    private fun closeGracefully(current: BeltGatt) {
        current.deactivateSession()
        handler.postDelayed({ current.disconnect(); current.close() }, DEACTIVATE_GRACE_MS)
    }

    private fun markLost(nowMs: Long) {
        if (reconnect == null) reconnect = ReconnectState(lostAtMs = nowMs)
    }

    private fun reportLink(event: LinkEvent) {
        val step = report(reporter, event)
        reporter = step.reporter
        step.change?.let { listener.onLinkChanged(it, SystemClock.elapsedRealtimeNanos()) }
    }

    private fun mayReplaceCurrentAttempt(): Boolean = mayReplaceAttempt(attempt, reporter.up, directDeadlineMs != null)

    private fun connectingStatus(): BleStatus = if (everStreamed) BleStatus.RECONNECTING else BleStatus.CONNECTING

    private companion object {
        const val SUPERVISE_EVERY_MS = 1_000L
        const val DIRECT_ATTEMPT_TIMEOUT_MS = 12_000L
        const val DEACTIVATE_GRACE_MS = 500L
    }
}
