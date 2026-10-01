package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.TrackingParams
import io.github.santiquiroz.blindside.core.geometry.Point2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

data class KalmanState(val x: Matrix, val p: Matrix) {
    val position: Point2 get() = Point2(x[0, 0], x[1, 0])
    val velocity: Point2 get() = Point2(x[2, 0], x[3, 0])
    val speed: Double get() = velocity.norm
}

data class Innovation(val residual: Point2, val covariance: Matrix) {
    val mahalanobis2: Double
        get() {
            val nu = Matrix.column(residual.x, residual.y)
            return (nu.transpose() * covariance.inverse2x2() * nu)[0, 0]
        }
}

object CvKalman {
    private val H = Matrix.of(2, 4, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    fun init(position: Point2, r: Matrix, sigmaSpeedMps: Double): KalmanState {
        val v = sigmaSpeedMps * sigmaSpeedMps
        val p = Matrix.build(4, 4) { row, col ->
            when {
                row < 2 && col < 2 -> r[row, col]
                row == col -> v
                else -> 0.0
            }
        }
        return KalmanState(Matrix.column(position.x, position.y, 0.0, 0.0), p)
    }

    fun predict(state: KalmanState, dtS: Double, q: Double): KalmanState =
        propagate(state, transition(dtS, 1.0), dtS, q)

    fun coast(state: KalmanState, dtS: Double, q: Double, speedTauS: Double): KalmanState {
        val decay = exp(-dtS / speedTauS)
        return propagate(state, transition(speedTauS * (1.0 - decay), decay), dtS, q)
    }

    fun freezeVelocity(state: KalmanState): KalmanState =
        state.copy(x = Matrix.column(state.x[0, 0], state.x[1, 0], 0.0, 0.0))

    fun innovation(state: KalmanState, z: Point2, r: Matrix, positionVarianceCap: Double? = null): Innovation {
        val residual = z - state.position
        val pPos = capped(H * state.p * H.transpose(), positionVarianceCap)
        return Innovation(residual, pPos + r)
    }

    fun update(state: KalmanState, z: Point2, r: Matrix): KalmanState {
        val s = H * state.p * H.transpose() + r
        val k = state.p * H.transpose() * s.inverse2x2()
        val nu = Matrix.column(z.x - state.x[0, 0], z.y - state.x[1, 0])
        val i = Matrix.identity(4)
        val joseph = (i - k * H) * state.p * (i - k * H).transpose() + k * r * k.transpose()
        return KalmanState(state.x + k * nu, joseph)
    }

    fun measurementNoise(rangeM: Double, angleOffBoresightDeg: Double, bearingInFrameDeg: Double, overlap: Boolean, params: TrackingParams): Matrix {
        val cosTheta = max(cos(Math.toRadians(angleOffBoresightDeg)), cos(Math.toRadians(params.maxAngleForNoiseDeg)))
        val sigmaT = rangeM * Math.toRadians(params.sigmaAngleDeg) / cosTheta
        val phi = Math.toRadians(bearingInFrameDeg)
        val radial = Matrix.column(sin(phi), cos(phi))
        val tangential = Matrix.column(cos(phi), -sin(phi))
        val r = radial * radial.transpose() * (params.sigmaRangeM * params.sigmaRangeM) +
            tangential * tangential.transpose() * (sigmaT * sigmaT)
        return if (overlap) r * params.overlapNoiseFactor else r
    }

    private fun transition(positionGain: Double, velocityGain: Double) = Matrix.of(
        4, 4,
        1.0, 0.0, positionGain, 0.0,
        0.0, 1.0, 0.0, positionGain,
        0.0, 0.0, velocityGain, 0.0,
        0.0, 0.0, 0.0, velocityGain,
    )

    private fun propagate(state: KalmanState, f: Matrix, dtS: Double, q: Double): KalmanState =
        KalmanState(f * state.x, f * state.p * f.transpose() + processNoise(dtS, q))

    private fun processNoise(dtS: Double, q: Double): Matrix {
        val dt2 = dtS * dtS / 2.0
        val dt3 = dtS * dtS * dtS / 3.0
        return Matrix.of(
            4, 4,
            dt3, 0.0, dt2, 0.0,
            0.0, dt3, 0.0, dt2,
            dt2, 0.0, dtS, 0.0,
            0.0, dt2, 0.0, dtS,
        ) * q
    }

    private fun capped(pPos: Matrix, cap: Double?): Matrix {
        if (cap == null) return pPos
        val largest = max(pPos[0, 0], pPos[1, 1])
        return if (largest <= cap) pPos else pPos * (cap / largest)
    }
}
