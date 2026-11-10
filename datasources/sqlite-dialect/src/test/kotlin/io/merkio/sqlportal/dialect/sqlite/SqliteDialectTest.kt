package io.merkio.sqlportal.dialect.sqlite

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.merkio.sqlportal.sqlexecutor.Cell
import io.merkio.sqlportal.sqlexecutor.DialectException

class SqliteDialectTest :
    FunSpec({
        test("placeholders become positional binds in order") {
            val st =
                SqliteDialect.prepare(
                    "SELECT :a, :b, :a -- :c",
                    listOf("a" to Cell.Int(1), "b" to Cell.Text("x")),
                )
            st.sql shouldBe "SELECT ?, ?, ? -- :c"
            st.bind shouldBe listOf(Cell.Int(1), Cell.Text("x"), Cell.Int(1))
        }

        test("missing values are reported") {
            shouldThrow<DialectException> { SqliteDialect.prepare("SELECT :z", emptyList()) }
        }

        test("literals and casts are untouched") {
            val st = SqliteDialect.prepare("SELECT ':a', x::text FROM t", emptyList())
            st.sql shouldBe "SELECT ':a', x::text FROM t"
            st.bind.shouldBeEmpty()
        }
    })
