package io.github.santiquiroz.blindside.phone.ui.recordings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.santiquiroz.blindside.phone.bridge.PhoneBridge
import io.github.santiquiroz.blindside.phone.recordings.RecordingsRepository
import io.github.santiquiroz.blindside.phone.recordings.RemoteRow
import io.github.santiquiroz.blindside.phone.recordings.recordingTitle
import io.github.santiquiroz.blindside.phone.ui.common.MIN_TOUCH
import io.github.santiquiroz.blindside.phone.ui.common.NumberText
import io.github.santiquiroz.blindside.phone.ui.common.formatBytes
import io.github.santiquiroz.blindside.phone.ui.theme.Text2Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LIST_MAX_HEIGHT = 360.dp

@Composable
fun FetchFromWatchDialog(bridge: PhoneBridge, repository: RecordingsRepository, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<FetchState>(FetchState.Loading) }
    LaunchedEffect(Unit) {
        val local = withContext(Dispatchers.IO) { repository.names() }
        state = listedState(bridge.listRecordings(), local)
    }
    val download: (RemoteRow) -> Unit = { row ->
        scope.launch {
            val rows = currentRows(state)
            state = FetchState.Downloading(rows, row.entry.name, 0L, row.entry.bytes)
            val result = bridge.download(row.entry, repository.dir) { received ->
                state = FetchState.Downloading(rows, row.entry.name, received, row.entry.bytes)
            }
            state = afterDownload(rows, row.entry.name, result)
        }
    }
    AlertDialog(
        onDismissRequest = { if (canDismiss(state)) onDismiss() },
        confirmButton = { TextButton(onClick = onDismiss, enabled = canDismiss(state)) { Text("Cerrar") } },
        dismissButton = { if (canCancel(state)) TextButton(onClick = bridge::cancelDownload) { Text("Cancelar") } },
        title = { Text("Grabaciones del reloj") },
        text = { FetchBody(state, download) },
    )
}

@Composable
private fun FetchBody(state: FetchState, onDownload: (RemoteRow) -> Unit) {
    when (state) {
        FetchState.Loading -> AskingWatch()
        is FetchState.Problem -> Text(state.text)
        is FetchState.Downloading -> DownloadProgress(state)
        is FetchState.Listed -> RemoteList(state, onDownload)
    }
}

@Composable
private fun AskingWatch() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp))
        Text("Preguntando al reloj…")
    }
}

@Composable
private fun DownloadProgress(state: FetchState.Downloading) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Trayendo ${recordingTitle(state.name)}")
        LinearProgressIndicator(progress = { downloadProgress(state.receivedBytes, state.totalBytes) }, modifier = Modifier.fillMaxWidth())
        NumberText("${formatBytes(state.receivedBytes)} de ${formatBytes(state.totalBytes)}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RemoteList(state: FetchState.Listed, onDownload: (RemoteRow) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.note?.let { Text(it, color = Text2Color) }
        LazyColumn(Modifier.heightIn(max = LIST_MAX_HEIGHT)) {
            items(state.rows, key = { it.entry.name }) { RemoteLine(it, onDownload) }
        }
    }
}

@Composable
private fun RemoteLine(row: RemoteRow, onDownload: (RemoteRow) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(recordingTitle(row.entry.name), style = MaterialTheme.typography.bodyMedium)
            NumberText(formatBytes(row.entry.bytes), style = MaterialTheme.typography.bodySmall, color = Text2Color)
        }
        if (row.alreadyLocal) Text("Ya está", color = Text2Color) else TextButton(onClick = { onDownload(row) }) { Text("Traer") }
    }
}
