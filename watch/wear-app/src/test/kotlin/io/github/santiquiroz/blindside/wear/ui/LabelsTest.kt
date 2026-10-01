package io.github.santiquiroz.blindside.wear.ui

import io.github.santiquiroz.blindside.core.scene.Side
import io.github.santiquiroz.blindside.wear.practice.QuizAnswer
import io.github.santiquiroz.blindside.wear.practice.QuizState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LabelsTest {
    @Test
    fun `wrong answers name the expected side`() {
        assertEquals("Correcto", answerFeedback(QuizAnswer(Side.LEFT, Side.LEFT)))
        assertEquals("Era derecha", answerFeedback(QuizAnswer(Side.RIGHT, Side.CENTER)))
    }

    @Test
    fun `the quiz result says whether it passed`() {
        val passedQuiz = QuizState(sequence = List(10) { Side.LEFT }, index = 10, correct = 9)
        val failedQuiz = passedQuiz.copy(correct = 8)
        assertEquals("9/10: aprobado", quizResultLabel(passedQuiz))
        assertEquals("8/10: repite", quizResultLabel(failedQuiz))
    }

    @Test
    fun `progress counts from one`() {
        assertEquals("Intento 1 de 10", quizProgressLabel(QuizState(sequence = List(10) { Side.LEFT })))
    }

    @Test
    fun `the motor label reports both capabilities`() {
        assertEquals("Motor: amplitud sí, primitivas no", motorLabel(amplitudeControl = true, primitives = false))
    }

    @Test
    fun `answer chips fit three letters`() {
        assertTrue(Side.entries.all { sideShortLabel(it).length <= 3 })
    }

    @Test
    fun `practice offers every rhythm including the system buzz`() {
        assertEquals(listOf("izquierda", "centro", "derecha", "sistema"), PRACTICE_RHYTHMS.map { it.label })
    }
}
