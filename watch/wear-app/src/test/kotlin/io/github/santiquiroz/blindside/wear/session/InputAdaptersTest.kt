package io.github.santiquiroz.blindside.wear.session

import kotlinx.coroutines.channels.Channel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InputAdaptersTest {
    private val inputs = Channel<SessionInput>(Channel.UNLIMITED)

    private fun drain(): List<SessionInput> = generateSequence { inputs.tryReceive().getOrNull() }.toList()

    @Test
    fun `watch sensor callbacks become session inputs in order`() {
        val sensors = SensorInputs(inputs)
        sensors.onGravity(0f, 0f, 9.8f, 1L)
        sensors.onGyro(0.1f, 0f, 0f, 2L)
        sensors.onStep(3L)
        assertEquals(
            listOf(SessionInput.Gravity(0f, 0f, 9.8f, 1L), SessionInput.Gyro(0.1f, 0f, 0f, 2L), SessionInput.Step(3L)),
            drain(),
        )
    }

    @Test
    fun `belt callbacks become session inputs in order`() {
        val belt = BeltInputs(inputs)
        belt.onBeltInfo("{}", 1L)
        belt.onRssi(-60, 2L)
        belt.onLinkChanged(false, 3L)
        assertEquals(
            listOf(SessionInput.BeltInfo("{}", 1L), SessionInput.Rssi(-60, 2L), SessionInput.Link(false, 3L)),
            drain(),
        )
    }

    @Test
    fun `belt packets keep their bytes and arrival time`() {
        BeltInputs(inputs).onPacket(byteArrayOf(5, 6), 99L)
        val packet = drain().single() as SessionInput.Packet
        assertEquals(listOf<Byte>(5, 6), packet.bytes.toList())
        assertEquals(99L, packet.arrivalNanos)
    }
}
