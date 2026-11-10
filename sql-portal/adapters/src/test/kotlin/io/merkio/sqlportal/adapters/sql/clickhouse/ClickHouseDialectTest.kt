package io.merkio.sqlportal.adapters.sql.clickhouse

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.merkio.sqlportal.sqlexecutor.Cell
import io.merkio.sqlportal.sqlexecutor.DialectException
import io.merkio.sqlportal.sqlexecutor.Table

internal class ClickHouseDialectTest :
    FunSpec({
        test("placeholders become typed server parameters") {
            val st =
                ClickHouseDialect.prepare(
                    "SELECT :n, :name, :n, :flag, :gone -- :ignored",
                    listOf(
                        "n" to Cell.Int(7),
                        "name" to Cell.Text("a'b"),
                        "flag" to Cell.Bool(true),
                        "gone" to Cell.Null,
                    ),
                )
            st.sql shouldBe
                "SELECT {n:Int64}, {name:String}, {n:Int64}, {flag:Bool}, {gone:Nullable(String)} -- :ignored"
            st.queryParams shouldHaveSize 4
            st.sql shouldNotContain "a'b"
            st.bind.shouldBeEmpty()
        }

        test("missing values are errors") {
            shouldThrow<DialectException> { ClickHouseDialect.prepare("SELECT :x", emptyList()) }
        }

        test("text parameters are escaped and special floats named") {
            paramText(Cell.Text("C:\\t\ty")) shouldBe "C:\\\\t\\ty"
            paramText(Cell.Text("\\N")) shouldBe "\\\\N"
            paramText(Cell.Null) shouldBe "\\N"
            paramText(Cell.Float(Double.NaN)) shouldBe "nan"
            paramText(Cell.Float(Double.NEGATIVE_INFINITY)) shouldBe "-inf"
            paramText(Cell.Float(2.5)) shouldBe "2.5"
        }

        test("statements split outside quotes and comments") {
            val sql =
                "CREATE TABLE t (s String) ENGINE = Memory;\n" +
                    "-- a ; in a comment\n" +
                    "INSERT INTO t VALUES ('a;b'), ('it\\'s; fine');\n" +
                    "/* block ; */ INSERT INTO t VALUES ('x');\n" +
                    "-- trailing comment only\n"
            val parts = splitStatements(sql)
            parts shouldHaveSize 3
            parts[0] shouldStartWith "CREATE TABLE"
            parts[1] shouldContain "'a;b'"
            parts[1] shouldContain "it\\'s; fine"
        }

        test("JSONCompact cells decode by type") {
            val body =
                """
                {
                    "meta": [
                        {"name": "id", "type": "UInt64"},
                        {"name": "price", "type": "Float64"},
                        {"name": "note", "type": "Nullable(String)"},
                        {"name": "ok", "type": "Bool"},
                        {"name": "day", "type": "Date"}
                    ],
                    "data": [
                        ["18446744073709551615", 1, null, true, "2024-01-02"],
                        ["2", 2.5, "x", false, "2024-01-03"]
                    ]
                }
                """.trimIndent()
            val t = parseResult(body)
            t.columns shouldBe listOf("id", "price", "note", "ok", "day")
            t.rows[0][0] shouldBe Cell.Text("18446744073709551615")
            t.rows[0][1] shouldBe Cell.Float(1.0)
            t.rows[0][2] shouldBe Cell.Null
            t.rows[0][3] shouldBe Cell.Bool(true)
            t.rows[1][0] shouldBe Cell.Int(2)
            t.rows[1][4] shouldBe Cell.Text("2024-01-03")
        }

        test("fixed strings drop padding and an empty body is empty") {
            val body = """{"meta":[{"name":"fs","type":"FixedString(4)"}],"data":[["ab\u0000\u0000"]]}"""
            parseResult(body).rows[0][0] shouldBe Cell.Text("ab")
            parseResult("") shouldBe Table()
            shouldThrow<DialectException> { parseResult("not json") }
        }

        test("endpoints apply defaults and decode credentials") {
            val e = Endpoint.parse("clickhouse://bob:p%40ss@ch.local/analytics")
            e.base shouldBe "http://ch.local:8123/"
            e.database shouldBe "analytics"
            e.user shouldBe "bob"
            e.password shouldBe "p@ss"
            e.isHttps shouldBe false

            val tls = Endpoint.parse("clickhouses://ch.local")
            tls.base shouldBe "https://ch.local:8443/"
            tls.database shouldBe "default"
            tls.user shouldBe "default"
            tls.isHttps shouldBe true

            shouldThrow<DialectException> { Endpoint.parse("postgres://x") }
            shouldThrow<IllegalArgumentException> { Endpoint.parse("clickhouse:///db") }
        }
    })
