package io.github.santiquiroz.blindside.wear.practice

import io.github.santiquiroz.blindside.core.scene.Side
import kotlin.random.Random

const val QUIZ_LENGTH = 10
const val QUIZ_PASS_MIN_CORRECT = 9
const val PRACTICE_VALID_MS = 12 * 60 * 60 * 1_000L

data class QuizAnswer(val expected: Side, val given: Side) {
    val isCorrect: Boolean get() = expected == given
}

data class QuizState(
    val sequence: List<Side>,
    val index: Int = 0,
    val correct: Int = 0,
    val lastAnswer: QuizAnswer? = null,
)

fun newQuiz(random: Random, length: Int = QUIZ_LENGTH): QuizState =
    QuizState(List(length) { Side.entries[it % Side.entries.size] }.shuffled(random))

fun currentSide(state: QuizState): Side? = state.sequence.getOrNull(state.index)

fun isFinished(state: QuizState): Boolean = state.index >= state.sequence.size

fun answer(state: QuizState, given: Side): QuizState {
    val expected = currentSide(state) ?: return state
    val result = QuizAnswer(expected, given)
    val gained = if (result.isCorrect) 1 else 0
    return state.copy(index = state.index + 1, correct = state.correct + gained, lastAnswer = result)
}

fun passed(state: QuizState): Boolean = isFinished(state) && state.correct >= QUIZ_PASS_MIN_CORRECT

fun needsPractice(lastPassedEpochMs: Long?, nowEpochMs: Long): Boolean =
    lastPassedEpochMs == null || nowEpochMs - lastPassedEpochMs > PRACTICE_VALID_MS
