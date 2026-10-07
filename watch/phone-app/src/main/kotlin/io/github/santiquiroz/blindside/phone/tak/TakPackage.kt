package io.github.santiquiroz.blindside.phone.tak

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

class TakPackage(
    val host: String,
    val port: Int,
    val truststore: ByteArray,
    val truststorePassword: String,
    val client: ByteArray,
    val clientPassword: String,
    val clientName: String,
)

sealed interface PackageResult {
    data class Ok(val pkg: TakPackage) : PackageResult
    data class Invalid(val reason: String) : PackageResult
}

private const val MAX_ENTRY_BYTES = 1_048_576
private const val MAX_ZIP_DEPTH = 2
private const val DEFAULT_P12_PASSWORD = "atakatak"

private data class ZipFile(val name: String, val bytes: ByteArray)

private sealed interface Unpacked {
    data class Files(val files: List<ZipFile>) : Unpacked
    data object TooBig : Unpacked
    data object Broken : Unpacked
}

private data class Server(val host: String, val port: Int, val protocol: String)

fun readTakPackage(zip: ByteArray): PackageResult {
    if (!isZipMagic(zip)) return PackageResult.Invalid("No es un zip válido")
    return when (val unpacked = collectFiles(zip, 0)) {
        Unpacked.Broken -> PackageResult.Invalid("No es un zip válido")
        Unpacked.TooBig -> PackageResult.Invalid("El paquete es demasiado grande")
        is Unpacked.Files -> buildPackage(unpacked.files)
    }
}

private fun buildPackage(files: List<ZipFile>): PackageResult {
    val pref = files.firstOrNull { it.isPref && it.text().contains("connectString") }
        ?: return PackageResult.Invalid("Falta la configuración del servidor (.pref)")
    val entries = prefEntries(pref.bytes)
    val connect = entries["connectString0"] ?: entries["connectString"]
        ?: return PackageResult.Invalid("Falta la configuración del servidor (.pref)")
    val server = parseConnectString(connect)
        ?: return PackageResult.Invalid("Falta la configuración del servidor (.pref)")
    if (server.protocol != "ssl") return PackageResult.Invalid("El paquete no usa TLS (puerto ssl)")
    val p12s = files.filter { it.isP12 }
    val truststore = p12s.firstOrNull { it.baseName().contains("truststore", ignoreCase = true) }
        ?: return PackageResult.Invalid("Falta el truststore del servidor")
    val client = p12s.firstOrNull { it !== truststore }
        ?: return PackageResult.Invalid("Falta el certificado del jugador")
    return PackageResult.Ok(
        TakPackage(
            host = server.host,
            port = server.port,
            truststore = truststore.bytes,
            truststorePassword = entries["caPassword"] ?: entries["caPassword0"] ?: DEFAULT_P12_PASSWORD,
            client = client.bytes,
            clientPassword = entries["clientPassword"] ?: entries["clientPassword0"] ?: DEFAULT_P12_PASSWORD,
            clientName = client.baseName().dropLast(".p12".length),
        ),
    )
}

private val ZipFile.isPref: Boolean get() = name.endsWith(".pref", ignoreCase = true)

private val ZipFile.isP12: Boolean get() = name.endsWith(".p12", ignoreCase = true)

private fun ZipFile.text(): String = bytes.toString(Charsets.UTF_8)

private fun ZipFile.baseName(): String = name.substringAfterLast('/').substringAfterLast('\\')

private fun isZipMagic(zip: ByteArray): Boolean =
    zip.size >= 4 && zip[0] == 'P'.code.toByte() && zip[1] == 'K'.code.toByte()

private fun collectFiles(bytes: ByteArray, depth: Int): Unpacked {
    val files = mutableListOf<ZipFile>()
    // A corrupt zip must read as Invalid, never crash the import.
    try {
        ZipInputStream(ByteArrayInputStream(bytes)).use { stream ->
            var entry = stream.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    if (entry.size > MAX_ENTRY_BYTES) return Unpacked.TooBig
                    val data = readCapped(stream) ?: return Unpacked.TooBig
                    when (val nested = expandNested(entry.name, data, depth)) {
                        null -> files.add(ZipFile(entry.name, data))
                        is Unpacked.Files -> files.addAll(nested.files)
                        Unpacked.TooBig -> return Unpacked.TooBig
                        Unpacked.Broken -> files.add(ZipFile(entry.name, data))
                    }
                }
                stream.closeEntry()
                entry = stream.nextEntry
            }
        }
    } catch (_: Exception) {
        return Unpacked.Broken
    }
    return Unpacked.Files(files)
}

// Null when the entry is not a nested zip; a corrupt inner zip stays an opaque file.
private fun expandNested(name: String, data: ByteArray, depth: Int): Unpacked? {
    if (!name.endsWith(".zip", ignoreCase = true) || depth >= MAX_ZIP_DEPTH) return null
    return collectFiles(data, depth + 1)
}

private fun readCapped(stream: ZipInputStream): ByteArray? {
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var total = 0
    while (true) {
        val read = stream.read(chunk)
        if (read < 0) return out.toByteArray()
        total += read
        if (total > MAX_ENTRY_BYTES) return null
        out.write(chunk, 0, read)
    }
}

private fun parseConnectString(value: String): Server? {
    val parts = value.trim().split(":")
    if (parts.size != 3) return null
    val host = parts[0].trim()
    val port = parts[1].trim().toIntOrNull()
    if (host.isEmpty() || port == null) return null
    return Server(host, port, parts[2].trim())
}

private fun prefEntries(bytes: ByteArray): Map<String, String> = runCatching {
    val text = bytes.toString(Charsets.UTF_8)
    // Android's parser may ignore the disallow-doctype feature, so entity tricks are refused before parsing.
    if (text.contains("<!DOCTYPE") || text.contains("<!ENTITY")) return emptyMap()
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = false
    factory.isExpandEntityReferences = false
    runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(text)))
    val nodes = doc.getElementsByTagName("entry")
    buildMap {
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            val key = element.getAttribute("key")
            if (key.isNotEmpty()) put(key, element.textContent.trim())
        }
    }
}.getOrElse { emptyMap() }
