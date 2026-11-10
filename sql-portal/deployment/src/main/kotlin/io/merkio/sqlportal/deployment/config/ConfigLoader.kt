package io.merkio.sqlportal.deployment.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigList
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigResolveOptions
import com.typesafe.config.ConfigSyntax
import com.typesafe.config.ConfigUtil
import com.typesafe.config.ConfigValue
import com.typesafe.config.ConfigValueFactory
import com.typesafe.config.ConfigValueType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.hocon.Hocon
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.readText

/** A merged and decoded configuration, before validation. */
internal class LoadedConfig(
    val app: AppConfig,
    val files: ConfigFiles,
    val environment: String,
)

/**
 * Loads the configuration in layers, the first one winning:
 *
 * 1. an optional file named by `SCRIPT_PORTAL_CONFIG`,
 * 2. `config/env/<environment>.conf` from the bundled resources,
 * 3. `config/default.conf` from the bundled resources.
 *
 * Environment variables are substituted the HOCON way (`key = ${?VAR}`
 * overrides a default, `${VAR}` is required) and also inside strings:
 * `"postgres://${DB_USER}@db/app"`, `"${VAR:-fallback}"`, `"${?VAR}"` (empty
 * when unset); `$${` stays a literal `${`. Script SQL (`sql`) is never
 * expanded. The merged result is checked strictly (unknown keys and wrong
 * types are errors, named by their path), decoded into [AppConfig] and
 * validated into a [RuntimeConfig].
 */
object ConfigLoader {
    /** Names an optional HOCON file applied on top of the bundled configuration. */
    const val FILE_VARIABLE = "SCRIPT_PORTAL_CONFIG"

    private const val DEFAULTS = "${ConfigFiles.RESOURCE_ROOT}/default.conf"

    private fun environmentFile(environment: String) = "${ConfigFiles.RESOURCE_ROOT}/env/$environment.conf"

    /**
     * The configuration the process environment selects: the environment named
     * by `APP_ENV` (default `local`), with the file named by
     * `SCRIPT_PORTAL_CONFIG` on top when it is set.
     *
     * @throws ConfigError with the first problem found.
     */
    fun fromEnvironment(vars: Map<String, String> = System.getenv()): RuntimeConfig =
        load(environment(vars::get), vars[FILE_VARIABLE]?.takeIf { it.isNotBlank() }?.let(Path::of), vars)

    /**
     * The configuration of [environment], with [file] on top when given.
     *
     * @throws ConfigError with the first problem found.
     */
    fun load(
        environment: String,
        file: Path? = null,
        vars: Map<String, String> = System.getenv(),
    ): RuntimeConfig = read(environment, file, vars).toRuntime()

    /**
     * Like [load], with HOCON [overrides] on top instead of a file; relative
     * paths in them resolve against [baseDir] first.
     *
     * @throws ConfigError with the first problem found.
     */
    fun load(
        environment: String,
        overrides: String,
        baseDir: Path? = null,
        vars: Map<String, String> = emptyMap(),
    ): RuntimeConfig = layered(environment, parse(overrides, "overrides"), baseDir, vars).toRuntime()

    internal fun read(
        environment: String,
        file: Path?,
        vars: Map<String, String>,
    ): LoadedConfig {
        val top =
            file?.let {
                val text =
                    try {
                        it.readText()
                    } catch (e: IOException) {
                        throw ConfigError.Io(it.toString(), e)
                    }
                parse(text, it.toString())
            }
        return layered(environment, top, file?.toAbsolutePath()?.parent, vars)
    }

    /** A single HOCON text with nothing below it; relative paths resolve against [baseDir]. */
    internal fun fromText(
        text: String,
        baseDir: Path?,
        vars: Map<String, String>,
        environment: String = "test",
    ): LoadedConfig = decode(listOf(parse(text, "text")), ConfigFiles(baseDir), vars, environment)

