package io.github.santiquiroz.blindside.core.imu

import io.github.santiquiroz.blindside.core.config.ImuParams
import io.github.santiquiroz.blindside.core.protocol.ImuBatch
import io.github.santiquiroz.blindside.core.protocol.ImuSample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YawTest {
    private val params = ImuParams()
    private val ready = ImuChannel(status = BiasStatus.READY, bias = Vec3.ZERO, gravity = Vec3(0.0, 0.0, 1.0))
    private val evidence = RestEvidence()

    @Test
    fun `a right turn at 10 deg per s adds 1 degree over five samples`() {
        val update = ready.ingestBatch(batch(tFirst = 1_000, gzLsb = -655, sums = sums(0)), evidence, params)

        assertEquals(5, update.increments.size)
        assertEquals(1.0, update.increments.sumOf { it.deltaDeg }, 1e-6)
        assertEquals(1_080L, update.channel.lastSampleMs)
    }

    @Test
    fun `each increment covers the 20 ms block centred on its sample`() {
        val update = ready.ingestBatch(batch(tFirst = 1_000, gzLsb = -655, sums = sums(0)), evidence, params)

        assertEquals(listOf(1_010L, 1_030L, 1_050L, 1_070L, 1_090L), update.increments.map { it.tEndMs })
    }

    @Test
    fun `a lost packet is rebuilt from the cumulative sums with the bias removed`() {
        val bias = Vec3(0.0, 0.0, 2.0)
        val gapLsb = -1834
        val batchLsb = -1834
        val previous = ready.copy(bias = bias, lastSums = sums(0), lastSampleMs = 1_080)
        val afterGap = 4L * 10 * gapLsb + 4L * 5 * batchLsb

        val update = previous.ingestBatch(batch(tFirst = 1_300, gzLsb = batchLsb, sums = sums(afterGap)), evidence, params)

        val gap = update.increments.first()
        assertEquals(1_290L, gap.tEndMs)
        assertEquals(200L, gap.durationMs)
        assertEquals(6.0, gap.deltaDeg, 1e-6)
    }

    @Test
    fun `the gap uses the channel gyro scale from info`() {
        val previous = ready.copy(lastSums = sums(0), lastSampleMs = 1_080, gyroLsbPerDps = 32.8)
        val afterGap = 4L * 10 * -328

        val update = previous.ingestBatch(batch(tFirst = 1_300, gzLsb = 0, sums = sums(afterGap)), evidence, params)

        assertEquals(2.0, update.increments.first().deltaDeg, 1e-6)
    }

    @Test
    fun `sums that wrap past 2 to the 32 still give the right difference`() {
        assertEquals(-40.0, wrappedDelta(now = 0xFFFFFFD8L, previous = 0L), 0.0)
        assertEquals(32.0, wrappedDelta(now = 16L, previous = 0xFFFFFFF0L), 0.0)
    }

    @Test
    fun `an uncalibrated channel yields no increments but remembers its sums`() {
        val update = ImuChannel().ingestBatch(batch(tFirst = 1_000, gzLsb = -655, sums = sums(123)), evidence, params)

        assertTrue(update.increments.isEmpty())
        assertEquals(sums(123), update.channel.lastSums)
    }

    @Test
    fun `two imus are averaged on the shared 20 ms grid and a single imu passes through`() {
        val a = listOf(YawIncrement(20, 20, 1.0), YawIncrement(40, 20, 1.0))
        val b = listOf(YawIncrement(27, 20, 3.0))

        val merged = mergeIncrements(listOf(a, b))

        assertEquals(listOf(20L to 1.0, 40L to 2.0), merged.map { it.tEndMs to it.deltaDeg })
    }

    @Test
    fun `two imus 7 ms apart turning 1 degree per sample read 50 degrees, not 100`() {
        val a = (1..50).map { YawIncrement(1_000L + it * 20, 20, 1.0) }
        val b = (1..50).map { YawIncrement(1_007L + it * 20, 20, 1.0) }

        val tracker = (0 until 10).fold(YawTracker()) { t, chunk ->
            val slice = { list: List<YawIncrement> -> list.subList(chunk * 5, chunk * 5 + 5) }
            t.apply(mergeIncrements(listOf(slice(a), slice(b))), params)
        }

        assertEquals(50.0, tracker.yawAt(2_000, params), 0.5)
    }

    @Test
    fun `a rebuilt gap is spread over the grid so it averages with the other imu sample by sample`() {
        val gap = listOf(YawIncrement(100, 100, 5.0))
        val other = (1..5).map { YawIncrement(it * 20L, 20, 1.0) }

        val merged = mergeIncrements(listOf(gap, other))

        assertEquals(List(5) { 1.0 }, merged.map { it.deltaDeg })
    }

    @Test
    fun `yaw interpolates between samples and extrapolates at most 150 ms`() {
        val tracker = YawTracker().apply((1..5).map { YawIncrement(1_000L + it * 20, 20, 0.2) }, params)

        assertEquals(0.1, tracker.yawAt(1_010, params), 1e-9)
        assertEquals(1.0, tracker.yawAt(1_100, params), 1e-9)
        assertEquals(1.5, tracker.yawAt(1_150, params), 1e-9)
        assertEquals(2.5, tracker.yawAt(1_400, params), 1e-9)
    }

    @Test
    fun `history keeps only the last 3 seconds`() {
        val tracker = YawTracker().apply((1..300).map { YawIncrement(it * 20L, 20, 0.0) }, params)

        assertTrue(tracker.history.first().tMs >= 6_000 - 3_000)
    }

    @Test
    fun `a restarted tracker keeps the heading and integrates from it on a new clock`() {
        val turned = YawTracker().apply((1..5).map { YawIncrement(9_000L + it * 20, 20, 9.0) }, params)

        val restarted = turned.restarted()
        val resumed = restarted.apply(listOf(YawIncrement(1_020, 20, 1.0)), params)

        assertEquals(45.0, restarted.yawAt(1_000, params), 1e-9)
        assertTrue(restarted.history.isEmpty())
        assertEquals(46.0, resumed.yawAt(1_020, params), 1e-9)
    }

    @Test
    fun `display yaw blends a correction away with a 50 ms time constant`() {
        val steady = YawTracker().apply((1..5).map { YawIncrement(it * 20L, 20, 0.0) }, params)
        val jumped = steady.apply(listOf(YawIncrement(300, 200, 10.0)), params)

        assertEquals(0.0, jumped.displayYawAt(300, params), 1e-9)
        assertEquals(10.0, jumped.displayYawAt(700, params), 0.01)
    }
}

private fun sums(gz: Long) = listOf(0L, 0L, gz and 0xFFFFFFFFL)

private fun batch(tFirst: Long, gzLsb: Int, sums: List<Long>) =
    ImuBatch(0, tFirst, List(5) { ImuSample(0, 0, 4096, 0, 0, gzLsb) }, sums)
