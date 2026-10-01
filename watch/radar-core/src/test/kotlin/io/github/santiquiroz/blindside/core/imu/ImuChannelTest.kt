package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.config.ImuParams
import io.github.santiquiroz.blindside.core.protocol.ImuBatch
import io.github.santiquiroz.blindside.core.protocol.ImuSample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin

class ImuChannelTest {
    private val params = ImuParams()
    private val offset = Vec3(8.0, -5.0, 3.0)
    private val up = Vec3(0.0, 0.0, 1.0)
    private val watchStill = watchAt(0.5)
    private val noWatch = RestEvidence()

    @Test
    fun `still signal with offset and 1 deg per s sway is accepted within 0_1 deg per s`() {
        val channel = feed(ImuChannel(), 0 until 100) { t -> offset + sway(t) }

        assertEquals(BiasStatus.READY, channel.status)
        assertTrue(channel.biasVerified)
        val bias = channel.bias!!
        assertEquals(offset.x, bias.x, 0.1)
        assertEquals(offset.y, bias.y, 0.1)
        assertEquals(offset.z, bias.z, 0.1)
    }

    @Test
    fun `a window where a slow 5 deg per s turn starts is rejected`() {
        val channel = feed(ImuChannel(), 0 until 100) { t -> if (t < 500) offset else offset + Vec3(0.0, 0.0, 5.0) }

        assertEquals(BiasStatus.CALIBRATING, channel.status)
        assertNull(channel.bias)
    }

    @Test
    fun `a constant 5 deg per s turn that the watch also feels is rejected`() {
        val channel = feed(ImuChannel(), 0 until 100, evidence = watchAt(5.0)) { offset + Vec3(0.0, 0.0, 5.0) }

        assertEquals(BiasStatus.CALIBRATING, channel.status)
    }

    @Test
    fun `a step inside the boot window rejects it`() {
        val channel = feed(ImuChannel(), 0 until 100, evidence = watchStill.copy(lastStepMs = 1_000)) { offset }

        assertEquals(BiasStatus.CALIBRATING, channel.status)
    }

    @Test
    fun `without a watch gyro the boot bias is accepted but unverified`() {
        val channel = feed(ImuChannel(), 0 until 100, evidence = noWatch) { offset }

        assertEquals(BiasStatus.READY, channel.status)
        assertFalse(channel.biasVerified)
    }

    @Test
    fun `a still mean above 45 deg per s marks the sensor defective, and a later good window recovers it`() {
        val defective = feed(ImuChannel(), 0 until 100, evidence = noWatch) { Vec3(50.0, 0.0, 0.0) }
        val recovered = feed(defective, 100 until 200) { offset }

        assertEquals(BiasStatus.DEFECTIVE, defective.status)
        assertEquals(BiasStatus.READY, recovered.status)
    }

    @Test
    fun `rest recalibration fires with the 8 deg per s raw offset because it uses the corrected rate`() {
        val booted = feed(ImuChannel(), 0 until 100) { offset }
        val drifted = feed(booted, 100 until 350) { offset + Vec3(0.5, 0.0, 0.0) }

        assertTrue(drifted.bias!!.x > offset.x + 0.02, "bias was ${drifted.bias}")
    }

    @Test
    fun `a bias 5 deg per s off is re-seeded in the first still 3 s window and the yaw stops drifting`() {
        val wrong = ImuChannel(status = BiasStatus.READY, bias = offset + Vec3(0.0, 0.0, 5.0), biasVerified = true, gravity = up)

        val fixed = feed(wrong, 0 until 150) { offset }

        assertEquals(1, fixed.reseeds)
        assertEquals(0.0, fixed.yawRateDps(offset, params)!!, 1e-6)
    }

    @Test
    fun `a bias 3 deg per s off after a temperature change still recalibrates`() {
        val warm = ImuChannel(status = BiasStatus.READY, bias = offset + Vec3(0.0, 0.0, 3.0), biasVerified = true, gravity = up)

        val fixed = feed(warm, 0 until 150) { offset }

        assertEquals(offset.z, fixed.bias!!.z, 1e-6)
    }

