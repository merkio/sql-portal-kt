/*
 * Database-independent contract for executing SQL.
 *
 * Every `*-executor` module implements [SqlExecutor] for one database with its
 * dialect, and knows nothing about the application that uses it. Values travel
 * as neutral [Cell]s; failures are reported as [SqlException].
 */
package io.merkio.sqlportal.sqlexecutor

import kotlin.time.Duration

/** A statement with `:name` placeholders and the values for them. */
data class SqlQuery(
    val sql: String,
    val params: List<Pair<String, Cell>> = emptyList(),
    /** Rows returned at most; one more row found sets [SqlResult.truncated]. */
    val maxRows: Int,
    val timeout: Duration,
)

/** Rows of a query, at most [SqlQuery.maxRows]. */
data class SqlResult(
    val columns: List<String> = emptyList(),
    val rows: List<List<Cell>> = emptyList(),
    /** `true` when the query had more rows than were returned. */
    val truncated: Boolean = false,
)

/** Why a statement could not be executed. */
sealed class SqlException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** The statement ran longer than its timeout and was cancelled. */
    class Timeout(
        cause: Throwable? = null,
    ) : SqlException("query timed out", cause)

    /** The database cannot be reached, or no connection was free in time. */
    class Unavailable(
        val detail: String,
        cause: Throwable? = null,
    ) : SqlException("database unavailable: $detail", cause)

    /** The database rejected the statement (syntax, missing table, missing parameter value...). */
    class Failed(
        val detail: String,
        cause: Throwable? = null,
    ) : SqlException("database error: $detail", cause)
}

/** Executes SQL against one database. Implementations keep their connections open between calls. */
interface SqlExecutor : AutoCloseable {
    /** @throws SqlException */
    suspend fun query(query: SqlQuery): SqlResult

    /** Runs a setup script (schema, seed data) that may hold several statements. @throws SqlException */
    suspend fun runScript(sql: String)

    /** @throws SqlException when the database cannot be reached. */
    suspend fun ping()
}

/** Truncates a full table to [maxRows] rows. */
fun Table.limit(maxRows: Int): SqlResult = SqlResult(columns, rows.take(maxRows), rows.size > maxRows)
