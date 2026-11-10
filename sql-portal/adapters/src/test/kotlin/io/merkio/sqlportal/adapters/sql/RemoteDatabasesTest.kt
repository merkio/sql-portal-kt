package io.merkio.sqlportal.adapters.sql

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.merkio.sqlportal.adapters.sql.postgres.PostgresUrl
import io.merkio.sqlportal.common.tls.TlsOptions
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class RemoteDatabasesTest :
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
            url.properties shouldContain ("sslmode" to "verify-full")
            url.properties shouldContain ("sslfactory" to PortalSslFactory::class.java.name)
            url.properties shouldContain ("sqlportal.tls.ca_file" to "/etc/certs/ca one.pem")
        }

        test("insecure requires SSL without verification") {
            val url = PostgresUrl.parse("postgres://u:p@db/app", TlsOptions(insecure = true))
            url.properties shouldContain ("sslmode" to "require")
            url.properties shouldNotContainKey "sqlportal.tls.ca_file"
            url.jdbcUrl shouldNotContain "ssl"
        }

        test("a client identity is passed to the socket factory") {
            val url =
                PostgresUrl.parse(
                    "postgres://u@db/app?sslcert=old",
                    TlsOptions(Path.of("/tls/ca.crt"), false, Path.of("/tls/tls.crt"), Path.of("/tls/tls.key")),
                )
            url.properties shouldContain ("sqlportal.tls.cert_file" to "/tls/tls.crt")
            url.properties shouldContain ("sqlportal.tls.key_file" to "/tls/tls.key")
            url.jdbcUrl shouldNotContain "sslcert"
        }

        test("a CA file and insecure conflict") {
            shouldThrow<IllegalArgumentException> {
                PostgresUrl.parse("postgres://db/app", TlsOptions(caFile = Path.of("ca.pem"), insecure = true))
            }
        }

        test("clickhouse rejects other schemes and needs https for TLS") {
            shouldThrow<DatabaseSetupException> { connectDatabase("clickhouse:///", 1, 1.seconds, TlsOptions()) }
            shouldThrow<DatabaseSetupException> {
                connectDatabase("clickhouse://ch.local", 1, 1.seconds, TlsOptions(insecure = true))
            }.message shouldContain "clickhouses://"
            connectDatabase("clickhouses://ch.local", 1, 1.seconds, TlsOptions(insecure = true))
        }

        test("an unreachable clickhouse server is unavailable") {
            val ch = connectDatabase("clickhouse://127.0.0.1:1", 1, 1.seconds, TlsOptions())
            shouldThrow<io.merkio.sqlportal.domain.query.QueryException.Unavailable> { ch.ping() }
        }
    })
