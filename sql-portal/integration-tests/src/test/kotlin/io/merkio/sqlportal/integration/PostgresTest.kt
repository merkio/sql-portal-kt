package io.merkio.sqlportal.integration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.merkio.sqlportal.adapters.sql.connectDatabase
import io.merkio.sqlportal.common.tls.TlsOptions
import io.merkio.sqlportal.domain.common.Value
import io.merkio.sqlportal.domain.query.QueryException
import io.merkio.sqlportal.domain.query.QueryExecutor
import io.merkio.sqlportal.domain.query.QueryRequest
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.time.Duration.Companion.seconds

/** The PostgreSQL adapter against a real server. The queries are read-only. */
class PostgresTest :
    FunSpec({
        tags(Docker)
        val server = PostgreSQLContainer("postgres:16-alpine").labelled()
        lateinit var db: QueryExecutor
        beforeSpec {
            server.start()
            val address = "${server.host}:${server.firstMappedPort}"
            val url = "postgres://${server.username}:${server.password}@$address/${server.databaseName}"
            db = connectDatabase(url, 2, 5.seconds, TlsOptions())
        }
        afterSpec {
            (db as? AutoCloseable)?.close()
            server.stop()
        }

        suspend fun run(
            sql: String,
            vararg params: Pair<String, Value>,
        ) = db.execute(QueryRequest(sql, params.toList(), 100, 5.seconds))

        suspend fun row(
            sql: String,
            vararg params: Pair<String, Value>,
        ) = run(sql, *params).rows.single()

        test("date and time types arrive as text") {
            row(
                "SELECT DATE '2024-01-02', TIMESTAMP '2024-01-02 03:04:05', TIMESTAMPTZ '2024-01-02 03:04:05+00', TIME '03:04:05'",
            ) shouldBe
                listOf(
                    Value.Text("2024-01-02"),
                    Value.Text("2024-01-02 03:04:05"),
                    Value.Text("2024-01-02 03:04:05+00"),
                    Value.Text("03:04:05"),
                )
        }

        test("numeric types keep integers and floats") {
            row("SELECT NUMERIC '12.50', NUMERIC '42', 1.5::float4, 2.5::float8, 7::int2, 8::int4, 9::int8") shouldBe
                listOf(
                    Value.Float(12.5),
                    Value.Int(42),
                    Value.Float(1.5),
                    Value.Float(2.5),
                    Value.Int(7),
                    Value.Int(8),
                    Value.Int(9),
                )
        }

        test("uuid, json, arrays, bytea and intervals arrive as text") {
            row(
                "SELECT UUID 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', '{\"a\":1}'::json, '[1,2]'::jsonb, " +
                    "ARRAY[1,2,3], '\\x0102'::bytea, INTERVAL '1 day'",
            ) shouldBe
                listOf(
                    Value.Text("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"),
                    Value.Text("{\"a\":1}"),
                    Value.Text("[1, 2]"),
                    Value.Text("{1,2,3}"),
                    Value.Text("\\x0102"),
                    Value.Text("1 day"),
                )
        }

        test("booleans, text and nulls") {
            row("SELECT true, 'x'::varchar(5), NULL::int, NULL::numeric") shouldBe
                listOf(Value.Bool(true), Value.Text("x"), Value.Null, Value.Null)
        }

        test("parameters are cast to the type the server expects") {
            row("SELECT DATE '2024-03-01' >= :from", "from" to Value.Text("2024-02-01")) shouldBe
                listOf(Value.Bool(true))
            row("SELECT NUMERIC '1.5' * :n", "n" to Value.Int(2)) shouldBe listOf(Value.Float(3.0))
            row("SELECT :u::uuid", "u" to Value.Text("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")) shouldBe
                listOf(Value.Text("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"))
        }

        test("text parameters keep backslashes and LIMIT binds integers") {
            row("SELECT :t", "t" to Value.Text("C:\\x\ty")) shouldBe listOf(Value.Text("C:\\x\ty"))
            val limited = run("SELECT g FROM generate_series(1, 10) g ORDER BY g LIMIT :n", "n" to Value.Int(3))
            limited.rows shouldHaveSize 3
            limited.rows[0] shouldBe listOf(Value.Int(1))
        }

        test("trailing semicolons, errors and timeouts are handled") {
            row("SELECT 1 AS a; ") shouldBe listOf(Value.Int(1))
            shouldThrow<QueryException.Database> { run("SELECT * FROM portal_no_such_table") }.message shouldContain
                "portal_no_such_table"
            shouldThrow<QueryException.Timeout> {
                db.execute(QueryRequest("SELECT pg_sleep(3)", maxRows = 1, timeout = 1.seconds))
            }
            // The connection pool is usable after a cancelled statement.
            row("SELECT 2") shouldBe listOf(Value.Int(2))
        }

        test("paging wraps the script and counts its rows") {
            run(
                "SELECT * FROM (\nSELECT g FROM generate_series(1, 30) g\n) AS portal_page LIMIT 10 OFFSET 20",
            ).rows.first() shouldBe
                listOf(Value.Int(21))
            row("SELECT COUNT(*) AS n FROM (\nSELECT g FROM generate_series(1, 30) g\n) AS portal_count") shouldBe
                listOf(Value.Int(30))
        }
    })
