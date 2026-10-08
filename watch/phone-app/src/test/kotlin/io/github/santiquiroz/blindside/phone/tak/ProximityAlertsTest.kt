package io.github.santiquiroz.blindside.phone.tak

import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import io.github.santiquiroz.blindside.shared.tactical.destinationOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProximityAlertsTest {
    companion object {
        const val NOW = 1_760_000_000_000L
        val HERE = GeoPoint(5.0689, -75.5174)
    }

    private fun contact(uid: String = "R1", label: String = "Radar Santi 1", distanceM: Double = 20.0, bearing: Double = 45.0) =
        TeamContact(uid, label, destinationOf(HERE, bearing, distanceM), 0)

    @Test
    fun `contact at 20 m raises an alert`() {
        val round = dueAlerts(AlertBook(), listOf(contact()), HERE, NOW)
        assertEquals(1, round.alerts.size)
        val alert = round.alerts.single()
        assertEquals("R1", alert.uid)
        assertEquals("Radar Santi 1", alert.label)
        assertEquals(20.0, alert.distanceM, 0.5)
        assertEquals(45.0, alert.bearingDeg, 0.5)
        assertEquals(NOW, round.book.lastAlertMs["R1"])
    }

    @Test
    fun `contact at 40 m raises no alert`() {
        val round = dueAlerts(AlertBook(), listOf(contact(distanceM = 40.0)), HERE, NOW)
        assertTrue(round.alerts.isEmpty())
        assertTrue(round.book.lastAlertMs.isEmpty())
    }

    @Test
    fun `repeated alert suppressed for 30 seconds`() {
        val first = dueAlerts(AlertBook(), listOf(contact()), HERE, NOW)
        assertEquals(1, first.alerts.size)
        val soon = dueAlerts(first.book, listOf(contact()), HERE, NOW + 10_000)
        assertTrue(soon.alerts.isEmpty())
        val later = dueAlerts(first.book, listOf(contact()), HERE, NOW + 31_000)
        assertEquals(1, later.alerts.size)
        assertEquals(NOW + 31_000, later.book.lastAlertMs["R1"])
    }

    @Test
    fun `null position raises no alert and keeps the book`() {
        val book = AlertBook(mapOf("R1" to NOW - 1_000))
        val round = dueAlerts(book, listOf(contact()), null, NOW)
        assertTrue(round.alerts.isEmpty())
        assertEquals(book, round.book)
    }

    @Test
    fun `alerts are sorted by distance`() {
        val round = dueAlerts(AlertBook(), listOf(contact(uid = "FAR", distanceM = 25.0), contact(uid = "NEAR", distanceM = 10.0)), HERE, NOW)
        assertEquals(listOf("NEAR", "FAR"), round.alerts.map { it.uid })
    }

    @Test
    fun `book prunes uids silent for over 5 minutes`() {
        val book = AlertBook(mapOf("OLD" to NOW - 301_000, "RECENT" to NOW - 299_000))
        val round = dueAlerts(book, emptyList(), HERE, NOW)
        assertEquals(mapOf("RECENT" to NOW - 299_000), round.book.lastAlertMs)
    }

    @Test
    fun `alertText matches the exact template`() {
        assertEquals(
            "Contacto a 22 m al NE · Radar Santi 1",
            alertText(ProximityAlert("R1", "Radar Santi 1", 22.0, 45.0)),
        )
    }

    @Test
    fun `alertText rounds the distance`() {
        assertEquals(
            "Contacto a 22 m al N · B",
            alertText(ProximityAlert("B", "B", 21.6, 0.0)),
        )
    }
}
