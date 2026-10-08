package io.github.santiquiroz.blindside.phone.tak

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import io.github.santiquiroz.blindside.shared.tak.TEAM_BEACON_UUID
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.CRC32

fun beaconIdOf(deviceId: String): Long {
    val crc = CRC32()
    crc.update(deviceId.toByteArray(Charsets.UTF_8))
    return crc.value
}

class TeamBeaconAdvertiser(context: Context) {
    private val app = context.applicationContext
    private var callback: AdvertiseCallback? = null

    fun start(id: Long): Boolean {
        if (callback != null) return true
        if (!hasPermission()) return false
        val advertiser = app.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser ?: return false
        val started = object : AdvertiseCallback() {}
        return try {
            advertiser.startAdvertising(advertiseSettings(), advertiseData(id), started)
            callback = started
            true
        } catch (error: SecurityException) {
            false
        }
    }

    fun stop() {
        val started = callback ?: return
        callback = null
        runCatching { app.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(started) }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    private fun advertiseSettings(): AdvertiseSettings = AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
        .setConnectable(false)
        .build()

    private fun advertiseData(id: Long): AdvertiseData {
        val uuid = ParcelUuid(UUID.fromString(TEAM_BEACON_UUID))
        return AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(uuid)
            .addServiceData(uuid, serviceDataOf(id))
            .build()
    }

    private fun serviceDataOf(id: Long): ByteArray =
        ByteBuffer.allocate(4).putInt(id.toInt()).array()
}
