package io.merkio.sqlportal.sqlexecutor.jdbc

import io.merkio.sqlportal.sqlexecutor.Cell
import io.merkio.sqlportal.sqlexecutor.DialectException
import io.merkio.sqlportal.sqlexecutor.SqlException
import io.merkio.sqlportal.sqlexecutor.SqlExecutor
import io.merkio.sqlportal.sqlexecutor.SqlQuery
import io.merkio.sqlportal.sqlexecutor.SqlResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.sql.SQLTransientConnectionException
import java.sql.Types
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

/**
 * Shared JDBC plumbing: reused connections, blocking work on the IO
 * dispatcher, timeouts enforced by cancelling the running statement, and the
 * mapping of driver errors to [SqlException].
 */
abstract class JdbcExecutor(
    private val connections: JdbcConnections,
) : SqlExecutor {
    /** Runs the query on [connection]; [running] receives each statement so a timeout can cancel it. */
    protected abstract fun fetch(
        connection: Connection,
        query: SqlQuery,
        running: AtomicReference<PreparedStatement?>,
    ): SqlResult

    /** Runs a script of one or more statements on [connection]. */
    protected abstract fun script(
        connection: Connection,
        sql: String,
    )

    /**
     * Runs the query on the IO dispatcher. A watchdog cancels the running
     * statement when the timeout passes; whatever the driver then reports, the
     * outcome is a timeout.
     */
    override suspend fun query(query: SqlQuery): SqlResult =
        withContext(Dispatchers.IO) {
            val running = AtomicReference<PreparedStatement?>()
            val timedOut = AtomicBoolean(false)
            val watchdog =
                launch {
                    delay(query.timeout)
                    timedOut.set(true)
                    runCatching { running.get()?.cancel() }
                }
            try {
                translate { connections.use { fetch(it, query, running) } }
            } catch (e: SqlException) {
                if (timedOut.get()) throw SqlException.Timeout(e)
                throw e
            } finally {
                watchdog.cancel()
            }
        }

    override suspend fun runScript(sql: String) {
        withContext(Dispatchers.IO) { translate { connections.use { script(it, sql) } } }
    }

    override suspend fun ping() {
        withContext(Dispatchers.IO) {
            translate { connections.use { c -> c.createStatement().use { it.execute("SELECT 1") } } }
        }
    }

    override fun close() = connections.close()

    private inline fun <T> translate(block: () -> T): T =
        try {
            block()
        } catch (e: DialectException) {
            throw SqlException.Failed(e.message.orEmpty(), e)
        } catch (e: SQLTimeoutException) {
            throw SqlException.Timeout(e)
        } catch (e: SQLException) {
            throw mapError(e)
        }

    /** Applies the query timeout at the driver as well (whole seconds, at least one). */
    protected fun PreparedStatement.limit(query: SqlQuery) {
        queryTimeout = ceil(query.timeout.inWholeMilliseconds / MILLIS_PER_SECOND).toInt().coerceAtLeast(1)
        // One extra row tells us whether the result was cut short.
        maxRows = query.maxRows + 1
    }

    protected fun PreparedStatement.bind(cells: List<Cell>) {
        cells.forEachIndexed { i, cell ->
            val index = i + 1
            when (cell) {
                Cell.Null -> setNull(index, Types.NULL)
                is Cell.Bool -> setBoolean(index, cell.value)
                is Cell.Int -> setLong(index, cell.value)
                is Cell.Float -> setDouble(index, cell.value)
                is Cell.Text -> setString(index, cell.value)
            }
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}

/**
 * Reads rows with [row] until [maxRows] are collected. The statement was
 * limited to one row more, so a further row means the result was cut short.
 */
fun ResultSet.collect(
    columns: List<String>,
    maxRows: Int,
    row: (ResultSet) -> List<Cell>,
): SqlResult {
    val rows = mutableListOf<List<Cell>>()
    var truncated = false
    while (next()) {
        if (rows.size >= maxRows) {
            truncated = true
            break
        }
        rows += row(this)
    }
    return SqlResult(columns, rows, truncated)
}

/** Connection problems (no connection, server gone) are "unavailable"; everything else a database error. */
fun mapError(e: SQLException): SqlException =
    if (e is SQLTransientConnectionException || e.sqlState?.startsWith("08") == true) {
        SqlException.Unavailable(e.message.orEmpty(), e)
    } else {
        SqlException.Failed(e.message.orEmpty(), e)
    }
