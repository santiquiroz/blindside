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
import io.github.santiquiroz.blindside.shared.hud.nextGameDuration
import io.github.santiquiroz.blindside.shared.session.PhonePairing
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import io.github.santiquiroz.blindside.shared.settings.DEFAULT_RADARS
import io.github.santiquiroz.blindside.shared.settings.SettingsTransform
import io.github.santiquiroz.blindside.shared.settings.YAW_STEP_DEG
import io.github.santiquiroz.blindside.shared.settings.effectiveYawDeg
import io.github.santiquiroz.blindside.shared.settings.nextHandedness
import io.github.santiquiroz.blindside.shared.settings.nextPosture
import io.github.santiquiroz.blindside.shared.settings.radar
import io.github.santiquiroz.blindside.shared.settings.toggledContactColor
import io.github.santiquiroz.blindside.shared.settings.toggledScreenMode
import io.github.santiquiroz.blindside.shared.settings.toggledUsage
import io.github.santiquiroz.blindside.shared.settings.withFlipXToggled
import io.github.santiquiroz.blindside.shared.settings.withHandedness
import io.github.santiquiroz.blindside.shared.settings.withSpeedSignFlipped
import io.github.santiquiroz.blindside.shared.settings.withYawNudged
import io.github.santiquiroz.blindside.shared.theme.BlindsideFonts

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdate: (SettingsTransform) -> Unit,
    onNavigate: (String) -> Unit,
    onStartDemo: (() -> Unit)?,
    phonePairing: PhonePairing,
    onPairPhone: () -> Unit,
) {
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { ListHeader { Text(SETTINGS_ENTRY_LABEL) } }
        item { SettingChip(AUTO_START_SETTING_LABEL, yesNo(settings.autoStartRadar)) { onUpdate { it.copy(autoStartRadar = !it.autoStartRadar) } } }
        item { SettingChip("Mano", handednessLabel(settings.handedness)) { onUpdate { it.withHandedness(nextHandedness(it.handedness)) } } }
        item { SettingChip("Postura del reloj", postureLabel(settings.posture)) { onUpdate { it.copy(posture = nextPosture(it.posture)) } } }
        item { NavChip(CALIBRATE_POSTURE_LABEL) { onNavigate(ROUTE_CALIBRATE_POSTURE) } }
        item { SettingChip("Pantalla", screenModeLabel(settings.screenMode)) { onUpdate { it.copy(screenMode = toggledScreenMode(it.screenMode)) } } }
        item { SettingChip("Duración de partida", gameDurationLabel(settings.gameDurationMs)) { onUpdate { it.copy(gameDurationMs = nextGameDuration(it.gameDurationMs)) } } }
        item { SettingChip("Color de contactos", contactColorLabel(settings.contactColor)) { onUpdate { it.copy(contactColor = toggledContactColor(it.contactColor)) } } }
        item { SettingChip("Brújula", yesNo(settings.compass)) { onUpdate { it.copy(compass = !it.compass) } } }
        item { SettingChip("Filtro de fantasmas al caminar", yesNo(settings.dopplerFilter)) { onUpdate { it.copy(dopplerFilter = !it.dopplerFilter) } } }
        item { SettingChip("Vibración", usageLabel(settings.vibrationUsage)) { onUpdate { it.copy(vibrationUsage = toggledUsage(it.vibrationUsage)) } } }
        item { NavChip(VIBRATION_TEST_LABEL) { onNavigate(ROUTE_PRACTICE) } }
        DEFAULT_RADARS.forEach { radarItems(settings, it.radarId, onUpdate) }
        item { SettingChip("Cinturón", settings.beltAddress ?: "sin emparejar") { onUpdate { it.copy(beltAddress = null) } } }
        item { SettingChip(PAIR_PHONE_LABEL, phonePairingLabel(phonePairing), onPairPhone) }
        onStartDemo?.let { start -> item { NavChip(DEMO_LABEL, start) } }
        item { NavChip(SPIKES_ENTRY_LABEL) { onNavigate(ROUTE_SPIKES) } }
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
        Text(yawLabel(radarId, effectiveYawDeg(settings, radarId)), fontSize = 12.sp, fontFamily = BlindsideFonts.Mono)
        CompactChip(onClick = { onUpdate { it.withYawNudged(radarId, YAW_STEP_DEG) } }, label = { Text("+5°") })
    }
}
