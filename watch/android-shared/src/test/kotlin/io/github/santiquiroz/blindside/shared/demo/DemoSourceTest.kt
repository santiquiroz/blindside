package io.github.santiquiroz.blindside.shared.demo

import io.github.santiquiroz.blindside.core.sim.SimPacket
import io.github.santiquiroz.blindside.shared.session.SessionInput
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DemoSourceTest {
    private val packets = listOf(
        SimPacket(byteArrayOf(1), 7_000_000L),
        SimPacket(byteArrayOf(2), 9_000_000L),
        SimPacket(byteArrayOf(3), 11_000_000L),
    )

    @Test
    fun `rebasing moves the first packet to the base and keeps the spacing`() {
        assertEquals(listOf(100L, 2_000_100L, 4_000_100L), rebase(packets, 100L).map { it.arrivalNanos })
    }

    @Test
    fun `rebasing an empty list gives an empty list`() {
        assertEquals(emptyList<SimPacket>(), rebase(emptyList(), 100L))
    }

    @Test
    fun `delays never go negative`() {
        assertEquals(0L, delayMsUntil(targetNanos = 1_000L, nowNanos = 5_000_000L))
        assertEquals(3L, delayMsUntil(targetNanos = 3_000_000L, nowNanos = 0L))
    }

    @Test
    fun `one play delivers every packet in order with real arrival times`() {
        val inputs = Channel<SessionInput>(Channel.UNLIMITED)
        runBlocking { DemoSource(packets, inputs, System::nanoTime).playOnce() }
        val delivered = generateSequence { inputs.tryReceive().getOrNull() }.toList().map { it as SessionInput.Packet }
        assertEquals(listOf(1, 2, 3), delivered.map { it.bytes.single().toInt() })
        assertTrue(delivered.zipWithNext().all { (a, b) -> a.arrivalNanos <= b.arrivalNanos })
    }

    @Test
    fun `the demo announces the link before any packet`() {
        val inputs = Channel<SessionInput>(Channel.UNLIMITED)
        DemoSource(packets, inputs, { 42L }).announceLink()
        assertEquals(SessionInput.Link(connected = true, nowNanos = 42L), inputs.tryReceive().getOrNull())
    }
}
