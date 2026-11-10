package io.merkio.sqlportal.sqlexecutor.postgres

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.merkio.sqlportal.sqlexecutor.Cell

internal class PostgresDialectTest :
    FunSpec({
        test("untyped parameters are positional, one per occurrence, and bound as text") {
            val st = PostgresDialect.prepare("SELECT :a, :b, :a", listOf("a" to Cell.Bool(true), "b" to Cell.Int(7)))
            st.sql shouldBe "SELECT ?, ?, ?"
            st.bind shouldBe listOf(Cell.Text("true"), Cell.Text("7"), Cell.Text("true"))
        }

        test("typed parameters are cast and keep native values") {
            val st =
                PostgresDialect.prepare(
                    "SELECT :d, :t",
                    listOf("d" to Cell.Text("2024-01-02"), "t" to Cell.Text("x")),
                    listOf("date", "TEXT"),
                )
            st.sql shouldBe "SELECT CAST(? AS date), ?"
            st.bind shouldBe listOf(Cell.Text("2024-01-02"), Cell.Text("x"))
        }

        test("trailing semicolons are dropped and literals untouched") {
            PostgresDialect.prepare("SELECT ':a', 1::int ;  ", emptyList()).sql shouldBe "SELECT ':a', 1::int"
        }

        test("floats are bound in their plain text form") {
            PostgresDialect.prepare("SELECT :f", listOf("f" to Cell.Float(2.0))).bind shouldBe listOf(Cell.Text("2"))
        }

        test("wrapper renames columns and converts kinds") {
            val w =
                wrapResult(
                    "SELECT 1",
                    listOf("numeric" to "NUMERIC", "numeric" to "numeric", "flag" to "bool", "when" to "timestamptz"),
                )!!
            w.sql shouldStartWith
                "SELECT \"c0\"::text AS \"numeric\", \"c1\"::text AS \"numeric\", \"c2\" AS \"flag\", \"c3\"::text AS \"when\" FROM ("
            w.sql shouldEndWith "AS portal_result(c0, c1, c2, c3)"
            w.kinds shouldBe listOf(Kind.NUMERIC, Kind.NUMERIC, Kind.BOOL, Kind.TEXT)
            wrapResult("SELECT", emptyList()).shouldBeNull()
        }

        test("numerics keep integers as integers") {
            numericCell("42") shouldBe Cell.Int(42)
            numericCell("12.50") shouldBe Cell.Float(12.5)
            numericCell("NaN").shouldBeInstanceOf<Cell.Float>().value.isNaN() shouldBe true
        }

        test("every occurrence is typed on its own") {
            val st =
                PostgresDialect.prepare(
                    "SELECT :a, :d, :a;",
                    listOf("a" to Cell.Int(7), "d" to Cell.Text("2024-01-02")),
                    listOf("int8", "date", "text"),
                )
            st.sql shouldBe "SELECT CAST(? AS int8), CAST(? AS date), ?"
            st.bind shouldBe listOf(Cell.Int(7), Cell.Text("2024-01-02"), Cell.Text("7"))
        }

        test("literal question marks are escaped in code only") {
            val st =
                PostgresDialect.prepare(
                    "SELECT doc ? 'k', '?' -- ?\nFROM t WHERE x = :x",
                    listOf(
                        "x" to Cell.Int(1),
                    ),
                )
            st.sql shouldBe "SELECT doc ?? 'k', '?' -- ?\nFROM t WHERE x = ?"
        }

        test("only known types are cast") {
            castType("UUID") shouldBe "uuid"
            castType("int4") shouldBe "int4"
            castType("TEXT").shouldBeNull()
            castType("_INT4").shouldBeNull()
        }
    })
