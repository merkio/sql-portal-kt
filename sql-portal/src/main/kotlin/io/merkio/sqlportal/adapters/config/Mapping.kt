package io.merkio.sqlportal.adapters.config

import io.merkio.sqlportal.common.tls.TlsOptions
import io.merkio.sqlportal.domain.access.AccessPolicy
import io.merkio.sqlportal.domain.access.Permission
import io.merkio.sqlportal.domain.common.DomainException
import io.merkio.sqlportal.domain.scripts.ChartDefinition
import io.merkio.sqlportal.domain.scripts.ChartKind
import io.merkio.sqlportal.domain.scripts.DEFAULT_MAX_LENGTH
import io.merkio.sqlportal.domain.scripts.DEFAULT_MAX_ROWS
import io.merkio.sqlportal.domain.scripts.DEFAULT_PAGE_SIZE
import io.merkio.sqlportal.domain.scripts.DEFAULT_TIMEOUT_SECS
import io.merkio.sqlportal.domain.scripts.ParamDefinition
import io.merkio.sqlportal.domain.scripts.ParamKind
import io.merkio.sqlportal.domain.scripts.ScriptDefinition
import io.merkio.sqlportal.domain.scripts.ScriptName
import io.merkio.sqlportal.domain.scripts.SelectOption
import io.merkio.sqlportal.domain.scripts.isReadOnlySelect
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds

private fun invalid(message: String): Nothing = throw ConfigError.Invalid(message)

private fun LdapConfig.usesTls() = url.startsWith("ldaps://") || startTls

private fun isTlsDatabaseUrl(url: String) =
    url.startsWith("postgres://") || url.startsWith("postgresql://") || url.startsWith("clickhouses://")

/** Resolves a path relative to the configuration file. */
internal fun LoadedConfig.resolve(relative: String): Path {
    val p = Path.of(relative)
    return if (p.isAbsolute) p else baseDir.resolve(p).normalize()
}

/**
 * Checks the configuration and returns the validated runtime value.
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
                        ?.let { Connection(it, ldapTls()) },
                oidc =
                    app.auth.oidc
                        ?.takeIf { it.enabled }
                        ?.let { Connection(it, oidcTls()) },
            )
        } else {
            AuthRuntime.Disabled
        }
    val grants =
        app.access.external?.let {
            GrantsRuntime.External(
                Connection(
                    ExternalGrants(it.url, it.timeoutSecs.seconds, it.cacheTtlSecs.seconds, it.bearerToken),
                    grantsTls(),
                ),
            )
        } ?: GrantsRuntime.Local
    return RuntimeConfig(
        server = app.server,
        ui = app.ui,
        observability = app.observability,
        database =
            DatabaseRuntime(
                url = app.database.url,
                maxConnections = app.database.maxConnections,
                acquireTimeout = app.database.acquireTimeoutSecs.seconds,
                tls = databaseTls(),
                initFiles = app.database.initSqlFiles.map(::resolve),
                migrations = databaseMigrations(),
            ),
        auth = auth,
        grants = grants,
        policy = buildPolicy(),
        scripts = buildScripts(),
    )
}

/** Fails with the first configuration problem found. */
internal fun LoadedConfig.validate() {
    toRuntime()
}

private fun LoadedConfig.checkTls(
    label: String,
    tls: TlsOptions,
) {
    try {
        tls.check()
    } catch (e: IllegalArgumentException) {
        throw ConfigError.Invalid("$label: ${e.message}", e)
    }
}

/** Cross-cutting rules that do not need the domain objects built. */
private fun LoadedConfig.checkRules() {
    if (app.auth.enabled && app.auth.ldap?.enabled != true && app.auth.oidc?.enabled != true) {
        invalid(
            "auth.enabled is true but neither auth.ldap nor auth.oidc is enabled; " +
                "enable a provider or set auth.enabled to false",
        )
    }
    // Shared and per-connection TLS. Files and combinations are checked on the
    // resolved options; scheme rules only apply to explicit blocks.
    checkTls("tls", resolveTls(app.tls, null, true))
    if (app.database.tls != null && !isTlsDatabaseUrl(app.database.url)) {
        invalid("database.tls applies only to postgres:// and clickhouses:// URLs")
    }
    checkTls("database.tls", databaseTls())
    app.auth.ldap?.let { ldap ->
        if (ldap.tls != null && !ldap.usesTls()) invalid("auth.ldap.tls needs an ldaps:// URL or start_tls = true")
        checkTls("auth.ldap.tls", ldapTls())
    }
    if (app.auth.oidc != null) checkTls("auth.oidc.tls", oidcTls())
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
    checkAbsoluteUrl("server.public_url", app.server.publicUrl)
    if (!app.ui.chartjsUrl.startsWith("/")) checkAbsoluteUrl("ui.chartjs_url", app.ui.chartjsUrl)
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
 * connection actually uses TLS. Paths resolve against the configuration file.
 */
private fun LoadedConfig.resolveTls(
    shared: TlsConfig?,
    own: TlsConfig?,
    usesTls: Boolean,
): TlsOptions {
    val source = own ?: shared.takeIf { usesTls } ?: return TlsOptions()
    return TlsOptions(
        caFile = source.caFile?.let(::resolve),
        insecure = source.insecure,
        certFile = source.certFile?.let(::resolve),
        keyFile = source.keyFile?.let(::resolve),
    )
}

internal fun LoadedConfig.databaseTls() = resolveTls(app.tls, app.database.tls, isTlsDatabaseUrl(app.database.url))

internal fun LoadedConfig.ldapTls() = resolveTls(app.tls, app.auth.ldap?.tls, app.auth.ldap?.usesTls() == true)

internal fun LoadedConfig.oidcTls() = resolveTls(app.tls, app.auth.oidc?.tls, true)

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
    val migrations = app.database.migrations?.takeIf { it.enabled } ?: return null
    return resolve(migrations.dir ?: "migrations/sqlite")
}

