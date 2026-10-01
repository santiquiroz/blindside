package io.github.santiquiroz.blindside.core.sim

import io.github.santiquiroz.blindside.core.geometry.Point2

// Spec §9 scenario list. Every scenario starts standing still so the gyro boot bias (2 s) is accepted.
object Scenarios {
    const val WARMUP_MS = 2_500L

    fun crossing() = Scenario(
        name = "crossing",
        player = listOf(Stand(WARMUP_MS + 6_000)),
        targets = listOf(walker(WARMUP_MS, WARMUP_MS + 5_000, start = Point2(-3.0, 3.0), velocityMps = Point2(1.2, 0.0))),
    )

    fun turningWithStillTarget() = Scenario(
        name = "turning-with-still-target",
        player = listOf(Stand(WARMUP_MS + 1_000), Turn(1_500, rateDps = 60.0), Stand(1_500)),
        targets = listOf(stillObject(0, WARMUP_MS + 4_000, at = Point2(0.5, 3.0))),
    )

    fun turningWithMarcher() = Scenario(
        name = "turning-with-marcher",
        player = listOf(Stand(WARMUP_MS + 2_500), Turn(750, rateDps = 60.0), Stand(2_000)),
        targets = listOf(marcher(WARMUP_MS, WARMUP_MS + 5_250, center = Point2(0.0, 3.0))),
    )

    fun walkingTowardWall() = Scenario(
        name = "walking-toward-wall",
        player = listOf(Stand(WARMUP_MS), Walk(3_000, speedMps = 1.2), Stand(3_000)),
        targets = listOf(stillObject(0, WARMUP_MS + 6_000, at = Point2(0.3, 5.5))),
    )

    fun headOnRival() = Scenario(
        name = "head-on-rival",
        player = listOf(Stand(WARMUP_MS), Walk(1_500, speedMps = 1.2), Stand(4_000)),
        targets = listOf(walker(WARMUP_MS, WARMUP_MS + 5_500, start = Point2(0.2, 7.5), velocityMps = Point2(0.0, -0.5))),
    )

    fun twoPeopleSameRange() = Scenario(
        name = "two-people-same-range",
        player = listOf(Stand(WARMUP_MS + 4_000)),
        targets = listOf(
            walker(WARMUP_MS, WARMUP_MS + 4_000, start = Point2(-2.0, 3.5), velocityMps = Point2(0.0, -0.6)),
            walker(WARMUP_MS, WARMUP_MS + 4_000, start = Point2(2.0, 3.5), velocityMps = Point2(0.0, -0.6)),
        ),
    )

    fun personStops() = Scenario(
        name = "person-stops",
        player = listOf(Stand(WARMUP_MS + 8_000)),
        targets = listOf(
            SimTarget(
                listOf(
                    Waypoint(WARMUP_MS, -2.5, 3.0),
                    Waypoint(WARMUP_MS + 1_700, -0.5, 3.0),
                    Waypoint(WARMUP_MS + 2_200, -0.2, 3.0),
                    Waypoint(WARMUP_MS + 3_200, 0.0, 3.0),
                    Waypoint(WARMUP_MS + 5_200, 0.0, 3.0),
                    Waypoint(WARMUP_MS + 7_000, 1.8, 3.0),
                ),
            ),
        ),
    )

    fun targetExitsCone() = Scenario(
        name = "target-exits-cone",
        player = listOf(Stand(WARMUP_MS + 10_500)),
        targets = listOf(walker(WARMUP_MS, WARMUP_MS + 5_500, start = Point2(0.5, 1.0), velocityMps = Point2(1.2, 0.0))),
    )

    // Spec §9 MVP set: a 90° right turn in 0.5 s in front of a wall 3 m away that only shows up while turning.
    const val TURN_START_MS = WARMUP_MS + 2_000
    const val TURN_END_MS = TURN_START_MS + 500

    fun wallDuringTurn() = Scenario(
        name = "wall-during-turn",
        player = turnInFrontOfWall(),
        targets = wall(),
    )

    fun walkerConfirmedBeforeTurn() = Scenario(
        name = "walker-confirmed-before-turn",
        player = turnInFrontOfWall(),
        targets = wall() + walker(WARMUP_MS, TURN_END_MS + 3_000, start = Point2(1.0, 2.5), velocityMps = Point2(0.3, 0.0)),
    )

    fun walkerAppearsDuringTurn() = Scenario(
        name = "walker-appears-during-turn",
        player = turnInFrontOfWall(),
        targets = wall() + walker(TURN_START_MS + 250, TURN_END_MS + 3_000, start = Point2(2.5, 1.0), velocityMps = Point2(0.0, 0.6)),
    )

    fun rivalWhileWalking() = Scenario(
        name = "rival-while-walking",
        player = listOf(Stand(WARMUP_MS), Walk(2_000, speedMps = 1.0), Stand(4_000)),
        targets = listOf(
            stillObject(0, WARMUP_MS + 6_000, at = Point2(0.5, 5.0)),
            walker(WARMUP_MS + 500, WARMUP_MS + 6_000, start = Point2(-2.5, 4.5), velocityMps = Point2(0.6, 0.0)),
        ),
    )

    val all: List<Scenario>
        get() = listOf(
            crossing(), turningWithStillTarget(), turningWithMarcher(), walkingTowardWall(),
            headOnRival(), twoPeopleSameRange(), personStops(), targetExitsCone(),
            wallDuringTurn(), walkerConfirmedBeforeTurn(), walkerAppearsDuringTurn(), rivalWhileWalking(),
        )

    private fun turnInFrontOfWall() = listOf(Stand(TURN_START_MS), Turn(TURN_END_MS - TURN_START_MS, rateDps = 180.0), Stand(3_000))

    private fun wall() = listOf(-1.0, 0.0, 1.0).map { x -> stillObject(0, TURN_END_MS + 3_000, at = Point2(x, 3.0)) }
}
