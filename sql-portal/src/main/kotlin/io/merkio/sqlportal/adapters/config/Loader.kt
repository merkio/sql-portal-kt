package io.merkio.sqlportal.adapters.config

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigResolveOptions
import com.typesafe.config.ConfigSyntax
import com.typesafe.config.ConfigUtil
import com.typesafe.config.ConfigValueFactory
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.readText

/** Why a configuration cannot be used. */
sealed class ConfigError(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    class Io(
        path: String,
        cause: Throwable,
    ) : ConfigError("cannot read '$path': ${describe(cause)}", cause)

    class Syntax(
        detail: String,
        cause: Throwable? = null,
    ) : ConfigError("invalid HOCON: $detail", cause)

    class MissingEnv(
        val name: String,
        cause: Throwable? = null,
    ) : ConfigError("environment variable '$name' is not set and has no default", cause)

    class Invalid(
        detail: String,
        cause: Throwable? = null,
    ) : ConfigError("invalid configuration: $detail", cause)
}

private fun describe(e: Throwable): String =
    when (e) {
        is java.nio.file.NoSuchFileException -> "no such file"
        is java.nio.file.AccessDeniedException -> "permission denied"
        else -> e.message ?: e.toString()
    }

/** A parsed configuration plus the directory used to resolve relative paths. */
internal class LoadedConfig(
    val app: AppConfig,
    val baseDir: Path,
)

private val SUBSTITUTION = Regex("""\$\{\??\s*([^}\s]+)\s*}""")

/**
 * Loads HOCON configuration. `${VAR}` substitutions fall back to environment
 * variables (`${?VAR}` when the variable is optional); values in quotes,
 * including SQL, are never expanded.
 */
object ConfigLoader {
    /**
     * Reads, parses and validates the file: the one entry point. Relative paths
     * in it resolve against its directory.
     *
     * @throws ConfigError with the first problem found.
     */
    fun load(path: Path): RuntimeConfig = fromPath(path).toRuntime()

    /**
     * Like [load] for text; relative paths resolve against [baseDir]. With
     * [env], substitutions resolve against that map only (tests).
     *
     * @throws ConfigError with the first problem found.
     */
    fun load(
        text: String,
        baseDir: Path,
        env: Map<String, String>? = null,
    ): RuntimeConfig = fromString(text, baseDir, env).toRuntime()

    /** @throws ConfigError */
    internal fun fromPath(path: Path): LoadedConfig {
        val text =
            try {
                path.readText()
            } catch (e: IOException) {
                throw ConfigError.Io(path.toString(), e)
            }
        val base = path.toAbsolutePath().parent ?: Path.of(".")
        return fromString(text, base)
    }

    /**
     * Parses [text]. With [env], substitutions resolve against that map only
     * (tests); otherwise against the process environment.
     *
     * @throws ConfigError
     */
    internal fun fromString(
        text: String,
        baseDir: Path,
        env: Map<String, String>? = null,
    ): LoadedConfig {
        val parsed =
            try {
                ConfigFactory.parseString(text, ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF))
            } catch (e: ConfigException.Parse) {
                throw ConfigError.Syntax(e.message.orEmpty(), e)
            }
        val resolved =
            try {
                if (env == null) {
                    parsed.resolve()
                } else {
                    // Resolve against the map as a fallback, then drop its keys again.
                    val extra = env.keys - parsed.root().keys
                    extra.fold(
                        parsed
                            .withFallback(ConfigValueFactory.fromMap(env).toConfig())
                            .resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false)),
                    ) { config, key -> config.withoutPath(ConfigUtil.joinPath(key)) }
                }
            } catch (e: ConfigException.UnresolvedSubstitution) {
                val name = SUBSTITUTION.find(e.message.orEmpty())?.groupValues?.get(1) ?: "?"
                throw ConfigError.MissingEnv(name, e)
            }
        return LoadedConfig(AppConfig.read(Section(resolved.root(), "")), baseDir)
    }
}