internal fun LoadedConfig.buildPolicy(): AccessPolicy =
    try {
        AccessPolicy(app.access.roles, app.access.groupRoles, app.access.userRoles, app.access.defaultRoles)
    } catch (e: DomainException) {
        throw ConfigError.Invalid(e.message.orEmpty(), e)
    }

internal fun LoadedConfig.buildScripts(): List<ScriptDefinition> {
    val seen = mutableSetOf<String>()
    return app.scripts.map { cfg ->
        if (!seen.add(cfg.name)) invalid("duplicate script name '${cfg.name}'")
        buildScript(cfg)
    }
}

private fun LoadedConfig.buildScript(cfg: ScriptConfig): ScriptDefinition {
    val sql =
        when {
            cfg.sql != null && cfg.sqlFile == null -> cfg.sql
            cfg.sql == null && cfg.sqlFile != null -> {
                val path = resolve(cfg.sqlFile)
                try {
                    path.readText()
                } catch (e: IOException) {
                    throw ConfigError.Io(path.toString(), e)
                }
            }
            else -> invalid("script '${cfg.name}': set exactly one of 'sql' or 'sql_file'")
        }
    if (app.database.readOnlyScripts && !isReadOnlySelect(sql)) {
        invalid(
            "script '${cfg.name}': only a single read-only SELECT/WITH statement is allowed " +
                "(set database.read_only_scripts to false to opt out)",
        )
    }
    return try {
        ScriptDefinition(
            name = ScriptName.of(cfg.name),
            title = cfg.title,
            sql = sql,
            description = cfg.description,
            group = cfg.group?.trim(),
            requiredPermissions = cfg.permissions.map(Permission::of),
            params = cfg.params.map { mapParam(cfg.name, it) },
            charts = cfg.charts.map { mapChart(cfg, it) },
            showTable = cfg.table,
            maxRows = cfg.maxRows ?: DEFAULT_MAX_ROWS,
            pageSize = cfg.pageSize ?: DEFAULT_PAGE_SIZE,
            timeoutSecs = cfg.timeoutSecs ?: DEFAULT_TIMEOUT_SECS,
        ).validate()
    } catch (e: DomainException) {
        val message = e.message.orEmpty()
        throw ConfigError.Invalid(if (message.startsWith("script '")) message else "script '${cfg.name}': $message", e)
    }
}

private fun mapParam(
    script: String,
    cfg: ParamConfig,
): ParamDefinition {
    val kind =
        ParamKind.entries.firstOrNull { it.label == cfg.type.lowercase() }
            ?: invalid("script '$script': parameter '${cfg.name}' has unknown type '${cfg.type}'")
    return ParamDefinition(
        name = cfg.name,
        kind = kind,
        label = cfg.label ?: cfg.name,
        required = cfg.required,
        default = cfg.default,
        help = cfg.help,
        min = cfg.min,
        max = cfg.max,
        maxLength = cfg.maxLength ?: DEFAULT_MAX_LENGTH,
        pattern = cfg.pattern,
        options = cfg.options.map { SelectOption(it.value, it.label) },
    )
}

private fun mapChart(
    script: ScriptConfig,
    cfg: ChartConfig,
): ChartDefinition {
    val kind =
        ChartKind.entries.firstOrNull { it.label == cfg.type.lowercase() }
            ?: invalid("script '${script.name}': unknown chart type '${cfg.type}'")
    return ChartDefinition(cfg.title ?: script.title, kind, cfg.x, cfg.y)
}
