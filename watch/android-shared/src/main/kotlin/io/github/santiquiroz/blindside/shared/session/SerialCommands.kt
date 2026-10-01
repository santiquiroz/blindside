package io.github.santiquiroz.blindside.shared.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

// One consumer finishes each command before the next, so a tap can never land between a restart's stop and its new start.
class SerialCommands<T>(
    scope: CoroutineScope,
    private val onError: (T, Exception) -> Unit,
    private val handle: suspend (T) -> Unit,
) {
    private val queue = Channel<T>(Channel.UNLIMITED)

    init {
        scope.launch { for (command in queue) handleSafely(command) }
    }

    fun submit(command: T): Boolean = queue.trySend(command).isSuccess

    fun close() {
        queue.close()
    }

    private suspend fun handleSafely(command: T) {
        try {
            handle(command)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onError(command, error)
        }
    }
}
