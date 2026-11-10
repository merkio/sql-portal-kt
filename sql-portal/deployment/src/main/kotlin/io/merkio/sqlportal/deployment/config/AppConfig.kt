package io.merkio.sqlportal.deployment.config

import io.merkio.sqlportal.adapters.sql.DatabaseConfig
import io.merkio.sqlportal.api.ServerConfig
import io.merkio.sqlportal.api.UiConfig
import io.merkio.sqlportal.common.tls.TlsConfig
import io.merkio.sqlportal.deployment.access.AccessConfig
import io.merkio.sqlportal.deployment.auth.AuthConfig
import io.merkio.sqlportal.deployment.observability.ObservabilityConfig
import io.merkio.sqlportal.deployment.scripts.ScriptConfig
import kotlinx.serialization.Serializable

/**
 * The configuration as written, after the layers are merged: one section per
 * part of the application, each a data class next to the code that uses it.
 */
@Serializable
data class AppConfig(
    /** Shared TLS settings for every outbound connection that uses TLS. */
    val tls: TlsConfig? = null,
    val server: ServerConfig = ServerConfig(),
    val ui: UiConfig = UiConfig(),
    val observability: ObservabilityConfig = ObservabilityConfig(),
    val database: DatabaseConfig,
    val auth: AuthConfig = AuthConfig(),
    val access: AccessConfig = AccessConfig(),
    val scripts: List<ScriptConfig> = emptyList(),
)

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

/** The variable that selects the environment: `local` (default), `k8s`, `uat` or `prod`. */
const val ENVIRONMENT_VARIABLE = "APP_ENV"

/** The name of the environment the process runs in, `local` unless [ENVIRONMENT_VARIABLE] says otherwise. */
fun environment(vars: (String) -> String?): String =
    vars(ENVIRONMENT_VARIABLE)?.trim()?.lowercase()?.ifEmpty { null } ?: "local"
