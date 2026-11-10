package io.merkio.sqlportal.sqlexecutor.sqlite

import io.github.oshai.kotlinlogging.KotlinLogging
import io.merkio.sqlportal.sqlexecutor.Cell
import io.merkio.sqlportal.sqlexecutor.SqlExecutor
import io.merkio.sqlportal.sqlexecutor.SqlQuery
import io.merkio.sqlportal.sqlexecutor.SqlResult
import io.merkio.sqlportal.sqlexecutor.jdbc.JdbcConnections
import io.merkio.sqlportal.sqlexecutor.jdbc.JdbcExecutor
import io.merkio.sqlportal.sqlexecutor.jdbc.collect
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * Executes SQL on SQLite (`sqlite:` URLs) through plain JDBC connections.
 *
 * Every connection to an in-memory SQLite database is a separate, empty
 * database, and closing the last one deletes it. So in-memory URLs use exactly
 * one connection, kept open for the life of the executor.
 */
class SqliteExecutor private constructor(
    connections: JdbcConnections,
) : JdbcExecutor(connections) {
    override fun fetch(
        connection: Connection,
        query: SqlQuery,
        running: AtomicReference<PreparedStatement?>,
    ): SqlResult {
        val statement = SqliteDialect.prepare(query.sql, query.params)
        log.debug { "executing script with ${statement.bind.size} parameters" }
        return connection.prepareStatement(statement.sql).use { ps ->
            running.set(ps)
            ps.limit(query)
            ps.bind(statement.bind)
            ps.executeQuery().use { rs -> read(rs, query.maxRows) }
        }
    }

    // The driver runs every statement of a script given to executeUpdate.
    override fun script(
        connection: Connection,
        sql: String,
    ) {
        connection.createStatement().use { it.executeUpdate(sql) }
    }

    companion object {
        private val log = KotlinLogging.logger {}

        /**
         * Connects to `sqlite::memory:`, `sqlite:path`, `sqlite://path` or
         * `sqlite:///abs/path` (`?mode=ro`); the first connection is opened now.
         *
         * @throws IllegalStateException when the database cannot be opened.
         */
        fun connect(
            url: String,
            maxConnections: Int,
            acquireTimeout: Duration,
        ): SqlExecutor {
            require(url.startsWith("sqlite:")) { "SqliteExecutor serves sqlite: URLs only" }
            val target = SqliteUrl.parse(url)
            val properties = Properties().apply { if (target.readOnly) setProperty("open_mode", OPEN_READONLY) }
            val size =
                if (target.inMemory) {
                    if (maxConnections != 1) {
                        log.warn {
                            "in-memory SQLite uses a single connection; ignoring max_connections=$maxConnections"
                        }
                    }
                    1
                } else {
                    maxConnections
                }
            val connections =
                JdbcConnections(size, acquireTimeout) { DriverManager.getConnection(target.jdbcUrl, properties) }
            return SqliteExecutor(connections)
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
): SqlResult {
    val meta = rs.metaData
    val columns = (1..meta.columnCount).map { meta.getColumnLabel(it) }
    return rs.collect(columns, maxRows) { r -> columns.indices.map { decode(r.getObject(it + 1)) } }
}

private fun decode(value: Any?): Cell =
    when (value) {
        null -> Cell.Null
        is Boolean -> Cell.Bool(value)
        is Int -> Cell.Int(value.toLong())
        is Long -> Cell.Int(value)
        is Short -> Cell.Int(value.toLong())
        is Float -> Cell.Float(value.toDouble())
        is Double -> Cell.Float(value)
        is ByteArray -> Cell.Text("<${value.size} bytes>")
        else -> Cell.Text(value.toString())
    }
