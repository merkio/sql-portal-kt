package io.merkio.sqlportal.integration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Every way the page presents selected data, checked against the showcase and
 * gallery scripts of the demo configuration (data from migrations 0003-0006).
 */
class PresentationTest :
    FunSpec({
        lateinit var h: Harness
        beforeSpec { h = Harness.start(Options(authEnabled = false)) }
        afterSpec { h.close() }

        suspend fun page(uri: String) = h.get(uri).body

        fun String.count(part: String) = windowed(part.length).count { it == part }

        test("a table only is the default presentation") {
            val r = h.get("/scripts/showcase_table?_run=1")
            r.status shouldBe HttpStatusCode.OK
            r.body shouldContain "<table"
            r.body shouldContain "24 rows in"
            r.body shouldNotContain "data-chart="
        }

        test("a bar chart draws two series with the table") {
            val body = page("/scripts/showcase_bar?_run=1")
            body shouldContain "\"type\":\"bar\""
            body shouldContain "\"label\":\"orders\""
            body shouldContain "\"label\":\"total\""
            body shouldContain "<table"
        }

        test("a line chart keeps gaps for missing values") {
            val body = page("/scripts/showcase_line?_run=1")
            body shouldContain "\"type\":\"line\""
            body shouldContain "null"
        }

        test("pie and doughnut draw their kind") {
            page("/scripts/showcase_pie?_run=1").let {
                it shouldContain "\"type\":\"pie\""
                it shouldContain "<table"
            }
            page("/scripts/showcase_doughnut?_run=1").let {
                it shouldContain "\"type\":\"doughnut\""
                it shouldNotContain "<table"
            }
        }

        test("a scatter plot draws points without a table") {
            val body = page("/scripts/showcase_scatter?_run=1")
            body shouldContain "\"type\":\"scatter\""
            body shouldContain "\"x\":"
            body shouldNotContain "<table"
        }

        test("several charts and a table on one page") {
            val body = page("/scripts/showcase_mixed?_run=1")
            for (i in 0..2) body shouldContain "data-chart=\"chart-data-$i\""
            for (kind in listOf("bar", "pie", "doughnut")) body shouldContain "\"type\":\"$kind\""
            body shouldContain "<table"
        }

        test("paged results with capped charts say both") {
            val body = page("/scripts/showcase_truncated?_run=1")
            body shouldContain "50 rows in"
            body shouldContain "rows 1–50 of 5000"
            body shouldContain "charts use the first 200 rows"
            body shouldContain "data-chart=\"chart-data-0\""
        }

        test("empty results show empty states") {
            val r = h.get("/scripts/showcase_empty?_run=1")
            r.status shouldBe HttpStatusCode.OK
            r.body shouldContain "returned no rows"
            r.body shouldNotContain "<table"
        }

        test("a broken chart does not hide the others") {
            val r = h.get("/scripts/showcase_chart_error?_run=1")
            r.status shouldBe HttpStatusCode.OK
            r.body shouldContain "Chart unavailable: column &#39;missing_column&#39; not found"
            r.body shouldContain "data-chart=\"chart-data-1\""
        }

        test("every parameter kind is rendered as an input") {
            val body = page("/scripts/showcase_params?_run=1")
            body shouldContain "type=\"text\""
            body shouldContain "type=\"number\" step=\"1\""
            body shouldContain "type=\"number\" step=\"any\""
            body shouldContain "type=\"checkbox\""
            body shouldContain "type=\"date\""
            body shouldContain "<select"
            body shouldContain "Matches label or city."
        }

        test("invalid parameter values are reported next to their field") {
            val r = h.get("/scripts/showcase_params?_run=1&status=bogus&from_date=2024-01-01&to_date=2024-12-31")
            r.status shouldBe HttpStatusCode.UnprocessableEntity
            r.body shouldContain "not an allowed option"
            r.body shouldContain "field invalid"
        }

        test("column types render nulls, booleans, text and escaped markup") {
            val body = page("/scripts/showcase_column_types?_run=1")
            body shouldContain "Kraków"
            body shouldContain "東京"
            body shouldContain "&lt;script&gt;alert(1)&lt;/script&gt;"
            body shouldNotContain "<script>alert(1)"
            body shouldContain "<td class=\"num\">1234.5678</td>"
        }

        test("wide tables keep every column") {
            val body = page("/scripts/showcase_wide_table?_run=1")
            body.count("<th scope=\"col\"") shouldBe 12
            body shouldContain "25 rows in"
            body shouldContain "rows 1–25 of 30"
        }

        test("pages are served by number") {
            val second = page("/scripts/showcase_big_table?_run=1&_page=2")
            second shouldContain "rows 101–200 of 20000"
            second shouldContain "<td class=\"num\">101</td>"
            second shouldNotContain "item-00100<"
        }

        test("out of range or invalid pages are clamped") {
            page("/scripts/showcase_big_table?_run=1&_page=999") shouldContain "rows 19901–20000 of 20000"
            page("/scripts/showcase_big_table?_run=1&_page=0") shouldContain "rows 1–100 of 20000"
            page("/scripts/showcase_big_table?_run=1&_page=abc") shouldContain "rows 1–100 of 20000"
        }

        test("pager links keep the form values") {
            val body = page("/scripts/showcase_search_paged?_run=1&q=gadget&_page=2")
            body shouldContain "rows 11–20 of 45"
            body shouldContain "href=\"?q=gadget&amp;_run=1&amp;_page=3\""
            body shouldContain "aria-current=\"page\""
        }

        test("the pager has every page of a big result") {
            val body = page("/scripts/showcase_big_table?_run=1")
            body shouldContain "aria-label=\"Page 200\""
            body shouldContain "&#8230;"
            body shouldContain "rel=\"next\""
            body shouldNotContain "rel=\"prev\""
        }

        test("scripts without a table do not page") {
            val body = page("/scripts/showcase_scatter?_run=1&_page=2")
            body shouldNotContain "class=\"pager\""
            body shouldContain "\"type\":\"scatter\""
        }

        test("filter scripts draw their charts for the chosen filter") {
            page(
                "/scripts/showcase_filter_bar_pie?_run=1&region=EU&from_date=2024-01-01&to_date=2024-06-30&min_amount=",
            ).let {
                it shouldContain "\"type\":\"bar\""
                it shouldContain "\"type\":\"pie\""
            }
            page(
                "/scripts/showcase_filter_monthly_line?_run=1&region=US&from_date=2024-01-01&to_date=2024-12-31",
            ) shouldContain
                "\"type\":\"line\""
            page("/scripts/showcase_top_n_switch?_run=1&limit=2&include_apac=0").let {
                it shouldContain "2 rows in"
                it shouldContain "\"type\":\"doughnut\""
            }
            page("/scripts/showcase_scatter_filters?_run=1&segment=consumer&min_advertising=").let {
                it shouldContain "rows 1–10 of 20"
                it shouldContain "\"type\":\"scatter\""
            }
        }

        test("a search without matches is an empty paged result") {
            val r = h.get("/scripts/showcase_search_paged?_run=1&q=no-such-thing")
            r.status shouldBe HttpStatusCode.OK
            r.body shouldContain "returned no rows"
            r.body shouldNotContain "class=\"pager\""
        }

        test("one page shows no pager at all") {
            val body = page("/scripts/showcase_table?_run=1")
            body shouldContain "24 rows in"
            body shouldNotContain "class=\"pager\""
            body shouldNotContain "pager-arrow"
        }

        test("the pager shows arrows around the active page and the total") {
            val body = page("/scripts/showcase_big_table?_run=1&_page=7")
            body shouldContain "Page 7 of 200"
            val bar = body.substringAfter("class=\"pager-bar\"").substringBefore("</ol>")
            (bar.indexOf("rel=\"prev\"") < bar.indexOf("aria-current")) shouldBe true
            (bar.indexOf("aria-current") < bar.indexOf("rel=\"next\"")) shouldBe true
            bar shouldContain "pager-gap"
        }

        test("the first and last pages disable the missing arrow") {
            page("/scripts/showcase_big_table?_run=1").let {
                it shouldNotContain "rel=\"prev\""
                it shouldContain "No previous page"
            }
            page("/scripts/showcase_big_table?_run=1&_page=200").let {
                it shouldNotContain "rel=\"next\""
                it shouldContain "No next page"
            }
        }

        test("gallery scripts each draw several chart kinds") {
            val cases =
                listOf(
                    Triple("gallery_all_types", 5, listOf("line", "bar", "pie", "doughnut", "scatter")),
                    Triple("gallery_quarterly_bars", 2, listOf("bar", "line")),
                    Triple("gallery_brand_share", 2, listOf("pie", "doughnut")),
                    Triple("gallery_sensor_readings", 2, listOf("line", "scatter")),
                    Triple("gallery_survey_bands", 3, listOf("bar", "line", "doughnut")),
                    Triple("gallery_web_traffic", 2, listOf("line", "bar")),
                )
            for ((name, charts, kinds) in cases) {
                val r = h.get("/scripts/$name?_run=1")
                r.status shouldBe HttpStatusCode.OK
                r.body.count("data-chart=\"chart-data-") shouldBe charts
                for (kind in kinds) r.body shouldContain "\"type\":\"$kind\""
                r.body shouldNotContain "Chart unavailable"
            }
        }

        test("gallery filters change the data") {
            page("/scripts/gallery_quarterly_bars?_run=1&year=ALL").let {
                (it.contains("rows 1–8 of 8") || it.contains("8 rows in")) shouldBe true
            }
            page("/scripts/gallery_quarterly_bars?_run=1&year=2024") shouldContain "4 rows in"
            page("/scripts/gallery_brand_share?_run=1&q=ini&min_share=") shouldContain "1 row in"
            page("/scripts/gallery_web_traffic?_run=1&weekdays_only=1") shouldContain "of 43"
            page(
                "/scripts/gallery_sensor_readings?_run=1&location=Office&from_date=2024-06-01&to_date=2024-06-02",
            ) shouldContain "of 48"
        }

        test("the script list is grouped in order") {
            val body = page("/")
            body
                .split("<h2 class=\"group-title\">")
                .drop(1)
                .map { it.substringBefore(' ') } shouldBe listOf("Charts", "Sales", "Showcase")
            body.count("data-group-count") shouldBe 3
        }

        test("the API lists each script's group") {
            val find =
                h
                    .get("/api/scripts")
                    .json()
                    .jsonObject["scripts"]!!
                    .jsonArray
                    .first { it.jsonObject["name"] == JsonPrimitive("find_sales") }
            find.jsonObject["group"] shouldBe JsonPrimitive("Sales")
        }

        test("rows per page offers the common sizes and selects the current one") {
            val body = page("/scripts/showcase_big_table?_run=1")
            body shouldContain "name=\"_size\""
            for (size in listOf(5, 10, 25, 50, 100)) body shouldContain "<option value=\"$size\""
            body shouldContain "<option value=\"100\" selected>"
            body shouldContain "data-auto-submit"
        }

        test("a chosen size changes the page length and resets the page") {
            val body = page("/scripts/showcase_big_table?_run=1&_size=10&_page=3")
            body shouldContain "rows 21–30 of 20000"
            body shouldContain "Page 3 of 2000"
            body shouldContain "<option value=\"10\" selected>"
            body shouldContain "name=\"_page\" value=\"1\""
        }

        test("the chosen size is kept by the page links") {
            page("/scripts/showcase_big_table?_run=1&_size=10&_page=2") shouldContain
                "href=\"?_run=1&amp;_page=3&amp;_size=10\""
        }

        test("an invalid size falls back to the script default") {
            page("/scripts/showcase_big_table?_run=1&_size=abc") shouldContain "rows 1–100 of 20000"
            page("/scripts/showcase_big_table?_run=1&_size=0") shouldContain "rows 1–100 of 20000"
        }

        test("the size choice is removed where nothing is paged") {
            val scatter = page("/scripts/showcase_scatter?_run=1")
            scatter shouldNotContain "name=\"_size\""
            scatter shouldNotContain "data-auto-submit"
        }

        test("the share link carries the parameters of the view") {
            val body = page("/scripts/sales_by_region?_run=1&from_date=2024-01-01&to_date=2024-03-31")
            val href = body.substringBefore("data-share-link").substringAfterLast("href=\"").substringBefore('"')
            href shouldContain "from_date=2024-01-01"
            href shouldContain "to_date=2024-03-31"
            href shouldContain "_run=1"
            href shouldContain "_page=1"
            href shouldContain "_size=25"
        }

        test("the share link keeps the page and size and escapes values") {
            page("/scripts/showcase_big_table?_run=1&_size=10&_page=3") shouldContain "_page=3&amp;_size=10"
            page("/scripts/showcase_search_paged?_run=1&q=a%26b%3Dc") shouldContain "q=a%26b%3Dc"
        }

        test("the share link of a page that runs on open includes the run") {
            val body = page("/scripts/showcase_params")
            body shouldContain "href=\"?_run=1"
            body shouldContain "data-share-output"
        }

        test("locked scripts are hidden by default and shown on request") {
            Harness.start().use { auth ->
                val cookie = auth.loginLdap("alice", "secret")
                val default = auth.get("/", cookie).body
                default shouldNotContain "Order sizes"
                default shouldContain "href=\"/?all=1\""
                val all = auth.get("/?all=1", cookie).body
                all shouldContain "Order sizes (admin)"
                all shouldContain "class=\"script-card locked\""
                all shouldContain "Requires all of"
                all shouldContain "<code>scripts.admin.read</code>"
                all shouldContain "Quantity versus amount for one region"
                all shouldNotContain "href=\"/scripts/order_sizes\""
                all shouldContain "href=\"/scripts/find_sales\""
                all shouldContain "Hide locked scripts"

                val api =
                    auth
                        .get("/api/scripts?all=1", cookie)
                        .json()
                        .jsonObject["scripts"]!!
                        .jsonArray
                val locked = api.first { it.jsonObject["name"] == JsonPrimitive("order_sizes") }.jsonObject
                locked["accessible"] shouldBe JsonPrimitive(false)
                locked["required_permissions"].toString() shouldBe """["scripts.admin.read"]"""
                auth
                    .get("/api/scripts", cookie)
                    .json()
                    .jsonObject["scripts"]!!
                    .jsonArray
                    .none { it.jsonObject["name"] == JsonPrimitive("order_sizes") } shouldBe true
            }
        }
    })
