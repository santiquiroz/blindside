package io.github.santiquiroz.blindside.phone.tak

import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CotStreamTest {
    private fun saEvent(uid: String) =
        "<event version=\"2.0\" uid=\"$uid\" type=\"a-f-G-U-C\" how=\"m-g\"" +
            " time=\"2025-10-09T08:53:20Z\" start=\"2025-10-09T08:53:20Z\" stale=\"2025-10-09T08:53:30Z\">" +
            "<point lat=\"5.0691000\" lon=\"-75.5170000\" hae=\"1500.0\" ce=\"5.0\" le=\"9999999.0\"/>" +
            "<detail><contact callsign=\"Toro\" endpoint=\"192.0.2.1:4242:tcp\"/>" +
            "<__group name=\"Cyan\" role=\"Team Member\"/><status battery=\"87\"/>" +
            "<takv device=\"S25\" platform=\"ATAK\" os=\"34\" version=\"5.3.0\"/></detail></event>"

    @Test
    fun `two events in one chunk come out as two`() {
        val splitter = CotSplitter()
        val first = saEvent("AAA")
        val second = saEvent("BBB")
        assertEquals(listOf(first, second), splitter.feed(first + second))
    }

    @Test
    fun `event split across three chunks comes out whole once`() {
        val splitter = CotSplitter()
        val event = saEvent("AAA")
        val first = event.substring(0, 40)
        val second = event.substring(40, 120)
        val third = event.substring(120)
        assertEquals(emptyList<String>(), splitter.feed(first))
        assertEquals(emptyList<String>(), splitter.feed(second))
        assertEquals(listOf(event), splitter.feed(third))
        assertEquals(emptyList<String>(), splitter.feed(""))
    }

    @Test
    fun `garbage and prologue between events are ignored`() {
        val splitter = CotSplitter()
        val first = saEvent("AAA")
        val second = saEvent("BBB")
        val chunk = "garbage<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + first +
            "trailing-junk<?xml version=\"1.0\"?>" + second
        assertEquals(listOf(first, second), splitter.feed(chunk))
    }

    @Test
    fun `oversized buffer is dropped and the next event still works`() {
        val splitter = CotSplitter()
        assertEquals(emptyList<String>(), splitter.feed("x".repeat(300_000)))
        val event = saEvent("AAA")
        assertEquals(listOf(event), splitter.feed(event))
    }

    @Test
    fun `parseCotEvent reads a real ATAK SA event`() {
        val parsed = parseCotEvent(saEvent("S12345678-ABCD"))
        assertEquals("S12345678-ABCD", parsed?.uid)
        assertEquals("a-f-G-U-C", parsed?.type)
        assertEquals("Toro", parsed?.callsign)
        assertEquals(GeoPoint(5.0691, -75.517), parsed?.point)
        assertEquals(null, parsed?.linkUid)
    }

    @Test
    fun `parseCotEvent reads link uid from a delete event`() {
        val xml = "<event version=\"2.0\" uid=\"AAA-delete\" type=\"t-x-d-d\" how=\"h-g-i-g-o\"" +
            " time=\"2025-10-09T08:53:20Z\" start=\"2025-10-09T08:53:20Z\" stale=\"2025-10-09T08:53:40Z\">" +
            "<point lat=\"0.0\" lon=\"0.0\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/>" +
            "<detail><link uid=\"AAA\" relation=\"none\" type=\"a-u-G\"/><__forcedelete/></detail></event>"
        val parsed = parseCotEvent(xml)
        assertEquals("AAA-delete", parsed?.uid)
        assertEquals("t-x-d-d", parsed?.type)
        assertEquals("AAA", parsed?.linkUid)
    }

    @Test
    fun `parseCotEvent rejects doctype and entity declarations`() {
        val evil = "<?xml version=\"1.0\"?><!DOCTYPE event [<!ENTITY x \"y\">]>" + saEvent("AAA")
        assertNull(parseCotEvent(evil))
        assertNull(parseCotEvent(saEvent("AAA") + "<!ENTITY x \"y\">"))
    }

    @Test
    fun `parseCotEvent returns null without uid or type`() {
        assertNull(parseCotEvent("<event version=\"2.0\" type=\"a-f-G-U-C\"/>"))
        assertNull(parseCotEvent("<event version=\"2.0\" uid=\"AAA\"/>"))
        assertNull(parseCotEvent("not xml at all"))
    }

    @Test
    fun `parseCotEvent keeps the event but drops a non-numeric point`() {
        val xml = saEvent("AAA").replace("5.0691000", "abc")
        val parsed = parseCotEvent(xml)
        assertEquals("AAA", parsed?.uid)
        assertNull(parsed?.point)
    }
}
