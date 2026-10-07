package io.github.santiquiroz.blindside.phone.tak

import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface LinkStatus {
    data object Idle : LinkStatus
    data object Connecting : LinkStatus
    data class Connected(val sinceMs: Long) : LinkStatus
    data class Retrying(val inMs: Long, val reason: String) : LinkStatus
}

fun backoffMs(failures: Int): Long = when {
    failures <= 0 -> 2_000
    failures == 1 -> 4_000
    failures == 2 -> 8_000
    failures == 3 -> 16_000
    else -> 30_000
}

const val PING_PERIOD_MS = 15_000L
const val SILENCE_LIMIT_MS = 45_000L
const val STABLE_AFTER_MS = 60_000L

private const val READ_TIMEOUT_MS = 1_000
private const val READ_CHUNK = 4_096

class TakLink(
    private val connector: TakConnector,
    private val ids: TakIds,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _status = MutableStateFlow<LinkStatus>(LinkStatus.Idle)
    val status: StateFlow<LinkStatus> = _status
    private val _incoming = MutableSharedFlow<CotEvent>(extraBufferCapacity = 64)
    val incoming: SharedFlow<CotEvent> = _incoming
    private val outgoing = Channel<String>(64, BufferOverflow.DROP_OLDEST)

    fun send(event: String) {
        outgoing.trySend(event)
    }

    suspend fun run() = withContext(Dispatchers.IO) {
        var failures = 0
        try {
            while (true) {
                ensureActive()
                _status.value = LinkStatus.Connecting
                val socket = try {
                    connector.open()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures = rest(failures, reasonOf(e))
                    continue
                }
                runCatching { socket.soTimeout = READ_TIMEOUT_MS }
                // Whatever piled up during the backoff is older than its own stale time.
                drain()
                val connectedAt = clock()
                try {
                    writeIdentity(socket)
                    _status.value = LinkStatus.Connected(connectedAt)
                    hold(socket)
                    runCatching { socket.close() }
                    failures = rest(failures, "el servidor cerró la conexión")
                } catch (e: CancellationException) {
                    runCatching { socket.close() }
                    throw e
                } catch (e: Exception) {
                    runCatching { socket.close() }
                    if (clock() - connectedAt >= STABLE_AFTER_MS) failures = 0
                    failures = rest(failures, reasonOf(e))
                }
            }
        } finally {
            _status.value = LinkStatus.Idle
        }
    }

    private suspend fun rest(failures: Int, reason: String): Int {
        drain()
        val wait = backoffMs(failures)
        _status.value = LinkStatus.Retrying(wait, reason)
        delay(wait)
        return failures + 1
    }

    private fun drain() {
        while (outgoing.tryReceive().isSuccess) Unit
    }

    private fun writeIdentity(socket: Socket) {
        val event = identityEvent(ids.identityUid, "${ids.callsign} radar", appVersion, clock())
        socket.getOutputStream().write(event.toByteArray(Charsets.UTF_8))
        socket.getOutputStream().flush()
    }

    private suspend fun hold(socket: Socket) = coroutineScope {
        val writer = launch {
            val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            for (event in outgoing) {
                try {
                    out.write(event)
                    out.flush()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    throw LinkBroken("no se pudo escribir al servidor")
                }
            }
        }
        val pinger = launch {
            while (true) {
                delay(PING_PERIOD_MS)
                outgoing.trySend(pingEvent(ids.pingUid, clock()))
            }
        }
        try {
            readLoop(socket)
        } finally {
            // A write blocked on a stalled network ignores cancellation; closing the socket is what unblocks it.
            runCatching { socket.close() }
            writer.cancel()
            pinger.cancel()
        }
    }

    private suspend fun readLoop(socket: Socket) {
        val reader = InputStreamReader(socket.getInputStream(), Charsets.UTF_8)
        val splitter = CotSplitter()
        val chunk = CharArray(READ_CHUNK)
        var lastDataAt = clock()
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = try {
                reader.read(chunk)
            } catch (_: SocketTimeoutException) {
                // The 1 s read timeout only wakes the loop; silence is measured below.
                null
            }
            if (read != null && read < 0) throw LinkBroken("el servidor cerró la conexión")
            if (read != null) {
                lastDataAt = clock()
                for (xml in splitter.feed(String(chunk, 0, read))) {
                    val event = parseCotEvent(xml) ?: continue
                    _incoming.emit(event)
                }
            }
            if (clock() - lastDataAt >= SILENCE_LIMIT_MS) throw LinkBroken("45 s sin datos del servidor")
        }
    }

    private fun reasonOf(e: Exception): String = when (e) {
        is LinkBroken -> e.message ?: "enlace roto"
        else -> e.message?.take(80) ?: e.javaClass.simpleName
    }
}

private class LinkBroken(message: String) : Exception(message)
