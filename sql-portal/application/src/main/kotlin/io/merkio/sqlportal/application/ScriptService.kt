package io.merkio.sqlportal.application

import io.github.oshai.kotlinlogging.KotlinLogging
import io.merkio.sqlportal.domain.access.Principal
import io.merkio.sqlportal.domain.access.ScriptAccessRule
import io.merkio.sqlportal.domain.common.Value
import io.merkio.sqlportal.domain.query.PageInfo
import io.merkio.sqlportal.domain.query.PageRequest
import io.merkio.sqlportal.domain.query.QueryException
import io.merkio.sqlportal.domain.query.QueryExecutor
import io.merkio.sqlportal.domain.query.QueryRequest
import io.merkio.sqlportal.domain.query.QueryResult
import io.merkio.sqlportal.domain.scripts.ChartOutcome
import io.merkio.sqlportal.domain.scripts.FieldError
import io.merkio.sqlportal.domain.scripts.MAX_PAGE_SIZE
import io.merkio.sqlportal.domain.scripts.ParamKind
import io.merkio.sqlportal.domain.scripts.ScriptCatalog
import io.merkio.sqlportal.domain.scripts.ScriptDefinition
import io.merkio.sqlportal.domain.scripts.ScriptException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Entry shown in the list of scripts. */
data class ScriptSummary(
    val name: String,
    val title: String,
    val description: String,
    val group: String?,
    /** The principal may run the script. Otherwise it is listed as locked. */
    val accessible: Boolean,
    /** Permissions a caller needs to run the script (all of them). */
    val requiredPermissions: List<String>,
)

/**
 * A script the principal is allowed to use. Only [ScriptService.authorize]
 * creates one, so holding it proves the permission check happened.
 */
class AuthorizedScript internal constructor(
    val script: ScriptDefinition,
)

/** Outcome of running a script. */
data class ScriptRun(
    val script: ScriptDefinition,
    val params: List<Pair<String, Value>>,
    /** The rows of the table: the whole result, or one page of it. */
    val result: QueryResult,
    /** Set when the table is paged; `null` when every row is in [result]. */
    val page: PageInfo?,
    /** One entry per configured chart. */
    val charts: List<ChartOutcome>,
    /** The charts were built from fewer rows than the query returns (`max_rows`). */
    val capped: Boolean,
    val duration: Duration,
)

/** A page as asked for by a caller; a missing or out-of-range [size] falls back to the script's page size. */
data class PageChoice(
    val number: Int = 1,
    val size: Int? = null,
)

/** How a request reaches a script. */
enum class Invocation {
    /** Run now with the parameters as given (JSON API). */
    DIRECT,

    /** A form was opened: run only if the script needs no input. */
    FORM_OPENED,

    /** A form was sent: run; unchecked boolean parameters (absent from a post) count as `false`. */
    FORM_SENT,
}

/**
 * What happened to a request to use a script. [ScriptService.execute] returns
 * one of these instead of throwing, so every front end renders the same cases.
 */
sealed interface ScriptOutcome {
    object NotFound : ScriptOutcome

    object Forbidden : ScriptOutcome

    /** The script has parameters to fill in and the form was not submitted yet. */
    data class NeedsInput(
        val script: ScriptDefinition,
        val input: Map<String, String>,
    ) : ScriptOutcome

    data class Ran(
        val run: ScriptRun,
        val input: Map<String, String>,
    ) : ScriptOutcome

    data class Invalid(
        val script: ScriptDefinition,
        val input: Map<String, String>,
        val errors: List<FieldError>,
    ) : ScriptOutcome

    data class Failed(
        val script: ScriptDefinition,
        val input: Map<String, String>,
        val error: QueryException,
    ) : ScriptOutcome
}

/** Default access rule: the principal must hold every permission the script declares. */
object RequiredPermissions : ScriptAccessRule {
    override fun canAccess(
        principal: Principal,
        script: ScriptDefinition,
    ) = principal.canAll(script.requiredPermissions)
}

