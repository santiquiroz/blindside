package io.github.santiquiroz.blindside.phone.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: ReleaseInfo) : UpdateState
    data class Downloading(val info: ReleaseInfo, val progress: Float) : UpdateState
    data class Installing(val info: ReleaseInfo) : UpdateState
    data class Failed(val info: ReleaseInfo?, val reason: String) : UpdateState
}

object UpdateStore {
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)

    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    fun set(state: UpdateState) {
        mutableState.value = state
    }
}
