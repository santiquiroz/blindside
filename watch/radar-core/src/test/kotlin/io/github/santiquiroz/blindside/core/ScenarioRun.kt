package io.github.santiquiroz.blindside.core

import io.github.santiquiroz.blindside.core.alerts.ContactAlert
import io.github.santiquiroz.blindside.core.alerts.PipelineEvent
import io.github.santiquiroz.blindside.core.alerts.TrackConfirmed
import io.github.santiquiroz.blindside.core.config.PipelineConfig
import io.github.santiquiroz.blindside.core.protocol.Bundle
import io.github.santiquiroz.blindside.core.protocol.BundleDecoder
import io.github.santiquiroz.blindside.core.protocol.BundleEncoder
import io.github.santiquiroz.blindside.core.scene.RadarScene
import io.github.santiquiroz.blindside.core.sim.SIM_PHONE_START_NANOS
import io.github.santiquiroz.blindside.core.sim.Scenario
import io.github.santiquiroz.blindside.core.sim.SimWatchGyro
import io.github.santiquiroz.blindside.core.sim.simulate
import io.github.santiquiroz.blindside.core.sim.simulateWatchGyro

sealed interface SimInput {
    val nanos: Long
}

data class PacketInput(val bytes: ByteArray, override val nanos: Long) : SimInput

data class GyroInput(val sample: SimWatchGyro) : SimInput {
    override val nanos: Long get() = sample.eventNanos
}

data class ScenarioRun(val events: List<PipelineEvent>, val scenes: List<Pair<Long, RadarScene>>, val pipeline: RadarPipeline) {
    val alerts: List<ContactAlert> get() = events.filterIsInstance<ContactAlert>()
    val confirmations: List<TrackConfirmed> get() = events.filterIsInstance<TrackConfirmed>()

    fun sceneAt(scenarioMs: Long): RadarScene = scenes.last { it.first <= scenarioMs }.second
}

fun scenarioMsOf(nanos: Long): Long = (nanos - SIM_PHONE_START_NANOS) / 1_000_000L

fun scenarioInputs(scenario: Scenario, rewrite: (Bundle) -> Bundle = { it }): List<SimInput> {
    val packets = simulate(scenario).map { PacketInput(BundleEncoder.encode(rewrite(BundleDecoder.decode(it.bytes)!!)), it.arrivalNanos) }
    return (packets + simulateWatchGyro(scenario).map { GyroInput(it) }).sortedBy { it.nanos }
}

fun runScenario(
    scenario: Scenario,
    config: PipelineConfig = PipelineConfig(mounts = scenario.mounts),
    rewrite: (Bundle) -> Bundle = { it },
): ScenarioRun {
    val pipeline = RadarPipeline(config)
    val inputs = scenarioInputs(scenario, rewrite)
    pipeline.onLinkState(true, inputs.first().nanos)
    return inputs.fold(ScenarioRun(emptyList(), emptyList(), pipeline)) { run, input -> run.after(input) }
}

private fun ScenarioRun.after(input: SimInput): ScenarioRun = when (input) {
    is GyroInput -> also { pipeline.onWatchGyro(input.sample.x, input.sample.y, input.sample.z, input.nanos) }
    is PacketInput -> {
        val produced = pipeline.onBlePacket(input.bytes, input.nanos)
        copy(events = events + produced, scenes = scenes + (scenarioMsOf(input.nanos) to pipeline.scene(input.nanos)))
    }
}