    private fun layered(
        environment: String,
        top: Config?,
        baseDir: Path?,
        vars: Map<String, String>,
    ): LoadedConfig {
        val envLayer =
            bundled(environmentFile(environment))
                ?: throw ConfigError.Invalid(
                    "unknown environment '$environment': there is no ${environmentFile(environment)} " +
                        "(set $ENVIRONMENT_VARIABLE to local, k8s, uat or prod)",
                )
        val defaults = bundled(DEFAULTS) ?: throw ConfigError.Invalid("$DEFAULTS is missing from the application")
        return decode(listOfNotNull(top, envLayer, defaults), ConfigFiles(baseDir), vars, environment)
    }

    private fun decode(
        layers: List<Config>,
        files: ConfigFiles,
        vars: Map<String, String>,
        environment: String,
    ): LoadedConfig {
        val merged = layers.reduce { upper, lower -> upper.withFallback(lower) }
        val expanded = expand(resolve(merged, vars).root(), null, vars) as ConfigObject
        @OptIn(ExperimentalSerializationApi::class)
        checkShape(expanded, AppConfig.serializer().descriptor, "")
        val app =
            try {
                @OptIn(ExperimentalSerializationApi::class)
                Hocon.decodeFromConfig(AppConfig.serializer(), expanded.toConfig())
            } catch (e: SerializationException) {
                throw ConfigError.Invalid(e.message.orEmpty(), e)
            } catch (e: ConfigException) {
                throw ConfigError.Invalid(e.message.orEmpty(), e)
            }
        return LoadedConfig(app, files, environment)
    }

    private fun parse(
        text: String,
        origin: String,
    ): Config =
        try {
            ConfigFactory.parseString(
                text,
                ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF).setOriginDescription(origin),
            )
        } catch (e: ConfigException.Parse) {
            throw ConfigError.Syntax(e.message.orEmpty(), e)
        }

    /** A bundled resource, with its `include`s resolved relative to it; `null` when absent. */
    private fun bundled(name: String): Config? {
        if (ConfigLoader::class.java.classLoader.getResource(name) == null) return null
        return try {
            ConfigFactory.parseResources(
                ConfigLoader::class.java.classLoader,
                name,
                ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF).setAllowMissing(false),
            )
        } catch (e: ConfigException.Parse) {
            throw ConfigError.Syntax(e.message.orEmpty(), e)
        }
    }

    /** HOCON substitutions against [vars] only; the variables do not become configuration keys. */
    private fun resolve(
        config: Config,
        vars: Map<String, String>,
    ): Config =
        try {
            val extra = vars.keys - config.root().keys
            extra.fold(
                config
                    .withFallback(ConfigValueFactory.fromMap(vars).toConfig())
                    .resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false)),
            ) { resolved, key -> resolved.withoutPath(ConfigUtil.joinPath(key)) }
        } catch (e: ConfigException.UnresolvedSubstitution) {
            val name = SUBSTITUTION.find(e.message.orEmpty())?.groupValues?.get(1) ?: "?"
            throw ConfigError.MissingEnv(name, e)
        }

    private val SUBSTITUTION = Regex("""\$\{\??\s*([^}\s]+)\s*}""")
}

// ------------------------------------------------------------ string expansion

private val IN_STRING = Regex("""\$\{(?<optional>\?)?(?<name>[A-Za-z_][A-Za-z0-9_.]*)(?::-(?<fallback>[^}]*))?}""")
private const val ESCAPED = "\u0000"

/** Keys whose strings are never expanded: script SQL is data, not configuration. */
private val VERBATIM = setOf("sql")

/**
 * Rebuilds [value] with variables substituted in every string, and with the
 * bare-scalar shorthand of select `options` turned into `{ value = ... }`.
 */
private fun expand(
    value: ConfigValue,
    key: String?,
    vars: Map<String, String>,
): ConfigValue =
    when (value) {
        is ConfigObject -> ConfigValueFactory.fromMap(value.mapValues { (k, v) -> expand(v, k, vars) })
        is ConfigList ->
            ConfigValueFactory.fromIterable(
                value.map { item ->
                    val shorthand = key == "options" && item !is ConfigObject && item !is ConfigList
                    expand(if (shorthand) ConfigValueFactory.fromMap(mapOf("value" to item)) else item, null, vars)
                },
            )
        else ->
            if (value.valueType() == ConfigValueType.STRING && key !in VERBATIM) {
                ConfigValueFactory.fromAnyRef(substitute(value.unwrapped() as String, vars))
            } else {
                value
            }
    }

