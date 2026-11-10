/*
 * PostgreSQL dialect.
 *
 * Three rules make typed values work over the wire:
 *
 * - Parameters. When the server declared a parameter's type and the type has
 *   a cast here, the placeholder becomes `CAST(? AS type)` and the value is
 *   bound natively. Otherwise the server types the parameter as text and the
 *   value is bound in its text form.
 * - Results. [wrapResult] puts the statement in a subquery and converts each
 *   column: booleans, integers and floats stay native; numerics become text,
 *   parsed back into numbers by [numericCell]; every other type (dates,
 *   timestamps, uuids, json, arrays, bytea, intervals...) becomes its text
 *   form. Columns are renamed by position, so duplicate or unnamed result
 *   columns stay addressable.
 * - Statements. A trailing semicolon is removed, since the wrapper cannot
 *   contain it.
 *
 * Type names are matched case-insensitively (JDBC reports `int4`, others `INT4`).
 */
package io.merkio.sqlportal.sqlexecutor.postgres

import io.merkio.sqlportal.sqlexecutor.Cell
import io.merkio.sqlportal.sqlexecutor.Dialect
import io.merkio.sqlportal.sqlexecutor.Statement
import io.merkio.sqlportal.sqlexecutor.lookup
import io.merkio.sqlportal.sqlexecutor.plainNumber
import io.merkio.sqlportal.sqlexecutor.scanPlaceholders
import io.merkio.sqlportal.sqlexecutor.skipBlockComment
import io.merkio.sqlportal.sqlexecutor.skipLineComment
import io.merkio.sqlportal.sqlexecutor.skipQuoted

/**
 * The PostgreSQL dialect for JDBC, which only knows `?` placeholders. Every
 * occurrence of a `:name` placeholder becomes its own `?` (repeated names bind
 * their value again), and [paramTypes] are the server-declared types per
 * occurrence. A literal `?` outside quotes and comments (jsonb operators) is
 * written `??`, the JDBC escape for a question mark.
 */
object PostgresDialect : Dialect {
    override val name = "postgres"

    override fun prepare(
        sql: String,
        params: List<Pair<String, Cell>>,
        paramTypes: List<String>?,
    ): Statement {
        val body = trimStatement(sql)
        val out = StringBuilder(body.length)
        val bind = mutableListOf<Cell>()
        var last = 0
        for ((i, p) in scanPlaceholders(body).withIndex()) {
            out.append(escapeQuestionMarks(body.substring(last, p.start)))
            val value = lookup(params, p.name)
            val cast = paramTypes?.getOrNull(i)?.let(::castType)
            out.append(if (cast != null) "CAST(? AS $cast)" else "?")
            bind += if (cast != null) value else textForm(value)
            last = p.end
        }
        out.append(escapeQuestionMarks(body.substring(last)))
        return Statement(out.toString(), bind)
    }
}

/** Doubles `?` in code; quoted text and comments are copied as they are. */
internal fun escapeQuestionMarks(segment: String): String {
    if ('?' !in segment) return segment
    val out = StringBuilder(segment.length + 2)
    var i = 0
    while (i < segment.length) {
        val c = segment[i]
        val end =
            when {
                c == '\'' || c == '"' -> skipQuoted(segment, i)
                c == '-' && segment.getOrNull(i + 1) == '-' -> skipLineComment(segment, i)
                c == '/' && segment.getOrNull(i + 1) == '*' -> skipBlockComment(segment, i)
                else -> -1
            }
        if (end >= 0) {
            out.append(segment, i, minOf(end, segment.length))
            i = end
        } else {
            out.append(if (c == '?') "??" else c.toString())
            i++
        }
    }
    return out.toString()
}

/** The statement without surrounding blanks and trailing semicolons. */
internal fun trimStatement(sql: String): String = sql.trim().trimEnd(';').trimEnd()

/** How a result column is converted and decoded. */
internal enum class Kind {
    BOOL,
    INT,
    FLOAT,
    NUMERIC,
    TEXT,
    ;

    /** The expression that converts the column [internal] of this kind, under the result name [output]. */
    internal fun selectExpr(
        internal: String,
        output: String,
    ): String {
        val source = quoteIdent(internal)
        val target = quoteIdent(output)
        return when (this) {
            BOOL -> "$source AS $target"
            INT -> "$source::int8 AS $target"
            FLOAT -> "$source::float8 AS $target"
            NUMERIC, TEXT -> "$source::text AS $target"
        }
    }

    companion object {
        /** Kind of a column from its PostgreSQL type name. */
        fun of(typeName: String): Kind =
            when (typeName.uppercase()) {
                "BOOL" -> BOOL
                "INT2", "INT4", "INT8" -> INT
                "FLOAT4", "FLOAT8" -> FLOAT
                "NUMERIC" -> NUMERIC
                else -> TEXT
            }
    }
}

private val CASTS =
    mapOf(
        "BOOL" to "bool",
        "INT2" to "int2",
        "INT4" to "int4",
        "INT8" to "int8",
        "FLOAT4" to "float4",
        "FLOAT8" to "float8",
        "NUMERIC" to "numeric",
        "DATE" to "date",
        "TIME" to "time",
        "TIMESTAMP" to "timestamp",
        "TIMESTAMPTZ" to "timestamptz",
        "UUID" to "uuid",
        "JSON" to "json",
        "JSONB" to "jsonb",
        "INTERVAL" to "interval",
    )

/** Cast target for a parameter of the given PostgreSQL type, when one is needed. */
internal fun castType(typeName: String): String? = CASTS[typeName.uppercase()]

/** A statement wrapped so that its columns decode. */
internal data class Wrapped(
    val sql: String,
    /** One kind per result column, in order. */
    val kinds: List<Kind>,
)

/**
 * Wraps [sql] so every column in [columns] (name, PostgreSQL type name)
 * arrives in a decodable form. Returns `null` when there are no columns.
 */
internal fun wrapResult(
    sql: String,
    columns: List<Pair<String, String>>,
): Wrapped? {
    if (columns.isEmpty()) return null
    val internal = columns.indices.map { "c$it" }
    val kinds = columns.map { Kind.of(it.second) }
    val selectList = columns.indices.map { kinds[it].selectExpr(internal[it], columns[it].first) }
    return Wrapped(
        "SELECT ${selectList.joinToString(", ")} FROM (\n$sql\n) AS portal_result(${internal.joinToString(", ")})",
        kinds,
    )
}

/**
 * A `NUMERIC` value as a number: integers stay integers. Precision beyond a
 * double is not kept, which is fine for display and charts.
 */
internal fun numericCell(text: String): Cell =
    text.toLongOrNull()?.let { Cell.Int(it) }
        ?: (if (text == "NaN") Double.NaN else text.toDoubleOrNull())?.let { Cell.Float(it) }
        ?: Cell.Text(text)

/** Text form of a value, as the server reads it for an untyped parameter. */
private fun textForm(value: Cell): Cell =
    when (value) {
        Cell.Null -> Cell.Null
        is Cell.Bool -> Cell.Text(value.value.toString())
        is Cell.Int -> Cell.Text(value.value.toString())
        is Cell.Float -> Cell.Text(plainNumber(value.value))
        is Cell.Text -> value
    }

internal fun quoteIdent(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""
