package io.merkio.sqlportal.deployment.config

import io.merkio.sqlportal.adapters.auth.LdapConfig
import io.merkio.sqlportal.adapters.auth.OidcConfig
import io.merkio.sqlportal.api.ServerConfig
import io.merkio.sqlportal.api.UiConfig
import io.merkio.sqlportal.common.tls.TlsConfig
import io.merkio.sqlportal.common.tls.TlsOptions
import io.merkio.sqlportal.deployment.access.GrantsRuntime
import io.merkio.sqlportal.deployment.auth.AuthRuntime
import io.merkio.sqlportal.deployment.auth.Provider
import io.merkio.sqlportal.deployment.database.DatabaseRuntime
import io.merkio.sqlportal.deployment.observability.ObservabilityConfig
import io.merkio.sqlportal.deployment.scripts.buildScripts
import io.merkio.sqlportal.domain.access.AccessPolicy
import io.merkio.sqlportal.domain.common.DomainException
import io.merkio.sqlportal.domain.scripts.ScriptDefinition
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds

/**
 * The validated configuration. A RuntimeConfig only exists after every rule
 * has been checked and every domain object has been built, so the composition
 * root never sees a half-valid configuration.
 */
data class RuntimeConfig(
    /** `local`, `k8s`, `uat` or `prod`. */
    val environment: String,
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

/**
 * Checks the configuration and builds the runtime value.
 *
 * @throws ConfigError with the first problem found.
 */
internal fun LoadedConfig.toRuntime(): RuntimeConfig {
    checkRules()
    val auth =
        if (app.auth.enabled) {
            AuthRuntime.Enabled(
                ldap =
                    app.auth.ldap
                        ?.takeIf { it.enabled }
                        ?.let { Provider(it, ldapTls(it)) },
                oidc =
                    app.auth.oidc
                        ?.takeIf { it.enabled }
                        ?.let { Provider(it, oidcTls(it)) },
            )
        } else {
            AuthRuntime.Disabled
        }
    val grants =
        app.access.external?.let {
            GrantsRuntime.External(it.url, it.timeoutSecs.seconds, it.cacheTtlSecs.seconds, it.bearerToken, grantsTls())
        } ?: GrantsRuntime.Local
    return RuntimeConfig(
        environment = environment,
        server = app.server,
        ui = app.ui,
        observability = app.observability,
        database =
            DatabaseRuntime(
                url = app.database.url,
                maxConnections = app.database.maxConnections,
                acquireTimeout = app.database.acquireTimeoutSecs.seconds,
                tls = databaseTls(),
                initFiles = app.database.initSqlFiles.map(files::resolve),
                migrations = databaseMigrations(),
            ),
        auth = auth,
        grants = grants,
        policy = buildPolicy(),
        scripts = buildScripts(app.scripts, app.database.readOnlyScripts, ::readSql),
    )
}

/** Cross-cutting rules that do not need the domain objects built. */
private fun LoadedConfig.checkRules() {
    checkAuth()
    // Shared and per-connection TLS. Files and combinations are checked on the
    // resolved options; scheme rules only apply to explicit blocks.
    checkTls("tls", resolveTls(app.tls, null, true))
    if (app.database.tls != null && !app.database.supportsTls) {
        invalid("database.tls applies only to postgres:// and clickhouses:// URLs")
    }
    checkTls("database.tls", databaseTls())
    app.access.external?.let { external ->
        if (!external.url.startsWith("https://") && !external.url.startsWith("http://")) {
            invalid("access.external.url must start with http:// or https://")
        }
        if (external.tls != null &&
            !external.url.startsWith("https://")
        ) {
            invalid("access.external.tls needs an https:// URL")
        }
        if (external.timeoutSecs <= 0 || external.cacheTtlSecs <= 0) {
            invalid("access.external: timeout_secs and cache_ttl_secs must be positive")
        }
        checkTls("access.external.tls", grantsTls())
    }
    databaseMigrations()?.let { if (!Files.isDirectory(it)) invalid("database.migrations.dir is not a directory: $it") }
    if (!app.observability.metricsPath.startsWith("/")) invalid("observability.metrics_path must start with '/'")
    if (app.server.session.ttlMinutes <= 0) invalid("server.session.ttl_minutes must be positive")
    if (app.database.maxConnections <= 0) invalid("database.max_connections must be at least 1")
    if (app.database.acquireTimeoutSecs <= 0) invalid("database.acquire_timeout_secs must be positive")
    checkAbsoluteUrl("server.public_url", app.server.publicUrl)
    if (!app.ui.chartjsUrl.startsWith("/")) checkAbsoluteUrl("ui.chartjs_url", app.ui.chartjsUrl)
}

/** Providers are only checked when they would be used. */
private fun LoadedConfig.checkAuth() {
    val auth = app.auth
    if (!auth.enabled) return
    val ldap = auth.ldap?.takeIf { it.enabled }
    val oidc = auth.oidc?.takeIf { it.enabled }
    if (ldap == null && oidc == null) {
        invalid(
            "auth.enabled is true but neither auth.ldap nor auth.oidc is enabled; " +
                "enable a provider or set auth.enabled to false",
        )
    }
    ldap?.let {
        requireSet("auth.ldap.url", it.url)
        requireSet("auth.ldap.bind_dn", it.bindDn)
        requireSet("auth.ldap.base_dn", it.baseDn)
        if (it.tls != null && !it.usesTls) invalid("auth.ldap.tls needs an ldaps:// URL or start_tls = true")
        checkTls("auth.ldap.tls", ldapTls(it))
    }
    oidc?.let {
        requireSet("auth.oidc.issuer_url", it.issuerUrl)
        requireSet("auth.oidc.client_id", it.clientId)
        checkTls("auth.oidc.tls", oidcTls(it))
    }
}

private fun requireSet(
    key: String,
    value: String,
) {
    if (value.isBlank()) invalid("$key is required when the provider is enabled")
}

private fun checkTls(
    label: String,
    tls: TlsOptions,
) {
    try {
        tls.check()
    } catch (e: IllegalArgumentException) {
        throw ConfigError.Invalid("$label: ${e.message}", e)
    }
}

private fun checkAbsoluteUrl(
    label: String,
    value: String,
) {
    val ok =
        try {
            URI(value).let { it.isAbsolute && !it.host.isNullOrEmpty() }
        } catch (_: URISyntaxException) {
            false
        }
    if (!ok) invalid("$label is not a valid absolute URL: '$value'")
}

/**
 * TLS for one connection: its own `tls` block, or the shared block when the
 * connection actually uses TLS. Paths resolve like every other file.
 */
private fun LoadedConfig.resolveTls(
    shared: TlsConfig?,
    own: TlsConfig?,
    usesTls: Boolean,
): TlsOptions {
    val source = own ?: shared.takeIf { usesTls } ?: return TlsOptions()
    return source.toOptions(files::resolve)
}

internal fun LoadedConfig.databaseTls() = resolveTls(app.tls, app.database.tls, app.database.supportsTls)

internal fun LoadedConfig.ldapTls(ldap: LdapConfig) = resolveTls(app.tls, ldap.tls, ldap.usesTls)

internal fun LoadedConfig.oidcTls(oidc: OidcConfig) = resolveTls(app.tls, oidc.tls, true)

internal fun LoadedConfig.grantsTls() =
    resolveTls(
        app.tls,
        app.access.external?.tls,
        app.access.external
            ?.url
            ?.startsWith("https://") == true,
    )

/** The migrations directory when migrations are enabled, `null` otherwise. */
internal fun LoadedConfig.databaseMigrations(): Path? {
    val migrations = app.database.migrations ?: return null
    return if (migrations.enabled) files.resolve(migrations.dir) else null
}

internal fun LoadedConfig.buildPolicy(): AccessPolicy =
    try {
        with(app.access) { AccessPolicy(roles, groupRoles, userRoles, defaultRoles) }
    } catch (e: DomainException) {
        throw ConfigError.Invalid(e.message.orEmpty(), e)
    }

internal fun LoadedConfig.buildScripts(): List<ScriptDefinition> =
    buildScripts(app.scripts, app.database.readOnlyScripts, ::readSql)

private fun LoadedConfig.readSql(relative: String): String {
    val path = files.resolve(relative)
    return try {
        path.readText()
    } catch (e: IOException) {
        throw ConfigError.Io(path.toString(), e)
    }
}

private fun invalid(message: String): Nothing = throw ConfigError.Invalid(message)
