package io.github.santiquiroz.blindside.core.sim

import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.imu.wrappedDelta
import io.github.santiquiroz.blindside.core.protocol.BundleDecoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SimulatorTest {
    @Test
    fun `the player pose integrates turns and walks`() {
        val segments = listOf(Stand(1_000), Turn(1_000, rateDps = 90.0), Walk(1_000, speedMps = 1.0))

        val end = poseAt(segments, 3_000)

        assertEquals(90.0, end.headingDeg, 1e-9)
        assertEquals(1.0, end.position.x, 1e-9)
        assertEquals(0.0, end.position.y, 1e-9)
        assertTrue(poseAt(segments, 2_500).walking)
        assertEquals(90.0, poseAt(segments, 1_500).yawRateDps, 1e-9)
    }

    @Test
    fun `after a right turn of 90 degrees a point ahead appears on the left`() {
        val pose = PlayerPose(Point2.ZERO, 90.0, 0.0, walking = false)

        val body = worldToBody(Point2(0.0, 3.0), pose)

        assertEquals(-3.0, body.x, 1e-9)
    }

    @Test
    fun `packets decode, are at most 244 bytes and arrive every 100 ms`() {
        val packets = simulate(Scenarios.crossing())

        assertEquals(85, packets.size)
        assertTrue(packets.all { it.bytes.size <= 244 })
        assertEquals(100_000_000L, packets[1].arrivalNanos - packets[0].arrivalNanos)
        val bundle = BundleDecoder.decode(packets[30].bytes)!!
        assertEquals(31, bundle.seq)
        assertEquals(simEspMs(3_100), bundle.tMs)
        assertEquals(0x0F, bundle.flags)
        assertEquals(listOf(0, 1), bundle.radarFrames.map { it.radarId })
        assertEquals(listOf(5, 5), bundle.imuBatches.map { it.samples.size })
    }

    @Test
    fun `a walking person is reported by the radar that covers it`() {
        val bundle = BundleDecoder.decode(simulate(Scenarios.crossing())[30].bytes)!!

        val radarA = bundle.radarFrames.first { it.radarId == 0 }
        assertEquals(1, radarA.targets.count { !it.isEmpty })
        assertTrue(radarA.targets.first().speedCms != 0)
    }

    @Test
    fun `a still object is not reported while the player stands`() {
        val bundle = BundleDecoder.decode(simulate(Scenarios.turningWithStillTarget())[20].bytes)!!

        assertTrue(bundle.radarFrames.all { frame -> frame.targets.all { it.isEmpty } })
    }

    @Test
    fun `gyro sums advance by four raw readings per sample`() {
        val packets = simulate(Scenarios.crossing()).map { BundleDecoder.decode(it.bytes)!! }
        val first = packets[0].imuBatches[0]
        val second = packets[1].imuBatches[0]

        val delta = wrappedDelta(second.gyroSums[2], first.gyroSums[2])

        assertEquals(second.samples.sumOf { it.gz * 4.0 }, delta, 0.0)
    }

    @Test
    fun `each imu is stamped at its block centres on its own phase`() {
        val first = BundleDecoder.decode(simulate(Scenarios.crossing())[0].bytes)!!

        assertEquals(listOf(simEspMs(10), simEspMs(17)), first.imuBatches.map { it.tFirstMs })
        assertEquals(listOf(5, 4), first.imuBatches.map { it.samples.size })
    }

    @Test
    fun `a radar frame describes the world 100 ms before its stamp`() {
        val scenario = Scenarios.crossing()
        val mount = scenario.mounts.first()

        assertEquals(radarTargets(scenario.copy(radarLatencyMs = 0), mount, 4_405), radarTargets(scenario, mount, 4_505))
    }

    @Test
    fun `the watch gyro reports the turn rate in rad per s at 10 Hz`() {
        val samples = simulateWatchGyro(Scenarios.turningWithMarcher())
        val turning = samples.filter { it.eventNanos == SIM_PHONE_START_NANOS + 5_250L * 1_000_000L }

        assertEquals(Scenarios.turningWithMarcher().durationMs / 100, samples.size.toLong())
        assertEquals(Math.toRadians(60.0), -turning.single().z.toDouble(), 1e-6)
        assertTrue(simulateWatchGyro(Scenarios.crossing().copy(watchGyroPeriodMs = null)).isEmpty())
    }

    @Test
    fun `dropped sequence numbers are not sent and a downed radar clears its flag`() {
        val scenario = Scenarios.crossing().copy(droppedSeqs = setOf(10), radarDownFromMs = mapOf(1 to 3_000L))

        val bundles = simulate(scenario).map { BundleDecoder.decode(it.bytes)!! }

        assertTrue(bundles.none { it.seq == 10 })
        val late = bundles.first { it.tMs == simEspMs(4_000) }
        assertEquals(listOf(0), late.radarFrames.map { it.radarId })
        assertEquals(0x0D, late.flags)
    }
}
