package io.merkio.sqlportal.adapters.sql

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.merkio.sqlportal.domain.query.QueryException
import io.merkio.sqlportal.domain.query.QueryExecutor
import io.merkio.sqlportal.domain.query.QueryRequest
import io.merkio.sqlportal.domain.query.QueryResult
import io.merkio.sqlportal.sqlexecutor.Cell
import io.merkio.sqlportal.sqlexecutor.DialectException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.sql.SQLTransientConnectionException
import java.sql.Types
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

/**
 * Shared JDBC plumbing: a HikariCP pool, blocking work on the IO dispatcher,
 * timeouts enforced by cancelling the running statement, and the mapping of
 * driver errors to [QueryException].
 */
abstract class JdbcExecutor(
    config: HikariConfig,
) : QueryExecutor,
    AutoCloseable {
    protected val pool: HikariDataSource =
        try {
            HikariDataSource(config)
        } catch (e: RuntimeException) {
            throw IllegalStateException("cannot connect to the database: ${rootMessage(e)}", e)
        }

    /** Runs the query on [connection]; [running] receives each statement so a timeout can cancel it. */
    protected abstract fun fetch(
        connection: Connection,
        request: QueryRequest,
        running: AtomicReference<PreparedStatement?>,
    ): QueryResult

    override suspend fun execute(request: QueryRequest): QueryResult =
        coroutineScope {
            val running = AtomicReference<PreparedStatement?>()
            val work = async(Dispatchers.IO) { translate { pool.connection.use { fetch(it, request, running) } } }
            withTimeoutOrNull(request.timeout) { work.await() } ?: run {
                runCatching { running.get()?.cancel() }
                work.cancel()
                throw QueryException.Timeout()
            }
        }

    override suspend fun ping() {
        withContext(Dispatchers.IO) {
            translate { pool.connection.use { it.createStatement().use { s -> s.execute("SELECT 1") } } }
        }
    }

    override fun close() = pool.close()

    /** Applies the request timeout at the driver as well (whole seconds, at least one). */
    protected fun PreparedStatement.limit(request: QueryRequest) {
        queryTimeout = ceil(request.timeout.inWholeMilliseconds / MILLIS_PER_SECOND).toInt().coerceAtLeast(1)
        // One extra row tells us whether the result was cut short.
        maxRows = request.maxRows + 1
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

    protected fun <T> translate(block: () -> T): T =
        try {
            block()
        } catch (e: DialectException) {
            throw QueryException.Database(e.message.orEmpty(), e)
        } catch (e: SQLTimeoutException) {
            throw QueryException.Timeout().apply { initCause(e) }
        } catch (e: SQLException) {
            throw mapError(e)
        }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}

/** Connection problems (pool exhausted, server gone) are "unavailable"; everything else a database error. */
internal fun mapError(e: SQLException): QueryException =
    if (e is SQLTransientConnectionException || e.sqlState?.startsWith("08") == true) {
        QueryException.Unavailable(e.message.orEmpty(), e)
    } else {
        QueryException.Database(e.message.orEmpty(), e)
    }

internal fun rootMessage(e: Throwable): String {
    var cause = e
    while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
    return cause.message ?: cause.toString()
}
