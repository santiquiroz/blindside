package io.github.santiquiroz.blindside.phone.ui.belt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.phone.ui.common.MIN_TOUCH
import io.github.santiquiroz.blindside.phone.ui.common.NumberText
import io.github.santiquiroz.blindside.phone.ui.common.SectionCard
import io.github.santiquiroz.blindside.phone.ui.common.SwitchRow
import io.github.santiquiroz.blindside.phone.ui.theme.RingColor
import io.github.santiquiroz.blindside.phone.ui.theme.Text2Color
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import io.github.santiquiroz.blindside.shared.settings.DEFAULT_RADARS
import io.github.santiquiroz.blindside.shared.settings.SettingsTransform
import io.github.santiquiroz.blindside.shared.settings.YAW_STEP_DEG
import io.github.santiquiroz.blindside.shared.settings.effectiveYawDeg
import io.github.santiquiroz.blindside.shared.settings.radar
import io.github.santiquiroz.blindside.shared.settings.withFlipXToggled
import io.github.santiquiroz.blindside.shared.settings.withHandedness
import io.github.santiquiroz.blindside.shared.settings.withSpeedSignFlipped
import io.github.santiquiroz.blindside.shared.settings.withYawNudged

@Composable
fun SharedSettingsSection(settings: AppSettings, onUpdate: (SettingsTransform) -> Unit) {
    SectionCard("Ajustes compartidos con el reloj") {
        Text("Mano", style = MaterialTheme.typography.labelLarge)
        HandednessSelector(settings.handedness) { chosen -> onUpdate { it.withHandedness(chosen) } }
        DEFAULT_RADARS.forEach { RadarSettingsBlock(settings, it.radarId, onUpdate) }
        Text(APPLY_ON_START_TEXT, style = MaterialTheme.typography.bodySmall, color = Text2Color)
    }
}

@Composable
private fun HandednessSelector(selected: Handedness, onSelect: (Handedness) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        Handedness.entries.forEachIndexed { index, handedness ->
            SegmentedButton(
                selected = handedness == selected,
                onClick = { onSelect(handedness) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = Handedness.entries.size),
            ) { Text(handednessLabel(handedness), maxLines = 1) }
        }
    }
}

@Composable
private fun RadarSettingsBlock(settings: AppSettings, radarId: Int, onUpdate: (SettingsTransform) -> Unit) {
    val radar = settings.radar(radarId)
    HorizontalDivider(color = RingColor)
    YawRow(settings, radarId, onUpdate)
    SwitchRow("Invertir X", radar.flipX) { onUpdate { it.withFlipXToggled(radarId) } }
    SwitchRow("Signo de velocidad invertido", radar.speedSign < 0) { onUpdate { it.withSpeedSignFlipped(radarId) } }
}

@Composable
private fun YawRow(settings: AppSettings, radarId: Int, onUpdate: (SettingsTransform) -> Unit) {
    val label = yawText(radarId, effectiveYawDeg(settings, radarId))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { onUpdate { it.withYawNudged(radarId, -YAW_STEP_DEG) } },
            modifier = Modifier.heightIn(min = MIN_TOUCH).semantics { contentDescription = "$label: girar 5 grados a la izquierda" },
        ) { Text("−5°") }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { NumberText(label) }
        OutlinedButton(
            onClick = { onUpdate { it.withYawNudged(radarId, YAW_STEP_DEG) } },
            modifier = Modifier.heightIn(min = MIN_TOUCH).semantics { contentDescription = "$label: girar 5 grados a la derecha" },
        ) { Text("+5°") }
    }
}
