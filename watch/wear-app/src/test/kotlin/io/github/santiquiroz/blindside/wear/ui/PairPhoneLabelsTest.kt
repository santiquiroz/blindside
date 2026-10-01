package io.github.santiquiroz.blindside.wear.ui

import io.github.santiquiroz.blindside.shared.session.PhonePairing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PairPhoneLabelsTest {
    @Test
    fun `every pairing state has its own short label and none claims the window is open`() {
        val labels = PhonePairing.entries.map(::phonePairingLabel)
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(labels.all { it.isNotBlank() && it.length <= 26 })
        assertFalse(labels.any { it.contains("Abierta") })
    }
}
