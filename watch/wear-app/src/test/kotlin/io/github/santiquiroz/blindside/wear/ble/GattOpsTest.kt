package io.github.santiquiroz.blindside.wear.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GattOpsTest {
    private val disconnect = listOf(SetupEffect.DISCONNECT)
    private val nothing = emptyList<SetupEffect>()

    @Test
    fun `setup requests the mtu before enabling notifications and activates the session last`() {
        val expected = listOf(
            GattOp.RequestMtu(REQUESTED_MTU),
            GattOp.ReadInfo,
            GattOp.EnableStreamNotify,
            GattOp.WriteSessionActive(true),
        )
        assertEquals(expected, setupOpsAfterDiscovery())
    }

    @Test
    fun `the mtu wait is two seconds and other ops five`() {
        assertEquals(2_000L, timeoutMsFor(GattOp.RequestMtu(REQUESTED_MTU)))
        assertEquals(5_000L, timeoutMsFor(GattOp.EnableStreamNotify))
        assertEquals(5_000L, timeoutMsFor(GattOp.WriteSessionActive(true)))
    }

    @Test
    fun `the info read waits for the whole pairing window`() {
        assertEquals(60_000L, timeoutMsFor(GattOp.ReadInfo))
    }

    @Test
    fun `a missing mtu callback does not disconnect`() {
        assertEquals(nothing, effectsAfter(GattOp.RequestMtu(REQUESTED_MTU), LOCAL_FAILURE_STATUS))
    }

    @Test
    fun `failed discovery or subscription disconnects`() {
        assertEquals(disconnect, effectsAfter(GattOp.DiscoverServices, LOCAL_FAILURE_STATUS))
        assertEquals(disconnect, effectsAfter(GattOp.EnableStreamNotify, 5))
    }

    @Test
    fun `a bond failure status on the info read ends the setup`() {
        BOND_FAILURE_STATUSES.forEach { status -> assertEquals(disconnect, effectsAfter(GattOp.ReadInfo, status)) }
        assertEquals(setOf(5, 15, 137), BOND_FAILURE_STATUSES)
    }

    @Test
    fun `an info read failing for another reason keeps going`() {
        assertEquals(nothing, effectsAfter(GattOp.ReadInfo, LOCAL_FAILURE_STATUS))
        assertEquals(nothing, effectsAfter(GattOp.ReadInfo, 133))
    }

    @Test
    fun `a failed control write still lowers the priority`() {
        assertEquals(listOf(SetupEffect.LOWER_PRIORITY), effectsAfter(GattOp.WriteSessionActive(true), 133))
    }

    @Test
    fun `subscribing reports the link up`() {
        assertEquals(listOf(SetupEffect.REPORT_LINK_UP), effectsAfter(GattOp.EnableStreamNotify, GATT_SUCCESS_STATUS))
    }

    @Test
    fun `activating the session lowers the connection priority`() {
        assertEquals(listOf(SetupEffect.LOWER_PRIORITY), effectsAfter(GattOp.WriteSessionActive(true), GATT_SUCCESS_STATUS))
        assertEquals(nothing, effectsAfter(GattOp.WriteSessionActive(false), GATT_SUCCESS_STATUS))
    }

    @Test
    fun `the first op starts at once and later ops wait`() {
        val first = enqueue(OpQueue(), GattOp.DiscoverServices)
        assertEquals(GattOp.DiscoverServices, first.start)
        val second = enqueue(first.queue, GattOp.ReadRssi)
        assertNull(second.start)
        assertEquals(listOf(GattOp.ReadRssi), second.queue.pending)
    }

    @Test
    fun `completing an op starts the next one in order`() {
        val queued = listOf(GattOp.RequestMtu(REQUESTED_MTU), GattOp.ReadInfo, GattOp.EnableStreamNotify)
            .fold(OpQueue()) { queue, op -> enqueue(queue, op).queue }
        val afterFirst = completeInFlight(queued)
        assertEquals(GattOp.ReadInfo, afterFirst.start)
        val afterSecond = completeInFlight(afterFirst.queue)
        assertEquals(GattOp.EnableStreamNotify, afterSecond.start)
        val idle = completeInFlight(afterSecond.queue)
        assertNull(idle.start)
        assertEquals(OpQueue(), idle.queue)
    }

    @Test
    fun `rssi reads never pile up behind a stuck op`() {
        val stuck = enqueue(OpQueue(), GattOp.EnableStreamNotify).queue
        assertTrue(shouldQueueRssi(stuck))
        assertFalse(shouldQueueRssi(enqueue(stuck, GattOp.ReadRssi).queue))
        assertFalse(shouldQueueRssi(enqueue(OpQueue(), GattOp.ReadRssi).queue))
    }
}
