package io.github.santiquiroz.blindside.phone.tak

import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TakTlsTest {
    private fun resource(name: String): ByteArray =
        javaClass.getResourceAsStream("/tak/$name")!!.use { it.readBytes() }

    private fun serverContext(): SSLContext {
        val keys = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/tak/server.p12")!!.use { keys.load(it, PASS.toCharArray()) }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagers.init(keys, PASS.toCharArray())
        val trust = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/tak/truststore-root.p12")!!.use {
            trust.load(it, PASS.toCharArray())
        }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagers.init(trust)
        val context = SSLContext.getInstance("TLS")
        context.init(keyManagers.keyManagers, trustManagers.trustManagers, null)
        return context
    }

    @Test
    fun `mutual handshake against local server exchanges bytes`() {
        val serverSocket = serverContext().serverSocketFactory.createServerSocket(0) as SSLServerSocket
        serverSocket.needClientAuth = true
        val pool = Executors.newSingleThreadExecutor()
        try {
            // The server side drives its lazy handshake from the read below.
            val received = pool.submit<Int> {
                (serverSocket.accept() as SSLSocket).use {
                    it.soTimeout = 10_000
                    val got = it.inputStream.read()
                    it.outputStream.write(8)
                    it.outputStream.flush()
                    got
                }
            }
            val pkg = TakPackage(
                "127.0.0.1", serverSocket.localPort, resource("truststore-root.p12"), PASS,
                resource("player.p12"), PASS, "player",
            )
            tlsConnector(pkg, sslContextOf(pkg)).open().use { client ->
                client.soTimeout = 10_000
                client.outputStream.write(7)
                client.outputStream.flush()
                assertEquals(8, client.inputStream.read())
            }
            assertEquals(7, received.get(15, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
            serverSocket.close()
        }
    }

    @Test
    fun `wrong client password is a setup error`() {
        val pkg = TakPackage(
            "127.0.0.1", 1, resource("truststore-root.p12"), PASS,
            resource("player.p12"), "wrong", "player",
        )
        val failure = assertThrows(TakSetupException::class.java) { sslContextOf(pkg) }
        assertEquals("El certificado no se pudo abrir (formato o contraseña)", failure.message)
    }

    @Test
    fun `truststore without trusted certs is a setup error`() {
        val pkg = TakPackage(
            "127.0.0.1", 1, resource("wrong-truststore.p12"), PASS,
            resource("player.p12"), PASS, "player",
        )
        val failure = assertThrows(TakSetupException::class.java) { sslContextOf(pkg) }
        assertEquals("El truststore del paquete no trae certificados", failure.message)
    }

    companion object {
        private const val PASS = "atakatak"
    }
}
