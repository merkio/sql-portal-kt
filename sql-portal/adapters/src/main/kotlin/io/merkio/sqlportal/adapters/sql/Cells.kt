package io.merkio.sqlportal.adapters.sql

import io.merkio.sqlportal.domain.common.Value
import io.merkio.sqlportal.domain.query.QueryResult
import io.merkio.sqlportal.sqlexecutor.Cell
import io.merkio.sqlportal.sqlexecutor.Table

/** Domain value to the dialect's neutral cell. */
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

/** Named parameters as neutral cells, for a dialect's `prepare`. */
internal fun cellsOf(params: List<Pair<String, Value>>) = params.map { (name, value) -> name to cellOf(value) }

/** A dialect table as a query result, truncated to [maxRows]. */
internal fun tableResult(
    table: Table,
    maxRows: Int,
) = QueryResult(
    table.columns,
    table.rows.take(maxRows).map { row -> row.map(::valueOf) },
    table.rows.size > maxRows,
)
