package io.github.santiquiroz.blindside.phone.tak

import org.junit.jupiter.api.Assertions.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TakStoreTest {
    private val now = 1_760_000_000_000L

    @Test
    fun `stopped shows Desconectado`() {
        assertEquals("Desconectado", takStatusText(TakUiState(), now))
    }

    @Test
    fun `error wins over link state`() {
        val state = TakUiState(running = true, link = LinkStatus.Connected(now), error = "Falta permiso de ubicación")
        assertEquals("Falta permiso de ubicación", takStatusText(state, now))
    }

    @Test
    fun `connecting shows Conectando`() {
        assertEquals("Conectando…", takStatusText(TakUiState(running = true, link = LinkStatus.Connecting), now))
    }

    @Test
    fun `retrying shows seconds and reason`() {
        val state = TakUiState(running = true, link = LinkStatus.Retrying(4_000, "se cayó la red"))
        assertEquals("Reconectando en 4 s · se cayó la red", takStatusText(state, now))
    }

    @Test
    fun `connected without a fresh fix shows sin GPS`() {
        val missing = TakUiState(running = true, link = LinkStatus.Connected(now), mates = 2, contactsSent = 9)
        assertEquals("Conectado · sin GPS", takStatusText(missing, now))
        val stale = missing.copy(lastFixAtMs = now - 15_001)
        assertEquals("Conectado · sin GPS", takStatusText(stale, now))
    }

    @Test
    fun `connected with a fix shows mates and contacts sent`() {
        val state = TakUiState(running = true, link = LinkStatus.Connected(now), mates = 2, contactsSent = 9, lastFixAtMs = now - 3_000)
        assertEquals("Conectado · 2 compañeros · 9 contactos enviados", takStatusText(state, now))
    }

    @Test
    fun `hasObservers follows active collectors`() {
        assertFalse(TakStore.hasObservers())
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        TakStore.state.onEach { }.launchIn(scope)
        assertTrue(TakStore.hasObservers())
        scope.cancel()
        assertFalse(TakStore.hasObservers())
    }
}
