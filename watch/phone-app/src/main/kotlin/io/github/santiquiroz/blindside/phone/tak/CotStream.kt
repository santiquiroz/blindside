package io.github.santiquiroz.blindside.phone.tak

import io.github.santiquiroz.blindside.shared.tactical.GeoPoint
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

private const val EVENT_OPEN = "<event"
private const val EVENT_CLOSE = "</event>"
private const val MAX_BUFFER = 262_144

class CotSplitter {
    private val buffer = StringBuilder()

    fun feed(chunk: CharSequence): List<String> {
        buffer.append(chunk)
        val events = mutableListOf<String>()
        while (true) {
            val start = buffer.indexOf(EVENT_OPEN)
            if (start < 0) {
                dropWithoutStart()
                return events
            }
            if (start > 0) buffer.delete(0, start)
            val end = buffer.indexOf(EVENT_CLOSE)
            if (end < 0) {
                if (buffer.length > MAX_BUFFER) buffer.clear()
                return events
            }
            events.add(buffer.substring(0, end + EVENT_CLOSE.length))
            buffer.delete(0, end + EVENT_CLOSE.length)
        }
    }

    private fun dropWithoutStart() {
        if (buffer.length > MAX_BUFFER) {
            buffer.clear()
            return
        }
        keepOpenTagPrefix()
    }

    // A chunk may end mid "<event": keep the tail that could still become one.
    private fun keepOpenTagPrefix() {
        val tail = (1 until EVENT_OPEN.length).lastOrNull { length ->
            buffer.length >= length && EVENT_OPEN.startsWith(buffer.substring(buffer.length - length))
        } ?: 0
        if (tail == 0) buffer.clear() else buffer.delete(0, buffer.length - tail)
    }
}

data class CotEvent(
    val uid: String,
    val type: String,
    val callsign: String?,
    val point: GeoPoint?,
    val linkUid: String?,
)

fun parseCotEvent(xml: String): CotEvent? {
    if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) return null
    val root = parseRoot(xml) ?: return null
    val uid = attrOrNull(root, "uid") ?: return null
    val type = attrOrNull(root, "type") ?: return null
    return CotEvent(uid, type, contactCallsign(root), eventPoint(root), linkUid(root))
}

private fun parseRoot(xml: String): Element? = runCatching {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = false
    factory.isExpandEntityReferences = false
    runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
}.getOrNull()

private fun firstElement(root: Element, tag: String): Element? =
    root.getElementsByTagName(tag).item(0) as? Element

private fun attrOrNull(element: Element?, name: String): String? =
    element?.getAttribute(name)?.takeIf { it.isNotEmpty() }

private fun contactCallsign(root: Element): String? =
    attrOrNull(firstElement(root, "contact"), "callsign")

private fun linkUid(root: Element): String? =
    attrOrNull(firstElement(root, "link"), "uid")

private fun eventPoint(root: Element): GeoPoint? {
    val point = firstElement(root, "point") ?: return null
    val lat = point.getAttribute("lat").toDoubleOrNull() ?: return null
    val lon = point.getAttribute("lon").toDoubleOrNull() ?: return null
    return GeoPoint(lat, lon)
}