    @Test
    fun `without a watch gyro a slow 10 deg per s turn at rest never re-seeds and is flagged unverified`() {
        val booted = feed(ImuChannel(), 0 until 100) { offset }

        val turned = feed(booted, 100 until 400, evidence = noWatch) { offset + Vec3(0.0, 0.0, 10.0) }

        assertEquals(offset, turned.bias)
        assertEquals(0, turned.reseeds)
        assertFalse(turned.biasVerified)
    }

    @Test
    fun `rest recalibration does not fire while the watch feels a turn`() {
        val booted = feed(ImuChannel(), 0 until 100) { offset }

        val turning = feed(booted, 100 until 350, evidence = watchAt(4.0)) { offset + Vec3(4.0, 0.0, 0.0) }

        assertEquals(offset, turning.bias)
    }

    @Test
    fun `yaw rate is minus the projection on up so a right turn is positive`() {
        val booted = feed(ImuChannel(), 0 until 100) { offset }

        assertEquals(-10.0, booted.yawRateDps(offset + Vec3(0.0, 0.0, 10.0), params)!!, 1e-6)
    }

    @Test
    fun `yaw rate is independent of how the chip is mounted`() {
        val sideways = Vec3(1.0, 0.0, 0.0)
        val booted = feed(ImuChannel(), 0 until 100, accel = sideways) { offset }

        assertEquals(-10.0, booted.yawRateDps(offset + Vec3(10.0, 0.0, 0.0), params)!!, 1e-6)
    }

    @Test
    fun `a 32_8 LSB per deg per s scale doubles the rate read from the same raw counts`() {
        val batch = ImuBatch(0, 1_000, listOf(ImuSample(0, 0, 4096, 0, 0, 655)), listOf(0L, 0L, 0L))

        assertEquals(10.0, batch.readings().single().gyroDps.z, 1e-9)
        assertEquals(655 / 32.8, batch.readings(gyroLsbPerDps = 32.8).single().gyroDps.z, 1e-9)
    }

    @Test
    fun `a new scale from info restarts the calibration`() {
        val booted = feed(ImuChannel(), 0 until 100) { offset }

        assertEquals(booted, booted.withScales(booted.gyroLsbPerDps, booted.accelLsbPerG))
        assertEquals(BiasStatus.CALIBRATING, booted.withScales(32.8, 4096.0).status)
    }

    @Test
    fun `gravity tilting past 60 degrees from standing means prone`() {
        val booted = feed(ImuChannel(), 0 until 100) { offset }
        val lying = feed(booted, 100 until 400, accel = Vec3(1.0, 0.0, 0.0)) { offset }

        assertFalse(booted.isProne(params))
        assertTrue(lying.isProne(params))
    }

    @Test
    fun `strong accelerations are skipped by the gravity filter`() {
        val booted = feed(ImuChannel(), 0 until 100) { offset }
        val shaken = feed(booted, 100 until 110, accel = Vec3(0.0, 1.0, 1.0)) { offset }

        assertEquals(booted.gravity, shaken.gravity)
    }

    private fun sway(sample: Long): Vec3 {
        val s = sin(2 * PI * sample / 1000.0)
        return Vec3(s, s, s)
    }

    private fun watchAt(rateDps: Double) = RestEvidence(WatchWitness((0L..20_000L step 100).map { TimedValue(it, rateDps) }))

    private fun feed(
        start: ImuChannel,
        samples: IntRange,
        accel: Vec3 = up,
        evidence: RestEvidence = watchStill,
        gyro: (Long) -> Vec3,
    ): ImuChannel =
        samples.fold(start) { channel, i ->
            val tMs = i * 20L
            channel.ingest(ImuReading(tMs, gyro(tMs), accel), evidence, params)
        }
}
