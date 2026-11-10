package io.merkio.sqlportal.application

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
import io.merkio.sqlportal.domain.scripts.ScriptCatalog
import io.merkio.sqlportal.domain.scripts.ScriptDefinition
import io.merkio.sqlportal.domain.scripts.ScriptException
import org.slf4j.LoggerFactory
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
    private val log = LoggerFactory.getLogger(ScriptService::class.java)

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
            log.info("script {} returned {} rows", script.name, run.result.rows.size)
            return run
        } catch (e: ScriptException) {
            metrics.scriptRun(script.name.value, e.outcome, started.elapsedNow(), 0)
            log.warn("script {} failed: {}", script.name, e.message)
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
            table = query(script.sql, params, script.maxRows, timeout)
            pageInfo = null
        } else {
            val body =
                script.sql
                    .trim()
                    .trimEnd(';')
                    .trimEnd()
            pageInfo = PageInfo.plan(request, countRows(body, params, timeout))
            val paged = "SELECT * FROM (\n$body\n) AS portal_page LIMIT ${pageInfo.size} OFFSET ${pageInfo.offset}"
            table = query(paged, params, pageInfo.size, timeout)
            if (script.charts.isNotEmpty()) chartSource = query(body, params, script.maxRows, timeout)
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

    private suspend fun query(
        sql: String,
        params: List<Pair<String, Value>>,
        maxRows: Int,
        timeout: Duration,
    ): QueryResult =
        try {
            executor.execute(QueryRequest(sql, params, maxRows, timeout))
        } catch (e: QueryException) {
            throw ScriptException.Query(e)
        }

    /** Number of rows the query returns, across all pages. */
    private suspend fun countRows(
        body: String,
        params: List<Pair<String, Value>>,
        timeout: Duration,
    ): Long {
        val counted = query("SELECT COUNT(*) AS n FROM (\n$body\n) AS portal_count", params, 1, timeout)
        return when (val n = counted.rows.firstOrNull()?.firstOrNull()) {
            is Value.Int -> n.value.coerceAtLeast(0)
            is Value.Float -> n.value.coerceAtLeast(0.0).toLong()
            is Value.Text -> n.value.toLongOrNull() ?: 0
            else -> 0
        }
    }
}
