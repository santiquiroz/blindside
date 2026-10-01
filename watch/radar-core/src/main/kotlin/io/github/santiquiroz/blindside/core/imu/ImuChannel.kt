package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.config.ImuParams
import io.github.santiquiroz.blindside.core.protocol.ACCEL_LSB_PER_G
import io.github.santiquiroz.blindside.core.protocol.GYRO_LSB_PER_DPS
import io.github.santiquiroz.blindside.core.protocol.IMU_SAMPLE_PERIOD_MS
import kotlin.math.abs

enum class BiasStatus { CALIBRATING, READY, DEFECTIVE }

data class ImuChannel(
    val status: BiasStatus = BiasStatus.CALIBRATING,
    val bias: Vec3? = null,
    val biasVerified: Boolean = false,
    val window: List<ImuReading> = emptyList(),
    val gravity: Vec3? = null,
    val standingUp: Vec3? = null,
    val reseeds: Int = 0,
    val lastSums: List<Long>? = null,
    val lastSampleMs: Long? = null,
    val gyroLsbPerDps: Double = GYRO_LSB_PER_DPS,
    val accelLsbPerG: Double = ACCEL_LSB_PER_G,
) {
    val isReady: Boolean get() = status == BiasStatus.READY

    fun ingest(reading: ImuReading, evidence: RestEvidence, params: ImuParams): ImuChannel {
        val withGravity = withGravity(reading, params)
        return when (status) {
            BiasStatus.READY -> withGravity.withRestReading(reading, evidence, params)
            BiasStatus.CALIBRATING, BiasStatus.DEFECTIVE -> withGravity.withBootReading(reading, evidence, params)
        }
    }

    // A new scale means the bias was measured in the wrong units: calibrate again from scratch.
    fun withScales(gyroLsbPerDps: Double, accelLsbPerG: Double): ImuChannel =
        if (gyroLsbPerDps == this.gyroLsbPerDps && accelLsbPerG == this.accelLsbPerG) this
        else ImuChannel(gyroLsbPerDps = gyroLsbPerDps, accelLsbPerG = accelLsbPerG)

    // The accelerometer reads +1 g pointing up, so a clockwise (rightward) turn is negative about "up".
    fun yawRateDps(gyroDps: Vec3, params: ImuParams): Double? {
        val b = bias ?: return null
        val g = gravity ?: return null
        if (!isReady) return null
        return -(g.normalized() dot (gyroDps - b)) * params.gyroScale
    }

    fun isProne(params: ImuParams): Boolean {
        val up = standingUp ?: return false
        val g = gravity ?: return false
        return up.angleDegTo(g) > params.proneAngleDeg
    }

    private fun withGravity(reading: ImuReading, params: ImuParams): ImuChannel {
        if (abs(reading.accelG.norm - 1.0) > params.gravityRejectG) return this
        val current = gravity ?: return copy(gravity = reading.accelG)
        val alpha = IMU_SAMPLE_PERIOD_MS / 1000.0 / params.gravityTauS
        return copy(gravity = current + (reading.accelG - current) * alpha)
    }

    private fun withBootReading(reading: ImuReading, evidence: RestEvidence, params: ImuParams): ImuChannel {
        val window = window + reading
        if (window.size < params.bootWindowSamples) return copy(window = window)
        val stillness = evidence.judge(window.first().tMs, window.last().tMs, params)
        return when (val verdict = evaluateBootWindow(window, stillness, params)) {
            is BootVerdict.Accepted -> copy(
                status = BiasStatus.READY, bias = verdict.bias, biasVerified = verdict.verified, window = emptyList(), standingUp = gravity,
            )
            BootVerdict.Moving -> copy(window = emptyList())
            BootVerdict.Defective -> copy(status = BiasStatus.DEFECTIVE, window = emptyList())
        }
    }

    private fun withRestReading(reading: ImuReading, evidence: RestEvidence, params: ImuParams): ImuChannel {
        val b = bias ?: return this
        val window = (window + reading).takeLast(params.restWindowSamples)
        if (window.size < params.restWindowSamples) return copy(window = window)
        val stillness = evidence.judge(window.first().tMs, window.last().tMs, params)
        return applyRest(evaluateRestWindow(window, b, stillness, params), b, window, params)
    }

    private fun applyRest(verdict: RestVerdict, b: Vec3, window: List<ImuReading>, params: ImuParams): ImuChannel = when (verdict) {
        RestVerdict.NotResting -> copy(window = window)
        is RestVerdict.Refine -> copy(
            window = window,
            bias = b + (verdict.mean - b) * (IMU_SAMPLE_PERIOD_MS / 1000.0 / params.restBiasTauS),
            biasVerified = biasVerified || verdict.verified,
        )
        is RestVerdict.Reseed -> copy(window = emptyList(), bias = verdict.mean, biasVerified = true, reseeds = reseeds + 1)
        RestVerdict.Unverified -> copy(window = window, biasVerified = false)
    }
}
