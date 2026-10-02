package io.github.santiquiroz.blindside.wear.ui.radar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import io.github.santiquiroz.blindside.shared.tactical.GeoPoint

private val FIX_PROVIDERS = listOf(
    LocationManager.GPS_PROVIDER,
    LocationManager.FUSED_PROVIDER,
    LocationManager.NETWORK_PROVIDER,
)

// A long-press reads the watch's last known fix; without the permission or a fix the mark shows "Sin GPS" (phone bridge is a later lever).
@Composable
fun rememberLastFix(): () -> GeoPoint? {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(LocationManager::class.java) }
    WarmFix(context, manager)
    return remember(manager) { { lastFix(context, manager) } }
}

// While the radar is on, one current-location request warms the provider cache so a later long-press has a fresh fix.
@Composable
private fun WarmFix(context: Context, manager: LocationManager?) {
    LifecycleStartEffect(manager) {
        val signal = CancellationSignal()
        warmProvider(context, manager, signal)
        onStopOrDispose { signal.cancel() }
    }
}

private fun warmProvider(context: Context, manager: LocationManager?, signal: CancellationSignal) {
    if (manager == null || !hasLocationPermission(context)) return
    val provider = availableProviders(manager).firstOrNull() ?: return
    runCatching { manager.getCurrentLocation(provider, signal, context.mainExecutor) { } }
}

private fun lastFix(context: Context, manager: LocationManager?): GeoPoint? {
    if (manager == null || !hasLocationPermission(context)) return null
    return availableProviders(manager).firstNotNullOfOrNull { manager.lastKnownOrNull(it) }?.let(::geoPointOf)
}

private fun availableProviders(manager: LocationManager): List<String> =
    FIX_PROVIDERS.filter { it in manager.allProviders }

private fun LocationManager.lastKnownOrNull(provider: String): Location? =
    runCatching { getLastKnownLocation(provider) }.getOrNull()

private fun geoPointOf(location: Location): GeoPoint = GeoPoint(location.latitude, location.longitude)

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
