package io.github.santiquiroz.blindside.phone.tak

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.santiquiroz.blindside.shared.tak.GeoFix
import io.github.santiquiroz.blindside.shared.tactical.GeoPoint

private const val TAG = "TakLocation"
private const val GPS_MIN_TIME_MS = 1_000L
private const val GPS_MIN_DISTANCE_M = 0f

class TakLocation(context: Context, private val onFix: (GeoFix, Long) -> Unit) {
    private val app = context.applicationContext
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onFix(GeoFix(GeoPoint(location.latitude, location.longitude), location.accuracy.toDouble()), System.currentTimeMillis())
        }
    }
    private var started = false

    fun start(): Boolean {
        if (!hasPermission()) return false
        val manager = app.getSystemService(LocationManager::class.java) ?: return false
        return runCatching {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_MIN_TIME_MS, GPS_MIN_DISTANCE_M, listener, app.mainLooper)
            started = true
            true
        }.getOrElse { error ->
            Log.w(TAG, "gps updates failed", error)
            false
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { app.getSystemService(LocationManager::class.java)?.removeUpdates(listener) }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
