/*
 * HTML views. Pages are rendered from the Freemarker templates in
 * resources/sqlportal/templates; every value is escaped by the template.
 * JSON embedded in `<script type="application/json">` blocks goes through
 * [jsonForScript].
 */
package io.merkio.sqlportal.api

import io.merkio.sqlportal.application.ScriptRun
import io.merkio.sqlportal.application.ScriptSummary
import io.merkio.sqlportal.common.template.Templates
import io.merkio.sqlportal.common.template.contentVersion
import io.merkio.sqlportal.domain.common.Value
import io.merkio.sqlportal.domain.common.formatNumber
import io.merkio.sqlportal.domain.scripts.ChartData
import io.merkio.sqlportal.domain.scripts.ChartOutcome
import io.merkio.sqlportal.domain.scripts.DatasetData
import io.merkio.sqlportal.domain.scripts.FieldError
import io.merkio.sqlportal.domain.scripts.ParamDefinition
import io.merkio.sqlportal.domain.scripts.ParamKind
import io.merkio.sqlportal.domain.scripts.ScriptDefinition
import io.merkio.sqlportal.domain.scripts.isTruthy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.util.SortedMap

internal object Assets {
    private fun load(name: String): String =
        requireNotNull(Assets::class.java.getResource("/sqlportal/static/$name")) { "missing asset $name" }.readText()

    val css = load("app.css")
    val js = load("app.js")

    /** Used as `?v=` in the page, so a new build never runs with a stylesheet cached from an older one. */
    val version = contentVersion(css, js)
}

private val log = LoggerFactory.getLogger("io.merkio.sqlportal.api.Views")

private val templates = Templates(Assets::class.java, "/sqlportal/templates")

internal val TEMPLATE_NAMES =
    listOf("layout.ftlh", "index.ftlh", "login.ftlh", "message.ftlh", "fields.ftlh", "script.ftlh")

/** Compiles every template so a broken one fails at startup. */
internal fun checkTemplates() = templates.check(TEMPLATE_NAMES)

/** A failing render is logged and answered with a generic message rather than leaking template details. */
private fun render(
    name: String,
    model: Map<String, Any?>,
): String =
    try {
        templates.render(name, model)
    } catch (e: io.merkio.sqlportal.common.template.TemplateException) {
        log.error("template render failed: {}", name, e)
        """<p class="alert" role="alert">The page could not be shown.</p>"""
    }

/** Serialises JSON so it can never terminate the surrounding `<script>` block. */
fun jsonForScript(value: JsonElement): String =
    value
        .toString()
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("&", "\\u0026")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")

/** A value as JSON; non-finite floats become `null`. */
fun jsonOf(value: Value): JsonElement =
    when (value) {
        Value.Null -> JsonNull
        is Value.Bool -> JsonPrimitive(value.value)
        is Value.Int -> JsonPrimitive(value.value)
        is Value.Float -> if (value.value.isFinite()) JsonPrimitive(value.value) else JsonNull
        is Value.Text -> JsonPrimitive(value.value)
    }

private fun jsonNumber(d: Double?): JsonElement = if (d == null || !d.isFinite()) JsonNull else JsonPrimitive(d)

/** Navigation state for the page header. */
data class Nav(
    val displayName: String,
    /** Renders a logout button using this CSRF token. */
    val logoutCsrf: String?,
)

/** A complete page around a rendered body. */
fun page(
    ui: UiSettings,
    title: String,
    nav: Nav?,
    body: String,
    withCharts: Boolean,
): String =
    render(
        "layout.ftlh",
        mapOf(
            "title" to title,
            "assetVersion" to Assets.version,
            "uiTitle" to ui.title,
            "nav" to nav,
            "body" to body,
            "withCharts" to withCharts,
            "chartjsUrl" to ui.chartjsUrl,
        ),
    )

class ScriptLink(
    val name: String,
    val title: String,
    val description: String,
    val accessible: Boolean,
    val requiredPermissions: List<String>,
)

class GroupView(
    /** `null` when the list has no groups at all: then no heading is shown. */
    val name: String?,
    val scripts: List<ScriptLink>,
)

/**
 * Groups in alphabetical order (case-insensitively), ungrouped scripts last.
 * Within a group the order of [scripts] is kept.
 */
internal fun groupScripts(scripts: List<ScriptSummary>): List<GroupView> {
    fun link(s: ScriptSummary) = ScriptLink(s.name, s.title, s.description, s.accessible, s.requiredPermissions)

    val named = sortedMapOf<String, Pair<String, MutableList<ScriptLink>>>()
    val ungrouped = mutableListOf<ScriptLink>()
    for (s in scripts) {
        val group = s.group
        if (group ==
            null
        ) {
            ungrouped += link(s)
        } else {
            named.getOrPut(group.lowercase()) { group to mutableListOf() }.second +=
                link(s)
        }
    }
    val groups = named.values.map { (name, links) -> GroupView(name, links) }.toMutableList()
    if (ungrouped.isNotEmpty()) groups += GroupView(if (named.isEmpty()) null else "Other scripts", ungrouped)
    return groups
}

