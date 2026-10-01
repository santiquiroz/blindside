package io.github.santiquiroz.blindside.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import io.github.santiquiroz.blindside.core.scene.Side
import io.github.santiquiroz.blindside.wear.haptics.HapticPlayer
import io.github.santiquiroz.blindside.wear.haptics.dndMaySilenceNow
import io.github.santiquiroz.blindside.wear.haptics.patternFor
import io.github.santiquiroz.blindside.wear.practice.QuizState
import io.github.santiquiroz.blindside.wear.practice.answer
import io.github.santiquiroz.blindside.wear.practice.currentSide
import io.github.santiquiroz.blindside.wear.practice.isFinished
import io.github.santiquiroz.blindside.wear.practice.newQuiz
import io.github.santiquiroz.blindside.shared.settings.AppSettings
import kotlin.random.Random

@Composable
fun PracticeScreen(settings: AppSettings) {
    val context = LocalContext.current
    val player = remember(settings.vibrationUsage) { HapticPlayer.create(context, settings.vibrationUsage) }
    val dndWarning = dndMaySilenceNow(context, settings.vibrationUsage)
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { ListHeader { Text(VIBRATION_TEST_LABEL) } }
        if (dndWarning) item { Text(DND_WARNING_MESSAGE, color = WARNING_AMBER) }
        item { Notice(motorLabel(player.hasAmplitudeControl(), player.supportsPrimitives())) }
        PRACTICE_RHYTHMS.forEach { rhythm -> item { NavChip("Probar ${rhythm.label}") { player.play(rhythm.pattern) } } }
        item { QuizPanel(player) }
    }
}

@Composable
private fun QuizPanel(player: HapticPlayer) {
    var quiz by remember { mutableStateOf<QuizState?>(null) }
    val current = quiz
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            current == null -> NavChip("Empezar quiz") { quiz = newQuiz(Random.Default) }
            isFinished(current) -> QuizResult(current) { quiz = newQuiz(Random.Default) }
            else -> QuizRound(current, player) { side -> quiz = answer(current, side) }
        }
    }
}

@Composable
private fun QuizRound(state: QuizState, player: HapticPlayer, onAnswer: (Side) -> Unit) {
    Notice(quizProgressLabel(state))
    state.lastAnswer?.let { Notice(answerFeedback(it)) }
    NavChip("Vibrar") { currentSide(state)?.let { player.play(patternFor(it)) } }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Side.entries.forEach { side ->
            CompactChip(onClick = { onAnswer(side) }, label = { Text(sideShortLabel(side)) })
        }
    }
}

@Composable
private fun QuizResult(state: QuizState, onRestart: () -> Unit) {
    Notice(quizResultLabel(state))
    NavChip("Repetir quiz", onRestart)
}