private fun substitute(
    text: String,
    vars: Map<String, String>,
): String {
    if ("\${" !in text) return text
    return IN_STRING
        .replace(text.replace("$\${", ESCAPED)) { m ->
            val name = m.groups["name"]!!.value
            vars[name] ?: when {
                m.groups["fallback"] != null -> m.groups["fallback"]!!.value
                m.groups["optional"] != null -> ""
                else -> throw ConfigError.MissingEnv(name)
            }
        }.replace(ESCAPED, "\${")
}

// --------------------------------------------------------------- strict shape

private val BOOLEAN_WORDS = setOf("true", "false", "yes", "no", "on", "off")

/**
 * Checks [value] against the data class [descriptor] before decoding, so every
 * problem is reported with its path: unknown keys (typos must not be
 * ignored), missing required keys and wrongly typed values. Numbers and
 * booleans written as strings (from `${?VAR}`) are accepted.
 */
@ExperimentalSerializationApi
private fun checkShape(
    value: ConfigValue,
    descriptor: SerialDescriptor,
    path: String,
) {
    fun at(key: String) = if (path.isEmpty()) key else "$path.$key"
    when (val kind = descriptor.kind) {
        StructureKind.CLASS, StructureKind.OBJECT -> {
            val obj = value as? ConfigObject ?: invalid("$path must be an object")
            val elements = (0 until descriptor.elementsCount).associateBy(descriptor::getElementName)
            for (key in obj.keys.sorted()) {
                val index = elements[key] ?: invalid("unknown key '${at(key)}'")
                val child = obj.getValue(key)
                if (child.valueType() != ConfigValueType.NULL) {
                    checkShape(child, descriptor.getElementDescriptor(index), at(key))
                }
            }
            for ((name, index) in elements) {
                val missing = obj[name] == null || obj.getValue(name).valueType() == ConfigValueType.NULL
                if (missing &&
                    !descriptor.isElementOptional(index) &&
                    !descriptor.getElementDescriptor(index).isNullable
                ) {
                    invalid("${at(name)} is required")
                }
            }
        }
        StructureKind.LIST -> {
            val list = value as? ConfigList ?: invalid("$path must be a list")
            list.forEachIndexed { i, item -> checkShape(item, descriptor.getElementDescriptor(0), "$path[$i]") }
        }
        StructureKind.MAP -> {
            val obj = value as? ConfigObject ?: invalid("$path must be an object")
            obj.forEach { (key, item) -> checkShape(item, descriptor.getElementDescriptor(1), at(key)) }
        }
        is PrimitiveKind -> checkScalar(value, kind, path)
        else -> Unit
    }
}

private fun checkScalar(
    value: ConfigValue,
    kind: SerialKind,
    path: String,
) {
    val raw = value.unwrapped()
    if (raw is Map<*, *> || raw is List<*>) invalid("$path must be ${expected(kind)}")
    when (kind) {
        PrimitiveKind.BOOLEAN ->
            if (raw !is Boolean && (raw as? String)?.trim()?.lowercase() !in BOOLEAN_WORDS) {
                invalid("$path must be true or false")
            }
        PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> {
            val number = numberOf(raw) ?: invalid("$path must be a number")
            if (number.stripTrailingZeros().scale() > 0) invalid("$path must be a whole number")
        }
        PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> numberOf(raw) ?: invalid("$path must be a number")
        else -> Unit
    }
}

private fun numberOf(raw: Any?) =
    when (raw) {
        is Number -> raw.toString().toBigDecimalOrNull()
        is String -> raw.trim().toBigDecimalOrNull()
        else -> null
    }

private fun expected(kind: SerialKind) =
    when (kind) {
        PrimitiveKind.BOOLEAN -> "true or false"
        PrimitiveKind.STRING -> "a string"
        else -> "a number"
    }

private fun invalid(message: String): Nothing = throw ConfigError.Invalid(message)
