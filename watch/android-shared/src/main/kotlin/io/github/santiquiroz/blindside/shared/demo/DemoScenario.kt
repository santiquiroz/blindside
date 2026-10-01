package io.github.santiquiroz.blindside.shared.demo

import io.github.santiquiroz.blindside.core.geometry.Point2
import io.github.santiquiroz.blindside.core.sim.Scenario
import io.github.santiquiroz.blindside.core.sim.SimPacket
import io.github.santiquiroz.blindside.core.sim.Stand
import io.github.santiquiroz.blindside.core.sim.marcher
import io.github.santiquiroz.blindside.core.sim.simulate
import io.github.santiquiroz.blindside.core.sim.walker

private const val DEMO_DURATION_MS = 60_000L

fun demoPackets(): List<SimPacket> = simulate(demoScenario())

fun demoScenario(): Scenario = Scenario(
    name = "demo",
    player = listOf(Stand(DEMO_DURATION_MS)),
    targets = listOf(
        walker(fromMs = 3_000, toMs = 11_000, start = Point2(-4.0, 3.0), velocityMps = Point2(1.0, 0.0)),
        walker(fromMs = 20_000, toMs = 23_500, start = Point2(3.5, 4.0), velocityMps = Point2(-0.5, -0.5)),
        marcher(fromMs = 35_000, toMs = 50_000, center = Point2(-2.5, 1.5)),
        walker(fromMs = 52_000, toMs = 57_000, start = Point2(0.3, 5.5), velocityMps = Point2(0.0, -0.6)),
    ),
)
