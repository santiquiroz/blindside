package io.github.santiquiroz.blindside.phone.viewer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

data class AnalysisKey(val name: String, val bytes: Long, val modifiedMs: Long)

typealias FileAnalyzer = (file: File, checkpoint: () -> Unit, onProgress: (Float) -> Unit) -> AnalysisState

fun analysisKeyOf(file: File): AnalysisKey = AnalysisKey(file.name, file.length(), file.lastModified())

private class CachedAnalysis(val key: AnalysisKey, val state: StateFlow<AnalysisState>, val job: Job)

// One analysis per file version: leaving the Visor neither stops nor repeats it, and opening another file cancels it at its next record.
class AnalysisCache(private val scope: CoroutineScope, private val analyze: FileAnalyzer = ::analyzeFile) {
    private var current: CachedAnalysis? = null

    fun analysisOf(file: File): StateFlow<AnalysisState> {
        val key = analysisKeyOf(file)
        current?.takeIf { it.key == key }?.let { return it.state }
        current?.job?.cancel()
        return launchAnalysis(file, key).also { current = it }.state
    }

    private fun launchAnalysis(file: File, key: AnalysisKey): CachedAnalysis {
        val state = MutableStateFlow<AnalysisState>(AnalysisState.Running(0f))
        val job = scope.launch {
            state.value = analyze(file, { ensureActive() }) { state.value = AnalysisState.Running(it) }
        }
        return CachedAnalysis(key, state, job)
    }
}
