// `scripts` section → script definitions, and the ScriptService with its catalog and access rule.
package io.merkio.sqlportal.deployment.scripts

import io.github.oshai.kotlinlogging.KotlinLogging
import io.merkio.sqlportal.application.RequiredPermissions
import io.merkio.sqlportal.application.ScriptService
import io.merkio.sqlportal.deployment.config.ConfigError
import io.merkio.sqlportal.domain.access.Permission
import io.merkio.sqlportal.domain.access.ScriptAccessRule
import io.merkio.sqlportal.domain.common.DomainException
import io.merkio.sqlportal.domain.scripts.ChartDefinition
import io.merkio.sqlportal.domain.scripts.ChartKind
import io.merkio.sqlportal.domain.scripts.ConfigScriptCatalog
import io.merkio.sqlportal.domain.scripts.DEFAULT_MAX_LENGTH
import io.merkio.sqlportal.domain.scripts.DEFAULT_MAX_ROWS
import io.merkio.sqlportal.domain.scripts.DEFAULT_PAGE_SIZE
import io.merkio.sqlportal.domain.scripts.DEFAULT_TIMEOUT_SECS
import io.merkio.sqlportal.domain.scripts.ParamDefinition
import io.merkio.sqlportal.domain.scripts.ParamKind
import io.merkio.sqlportal.domain.scripts.ScriptCatalog
import io.merkio.sqlportal.domain.scripts.ScriptDefinition
import io.merkio.sqlportal.domain.scripts.ScriptName
import io.merkio.sqlportal.domain.scripts.SelectOption
import io.merkio.sqlportal.domain.scripts.isReadOnlySelect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.dsl.module

private val log = KotlinLogging.logger {}

/** One entry of `scripts`. */
@Serializable
data class ScriptConfig(
    val name: String,
    val title: String,
    val description: String = "",
    /** Section of the script list. Scripts without a group are listed last. */
    val group: String? = null,
    /** Inline SQL. Exactly one of `sql` / `sql_file` is required. Never expanded from the environment. */
    val sql: String? = null,
    /** SQL file, relative to the configuration. */
    @SerialName("sql_file") val sqlFile: String? = null,
    /** Permissions the caller needs (all of them). */
    val permissions: List<String> = emptyList(),
    val params: List<ParamConfig> = emptyList(),
    val charts: List<ChartConfig> = emptyList(),
    val table: Boolean = true,
    @SerialName("max_rows") val maxRows: Int? = null,
    /** Rows per page of the table; charts always use `max_rows`. */
    @SerialName("page_size") val pageSize: Int? = null,
    @SerialName("timeout_secs") val timeoutSecs: Long? = null,
)

/** A form parameter. */
@Serializable
data class ParamConfig(
    val name: String,
    val label: String? = null,
    val type: String,
    val required: Boolean = false,
    /** Any scalar, kept as text (`default = 5` and `default = "5"` are the same). */
    val default: String? = null,
    val help: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    @SerialName("max_length") val maxLength: Int? = null,
    val pattern: String? = null,
    /** Each option is written as a bare scalar or as `{ value, label }`. */
    val options: List<OptionConfig> = emptyList(),
)

@Serializable
data class OptionConfig(
    val value: String = "",
    /** Defaults to the value. */
    val label: String? = null,
)

@Serializable
data class ChartConfig(
    /** Defaults to the script title. */
    val title: String? = null,
    val type: String,
    val x: String,
    val y: List<String>,
)

/**
 * Builds the domain scripts. [readSql] reads a `sql_file`; with [readOnly],
 * only a single SELECT/WITH statement is accepted.
 *
 * @throws ConfigError with the first problem found.
 */
fun buildScripts(
    scripts: List<ScriptConfig>,
    readOnly: Boolean,
    readSql: (String) -> String,
): List<ScriptDefinition> {
    val seen = mutableSetOf<String>()
    return scripts.map { cfg ->
        if (!seen.add(cfg.name)) invalid("duplicate script name '${cfg.name}'")
        cfg.toDefinition(readOnly, readSql)
    }
}

private fun ScriptConfig.toDefinition(
    readOnly: Boolean,
    readSql: (String) -> String,
): ScriptDefinition {
    val text =
        when {
            sql != null && sqlFile == null -> sql
            sql == null && sqlFile != null -> readSql(sqlFile)
            else -> invalid("script '$name': set exactly one of 'sql' or 'sql_file'")
        }
    if (readOnly && !isReadOnlySelect(text)) {
        invalid(
            "script '$name': only a single read-only SELECT/WITH statement is allowed " +
                "(set database.read_only_scripts to false to opt out)",
        )
    }
    return try {
        ScriptDefinition(
            name = ScriptName.of(name),
            title = title,
            sql = text,
            description = description,
            group = group?.trim(),
            requiredPermissions = permissions.map(Permission::of),
            params = params.map { it.toDefinition(name) },
            charts = charts.map { it.toDefinition(this) },
            showTable = table,
            maxRows = maxRows ?: DEFAULT_MAX_ROWS,
            pageSize = pageSize ?: DEFAULT_PAGE_SIZE,
            timeoutSecs = timeoutSecs ?: DEFAULT_TIMEOUT_SECS,
        ).validate()
    } catch (e: DomainException) {
        val message = e.message.orEmpty()
        throw ConfigError.Invalid(if (message.startsWith("script '")) message else "script '$name': $message", e)
    }
}

private fun ParamConfig.toDefinition(script: String): ParamDefinition {
    val kind =
        ParamKind.entries.firstOrNull { it.label == type.lowercase() }
            ?: invalid("script '$script': parameter '$name' has unknown type '$type'")
    return ParamDefinition(
        name = name,
        kind = kind,
        label = label ?: name,
        required = required,
        default = default,
        help = help,
        min = min,
        max = max,
        maxLength = maxLength ?: DEFAULT_MAX_LENGTH,
        pattern = pattern,
        options = options.map { SelectOption(it.value, it.label ?: it.value) },
    )
}

private fun ChartConfig.toDefinition(script: ScriptConfig): ChartDefinition {
    val kind =
        ChartKind.entries.firstOrNull { it.label == type.lowercase() }
            ?: invalid("script '${script.name}': unknown chart type '$type'")
    return ChartDefinition(title ?: script.title, kind, x, y)
}

private fun invalid(message: String): Nothing = throw ConfigError.Invalid(message)

/** The access rule defaults to "the caller holds every permission the script lists". */
fun scriptsModule(
    scripts: List<ScriptDefinition>,
    access: ScriptAccessRule?,
) = module {
    single<ScriptCatalog> {
        log.info { "${scripts.size} scripts loaded" }
        ConfigScriptCatalog(scripts)
    }
    single<ScriptAccessRule> { access ?: RequiredPermissions }
    single { ScriptService(get(), get(), get(), get()) }
}
