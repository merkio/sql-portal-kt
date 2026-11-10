package io.merkio.sqlportal.common.tls

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.jcajce.JcaMiscPEMGenerator
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.io.pem.PemObject
import java.math.BigInteger
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.Date
import kotlin.io.path.writer

class TlsOptionsTest :
    FunSpec({
        val dir = tempdir().toPath()

        /** A self-signed certificate and its key as PEM files; the key in the given PEM form. */
        fun identity(pkcs1: Boolean): Pair<Path, Path> {
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=localhost")
            val now = System.currentTimeMillis()
            val holder =
                JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(now), Date(now + 86_400_000), name, keys.public)
                    .build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private))
            val cert: X509Certificate = JcaX509CertificateConverter().getCertificate(holder)
            val suffix = if (pkcs1) "pkcs1" else "pkcs8"
            val certFile = dir.resolve("tls-$suffix.crt")
            val keyFile = dir.resolve("tls-$suffix.key")
            JcaPEMWriter(certFile.writer()).use { it.writeObject(cert) }
            JcaPEMWriter(keyFile.writer()).use { writer ->
                if (pkcs1) {
                    writer.writeObject(JcaMiscPEMGenerator(keys.private))
                } else {
                    writer.writeObject(PemObject("PRIVATE KEY", keys.private.encoded))
                }
            }
            return certFile to keyFile
        }

        test("check rejects conflicting options") {
            shouldThrow<IllegalArgumentException> {
                TlsOptions(caFile = Path.of("ca.pem"), insecure = true).check()
            }.message shouldContain "cannot be combined"
            shouldThrow<IllegalArgumentException> {
                TlsOptions(caFile = Path.of("ca.pem"), certFile = Path.of("tls.crt")).check()
            }.message shouldContain "set together"
            shouldThrow<IllegalArgumentException> {
                TlsOptions(certFile = Path.of("tls.crt"), keyFile = Path.of("tls.key")).check()
            }.message shouldContain "needs ca_file"
        }

        test("check reports missing files") {
            shouldThrow<IllegalArgumentException> {
                TlsOptions(caFile = Path.of("/no/such/ca.pem")).check()
            }.message shouldContain "ca_file not found"
        }

        test("insecure options build without files") {
            val tls = TlsOptions(insecure = true)
            tls.check()
            tls.isSet shouldBe true
            tls.sslContext().shouldNotBeNull()
            TlsOptions().isSet shouldBe false
        }

        test("a CA file and a client identity in PKCS#8 or PKCS#1 form load") {
            for (pkcs1 in listOf(false, true)) {
                val (cert, key) = identity(pkcs1)
                val tls = TlsOptions(caFile = cert, certFile = cert, keyFile = key)
                tls.check()
                tls.keyManagers().shouldNotBeNull().size shouldBe 1
                tls.trustManager().acceptedIssuers.any { it.subjectX500Principal.name == "CN=localhost" } shouldBe true
                tls.sslContext().shouldNotBeNull()
            }
            TlsOptions().keyManagers().shouldBeNull()
        }
    })