/** The start page. [scripts] is the list to show; [locked] counts the locked scripts for this user. */
fun indexBody(
    scripts: List<ScriptSummary>,
    showAll: Boolean,
    locked: Int,
): String =
    render(
        "index.ftlh",
        mapOf("total" to scripts.size, "groups" to groupScripts(scripts), "showAll" to showAll, "locked" to locked),
    )

fun loginBody(
    ldap: Boolean,
    oidc: Boolean,
    csrf: String,
    error: String?,
): String = render("login.ftlh", mapOf("ldap" to ldap, "oidc" to oidc, "csrf" to csrf, "error" to error))

fun messageBody(
    title: String,
    message: String,
): String = render("message.ftlh", mapOf("title" to title, "message" to message))

class OptionView(
    val value: String,
    val label: String,
    val selected: Boolean,
)

class FieldView(
    val id: String,
    val name: String,
    val label: String,
    val kind: String,
    val value: String,
    val checked: Boolean,
    val required: Boolean,
    val min: String?,
    val max: String?,
    val maxLength: String,
    val help: String?,
    val error: String?,
    val invalid: Boolean,
    val options: List<OptionView>,
)

private fun fieldView(
    p: ParamDefinition,
    value: String?,
    error: FieldError?,
): FieldView {
    val current = value ?: p.default ?: ""
    return FieldView(
        id = "p-${p.name}",
        name = p.name,
        label = p.label,
        kind = p.kind.label,
        value = current,
        checked = p.kind == ParamKind.BOOLEAN && isTruthy(current),
        required = p.required,
        min = p.min?.let(::formatNumber),
        max = p.max?.let(::formatNumber),
        maxLength = p.maxLength.toString(),
        help = p.help,
        error = error?.message,
        invalid = error != null,
        options = p.options.map { OptionView(it.value, it.label, it.value == current) },
    )
}

/** Chart.js configuration for a prepared chart. */
fun chartConfig(chart: ChartData): JsonElement =
    buildJsonObject {
        put("type", chart.kind.label)
        putJsonObject("data") {
            putJsonArray("labels") { chart.labels.forEach { add(it) } }
            putJsonArray("datasets") {
                for (d in chart.datasets) {
                    addJsonObject {
                        put("label", d.label)
                        put(
                            "data",
                            when (val data = d.data) {
                                is DatasetData.Values -> buildJsonArray { data.values.forEach { add(jsonNumber(it)) } }
                                is DatasetData.Points ->
                                    buildJsonArray {
                                        data.points.forEach { (x, y) ->
                                            addJsonObject {
                                                put("x", jsonNumber(x))
                                                put("y", jsonNumber(y))
                                            }
                                        }
                                    }
                            },
                        )
                    }
                }
            }
        }
        putJsonObject("options") {
            putJsonObject("plugins") {
                putJsonObject("title") {
                    put("display", true)
                    put("text", chart.title)
                }
            }
        }
    }

class CellView(
    val text: String,
    val numeric: Boolean,
)

class ChartView(
    val index: Int,
    val title: String,
    /** Already safe for a `<script>` block (see [jsonForScript]). */
    val json: String,
    val error: String?,
)

class PageLinkView(
    /** `null` marks a gap ("…") between shown page numbers. */
    val number: Int?,
    val href: String,
    val current: Boolean,
    /** Not next to the active page; hidden on narrow screens. */
    val far: Boolean,
)

class SizeOptionView(
    val value: Int,
    val selected: Boolean,
)

class HiddenFieldView(
    val name: String,
    val value: String,
)

class PagerView(
    val firstRow: Long,
    val lastRow: Long,
    val total: Long,
    val current: Int,
    val pages: Int,
    val prev: String?,
    val next: String?,
    val links: List<PageLinkView>,
    /** Rows per page in effect, and the choices offered for it. */
    val size: Int,
    val sizeOptions: List<SizeOptionView>,
    /** Form values to keep when the page size changes (the page resets to 1). */
    val hiddenFields: List<HiddenFieldView>,
)

class RunView(
    val meta: String,
    /** What the charts or the table leave out, in words. */
    val notice: String?,
    val pager: PagerView?,
    val charts: List<ChartView>,
    val showTable: Boolean,
    val empty: Boolean,
    val columns: List<String>,
    val rows: List<List<CellView>>,
)

private fun encode(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

/** The form values (names starting with `_` are paging controls) as query pairs. */
private fun formPairs(values: SortedMap<String, String>) =
    values.filterKeys { !it.startsWith("_") }.map {
        it.key to
            it.value
    }

private fun query(pairs: List<Pair<String, String>>) =
    "?" + pairs.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }

