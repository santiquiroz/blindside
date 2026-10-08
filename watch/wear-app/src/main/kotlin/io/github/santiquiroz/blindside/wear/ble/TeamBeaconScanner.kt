package io.github.santiquiroz.blindside.wear.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import io.github.santiquiroz.blindside.shared.permissions.PERMISSION_BLUETOOTH_SCAN
import io.github.santiquiroz.blindside.shared.session.SessionStore
import io.github.santiquiroz.blindside.shared.tak.TEAM_BEACON_UUID
import kotlinx.coroutines.awaitCancellation

// A session companion: while it runs, nearby team beacons feed SessionStore for likely-ally matching.
@SuppressLint("MissingPermission")
suspend fun scanTeamBeacons(context: Context, clockMs: () -> Long) {
    if (!hasScanPermission(context)) return
    val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
    val uuid = ParcelUuid.fromString(TEAM_BEACON_UUID)
    val callback = beaconCallback(uuid, clockMs)
    scanner.startScan(listOf(teamFilter(uuid)), balancedSettings(), callback)
    try {
        awaitCancellation()
    } finally {
        runCatching { scanner.stopScan(callback) }
    }
}

private fun hasScanPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, PERMISSION_BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

private fun teamFilter(uuid: ParcelUuid): ScanFilter =
    ScanFilter.Builder().setServiceUuid(uuid).build()

private fun balancedSettings(): ScanSettings =
    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()

private fun beaconCallback(uuid: ParcelUuid, clockMs: () -> Long): ScanCallback =
    object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            beaconIdOf(result.scanRecord?.getServiceData(uuid))?.let { SessionStore.sawBeacon(it, result.rssi, clockMs()) }
        }
    }

// Service data holds the 4-byte big-endian beacon id; anything else is not a team beacon.
private fun beaconIdOf(data: ByteArray?): Long? {
    if (data == null || data.size != 4) return null
    return ((data[0].toLong() and 0xFF) shl 24) or
        ((data[1].toLong() and 0xFF) shl 16) or
        ((data[2].toLong() and 0xFF) shl 8) or
        (data[3].toLong() and 0xFF)
}
