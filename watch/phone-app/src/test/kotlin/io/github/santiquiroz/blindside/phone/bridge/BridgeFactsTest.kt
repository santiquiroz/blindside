package io.github.santiquiroz.blindside.phone.bridge

import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.bridge.WatchStatus
import io.github.santiquiroz.blindside.shared.bridge.encodeSharedSettings
import io.github.santiquiroz.blindside.shared.bridge.encodeWatchStatus
import io.github.santiquiroz.blindside.shared.settings.DEFAULT_RADARS
import io.github.santiquiroz.blindside.shared.settings.SharedSettings
import io.github.santiquiroz.blindside.shared.settings.WatchPosture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class BridgeFactsTest {
    private val status = WatchStatus(sessionActive = true, ble = BleStatus.STREAMING, linkUp = true, radars = emptyList(), updatedMs = 2_000L)

    @Test
    fun `the nearby node is the watch`() {
        assertEquals("watch", pickWatchNode(listOf(NodeFacts("cloud", nearby = false), NodeFacts("watch", nearby = true))))
    }

    @Test
    fun `a node that is not nearby is still better than none`() {
        assertEquals("cloud", pickWatchNode(listOf(NodeFacts("cloud", nearby = false))))
    }

    @Test
    fun `no connected node means no watch`() {
        assertNull(pickWatchNode(emptyList()))
    }

    @Test
    fun `an older status never replaces a newer one`() {
        val older = status.copy(sessionActive = false, updatedMs = 1_000L)
        assertSame(status, newerStatus(status, older))
        assertSame(status, newerStatus(older, status))
        assertSame(older, newerStatus(null, older))
    }

    @Test
    fun `the newest valid settings win and garbage is skipped`() {
        val older = encodeSharedSettings(SharedSettings(Handedness.LEFT, DEFAULT_RADARS, WatchPosture.NORMAL, 100L))
        val newer = encodeSharedSettings(SharedSettings(Handedness.SWITCHER, DEFAULT_RADARS, WatchPosture.NORMAL, 200L))
        assertEquals(Handedness.SWITCHER, newestSettings(listOf(older, "garbage", newer))?.handedness)
        assertNull(newestSettings(listOf("garbage")))
    }

    @Test
    fun `the newest status wins`() {
        val older = encodeWatchStatus(status.copy(updatedMs = 1_000L))
        assertEquals(2_000L, newestStatus(listOf(encodeWatchStatus(status), older, "nope"))?.updatedMs)
    }
}