/** Query string that keeps the form values and selects [page]. */
private fun pageHref(
    values: SortedMap<String, String>,
    page: Int,
): String =
    query(
        formPairs(values) + listOf("_run" to "1", "_page" to page.toString()) +
            listOfNotNull(values["_size"]?.let { "_size" to it }),
    )

/** Rows per page offered in the selector. */
private val PAGE_SIZE_CHOICES = listOf(5, 10, 25, 50, 100)

/** The script's own page size is always among the choices, so the default can be selected. */
internal fun sizeOptions(
    current: Int,
    default: Int,
) = (PAGE_SIZE_CHOICES + default).toSortedSet().map { SizeOptionView(it, it == current) }

/** Page numbers to link to: the first, the last and a window around the current page. Gaps are `null`. */
internal fun pageNumbers(
    current: Int,
    pages: Int,
): List<Int?> {
    val shown = sortedSetOf(1, pages)
    shown += (current - 2).coerceAtLeast(1)..(current + 2).coerceAtMost(pages)
    val out = mutableListOf<Int?>()
    var previous: Int? = null
    for (n in shown) {
        if (previous != null && n > previous + 1) out += null
        out += n
        previous = n
    }
    return out
}

private fun pagerView(
    run: ScriptRun,
    values: SortedMap<String, String>,
): PagerView? {
    val info = run.page ?: return null
    return PagerView(
        firstRow = info.firstRow,
        lastRow = info.lastRow,
        total = info.total,
        current = info.number,
        pages = info.pages,
        prev = if (info.number > 1) pageHref(values, info.number - 1) else null,
        next = if (info.number < info.pages) pageHref(values, info.number + 1) else null,
        links =
            pageNumbers(info.number, info.pages).map { n ->
                PageLinkView(
                    number = n,
                    href = n?.let { pageHref(values, it) }.orEmpty(),
                    current = n == info.number,
                    far = n == null || kotlin.math.abs(n - info.number) > 1,
                )
            },
        size = info.size,
        sizeOptions = sizeOptions(info.size, run.script.pageSize),
        hiddenFields = formPairs(values).map { HiddenFieldView(it.first, it.second) },
    )
}

private fun plural(
    n: Int,
    word: String,
) = "$n $word" + if (n == 1) "" else "s"

private fun runView(
    run: ScriptRun,
    values: SortedMap<String, String>,
): RunView {
    val rows = run.result.rows.size
    val millis = run.duration.inWholeMilliseconds
    val info = run.page
    val meta =
        if (info != null && info.total > 0) {
            "${plural(rows, "row")} in $millis ms · rows ${info.firstRow}–${info.lastRow} of ${info.total}"
        } else {
            "${plural(rows, "row")} in $millis ms"
        }
    // Charts are capped at max_rows; an unpaged table is capped the same way.
    val notice =
        when {
            !run.capped -> null
            info != null -> "charts use the first ${run.script.maxRows} rows"
            else -> "truncated to the first ${run.script.maxRows} rows"
        }
    return RunView(
        meta = meta,
        notice = notice,
        pager = pagerView(run, values),
        charts =
            run.charts.mapIndexed { i, chart ->
                when (chart) {
                    is ChartOutcome.Drawn ->
                        ChartView(
                            i,
                            chart.data.title,
                            jsonForScript(chartConfig(chart.data)),
                            null,
                        )
                    is ChartOutcome.Failed -> ChartView(i, "", "", chart.message)
                }
            },
        showTable = run.script.showTable,
        empty = run.result.rows.isEmpty(),
        columns = run.result.columns,
        rows =
            run.result.rows.map { row ->
                row.map { CellView(it.display(), it is Value.Int || it is Value.Float) }
            },
    )
}

/** Link to the page with the given values; once run, the page and size too, so the recipient sees the same rows. */
private fun shareHref(
    values: SortedMap<String, String>,
    run: ScriptRun?,
): String {
    val pairs = formPairs(values).toMutableList()
    if (run != null) {
        pairs += "_run" to "1"
        run.page?.let {
            pairs += "_page" to it.number.toString()
            pairs += "_size" to it.size.toString()
        }
    }
    return query(pairs)
}

/** Script page: the parameter form and, when available, the results. */
fun scriptBody(
    script: ScriptDefinition,
    values: Map<String, String>,
    fieldErrors: List<FieldError>,
    run: ScriptRun?,
    error: String?,
): String {
    val sorted = values.toSortedMap()
    return render(
        "script.ftlh",
        mapOf(
            "title" to script.title,
            "description" to script.description.ifEmpty { null },
            "error" to error,
            "hasParams" to script.params.isNotEmpty(),
            "name" to script.name.value,
            "fields" to
                script.params.map { p -> fieldView(p, values[p.name], fieldErrors.firstOrNull { it.param == p.name }) },
            "shareHref" to shareHref(sorted, run),
            "run" to run?.let { runView(it, sorted) },
        ),
    )
}
