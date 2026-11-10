package io.merkio.sqlportal.adapters.sql

import com.zaxxer.hikari.HikariConfig
import io.merkio.sqlportal.common.tls.TlsOptions
import io.merkio.sqlportal.dialect.postgres.Kind
import io.merkio.sqlportal.dialect.postgres.PostgresJdbcDialect
import io.merkio.sqlportal.dialect.postgres.numericCell
import io.merkio.sqlportal.dialect.postgres.wrapResult
import io.merkio.sqlportal.domain.common.Value
import io.merkio.sqlportal.domain.query.QueryRequest
import io.merkio.sqlportal.domain.query.QueryResult
import io.merkio.sqlportal.sqlexecutor.Cell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * PostgreSQL adapter. The SQL rules live in the postgres dialect; this class
 * does the I/O: describe the statement, bind the prepared values, decode rows.
 */
class PostgresExecutor private constructor(
    config: HikariConfig,
) : JdbcExecutor(config) {
    override fun fetch(
        connection: Connection,
        request: QueryRequest,
        running: AtomicReference<PreparedStatement?>,
    ): QueryResult {
        val params = cellsOf(request.params)

        // 1. Describe the statement with untyped parameters.
        val plain = PostgresJdbcDialect.prepare(request.sql, params)
        val (paramTypes, columns) =
            connection.prepareStatement(plain.sql).use { ps ->
                running.set(ps)
                val types = ps.parameterMetaData.let { m -> (1..m.parameterCount).map { m.getParameterTypeName(it) } }
                val cols =
                    ps.metaData?.let { m -> (1..m.columnCount).map { m.getColumnLabel(it) to m.getColumnTypeName(it) } }
                types to cols.orEmpty()
            }

        // 2. Prepare again with the declared parameter types (casts), 3. wrap so every column decodes.
        val typed = PostgresJdbcDialect.prepare(request.sql, params, paramTypes)
        val wrapped = wrapResult(typed.sql, columns)
        val sql = wrapped?.sql ?: typed.sql
        val kinds = wrapped?.kinds.orEmpty()
        return connection.prepareStatement(sql).use { ps ->
            running.set(ps)
            ps.limit(request)
            ps.bind(typed.bind)
            if (kinds.isEmpty()) {
                ps.execute()
                QueryResult()
            } else {
                ps.executeQuery().use { rs -> read(rs, columns.map { it.first }, kinds, request.maxRows) }
            }
        }
    }

    override suspend fun runScript(sql: String) {
        withContext(Dispatchers.IO) {
            translate {
                // The simple query protocol accepts several statements.
                pool.connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
            }
        }
    }

    companion object {
        /** Connects to a `postgres://` or `postgresql://` URL. */
        fun connect(
            url: String,
            maxConnections: Int,
            acquireTimeout: Duration,
            tls: TlsOptions,
        ): PostgresExecutor {
            val target = PostgresUrl.parse(url, tls)
            val config =
                HikariConfig().apply {
                    jdbcUrl = target.jdbcUrl
                    target.user?.let { username = it }
                    target.password?.let { password = it }
                    target.properties.forEach { (k, v) -> addDataSourceProperty(k, v) }
                    maximumPoolSize = maxConnections
                    connectionTimeout = acquireTimeout.inWholeMilliseconds
                    poolName = "postgres"
                }
            return PostgresExecutor(config)
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
         * `ca_file` verifies the chain and the host name; `insecure` encrypts
         * without verification. TLS settings in the URL are replaced when TLS
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
                properties["sslfactory"] = PortalSslFactory::class.java.name
                properties.putAll(PortalSslFactory.properties(tls))
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
): QueryResult {
    val rows = mutableListOf<List<Value>>()
    var truncated = false
    while (rs.next()) {
        if (rows.size >= maxRows) {
            truncated = true
            break
        }
        rows += kinds.mapIndexed { i, kind -> decode(rs, i + 1, kind) }
    }
    return QueryResult(columns, rows, truncated)
}

private fun decode(
    rs: ResultSet,
    index: Int,
    kind: Kind,
): Value {
    val cell =
        when (kind) {
            Kind.BOOL -> Cell.Bool(rs.getBoolean(index))
            Kind.INT -> Cell.Int(rs.getLong(index))
            Kind.FLOAT -> Cell.Float(rs.getDouble(index))
            Kind.NUMERIC -> rs.getString(index)?.let(::numericCell) ?: Cell.Null
            Kind.TEXT -> rs.getString(index)?.let { Cell.Text(it) } ?: Cell.Null
        }
    return if (rs.wasNull()) Value.Null else valueOf(cell)
}
