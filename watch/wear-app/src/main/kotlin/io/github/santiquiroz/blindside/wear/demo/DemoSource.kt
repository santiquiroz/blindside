package io.github.santiquiroz.blindside.wear.demo

import io.github.santiquiroz.blindside.core.sim.SimPacket
import io.github.santiquiroz.blindside.wear.session.SessionInput
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val NANOS_PER_MS = 1_000_000L
private const val LOOP_PAUSE_MS = 2_000L

class DemoSource(
    private val packets: List<SimPacket>,
    private val inputs: SendChannel<SessionInput>,
    private val clock: () -> Long,
) {
    suspend fun run() {
        announceLink()
        while (currentCoroutineContext().isActive) {
            playOnce()
            delay(LOOP_PAUSE_MS)
        }
    }

    fun announceLink() {
        inputs.trySend(SessionInput.Link(connected = true, nowNanos = clock()))
    }

    suspend fun playOnce() {
        rebase(packets, clock()).forEach { deliver(it) }
    }

    private suspend fun deliver(packet: SimPacket) {
        delay(delayMsUntil(packet.arrivalNanos, clock()))
        inputs.trySend(SessionInput.Packet(packet.bytes, clock()))
    }
}

fun rebase(packets: List<SimPacket>, baseNanos: Long): List<SimPacket> {
    val first = packets.firstOrNull()?.arrivalNanos ?: return emptyList()
    return packets.map { it.copy(arrivalNanos = it.arrivalNanos - first + baseNanos) }
}

fun delayMsUntil(targetNanos: Long, nowNanos: Long): Long = ((targetNanos - nowNanos) / NANOS_PER_MS).coerceAtLeast(0L)
