package io.github.santiquiroz.blindside.shared.compass

import io.github.santiquiroz.blindside.shared.settings.ScreenMode
import io.github.santiquiroz.blindside.shared.theme.BlindsideColors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CompassColorsTest {
    @Test
    fun `stealth keeps north on the dim accent`() {
        assertEquals(BlindsideColors.AccentDim, compassColors(ScreenMode.SIGILO, CompassTrust.GOOD).north)
        assertEquals(BlindsideColors.Accent, compassColors(ScreenMode.VISTA, CompassTrust.GOOD).north)
    }

    @Test
    fun `an uncalibrated ring fades but the front index stays solid`() {
        val faded = compassColors(ScreenMode.VISTA, CompassTrust.CALIBRATE)
        assertEquals(CALIBRATE_ALPHA, faded.tick.alpha, 1e-3f)
        assertEquals(CALIBRATE_ALPHA, faded.north.alpha, 1e-3f)
        assertEquals(1f, faded.index.alpha, 1e-3f)
    }
}
