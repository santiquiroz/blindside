package io.github.santiquiroz.blindside.core.clock

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

class ClockMapperTest {
    private val trueOffsetNanos = 7_000_000_000_000L
    private val ms = ClockMapper.NANOS_PER_MS

    @Test
    fun `an empty mapper cannot map`() {
        val mapper = ClockMapper()

        assertFalse(mapper.isReady)
        assertNull(mapper.toNanos(100))
        assertNull(mapper.toEspMs(100))
    }

    @Test
    fun `minimum filter removes variable BLE delay`() {
        val random = Random(7)
        val mapper = (0 until 300).fold(ClockMapper()) { acc, i ->
            val tMs = 1_000L + i * 100
            val delayMs = 5 + random.nextInt(55)
            acc.observe(tMs, tMs * ms + trueOffsetNanos + delayMs * ms)
        }

        val errorMs = (mapper.toNanos(31_000)!! - (31_000 * ms + trueOffsetNanos)) / ms.toDouble()

        assertTrue(errorMs in 4.0..7.0, "error was $errorMs ms")
    }

    @Test
    fun `linear drift of 100 ppm is followed`() {
        val random = Random(3)
        val mapper = (0 until 700).fold(ClockMapper()) { acc, i ->
            val tMs = 1_000L + i * 100
            acc.observe(tMs, phoneNanos(tMs, driftPpm = 100.0) + (5 + random.nextInt(35)) * ms)
        }

        val errorMs = (mapper.toNanos(71_000)!! - phoneNanos(71_000, 100.0)) / ms.toDouble()

        assertTrue(abs(errorMs - 5.0) < 2.0, "error was $errorMs ms")
        assertEquals(100.0, mapper.driftNanosPerMs(), 10.0)
    }

    @Test
    fun `esp time and phone time convert back and forth`() {
        val mapper = ClockMapper().observe(5_000, 5_000 * ms + trueOffsetNanos)

        val nanos = mapper.toNanos(5_250)!!

        assertEquals(5_250L, mapper.toEspMs(nanos))
    }

    @Test
    fun `a sample from an older window is ignored`() {
        val mapper = ClockMapper().observe(25_000, 25_000 * ms + trueOffsetNanos)

        assertEquals(mapper, mapper.observe(1_000, 0))
    }

    private fun phoneNanos(tMs: Long, driftPpm: Double): Long =
        trueOffsetNanos + (tMs * ms * (1.0 + driftPpm * 1e-6)).toLong()
}
