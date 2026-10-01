package io.github.santiquiroz.blindside.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BuildSmokeTest {
    @Test
    fun `shared vectors are reachable from radar-core tests`() {
        val official = SharedVectors.hex("ld2450_official_frame")

        assertEquals(30, official.size)
    }
}
