package io.github.santiquiroz.blindside.phone.ui.theme

import io.github.santiquiroz.blindside.shared.theme.Tokens
import io.github.santiquiroz.blindside.shared.theme.contrastRatio
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhoneContrastTest {
    private val cards = listOf(Tokens.SURFACE, Tokens.SURFACE_2)

    @Test
    fun `main text stays at 7 to 1 on the phone's cards`() {
        cards.forEach { assertTrue(contrastRatio(Tokens.TEXT, it) >= 7.0, "text on ${it.toString(16)}") }
    }

    @Test
    fun `warnings and errors stay at 4,5 to 1 on the phone's cards`() {
        listOf(Tokens.WARN, Tokens.ALERT_RED).forEach { foreground ->
            cards.forEach { background ->
                assertTrue(contrastRatio(foreground, background) >= 4.5, "${foreground.toString(16)} on ${background.toString(16)}")
            }
        }
    }
}
