package io.merkio.sqlportal.adapters.sql

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.basicAuth
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.merkio.sqlportal.adapters.http.httpClient
import io.merkio.sqlportal.common.tls.TlsOptions
import io.merkio.sqlportal.dialect.clickhouse.ClickHouseDialect
import io.merkio.sqlportal.dialect.clickhouse.Endpoint
import io.merkio.sqlportal.dialect.clickhouse.parseResult
import io.merkio.sqlportal.dialect.clickhouse.splitStatements
import io.merkio.sqlportal.domain.query.QueryException
import io.merkio.sqlportal.domain.query.QueryExecutor
import io.merkio.sqlportal.domain.query.QueryRequest
import io.merkio.sqlportal.domain.query.QueryResult
import io.merkio.sqlportal.sqlexecutor.DialectException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ConnectException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Runs script SQL against a ClickHouse server over its HTTP interface. The SQL
 * rules (typed parameters, result parsing, statement splitting, endpoint URLs)
 * live in the clickhouse dialect; this class does the I/O and server settings.
 */
class ClickHouseExecutor private constructor(
    private val endpoint: Endpoint,
    private val client: HttpClient,
) : QueryExecutor,
    AutoCloseable {
    /** Sends one statement and returns the response body. */
    private suspend fun send(
        sql: String,
        query: List<Pair<String, String>>,
    ): String {
        val response =
            try {
                client.post(endpoint.base) {
                    basicAuth(endpoint.user, endpoint.password.orEmpty())
                    query.forEach { (k, v) -> parameter(k, v) }
                    parameter("database", endpoint.database)
                    setBody(sql)
                }
            } catch (e: HttpRequestTimeoutException) {
                throw QueryException.Timeout(e)
            } catch (e: ConnectException) {
                throw QueryException.Unavailable(e.message ?: "cannot connect", e)
            } catch (e: IOException) {
                throw QueryException.Unavailable(e.message ?: e.toString(), e)
            }
        val text = response.bodyAsText()
        val status = response.status.value
        return when {
            status in SUCCESS -> text
            // ClickHouse reports query errors as HTTP 500 with the message as body.
            status >= SERVER_ERROR && status != SERVER_ERROR -> throw QueryException.Unavailable(text.trim())
            else -> throw QueryException.Database(text.trim())
        }
    }

    override suspend fun execute(request: QueryRequest): QueryResult =
        withTimeoutOrNull(request.timeout) { fetch(request) } ?: throw QueryException.Timeout()

    private suspend fun fetch(request: QueryRequest): QueryResult {
        val statement =
            try {
                ClickHouseDialect.prepare(request.sql, cellsOf(request.params))
            } catch (e: DialectException) {
                throw QueryException.Database(e.message.orEmpty(), e)
            }
        val limit = (request.maxRows + 1).toString()
        val settings =
            listOf(
                "default_format" to "JSONCompact",
                // Read-only at the server, but settings may still be passed.
                "readonly" to "2",
                "max_execution_time" to
                    request.timeout.inWholeSeconds
                        .coerceAtLeast(1)
                        .toString(),
                // One extra row tells us whether the result was cut short. Blocks
                // are sized to match so the server does not stream a full block.
                "max_result_rows" to limit,
                "max_block_size" to limit,
                "result_overflow_mode" to "break",
            )
        val body = send(statement.sql, settings + statement.queryParams)
        val table =
            try {
                parseResult(body)
            } catch (e: DialectException) {
                throw QueryException.Database(e.message.orEmpty(), e)
            }
        return tableResult(table, request.maxRows)
    }

    override suspend fun ping() {
        execute(QueryRequest("SELECT 1", maxRows = 1, timeout = 5.seconds))
    }

    override suspend fun runScript(sql: String) {
        // The HTTP interface accepts one statement per request. Setup scripts
        // are not read-only, so they are sent without the readonly setting.
        for (statement in splitStatements(sql)) send(statement, emptyList())
    }

    override fun close() = client.close()

    companion object {
        private val SUCCESS = 200..299
        private const val SERVER_ERROR = 500

        /** Connects to a `clickhouse://` or `clickhouses://` URL; no request is made yet. */
        fun connect(
            url: String,
            acquireTimeout: Duration,
            tls: TlsOptions,
        ): ClickHouseExecutor {
            val endpoint = Endpoint.parse(url)
            require(!tls.isSet || endpoint.isHttps) { "database.tls needs a clickhouses:// URL" }
            // Each request carries its own timeout; the client limit is only a backstop.
            return ClickHouseExecutor(
                endpoint,
                httpClient(tls, requestTimeout = 60.minutes, connectTimeout = acquireTimeout),
            )
        }
    }
}
