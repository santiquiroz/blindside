package io.github.santiquiroz.blindside.shared.tactical

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TacticalPointsTest {
    @Test
    fun `a long-press cycles which slot it marks`() {
        assertEquals(TacticalKind.SPAWN, nextTacticalKind(TacticalKind.BASE))
        assertEquals(TacticalKind.OBJECTIVE, nextTacticalKind(TacticalKind.SPAWN))
        assertEquals(TacticalKind.BASE, nextTacticalKind(TacticalKind.OBJECTIVE))
    }

    @Test
    fun `marking a slot replaces only that slot`() {
        val base = withTacticalPoint(emptyMap(), TacticalKind.BASE, GeoPoint(5.0, -75.0))
        val both = withTacticalPoint(base, TacticalKind.OBJECTIVE, GeoPoint(5.1, -75.1))
        assertEquals(setOf(TacticalKind.BASE, TacticalKind.OBJECTIVE), both.keys)
        val moved = withTacticalPoint(both, TacticalKind.BASE, GeoPoint(5.2, -75.2))
        assertEquals(GeoPoint(5.2, -75.2), moved.getValue(TacticalKind.BASE))
        assertEquals(GeoPoint(5.1, -75.1), moved.getValue(TacticalKind.OBJECTIVE))
    }
}
