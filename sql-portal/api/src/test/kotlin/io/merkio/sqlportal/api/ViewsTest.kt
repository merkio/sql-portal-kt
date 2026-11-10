package io.merkio.sqlportal.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.merkio.sqlportal.application.ScriptRun
import io.merkio.sqlportal.application.ScriptSummary
import io.merkio.sqlportal.domain.common.Value
import io.merkio.sqlportal.domain.query.QueryResult
import io.merkio.sqlportal.domain.scripts.ChartData
import io.merkio.sqlportal.domain.scripts.ChartDefinition
import io.merkio.sqlportal.domain.scripts.ChartKind
import io.merkio.sqlportal.domain.scripts.ChartOutcome
import io.merkio.sqlportal.domain.scripts.Dataset
import io.merkio.sqlportal.domain.scripts.DatasetData
import io.merkio.sqlportal.domain.scripts.FieldError
import io.merkio.sqlportal.domain.scripts.ParamDefinition
import io.merkio.sqlportal.domain.scripts.ParamKind
import io.merkio.sqlportal.domain.scripts.ScriptDefinition
import io.merkio.sqlportal.domain.scripts.ScriptName
import io.merkio.sqlportal.domain.scripts.SelectOption
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.milliseconds

class ViewsTest :
    FunSpec({
        val ui = UiSettings("Portal <&>", "https://cdn.example.org/chart.js")

        fun summary(
            name: String,
            group: String? = null,
            title: String = name,
        ) = ScriptSummary(name, title, "d", group, true, emptyList())

        test("JSON cannot break out of script blocks") {
            val s =
                jsonForScript(buildJsonObject { put("x", JsonPrimitive("</script><script>alert(1)</script>&\u2028")) })
            s shouldNotContain "</script>"
            s shouldNotContain "<"
            s shouldContain "\\u003c/script\\u003e"
            s shouldContain "\\u2028"
        }

        test("the page escapes the title and includes the chart script on demand") {
            val html = page(ui, "T<1>", null, "<p>x</p>", true)
            html shouldContain "T&lt;1&gt; · Portal &lt;&amp;&gt;"
            html shouldContain "<p>x</p>"
            html shouldContain "https://cdn.example.org/chart.js"
            page(ui, "T", null, "", false) shouldNotContain "cdn.example.org"
        }

        test("the navigation shows logout only with a CSRF token") {
            val with = page(ui, "T", Nav("Ann", "tok"), "", false)
            with shouldContain "action=\"/logout\""
            with shouldContain "value=\"tok\""
            page(ui, "T", Nav("Ann", null), "", false) shouldNotContain "/logout"
        }

        test("the index lists scripts or explains emptiness") {
            val html = indexBody(listOf(summary("a", title = "A <b>")), false, 0)
            html shouldContain "href=\"/scripts/a\""
            html shouldContain "A &lt;b&gt;"
            html shouldNotContain "group-title"
            indexBody(emptyList(), false, 0) shouldContain "no scripts you can run"
        }

        test("scripts are grouped alphabetically with ungrouped last") {
            val groups =
                groupScripts(
                    listOf(summary("a", "Sales"), summary("b"), summary("c", "charts"), summary("d", "Charts")),
                )
            groups.map { it.name } shouldBe listOf("charts", "Sales", "Other scripts")
            groups[0].scripts shouldHaveSize 2
            val html = indexBody(listOf(summary("a", "Sales"), summary("b")), false, 0)
            html shouldContain "<h2 class=\"group-title\">Sales"
            html shouldContain "Other scripts"
        }

        test("login page variants") {
            val both = loginBody(ldap = true, oidc = true, csrf = "tok", error = null)
            both shouldContain "name=\"username\""
            both shouldContain "/login/oidc"
            both shouldContain "tok"
            val ldapOnly = loginBody(ldap = true, oidc = false, csrf = "t", error = "bad <x>")
            ldapOnly shouldNotContain "/login/oidc"
            ldapOnly shouldContain "bad &lt;x&gt;"
            loginBody(ldap = false, oidc = false, csrf = "t", error = null) shouldContain "No sign-in method"
        }

        val withParams =
            ScriptDefinition(
                ScriptName.of("s"),
                "Title",
                "SELECT :t, :i, :n, :b, :d, :sel",
                params =
                    listOf(
                        ParamDefinition("t", ParamKind.TEXT, label = "Text", help = "Helpful"),
                        ParamDefinition("i", ParamKind.INTEGER, min = 1.0, max = 9.0),
                        ParamDefinition("n", ParamKind.NUMBER),
                        ParamDefinition("b", ParamKind.BOOLEAN, default = "true"),
                        ParamDefinition("d", ParamKind.DATE),
                        ParamDefinition(
                            "sel",
                            ParamKind.SELECT,
                            options = listOf(SelectOption("a", "A"), SelectOption("b", "B")),
                        ),
                    ),
            ).validate()

        test("every input kind is rendered") {
            val html = scriptBody(withParams, emptyMap(), emptyList(), null, null)
            html shouldContain "type=\"text\""
            html shouldContain "type=\"number\" step=\"1\""
            html shouldContain "min=\"1\""
            html shouldContain "max=\"9\""
            html shouldContain "step=\"any\""
            html shouldContain "type=\"checkbox\""
            html shouldContain " checked"
            html shouldContain "type=\"date\""
            html shouldContain "<select"
            html shouldContain "<option value=\"a\""
            html shouldContain "Helpful"
            html shouldContain "name=\"_run\""
        }

        test("values are preserved and field errors shown") {
            val html =
                scriptBody(
                    withParams,
                    mapOf("t" to "<script>", "sel" to "b"),
                    listOf(FieldError("i", "must be at most 9")),
                    null,
                    "Fix errors",
                )
            html shouldContain "value=\"&lt;script&gt;\""
            html shouldContain "<option value=\"b\" selected>"
            html shouldContain "must be at most 9"
            html shouldContain "field invalid"
            html shouldContain "Fix errors"
        }

        fun run(
            charts: List<ChartOutcome>,
            rows: List<List<Value>>,
            showTable: Boolean,
        ): ScriptRun {
            val script =
                ScriptDefinition(
                    ScriptName.of("s"),
                    "T",
                    "SELECT 1",
                    showTable = showTable,
                    charts = listOf(ChartDefinition("c", ChartKind.BAR, "k", listOf("v"))),
                )
            return ScriptRun(
                script,
                emptyList(),
                QueryResult(listOf("k", "v"), rows, true),
                null,
                charts,
                true,
                12.milliseconds,
            )
        }

        test("table, charts and meta are rendered") {
            val result = QueryResult(listOf("k", "v"), listOf(listOf(Value.Text("<x>"), Value.Int(3))))
            val chart = ChartDefinition("Bars </script>", ChartKind.BAR, "k", listOf("v")).build(result)
            val r = run(listOf(chart, ChartOutcome.Failed("column 'z' not found")), result.rows, true)
            val html = scriptBody(r.script, emptyMap(), emptyList(), r, null)
            html shouldContain "1 row in 12 ms"
            html shouldContain "truncated to the first 1000 rows"
            html shouldContain "<td>&lt;x&gt;</td>"
            html shouldContain "<td class=\"num\">3</td>"
            html shouldContain "data-chart=\"chart-data-0\""
            html shouldNotContain "</script>Bars"
            html shouldContain "Chart unavailable: column &#39;z&#39; not found"
        }

        test("an empty result and a hidden table") {
            val empty = run(emptyList(), emptyList(), true)
            scriptBody(empty.script, emptyMap(), emptyList(), empty, null) shouldContain "returned no rows"
            val hidden = run(emptyList(), listOf(listOf(Value.Null, Value.Null)), false)
            scriptBody(hidden.script, emptyMap(), emptyList(), hidden, null) shouldNotContain "<table"
        }

        test("chart configurations") {
            val bar =
                chartConfig(
                    ChartData(
                        "t",
                        ChartKind.BAR,
                        listOf("a"),
                        listOf(Dataset("v", DatasetData.Values(listOf(1.0, null)))),
                    ),
                )
            bar.jsonObject["type"]!!.jsonPrimitive.content shouldBe "bar"
            bar.toString() shouldContain "\"data\":[1.0,null]"
            val scatter =
                chartConfig(
                    ChartData(
                        "t",
                        ChartKind.SCATTER,
                        emptyList(),
                        listOf(
                            Dataset(
                                "v",
                                DatasetData.Points(
                                    listOf(
                                        1.0 to 2.0,
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            scatter
                .jsonObject["data"]!!
                .jsonObject["datasets"]!!
                .jsonArray[0]
                .jsonObject["data"]
                .toString() shouldBe """[{"x":1.0,"y":2.0}]"""
        }

        test("page numbers keep the ends and a window, with gaps") {
            pageNumbers(1, 1) shouldBe listOf(1)
            pageNumbers(7, 200) shouldBe listOf(1, null, 5, 6, 7, 8, 9, null, 200)
            pageNumbers(2, 4) shouldBe listOf(1, 2, 3, 4)
            sizeOptions(100, 100).map { it.value } shouldBe listOf(5, 10, 25, 50, 100)
            sizeOptions(10, 40).map { it.value } shouldBe listOf(5, 10, 25, 40, 50, 100)
        }
    })
