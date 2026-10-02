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

// Gyroscope Z axis: yaw rate (rad/s) about the screen normal, the turn the scene-spin washout consumes.
private const val YAW_AXIS = 2

@Composable
fun rememberYawRate(active: Boolean): State<Float> {
    val context = LocalContext.current
    val yawRate = remember { mutableStateOf(0f) }
    LifecycleStartEffect(active) {
        val stopGyro = if (active) startGyro(context, yawRate) else null
        onStopOrDispose {
            stopGyro?.invoke()
            yawRate.value = 0f
        }
    }
    return yawRate
}

private fun startGyro(context: Context, yawRate: MutableState<Float>): (() -> Unit)? {
    val manager = context.getSystemService(SensorManager::class.java) ?: return null
    val sensor = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return null
    val listener = GyroListener { yawRate.value = it }
    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
    return { manager.unregisterListener(listener) }
}

private class GyroListener(private val emit: (Float) -> Unit) : SensorEventListener {
    override fun onSensorChanged(event: SensorEvent) {
        emit(event.values[YAW_AXIS])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
