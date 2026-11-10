package io.merkio.sqlportal.domain.common

import io.merkio.sqlportal.sqlexecutor.plainNumber

/** A scalar value flowing in and out of SQL scripts. */
sealed interface Value {
    data object Null : Value

    data class Bool(
        val value: Boolean,
    ) : Value

    data class Int(
        val value: Long,
    ) : Value

    data class Float(
        val value: Double,
    ) : Value

    data class Text(
        val value: String,
    ) : Value

    val isNull: Boolean get() = this == Null

    /** Numeric view of the value, used by charts. */
    fun asDouble(): Double? =
        when (this) {
            is Int -> value.toDouble()
            is Float -> value.takeIf { it.isFinite() }
            is Bool -> if (value) 1.0 else 0.0
            is Text -> parseNumber(value.trim())?.takeIf { it.isFinite() }
            Null -> null
        }

    /** Human readable representation (NULL renders as an empty string). */
    fun display(): String =
        when (this) {
            Null -> ""
            is Bool -> value.toString()
            is Int -> value.toString()
            is Float ->
                if (value.isFinite() && value == Math.rint(value) && kotlin.math.abs(value) < WHOLE_LIMIT) {
                    "${value.toLong()}.0"
                } else {
                    plainNumber(value)
                }
            is Text -> value
        }

    private companion object {
        const val WHOLE_LIMIT = 1e15
    }
}

private val DECIMAL = Regex("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")

/**
 * Parses a decimal number. Unlike [String.toDoubleOrNull] it rejects Java
 * specifics such as `1.5d` or hexadecimal floats; `inf` and `NaN` are read so
 * that callers can reject them as non-finite.
 */
fun parseNumber(text: String): Double? =
    when (text.lowercase()) {
        "nan", "+nan", "-nan" -> Double.NaN
        "inf", "+inf", "infinity", "+infinity" -> Double.POSITIVE_INFINITY
        "-inf", "-infinity" -> Double.NEGATIVE_INFINITY
        else -> if (DECIMAL.matches(text)) text.toDouble() else null
    }

/** Violations of domain invariants (invalid configuration, malformed names...). */
class DomainException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
