package io.merkio.sqlportal.sqlexecutor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class PlaceholdersTest :
    FunSpec({
        fun names(sql: String) = scanPlaceholders(sql).map { it.name }

        test("finds simple placeholders in order") {
            names("SELECT * FROM t WHERE a = :a AND b >= :b_2 OR a = :a") shouldBe listOf("a", "b_2", "a")
        }

        test("records index ranges") {
            val sql = "x = :abc;"
            val p = scanPlaceholders(sql).single()
            sql.substring(p.start, p.end) shouldBe ":abc"
        }

        test("ignores string literals and quoted identifiers") {
            names("SELECT ':nope', \":nope\", 'it''s :nope'").shouldBeEmpty()
        }

        test("ignores comments") {
            names("SELECT 1 -- :nope\n/* :nope */").shouldBeEmpty()
            names("SELECT 1 -- c\n WHERE a = :yes") shouldBe listOf("yes")
        }

        test("ignores postgres casts") {
            names("SELECT :a::int, x::text") shouldBe listOf("a")
        }

        test("ignores array slices and digits") {
            names("SELECT arr[1:2], '12:30'").shouldBeEmpty()
        }

        test("only ASCII identifiers start a placeholder") {
            names("SELECT 'é', :ü_not, :ok") shouldBe listOf("ok")
        }

        test("placeholder at end of input and next to punctuation") {
            names("WHERE a=:z") shouldBe listOf("z")
            names("IN (:a,:b)") shouldBe listOf("a", "b")
        }

        test("lookup reports missing values") {
            val params = listOf("a" to Cell.Int(1))
            lookup(params, "a") shouldBe Cell.Int(1)
            shouldThrow<DialectException> { lookup(params, "b") }.message shouldContain ":b"
        }

        test("code only blanks literals and comments") {
            codeOnly("SELECT 'drop' -- delete\n/* x */ FROM t") shouldBe "SELECT ''  \n  FROM t"
        }
    })
