package io.merkio.sqlportal.adapters.sql

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.merkio.sqlportal.common.tls.TlsOptions
import kotlin.time.Duration.Companion.seconds

class RemoteDatabasesTest :
    FunSpec({
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
