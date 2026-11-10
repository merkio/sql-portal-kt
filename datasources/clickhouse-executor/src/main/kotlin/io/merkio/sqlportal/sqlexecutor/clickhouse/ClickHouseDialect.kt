/*
 * ClickHouse dialect.
 *
 * - Parameters are typed server-side: `:name` becomes `{name:Type}` and the
 *   value travels in a `param_name` query argument, so values never appear in
 *   the SQL text. Text values are escaped the way the server reads them.
 * - Results come back as `JSONCompact`. Cells are decoded by the column's
 *   declared type: exact 64-bit integers, padded `FixedString`s, and every
 *   other type as text (dates, uuids, arrays, maps, tuples, enums...).
 * - Setup scripts are split into statements, since the HTTP interface accepts
 *   one statement per request.
 * - Endpoints `clickhouse://` (HTTP, port 8123) and `clickhouses://` (HTTPS,
 *   port 8443) are parsed here.
 */
package io.merkio.sqlportal.sqlexecutor.clickhouse

import io.merkio.sqlportal.sqlexecutor.Cell
import io.merkio.sqlportal.sqlexecutor.Dialect
import io.merkio.sqlportal.sqlexecutor.DialectException
import io.merkio.sqlportal.sqlexecutor.Statement
import io.merkio.sqlportal.sqlexecutor.Table
import io.merkio.sqlportal.sqlexecutor.lookup
import io.merkio.sqlportal.sqlexecutor.plainNumber
import io.merkio.sqlportal.sqlexecutor.scanPlaceholders
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException

object ClickHouseDialect : Dialect {
    override val name = "clickhouse"

    override fun prepare(
        sql: String,
        params: List<Pair<String, Cell>>,
        paramTypes: List<String>?,
    ): Statement {
        val out = StringBuilder(sql.length)
        val queryParams = linkedMapOf<String, String>()
        var last = 0
        for (p in scanPlaceholders(sql)) {
            val value = lookup(params, p.name)
            out
                .append(sql, last, p.start)
                .append('{')
                .append(p.name)
                .append(':')
                .append(serverType(value))
                .append('}')
            queryParams.putIfAbsent("param_${p.name}", paramText(value))
            last = p.end
        }
        out.append(sql, last, sql.length)
        return Statement(out.toString(), queryParams = queryParams.toList())
    }
}

/** The server-side type of a parameter, from the value's kind. */
internal fun serverType(value: Cell): String =
    when (value) {
        Cell.Null -> "Nullable(String)"
        is Cell.Bool -> "Bool"
        is Cell.Int -> "Int64"
        is Cell.Float -> "Float64"
        is Cell.Text -> "String"
    }

/** Text form of a parameter, as the server parses it for its declared type. */
internal fun paramText(value: Cell): String =
    when (value) {
        // TSV-escaped NULL marker.
        Cell.Null -> "\\N"
        is Cell.Bool -> value.value.toString()
        is Cell.Int -> value.value.toString()
        is Cell.Float -> if (value.value.isNaN()) "nan" else plainNumber(value.value)
        is Cell.Text -> escapeText(value.value)
    }

/** Escapes a string the way ClickHouse's escaped text format reads it. */
internal fun escapeText(s: String): String =
    buildString(s.length) {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\u0000' -> append("\\0")
                else -> append(c)
            }
        }
    }

/**
 * Splits a setup script into statements on `;`, ignoring quoted text and
 * comments. Comment-only fragments are dropped.
 */
internal fun splitStatements(sql: String): List<String> {
    val statements = mutableListOf<String>()
    val current = StringBuilder()
    var hasCode = false
    var i = 0
    while (i < sql.length) {
        val c = sql[i]
        when {
            c == '\'' || c == '"' || c == '`' -> {
                hasCode = true
                val end = quotedEnd(sql, i)
                current.append(sql, i, end)
                i = end
                continue
            }
            c == '-' && sql.getOrNull(i + 1) == '-' -> {
                val nl = sql.indexOf('\n', i)
                val end = if (nl < 0) sql.length else nl + 1
                current.append(sql, i, end)
                i = end
                continue
            }
            c == '/' && sql.getOrNull(i + 1) == '*' -> {
                val close = sql.indexOf("*/", i + 2)
                val end = if (close < 0) sql.length else close + 2
                current.append(sql, i, end)
                i = end
                continue
            }
            c == ';' -> {
                if (hasCode) statements += current.toString().trim()
                current.clear()
                hasCode = false
            }
            else -> {
                if (!c.isWhitespace()) hasCode = true
                current.append(c)
            }
        }
        i++
    }
    if (hasCode) statements += current.toString().trim()
    return statements
}

/** Index just past a quoted section; backslash escapes (not in backticks) and doubled quotes. */
private fun quotedEnd(
    sql: String,
    start: Int,
): Int {
    val quote = sql[start]
    var i = start + 1
    while (i < sql.length) {
        val d = sql[i]
        when {
            d == '\\' && quote != '`' -> i += 2
            d == quote && sql.getOrNull(i + 1) == quote -> i += 2
            d == quote -> return i + 1
            else -> i++
        }
    }
    return sql.length
}

