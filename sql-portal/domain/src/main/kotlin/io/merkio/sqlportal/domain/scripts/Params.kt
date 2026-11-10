package io.merkio.sqlportal.domain.scripts

import io.merkio.sqlportal.domain.common.DomainException
import io.merkio.sqlportal.domain.common.Value
import io.merkio.sqlportal.domain.common.parseNumber
import io.merkio.sqlportal.sqlexecutor.plainNumber
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.regex.PatternSyntaxException

enum class ParamKind(
    val label: String,
) {
    TEXT("text"),
    INTEGER("integer"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    DATE("date"),
    SELECT("select"),
}

data class SelectOption(
    val value: String,
    val label: String,
)

/** The submitted value of one field is not acceptable. */
class FieldException(
    val error: FieldError,
    cause: Throwable? = null,
) : RuntimeException(error.message, cause)

const val DEFAULT_MAX_LENGTH = 255

private val PARAM_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val TRUE_WORDS = setOf("true", "1", "on", "yes")
private val FALSE_WORDS = setOf("false", "0", "off", "no", "")

/** Whether a checkbox-like value means "on". */
fun isTruthy(raw: String) = raw.trim().lowercase() in TRUE_WORDS

/**
 * Definition of one script parameter; it drives both the HTML form and the
 * validation and binding of submitted values.
 */
data class ParamDefinition(
    val name: String,
    val kind: ParamKind,
    val label: String = name,
    val required: Boolean = false,
    val default: String? = null,
    val help: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val maxLength: Int = DEFAULT_MAX_LENGTH,
    val pattern: String? = null,
    val options: List<SelectOption> = emptyList(),
) {
    /** Checks the definition itself (called while building a script). */
    fun validate() {
        fun invalid(
            message: String,
            cause: Throwable? = null,
        ): Nothing = throw DomainException(message, cause)

        if (!PARAM_NAME.matches(name)) invalid("parameter name '$name' must match [A-Za-z_][A-Za-z0-9_]*")
        if (kind == ParamKind.SELECT && options.isEmpty()) invalid("select parameter '$name' needs at least one option")
        if (min != null && max != null && min > max) invalid("parameter '$name' has min greater than max")
        if (pattern != null) {
            try {
                Regex(pattern)
            } catch (e: PatternSyntaxException) {
                invalid("parameter '$name' has an invalid pattern: ${e.description}", e)
            }
        }
        // The default must itself be valid.
        if (default != null) {
            try {
                parse(null)
            } catch (e: FieldException) {
                invalid("default of parameter '$name' is invalid: ${e.error.message}", e)
            }
        }
    }

    /** Whether the form can be submitted without the user typing anything. */
    val isSatisfiedWithoutInput: Boolean get() = !required || default != null || kind == ParamKind.BOOLEAN

    /**
     * Validates a submitted raw value and converts it into a typed [Value].
     *
     * @throws FieldException when the value is not acceptable.
     */
    fun parse(raw: String?): Value {
        if (kind == ParamKind.BOOLEAN) {
            val effective = (raw?.trim() ?: default ?: "").lowercase()
            return when (effective) {
                in TRUE_WORDS -> Value.Bool(true)
                in FALSE_WORDS -> Value.Bool(false)
                else -> fail("'$effective' is not a valid boolean")
            }
        }
        val text =
            raw?.trim()?.takeIf { it.isNotEmpty() } ?: default
                ?: return if (required) fail("this field is required") else Value.Null
        return when (kind) {
            ParamKind.TEXT -> parseText(text)
            ParamKind.INTEGER -> {
                val n = text.toLongOrNull() ?: fail("'$text' is not an integer")
                checkRange(n.toDouble())
                Value.Int(n)
            }
            ParamKind.NUMBER -> {
                val n = parseNumber(text) ?: fail("'$text' is not a number")
                if (!n.isFinite()) fail("must be a finite number")
                checkRange(n)
                Value.Float(n)
            }
            ParamKind.DATE ->
                try {
                    Value.Text(LocalDate.parse(text).toString())
                } catch (e: DateTimeParseException) {
                    fail("'$text' is not a date (YYYY-MM-DD)", e)
                }
            ParamKind.SELECT ->
                if (options.any { it.value == text }) Value.Text(text) else fail("'$text' is not an allowed option")
            ParamKind.BOOLEAN -> error("handled above")
        }
    }

    private fun parseText(text: String): Value {
        if (text.codePointCount(0, text.length) > maxLength) fail("must be at most $maxLength characters")
        if (pattern != null && !Regex(pattern).containsMatchIn(text)) fail("does not match the expected format")
        return Value.Text(text)
    }

    private fun checkRange(n: Double) {
        if (min != null && n < min) fail("must be at least ${plainNumber(min)}")
        if (max != null && n > max) fail("must be at most ${plainNumber(max)}")
    }

    private fun fail(
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw FieldException(FieldError(name, message), cause)
}
