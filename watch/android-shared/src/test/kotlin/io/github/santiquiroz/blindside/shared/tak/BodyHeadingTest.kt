package io.github.santiquiroz.blindside.shared.tak

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BodyHeadingTest {
    @Test
    fun `anchor stores the normalized front minus yaw offset`() {
        assertEquals(340.0, anchorOf(10.0, 30.0, 0L).offsetDeg, 1e-9)
    }

    @Test
    fun `a fresh heading adds the anchor offset to the live yaw`() {
        val anchor = anchorOf(10.0, 30.0, 0L)
        assertEquals(20.0, bodyHeadingDeg(anchor, 40.0, false, 1_000_000_000L)!!, 1e-9)
    }

    @Test
    fun `heading wraps past north`() {
        val anchor = anchorOf(350.0, 0.0, 0L)
        assertEquals(10.0, bodyHeadingDeg(anchor, 20.0, false, 1_000_000_000L)!!, 1e-9)
    }

    @Test
    fun `a one second anchor without belt is still fresh`() {
        val anchor = anchorOf(0.0, 0.0, 0L)
        assertNotNull(bodyHeadingDeg(anchor, 5.0, false, 1_000_000_000L))
    }

    @Test
    fun `a ten second anchor without belt is stale`() {
        val anchor = anchorOf(0.0, 0.0, 0L)
        assertNull(bodyHeadingDeg(anchor, 5.0, false, 10_000_000_000L))
    }

    @Test
    fun `a ten second anchor with belt still holds`() {
        val anchor = anchorOf(0.0, 0.0, 0L)
        assertNotNull(bodyHeadingDeg(anchor, 5.0, true, 10_000_000_000L))
    }

    @Test
    fun `a 121 second anchor with belt is stale`() {
        val anchor = anchorOf(0.0, 0.0, 0L)
        assertNull(bodyHeadingDeg(anchor, 5.0, true, 121_000_000_000L))
    }

    @Test
    fun `no anchor means no heading`() {
        assertNull(bodyHeadingDeg(null, 5.0, true, 1_000_000_000L))
    }

    @Test
    fun `a negative age counts as fresh`() {
        val anchor = anchorOf(0.0, 0.0, 10_000_000_000L)
        assertNotNull(bodyHeadingDeg(anchor, 5.0, false, 5_000_000_000L))
    }
}
