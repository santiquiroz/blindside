package io.github.santiquiroz.blindside.phone.tak

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TakPackageTest {
    companion object {
        const val HOST = "203.0.113.5"
        const val PORT = 8089
        const val CA_PASS = "test-ca-pass"
        const val CLIENT_PASS = "test-client-pass"
        val FAKE_P12 = byteArrayOf(1, 2, 3)
        const val OUTER_DIR = "80b828699e074a239066d454a76284eb"
        const val INNER_DIR = "5c2bfcae3d98c9f4d262172df99ebac5"

        fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zos ->
                for ((name, bytes) in entries) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
            return out.toByteArray()
        }

        fun prefXml(connectString: String, caPass: String?, clientPass: String?): String {
            val ca = if (caPass == null) "" else "<entry key=\"caPassword\" class=\"class java.lang.String\">$caPass</entry>"
            val client = if (clientPass == null) "" else "<entry key=\"clientPassword\" class=\"class java.lang.String\">$clientPass</entry>"
            return "<preferences><preference name=\"cot_streams\">" +
                "<entry key=\"connectString0\" class=\"class java.lang.String\">$connectString</entry>" +
                "</preference><preference name=\"com.atakmap.app_preferences\">$ca$client</preference></preferences>"
        }

        fun itakZip(connectString: String = "$HOST:$PORT:ssl", caPass: String? = CA_PASS, clientPass: String? = CLIENT_PASS) =
            zipBytes(
                mapOf(
                    "config.pref" to prefXml(connectString, caPass, clientPass).toByteArray(),
                    "santi.p12" to FAKE_P12,
                    "truststore-root.p12" to FAKE_P12,
                ),
            )

        fun atakZip(connectString: String = "$HOST:$PORT:ssl") =
            zipBytes(
                mapOf(
                    "$OUTER_DIR/santi.zip" to zipBytes(
                        mapOf(
                            "$INNER_DIR/santi.p12" to FAKE_P12,
                            "$INNER_DIR/preference.pref" to prefXml(connectString, CA_PASS, CLIENT_PASS).toByteArray(),
                            "$INNER_DIR/truststore-root.p12" to FAKE_P12,
                            "MANIFEST/manifest.xml" to "<manifest></manifest>".toByteArray(),
                        ),
                    ),
                    "MANIFEST/manifest.xml" to "<manifest></manifest>".toByteArray(),
                ),
            )
    }

    private fun assertOkPackage(result: PackageResult) {
        assertTrue(result is PackageResult.Ok, "expected Ok but got $result")
        val pkg = (result as PackageResult.Ok).pkg
        assertEquals(HOST, pkg.host)
        assertEquals(PORT, pkg.port)
        assertEquals(CA_PASS, pkg.truststorePassword)
        assertEquals(CLIENT_PASS, pkg.clientPassword)
        assertEquals("santi", pkg.clientName)
        assertTrue(FAKE_P12.contentEquals(pkg.client))
        assertTrue(FAKE_P12.contentEquals(pkg.truststore))
    }

    @Test
    fun `flat iTAK zip imports`() {
        assertOkPackage(readTakPackage(itakZip()))
    }

    @Test
    fun `nested ATAK zip imports`() {
        assertOkPackage(readTakPackage(atakZip()))
    }

    @Test
    fun `non-ssl connectString is rejected`() {
        val result = readTakPackage(itakZip(connectString = "$HOST:$PORT:tcp"))
        assertTrue(result is PackageResult.Invalid, "expected Invalid but got $result")
        assertEquals("El paquete no usa TLS (puerto ssl)", (result as PackageResult.Invalid).reason)
    }

    @Test
    fun `zip without pref is rejected`() {
        val result = readTakPackage(zipBytes(mapOf("santi.p12" to FAKE_P12, "truststore-root.p12" to FAKE_P12)))
        assertTrue(result is PackageResult.Invalid, "expected Invalid but got $result")
        assertEquals("Falta la configuración del servidor (.pref)", (result as PackageResult.Invalid).reason)
    }

    @Test
    fun `non-zip bytes are rejected`() {
        val result = readTakPackage("not a zip".toByteArray())
        assertTrue(result is PackageResult.Invalid, "expected Invalid but got $result")
        assertEquals("No es un zip válido", (result as PackageResult.Invalid).reason)
    }

    @Test
    fun `missing client certificate is rejected`() {
        val result = readTakPackage(
            zipBytes(
                mapOf(
                    "config.pref" to prefXml("$HOST:$PORT:ssl", CA_PASS, CLIENT_PASS).toByteArray(),
                    "truststore-root.p12" to FAKE_P12,
                ),
            ),
        )
        assertTrue(result is PackageResult.Invalid, "expected Invalid but got $result")
        assertEquals("Falta el certificado del jugador", (result as PackageResult.Invalid).reason)
    }

    @Test
    fun `missing truststore is rejected`() {
        val result = readTakPackage(
            zipBytes(
                mapOf(
                    "config.pref" to prefXml("$HOST:$PORT:ssl", CA_PASS, CLIENT_PASS).toByteArray(),
                    "santi.p12" to FAKE_P12,
                ),
            ),
        )
        assertTrue(result is PackageResult.Invalid, "expected Invalid but got $result")
        assertEquals("Falta el truststore del servidor", (result as PackageResult.Invalid).reason)
    }

    @Test
    fun `oversized entry is rejected`() {
        val result = readTakPackage(zipBytes(mapOf("big.bin" to ByteArray(1_048_577))))
        assertTrue(result is PackageResult.Invalid, "expected Invalid but got $result")
        assertEquals("El paquete es demasiado grande", (result as PackageResult.Invalid).reason)
    }

    @Test
    fun `missing passwords default to atakatak`() {
        val result = readTakPackage(itakZip(caPass = null, clientPass = null))
        assertTrue(result is PackageResult.Ok, "expected Ok but got $result")
        val pkg = (result as PackageResult.Ok).pkg
        assertEquals("atakatak", pkg.truststorePassword)
        assertEquals("atakatak", pkg.clientPassword)
    }
}
