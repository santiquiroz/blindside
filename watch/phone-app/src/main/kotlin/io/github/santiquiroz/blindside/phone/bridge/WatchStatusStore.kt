package io.github.santiquiroz.blindside.phone.bridge

import io.github.santiquiroz.blindside.shared.bridge.WatchStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object WatchStatusStore {
    private val mutableState = MutableStateFlow<WatchStatus?>(null)

    val state: StateFlow<WatchStatus?> = mutableState.asStateFlow()

    fun offer(status: WatchStatus) = mutableState.update { newerStatus(it, status) }
}

// Data Layer events can arrive out of order; an older status never replaces a newer one.
fun newerStatus(current: WatchStatus?, incoming: WatchStatus): WatchStatus =
    if (current != null && current.updatedMs > incoming.updatedMs) current else incoming
