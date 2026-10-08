package io.github.santiquiroz.blindside.phone.ui.team

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
import io.github.santiquiroz.blindside.shared.compass.compassTrust
import io.github.santiquiroz.blindside.shared.compass.smoothedHeadingDeg

@Composable
fun rememberPhoneHeading(active: Boolean): State<CompassReading?> {
    val context = LocalContext.current
    val reading = remember { mutableStateOf<CompassReading?>(null) }
    // Start/stop, not composition: leaving the tab stops updates but keeps the tab composed.
    LifecycleStartEffect(active) {
        val stop = if (active) startPhoneHeading(context, reading) else null
        onStopOrDispose {
            stop?.invoke()
            reading.value = null
        }
    }
    return reading
}

private fun startPhoneHeading(context: Context, reading: MutableState<CompassReading?>): (() -> Unit)? {
    val manager = context.getSystemService(SensorManager::class.java) ?: return null
    val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return null
    val listener = PhoneHeadingListener { reading.value = it }
    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
    return { manager.unregisterListener(listener) }
}

private class PhoneHeadingListener(private val emit: (CompassReading) -> Unit) : SensorEventListener {
    private var previousDeg: Double? = null
    private var previousNs = 0L
    private val matrix = FloatArray(9)

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        val sample = phoneHeadingDeg(matrix)
        val dtMs = if (previousNs == 0L) 0L else (event.timestamp - previousNs) / 1_000_000L
        previousNs = event.timestamp
        val smoothed = smoothedHeadingDeg(previousDeg, sample, dtMs)
        previousDeg = smoothed
        emit(CompassReading(smoothed, compassTrust(event.accuracy)))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
