package io.merkio.sqlportal.domain.scripts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.merkio.sqlportal.domain.common.DomainException
import io.merkio.sqlportal.domain.common.Value

class ScriptsTest :
    FunSpec({
        fun name(s: String) = ScriptName.of(s)

        fun script(sql: String = "SELECT 1") = ScriptDefinition(name("a"), "T", sql)

        test("script name rules") {
            ScriptName.of("sales_by-region2").value shouldBe "sales_by-region2"
            for (bad in listOf("", "Upper", "has space", "a/b", "x".repeat(65))) {
                shouldThrow<DomainException> { ScriptName.of(bad) }
            }
        }

        test("rejects empty sql, title and limits") {
            shouldThrow<DomainException> { ScriptDefinition.of(name("a"), "T", "  ") }
            shouldThrow<DomainException> { ScriptDefinition.of(name("a"), " ", "SELECT 1") }
            shouldThrow<DomainException> { script().copy(maxRows = 0).validate() }
            shouldThrow<DomainException> { script().copy(timeoutSecs = 0).validate() }
            shouldThrow<DomainException> { script().copy(pageSize = 501).validate() }
        }

        test("requires a table or a chart") {
            shouldThrow<DomainException> { script().copy(showTable = false).validate() }
        }

        test("rejects undeclared placeholders and duplicate params") {
            shouldThrow<DomainException> { ScriptDefinition.of(name("a"), "T", "SELECT :x") }.message shouldContain ":x"
            val p = ParamDefinition("p", ParamKind.TEXT)
            shouldThrow<DomainException> { script().copy(params = listOf(p, p)).validate() }.message shouldContain
                "duplicate"
        }

        test("binds params and collects all errors") {
            val s =
                script("SELECT :n, :t")
                    .copy(
                        params =
                            listOf(
                                ParamDefinition("n", ParamKind.INTEGER, required = true),
                                ParamDefinition("t", ParamKind.TEXT, required = true),
                            ),
                    ).validate()
            s.bindParams(mapOf("n" to "4", "t" to "x")) shouldBe listOf("n" to Value.Int(4), "t" to Value.Text("x"))
            shouldThrow<ScriptException.InvalidParams> { s.bindParams(mapOf("n" to "abc")) }.errors shouldHaveSize 2
        }

        test("runs without input depends on required params") {
            val required = ParamDefinition("n", ParamKind.INTEGER, required = true)
            script("SELECT :n").copy(params = listOf(required)).runsWithoutInput shouldBe false
            script("SELECT :n").copy(params = listOf(required.copy(default = "1"))).runsWithoutInput shouldBe true
        }

        test("catalog stores and finds scripts") {
            val c = ConfigScriptCatalog(listOf(ScriptDefinition.of(name("a"), "A", "SELECT 1")))
            c.all() shouldHaveSize 1
            c.get("a").shouldNotBeNull()
            c.get("b").shouldBeNull()
        }

        test("read-only check accepts SELECT and WITH") {
            isReadOnlySelect("SELECT 1") shouldBe true
            isReadOnlySelect("  -- c\n select * from t where a = :a;  ") shouldBe true
            isReadOnlySelect("WITH x AS (SELECT 1) SELECT * FROM x") shouldBe true
            isReadOnlySelect("SELECT 'drop table x; delete' AS txt") shouldBe true
            isReadOnlySelect("SELECT updated_at, created FROM t") shouldBe true
        }

        test("read-only check rejects writes and multiple statements") {
            for (sql in listOf(
                "DELETE FROM t",
                "SELECT 1; DROP TABLE t",
                "WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d",
                "PRAGMA writable_schema = 1",
                "",
                "-- only a comment",
            )) {
                isReadOnlySelect(sql) shouldBe false
            }
        }
    })
