package io.github.santiquiroz.blindside.phone.tak

import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class TakLinkTest {
    private fun startServer(): ServerSocket = ServerSocket().apply {
        bind(InetSocketAddress("127.0.0.1", 0))
        soTimeout = 500
    }

    private fun plainConnector(server: ServerSocket) = TakConnector {
        Socket().apply { connect(InetSocketAddress("127.0.0.1", server.localPort), 5_000) }
    }

    private fun acceptSoon(server: ServerSocket, timeoutMs: Long): Socket {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            try {
                return server.accept().also { it.soTimeout = 1_000 }
            } catch (_: SocketTimeoutException) {
                if (System.currentTimeMillis() > deadline) fail<Nothing>("timed out waiting for the link to connect")
            }
        }
    }

    private class EventReader(input: InputStream) {
        private val reader = InputStreamReader(input, Charsets.UTF_8)
        private val pending = StringBuilder()

        // One read may hold several events, so leftovers stay buffered for next().
        fun next(timeoutMs: Long = 5_000): String {
            val deadline = System.currentTimeMillis() + timeoutMs
            val chunk = CharArray(1_024)
            while (true) {
                val end = pending.indexOf(EVENT_CLOSE)
                if (end >= 0) {
                    val event = pending.substring(0, end + EVENT_CLOSE.length)
                    pending.delete(0, end + EVENT_CLOSE.length)
                    return event
                }
                if (System.currentTimeMillis() > deadline) fail<Nothing>("timed out waiting for an event: $pending")
                val read = try {
                    reader.read(chunk)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (read < 0) fail<Nothing>("stream closed before the event completed: $pending")
                pending.append(String(chunk, 0, read))
            }
        }

        companion object {
            private const val EVENT_CLOSE = "</event>"
        }
    }

    private fun saEvent(): String =
        "<event version=\"2.0\" uid=\"ATAK-toro\" type=\"a-f-G-U-C\" how=\"m-g\"" +
            " time=\"2026-10-07T22:31:05.123Z\" start=\"2026-10-07T22:31:05.123Z\"" +
            " stale=\"2026-10-07T22:31:15.123Z\">" +
            "<point lat=\"5.0691000\" lon=\"-75.5170000\" hae=\"1500.0\" ce=\"5.0\" le=\"9999999.0\"/>" +
            "<detail><contact callsign=\"Toro\"/></detail></event>"

    @Test
    fun `first bytes the server reads are the identity event`() = runBlocking {
        val server = startServer()
        try {
            val link = TakLink(plainConnector(server), TakIds("abc123", "Santi"), "0.2.0")
            val job = launch(Dispatchers.IO) { link.run() }
            try {
                acceptSoon(server, 5_000).use {
                    val identity = EventReader(it.getInputStream()).next()
                    assertTrue(identity.contains("uid=\"BLINDSIDE-abc123\""), identity)
                    assertTrue(identity.contains("callsign=\"Santi radar\""), identity)
                }
            } finally {
                job.cancelAndJoin()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `send arrives at the server`() = runBlocking {
        val server = startServer()
        try {
            val link = TakLink(plainConnector(server), TakIds("abc123", "Santi"), "0.2.0")
            val job = launch(Dispatchers.IO) { link.run() }
            try {
                acceptSoon(server, 5_000).use {
                    val reader = EventReader(it.getInputStream())
                    reader.next()
                    val contact = contactEvent(
                        "BLINDSIDE-abc123-C1", "Radar Santi 1",
                        GeoPoint(5.0, -75.0), 3.0, System.currentTimeMillis(),
                    )
                    link.send(contact)
                    val got = reader.next()
                    assertTrue(got.contains("uid=\"BLINDSIDE-abc123-C1\""), got)
                }
            } finally {
                job.cancelAndJoin()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `sa event split in two writes is emitted once`() = runBlocking {
        val server = startServer()
        try {
            val link = TakLink(plainConnector(server), TakIds("abc123", "Santi"), "0.2.0")
            val job = launch(Dispatchers.IO) { link.run() }
            try {
                acceptSoon(server, 5_000).use {
                    EventReader(it.getInputStream()).next()
                    val incoming = async { withTimeout(5_000) { link.incoming.first() } }
                    delay(100)
                    val event = saEvent()
                    val out = it.getOutputStream()
                    out.write(event.substring(0, event.length / 2).toByteArray(Charsets.UTF_8))
                    out.flush()
                    delay(100)
                    out.write(event.substring(event.length / 2).toByteArray(Charsets.UTF_8))
                    out.flush()
                    val received = incoming.await()
                    assertEquals("ATAK-toro", received.uid)
                    assertEquals("a-f-G-U-C", received.type)
                    assertEquals("Toro", received.callsign)
                    assertNotNull(received.point)
                    assertEquals(5.0691, received.point!!.latDeg, 0.0001)
                    assertNull(withTimeoutOrNull(300) { link.incoming.first() })
                }
            } finally {
                job.cancelAndJoin()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `server close retries then reconnects with identity first`() = runBlocking {
        val server = startServer()
        try {
            val link = TakLink(plainConnector(server), TakIds("abc123", "Santi"), "0.2.0")
            val job = launch(Dispatchers.IO) { link.run() }
            try {
                val first = acceptSoon(server, 5_000)
                EventReader(first.getInputStream()).next()
                first.close()
                val retrying = withTimeout(5_000) {
                    link.status.first { it is LinkStatus.Retrying }
                } as LinkStatus.Retrying
                assertEquals(2_000, retrying.inMs)
                acceptSoon(server, 10_000).use {
                    val identity = EventReader(it.getInputStream()).next()
                    assertTrue(identity.contains("uid=\"BLINDSIDE-abc123\""), identity)
                }
            } finally {
                job.cancelAndJoin()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `backoff grows then caps`() {
        assertEquals(2_000, backoffMs(0))
        assertEquals(4_000, backoffMs(1))
        assertEquals(8_000, backoffMs(2))
        assertEquals(16_000, backoffMs(3))
        assertEquals(30_000, backoffMs(4))
        assertEquals(30_000, backoffMs(5))
        assertEquals(30_000, backoffMs(6))
    }
}
