package io.github.santiquiroz.blindside.core.protocol

import io.github.santiquiroz.blindside.core.SharedVectors
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BundleEncoderTest {
    @Test
    fun `encoding the decoded typical bundle reproduces the shared bytes`() {
        val bytes = SharedVectors.hex("bundle_typical")

        assertArrayEquals(bytes, BundleEncoder.encode(BundleDecoder.decode(bytes)!!))
    }

    @Test
    fun `encoding the decoded one radar bundle reproduces the shared bytes`() {
        val bytes = SharedVectors.hex("bundle_one_radar")

        assertArrayEquals(bytes, BundleEncoder.encode(BundleDecoder.decode(bytes)!!))
    }

    @Test
    fun `encoding the decoded link bundle reproduces the shared bytes`() {
        val bytes = SharedVectors.hex("bundle_with_link")

        assertArrayEquals(bytes, BundleEncoder.encode(BundleDecoder.decode(bytes)!!))
    }

    @Test
    fun `sections follow the contract fill order whatever order the bundle lists them in`() {
        val imu = { id: Int -> ImuBatch(id, 100, listOf(ImuSample(0, 0, 4096, 0, 0, 0)), listOf(0L, 0L, 0L)) }
        val radarB = RadarFrame(1, 120, listOf(RawTarget(500, 3000, 25, 360)))
        val radarA = RadarFrame(0, 110, listOf(RawTarget(-782, 1713, -16, 320)))
        val bundle = Bundle(1, 0x0F, 3, 130, listOf(radarB, radarA), listOf(imu(1), imu(0)), listOf(RadarStatus(0, 0, 0, 7)), links = listOf(LinkParams(36, 0, 500)))

        val bytes = BundleEncoder.encode(bundle)
        val decoded = BundleDecoder.decode(bytes)!!

        assertEquals(listOf(TLV_IMU, TLV_IMU, TLV_STATUS, TLV_LINK, TLV_RADAR, TLV_RADAR), sectionTypes(bytes))
        assertEquals(listOf(0, 1), decoded.imuBatches.map { it.imuId })
        assertEquals(listOf(110L, 120L), decoded.radarFrames.map { it.tMs })
    }

    @Test
    fun `a typical bundle stays within the 244 byte packet budget`() {
        assertEquals(242, BundleEncoder.encode(BundleDecoder.decode(SharedVectors.hex("bundle_typical"))!!).size)
    }

    @Test
    fun `negative gyro sums are written as wrapped u32`() {
        val batch = ImuBatch(0, 10, listOf(ImuSample(0, 0, 4096, 0, 0, -655)), listOf(-6L and 0xFFFFFFFFL, 0, 0))

        val decoded = BundleDecoder.decode(BundleEncoder.encode(Bundle(1, 0x0F, 1, 20, emptyList(), listOf(batch), emptyList())))!!

        assertEquals(batch, decoded.imuBatches.single())
    }

    @Test
    fun `a LINK section round trips and is not counted as skipped`() {
        val bundle = Bundle(1, 0x0F, 7, 1_000, emptyList(), emptyList(), emptyList(), links = listOf(LinkParams(36, 0, 500)))

        val decoded = BundleDecoder.decode(BundleEncoder.encode(bundle))!!

        assertEquals(listOf(LinkParams(36, 0, 500)), decoded.links)
        assertEquals(0, decoded.skippedSections)
        assertEquals(45.0, decoded.links.single().intervalMs, 1e-9)
        assertEquals(5_000, decoded.links.single().timeoutMs)
    }

    private tailrec fun sectionTypes(bytes: ByteArray, at: Int = HEADER_BYTES, found: List<Int> = emptyList()): List<Int> =
        if (at >= bytes.size) found else sectionTypes(bytes, at + 2 + bytes.u8(at + 1), found + bytes.u8(at))
}
