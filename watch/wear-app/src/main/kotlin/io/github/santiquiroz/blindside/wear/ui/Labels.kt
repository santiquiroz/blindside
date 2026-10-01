package io.github.santiquiroz.blindside.wear.ui

import io.github.santiquiroz.blindside.core.scene.Side
import io.github.santiquiroz.blindside.shared.haptics.CENTER_PATTERN
import io.github.santiquiroz.blindside.shared.haptics.HapticPattern
import io.github.santiquiroz.blindside.shared.haptics.LEFT_PATTERN
import io.github.santiquiroz.blindside.shared.haptics.RIGHT_PATTERN
import io.github.santiquiroz.blindside.shared.haptics.SYSTEM_PATTERN
import io.github.santiquiroz.blindside.wear.practice.QuizAnswer
import io.github.santiquiroz.blindside.wear.practice.QuizState
import io.github.santiquiroz.blindside.wear.practice.passed
import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.wear.ble.BleStatus
import io.github.santiquiroz.blindside.wear.ble.needsRetry
import io.github.santiquiroz.blindside.wear.session.SessionSource
import io.github.santiquiroz.blindside.wear.session.SessionUiState
import io.github.santiquiroz.blindside.wear.session.StartError
import io.github.santiquiroz.blindside.core.config.RADAR_A
import io.github.santiquiroz.blindside.shared.settings.ScreenMode
import io.github.santiquiroz.blindside.shared.settings.VibrationUsage
import io.github.santiquiroz.blindside.shared.settings.WatchPosture
import kotlin.math.roundToInt

data class PracticeRhythm(val label: String, val pattern: HapticPattern)

const val DND_WARNING_MESSAGE = "No molestar activo: puede silenciar las alertas. Desactívalo o usa vibración tipo Alarma."

val PRACTICE_RHYTHMS: List<PracticeRhythm> = listOf(
    PracticeRhythm("izquierda", LEFT_PATTERN),
    PracticeRhythm("centro", CENTER_PATTERN),
    PracticeRhythm("derecha", RIGHT_PATTERN),
    PracticeRhythm("sistema", SYSTEM_PATTERN),
)

fun yesNo(value: Boolean): String = if (value) "sí" else "no"

fun sideLabel(side: Side): String = when (side) {
    Side.LEFT -> "izquierda"
    Side.CENTER -> "centro"
    Side.RIGHT -> "derecha"
}

fun sideShortLabel(side: Side): String = when (side) {
    Side.LEFT -> "IZQ"
    Side.CENTER -> "CEN"
    Side.RIGHT -> "DER"
}

fun quizProgressLabel(state: QuizState): String = "Intento ${state.index + 1} de ${state.sequence.size}"

fun answerFeedback(answer: QuizAnswer): String = if (answer.isCorrect) "Correcto" else "Era ${sideLabel(answer.expected)}"

fun quizResultLabel(state: QuizState): String =
    "${state.correct}/${state.sequence.size}: ${if (passed(state)) "aprobado" else "repite"}"

fun motorLabel(amplitudeControl: Boolean, primitives: Boolean): String =
    "Motor: amplitud ${yesNo(amplitudeControl)}, primitivas ${yesNo(primitives)}"

const val BLUETOOTH_DENIED_MESSAGE = "Sin permiso de Bluetooth la partida no arranca. Concédelo en los ajustes del reloj."
const val RECORDING_FAILED_MESSAGE = "La grabación falló; la partida sigue."
const val APPLY_ON_START_MESSAGE = "Mano, ángulos y signos se aplican al iniciar la partida."
const val PAIRING_FAILED_MESSAGE = "Clave incorrecta o ventana cerrada"
const val BOND_LOST_MESSAGE =
    "El cinturón olvidó este reloj: olvídalo en los ajustes Bluetooth del reloj y vuelve a emparejar"
const val MTU_TOO_LOW_MESSAGE = "MTU insuficiente"
const val LINK_HALTED_HEADLINE = "Enlace detenido"
const val SPIKES_ENTRY_LABEL = "Diagnóstico (spikes)"

fun bleStatusLabel(status: BleStatus): String = when (status) {
    BleStatus.IDLE -> "Sin sesión"
    BleStatus.BLUETOOTH_OFF -> "Bluetooth apagado"
    BleStatus.SEARCHING -> "Buscando cinturón (BOOT 3 s para emparejar)"
    BleStatus.PAIRING -> "Emparejando: escribe la clave de la etiqueta"
    BleStatus.PAIRING_FAILED -> PAIRING_FAILED_MESSAGE
    BleStatus.CONNECTING -> "Conectando…"
    BleStatus.STREAMING -> "Recibiendo datos"
    BleStatus.RECONNECTING -> "Reconectando…"
    BleStatus.BOND_LOST -> BOND_LOST_MESSAGE
    BleStatus.MTU_TOO_LOW -> MTU_TOO_LOW_MESSAGE
}

fun sessionHeadline(session: SessionUiState): String = when {
    session.source == SessionSource.DEMO -> "Demo"
    needsRetry(session.ble) -> LINK_HALTED_HEADLINE
    else -> bleStatusLabel(session.ble)
}

fun startErrorMessage(error: StartError): String = when (error) {
    StartError.BLUETOOTH_PERMISSION_MISSING -> BLUETOOTH_DENIED_MESSAGE
    StartError.BLUETOOTH_UNAVAILABLE -> "Este reloj no tiene Bluetooth disponible."
}

const val START_RADAR_LABEL = "Iniciar radar"
const val SETTINGS_ENTRY_LABEL = "Ajustes"
const val AUTO_START_SETTING_LABEL = "Iniciar radar al abrir"
const val VIBRATION_TEST_LABEL = "Probar vibraciones"
const val DEMO_LABEL = "Demo"

fun stopLabel(confirming: Boolean): String = if (confirming) "¿Detener? Toca otra vez" else "Detener partida"

fun handednessLabel(handedness: Handedness): String = when (handedness) {
    Handedness.RIGHT -> "Diestro"
    Handedness.LEFT -> "Zurdo"
    Handedness.SWITCHER -> "Cambia de hombro"
}

fun screenModeLabel(mode: ScreenMode): String = when (mode) {
    ScreenMode.SIGILO -> "Sigilo"
    ScreenMode.VISTA -> "Vista (siempre encendida)"
}

fun postureLabel(posture: WatchPosture): String = when (posture) {
    WatchPosture.NORMAL -> "Normal"
    WatchPosture.TACTICAL_LEFT -> "Táctica izquierda (+90°)"
    WatchPosture.TACTICAL_RIGHT -> "Táctica derecha (-90°)"
}

fun usageLabel(usage: VibrationUsage): String = when (usage) {
    VibrationUsage.ALARM -> "Alarma"
    VibrationUsage.NOTIFICATION -> "Notificación"
}

fun radarName(radarId: Int): String = if (radarId == RADAR_A) "Radar A (izq.)" else "Radar B (der.)"

fun yawLabel(radarId: Int, yawDeg: Double): String = "${radarLetter(radarId)} ${yawDeg.roundToInt()}°"

fun signLabel(sign: Int): String = if (sign < 0) "-1" else "+1"

private fun radarLetter(radarId: Int): String = if (radarId == RADAR_A) "A" else "B"