/** Use cases around discovering and running scripts. */
class ScriptService(
    private val catalog: ScriptCatalog,
    private val executor: QueryExecutor,
    private val metrics: Metrics,
    private val access: ScriptAccessRule = RequiredPermissions,
) {
    private val log = KotlinLogging.logger {}

    /** Every script, sorted by title, each marked as accessible or locked. */
    fun listAll(principal: Principal): List<ScriptSummary> =
        catalog
            .all()
            .map { s ->
                ScriptSummary(
                    name = s.name.value,
                    title = s.title,
                    description = s.description,
                    group = s.group,
                    accessible = access.canAccess(principal, s),
                    requiredPermissions = s.requiredPermissions.map { it.value },
                )
            }.sortedBy { it.title.lowercase() }

    /** Scripts the principal is allowed to use, sorted by title. */
    fun listAvailable(principal: Principal) = listAll(principal).filter { it.accessible }

    /**
     * The only gate: finds the script and checks the principal may use it.
     * Denials are recorded in metrics.
     *
     * @throws ScriptException.NotFound
     * @throws ScriptException.Forbidden
     */
    fun authorize(
        principal: Principal,
        name: String,
    ): AuthorizedScript {
        val script = catalog.get(name)
        val denial =
            when {
                script == null -> ScriptException.NotFound()
                !access.canAccess(principal, script) -> ScriptException.Forbidden()
                else -> return AuthorizedScript(script)
            }
        // Unknown names are not recorded under their own label to keep metric cardinality bounded.
        val label = if (denial is ScriptException.NotFound) "_unknown" else name
        metrics.scriptRun(label, denial.outcome, Duration.ZERO, 0)
        throw denial
    }

    /**
     * The one call front ends make: finds the script, checks the principal,
     * binds the parameters and runs it.
     *
     * [invocation] says how the request arrived. The returned `input` is the
     * parameter map as used.
     */
    suspend fun execute(
        principal: Principal,
        name: String,
        rawParams: Map<String, String>,
        page: PageChoice? = null,
        invocation: Invocation = Invocation.DIRECT,
    ): ScriptOutcome {
        val authorized =
            try {
                authorize(principal, name)
            } catch (_: ScriptException.NotFound) {
                return ScriptOutcome.NotFound
            } catch (_: ScriptException.Forbidden) {
                return ScriptOutcome.Forbidden
            }
        val script = authorized.script
        if (invocation == Invocation.FORM_OPENED && !script.runsWithoutInput) {
            return ScriptOutcome.NeedsInput(script, rawParams)
        }
        val input = rawParams.toMutableMap()
        if (invocation == Invocation.FORM_SENT) {
            script.params.filter { it.kind == ParamKind.BOOLEAN }.forEach { input.putIfAbsent(it.name, "false") }
        }
        return try {
            val request =
                page?.let {
                    PageRequest(
                        it.number,
                        it.size?.takeIf { n -> n in 1..MAX_PAGE_SIZE } ?: script.pageSize,
                    )
                }
            ScriptOutcome.Ran(run(authorized, input, request), input)
        } catch (e: ScriptException.InvalidParams) {
            ScriptOutcome.Invalid(script, input, e.errors)
        } catch (e: ScriptException.Query) {
            ScriptOutcome.Failed(script, input, e.error)
        }
    }

    /**
     * Runs a script. With [page], the table holds that page of the whole result
     * (the count and the page are two queries); without it, the table holds the
     * result capped at `max_rows`. Charts always use the capped result.
     *
     * @throws ScriptException.InvalidParams
     * @throws ScriptException.Query
     */
    suspend fun run(
        authorized: AuthorizedScript,
        rawParams: Map<String, String>,
        page: PageRequest? = null,
    ): ScriptRun {
        val script = authorized.script
        val started = TimeSource.Monotonic.markNow()
        try {
            val run = runInner(script, rawParams, page, started)
            metrics.scriptRun(script.name.value, "success", run.duration, run.result.rows.size)
            log.info { "script ${script.name} returned ${run.result.rows.size} rows" }
            return run
        } catch (e: ScriptException) {
            metrics.scriptRun(script.name.value, e.outcome, started.elapsedNow(), 0)
            log.warn { "script ${script.name} failed: ${e.message}" }
            throw e
        }
    }

    private suspend fun runInner(
        script: ScriptDefinition,
        rawParams: Map<String, String>,
        page: PageRequest?,
        started: TimeSource.Monotonic.ValueTimeMark,
    ): ScriptRun {
        val params = script.bindParams(rawParams)
        val timeout = script.timeoutSecs.seconds
        // A table that is not shown has nothing to page.
        val request = page?.takeIf { script.showTable }
        val table: QueryResult
        val pageInfo: PageInfo?
        var chartSource: QueryResult? = null
        if (request == null) {
            table = query(script, "table", script.sql, params, script.maxRows, timeout)
            pageInfo = null
        } else {
            val body =
                script.sql
                    .trim()
                    .trimEnd(';')
                    .trimEnd()
            pageInfo = PageInfo.plan(request, countRows(script, body, params, timeout))
            val paged = "SELECT * FROM (\n$body\n) AS portal_page LIMIT ${pageInfo.size} OFFSET ${pageInfo.offset}"
            table = query(script, "page", paged, params, pageInfo.size, timeout)
            if (script.charts.isNotEmpty()) chartSource = query(script, "chart", body, params, script.maxRows, timeout)
        }
        val chartInput = chartSource ?: table
        return ScriptRun(
            script = script,
            params = params,
            result = table,
            page = pageInfo,
            charts = script.charts.map { it.build(chartInput) },
            capped = chartInput.truncated,
            duration = started.elapsedNow(),
        )
    }

    /** Sends one statement; its time in the database is reported to the metrics, whatever the outcome. */
    private suspend fun query(
        script: ScriptDefinition,
        purpose: String,
        sql: String,
        params: List<Pair<String, Value>>,
        maxRows: Int,
        timeout: Duration,
    ): QueryResult {
        val started = TimeSource.Monotonic.markNow()
        var outcome = "success"
        try {
            return executor.execute(QueryRequest(sql, params, maxRows, timeout))
        } catch (e: QueryException) {
            outcome = if (e is QueryException.Timeout) "timeout" else "error"
            throw ScriptException.Query(e)
        } finally {
            metrics.sqlExecuted(script.name.value, purpose, outcome, started.elapsedNow())
        }
    }

    /** Number of rows the query returns, across all pages. */
    private suspend fun countRows(
        script: ScriptDefinition,
        body: String,
        params: List<Pair<String, Value>>,
        timeout: Duration,
    ): Long {
        val counted =
            query(script, "count", "SELECT COUNT(*) AS n FROM (\n$body\n) AS portal_count", params, 1, timeout)
        return when (val n = counted.rows.firstOrNull()?.firstOrNull()) {
            is Value.Int -> n.value.coerceAtLeast(0)
            is Value.Float -> n.value.coerceAtLeast(0.0).toLong()
            is Value.Text -> n.value.toLongOrNull() ?: 0
            else -> 0
        }
    }
}