/** Parses a `JSONCompact` body. An empty body (no result set) is an empty table. */
internal fun parseResult(body: String): Table {
    if (body.isBlank()) return Table()
    val envelope =
        try {
            Json.parseToJsonElement(body).jsonObject
        } catch (e: SerializationException) {
            throw DialectException("unexpected ClickHouse response: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw DialectException("unexpected ClickHouse response: ${e.message}", e)
        }
    val meta = envelope.array("meta")
    val data = envelope.array("data")
    val columns = meta.map { it.jsonObject.string("name") }
    val types = meta.map { it.jsonObject.string("type") }
    val rows =
        data.map { row ->
            row.jsonArray.mapIndexed { i, cell -> decodeCell(types.getOrElse(i) { "" }, cell) }
        }
    return Table(columns, rows)
}

private fun JsonObject.array(key: String): JsonArray =
    this[key] as? JsonArray ?: throw DialectException("unexpected ClickHouse response: no '$key'")

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.content ?: throw DialectException("unexpected ClickHouse response: no '$key'")

/** Decodes one JSON cell according to its declared ClickHouse type. */
internal fun decodeCell(
    type: String,
    cell: JsonElement,
): Cell {
    val base = type.removeSurrounding("Nullable(", ")")
    val integer = base.startsWith("Int") || base.startsWith("UInt")
    val float = base.startsWith("Float")
    if (cell is JsonNull) return Cell.Null
    if (cell !is JsonPrimitive) return Cell.Text(cell.toString())
    if (!cell.isString) cell.booleanOrNull?.let { return Cell.Bool(it) }
    val text = cell.jsonPrimitive.content
    return when {
        // FixedString pads with NUL bytes.
        cell.isString && base.startsWith("FixedString") -> Cell.Text(text.trimEnd('\u0000'))
        // 64-bit integers are quoted by default; values beyond Long stay exact text.
        integer -> text.toLongOrNull()?.let { Cell.Int(it) } ?: Cell.Text(text)
        float -> floatOf(text)?.let { Cell.Float(it) } ?: Cell.Text(text)
        cell.isString -> Cell.Text(text)
        else ->
            text.toLongOrNull()?.let { Cell.Int(it) }
                ?: text.toDoubleOrNull()?.let { Cell.Float(it) }
                ?: Cell.Text(text)
    }
}

private fun floatOf(text: String): Double? =
    when (text.lowercase()) {
        "nan" -> Double.NaN
        "inf", "+inf" -> Double.POSITIVE_INFINITY
        "-inf" -> Double.NEGATIVE_INFINITY
        else -> text.toDoubleOrNull()
    }

/** A parsed `clickhouse://` or `clickhouses://` URL. */
internal data class Endpoint(
    /** `http://host:port/` or `https://host:port/`. */
    val base: String,
    val database: String,
    val user: String,
    val password: String?,
) {
    val isHttps: Boolean get() = base.startsWith("https://")

    companion object {
        /** Default port of the HTTP interface. */
        const val HTTP_PORT = 8123

        /** Default port of the HTTPS interface. */
        const val HTTPS_PORT = 8443

        /**
         * Parses `clickhouse://[user[:password]@]host[:port][/database]`, or the
         * `clickhouses://` form for HTTPS.
         */
        fun parse(url: String): Endpoint {
            val uri =
                try {
                    URI(url)
                } catch (e: URISyntaxException) {
                    throw DialectException("invalid ClickHouse URL: ${e.message}", e)
                }
            val (scheme, defaultPort) =
                when (uri.scheme) {
                    "clickhouse" -> "http" to HTTP_PORT
                    "clickhouses" -> "https" to HTTPS_PORT
                    else -> throw DialectException("unsupported ClickHouse URL scheme '${uri.scheme}'")
                }
            val host = requireNotNull(uri.host) { "ClickHouse URL needs a host" }
            val port = if (uri.port >= 0) uri.port else defaultPort
            val userInfo = uri.rawUserInfo
            val user = userInfo?.substringBefore(':')?.let(::percentDecode)?.ifEmpty { null }
            val password = userInfo?.takeIf { ':' in it }?.substringAfter(':')?.let(::percentDecode)
            val database =
                uri.rawPath
                    .orEmpty()
                    .trim('/')
                    .let(::percentDecode)
                    .ifEmpty { "default" }
            return Endpoint("$scheme://$host:$port/", database, user ?: "default", password)
        }
    }
}

/** Decodes `%XX` sequences as UTF-8; `+` stays a plus (URL userinfo/path, not a form). */
internal fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val bytes = ByteArrayOutputStream()
    var i = 0
    while (i < s.length) {
        val hex = if (s[i] == '%') s.substring(i + 1, minOf(i + PERCENT_ESCAPE, s.length)).toIntOrNull(HEX) else null
        if (hex != null) {
            bytes.write(hex)
            i += PERCENT_ESCAPE
        } else {
            bytes.write(s[i].toString().toByteArray(Charsets.UTF_8))
            i++
        }
    }
    return bytes.toString(Charsets.UTF_8)
}

private const val PERCENT_ESCAPE = 3
private const val HEX = 16
