package io.github.santiquiroz.blindside.shared.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LinkStateTest {
    @Test
    fun `only changes are reported`() {
        assertNull(linkTransition(previouslyUp = false, nowUp = false))
        assertNull(linkTransition(previouslyUp = true, nowUp = true))
        assertEquals(true, linkTransition(previouslyUp = false, nowUp = true))
        assertEquals(false, linkTransition(previouslyUp = true, nowUp = false))
    }

    @Test
    fun `one outage reports link down exactly once`() {
        val events = listOf(
            LinkEvent.DISCONNECTED,
            LinkEvent.DISCONNECTED,
            LinkEvent.DISCONNECTED,
            LinkEvent.BLUETOOTH_OFF,
            LinkEvent.DISCONNECTED,
            LinkEvent.STREAM_READY,
        )
        assertEquals(listOf(false, true), changesFor(LinkReporter(up = true), events))
    }

    @Test
    fun `the first subscription reports the link up and a halt reports it down`() {
        assertEquals(listOf(true, false), changesFor(LinkReporter(), listOf(LinkEvent.STREAM_READY, LinkEvent.HALTED)))
    }

    @Test
    fun `halted links offer a retry`() {
        listOf(BleStatus.PAIRING_FAILED, BleStatus.BOND_LOST, BleStatus.MTU_TOO_LOW).forEach { assertTrue(needsRetry(it)) }
        listOf(BleStatus.STREAMING, BleStatus.RECONNECTING, BleStatus.SEARCHING).forEach { assertFalse(needsRetry(it)) }
    }

    private fun changesFor(start: LinkReporter, events: List<LinkEvent>): List<Boolean> =
        events.fold(start to emptyList<Boolean>()) { (reporter, changes), event ->
            val step = report(reporter, event)
            step.reporter to (changes + listOfNotNull(step.change))
        }.second
}
