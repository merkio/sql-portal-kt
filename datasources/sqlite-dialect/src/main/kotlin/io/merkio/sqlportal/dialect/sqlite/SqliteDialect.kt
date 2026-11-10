package io.merkio.sqlportal.dialect.sqlite

import io.merkio.sqlportal.sqlexecutor.Cell
import io.merkio.sqlportal.sqlexecutor.Dialect
import io.merkio.sqlportal.sqlexecutor.Statement
import io.merkio.sqlportal.sqlexecutor.lookup
import io.merkio.sqlportal.sqlexecutor.scanPlaceholders

/**
 * SQLite dialect: `:name` placeholders become positional `?` binds, one per
 * occurrence, with the values in the order they appear.
 */
object SqliteDialect : Dialect {
    override val name = "sqlite"

    override fun prepare(
        sql: String,
        params: List<Pair<String, Cell>>,
        paramTypes: List<String>?,
    ): Statement {
        val out = StringBuilder(sql.length)
        val bind = mutableListOf<Cell>()
        var last = 0
        for (p in scanPlaceholders(sql)) {
            out.append(sql, last, p.start).append('?')
            bind += lookup(params, p.name)
            last = p.end
        }
        out.append(sql, last, sql.length)
        return Statement(out.toString(), bind)
    }
}
