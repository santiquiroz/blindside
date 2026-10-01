package io.github.santiquiroz.blindside.phone.session

import io.github.santiquiroz.blindside.core.PipelineCounters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PhoneDiagnostics(
    val infoJson: String? = null,
    val rssiDbm: Int? = null,
    val counters: PipelineCounters? = null,
)

// The last info outlives its link so the Belt tab is never blank between connections.
object PhoneStore {
    private val mutableState = MutableStateFlow(PhoneDiagnostics())

    val state: StateFlow<PhoneDiagnostics> = mutableState.asStateFlow()

    fun update(transform: (PhoneDiagnostics) -> PhoneDiagnostics) = mutableState.update(transform)
}
