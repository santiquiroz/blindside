package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.config.ImuParams
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.config.TuningParams
import io.github.santiquiroz.blindside.core.scene.Confidence
import io.github.santiquiroz.blindside.core.scene.Side
import io.github.santiquiroz.blindside.core.sim.Scenario
import io.github.santiquiroz.blindside.core.sim.Scenarios
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScenarioTest {
    @Test
    fun `crossing person is confirmed within 0_7 s, alerts once on the left and keeps one id`() {
        val run = runScenario(Scenarios.crossing())

        assertEquals(1, run.confirmations.size)
        assertTrue(scenarioMsOf(run.confirmations.single().tNanos) <= Scenarios.WARMUP_MS + 700)
        assertEquals(listOf(Side.LEFT), run.alerts.map { it.side })
        val ids = run.scenes.flatMap { (_, scene) -> scene.blips.map { it.displayId } }.toSet()
        assertEquals(1, ids.size)
        assertTrue(run.sceneAt(7_400).blips.single().bearingDeg > 20.0)
    }

    @Test
    fun `turning with a still object in view never confirms it`() {
        val run = runScenario(Scenarios.turningWithStillTarget())

        assertTrue(run.confirmations.isEmpty())
        assertTrue(run.alerts.isEmpty())
    }

    @Test
    fun `turning keeps a confirmed contact fixed in the world and does not re-alert`() {
        val run = runScenario(Scenarios.turningWithMarcher())

        assertEquals(listOf(Side.CENTER), run.alerts.map { it.side })
        assertEquals(-45.0, run.sceneAt(7_600).blips.single().bearingDeg, 8.0)
    }

    @Test
    fun `walking toward a wall confirms nothing`() {
        val run = runScenario(Scenarios.walkingTowardWall())

        assertTrue(run.confirmations.isEmpty())
        assertTrue(run.alerts.isEmpty())
    }

    @Test
    fun `a rival walking head on is confirmed and alerts once the player stops`() {
        val run = runScenario(Scenarios.headOnRival())

        assertEquals(1, run.confirmations.size)
        assertEquals(listOf(Side.CENTER), run.alerts.map { it.side })
        assertTrue(scenarioMsOf(run.alerts.single().tNanos) > Scenarios.WARMUP_MS + 1_500)
    }

    @Test
    fun `two people at the same range get two ids and two vibrations`() {
        val run = runScenario(Scenarios.twoPeopleSameRange())

        assertEquals(2, run.confirmations.map { it.displayId }.toSet().size)
        assertEquals(2, run.alerts.size)
    }

    @Test
    fun `a person who stops is held still for the pause and reacquired without a second alert`() {
        val run = runScenario(Scenarios.personStops())

        assertEquals(1, run.confirmations.size)
        assertEquals(1, run.alerts.size)
        val paused = run.sceneAt(Scenarios.WARMUP_MS + 4_800).blips.single()
        assertEquals(Confidence.COASTING, paused.confidence)
        assertEquals(1, run.sceneAt(Scenarios.WARMUP_MS + 6_500).blips.size)
    }

    @Test
    fun `a target leaving the cone shows as out of view, then disappears`() {
        val run = runScenario(Scenarios.targetExitsCone())

        assertEquals(listOf(Side.RIGHT), run.alerts.map { it.side })
        assertTrue(run.sceneAt(Scenarios.WARMUP_MS + 5_200).blips.single().outOfView)
        assertTrue(run.sceneAt(Scenarios.WARMUP_MS + 10_400).blips.isEmpty())
    }

    @Test
    fun `MVP - a 90 degree turn in 0_5 s in front of a wall gives no alert`() {
        tausOffBy100.forEach { config ->
            val run = runWith(Scenarios.wallDuringTurn(), config)

            assertTrue(run.alerts.isEmpty(), "tau ${config.tuning.imu.radarImuDelayMs}")
        }
    }

    @Test
    fun `MVP - a walker confirmed before the turn gives no extra alert`() {
        tausOffBy100.forEach { config ->
            val run = runWith(Scenarios.walkerConfirmedBeforeTurn(), config)

            assertEquals(1, run.alerts.size, "tau ${config.tuning.imu.radarImuDelayMs}")
            assertTrue(scenarioMsOf(run.alerts.single().tNanos) < Scenarios.TURN_START_MS)
        }
    }

    @Test
    fun `MVP - a walker who appears during the turn alerts once, within 1 s of the end of the tail`() {
        tausOffBy100.forEach { config ->
            val run = runWith(Scenarios.walkerAppearsDuringTurn(), config)

            assertEquals(1, run.alerts.size, "tau ${config.tuning.imu.radarImuDelayMs}")
            val alertMs = scenarioMsOf(run.alerts.single().tNanos)
            assertTrue(alertMs in Scenarios.TURN_END_MS + 500..Scenarios.TURN_END_MS + 1_500, "alert at $alertMs ms")
        }
    }

    @Test
    fun `MVP - a rival who appears while the player walks alerts once after the player stops`() {
        tausOffBy100.forEach { config ->
            val run = runWith(Scenarios.rivalWhileWalking(), config)

            assertEquals(1, run.alerts.size, "tau ${config.tuning.imu.radarImuDelayMs}")
            assertTrue(scenarioMsOf(run.alerts.single().tNanos) > Scenarios.WARMUP_MS + 2_000)
        }
    }

    private val tausOffBy100: List<PipelineConfig>
        get() = listOf(0L, 200L).map { tau -> PipelineConfig(TuningParams(imu = ImuParams(radarImuDelayMs = tau))) }

    private fun runWith(scenario: Scenario, config: PipelineConfig): ScenarioRun = runScenario(scenario, config.copy(mounts = scenario.mounts))
}
