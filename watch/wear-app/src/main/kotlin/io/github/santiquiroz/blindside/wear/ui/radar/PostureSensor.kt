package io.github.santiquiroz.blindside.wear.ui.radar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import io.github.santiquiroz.blindside.shared.sensors.GravityTemplate
import io.github.santiquiroz.blindside.shared.sensors.PostureDetectorState
import io.github.santiquiroz.blindside.shared.sensors.Vec3
import io.github.santiquiroz.blindside.shared.sensors.angleBetweenDeg
import io.github.santiquiroz.blindside.shared.sensors.stepPostureDetector

// AUTO without a calibrated grip never rotates: no template, or inactive, means nothing is registered and it reports false.
@Composable
fun rememberTacticalPosture(active: Boolean, template: GravityTemplate?): State<Boolean> {
    val context = LocalContext.current
    val tactical = remember { mutableStateOf(false) }
    LifecycleStartEffect(active, template) {
        val stopGravity = if (active && template != null) startGravity(context, template, tactical) else null
        onStopOrDispose {
            stopGravity?.invoke()
            tactical.value = false
        }
    }
    return tactical
}

private fun startGravity(context: Context, template: GravityTemplate, tactical: MutableState<Boolean>): (() -> Unit)? {
    val manager = context.getSystemService(SensorManager::class.java) ?: return null
    val sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: return null
    val listener = GravityListener(template) { tactical.value = it }
    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
    return { manager.unregisterListener(listener) }
}

private class GravityListener(template: GravityTemplate, private val emit: (Boolean) -> Unit) : SensorEventListener {
    private val reference = Vec3(template.x, template.y, template.z)
    private var state = PostureDetectorState()

    override fun onSensorChanged(event: SensorEvent) {
        val angle = angleBetweenDeg(sampleVector(event), reference)
        state = stepPostureDetector(state, angle, SystemClock.elapsedRealtime())
        emit(state.tactical)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

private fun sampleVector(event: SensorEvent): Vec3 = Vec3(event.values[0], event.values[1], event.values[2])
