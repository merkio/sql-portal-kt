/*
 * TLS settings shared by every outbound connection (database, LDAP, OIDC,
 * grants service), turned into JSSE objects.
 *
 * The same files work for a Kubernetes `kubernetes.io/tls` secret mounted into
 * the pod: `tls.crt` and `tls.key` as the client identity, and the CA bundle
 * (for example cert-manager's `ca.crt`) as `caFile`.
 */
package io.merkio.sqlportal.common.tls

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager

/** Resolved TLS settings for one connection. */
data class TlsOptions(
    /** Certificate(s) to trust in addition to the system ones: a CA, or the server's own certificate. */
    val caFile: Path? = null,
    /** Skip certificate and host name verification entirely. Development only. */
    val insecure: Boolean = false,
    /** Client certificate for mutual TLS (PEM). */
    val certFile: Path? = null,
    /** Private key for [certFile] (PEM: PKCS#8, PKCS#1 or SEC1). */
    val keyFile: Path? = null,
) {
    val isSet: Boolean get() = caFile != null || insecure || certFile != null

    /**
     * Checks the combinations that cannot work, and that referenced files exist.
     *
     * @throws IllegalArgumentException with the first problem.
     */
    fun check() {
        require(!(insecure && caFile != null)) { "ca_file and insecure cannot be combined" }
        require((certFile == null) == (keyFile == null)) { "cert_file and key_file must be set together" }
        require(certFile == null || caFile != null || insecure) {
            "a client certificate needs ca_file (or insecure) to verify the server"
        }
        for ((name, path) in listOf("ca_file" to caFile, "cert_file" to certFile, "key_file" to keyFile)) {
            require(path == null || Files.isRegularFile(path)) { "$name not found: $path" }
        }
    }

    /** The trust decision for these options. */
    fun trustManager(): X509ExtendedTrustManager =
        when {
            insecure -> AcceptAnyCertificate
            caFile != null -> AnyOf(listOf(trustOnly(readCertificates(caFile)), systemTrust()))
            else -> systemTrust()
        }

    /** The client identity, or `null` without one. */
    fun keyManagers(): Array<KeyManager>? {
        val cert = certFile ?: return null
        val key = keyFile ?: return null
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        store.setKeyEntry("client", readPrivateKey(key), CharArray(0), readCertificates(cert).toTypedArray())
        return KeyManagerFactory
            .getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(store, CharArray(0)) }
            .keyManagers
    }

    /** An SSL context applying these options. */
    fun sslContext(): SSLContext =
        SSLContext.getInstance("TLS").apply { init(keyManagers(), arrayOf(trustManager()), null) }
}

private fun readCertificates(path: Path): List<X509Certificate> {
    val certificates =
        Files.newInputStream(path).use { input ->
            CertificateFactory.getInstance("X.509").generateCertificates(input).map { it as X509Certificate }
        }
    require(certificates.isNotEmpty()) { "no certificate in $path" }
    return certificates
}

private fun readPrivateKey(path: Path): PrivateKey =
    PEMParser(Files.newBufferedReader(path)).use { parser ->
        val converter = JcaPEMKeyConverter()
        when (val pem = parser.readObject()) {
            is PrivateKeyInfo -> converter.getPrivateKey(pem)
            is PEMKeyPair -> converter.getKeyPair(pem).private
            else -> throw IllegalArgumentException("no unencrypted private key in $path")
        }
    }

private fun trustOnly(certificates: List<X509Certificate>): X509ExtendedTrustManager {
    val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    certificates.forEachIndexed { i, cert -> store.setCertificateEntry("ca-$i", cert) }
    return trustManagerOf(store)
}

private fun systemTrust(): X509ExtendedTrustManager = trustManagerOf(null)

private fun trustManagerOf(store: KeyStore?): X509ExtendedTrustManager =
    TrustManagerFactory
        .getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(store) }
        .trustManagers
        .filterIsInstance<X509ExtendedTrustManager>()
        .first()

/** Trusts a server when any delegate does. Host names are checked by the delegates. */
private class AnyOf(
    private val delegates: List<X509ExtendedTrustManager>,
) : X509ExtendedTrustManager() {
    private fun firstAccepting(check: (X509ExtendedTrustManager) -> Unit) {
        var failure: CertificateException? = null
        for (delegate in delegates) {
            try {
                check(delegate)
                return
            } catch (e: CertificateException) {
                failure = failure ?: e
            }
        }
        throw failure ?: CertificateException("no trust manager accepted the certificate")
    }

    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        socket: Socket?,
    ) = firstAccepting { it.checkServerTrusted(chain, authType, socket) }

    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        engine: SSLEngine?,
    ) = firstAccepting { it.checkServerTrusted(chain, authType, engine) }

    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
    ) = firstAccepting { it.checkServerTrusted(chain, authType) }

    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        socket: Socket?,
    ) = firstAccepting { it.checkClientTrusted(chain, authType, socket) }

    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        engine: SSLEngine?,
    ) = firstAccepting { it.checkClientTrusted(chain, authType, engine) }

    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
    ) = firstAccepting { it.checkClientTrusted(chain, authType) }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        delegates.flatMap { it.acceptedIssuers.toList() }.toTypedArray()
}

/**
 * Accepts any server certificate and host name. Used only when `insecure` is
 * set: an extended trust manager is responsible for host name checks, so this
 * one skips them as well.
 */
private object AcceptAnyCertificate : X509ExtendedTrustManager() {
    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        socket: Socket?,
    ) = Unit

    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        engine: SSLEngine?,
    ) = Unit

    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
    ) = Unit

    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        socket: Socket?,
    ) = Unit

    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        engine: SSLEngine?,
    ) = Unit

    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
    ) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
