package io.github.santiquiroz.blindside.core.protocol

import io.github.santiquiroz.blindside.core.SharedVectors
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class Ld2450CodecTest {
    @Test
    fun `sign magnitude decoding matches every shared vector`() {
        val cases = SharedVectors.json.getJSONArray("sign_magnitude")

        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            assertEquals(case.getInt("value"), Ld2450Codec.decodeSignMagnitude(case.getInt("raw")), "raw=${case.getInt("raw")}")
        }
    }

    @Test
    fun `official manual frame decodes to minus 782 mm, plus 1713 mm, minus 16 cm per s`() {
        val frame = SharedVectors.hex("ld2450_official_frame")

        val targets = Ld2450Codec.decodeTargets(frame, offset = 4)

        assertEquals(RawTarget(-782, 1713, -16, 320), targets[0])
        assertTrue(targets[1].isEmpty)
        assertTrue(targets[2].isEmpty)
    }

    @Test
    fun `encoding the official target reproduces the manual bytes`() {
        val frame = SharedVectors.hex("ld2450_official_frame")

        val block = Ld2450Codec.encodeTargets(listOf(RawTarget(-782, 1713, -16, 320)))

        assertArrayEquals(frame.copyOfRange(4, 28), block)
    }

    @Test
    fun `encoding rejects magnitudes above 15 bits`() {
        assertThrows<IllegalArgumentException> { Ld2450Codec.encodeSignMagnitude(40_000) }
    }

    @Test
    fun `both encodings of zero decode to an empty slot`() {
        assertEquals(0, Ld2450Codec.decodeSignMagnitude(0x8000))
        assertEquals(0, Ld2450Codec.decodeSignMagnitude(0x0000))
    }
}
