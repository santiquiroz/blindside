package io.github.santiquiroz.blindside.core.protocol

import io.github.santiquiroz.blindside.core.SharedVectors
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BundleDecoderTest {
    @Test
    fun `one radar bundle matches the shared expectation`() {
        val vector = SharedVectors.json.getJSONObject("bundle_one_radar")

        val bundle = BundleDecoder.decode(SharedVectors.hex("bundle_one_radar"))!!

        assertHeader(vector.getJSONObject("expected"), bundle)
        assertEquals(listOf(RadarFrame(0, 995, listOf(RawTarget(-782, 1713, -16, 320), empty(), empty()))), bundle.radarFrames)
        assertTrue(bundle.imuBatches.isEmpty())
    }

    @Test
    fun `typical 242 byte bundle decodes radars, imus and statuses`() {
        val expected = SharedVectors.json.getJSONObject("bundle_typical").getJSONObject("expected")

        val bundle = BundleDecoder.decode(SharedVectors.hex("bundle_typical"))!!

        assertHeader(expected, bundle)
        assertRadarFrameIds(expected, bundle)
        assertEquals(RawTarget(500, 3000, 25, 360), bundle.radarFrames[1].targets[0])
        assertImuBatches(expected, bundle)
        assertEquals(listOf(RadarStatus(0, 2, 0, 7), RadarStatus(1, 0, 1, 7)), bundle.statuses)
        assertTrue(bundle.links.isEmpty())
    }

    @Test
    fun `a LINK section ahead of the radar decodes from the shared vector`() {
        val expected = SharedVectors.json.getJSONObject("bundle_with_link").getJSONObject("expected")
        val link = expected.getJSONObject("link")

        val bundle = BundleDecoder.decode(SharedVectors.hex("bundle_with_link"))!!

        assertHeader(expected, bundle)
        assertEquals(listOf(LinkParams(link.getInt("interval_units"), link.getInt("latency"), link.getInt("supervision_units"))), bundle.links)
        assertEquals(listOf(RadarFrame(0, 6990, listOf(RawTarget(-782, 1713, -16, 320), empty(), empty()))), bundle.radarFrames)
        assertEquals(0, bundle.skippedSections)
    }

    @Test
    fun `unknown section types are skipped by length`() {
        val bundle = BundleDecoder.decode(SharedVectors.hex("bundle_unknown_tlv"))!!

        assertEquals(listOf(1), bundle.radarFrames.map { it.radarId })
        assertEquals(1, bundle.skippedSections)
        assertFalse(bundle.truncated)
    }

    @Test
    fun `length past the end drops the rest and marks the bundle truncated`() {
        val bundle = BundleDecoder.decode(SharedVectors.hex("bundle_truncated"))!!

        assertTrue(bundle.truncated)
        assertTrue(bundle.radarFrames.isEmpty())
    }

    @Test
    fun `a single dangling byte after the header is a truncation`() {
        val bytes = SharedVectors.hex("bundle_one_radar").copyOfRange(0, HEADER_BYTES + 1)

        assertTrue(BundleDecoder.decode(bytes)!!.truncated)
    }

    @Test
    fun `short buffers and other protocol versions are rejected`() {
        assertNull(BundleDecoder.decode(ByteArray(5)))
        assertNull(BundleDecoder.decode(byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun `flags expose radar, imu and dropped bits`() {
        val bundle = Bundle(1, 0b10110, 0, 0, emptyList(), emptyList(), emptyList())

        assertFalse(bundle.radarAlive(0))
        assertTrue(bundle.radarAlive(1))
        assertTrue(bundle.imuOk(0))
        assertFalse(bundle.imuOk(1))
        assertTrue(bundle.dataDropped)
    }

    private fun assertHeader(expected: JSONObject, bundle: Bundle) {
        assertEquals(expected.getInt("version"), bundle.version)
        assertEquals(expected.getInt("flags"), bundle.flags)
        assertEquals(expected.getInt("seq"), bundle.seq)
        assertEquals(expected.getLong("t_ms"), bundle.tMs)
        assertEquals(expected.getBoolean("truncated"), bundle.truncated)
    }

    private fun assertRadarFrameIds(expected: JSONObject, bundle: Bundle) {
        val frames = expected.getJSONArray("radar_frames")
        val ids = List(frames.length()) { frames.getJSONObject(it).let { f -> f.getInt("radar_id") to f.getLong("t_ms") } }
        assertEquals(ids, bundle.radarFrames.map { it.radarId to it.tMs })
    }

    private fun assertImuBatches(expected: JSONObject, bundle: Bundle) {
        val batches = expected.getJSONArray("imu_batches")
        assertEquals(batches.length(), bundle.imuBatches.size)
        for (i in 0 until batches.length()) {
            val batch = batches.getJSONObject(i)
            val actual = bundle.imuBatches[i]
            assertEquals(batch.getInt("imu_id"), actual.imuId)
            assertEquals(batch.getLong("t_first_ms"), actual.tFirstMs)
            val sums = batch.getJSONArray("gyro_sums_u32")
            assertEquals(List(3) { sums.getLong(it) }, actual.gyroSums)
            val samples = batch.getJSONArray("samples")
            assertEquals(samples.length(), actual.samples.size)
            val first = samples.getJSONArray(0)
            assertEquals(ImuSample(first.getInt(0), first.getInt(1), first.getInt(2), first.getInt(3), first.getInt(4), first.getInt(5)), actual.samples[0])
        }
    }

    private fun empty() = RawTarget(0, 0, 0, 0)
}
