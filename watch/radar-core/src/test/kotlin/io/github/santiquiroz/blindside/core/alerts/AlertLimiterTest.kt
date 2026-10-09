package io.github.santiquiroz.blindside.core.alerts

import io.github.santiquiroz.blindside.core.config.AlertParams
import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.scene.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlertLimiterTest {
    private val params = AlertParams()
    private val ms = 1_000_000L
    private val t0 = 50_000L * ms

    @Test
    fun `the first alert fires immediately`() {
        val outcome = AlertLimiter().step(AlertFrame(listOf(left(1)), t0), params)

        assertEquals(ContactAlert(1, Side.LEFT, t0), outcome.fired)
    }

    @Test
    fun `left at 0 and center at 0_25 s vibrate the center at 1 s`() {
        val fired = run(
            0L to listOf(left(1)),
            250L to listOf(left(1), center(2)),
            500L to listOf(left(1), center(2)),
            1_000L to listOf(left(1), center(2)),
        )

        assertEquals(listOf(0L to 1, 1_000L to 2), fired)
    }

    @Test
    fun `left at 0, center at 0_25 s and right at 0_5 s vibrate center at 1 s and right at 2 s`() {
        val all = listOf(left(1), center(2), right(3))
        val fired = run(
            0L to listOf(left(1)),
            250L to listOf(left(1), center(2)),
            500L to all,
            1_000L to all,
            1_500L to all,
            2_000L to all,
        )

        assertEquals(listOf(0L to 1, 1_000L to 2, 2_000L to 3), fired)
    }

    @Test
    fun `a pending contact missing only at another contact's slot still fires at its own turn`() {
        val all = listOf(left(1), center(2), right(3))
        val fired = run(
            0L to listOf(left(1)),
            250L to listOf(left(1), center(2)),
            500L to all,
            1_000L to listOf(left(1), center(2)),
            1_500L to all,
            2_000L to all,
        )

        assertEquals(listOf(0L to 1, 1_000L to 2, 2_000L to 3), fired)
    }

    @Test
    fun `a pending contact held for a moment at its turn fires once it is confirmed again`() {
        val first = AlertLimiter().step(AlertFrame(listOf(left(1)), t0), params).limiter
        val queued = first.step(AlertFrame(listOf(left(1), right(2)), t0 + 500 * ms), params).limiter

        val held = queued.step(AlertFrame(listOf(left(1)), t0 + 1_000 * ms, heldIds = setOf(2)), params)
        val back = held.limiter.step(AlertFrame(listOf(left(1), right(2)), t0 + 1_100 * ms), params)

        assertNull(held.fired)
        assertEquals(ContactAlert(2, Side.RIGHT, t0 + 1_100 * ms), back.fired)
    }

    @Test
    fun `a pending contact gone before its slot only shows on screen, even if it comes back`() {
        val fired = run(
            0L to listOf(left(1)),
            500L to listOf(left(1), right(2)),
            1_000L to listOf(left(1)),
            1_500L to listOf(left(1), right(2)),
            2_500L to listOf(left(1), right(2)),
        )

        assertEquals(listOf(0L to 1), fired)
    }

    @Test
    fun `a person lost for 2 s who reappears with a new id vibrates once`() {
        val fired = run(
            0L to listOf(left(1)),
            100L to emptyList(),
            2_000L to listOf(left(9, at = Point2(-2.5, 3.2))),
        )

        assertEquals(listOf(0L to 1), fired)
    }

    @Test
    fun `a person lost for 4 s who reappears less than 1_5 m away vibrates once`() {
        val fired = run(
            0L to listOf(left(1)),
            100L to emptyList(),
            4_000L to listOf(left(9, at = Point2(-3.0, 3.5))),
        )

        assertEquals(listOf(0L to 1), fired)
    }

    @Test
    fun `someone else born on the same side more than 1_5 m away before 5 s vibrates`() {
        val fired = run(
            0L to listOf(left(1)),
            100L to emptyList(),
            3_000L to listOf(left(9, at = Point2(-2.5, 5.0))),
        )

        assertEquals(listOf(0L to 1, 3_000L to 9), fired)
    }

    @Test
    fun `a contact back after the 5 s sector pause vibrates again as someone new`() {
        val fired = run(
            0L to listOf(left(1)),
            100L to emptyList(),
            5_200L to listOf(left(9)),
        )

        assertEquals(listOf(0L to 1, 5_200L to 9), fired)
    }

    @Test
    fun `the lost contact's sector is judged with the current yaw, so a turn does not hide a reacquired contact`() {
        val before = AlertLimiter().step(AlertFrame(listOf(right(1)), t0), params).limiter
        val lost = before.step(AlertFrame(emptyList(), t0 + 100 * ms), params).limiter

        val afterTurn = lost.step(AlertFrame(listOf(AlertCandidate(5, Side.LEFT, 3.0, Point2(2.7, 2.2))), t0 + 3_000 * ms, yawDeg = 90.0), params)

        assertNull(afterTurn.fired)
    }

    @Test
    fun `while the player moves a contact keeps the position it had when the player was still`() {
        val still = AlertLimiter().step(AlertFrame(listOf(left(1)), t0), params).limiter
        val dragged = still.step(AlertFrame(listOf(left(1, at = Point2(-0.5, 3.0))), t0 + 100 * ms, playerMoving = true), params).limiter
        val lost = dragged.step(AlertFrame(emptyList(), t0 + 200 * ms), params).limiter

        val reborn = lost.step(AlertFrame(listOf(left(7, at = Point2(-2.8, 2.6))), t0 + 1_500 * ms), params)

        assertNull(reborn.fired)
    }

    @Test
    fun `a system alert at 0_5 s does not move the gap and the pending contact waits for the pattern to end`() {
        val first = AlertLimiter().step(AlertFrame(listOf(left(1)), t0), params).limiter
        val queued = first.step(AlertFrame(listOf(left(1), center(2)), t0 + 250 * ms), params).limiter
        val buzzing = queued.withSystemAlert(t0 + 500 * ms, params)

        val atOneSecond = buzzing.step(AlertFrame(listOf(left(1), center(2)), t0 + 1_000 * ms), params)
        val afterPattern = atOneSecond.limiter.step(AlertFrame(listOf(left(1), center(2)), t0 + 1_700 * ms), params)

        assertNull(atOneSecond.fired)
        assertEquals(ContactAlert(2, Side.CENTER, t0 + 1_700 * ms), afterPattern.fired)
        assertEquals(t0 + 1_700 * ms, afterPattern.limiter.lastFiredNanos)
    }

    @Test
    fun `the 11th alert in a rolling minute does not vibrate and the limiter is saturated`() {
        val fired = (0 until 11).fold(AlertLimiter() to 0) { (limiter, count), i ->
            val outcome = limiter.step(AlertFrame(listOf(left(100 + i, at = Point2(-3.0 - i * 2.0, 3.0))), t0 + i * 5_100L * ms), params)
            outcome.limiter to count + (if (outcome.fired != null) 1 else 0)
        }

        assertEquals(10, fired.second)
        assertTrue(fired.first.isSaturated(t0 + 51_000 * ms, params))
        assertFalse(fired.first.isSaturated(t0 + 120_000 * ms, params))
    }

    @Test
    fun `one alert per display id`() {
        val first = AlertLimiter().step(AlertFrame(listOf(left(1)), t0), params).limiter

        val again = first.step(AlertFrame(listOf(left(1)), t0 + 10_000 * ms), params)

        assertNull(again.fired)
    }

    @Test
    fun `center wins over the sides and the nearest wins within a side`() {
        val candidates = listOf(left(1, range = 1.5), center(2, range = 5.0), right(3, range = 1.0))

        assertEquals(2, AlertLimiter().step(AlertFrame(candidates, t0), params).fired?.displayId)
        assertEquals(3, AlertLimiter().step(AlertFrame(listOf(left(1, range = 2.5), right(3, range = 1.0)), t0), params).fired?.displayId)
    }

    @Test
    fun `sides split at plus and minus 20 degrees`() {
        assertEquals(Side.CENTER, sideOf(-20.0, params))
        assertEquals(Side.LEFT, sideOf(-21.0, params))
        assertEquals(Side.RIGHT, sideOf(35.0, params))
    }

    @Test
    fun `a far contact at 8 m gets one far alert`() {
        val first = AlertLimiter().step(AlertFrame(listOf(far(1)), t0), params)
        val again = first.limiter.step(AlertFrame(listOf(far(1)), t0 + 10_000 * ms), params)

        assertEquals(ContactAlert(1, Side.LEFT, t0, far = true), first.fired)
        assertNull(again.fired)
    }

    @Test
    fun `a far contact that later steps within 6 m gets its near alert once`() {
        val fired = alerts(
            0L to listOf(far(1)),
            2_000L to listOf(left(1, range = 5.0)),
            4_000L to listOf(left(1, range = 4.0)),
            6_000L to listOf(far(1)),
        )

        assertEquals(listOf(ContactAlert(1, Side.LEFT, t0, far = true), ContactAlert(1, Side.LEFT, t0 + 2_000 * ms)), fired)
    }

    @Test
    fun `a near contact 300 ms after a far alert fires at once`() {
        val afterFar = AlertLimiter().step(AlertFrame(listOf(far(1)), t0), params).limiter
        val near = afterFar.step(AlertFrame(listOf(far(1), right(2)), t0 + 300 * ms), params)

        assertNull(afterFar.lastFiredNanos)
        assertEquals(ContactAlert(2, Side.RIGHT, t0 + 300 * ms), near.fired)
    }

    @Test
    fun `seven far contacts in a minute give six far alerts and leave near alerts alone`() {
        val farSteps = (0 until 7).map { i -> i * 5_000L to listOf(far(10 + i)) }
        val fired = alerts(
            *farSteps.toTypedArray(),
            30_300L to listOf(far(16), left(1)),
            31_500L to listOf(far(16)),
            95_000L to listOf(far(16)),
        )

        assertEquals((10..15).toList(), fired.filter { it.far }.map { it.displayId })
        assertEquals(listOf(ContactAlert(1, Side.LEFT, t0 + 30_300 * ms)), fired.filterNot { it.far })
    }

    @Test
    fun `far alerts do not count toward near saturation`() {
        val afterFar = (0 until 6).fold(AlertLimiter()) { limiter, i ->
            limiter.step(AlertFrame(listOf(far(20 + i)), t0 + i * 2_000L * ms), params).limiter
        }
        val near = (0 until 10).fold(afterFar to 0) { (limiter, count), i ->
            val frame = AlertFrame(listOf(left(100 + i, at = Point2(-3.0 - i * 2.0, 3.0))), t0 + (12_000L + i * 1_100L) * ms)
            val outcome = limiter.step(frame, params)
            outcome.limiter to count + (if (outcome.fired != null) 1 else 0)
        }

        assertEquals(6, afterFar.farFiredNanos.size)
        assertFalse(afterFar.isSaturated(t0 + 12_000 * ms, params))
        assertEquals(10, near.second)
    }

    @Test
    fun `when a near and a far contact are both due the near one fires and the far one waits for the near gap`() {
        val fired = alerts(
            0L to listOf(far(1, side = Side.CENTER), right(2)),
            500L to listOf(far(1, side = Side.CENTER), right(2)),
            1_000L to listOf(far(1, side = Side.CENTER), right(2)),
        )

        assertEquals(listOf(ContactAlert(2, Side.RIGHT, t0), ContactAlert(1, Side.CENTER, t0 + 1_000 * ms, far = true)), fired)
    }

    @Test
    fun `a far alert waits for the system pattern like a near one`() {
        val buzzing = AlertLimiter().withSystemAlert(t0, params)

        val during = buzzing.step(AlertFrame(listOf(far(1)), t0 + 500 * ms), params)
        val after = during.limiter.step(AlertFrame(listOf(far(1)), t0 + 1_300 * ms), params)

        assertNull(during.fired)
        assertEquals(ContactAlert(1, Side.LEFT, t0 + 1_300 * ms, far = true), after.fired)
    }

    @Test
    fun `the far stage picks the center first, then the nearest`() {
        val candidates = listOf(far(1, range = 7.0), far(2, range = 9.0, side = Side.CENTER), far(3, range = 6.5, side = Side.RIGHT))

        assertEquals(2, AlertLimiter().step(AlertFrame(candidates, t0), params).fired?.displayId)
        assertEquals(3, AlertLimiter().step(AlertFrame(listOf(candidates[0], candidates[2]), t0), params).fired?.displayId)
    }

    @Test
    fun `a contact first seen near never gets a far alert`() {
        val fired = alerts(
            0L to listOf(left(1)),
            2_000L to listOf(left(1, range = 8.0)),
            4_000L to listOf(left(1, range = 9.0)),
        )

        assertEquals(listOf(ContactAlert(1, Side.LEFT, t0)), fired)
    }

    @Test
    fun `a queued near contact that steps beyond 6 m keeps its near alert`() {
        val fired = alerts(
            0L to listOf(left(1)),
            250L to listOf(left(1), center(2)),
            600L to listOf(left(1), center(2, range = 8.0)),
            1_000L to listOf(left(1), center(2, range = 8.0)),
        )

        assertEquals(listOf(ContactAlert(1, Side.LEFT, t0), ContactAlert(2, Side.CENTER, t0 + 1_000 * ms)), fired)
    }

    @Test
    fun `a far contact seen next to an alerted one is never taken for it once that one is lost`() {
        val alerted = left(1, range = 5.5, at = Point2(-3.9, 3.9))
        val beside = far(5, range = 6.5, at = Point2(-4.6, 4.6))
        val fired = alerts(
            0L to listOf(alerted, beside),
            100L to listOf(beside),
            1_000L to listOf(beside),
            2_000L to listOf(left(5, range = 4.0, at = Point2(-2.8, 2.8))),
        )

        val expected = listOf(
            ContactAlert(1, Side.LEFT, t0),
            ContactAlert(5, Side.LEFT, t0 + 1_000 * ms, far = true),
            ContactAlert(5, Side.LEFT, t0 + 2_000 * ms),
        )
        assertEquals(expected, fired)
    }

    @Test
    fun `a track reborn far through the sector pause inherits the near alert and gets no far alert`() {
        val fired = alerts(
            0L to listOf(left(1, range = 5.5, at = Point2(-3.9, 3.9))),
            100L to emptyList(),
            2_000L to listOf(far(9, range = 6.5, at = Point2(-4.6, 4.6))),
            3_000L to listOf(left(9, range = 4.0, at = Point2(-2.8, 2.8))),
        )

        assertEquals(listOf(ContactAlert(1, Side.LEFT, t0)), fired)
    }

    @Test
    fun `eliminated contacts get no far alert either`() {
        val silenced = AlertLimiter().silence(listOf(1, 2))

        val outcome = silenced.step(AlertFrame(listOf(far(1), left(2)), t0), params)

        assertNull(outcome.fired)
    }

    private fun alerts(vararg steps: Pair<Long, List<AlertCandidate>>): List<ContactAlert> =
        steps.fold(AlertLimiter() to emptyList<ContactAlert>()) { (limiter, fired), (atMs, candidates) ->
            val outcome = limiter.step(AlertFrame(candidates, t0 + atMs * ms), params)
            outcome.limiter to fired + listOfNotNull(outcome.fired)
        }.second

    private fun run(vararg steps: Pair<Long, List<AlertCandidate>>): List<Pair<Long, Int>> =
        steps.fold(AlertLimiter() to emptyList<Pair<Long, Int>>()) { (limiter, fired), (atMs, candidates) ->
            val outcome = limiter.step(AlertFrame(candidates, t0 + atMs * ms), params)
            outcome.limiter to fired + listOfNotNull(outcome.fired?.let { atMs to it.displayId })
        }.second

    private fun left(id: Int, range: Double = 3.0, at: Point2 = Point2(-2.5, 2.5)) = AlertCandidate(id, Side.LEFT, range, at)

    private fun center(id: Int, range: Double = 3.0) = AlertCandidate(id, Side.CENTER, range, Point2(0.0, range))

    private fun right(id: Int, range: Double = 3.0) = AlertCandidate(id, Side.RIGHT, range, Point2(2.5, 2.5))

    private fun far(id: Int, range: Double = 8.0, side: Side = Side.LEFT, at: Point2 = Point2(-5.6, 5.7)) = AlertCandidate(id, side, range, at)
}
