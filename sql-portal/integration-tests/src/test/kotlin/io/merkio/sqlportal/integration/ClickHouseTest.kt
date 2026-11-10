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
import org.testcontainers.clickhouse.ClickHouseContainer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The ClickHouse adapter against a real server over its HTTP interface. */
class ClickHouseTest :
    FunSpec({
        tags(Docker)
        val server = ClickHouseContainer("clickhouse/clickhouse-server:24.8").labelled()
        lateinit var ch: QueryExecutor
        beforeSpec {
            server.start()
            val url = "clickhouse://${server.username}:${server.password}@${server.host}:${server.getMappedPort(
                8123,
            )}/default"
            ch = connectDatabase(url, 2, 5.seconds, TlsOptions())
        }
        afterSpec {
            (ch as? AutoCloseable)?.close()
            server.stop()
        }

        suspend fun run(
            sql: String,
            vararg params: Pair<String, Value>,
            maxRows: Int = 10,
            timeout: Duration = 5.seconds,
        ) = ch.execute(QueryRequest(sql, params.toList(), maxRows, timeout))

        test("typed parameters and nulls round trip") {
            ch.ping()
            val r =
                run(
                    "SELECT :n AS n, :text AS text, :flag AS flag, :gone AS gone, :f AS f",
                    "n" to Value.Int(7),
                    "text" to Value.Text("it's \"quoted\""),
                    "flag" to Value.Bool(true),
                    "gone" to Value.Null,
                    "f" to Value.Float(2.5),
                )
            r.columns shouldBe listOf("n", "text", "flag", "gone", "f")
            r.rows shouldBe
                listOf(
                    listOf(Value.Int(7), Value.Text("it's \"quoted\""), Value.Bool(true), Value.Null, Value.Float(2.5)),
                )
        }

        test("a setup script, then a query with truncation") {
            ch.runScript(
                "DROP TABLE IF EXISTS portal_it;\n" +
                    "CREATE TABLE portal_it (id Int64, label String) ENGINE = Memory;\n" +
                    "-- a ; inside a comment\n" +
                    "INSERT INTO portal_it SELECT number, concat('row;', toString(number)) FROM numbers(50);",
            )
            val r = run("SELECT id, label FROM portal_it ORDER BY id DESC", maxRows = 5)
            r.rows shouldHaveSize 5
            r.truncated shouldBe true
            r.rows[0][1] shouldBe Value.Text("row;49")
            val all = run("SELECT id FROM portal_it", maxRows = 100)
            all.rows shouldHaveSize 50
            all.truncated shouldBe false
            ch.runScript("DROP TABLE portal_it")
        }

        test("scripts are read-only at the server") {
            shouldThrow<QueryException.Database> {
                run(
                    "CREATE TABLE portal_should_not_exist (a Int32) ENGINE = Memory",
                )
            }
        }

        test("slow queries time out") {
            shouldThrow<QueryException.Timeout> { run("SELECT sleepEachRow(1) FROM numbers(3)", timeout = 1.seconds) }
        }

        test("a missing table is a database error") {
            shouldThrow<QueryException.Database> { run("SELECT * FROM portal_no_such_table") }.message shouldContain
                "portal_no_such_table"
        }

        test("text parameters keep backslashes, tabs and null markers") {
            for (text in listOf("C:\\temp\\new\tline\nend", "\\N", "it's \"quoted\"", "", "naïve ✓")) {
                run("SELECT :t AS v", "t" to Value.Text(text)).rows shouldBe listOf(listOf(Value.Text(text)))
            }
        }

        test("named parameters work in common SQL positions") {
            suspend fun check(
                sql: String,
                param: Pair<String, Value>,
                expected: Value,
            ) = run(sql, param).rows shouldBe listOf(listOf(expected))

            check("SELECT :n + 1", "n" to Value.Int(41), Value.Int(42))
            check("SELECT :f * 2", "f" to Value.Float(1.25), Value.Float(2.5))
            check("SELECT :b", "b" to Value.Bool(false), Value.Bool(false))
            check("SELECT isNaN(:f)", "f" to Value.Float(Double.NaN), Value.Int(1))
            check("SELECT :x", "x" to Value.Null, Value.Null)
            check("SELECT CAST(:p AS Int32)", "p" to Value.Int(7), Value.Int(7))
            check("SELECT :p::String", "p" to Value.Int(7), Value.Text("7"))
            check("SELECT :p + :p -- :ignored", "p" to Value.Int(4), Value.Int(8))
            check("SELECT concat(':p', toString(:p))", "p" to Value.Int(1), Value.Text(":p1"))
        }

        test("named parameters filter, limit and compare dates") {
            ch.runScript(
                "DROP TABLE IF EXISTS portal_params;\n" +
                    "CREATE TABLE portal_params (d Date, n Int64) ENGINE = Memory;\n" +
                    "INSERT INTO portal_params VALUES ('2024-01-01', 1), ('2024-02-15', 2), ('2024-03-01', 3), ('2024-04-01', 4);",
            )
            run(
                "SELECT n FROM portal_params WHERE d >= :from ORDER BY n",
                "from" to Value.Text("2024-02-01"),
            ).rows shouldHaveSize
                3
            run("SELECT n FROM portal_params ORDER BY n LIMIT :limit", "limit" to Value.Int(2)).rows shouldBe
                listOf(listOf(Value.Int(1)), listOf(Value.Int(2)))
            ch.runScript("DROP TABLE portal_params")
        }

        test("common column types decode to portal values") {
            val r =
                run(
                    "SELECT toDate('2024-01-02') AS d, toDateTime('2024-01-02 03:04:05') AS dt, " +
                        "toDateTime64('2024-01-02 03:04:05.123', 3) AS dt64, toDecimal64(12.5, 2) AS dec, " +
                        "toUUID('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11') AS u, [1, 2, 3] AS arr, " +
                        "CAST(NULL AS Nullable(String)) AS nn, CAST('a' AS Enum8('a' = 1)) AS e, " +
                        "toFloat32(1.5) AS f32, map('k', 1) AS m, tuple(1, 'x') AS t, toIPv4('10.0.0.1') AS ip, " +
                        "toLowCardinality('lc') AS lc, toInt128(5) AS i128, toUInt64(18446744073709551615) AS big, " +
                        "toInt64(-7) AS i64, 2.0 AS f64, toNullable(3) AS ni, toFixedString('ab', 4) AS fs",
                    maxRows = 5,
                )
            r.rows[0] shouldBe
                listOf(
                    Value.Text("2024-01-02"),
                    Value.Text("2024-01-02 03:04:05"),
                    Value.Text("2024-01-02 03:04:05.123"),
                    Value.Float(12.5),
                    Value.Text("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"),
                    Value.Text("[1,2,3]"),
                    Value.Null,
                    Value.Text("a"),
                    Value.Float(1.5),
                    Value.Text("{\"k\":1}"),
                    Value.Text("[1,\"x\"]"),
                    Value.Text("10.0.0.1"),
                    Value.Text("lc"),
                    Value.Int(5),
                    Value.Text("18446744073709551615"),
                    Value.Int(-7),
                    Value.Float(2.0),
                    Value.Int(3),
                    Value.Text("ab"),
                )
        }
    })
