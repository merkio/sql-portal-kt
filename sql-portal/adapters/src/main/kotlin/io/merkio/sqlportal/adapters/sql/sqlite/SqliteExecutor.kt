package io.merkio.sqlportal.adapters.sql.sqlite

import com.zaxxer.hikari.HikariConfig
import io.merkio.sqlportal.adapters.sql.JdbcExecutor
import io.merkio.sqlportal.adapters.sql.cellsOf
import io.merkio.sqlportal.adapters.sql.collect
import io.merkio.sqlportal.domain.common.Value
import io.merkio.sqlportal.domain.query.QueryRequest
import io.merkio.sqlportal.domain.query.QueryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * Executes script SQL on SQLite (`sqlite:` URLs) through JDBC.
 *
 * Every connection to an in-memory SQLite database is a separate, empty
 * database, and closing the last one deletes it. So for in-memory URLs the pool
 * keeps exactly one connection and never closes or recycles it.
 */
internal class SqliteExecutor private constructor(
    config: HikariConfig,
) : JdbcExecutor(config) {
    override fun fetch(
        connection: Connection,
        request: QueryRequest,
        running: AtomicReference<PreparedStatement?>,
    ): QueryResult {
        val statement = SqliteDialect.prepare(request.sql, cellsOf(request.params))
        log.debug("executing script with {} parameters", statement.bind.size)
        return connection.prepareStatement(statement.sql).use { ps ->
            running.set(ps)
            ps.limit(request)
            ps.bind(statement.bind)
            ps.executeQuery().use { rs -> read(rs, request.maxRows) }
        }
    }

    override suspend fun runScript(sql: String) {
        withContext(Dispatchers.IO) {
            translate {
                pool.connection.use { connection ->
                    // The driver runs every statement of a script given to executeUpdate.
                    connection.createStatement().use { it.executeUpdate(sql) }
                }
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SqliteExecutor::class.java)

        /** Connects to `sqlite::memory:`, `sqlite:path`, `sqlite://path` or `sqlite:///abs/path` (`?mode=ro`). */
        fun connect(
            url: String,
            maxConnections: Int,
            acquireTimeout: Duration,
        ): SqliteExecutor {
            require(url.startsWith("sqlite:")) { "SqliteExecutor serves sqlite: URLs only" }
            val target = SqliteUrl.parse(url)
            val config =
                HikariConfig().apply {
                    jdbcUrl = target.jdbcUrl
                    connectionTimeout = acquireTimeout.inWholeMilliseconds
                    poolName = "sqlite"
                    if (target.readOnly) addDataSourceProperty("open_mode", OPEN_READONLY)
                    if (target.inMemory) {
                        if (maxConnections != 1) {
                            log.warn(
                                "in-memory SQLite uses a single connection; ignoring max_connections={}",
                                maxConnections,
                            )
                        }
                        maximumPoolSize = 1
                        minimumIdle = 1
                        maxLifetime = 0
                        idleTimeout = 0
                    } else {
                        maximumPoolSize = maxConnections
                    }
                }
            return SqliteExecutor(config)
        }

        /** SQLITE_OPEN_READONLY: the file must exist and is never written. */
        private const val OPEN_READONLY = "1"
    }
}

/** A `sqlite:` URL in JDBC terms. */
internal data class SqliteUrl(
    val jdbcUrl: String,
    val inMemory: Boolean,
    val readOnly: Boolean,
) {
    companion object {
        fun parse(url: String): SqliteUrl {
            val rest = url.removePrefix("sqlite:")
            val path = rest.substringBefore('?').let { if (it.startsWith("//")) it.removePrefix("//") else it }
            val mode =
                rest
                    .substringAfter('?', "")
                    .split('&')
                    .firstOrNull { it.startsWith("mode=") }
                    ?.removePrefix("mode=")
            val inMemory = path == ":memory:" || path.isEmpty() || mode == "memory"
            return if (inMemory) {
                SqliteUrl("jdbc:sqlite::memory:", inMemory = true, readOnly = false)
            } else {
                SqliteUrl("jdbc:sqlite:$path", inMemory = false, readOnly = mode == "ro")
            }
        }
    }
}

/** Reads up to [maxRows] rows; SQLite is dynamically typed, so each cell is decoded by its value. */
private fun read(
    rs: ResultSet,
    maxRows: Int,
): QueryResult {
    val meta = rs.metaData
    val columns = (1..meta.columnCount).map { meta.getColumnLabel(it) }
    return rs.collect(columns, maxRows) { r -> columns.indices.map { decode(r.getObject(it + 1)) } }
}

private fun decode(value: Any?): Value =
    when (value) {
        null -> Value.Null
        is Boolean -> Value.Bool(value)
        is Int -> Value.Int(value.toLong())
        is Long -> Value.Int(value)
        is Short -> Value.Int(value.toLong())
        is Float -> Value.Float(value.toDouble())
        is Double -> Value.Float(value)
        is ByteArray -> Value.Text("<${value.size} bytes>")
        else -> Value.Text(value.toString())
    }
