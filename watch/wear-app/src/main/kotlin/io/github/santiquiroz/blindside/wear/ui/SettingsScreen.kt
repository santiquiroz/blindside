package io.github.santiquiroz.blindside.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import io.github.santiquiroz.blindside.wear.settings.AppSettings
import io.github.santiquiroz.blindside.wear.settings.DEFAULT_RADARS
import io.github.santiquiroz.blindside.wear.settings.SettingsTransform
import io.github.santiquiroz.blindside.wear.settings.YAW_STEP_DEG
import io.github.santiquiroz.blindside.wear.settings.effectiveYawDeg
import io.github.santiquiroz.blindside.wear.settings.nextHandedness
import io.github.santiquiroz.blindside.wear.settings.radar
import io.github.santiquiroz.blindside.wear.settings.toggledScreenMode
import io.github.santiquiroz.blindside.wear.settings.toggledUsage
import io.github.santiquiroz.blindside.wear.settings.withFlipXToggled
import io.github.santiquiroz.blindside.wear.settings.withHandedness
import io.github.santiquiroz.blindside.wear.settings.withSpeedSignFlipped
import io.github.santiquiroz.blindside.wear.settings.withYawNudged

@Composable
fun SettingsScreen(settings: AppSettings, onUpdate: (SettingsTransform) -> Unit, onOpenSpikes: () -> Unit) {
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { ListHeader { Text("Ajustes") } }
        item { SettingChip("Mano", handednessLabel(settings.handedness)) { onUpdate { it.withHandedness(nextHandedness(it.handedness)) } } }
        item { SettingChip("Pantalla", screenModeLabel(settings.screenMode)) { onUpdate { it.copy(screenMode = toggledScreenMode(it.screenMode)) } } }
        item { SettingChip("Vibración", usageLabel(settings.vibrationUsage)) { onUpdate { it.copy(vibrationUsage = toggledUsage(it.vibrationUsage)) } } }
        DEFAULT_RADARS.forEach { radarItems(settings, it.radarId, onUpdate) }
        item { SettingChip("Cinturón", settings.beltAddress ?: "sin emparejar") { onUpdate { it.copy(beltAddress = null) } } }
        item { NavChip(SPIKES_ENTRY_LABEL, onOpenSpikes) }
        item { Notice(APPLY_ON_START_MESSAGE) }
    }
}

private fun ScalingLazyListScope.radarItems(settings: AppSettings, radarId: Int, onUpdate: (SettingsTransform) -> Unit) {
    val radar = settings.radar(radarId)
    item { YawRow(settings, radarId, onUpdate) }
    item { SettingChip("${radarName(radarId)}: invertir X", yesNo(radar.flipX)) { onUpdate { it.withFlipXToggled(radarId) } } }
    item { SettingChip("${radarName(radarId)}: signo velocidad", signLabel(radar.speedSign)) { onUpdate { it.withSpeedSignFlipped(radarId) } } }
}

@Composable
private fun YawRow(settings: AppSettings, radarId: Int, onUpdate: (SettingsTransform) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CompactChip(onClick = { onUpdate { it.withYawNudged(radarId, -YAW_STEP_DEG) } }, label = { Text("-5°") })
        Text(yawLabel(radarId, effectiveYawDeg(settings, radarId)), fontSize = 12.sp)
        CompactChip(onClick = { onUpdate { it.withYawNudged(radarId, YAW_STEP_DEG) } }, label = { Text("+5°") })
    }
}
