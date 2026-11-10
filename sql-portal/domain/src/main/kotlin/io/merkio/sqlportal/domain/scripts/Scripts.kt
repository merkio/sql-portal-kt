package io.merkio.sqlportal.domain.scripts

import io.merkio.sqlportal.domain.access.Permission
import io.merkio.sqlportal.domain.common.DomainException
import io.merkio.sqlportal.domain.common.Value
import io.merkio.sqlportal.sqlexecutor.scanPlaceholders

const val DEFAULT_MAX_ROWS = 1000

/** Rows per table page when a script does not say otherwise. */
const val DEFAULT_PAGE_SIZE = 25

/** Largest page a script may ask for. */
const val MAX_PAGE_SIZE = 500

const val DEFAULT_TIMEOUT_SECS = 30L

private const val MAX_NAME_LENGTH = 64
private val SCRIPT_NAME = Regex("[a-z0-9_-]{1,$MAX_NAME_LENGTH}")

/** URL-safe identifier of a script (`[a-z0-9_-]+`). */
@ConsistentCopyVisibility
data class ScriptName private constructor(
    val value: String,
) : Comparable<ScriptName> {
    override fun compareTo(other: ScriptName) = value.compareTo(other.value)

    override fun toString() = value

    companion object {
        /** @throws DomainException when the name is not 1-64 chars of `[a-z0-9_-]`. */
        fun of(value: String): ScriptName {
            if (!SCRIPT_NAME.matches(value)) {
                throw DomainException("script name '$value' must be 1-64 chars of [a-z0-9_-]")
            }
            return ScriptName(value)
        }
    }
}

/**
 * An aggregate describing one launchable SQL script: its SQL, the form
 * parameters, the permissions required to run it and how to present results.
 * Construct freely, then call [validate]; [of] does both.
 */
data class ScriptDefinition(
    val name: ScriptName,
    val title: String,
    val sql: String,
    val description: String = "",
    /** Section of the script list this script belongs to; `null` = ungrouped. */
    val group: String? = null,
    /** The caller needs **all** of these permissions. Empty = any authenticated user. */
    val requiredPermissions: List<Permission> = emptyList(),
    val params: List<ParamDefinition> = emptyList(),
    val charts: List<ChartDefinition> = emptyList(),
    val showTable: Boolean = true,
    val maxRows: Int = DEFAULT_MAX_ROWS,
    /** Rows per page of the table (1..MAX_PAGE_SIZE). */
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    val timeoutSecs: Long = DEFAULT_TIMEOUT_SECS,
) {
    /** Checks the invariants. @throws DomainException with the first violation. */
    fun validate(): ScriptDefinition {
        fun fail(message: String): Nothing = throw DomainException("script '$name': $message")

        if (sql.isBlank()) fail("sql must not be empty")
        if (title.isBlank()) fail("title must not be empty")
        if (group != null && (group.isBlank() || group.codePointCount(0, group.length) > MAX_NAME_LENGTH)) {
            fail("group must be 1 to 64 characters")
        }
        if (maxRows <= 0) fail("max_rows must be greater than zero")
        if (timeoutSecs <= 0) fail("timeout_secs must be greater than zero")
        if (pageSize !in 1..MAX_PAGE_SIZE) fail("page_size must be between 1 and $MAX_PAGE_SIZE")
        if (!showTable && charts.isEmpty()) fail("a script must show a table or at least one chart")

        val seen = mutableSetOf<String>()
        for (p in params) {
            try {
                p.validate()
            } catch (e: DomainException) {
                throw DomainException("script '$name': ${e.message}", e)
            }
            if (!seen.add(p.name)) fail("duplicate parameter '${p.name}'")
        }
        scanPlaceholders(sql).firstOrNull { it.name !in seen }?.let {
            fail("sql uses ':${it.name}' but no parameter with that name is defined")
        }
        return this
    }

    /**
     * Validates the submitted values and returns typed, named parameters.
     *
     * @throws ScriptException.InvalidParams with every field error.
     */
    fun bindParams(raw: Map<String, String>): List<Pair<String, Value>> {
        val errors = mutableListOf<FieldError>()
        val bound =
            params.mapNotNull { p ->
                try {
                    p.name to p.parse(raw[p.name])
                } catch (e: FieldException) {
                    errors += e.error
                    null
                }
            }
        if (errors.isNotEmpty()) throw ScriptException.InvalidParams(errors)
        return bound
    }

    /** `true` if the script can be executed immediately on page load. */
    val runsWithoutInput: Boolean get() = params.all { it.isSatisfiedWithoutInput }

    companion object {
        /** A validated script with default settings. */
        fun of(
            name: ScriptName,
            title: String,
            sql: String,
        ) = ScriptDefinition(name, title, sql).validate()
    }
}

/** Read access to the configured scripts. */
interface ScriptCatalog {
    fun all(): List<ScriptDefinition>

    fun get(name: String): ScriptDefinition?
}

/** Catalog holding the scripts loaded from the configuration, ordered by name. */
class ConfigScriptCatalog(
    scripts: List<ScriptDefinition>,
) : ScriptCatalog {
    private val scripts = scripts.associateBy { it.name.value }.toSortedMap()

    override fun all() = scripts.values.toList()

    override fun get(name: String) = scripts[name]
}
