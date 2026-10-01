package io.github.santiquiroz.blindside.phone.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.santiquiroz.blindside.phone.recordings.RecordingsRepository
import io.github.santiquiroz.blindside.phone.recordings.recordingTitle
import io.github.santiquiroz.blindside.phone.ui.common.EmptyState
import io.github.santiquiroz.blindside.phone.ui.common.NumberText
import io.github.santiquiroz.blindside.phone.ui.common.SectionCard
import io.github.santiquiroz.blindside.phone.ui.common.formatPercent
import io.github.santiquiroz.blindside.phone.ui.theme.Text2Color
import io.github.santiquiroz.blindside.phone.viewer.AnalysisState
import io.github.santiquiroz.blindside.phone.viewer.RecordingAnalysis
import io.github.santiquiroz.blindside.phone.viewer.SummaryRow
import io.github.santiquiroz.blindside.phone.viewer.analyzeFile
import io.github.santiquiroz.blindside.phone.viewer.summaryRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val VIEWER_EMPTY_TEXT = "Elige una grabación para verla aquí: reproducción, mapa de calor y resumen."

@Composable
fun ViewerTab(name: String?, repository: RecordingsRepository, onPickRecording: () -> Unit) {
    val file = remember(name) { name?.let(repository::file) }
    if (file == null) {
        EmptyState(VIEWER_EMPTY_TEXT, "Ver grabaciones", Icons.Filled.Insights, onPickRecording)
        return
    }
    val analysis by produceState<AnalysisState>(AnalysisState.Running(0f), file) {
        value = withContext(Dispatchers.Default) { analyzeFile(file) { progress -> value = AnalysisState.Running(progress) } }
    }
    when (val current = analysis) {
        is AnalysisState.Running -> AnalysisProgress(current.progress)
        is AnalysisState.Failed -> EmptyState("No se pudo abrir: ${current.reason}.", "Volver a grabaciones", Icons.Filled.ErrorOutline, onPickRecording)
        is AnalysisState.Done -> ViewerContent(file, current.analysis)
    }
}

@Composable
private fun AnalysisProgress(progress: Float) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("Analizando la grabación…", style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        NumberText(formatPercent(progress.toDouble()), color = Text2Color)
    }
}

@Composable
private fun ViewerContent(file: File, analysis: RecordingAnalysis) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(recordingTitle(file.name), style = MaterialTheme.typography.headlineSmall)
        ReplayPanel(file, analysis.summary.durationMs)
        SectionCard("Mapa de calor") { HeatmapView(analysis) }
        SectionCard("Resumen") { summaryRows(analysis.summary).forEach { SummaryLine(it) } }
    }
}

@Composable
private fun SummaryLine(row: SummaryRow) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(row.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Text2Color)
        NumberText(row.value, style = MaterialTheme.typography.bodyMedium)
    }
}
