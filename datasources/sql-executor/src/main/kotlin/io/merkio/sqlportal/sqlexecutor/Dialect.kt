/*
 * Database-independent contract for SQL dialects.
 *
 * A dialect turns a script written with `:name` placeholders into the syntax
 * of one database and decides how values are bound. Each implementation lives
 * in the `*-executor` module of its database, next to the executor, and does
 * no I/O, so it can be tested without a server.
 *
 * To support another database: add a `*-executor` module with a [Dialect] and
 * a [SqlExecutor] that runs the [Statement]s it produces.
 */
package io.merkio.sqlportal.sqlexecutor

/** A value a dialect binds or a driver returns, independent of the driver. */
sealed interface Cell {
    data object Null : Cell

    data class Bool(
        val value: Boolean,
    ) : Cell

    data class Int(
        val value: Long,
    ) : Cell

    data class Float(
        val value: Double,
    ) : Cell

    data class Text(
        val value: String,
    ) : Cell
}

/**
 * A problem found while preparing a statement, e.g. a placeholder without a
 * value. Drivers report these as query errors.
 */
class DialectException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** A statement ready for the driver. */
data class Statement(
    /** SQL in the dialect's syntax. */
    val sql: String,
    /** Values for the driver's positional binds, in bind order. */
    val bind: List<Cell> = emptyList(),
    /** Parameters the server substitutes itself (ClickHouse `param_*`). */
    val queryParams: List<Pair<String, String>> = emptyList(),
)

/** A rectangular result in neutral cells. */
data class Table(
    val columns: List<String> = emptyList(),
    val rows: List<List<Cell>> = emptyList(),
)

/** SQL dialect of one database. */
interface Dialect {
    /** Short stable name, e.g. `postgres`. */
    val name: String

    /**
     * Rewrites `:name` placeholders for the database and prepares the bound
     * values. [params] holds the value of each named parameter. [paramTypes]
     * are the server-declared parameter types in placeholder order, when the
     * driver can report them.
     *
     * @throws DialectException when a placeholder has no value.
     */
    fun prepare(
        sql: String,
        params: List<Pair<String, Cell>>,
        paramTypes: List<String>? = null,
    ): Statement
}

/** Finds the value of a named parameter. */
fun lookup(
    params: List<Pair<String, Cell>>,
    name: String,
): Cell =
    params.firstOrNull { it.first == name }?.second
        ?: throw DialectException("missing value for ':$name'")

/**
 * Shortest decimal text of a double without exponent, `2` for 2.0: the form
 * servers parse for numeric parameters.
 */
fun plainNumber(value: Double): String =
    when {
        value.isNaN() -> "NaN"
        value.isInfinite() -> if (value > 0) "inf" else "-inf"
        value == Math.rint(value) && kotlin.math.abs(value) < EXACT_WHOLE_LIMIT -> value.toLong().toString()
        else ->
            java.math
                .BigDecimal(value.toString())
                .stripTrailingZeros()
                .toPlainString()
    }

/** Whole doubles below this magnitude print without a fraction. */
private const val EXACT_WHOLE_LIMIT = 1e15
