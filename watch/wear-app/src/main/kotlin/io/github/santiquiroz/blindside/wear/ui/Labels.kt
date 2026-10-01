package io.github.santiquiroz.blindside.wear.ui

import io.github.santiquiroz.blindside.core.scene.Side
import io.github.santiquiroz.blindside.wear.haptics.CENTER_PATTERN
import io.github.santiquiroz.blindside.wear.haptics.HapticPattern
import io.github.santiquiroz.blindside.wear.haptics.LEFT_PATTERN
import io.github.santiquiroz.blindside.wear.haptics.RIGHT_PATTERN
import io.github.santiquiroz.blindside.wear.haptics.SYSTEM_PATTERN
import io.github.santiquiroz.blindside.wear.practice.QuizAnswer
import io.github.santiquiroz.blindside.wear.practice.QuizState
import io.github.santiquiroz.blindside.wear.practice.passed

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
