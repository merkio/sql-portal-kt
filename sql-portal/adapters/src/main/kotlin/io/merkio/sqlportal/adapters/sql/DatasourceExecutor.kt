package io.merkio.sqlportal.adapters.sql

import io.merkio.sqlportal.domain.common.Value
import io.merkio.sqlportal.domain.query.QueryException
import io.merkio.sqlportal.domain.query.QueryExecutor
import io.merkio.sqlportal.domain.query.QueryRequest
import io.merkio.sqlportal.domain.query.QueryResult
import io.merkio.sqlportal.sqlexecutor.Cell
import io.merkio.sqlportal.sqlexecutor.SqlException
import io.merkio.sqlportal.sqlexecutor.SqlExecutor
import io.merkio.sqlportal.sqlexecutor.SqlQuery

/**
 * The domain's [QueryExecutor] over a datasource [SqlExecutor]: domain values
 * become neutral cells and back, datasource failures become [QueryException]s.
 */
internal class DatasourceExecutor(
    private val datasource: SqlExecutor,
) : QueryExecutor,
    AutoCloseable {
    override suspend fun execute(request: QueryRequest): QueryResult {
        val query =
            SqlQuery(
                sql = request.sql,
                params = request.params.map { (name, value) -> name to cellOf(value) },
                maxRows = request.maxRows,
                timeout = request.timeout,
            )
        val result = translate { datasource.query(query) }
        return QueryResult(result.columns, result.rows.map { row -> row.map(::valueOf) }, result.truncated)
    }

    override suspend fun ping() = translate { datasource.ping() }

    override suspend fun runScript(sql: String) = translate { datasource.runScript(sql) }

    override fun close() = datasource.close()

    private inline fun <T> translate(block: () -> T): T =
        try {
            block()
        } catch (e: SqlException.Timeout) {
            throw QueryException.Timeout(e)
        } catch (e: SqlException.Unavailable) {
            throw QueryException.Unavailable(e.detail, e)
        } catch (e: SqlException.Failed) {
            throw QueryException.Database(e.detail, e)
        }
}

/** Domain value to the datasource's neutral cell. */
internal fun cellOf(value: Value): Cell =
    when (value) {
        Value.Null -> Cell.Null
        is Value.Bool -> Cell.Bool(value.value)
        is Value.Int -> Cell.Int(value.value)
        is Value.Float -> Cell.Float(value.value)
        is Value.Text -> Cell.Text(value.value)
    }

/** Neutral cell back to the domain value. */
internal fun valueOf(cell: Cell): Value =
    when (cell) {
        Cell.Null -> Value.Null
        is Cell.Bool -> Value.Bool(cell.value)
        is Cell.Int -> Value.Int(cell.value)
        is Cell.Float -> Value.Float(cell.value)
        is Cell.Text -> Value.Text(cell.value)
    }
