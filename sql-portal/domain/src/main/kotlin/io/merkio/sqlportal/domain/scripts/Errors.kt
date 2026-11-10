package io.merkio.sqlportal.domain.scripts

import io.merkio.sqlportal.domain.query.QueryException

/** A validation problem tied to one form field. */
data class FieldError(
    val param: String,
    val message: String,
)

/** Why a script could not be used or run. */
sealed class ScriptException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** Short, low-cardinality label used for metrics. */
    abstract val outcome: String

    class NotFound : ScriptException("script not found") {
        override val outcome = "not_found"
    }

    class Forbidden : ScriptException("permission denied") {
        override val outcome = "forbidden"
    }

    class InvalidParams(
        val errors: List<FieldError>,
    ) : ScriptException("invalid parameters") {
        override val outcome = "invalid_params"
    }

    class Query(
        val error: QueryException,
    ) : ScriptException(error.message ?: "query failed", error) {
        override val outcome = if (error is QueryException.Timeout) "timeout" else "error"
    }
}
