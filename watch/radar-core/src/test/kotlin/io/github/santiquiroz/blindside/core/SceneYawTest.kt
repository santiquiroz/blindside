package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.config.DecodeParams
import io.github.santiquiroz.blindside.core.config.Handedness
import io.github.santiquiroz.blindside.core.config.TrackingParams
import io.github.santiquiroz.blindside.core.config.defaultMounts
import io.github.santiquiroz.blindside.core.scene.MotionState
import io.github.santiquiroz.blindside.core.scene.SceneInputs
import io.github.santiquiroz.blindside.core.scene.SensorStatus
import io.github.santiquiroz.blindside.core.scene.buildScene
import io.github.santiquiroz.blindside.core.sim.Scenarios
import io.github.santiquiroz.blindside.core.sim.simArrivalNanos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SceneYawTest {
    @Test
    fun `a 90 degree right turn with belt imus alive raises body yaw about 90 degrees from the belt`() {
        val run = runScenario(Scenarios.wallDuringTurn())

        val before = run.sceneAt(Scenarios.TURN_START_MS - 100).bodyYawDeg
        val after = run.sceneAt(Scenarios.TURN_END_MS + 600)

        assertEquals(90.0, after.bodyYawDeg - before, 10.0)
        assertTrue(after.yawFromBelt)
    }

    @Test
    fun `without link the scene yaw does not come from the belt`() {
        val run = runScenario(Scenarios.wallDuringTurn())
        val nanos = simArrivalNanos(Scenarios.TURN_END_MS + 600, 0)
        run.pipeline.onLinkState(false, nanos)

        assertFalse(run.pipeline.scene(nanos).yawFromBelt)
    }

    @Test
    fun `a turn read from the watch gyro does not come from the belt`() {
        val run = runScenario(Scenarios.wallDuringTurn()) { it.copy(flags = it.flags and 0x03) }

        assertFalse(run.sceneAt(Scenarios.TURN_END_MS + 600).yawFromBelt)
    }

    @Test
    fun `buildScene passes body yaw and the belt flag through`() {
        val bothAlive = listOf(SensorStatus(0, true), SensorStatus(1, true))
        val inputs = SceneInputs(
            nowMs = 1_000, tracks = emptyList(), yawDeg = 33.0, mounts = defaultMounts(Handedness.RIGHT),
            radars = bothAlive, imus = bothAlive, motion = MotionState.STILL,
            warnings = emptySet(), linkUp = true, eliminated = false, yawFromBelt = true,
        )

        val scene = buildScene(inputs, TrackingParams(), DecodeParams())

        assertEquals(33.0, scene.bodyYawDeg, 1e-9)
        assertTrue(scene.yawFromBelt)
    }
}
