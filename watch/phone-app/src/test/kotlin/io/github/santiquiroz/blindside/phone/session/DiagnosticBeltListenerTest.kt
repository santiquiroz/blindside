package io.github.santiquiroz.blindside.phone.session

import io.github.santiquiroz.blindside.shared.ble.BeltCommand
import io.github.santiquiroz.blindside.shared.ble.BeltListener
import io.github.santiquiroz.blindside.shared.ble.BleStatus
import io.github.santiquiroz.blindside.shared.ble.CommandResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiagnosticBeltListenerTest {
    private val calls = mutableListOf<String>()
    private val inner = object : BeltListener {
        override fun onPacket(bytes: ByteArray, arrivalNanos: Long) { calls += "packet" }
        override fun onBeltInfo(json: String, nowNanos: Long) { calls += "info:$json" }
        override fun onRssi(dbm: Int, nowNanos: Long) { calls += "rssi:$dbm" }
        override fun onLinkChanged(connected: Boolean, nowNanos: Long) { calls += "link:$connected" }
        override fun onStatus(status: BleStatus) { calls += "status:$status" }
        override fun onCommandWritten(result: CommandResult, nowNanos: Long) { calls += "command:${result.command}:${result.delivered}" }
    }

    @Test
    fun `info and signal reach the session and the diagnostic store, everything else passes through`() {
        PhoneStore.update { PhoneDiagnostics() }
        val listener = DiagnosticBeltListener(inner)

        listener.onBeltInfo("""{"fw":"0.1.0"}""", 1L)
        listener.onRssi(-58, 2L)
        listener.onPacket(byteArrayOf(1), 3L)
        listener.onStatus(BleStatus.STREAMING)
        listener.onCommandWritten(CommandResult(BeltCommand.Identify, delivered = true), 4L)

        assertEquals(listOf("info:{\"fw\":\"0.1.0\"}", "rssi:-58", "packet", "status:STREAMING", "command:Identify:true"), calls)
        assertEquals("""{"fw":"0.1.0"}""", PhoneStore.state.value.infoJson)
        assertEquals(-58, PhoneStore.state.value.rssiDbm)
    }
}
