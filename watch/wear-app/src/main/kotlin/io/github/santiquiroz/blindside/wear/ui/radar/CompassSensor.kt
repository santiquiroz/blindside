package io.github.santiquiroz.blindside.wear.ui.radar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import io.github.santiquiroz.blindside.shared.compass.CompassReading
import io.github.santiquiroz.blindside.shared.compass.azimuthFromRotationVector
import io.github.santiquiroz.blindside.shared.compass.compassTrust

@Composable
fun rememberCompassReading(active: Boolean): State<CompassReading?> {
    val context = LocalContext.current
    val reading = remember { mutableStateOf<CompassReading?>(null) }
    // Start/stop, not composition: a Sigilo screen-off without ambient or a trip to the watch face stops the activity but keeps it composed.
    LifecycleStartEffect(active) {
        val stopCompass = if (active) startCompass(context, reading) else null
        onStopOrDispose {
            stopCompass?.invoke()
            reading.value = null
        }
    }
    return reading
}

private fun startCompass(context: Context, reading: MutableState<CompassReading?>): (() -> Unit)? {
    val manager = context.getSystemService(SensorManager::class.java) ?: return null
    val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return null
    val listener = CompassListener { reading.value = it }
    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
    return { manager.unregisterListener(listener) }
}

private class CompassListener(private val emit: (CompassReading) -> Unit) : SensorEventListener {
    override fun onSensorChanged(event: SensorEvent) {
        emit(CompassReading(azimuthFromRotationVector(event.values), compassTrust(event.accuracy)))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
