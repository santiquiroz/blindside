package io.github.santiquiroz.blindside.phone.tak

import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.xml.sax.InputSource

class CotXmlTest {
    companion object {
        const val NOW = 1_760_000_000_000L
        const val T = "2025-10-09T08:53:20Z"
        const val T5 = "2025-10-09T08:53:25Z"
        const val T10 = "2025-10-09T08:53:30Z"
        const val T20 = "2025-10-09T08:53:40Z"
        const val T30 = "2025-10-09T08:53:50Z"
        const val T600 = "2025-10-09T09:03:20Z"
        val AT = GeoPoint(5.0689, -75.5174)
        const val ZERO_POINT = "<point lat=\"0.0\" lon=\"0.0\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/>"
    }

    private fun parse(xml: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml)))

    @Test
    fun `cotTime formats epoch millis as instant`() {
        assertEquals(T, cotTime(NOW))
        assertEquals("2025-10-09T08:53:20.123Z", cotTime(NOW + 123))
    }

    @Test
    fun `xmlAttr escapes the five special chars`() {
        assertEquals("a&amp;b&lt;c&gt;d&quot;e&apos;f", xmlAttr("a&b<c>d\"e'f"))
        assertEquals("Santi 123", xmlAttr("Santi 123"))
    }

    @Test
    fun `identityEvent matches the template exactly`() {
        val expected = "<event version=\"2.0\" uid=\"BLINDSIDE-abc123\" type=\"a-f-G-E-S\" how=\"h-g-i-g-o\"" +
            " time=\"$T\" start=\"$T\" stale=\"$T5\">" + ZERO_POINT +
            "<detail><contact callsign=\"Santi\"/>" +
            "<takv device=\"Blindside\" platform=\"Blindside\" os=\"Android\" version=\"0.2.0\"/></detail></event>"
        val actual = identityEvent("BLINDSIDE-abc123", "Santi", "0.2.0", NOW)
        assertEquals(expected, actual)
        assertEquals("event", parse(actual).documentElement.tagName)
    }

    @Test
    fun `contactEvent matches the template exactly`() {
        val expected = "<event version=\"2.0\" uid=\"BLINDSIDE-abc123-C3\" type=\"a-u-G\" how=\"m-g\"" +
            " time=\"$T\" start=\"$T\" stale=\"$T10\">" +
            "<point lat=\"5.0689000\" lon=\"-75.5174000\" hae=\"9999999.0\" ce=\"4.2\" le=\"9999999.0\"/>" +
            "<detail><contact callsign=\"Radar Santi 3\"/>" +
            "<remarks>Blindside: contacto de radar, posición aproximada</remarks></detail></event>"
        val actual = contactEvent("BLINDSIDE-abc123-C3", "Radar Santi 3", AT, 4.2, NOW)
        assertEquals(expected, actual)
        assertEquals("event", parse(actual).documentElement.tagName)
    }

    @Test
    fun `deleteEvent matches the template exactly`() {
        val expected = "<event version=\"2.0\" uid=\"BLINDSIDE-abc123-C3-delete\" type=\"t-x-d-d\" how=\"h-g-i-g-o\"" +
            " time=\"$T\" start=\"$T\" stale=\"$T20\">" + ZERO_POINT +
            "<detail><link uid=\"BLINDSIDE-abc123-C3\" relation=\"none\" type=\"a-u-G\"/>" +
            "<__forcedelete/></detail></event>"
        val actual = deleteEvent("BLINDSIDE-abc123-C3", "a-u-G", NOW)
        assertEquals(expected, actual)
        assertEquals("event", parse(actual).documentElement.tagName)
    }

    @Test
    fun `markerEvent matches the template exactly`() {
        val expected = "<event version=\"2.0\" uid=\"BLINDSIDE-abc123-BASE\" type=\"b-m-p-s-m\" how=\"h-g-i-g-o\"" +
            " time=\"$T\" start=\"$T\" stale=\"$T600\">" +
            "<point lat=\"5.0689000\" lon=\"-75.5174000\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/>" +
            "<detail><contact callsign=\"Base Santi\"/><color argb=\"42\"/>" +
            "<remarks>Blindside: punto táctico</remarks></detail></event>"
        val actual = markerEvent("BLINDSIDE-abc123-BASE", "Base Santi", AT, 42, NOW)
        assertEquals(expected, actual)
        assertEquals("event", parse(actual).documentElement.tagName)
    }

    @Test
    fun `markerEvent writes negative argb as signed decimal`() {
        val actual = markerEvent("U", "C", AT, -1, NOW)
        assertEquals("-1", parse(actual).documentElement.getElementsByTagName("color").item(0).attributes.getNamedItem("argb").nodeValue)
    }

    @Test
    fun `pingEvent matches the template exactly`() {
        val expected = "<event version=\"2.0\" uid=\"BLINDSIDE-abc123-ping\" type=\"t-x-c-t\" how=\"h-g-i-g-o\"" +
            " time=\"$T\" start=\"$T\" stale=\"$T20\">" + ZERO_POINT + "<detail/></event>"
        val actual = pingEvent("BLINDSIDE-abc123-ping", NOW)
        assertEquals(expected, actual)
        assertEquals("event", parse(actual).documentElement.tagName)
    }

    @Test
    fun `selfEvent matches the template exactly`() {
        val expected = "<event version=\"2.0\" uid=\"BLINDSIDE-abc123-SA\" type=\"a-f-G-U-C\" how=\"m-g\"" +
            " time=\"$T\" start=\"$T\" stale=\"$T30\">" +
            "<point lat=\"5.0689000\" lon=\"-75.5174000\" hae=\"9999999.0\" ce=\"4.2\" le=\"9999999.0\"/>" +
            "<detail><contact callsign=\"Santi\"/>" +
            "<takv device=\"Blindside\" platform=\"Blindside\" os=\"Android\" version=\"1\"/></detail></event>"
        val actual = selfEvent("BLINDSIDE-abc123-SA", "Santi", AT, 4.2, NOW)
        assertEquals(expected, actual)
        assertEquals("event", parse(actual).documentElement.tagName)
    }

    @Test
    fun `selfEvent parses back with parseCotEvent`() {
        val parsed = requireNotNull(parseCotEvent(selfEvent("BLINDSIDE-abc123-SA", "Santi", AT, 4.2, NOW)))
        assertEquals("BLINDSIDE-abc123-SA", parsed.uid)
        assertEquals("a-f-G-U-C", parsed.type)
        assertEquals("Santi", parsed.callsign)
        assertEquals(5.0689, requireNotNull(parsed.point).latDeg, 1e-7)
        assertEquals(-75.5174, requireNotNull(parsed.point).lonDeg, 1e-7)
        assertEquals(NOW + 30_000, parsed.staleMs)
    }

    @Test
    fun `callsign with special chars is escaped and still parses`() {
        val callsign = "Tom & \"Jerry\" <1>"
        val actual = contactEvent("U", callsign, AT, 4.2, NOW)
        assertTrue(actual.contains("callsign=\"Tom &amp; &quot;Jerry&quot; &lt;1&gt;\""), actual)
        assertEquals(
            callsign,
            parse(actual).documentElement.getElementsByTagName("contact").item(0).attributes.getNamedItem("callsign").nodeValue,
        )
    }
}
