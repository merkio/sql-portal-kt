package io.merkio.sqlportal.domain.query

import io.merkio.sqlportal.domain.common.Value
import kotlin.time.Duration

/** What the application asks the database adapter to run. */
data class QueryRequest(
    /** SQL text with `:name` placeholders. */
    val sql: String,
    /** Bound parameters by name. */
    val params: List<Pair<String, Value>> = emptyList(),
    val maxRows: Int,
    val timeout: Duration,
)

/** Tabular result of a script. */
data class QueryResult(
    val columns: List<String> = emptyList(),
    val rows: List<List<Value>> = emptyList(),
    /** `true` when more rows existed than `maxRows`. */
    val truncated: Boolean = false,
) {
    fun columnIndex(name: String): Int? = columns.indexOf(name).takeIf { it >= 0 }
}

sealed class QueryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    class Timeout : QueryException("query timed out")

    class Database(
        val detail: String,
        cause: Throwable? = null,
    ) : QueryException("database error: $detail", cause)

    class Unavailable(
        val detail: String,
        cause: Throwable? = null,
    ) : QueryException("database unavailable: $detail", cause)
}

/** Executes SQL for a script. */
interface QueryExecutor {
    /** @throws QueryException */
    suspend fun execute(request: QueryRequest): QueryResult

    /** @throws QueryException when the database cannot be reached. */
    suspend fun ping()

    /** Runs a setup script (schema, seed data). Not subject to script rules. */
    suspend fun runScript(sql: String)
}

/** A page the caller asks for. [number] is 1-based. */
data class PageRequest(
    val number: Int,
    val size: Int,
)

/** The page that is actually served, after clamping to the pages that exist. */
data class PageInfo(
    val number: Int,
    val size: Int,
    /** Rows matching the query, across all pages. */
    val total: Long,
    /** Number of pages; at least one, even for an empty result. */
    val pages: Int,
) {
    /** Rows before this page. */
    val offset: Long get() = (number - 1).toLong() * size

    /** Position of the first row on this page, 1-based; 0 when there are none. */
    val firstRow: Long get() = if (total == 0L) 0 else offset + 1

    /** Position of the last row on this page, 1-based. */
    val lastRow: Long get() = minOf(offset + size, total)

    companion object {
        /**
         * Clamps the requested page to the pages that exist. A page number
         * below one is the first page, one above the last is the last page.
         */
        fun plan(
            request: PageRequest,
            total: Long,
        ): PageInfo {
            val size = request.size.coerceAtLeast(1)
            val pages = ((total + size - 1) / size).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            return PageInfo(request.number.coerceIn(1, pages), size, total, pages)
        }
    }
}
