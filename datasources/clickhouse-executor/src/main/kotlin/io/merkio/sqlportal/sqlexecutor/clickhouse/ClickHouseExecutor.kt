package io.merkio.sqlportal.sqlexecutor.clickhouse

import io.merkio.sqlportal.common.tls.TlsOptions
import io.merkio.sqlportal.sqlexecutor.DialectException
import io.merkio.sqlportal.sqlexecutor.SqlException
import io.merkio.sqlportal.sqlexecutor.SqlExecutor
import io.merkio.sqlportal.sqlexecutor.SqlQuery
import io.merkio.sqlportal.sqlexecutor.SqlResult
import io.merkio.sqlportal.sqlexecutor.limit
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Runs SQL against a ClickHouse server over its HTTP interface. The SQL rules
 * (typed parameters, result parsing, statement splitting, endpoint URLs) live
 * in [ClickHouseDialect]; this class does the I/O and server settings. One
 * HTTP client, and so its keep-alive connections, serves every request.
 */
class ClickHouseExecutor private constructor(
    private val endpoint: Endpoint,
    private val client: HttpClient,
) : SqlExecutor {
    private val authorization =
        "Basic " +
            Base64.getEncoder().encodeToString("${endpoint.user}:${endpoint.password.orEmpty()}".toByteArray())

    /** Sends one statement and returns the response body. */
    private suspend fun send(
        sql: String,
        query: List<Pair<String, String>>,
        timeout: Duration = SCRIPT_TIMEOUT,
    ): String {
        val parameters =
            (query + ("database" to endpoint.database))
                .joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        val request =
            HttpRequest
                .newBuilder(URI(endpoint.base + "?" + parameters))
                .timeout(timeout.toJavaDuration())
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofString(sql))
                .build()
        val response =
            try {
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            } catch (e: HttpTimeoutException) {
                throw SqlException.Timeout(e)
            } catch (e: IOException) {
                throw SqlException.Unavailable(e.message ?: e.toString(), e)
            }
        val text = response.body()
        val status = response.statusCode()
        return when {
            status in SUCCESS -> text
            // ClickHouse reports query errors as HTTP 500 with the message as body.
            status >= SERVER_ERROR && status != SERVER_ERROR -> throw SqlException.Unavailable(text.trim())
            else -> throw SqlException.Failed(text.trim())
        }
    }

    override suspend fun query(query: SqlQuery): SqlResult =
        withTimeoutOrNull(query.timeout) { fetch(query) } ?: throw SqlException.Timeout()

    private suspend fun fetch(query: SqlQuery): SqlResult {
        val statement =
            try {
                ClickHouseDialect.prepare(query.sql, query.params)
            } catch (e: DialectException) {
                throw SqlException.Failed(e.message.orEmpty(), e)
            }
        val limit = (query.maxRows + 1).toString()
        val settings =
            listOf(
                "default_format" to "JSONCompact",
                // Read-only at the server, but settings may still be passed.
                "readonly" to "2",
                "max_execution_time" to
                    query.timeout.inWholeSeconds
                        .coerceAtLeast(1)
                        .toString(),
                // One extra row tells us whether the result was cut short. Blocks
                // are sized to match so the server does not stream a full block.
                "max_result_rows" to limit,
                "max_block_size" to limit,
                "result_overflow_mode" to "break",
            )
        val body = send(statement.sql, settings + statement.queryParams, query.timeout)
        val table =
            try {
                parseResult(body)
            } catch (e: DialectException) {
                throw SqlException.Failed(e.message.orEmpty(), e)
            }
        return table.limit(query.maxRows)
    }

    override suspend fun ping() {
        query(SqlQuery("SELECT 1", maxRows = 1, timeout = 5.seconds))
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

        /** Setup scripts may run long; queries carry their own timeout. */
        private val SCRIPT_TIMEOUT = 60.minutes

        /**
         * Connects to a `clickhouse://` or `clickhouses://` URL; no request is
         * made yet. Redirects are never followed, as they could send the
         * credentials to another host.
         *
         * @throws IllegalArgumentException for a malformed URL or TLS on plain HTTP.
         */
        fun connect(
            url: String,
            connectTimeout: Duration,
            tls: TlsOptions,
        ): SqlExecutor {
            val endpoint =
                try {
                    Endpoint.parse(url)
                } catch (e: DialectException) {
                    throw IllegalArgumentException(e.message, e)
                }
            require(!tls.isSet || endpoint.isHttps) { "database.tls needs a clickhouses:// URL" }
            val client =
                HttpClient
                    .newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(connectTimeout.toJavaDuration())
                    .apply { if (tls.isSet) sslContext(tls.sslContext()) }
                    .build()
            return ClickHouseExecutor(endpoint, client)
        }

        private fun encode(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    }
}
