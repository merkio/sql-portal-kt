package io.merkio.sqlportal.adapters.config

import io.merkio.sqlportal.common.tls.TlsOptions
import io.merkio.sqlportal.domain.access.AccessPolicy
import io.merkio.sqlportal.domain.scripts.ScriptDefinition
import java.nio.file.Path
import kotlin.time.Duration

/*
 * The validated configuration. A RuntimeConfig only exists after every rule
 * has been checked and every domain object has been built, so the composition
 * root never sees a half-valid file.
 */

data class RuntimeConfig(
    val server: ServerConfig,
    val ui: UiConfig,
    val observability: ObservabilityConfig,
    val database: DatabaseRuntime,
    val auth: AuthRuntime,
    val grants: GrantsRuntime,
    /** Local roles and permissions, used when no external grant service is set. */
    val policy: AccessPolicy,
    val scripts: List<ScriptDefinition>,
)

data class DatabaseRuntime(
    val url: String,
    val maxConnections: Int,
    val acquireTimeout: Duration,
    val tls: TlsOptions,
    val initFiles: List<Path>,
    /** Set only when migrations are enabled in the configuration. */
    val migrations: Path?,
)

/** A configured remote endpoint with the TLS options that apply to it. */
data class Connection<S>(
    val settings: S,
    val tls: TlsOptions,
)

sealed interface AuthRuntime {
    /** Every request is an anonymous administrator. */
    data object Disabled : AuthRuntime

    data class Enabled(
        val ldap: Connection<LdapConfig>?,
        val oidc: Connection<OidcConfig>?,
    ) : AuthRuntime
}

sealed interface GrantsRuntime {
    data object Local : GrantsRuntime

    data class External(
        val connection: Connection<ExternalGrants>,
    ) : GrantsRuntime
}

data class ExternalGrants(
    val url: String,
    val timeout: Duration,
    val cacheTtl: Duration,
    val bearerToken: String?,
)
