package io.merkio.sqlportal.sqlexecutor

/**
 * A `:name` placeholder found in a SQL text. `start until end` is the range of
 * string indices including the leading colon.
 */
data class Placeholder(
    val name: String,
    val start: Int,
    val end: Int,
)

private fun isIdentStart(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c == '_'

private fun isIdentChar(c: Char) = isIdentStart(c) || c in '0'..'9'

/**
 * Finds `:name` placeholders. They are only recognised outside string
 * literals, quoted identifiers and comments, and `::` (Postgres casts) is
 * never a placeholder.
 */
fun scanPlaceholders(sql: String): List<Placeholder> {
    val found = mutableListOf<Placeholder>()
    var i = 0
    while (i < sql.length) {
        val c = sql[i]
        when {
            c == '\'' || c == '"' || c == '`' -> i = skipQuoted(sql, i)
            c == '-' && sql.getOrNull(i + 1) == '-' -> i = skipLineComment(sql, i)
            c == '/' && sql.getOrNull(i + 1) == '*' -> i = skipBlockComment(sql, i)
            c == ':' -> {
                val prev = sql.getOrNull(i - 1)
                val next = sql.getOrNull(i + 1)
                if (next == ':') {
                    // Postgres cast `::type`
                    i += 2
                    continue
                }
                val prevBlocks = prev != null && (isIdentChar(prev) || prev == ':')
                if (!prevBlocks && next != null && isIdentStart(next)) {
                    var j = i + 1
                    while (j < sql.length && isIdentChar(sql[j])) j++
                    found += Placeholder(sql.substring(i + 1, j), i, j)
                    i = j
                } else {
                    i++
                }
            }
            else -> i++
        }
    }
    return found
}

/** Index just past a quoted literal or identifier; a doubled quote is an escape. */
fun skipQuoted(
    sql: String,
    start: Int,
): Int {
    val quote = sql[start]
    var i = start + 1
    while (i < sql.length) {
        if (sql[i] == quote) {
            if (sql.getOrNull(i + 1) == quote) {
                i += 2
                continue
            }
            break
        }
        i++
    }
    return i + 1
}

/** Index of the newline ending a `--` comment (or the end of the text). */
fun skipLineComment(
    sql: String,
    start: Int,
): Int {
    var i = start
    while (i < sql.length && sql[i] != '\n') i++
    return i
}

/** Index just past the end of a block comment. */
fun skipBlockComment(
    sql: String,
    start: Int,
): Int {
    var i = start + 2
    while (i + 1 < sql.length && !(sql[i] == '*' && sql[i + 1] == '/')) i++
    return i + 2
}

/**
 * The SQL text with string literals emptied and comments replaced by a space,
 * so keyword checks only see code.
 */
fun codeOnly(sql: String): String {
    val out = StringBuilder(sql.length)
    var i = 0
    while (i < sql.length) {
        val c = sql[i]
        when {
            c == '\'' || c == '"' || c == '`' -> {
                i = skipQuoted(sql, i)
                out.append(c).append(c)
            }
            c == '-' && sql.getOrNull(i + 1) == '-' -> {
                i = skipLineComment(sql, i)
                out.append(' ')
            }
            c == '/' && sql.getOrNull(i + 1) == '*' -> {
                i = skipBlockComment(sql, i)
                out.append(' ')
            }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}
