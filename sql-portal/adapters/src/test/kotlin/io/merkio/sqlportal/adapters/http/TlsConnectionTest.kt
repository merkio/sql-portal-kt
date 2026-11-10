package io.merkio.sqlportal.adapters.http

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.merkio.sqlportal.adapters.grants.RemoteGrants
import io.merkio.sqlportal.common.tls.TlsOptions
import io.merkio.sqlportal.domain.access.AuthException
import io.merkio.sqlportal.domain.access.AuthProvider
import io.merkio.sqlportal.domain.access.User
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.io.path.writer
import kotlin.time.Duration.Companion.seconds

/**
 * TLS options on a real HTTPS connection (the client used for ClickHouse,
 * OIDC and the grants service): a private CA with host name verification,
 * `insecure`, and mutual TLS.
 */
class TlsConnectionTest :
    FunSpec({
        val dir = tempdir().toPath()
        val day = 86_400_000L

        fun keys(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        fun certificate(
            subject: String,
            keys: KeyPair,
            issuer: String,
            issuerKeys: KeyPair,
            ca: Boolean,
            serial: Long,
        ): X509Certificate {
            val now = System.currentTimeMillis()
            val builder =
                JcaX509v3CertificateBuilder(
                    X500Name(issuer),
                    BigInteger.valueOf(serial),
                    Date(now - day),
                    Date(now + day),
                    X500Name(subject),
                    keys.public,
                ).addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
            if (!ca) {
                builder.addExtension(
                    Extension.subjectAlternativeName,
                    false,
                    GeneralNames(
                        arrayOf(
                            GeneralName(GeneralName.iPAddress, "127.0.0.1"),
                            GeneralName(GeneralName.dNSName, "localhost"),
                        ),
                    ),
                )
            }
            return JcaX509CertificateConverter().getCertificate(
                builder.build(JcaContentSignerBuilder("SHA256withRSA").build(issuerKeys.private)),
            )
        }

        fun pem(
            name: String,
            value: Any,
        ): Path = dir.resolve(name).also { path -> JcaPEMWriter(path.writer()).use { it.writeObject(value) } }

        val caKeys = keys()
        val ca = certificate("CN=Test CA", caKeys, "CN=Test CA", caKeys, ca = true, serial = 1)
        val serverKeys = keys()
        val server = certificate("CN=localhost", serverKeys, "CN=Test CA", caKeys, ca = false, serial = 2)
        val clientKeys = keys()
        val client = certificate("CN=portal", clientKeys, "CN=Test CA", caKeys, ca = false, serial = 3)
        val caFile = pem("ca.crt", ca)
        val clientCert = pem("tls.crt", client)
        val clientKey = pem("tls.key", clientKeys.private)

        /** An HTTPS grants service with the server certificate; [clientAuth] demands a client certificate from the CA. */
        fun https(clientAuth: Boolean): HttpsServer {
            val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
            store.setKeyEntry("server", serverKeys.private, CharArray(0), arrayOf(server, ca))
            val trust = KeyStore.getInstance("PKCS12").apply { load(null, null) }
            trust.setCertificateEntry("ca", ca)
            val context = SSLContext.getInstance("TLS")
            context.init(
                KeyManagerFactory
                    .getInstance(
                        KeyManagerFactory.getDefaultAlgorithm(),
                    ).apply { init(store, CharArray(0)) }
                    .keyManagers,
                TrustManagerFactory
                    .getInstance(
                        TrustManagerFactory.getDefaultAlgorithm(),
                    ).apply { init(trust) }
                    .trustManagers,
                null,
            )
            return HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                httpsConfigurator =
                    object : HttpsConfigurator(context) {
                        override fun configure(params: HttpsParameters) {
                            params.setSSLParameters(context.defaultSSLParameters.apply { needClientAuth = clientAuth })
                        }
                    }
                createContext("/grants") { exchange ->
                    val body = """{"roles":["tls"],"permissions":[]}""".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                start()
            }
        }

        val user = User("alice", "alice", provider = AuthProvider.OIDC)

        suspend fun grants(
            server: HttpsServer,
            tls: TlsOptions,
        ) = RemoteGrants("https://127.0.0.1:${server.address.port}/grants", null, 5.seconds, tls).grantsFor(user)

        test("a private CA verifies the server, including its host name") {
            val service = https(clientAuth = false)
            try {
                grants(service, TlsOptions(caFile = caFile)).roles shouldContain "tls"
                // Without the CA the certificate is not trusted.
                shouldThrow<AuthException.ProviderUnavailable> { grants(service, TlsOptions()) }
                // insecure skips verification entirely.
                grants(service, TlsOptions(insecure = true)).roles shouldContain "tls"
            } finally {
                service.stop(0)
            }
        }

        test("mutual TLS presents the client certificate") {
            val service = https(clientAuth = true)
            try {
                grants(
                    service,
                    TlsOptions(caFile = caFile, certFile = clientCert, keyFile = clientKey),
                ).roles shouldContain
                    "tls"
                shouldThrow<AuthException.ProviderUnavailable> { grants(service, TlsOptions(caFile = caFile)) }
            } finally {
                service.stop(0)
            }
        }
    })
