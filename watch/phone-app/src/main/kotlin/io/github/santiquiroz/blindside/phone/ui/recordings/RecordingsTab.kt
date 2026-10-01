package io.github.santiquiroz.blindside.phone.ui.recordings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.santiquiroz.blindside.phone.bridge.PhoneBridge
import io.github.santiquiroz.blindside.phone.recordings.LocalRecording
import io.github.santiquiroz.blindside.phone.recordings.RecordingsRepository
import io.github.santiquiroz.blindside.phone.recordings.RowActions
import io.github.santiquiroz.blindside.phone.recordings.recordingTitle
import io.github.santiquiroz.blindside.phone.recordings.rowActions
import io.github.santiquiroz.blindside.phone.ui.common.EmptyState
import io.github.santiquiroz.blindside.phone.ui.common.MIN_TOUCH
import io.github.santiquiroz.blindside.phone.ui.common.NumberText
import io.github.santiquiroz.blindside.phone.ui.common.formatBytes
import io.github.santiquiroz.blindside.phone.ui.theme.AlertRedColor
import io.github.santiquiroz.blindside.phone.ui.theme.RingColor
import io.github.santiquiroz.blindside.phone.ui.theme.SurfaceColor
import io.github.santiquiroz.blindside.phone.ui.theme.Text2Color
import io.github.santiquiroz.blindside.phone.ui.theme.TextColor
import io.github.santiquiroz.blindside.phone.ui.theme.WarnColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val FETCH_FROM_WATCH_LABEL = "Traer del reloj"
private const val NO_RECORDINGS_TEXT = "Aún no hay grabaciones en este celular. Trae las del reloj o inicia el radar."

private data class RecordingActions(
    val activeName: String?,
    val onOpen: (String) -> Unit,
    val onShare: (LocalRecording) -> Unit,
    val onDelete: (LocalRecording) -> Unit,
)

@Composable
fun RecordingsTab(
    repository: RecordingsRepository,
    bridge: PhoneBridge,
    activeName: String?,
    onOpen: (String) -> Unit,
    onDeleted: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val recordings by produceState<List<LocalRecording>?>(null, version, activeName) { value = withContext(Dispatchers.IO) { repository.list() } }
    var fetching by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<LocalRecording?>(null) }
    val actions = RecordingActions(
        activeName = activeName,
        onOpen = onOpen,
        onShare = { recording -> repository.file(recording.name)?.let { shareRecording(context, it) } },
        onDelete = { deleting = it },
    )
    RecordingsBody(recordings, actions, onFetch = { fetching = true })
    if (fetching) {
        FetchFromWatchDialog(bridge, repository, onDismiss = {
            fetching = false
            version++
        })
    }
    deleting?.let { target ->
        DeleteDialog(
            recording = target,
            onConfirm = {
                deleting = null
                scope.launch {
                    withContext(Dispatchers.IO) { repository.delete(target.name) }
                    onDeleted(target.name)
                    version++
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun RecordingsBody(recordings: List<LocalRecording>?, actions: RecordingActions, onFetch: () -> Unit) {
    when {
        recordings == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        recordings.isEmpty() -> EmptyState(NO_RECORDINGS_TEXT, FETCH_FROM_WATCH_LABEL, Icons.Filled.FolderOpen, onFetch)
        else -> RecordingList(recordings, actions, onFetch)
    }
}

@Composable
private fun RecordingList(recordings: List<LocalRecording>, actions: RecordingActions, onFetch: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Grabaciones", style = MaterialTheme.typography.headlineSmall) }
        item { FetchButton(onFetch) }
        items(recordings, key = { it.name }) { RecordingCard(it, actions, rowActions(it.name, actions.activeName)) }
    }
}

@Composable
private fun FetchButton(onFetch: () -> Unit) {
    Button(onClick = onFetch, modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH)) {
        Icon(Icons.Filled.Download, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(FETCH_FROM_WATCH_LABEL)
    }
}

@Composable
private fun RecordingCard(recording: LocalRecording, actions: RecordingActions, allowed: RowActions) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceColor, contentColor = TextColor),
        border = BorderStroke(1.dp, RingColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = MIN_TOUCH)
                .clickable(enabled = allowed.canOpen, onClickLabel = "Abrir en el visor") { actions.onOpen(recording.name) }
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(recordingTitle(recording.name), style = MaterialTheme.typography.titleSmall)
                NumberText(formatBytes(recording.bytes), style = MaterialTheme.typography.bodySmall, color = Text2Color)
                allowed.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = WarnColor) }
            }
            if (allowed.canShare) IconButton(onClick = { actions.onShare(recording) }) { Icon(Icons.Filled.Share, contentDescription = "Compartir") }
            if (allowed.canDelete) IconButton(onClick = { actions.onDelete(recording) }) { Icon(Icons.Filled.Delete, contentDescription = "Borrar") }
        }
    }
}

@Composable
private fun DeleteDialog(recording: LocalRecording, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text("Borrar", color = AlertRedColor) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        title = { Text("¿Borrar esta grabación?") },
        text = { Text("${recordingTitle(recording.name)}. No se puede deshacer.") },
    )
}
