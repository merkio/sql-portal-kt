package io.merkio.sqlportal.domain.scripts

import io.merkio.sqlportal.sqlexecutor.codeOnly

private val WRITE_KEYWORDS =
    setOf(
        "insert",
        "update",
        "delete",
        "drop",
        "alter",
        "create",
        "truncate",
        "grant",
        "revoke",
        "merge",
        "replace",
        "attach",
        "detach",
        "pragma",
        "vacuum",
        "copy",
        "call",
        "exec",
    )

private val WORD_SEPARATOR = Regex("[^A-Za-z0-9_]+")

/**
 * Conservative check that a script is a single read-only `SELECT`/`WITH`
 * statement. It is a safety net, not a substitute for a read-only database
 * account.
 */
fun isReadOnlySelect(sql: String): Boolean {
    val statements = codeOnly(sql).split(';').map { it.trim() }.filter { it.isNotEmpty() }
    if (statements.size != 1) return false
    val words =
        statements
            .single()
            .lowercase()
            .split(WORD_SEPARATOR)
            .filter { it.isNotEmpty() }
    return words.firstOrNull() in setOf("select", "with") && words.none { it in WRITE_KEYWORDS }
}
