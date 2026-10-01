package io.github.santiquiroz.blindside.phone.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.phone.ui.common.NumberText
import io.github.santiquiroz.blindside.phone.ui.common.formatClock
import io.github.santiquiroz.blindside.phone.ui.radar.RadarView
import io.github.santiquiroz.blindside.phone.ui.theme.AlertRedColor
import io.github.santiquiroz.blindside.phone.ui.theme.Text2Color
import io.github.santiquiroz.blindside.phone.viewer.PLAYBACK_SPEEDS
import io.github.santiquiroz.blindside.phone.viewer.Playback
import io.github.santiquiroz.blindside.phone.viewer.ReplayCursor
import io.github.santiquiroz.blindside.phone.viewer.advanced
import io.github.santiquiroz.blindside.phone.viewer.seekedTo
import io.github.santiquiroz.blindside.phone.viewer.toggledPlay
import io.github.santiquiroz.blindside.phone.viewer.withSpeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

private val PLAY_BUTTON_SIZE = 64.dp
private val PLAY_ICON_SIZE = 32.dp
private const val MIN_SLIDER_RANGE = 1f

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun ReplayPanel(file: File, durationMs: Long) {
    val cursor = remember(file) { runCatching { ReplayCursor { BufferedInputStream(FileInputStream(file)) } }.getOrNull() }
    if (cursor == null) {
        Text("No se pudo abrir la reproducción de esta grabación.", color = AlertRedColor)
        return
    }
    DisposableEffect(cursor) { onDispose { cursor.close() } }
    var playback by remember(file) { mutableStateOf(Playback(durationMs = durationMs)) }
    var scene by remember(file) { mutableStateOf<RadarScene?>(null) }
    var seeking by remember(file) { mutableStateOf(false) }
    val replayThread = remember { Dispatchers.Default.limitedParallelism(1) }
    LaunchedEffect(cursor) {
        var shownMs = 0L
        // Conflating keeps only the newest position, so a long scrub never queues a pile of slow backward seeks.
        snapshotFlow { playback.positionMs }.conflate().collect { target ->
            seeking = target < shownMs
            scene = withContext(replayThread) { runCatching { cursor.seekTo(target) }.getOrNull() } ?: scene
            shownMs = target
            seeking = false
        }
    }
    LaunchedEffect(playback.playing) {
        if (playback.playing) runPlaybackClock(current = { playback }, update = { playback = it })
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            RadarView(scene, Modifier.fillMaxSize())
            if (seeking) Text("Buscando…", Modifier.align(Alignment.TopEnd), color = Text2Color)
        }
        Transport(playback, onChange = { playback = it })
    }
}

private suspend fun runPlaybackClock(current: () -> Playback, update: (Playback) -> Unit) {
    var lastFrameMs = withFrameMillis { it }
    while (current().playing) {
        val frameMs = withFrameMillis { it }
        update(advanced(current(), frameMs - lastFrameMs))
        lastFrameMs = frameMs
    }
}

@Composable
private fun Transport(playback: Playback, onChange: (Playback) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledIconButton(onClick = { onChange(toggledPlay(playback)) }, modifier = Modifier.size(PLAY_BUTTON_SIZE)) {
            Icon(
                imageVector = if (playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playback.playing) "Pausar" else "Reproducir",
                modifier = Modifier.size(PLAY_ICON_SIZE),
            )
        }
        SpeedSelector(playback.speed) { onChange(withSpeed(playback, it)) }
    }
    Slider(
        value = playback.positionMs.toFloat(),
        onValueChange = { onChange(seekedTo(playback, it.toLong())) },
        valueRange = 0f..playback.durationMs.toFloat().coerceAtLeast(MIN_SLIDER_RANGE),
        modifier = Modifier.semantics { contentDescription = "Línea de tiempo" },
    )
    Row(Modifier.fillMaxWidth()) {
        NumberText(formatClock(playback.positionMs), style = MaterialTheme.typography.bodySmall, color = Text2Color)
        Spacer(Modifier.weight(1f))
        NumberText(formatClock(playback.durationMs), style = MaterialTheme.typography.bodySmall, color = Text2Color)
    }
}

@Composable
private fun SpeedSelector(selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        PLAYBACK_SPEEDS.forEachIndexed { index, speed ->
            SegmentedButton(
                selected = speed == selected,
                onClick = { onSelect(speed) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = PLAYBACK_SPEEDS.size),
            ) { NumberText("$speed×") }
        }
    }
}
