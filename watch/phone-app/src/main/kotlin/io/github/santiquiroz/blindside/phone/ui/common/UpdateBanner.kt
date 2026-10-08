package io.github.santiquiroz.blindside.phone.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.santiquiroz.blindside.phone.ui.theme.RingColor
import io.github.santiquiroz.blindside.phone.ui.theme.SurfaceColor
import io.github.santiquiroz.blindside.phone.ui.theme.Text2Color
import io.github.santiquiroz.blindside.phone.ui.theme.TextColor
import io.github.santiquiroz.blindside.phone.update.ReleaseInfo
import io.github.santiquiroz.blindside.phone.update.UpdateState
import io.github.santiquiroz.blindside.phone.update.UpdateStore
import kotlin.math.roundToInt

@Composable
fun UpdateBanner(
    state: UpdateState,
    onUpdate: (ReleaseInfo) -> Unit,
    onDismiss: () -> Unit,
    onRetry: (ReleaseInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is UpdateState.Available -> BannerCard(modifier) { AvailableBody(state.info, onUpdate, onDismiss) }
        is UpdateState.Downloading -> BannerCard(modifier) { DownloadingBody(state.info, state.progress) }
        is UpdateState.Installing -> BannerCard(modifier) { InstallingBody() }
        is UpdateState.Failed -> BannerCard(modifier) { FailedBody(state.info, state.reason, onRetry) }
        UpdateState.Idle, UpdateState.Checking, UpdateState.UpToDate -> Unit
    }
}

@Composable
private fun BannerCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceColor, contentColor = TextColor),
        border = BorderStroke(1.dp, RingColor),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun AvailableBody(info: ReleaseInfo, onUpdate: (ReleaseInfo) -> Unit, onDismiss: () -> Unit) {
    Text("Nueva versión ${info.version} disponible", style = MaterialTheme.typography.titleMedium)
    if (info.watchApkUrl != null) {
        Text(
            "Si usas reloj, instala también la app del reloj de esa versión.",
            style = MaterialTheme.typography.bodyMedium,
            color = Text2Color,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onUpdate(info) }, modifier = Modifier.weight(1f).heightIn(min = MIN_TOUCH)) {
            Text("Actualizar")
        }
        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = MIN_TOUCH)) {
            Text("Ahora no")
        }
    }
}

@Composable
private fun DownloadingBody(info: ReleaseInfo, progress: Float) {
    val percent = (progress * 100).roundToInt().coerceIn(0, 100)
    Text("Descargando ${info.version}… $percent %", style = MaterialTheme.typography.bodyLarge)
    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun InstallingBody() {
    Text("Abriendo el instalador…", style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun FailedBody(info: ReleaseInfo?, reason: String, onRetry: (ReleaseInfo) -> Unit) {
    Text(reason, style = MaterialTheme.typography.bodyLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (info != null) {
            Button(onClick = { onRetry(info) }, modifier = Modifier.weight(1f).heightIn(min = MIN_TOUCH)) {
                Text("Reintentar")
            }
        }
        OutlinedButton(
            // A failure is local to this attempt, so Cerrar only clears the banner without recording a dismissal.
            onClick = { UpdateStore.set(UpdateState.Idle) },
            modifier = Modifier.weight(1f).heightIn(min = MIN_TOUCH),
        ) {
            Text("Cerrar")
        }
    }
}
