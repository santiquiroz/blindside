package io.github.santiquiroz.blindside.wear.practice

import io.github.santiquiroz.blindside.core.scene.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class QuizTest {
    private fun wrong(side: Side): Side = Side.entries[(side.ordinal + 1) % Side.entries.size]

    private fun play(quiz: QuizState, mistakes: Int): QuizState =
        quiz.sequence.foldIndexed(quiz) { index, state, side -> answer(state, if (index < mistakes) wrong(side) else side) }

    @Test
    fun `a new quiz has ten rounds with every side at least three times`() {
        val quiz = newQuiz(Random(7))
        assertEquals(QUIZ_LENGTH, quiz.sequence.size)
        Side.entries.forEach { side -> assertTrue(quiz.sequence.count { it == side } >= 3) }
    }

    @Test
    fun `a perfect run passes`() {
        val done = play(newQuiz(Random(1)), mistakes = 0)
        assertTrue(isFinished(done))
        assertTrue(passed(done))
        assertEquals(10, done.correct)
    }

    @Test
    fun `one mistake in ten still meets the ninety percent target`() {
        assertTrue(passed(play(newQuiz(Random(3)), mistakes = 1)))
    }

    @Test
    fun `two mistakes fail the quiz`() {
        val done = play(newQuiz(Random(2)), mistakes = 2)
        assertFalse(passed(done))
        assertEquals(8, done.correct)
    }

    @Test
    fun `the last answer is kept for feedback`() {
        val after = answer(QuizState(listOf(Side.LEFT, Side.RIGHT)), Side.CENTER)
        assertEquals(QuizAnswer(Side.LEFT, Side.CENTER), after.lastAnswer)
        assertFalse(after.lastAnswer!!.isCorrect)
        assertEquals(Side.RIGHT, currentSide(after))
    }

    @Test
    fun `answers after the end change nothing`() {
        val done = play(newQuiz(Random(4)), mistakes = 0)
        assertEquals(done, answer(done, Side.LEFT))
    }

    @Test
    fun `practice is due when never passed or older than twelve hours`() {
        assertTrue(needsPractice(lastPassedEpochMs = null, nowEpochMs = 0L))
        assertFalse(needsPractice(lastPassedEpochMs = 0L, nowEpochMs = PRACTICE_VALID_MS))
        assertTrue(needsPractice(lastPassedEpochMs = 0L, nowEpochMs = PRACTICE_VALID_MS + 1))
    }
}
