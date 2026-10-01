package io.github.santiquiroz.blindside.shared.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

private const val RESTART = "restart"
private const val START = "start"
private const val STOP = "stop"

class SerialCommandsTest {
    private val handled = mutableListOf<String>()
    private val failed = mutableListOf<String>()

    private fun CoroutineScope.commands(owner: Job, handle: suspend (String) -> Unit): SerialCommands<String> =
        SerialCommands(CoroutineScope(coroutineContext + owner), { command, _ -> failed += command }, handle)

    private suspend fun drain(commands: SerialCommands<String>, owner: CompletableJob) {
        commands.close()
        owner.complete()
        owner.join()
    }

    @Test
    fun `a tap during a restart waits until the old session stopped and the new one started`() {
        runBlocking {
            val owner = Job()
            val oldSessionStopped = CompletableDeferred<Unit>()
            val commands = commands(owner) { command ->
                if (command == RESTART) oldSessionStopped.await()
                handled += command
            }
            commands.submit(RESTART)
            commands.submit(START)
            commands.submit(STOP)
            yield()
            assertEquals(emptyList<String>(), handled)
            oldSessionStopped.complete(Unit)
            drain(commands, owner)
            assertEquals(listOf(RESTART, START, STOP), handled)
        }
    }

    @Test
    fun `a failing command is reported and the next one still runs`() {
        runBlocking {
            val owner = Job()
            val commands = commands(owner) { command ->
                check(command != START) { "no Bluetooth adapter" }
                handled += command
            }
            commands.submit(START)
            commands.submit(STOP)
            drain(commands, owner)
            assertEquals(listOf(START), failed)
            assertEquals(listOf(STOP), handled)
        }
    }

    @Test
    fun `nothing is accepted once the service is gone`() {
        runBlocking {
            val owner = Job()
            val commands = commands(owner) { handled += it }
            drain(commands, owner)
            assertFalse(commands.submit(START))
            assertEquals(emptyList<String>(), handled)
        }
    }
}
