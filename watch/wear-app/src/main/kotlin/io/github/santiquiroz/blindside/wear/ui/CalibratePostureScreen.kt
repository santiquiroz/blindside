package io.github.santiquiroz.blindside.wear.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import io.github.santiquiroz.blindside.shared.sensors.GravityTemplate
import io.github.santiquiroz.blindside.shared.sensors.Vec3
import io.github.santiquiroz.blindside.shared.sensors.captureGravityTemplate
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import io.github.santiquiroz.blindside.shared.settings.SettingsTransform
import io.github.santiquiroz.blindside.shared.theme.BlindsideColors
import io.github.santiquiroz.blindside.shared.theme.BlindsideFonts
import kotlinx.coroutines.delay

private const val CALIBRATE_SECONDS = 3
private const val ONE_SECOND_MS = 1_000L
private val COUNTDOWN_SIZE = 48.sp
private val SCREEN_PADDING = 16.dp

private enum class CalibratePhase { READY, COLLECTING, SAVED, FAILED }

@Composable
fun CalibratePostureScreen(settings: AppSettings, onUpdate: (SettingsTransform) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    var phase by remember { mutableStateOf(CalibratePhase.READY) }
    var secondsLeft by remember { mutableIntStateOf(CALIBRATE_SECONDS) }
    LaunchedEffect(phase) {
        if (phase != CalibratePhase.COLLECTING) return@LaunchedEffect
        val samples = collectGravitySamples(context, CALIBRATE_SECONDS) { secondsLeft = it }
        phase = saveOrFail(captureGravityTemplate(samples), onUpdate)
    }
    CalibrateContent(phase, secondsLeft, settings.postureTemplate != null, onDone) {
        secondsLeft = CALIBRATE_SECONDS
        phase = CalibratePhase.COLLECTING
    }
}

private fun saveOrFail(template: GravityTemplate?, onUpdate: (SettingsTransform) -> Unit): CalibratePhase {
    if (template == null) return CalibratePhase.FAILED
    onUpdate { it.copy(postureTemplate = template) }
    return CalibratePhase.SAVED
}

@Composable
private fun CalibrateContent(
    phase: CalibratePhase,
    secondsLeft: Int,
    alreadyCalibrated: Boolean,
    onDone: () -> Unit,
    onStart: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(SCREEN_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        when (phase) {
            CalibratePhase.READY -> ReadyPanel(alreadyCalibrated, onStart)
            CalibratePhase.COLLECTING -> CollectingPanel(secondsLeft)
            CalibratePhase.SAVED -> DonePanel(POSTURE_SAVED_MESSAGE, DONE_LABEL, onDone)
            CalibratePhase.FAILED -> DonePanel(POSTURE_CALIBRATE_FAILED_MESSAGE, RETRY_LABEL, onStart)
        }
    }
}

@Composable
private fun ReadyPanel(alreadyCalibrated: Boolean, onStart: () -> Unit) {
    Text(CALIBRATE_POSTURE_LABEL, color = BlindsideColors.Text, textAlign = TextAlign.Center, fontSize = 15.sp)
    if (alreadyCalibrated) Notice(POSTURE_ALREADY_SET_MESSAGE)
    PrimaryChip(CALIBRATE_HOLD_LABEL, onStart)
}

@Composable
private fun CollectingPanel(secondsLeft: Int) {
    Notice(CALIBRATE_HOLD_LABEL)
    Text(
        secondsLeft.toString(),
        color = BlindsideColors.Accent,
        fontFamily = BlindsideFonts.Mono,
        fontSize = COUNTDOWN_SIZE,
    )
}

@Composable
private fun DonePanel(message: String, actionLabel: String, onAction: () -> Unit) {
    Notice(message)
    PrimaryChip(actionLabel, onAction)
}

@Composable
private fun PrimaryChip(label: String, onClick: () -> Unit) {
    Chip(
        label = { Text(label, maxLines = 2) },
        onClick = onClick,
        colors = ChipDefaults.primaryChipColors(),
    )
}

// Sensor events and this coroutine both land on the main thread, so the list is read only after the listener is off.
private suspend fun collectGravitySamples(context: Context, seconds: Int, onTick: (Int) -> Unit): List<Vec3> {
    val manager = context.getSystemService(SensorManager::class.java) ?: return emptyList()
    val sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: return emptyList()
    val samples = mutableListOf<Vec3>()
    val listener = gravityCollector { samples.add(it) }
    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
    try {
        for (remaining in seconds downTo 1) {
            onTick(remaining)
            delay(ONE_SECOND_MS)
        }
    } finally {
        manager.unregisterListener(listener)
    }
    return samples.toList()
}

private fun gravityCollector(onSample: (Vec3) -> Unit): SensorEventListener = object : SensorEventListener {
    override fun onSensorChanged(event: SensorEvent) {
        onSample(Vec3(event.values[0], event.values[1], event.values[2]))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
