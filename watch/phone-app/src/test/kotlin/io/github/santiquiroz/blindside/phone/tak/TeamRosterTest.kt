package io.github.santiquiroz.blindside.phone.tak

import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TeamRosterTest {
    companion object {
        const val NOW = 1_760_000_000_000L
        val AT = GeoPoint(5.0689, -75.5174)
    }

    private fun event(
        uid: String = "u-toro",
        type: String = "a-f-G-U-C",
        callsign: String? = "Toro",
        point: GeoPoint? = AT,
        linkUid: String? = null,
    ) = CotEvent(uid, type, callsign, point, linkUid)

    @Test
    fun `accepts a friendly SA event`() {
        val mates = TeamRoster().with(event(), "Santi", NOW).mates(NOW)
        assertEquals(1, mates.size)
        assertEquals("Toro", mates[0].callsign)
        assertEquals(AT, mates[0].point)
        assertEquals(0, mates[0].ageS)
    }

    @Test
    fun `rejects non-friendly type`() {
        val roster = TeamRoster().with(event(type = "a-h-G"), "Santi", NOW)
        assertTrue(roster.mates(NOW).isEmpty())
    }

    @Test
    fun `rejects own BLINDSIDE uid`() {
        val roster = TeamRoster().with(event(uid = "BLINDSIDE-x-C1"), "Santi", NOW)
        assertTrue(roster.mates(NOW).isEmpty())
    }

    @Test
    fun `rejects own callsign trimmed and case-insensitive`() {
        val roster = TeamRoster().with(event(callsign = "Santi "), "santi", NOW)
        assertTrue(roster.mates(NOW).isEmpty())
    }

    @Test
    fun `rejects null island point`() {
        val roster = TeamRoster().with(event(point = GeoPoint(0.0, 0.0)), "Santi", NOW)
        assertTrue(roster.mates(NOW).isEmpty())
    }

    @Test
    fun `rejects blank callsign and null point`() {
        val blank = TeamRoster().with(event(callsign = "  "), "Santi", NOW)
        assertTrue(blank.mates(NOW).isEmpty())
        val noPoint = TeamRoster().with(event(point = null), "Santi", NOW)
        assertTrue(noPoint.mates(NOW).isEmpty())
    }

    @Test
    fun `delete event removes the linked entry`() {
        val roster = TeamRoster()
            .with(event(uid = "u-toro", callsign = "Toro"), "Santi", NOW)
            .with(event(uid = "u-puma", callsign = "Puma"), "Santi", NOW)
            .with(event(uid = "x", type = "t-x-d-d", callsign = null, point = null, linkUid = "u-toro"), "Santi", NOW)
        val mates = roster.mates(NOW)
        assertEquals(1, mates.size)
        assertEquals("Puma", mates[0].callsign)
    }

    @Test
    fun `delete event without linkUid removes nothing`() {
        val roster = TeamRoster()
            .with(event(), "Santi", NOW)
            .with(event(uid = "x", type = "t-x-d-d", callsign = null, point = null), "Santi", NOW)
        assertEquals(1, roster.mates(NOW).size)
    }

    @Test
    fun `entry older than 60s disappears from mates`() {
        val roster = TeamRoster().with(event(), "Santi", NOW)
        assertEquals(1, roster.mates(NOW + 60_000).size)
        assertTrue(roster.mates(NOW + 61_000).isEmpty())
    }

    @Test
    fun `with prunes stale entries`() {
        val roster = TeamRoster()
            .with(event(uid = "u-old"), "Santi", NOW)
            .with(event(uid = "u-new"), "Santi", NOW + 61_000)
        assertTrue(roster.entries.keys.none { it == "u-old" })
        assertEquals(1, roster.mates(NOW + 61_000).size)
    }

    @Test
    fun `mates carry age in seconds sorted by callsign`() {
        val roster = TeamRoster()
            .with(event(uid = "u-z", callsign = "Zulu"), "Santi", NOW)
            .with(event(uid = "u-a", callsign = "Alfa"), "Santi", NOW + 2_500)
        val mates = roster.mates(NOW + 5_000)
        assertEquals(listOf("Alfa", "Zulu"), mates.map { it.callsign })
        assertEquals(2, mates[0].ageS)
        assertEquals(5, mates[1].ageS)
    }

    @Test
    fun `second event with same uid replaces the entry`() {
        val moved = GeoPoint(5.0691, -75.517)
        val roster = TeamRoster()
            .with(event(), "Santi", NOW)
            .with(event(point = moved), "Santi", NOW + 1_000)
        val mates = roster.mates(NOW + 1_000)
        assertEquals(1, mates.size)
        assertEquals(moved, mates[0].point)
        assertEquals(0, mates[0].ageS)
    }
}
