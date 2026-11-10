package io.merkio.sqlportal.sqlexecutor.postgres

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.merkio.sqlportal.common.tls.TlsOptions
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class PostgresUrlTest :
    FunSpec({
        test("no TLS options leave the postgres URL alone") {
            val url = PostgresUrl.parse("postgres://u:p%40ss@db:5432/app?sslmode=prefer", TlsOptions())
            url.jdbcUrl shouldBe "jdbc:postgresql://db:5432/app?sslmode=prefer"
            url.user shouldBe "u"
            url.password shouldBe "p@ss"
            url.properties shouldBe emptyMap()
        }

        test("a CA file requests verified TLS and replaces existing SSL parameters") {
            val url =
                PostgresUrl.parse(
                    "postgres://u:p@db:5432/app?sslmode=disable&application_name=portal",
                    TlsOptions(caFile = Path.of("/etc/certs/ca one.pem")),
                )
            url.jdbcUrl shouldBe "jdbc:postgresql://db:5432/app?application_name=portal"
            url.properties shouldBe mapOf("sslmode" to "verify-full", "sslrootcert" to "/etc/certs/ca one.pem")
        }

        test("insecure requires SSL without verification") {
            val url = PostgresUrl.parse("postgres://u:p@db/app", TlsOptions(insecure = true))
            url.properties shouldBe mapOf("sslmode" to "require")
            url.jdbcUrl shouldNotContain "ssl"
        }

        test("a client identity becomes the driver's sslcert and sslkey") {
            val url =
                PostgresUrl.parse(
                    "postgres://u@db/app?sslcert=old",
                    TlsOptions(Path.of("/tls/ca.crt"), false, Path.of("/tls/tls.crt"), Path.of("/tls/tls.key")),
                )
            url.properties shouldContain ("sslcert" to "/tls/tls.crt")
            url.properties shouldContain ("sslkey" to "/tls/tls.key")
            url.properties shouldContain ("sslrootcert" to "/tls/ca.crt")
            url.jdbcUrl shouldNotContain "sslcert"
        }

        test("a CA file and insecure conflict") {
            shouldThrow<IllegalArgumentException> {
                PostgresUrl.parse("postgres://db/app", TlsOptions(caFile = Path.of("ca.pem"), insecure = true))
            }
        }

        test("an unreachable server fails at connect") {
            shouldThrow<IllegalStateException> {
                PostgresExecutor.connect("postgres://u:p@127.0.0.1:1/app", 1, 1.seconds, TlsOptions())
            }
        }
    })
