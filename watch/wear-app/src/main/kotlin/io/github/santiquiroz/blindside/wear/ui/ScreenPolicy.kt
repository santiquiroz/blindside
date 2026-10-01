package io.github.santiquiroz.blindside.wear.ui

import io.github.santiquiroz.blindside.wear.settings.ScreenMode
import io.github.santiquiroz.blindside.wear.ui.radar.PointPx

const val BURN_IN_STEP_MS = 3 * 60 * 1_000L
private const val BURN_IN_SHIFT_PX = 2f
private val BURN_IN_STEPS = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1)

fun keepScreenOn(mode: ScreenMode, eliminated: Boolean): Boolean = mode == ScreenMode.VISTA && !eliminated

fun burnInOffset(mode: ScreenMode, elapsedMs: Long): PointPx {
    if (mode != ScreenMode.VISTA) return PointPx(0f, 0f)
    val (dx, dy) = BURN_IN_STEPS[((elapsedMs / BURN_IN_STEP_MS) % BURN_IN_STEPS.size).toInt()]
    return PointPx(dx * BURN_IN_SHIFT_PX, dy * BURN_IN_SHIFT_PX)
}
