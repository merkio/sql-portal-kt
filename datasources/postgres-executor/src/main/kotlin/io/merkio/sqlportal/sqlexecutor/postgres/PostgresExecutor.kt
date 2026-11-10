package io.merkio.sqlportal.sqlexecutor.postgres

import io.merkio.sqlportal.common.tls.TlsOptions
import io.merkio.sqlportal.sqlexecutor.Cell
import io.merkio.sqlportal.sqlexecutor.SqlExecutor
import io.merkio.sqlportal.sqlexecutor.SqlQuery
import io.merkio.sqlportal.sqlexecutor.SqlResult
import io.merkio.sqlportal.sqlexecutor.jdbc.JdbcConnections
import io.merkio.sqlportal.sqlexecutor.jdbc.JdbcExecutor
import io.merkio.sqlportal.sqlexecutor.jdbc.collect
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * PostgreSQL executor. The SQL rules live in [PostgresDialect]; this class
 * does the I/O: describe the statement, bind the prepared values, decode rows.
 */
class PostgresExecutor private constructor(
    connections: JdbcConnections,
) : JdbcExecutor(connections) {
    override fun fetch(
        connection: Connection,
        query: SqlQuery,
        running: AtomicReference<PreparedStatement?>,
    ): SqlResult {
        // 1. Describe the statement with untyped parameters.
        val plain = PostgresDialect.prepare(query.sql, query.params)
        val (paramTypes, columns) =
            connection.prepareStatement(plain.sql).use { ps ->
                running.set(ps)
                val types = ps.parameterMetaData.let { m -> (1..m.parameterCount).map { m.getParameterTypeName(it) } }
                val cols =
                    ps.metaData?.let { m -> (1..m.columnCount).map { m.getColumnLabel(it) to m.getColumnTypeName(it) } }
                types to cols.orEmpty()
            }

        // 2. Prepare again with the declared parameter types (casts), 3. wrap so every column decodes.
        val typed = PostgresDialect.prepare(query.sql, query.params, paramTypes)
        val wrapped = wrapResult(typed.sql, columns)
        val sql = wrapped?.sql ?: typed.sql
        val kinds = wrapped?.kinds.orEmpty()
        return connection.prepareStatement(sql).use { ps ->
            running.set(ps)
            ps.limit(query)
            ps.bind(typed.bind)
            if (kinds.isEmpty()) {
                ps.execute()
                SqlResult()
            } else {
                ps.executeQuery().use { rs -> read(rs, columns.map { it.first }, kinds, query.maxRows) }
            }
        }
    }

    // The simple query protocol accepts several statements.
    override fun script(
        connection: Connection,
        sql: String,
    ) {
        connection.createStatement().use { it.execute(sql) }
    }

    companion object {
        /**
         * Connects to a `postgres://` or `postgresql://` URL. Up to
         * [maxConnections] plain JDBC connections are opened and reused; the
         * first one is opened now.
         *
         * @throws IllegalArgumentException for a malformed URL or conflicting TLS options.
         * @throws IllegalStateException when the database cannot be reached.
         */
        fun connect(
            url: String,
            maxConnections: Int,
            acquireTimeout: Duration,
            tls: TlsOptions,
        ): SqlExecutor {
            val target = PostgresUrl.parse(url, tls)
            val properties =
                Properties().apply {
                    target.user?.let { setProperty("user", it) }
                    target.password?.let { setProperty("password", it) }
                    target.properties.forEach { (k, v) -> setProperty(k, v) }
                    val seconds = acquireTimeout.inWholeSeconds.coerceAtLeast(1).toString()
                    setProperty("connectTimeout", seconds)
                    setProperty("loginTimeout", seconds)
                    setProperty("ApplicationName", "script-portal")
                }
            val connections =
                JdbcConnections(
                    maxConnections,
                    acquireTimeout,
                ) { DriverManager.getConnection(target.jdbcUrl, properties) }
            return PostgresExecutor(connections)
        }
    }
}

/** A `postgres://` URL in JDBC terms, with the TLS options as driver properties. */
internal data class PostgresUrl(
    val jdbcUrl: String,
    val user: String?,
    val password: String?,
    val properties: Map<String, String>,
) {
    companion object {
        private val SSL_KEYS = setOf("sslmode", "sslrootcert", "sslcert", "sslkey", "sslfactory", "ssl")

        /**
         * TLS options become the driver's own SSL settings. `ca_file` verifies
         * the chain and the host name (`verify-full`); `insecure` encrypts
         * without verification (`require`); `cert_file`/`key_file` are the
         * client identity. TLS settings in the URL are replaced when TLS
         * options are given.
         */
        fun parse(
            url: String,
            tls: TlsOptions,
        ): PostgresUrl {
            val uri = URI(url)
            require(uri.scheme == "postgres" || uri.scheme == "postgresql") { "not a postgres:// URL" }
            require(!(tls.insecure && tls.caFile != null)) { "database.tls: ca_file and insecure cannot be combined" }
            val userInfo = uri.rawUserInfo
            val user = userInfo?.substringBefore(':')?.let(::decode)?.ifEmpty { null }
            val password = userInfo?.takeIf { ':' in it }?.substringAfter(':')?.let(::decode)
            val query =
                uri.rawQuery
                    .orEmpty()
                    .split('&')
                    .filter { it.isNotEmpty() }
                    .map { decode(it.substringBefore('=')) to decode(it.substringAfter('=', "")) }
                    .filter { !tls.isSet || it.first !in SSL_KEYS }
            val properties = linkedMapOf<String, String>()
            if (tls.isSet) {
                properties["sslmode"] = if (tls.insecure) "require" else "verify-full"
                tls.caFile?.let { properties["sslrootcert"] = it.toString() }
                tls.certFile?.let { properties["sslcert"] = it.toString() }
                tls.keyFile?.let { properties["sslkey"] = it.toString() }
            }
            val host = uri.host ?: "localhost"
            val port = if (uri.port > 0) ":${uri.port}" else ""
            val queryString = query.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
            val jdbc =
                "jdbc:postgresql://$host$port${uri.rawPath.orEmpty()}" +
                    if (queryString.isEmpty()) "" else "?$queryString"
            return PostgresUrl(jdbc, user, password, properties)
        }

        private fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8)

        private fun encode(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    }
}

private fun read(
    rs: ResultSet,
    columns: List<String>,
    kinds: List<Kind>,
    maxRows: Int,
) = rs.collect(columns, maxRows) { r -> kinds.mapIndexed { i, kind -> decode(r, i + 1, kind) } }

private fun decode(
    rs: ResultSet,
    index: Int,
    kind: Kind,
): Cell {
    val cell =
        when (kind) {
            Kind.BOOL -> Cell.Bool(rs.getBoolean(index))
            Kind.INT -> Cell.Int(rs.getLong(index))
            Kind.FLOAT -> Cell.Float(rs.getDouble(index))
            Kind.NUMERIC -> rs.getString(index)?.let(::numericCell) ?: Cell.Null
            Kind.TEXT -> rs.getString(index)?.let { Cell.Text(it) } ?: Cell.Null
        }
    return if (rs.wasNull()) Cell.Null else cell
}
