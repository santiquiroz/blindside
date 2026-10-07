package io.github.santiquiroz.blindside.phone.tak

import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

class TakSetupException(message: String) : Exception(message)

fun sslContextOf(pkg: TakPackage): SSLContext {
    val keyManagers = loadKeyManagers(pkg)
    val trustManagers = loadTrustManagers(pkg)
    val context = SSLContext.getInstance("TLS")
    context.init(keyManagers, trustManagers, null)
    return context
}

fun interface TakConnector {
    fun open(): Socket
}

fun tlsConnector(pkg: TakPackage, context: SSLContext): TakConnector = TakConnector {
    val socket = context.socketFactory.createSocket()
    // Only certs signed by the package CA are trusted, so hostname checks add nothing.
    socket.connect(InetSocketAddress(pkg.host, pkg.port), 10_000)
    socket.soTimeout = 1_000
    (socket as SSLSocket).startHandshake()
    socket
}

private fun loadKeyManagers(pkg: TakPackage) = try {
    val store = pkcs12(pkg.client, pkg.clientPassword)
    val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    factory.init(store, pkg.clientPassword.toCharArray())
    factory.keyManagers
} catch (_: Exception) {
    throw TakSetupException("El certificado no se pudo abrir (formato o contraseña)")
}

private fun loadTrustManagers(pkg: TakPackage): Array<javax.net.ssl.TrustManager> {
    val store = try {
        pkcs12(pkg.truststore, pkg.truststorePassword)
    } catch (_: Exception) {
        throw TakSetupException("El certificado no se pudo abrir (formato o contraseña)")
    }
    val anchors = trustedCerts(store)
    if (anchors.isEmpty()) throw TakSetupException("El truststore del paquete no trae certificados")
    val empty = KeyStore.getInstance(KeyStore.getDefaultType())
    empty.load(null, null)
    anchors.forEachIndexed { index, cert -> empty.setCertificateEntry("ca$index", cert) }
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(empty)
    return factory.trustManagers
}

// Key entries carry a cert too, but only trusted-cert entries count as anchors.
private fun trustedCerts(store: KeyStore): List<Certificate> =
    store.aliases().toList().filter { store.isCertificateEntry(it) }.mapNotNull { store.getCertificate(it) }

private fun pkcs12(bytes: ByteArray, password: String): KeyStore {
    val store = KeyStore.getInstance("PKCS12")
    ByteArrayInputStream(bytes).use { store.load(it, password.toCharArray()) }
    return store
}
