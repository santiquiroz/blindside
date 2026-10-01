package io.github.santiquiroz.blindside.wear.ui.radar

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.santiquiroz.blindside.shared.compass.CompassReading
import io.github.santiquiroz.blindside.shared.compass.azimuthFromRotationVector
import io.github.santiquiroz.blindside.shared.compass.compassTrust
import io.github.santiquiroz.blindside.shared.compass.smoothedHeadingDeg

private const val NANOS_PER_MS = 1_000_000L

@Composable
fun rememberCompassReading(active: Boolean): State<CompassReading?> {
    val context = LocalContext.current
    val reading = remember { mutableStateOf<CompassReading?>(null) }
    DisposableEffect(active) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (!active || manager == null || sensor == null) {
            reading.value = null
            return@DisposableEffect onDispose { }
        }
        val listener = CompassListener { reading.value = it }
        // UI rate only while the radar is on screen: ambient and Sigilo screen-off unregister it, so the fusion costs nothing in a long game.
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { manager.unregisterListener(listener) }
    }
    return reading
}

private class CompassListener(private val emit: (CompassReading) -> Unit) : SensorEventListener {
    private var smoothedDeg: Double? = null
    private var lastNanos: Long? = null

    override fun onSensorChanged(event: SensorEvent) {
        val dtMs = lastNanos?.let { (event.timestamp - it) / NANOS_PER_MS } ?: 0L
        lastNanos = event.timestamp
        val next = smoothedHeadingDeg(smoothedDeg, azimuthFromRotationVector(event.values), dtMs)
        smoothedDeg = next
        emit(CompassReading(next, compassTrust(event.accuracy)))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
