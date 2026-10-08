package io.github.santiquiroz.blindside.phone.tak

import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContactBoardTest {
    companion object {
        const val NOW = 1_760_000_000_000L
        const val PREFIX = "BLINDSIDE-santi-"
        val POINT = GeoPoint(5.0689, -75.5174)
    }

    private fun event(
        uid: String = "R1",
        type: String = "a-u-G",
        callsign: String? = "Radar Santi 1",
        point: GeoPoint? = POINT,
        linkUid: String? = null,
        staleMs: Long? = null,
    ) = CotEvent(uid, type, callsign, point, linkUid, staleMs)

    private fun labels(board: ContactBoard, nowMs: Long = NOW): List<String> =
        board.contacts(nowMs).map { it.label }

    @Test
    fun `accepts hostile and unknown tracks`() {
        val hostile = ContactBoard().with(event(uid = "H1", type = "a-h-G"), PREFIX, NOW)
        assertEquals(listOf("Radar Santi 1"), labels(hostile))
        val unknown = ContactBoard().with(event(uid = "U1", type = "a-u-G"), PREFIX, NOW)
        assertEquals(listOf("Radar Santi 1"), labels(unknown))
    }

    @Test
    fun `rejects friendly self type`() {
        val board = ContactBoard().with(event(type = "a-f-G-U-C"), PREFIX, NOW)
        assertTrue(board.contacts(NOW).isEmpty())
    }

    @Test
    fun `rejects own uid prefix`() {
        val board = ContactBoard().with(event(uid = "BLINDSIDE-santi-C1"), PREFIX, NOW)
        assertTrue(board.contacts(NOW).isEmpty())
    }

    @Test
    fun `rejects expired stale`() {
        val board = ContactBoard().with(event(staleMs = NOW - 1), PREFIX, NOW)
        assertTrue(board.contacts(NOW).isEmpty())
    }

    @Test
    fun `rejects zero point`() {
        val board = ContactBoard().with(event(point = GeoPoint(0.0, 0.0)), PREFIX, NOW)
        assertTrue(board.contacts(NOW).isEmpty())
    }

    @Test
    fun `blank or missing callsign falls back to Contacto`() {
        val blank = ContactBoard().with(event(callsign = "  "), PREFIX, NOW)
        assertEquals(listOf("Contacto"), labels(blank))
        val missing = ContactBoard().with(event(callsign = null), PREFIX, NOW)
        assertEquals(listOf("Contacto"), labels(missing))
    }

    @Test
    fun `delete event removes the linked entry`() {
        val board = ContactBoard().with(event(), PREFIX, NOW)
        assertEquals(1, board.contacts(NOW).size)
        val after = board.with(event(uid = "R1-delete", type = "t-x-d-d", callsign = null, point = null, linkUid = "R1"), PREFIX, NOW)
        assertTrue(after.contacts(NOW).isEmpty())
    }

    @Test
    fun `entry expires by stale`() {
        val board = ContactBoard().with(event(staleMs = NOW + 5_000), PREFIX, NOW)
        assertEquals(1, board.contacts(NOW + 5_000).size)
        assertTrue(board.contacts(NOW + 5_001).isEmpty())
    }

    @Test
    fun `entry without stale expires after 30 seconds`() {
        val board = ContactBoard().with(event(), PREFIX, NOW)
        assertEquals(1, board.contacts(NOW + 29_999).size)
        assertTrue(board.contacts(NOW + 30_001).isEmpty())
    }

    @Test
    fun `with prunes expired entries`() {
        val board = ContactBoard().with(event(), PREFIX, NOW)
        val pruned = board.with(event(uid = "OTHER", type = "b-m-p-s-m"), PREFIX, NOW + 31_000)
        assertTrue(pruned.entries.isEmpty())
    }

    @Test
    fun `same uid replaces the entry`() {
        val board = ContactBoard().with(event(), PREFIX, NOW)
            .with(event(point = GeoPoint(5.1, -75.5), callsign = "Nuevo"), PREFIX, NOW)
        val contacts = board.contacts(NOW)
        assertEquals(1, contacts.size)
        assertEquals("Nuevo", contacts.single().label)
        assertEquals(GeoPoint(5.1, -75.5), contacts.single().point)
    }

    @Test
    fun `contacts are sorted by uid with age in seconds`() {
        val board = ContactBoard()
            .with(event(uid = "B2"), PREFIX, NOW)
            .with(event(uid = "A1"), PREFIX, NOW)
        val contacts = board.contacts(NOW + 2_500)
        assertEquals(listOf("A1", "B2"), contacts.map { it.uid })
        assertEquals(listOf(2, 2), contacts.map { it.ageS })
    }
}
