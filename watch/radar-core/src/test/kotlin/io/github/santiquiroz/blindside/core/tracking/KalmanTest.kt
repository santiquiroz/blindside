package io.github.santiquiroz.blindside.core.tracking

import io.github.santiquiroz.blindside.core.config.TrackingParams
import io.github.santiquiroz.blindside.core.geometry.Point2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KalmanTest {
    private val params = TrackingParams()
    private val r = CvKalman.measurementNoise(3.0, 0.0, 0.0, overlap = false, params = params)

    @Test
    fun `matrix product, transpose and 2x2 inverse`() {
        val a = Matrix.of(2, 2, 4.0, 7.0, 2.0, 6.0)

        val product = a * a.inverse2x2()

        assertEquals(1.0, product[0, 0], 1e-12)
        assertEquals(0.0, product[0, 1], 1e-12)
        assertEquals(7.0, a.transpose()[1, 0], 0.0)
    }

    @Test
    fun `a new track starts still with the configured speed uncertainty`() {
        val state = CvKalman.init(Point2(1.0, 3.0), r, 1.5)

        assertEquals(Point2(1.0, 3.0), state.position)
        assertEquals(0.0, state.speed, 0.0)
        assertEquals(2.25, state.p[2, 2], 1e-12)
    }

    @Test
    fun `measurement noise is radial sigma along the line of sight and angular across it`() {
        val ahead = CvKalman.measurementNoise(3.0, 0.0, 0.0, overlap = false, params = params)

        assertEquals(0.04, ahead[1, 1], 1e-12)
        assertEquals(Math.pow(3.0 * Math.toRadians(3.5), 2.0), ahead[0, 0], 1e-12)
    }

    @Test
    fun `angular noise grows with 1 over cos theta and is capped at 70 degrees`() {
        val wide = CvKalman.measurementNoise(3.0, 80.0, 0.0, overlap = false, params = params)
        val at70 = CvKalman.measurementNoise(3.0, 70.0, 0.0, overlap = false, params = params)

        assertEquals(at70[0, 0], wide[0, 0], 1e-12)
        assertTrue(at70[0, 0] > r[0, 0] * 8)
    }

    @Test
    fun `overlap inflates the noise by 1_5`() {
        val overlap = CvKalman.measurementNoise(3.0, 0.0, 0.0, overlap = true, params = params)

        assertEquals(r[1, 1] * 1.5, overlap[1, 1], 1e-12)
    }

    @Test
    fun `a target walking at 1_2 m per s is tracked within 0_1 m per s`() {
        val final = (1..30).fold(CvKalman.init(Point2(-2.0, 3.0), r, 1.5)) { s, k ->
            val predicted = CvKalman.predict(s, 0.1, params.processNoise)
            CvKalman.update(predicted, Point2(-2.0 + 0.12 * k, 3.0), r)
        }

        assertEquals(1.2, final.velocity.x, 0.1)
        assertEquals(0.0, final.velocity.y, 0.1)
    }

    @Test
    fun `coasting decays velocity with a 1 s time constant`() {
        val moving = CvKalman.init(Point2(0.0, 3.0), r, 1.5).copy(x = Matrix.column(0.0, 3.0, 1.0, 0.0))

        val coasted = CvKalman.coast(moving, 1.0, params.processNoise, 1.0)

        assertEquals(Math.exp(-1.0), coasted.velocity.x, 1e-9)
        assertEquals(1.0 - Math.exp(-1.0), coasted.position.x, 1e-9)
    }

    @Test
    fun `mahalanobis distance of a residual equal to one sigma is one`() {
        val state = CvKalman.init(Point2(0.0, 3.0), Matrix.of(2, 2, 0.0, 0.0, 0.0, 0.0), 1.5)
        val unit = Matrix.of(2, 2, 1.0, 0.0, 0.0, 1.0)

        assertEquals(1.0, CvKalman.innovation(state, Point2(1.0, 3.0), unit).mahalanobis2, 1e-12)
    }

    @Test
    fun `the gating covariance can be capped for coasting tracks`() {
        val wide = CvKalman.init(Point2(0.0, 3.0), Matrix.of(2, 2, 4.0, 0.0, 0.0, 4.0), 1.5)
        val zero = Matrix.of(2, 2, 0.0, 0.0, 0.0, 0.0)

        val capped = CvKalman.innovation(wide, Point2(0.0, 3.0), zero, positionVarianceCap = 0.49)

        assertEquals(0.49, capped.covariance[0, 0], 1e-12)
    }
}
