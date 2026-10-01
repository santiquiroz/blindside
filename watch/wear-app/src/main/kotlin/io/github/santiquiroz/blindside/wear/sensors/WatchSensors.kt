package io.github.santiquiroz.blindside.wear.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

interface WatchSensorListener {
    fun onGravity(x: Float, y: Float, z: Float, eventNanos: Long)
    fun onGyro(x: Float, y: Float, z: Float, eventNanos: Long)
    fun onStep(eventNanos: Long)
}

data class SensorAvailability(val gravity: GravitySource, val gyro: Boolean, val steps: Boolean)

class WatchSensors(
    private val sensorManager: SensorManager,
    private val listener: WatchSensorListener,
) : SensorEventListener {
    private var lastGravityNanos: Long? = null
    private var lastGyroNanos: Long? = null
    private var lastAccelNanos: Long? = null
    private var filteredAccel: Vec3? = null

    fun start(stepsAllowed: Boolean): SensorAvailability {
        val gravity = sensorOf(Sensor.TYPE_GRAVITY)
        val accelerometer = if (gravity == null) sensorOf(Sensor.TYPE_ACCELEROMETER) else null
        val gyro = sensorOf(Sensor.TYPE_GYROSCOPE)
        val steps = if (stepsAllowed) sensorOf(Sensor.TYPE_STEP_DETECTOR) else null
        listOfNotNull(gravity, accelerometer, gyro).forEach { register(it, MOTION_PERIOD_US, MAX_REPORT_LATENCY_US) }
        steps?.let { register(it, SensorManager.SENSOR_DELAY_NORMAL, NO_BATCHING_US) }
        return SensorAvailability(gravitySourceFor(gravity != null, accelerometer != null), gyro != null, steps != null)
    }

    fun stop() = sensorManager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> emitGravity(vectorOf(event), event.timestamp)
            Sensor.TYPE_ACCELEROMETER -> onAccelerometer(vectorOf(event), event.timestamp)
            Sensor.TYPE_GYROSCOPE -> emitGyro(vectorOf(event), event.timestamp)
            Sensor.TYPE_STEP_DETECTOR -> listener.onStep(event.timestamp)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun onAccelerometer(sample: Vec3, eventNanos: Long) {
        val dt = lastAccelNanos?.let { eventNanos - it } ?: 0L
        lastAccelNanos = eventNanos
        val filtered = lowPass(filteredAccel, sample, dt, ACCEL_LOW_PASS_TAU_NANOS)
        filteredAccel = filtered
        emitGravity(filtered, eventNanos)
    }

    private fun emitGravity(value: Vec3, eventNanos: Long) {
        if (!passesGate(lastGravityNanos, eventNanos, GRAVITY_MIN_INTERVAL_NANOS)) return
        lastGravityNanos = eventNanos
        listener.onGravity(value.x, value.y, value.z, eventNanos)
    }

    private fun emitGyro(value: Vec3, eventNanos: Long) {
        if (!passesGate(lastGyroNanos, eventNanos, GYRO_MIN_INTERVAL_NANOS)) return
        lastGyroNanos = eventNanos
        listener.onGyro(value.x, value.y, value.z, eventNanos)
    }

    private fun sensorOf(type: Int): Sensor? =
        preferWakeUp(sensorManager.getDefaultSensor(type, true), sensorManager.getDefaultSensor(type))

    private fun register(sensor: Sensor, periodUs: Int, maxLatencyUs: Int) {
        sensorManager.registerListener(this, sensor, periodUs, maxLatencyUs)
    }

    private companion object {
        const val MOTION_PERIOD_US = 50_000
        const val MAX_REPORT_LATENCY_US = 200_000
        const val NO_BATCHING_US = 0
    }
}

private fun vectorOf(event: SensorEvent) = Vec3(event.values[0], event.values[1], event.values[2])
