package io.github.santiquiroz.blindside.wear.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.ParcelUuid
import android.os.SystemClock

@SuppressLint("MissingPermission")
class BeltScanner(
    private val adapter: BluetoothAdapter?,
    private val handler: Handler,
    private val onFound: (BluetoothDevice) -> Unit,
) {
    private var history = ScanHistory()
    private val stopToken = Any()
    var isScanning = false
        private set

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post { onFound(result.device) }
        }
    }

    fun start(nowMs: Long) {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (isScanning) return
        val permit = tryStartScan(history, nowMs)
        history = permit.history
        if (!permit.allowed) return
        scanner.startScan(listOf(serviceFilter()), lowLatencySettings(), callback)
        isScanning = true
        handler.postAtTime({ stop() }, stopToken, SystemClock.uptimeMillis() + SCAN_DURATION_MS)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(stopToken)
        if (!isScanning) return
        isScanning = false
        adapter?.bluetoothLeScanner?.stopScan(callback)
    }

    private fun serviceFilter(): ScanFilter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()

    private fun lowLatencySettings(): ScanSettings =
        ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

    private companion object {
        const val SCAN_DURATION_MS = 10_000L
    }
}
