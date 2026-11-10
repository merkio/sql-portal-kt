package io.merkio.sqlportal.adapters.config

import com.typesafe.config.ConfigObject

/*
 * The configuration file as written, before validation. Keys use snake_case
 * like the original YAML model; defaults are applied here.
 */

data class AppConfig(
    /** Shared TLS settings for every outbound connection that uses TLS. */
    val tls: TlsConfig?,
    val server: ServerConfig,
    val ui: UiConfig,
    val observability: ObservabilityConfig,
    val database: DatabaseConfig,
    val auth: AuthConfig,
    val access: AccessConfig,
    val scripts: List<ScriptConfig>,
) {
    companion object {
        fun read(root: Section): AppConfig {
            val database = root.sectionOrNull("database") ?: throw ConfigError.Invalid("database is required")
            return AppConfig(
                tls = root.sectionOrNull("tls")?.let(TlsConfig::read),
                server = ServerConfig.read(root.section("server")),
                ui = UiConfig.read(root.section("ui")),
                observability = ObservabilityConfig.read(root.section("observability")),
                database = DatabaseConfig.read(database),
                auth = AuthConfig.read(root.section("auth")),
                access = AccessConfig.read(root.section("access")),
                scripts = root.sections("scripts").map(ScriptConfig::read),
            ).also { root.done() }
        }
    }
}

data class ServerConfig(
    val bind: String = "0.0.0.0:8080",
    /** Externally visible base URL, used to build the OIDC redirect URI. */
    val publicUrl: String = "http://localhost:8080",
    val session: SessionConfig = SessionConfig(),
) {
    companion object {
        fun read(s: Section) =
            ServerConfig(
                bind = s.string("bind", "0.0.0.0:8080"),
                publicUrl = s.string("public_url", "http://localhost:8080"),
                session = SessionConfig.read(s.section("session")),
            ).also { s.done() }
    }
}

data class SessionConfig(
    val cookieName: String = "sp_session",
    /** Set the `Secure` cookie attribute (required behind HTTPS). */
    val secure: Boolean = false,
    val ttlMinutes: Long = DEFAULT_TTL_MINUTES,
) {
    companion object {
        const val DEFAULT_TTL_MINUTES = 60L

        fun read(s: Section) =
            SessionConfig(
                cookieName = s.string("cookie_name", "sp_session"),
                secure = s.boolean("secure", false),
                ttlMinutes = s.long("ttl_minutes", DEFAULT_TTL_MINUTES),
            ).also { s.done() }
    }
}

data class UiConfig(
    val title: String = "Script Portal",
    /** Chart.js bundle. Point this at a self-hosted copy to avoid the CDN. */
    val chartjsUrl: String = DEFAULT_CHARTJS,
) {
    companion object {
        const val DEFAULT_CHARTJS = "https://cdn.jsdelivr.net/npm/chart.js@4.4.7/dist/chart.umd.min.js"

        fun read(s: Section) =
            UiConfig(
                title = s.string("title", "Script Portal"),
                chartjsUrl = s.string("chartjs_url", DEFAULT_CHARTJS),
            ).also { s.done() }
    }
}

enum class LogFormat { TEXT, JSON }

data class ObservabilityConfig(
    /** Root level and optional overrides, e.g. `info,io.merkio.sqlportal=debug`. */
    val logLevel: String = "info",
    val logFormat: LogFormat = LogFormat.TEXT,
    val metricsEnabled: Boolean = true,
    val metricsPath: String = "/metrics",
) {
    companion object {
        fun read(s: Section): ObservabilityConfig {
            val format =
                when (val f = s.string("log_format", "text").lowercase()) {
                    "text" -> LogFormat.TEXT
                    "json" -> LogFormat.JSON
                    else -> throw ConfigError.Invalid("observability.log_format must be text or json, not '$f'")
                }
            return ObservabilityConfig(
                logLevel = s.string("log_level", "info"),
                logFormat = format,
                metricsEnabled = s.boolean("metrics_enabled", true),
                metricsPath = s.string("metrics_path", "/metrics"),
            ).also { s.done() }
        }
    }
}

/** TLS settings of a connection. Relative paths resolve against the configuration file. */
data class TlsConfig(
    val caFile: String? = null,
    /** Accept any server certificate without verification. Development only. */
    val insecure: Boolean = false,
    val certFile: String? = null,
    val keyFile: String? = null,
) {
    companion object {
        fun read(s: Section) =
            TlsConfig(
                caFile = s.stringOrNull("ca_file"),
                insecure = s.boolean("insecure", false),
                certFile = s.stringOrNull("cert_file"),
                keyFile = s.stringOrNull("key_file"),
            ).also { s.done() }
    }
}

data class DatabaseConfig(
    val url: String,
    val maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
    val acquireTimeoutSecs: Long = DEFAULT_ACQUIRE_SECS,
    /** SQL files executed once at startup, relative to the configuration file. */
    val initSqlFiles: List<String> = emptyList(),
    /** Schema and data migrations. Off unless enabled explicitly. */
    val migrations: MigrationsConfig? = null,
    /** Reject scripts that are not a single SELECT/WITH statement. */
    val readOnlyScripts: Boolean = true,
    /** TLS for the connection (`postgres://` and `clickhouses://` only). */
    val tls: TlsConfig? = null,
) {
    companion object {
        const val DEFAULT_MAX_CONNECTIONS = 5
        const val DEFAULT_ACQUIRE_SECS = 5L

        fun read(s: Section) =
            DatabaseConfig(
                url = s.string("url"),
                maxConnections = s.int("max_connections", DEFAULT_MAX_CONNECTIONS),
                acquireTimeoutSecs = s.long("acquire_timeout_secs", DEFAULT_ACQUIRE_SECS),
                initSqlFiles = s.stringList("init_sql_files"),
                migrations = s.sectionOrNull("migrations")?.let(MigrationsConfig::read),
                readOnlyScripts = s.boolean("read_only_scripts", true),
                tls = s.sectionOrNull("tls")?.let(TlsConfig::read),
            ).also { s.done() }
    }
}

/** Migrations run once at startup, in file-name order, before `init_sql_files`. */
data class MigrationsConfig(
    /** Explicit opt-in: nothing is applied unless this is `true`. */
    val enabled: Boolean = false,
    /** Directory of `*.sql` files, relative to the config file. Defaults to `migrations/sqlite`. */
    val dir: String? = null,
) {
    companion object {
        fun read(s: Section) = MigrationsConfig(s.boolean("enabled", false), s.stringOrNull("dir")).also { s.done() }
    }
}

data class AuthConfig(
    /** Master switch: `false` disables all authorizers and serves everyone as an anonymous administrator. */
    val enabled: Boolean = true,
    val ldap: LdapConfig? = null,
    val oidc: OidcConfig? = null,
) {
    companion object {
        fun read(s: Section) =
            AuthConfig(
                enabled = s.boolean("enabled", true),
                ldap = s.sectionOrNull("ldap")?.let(LdapConfig::read),
                oidc = s.sectionOrNull("oidc")?.let(OidcConfig::read),
            ).also { s.done() }
    }
}

data class LdapConfig(
    val enabled: Boolean = true,
    /** Overrides the shared `tls` block for this directory. */
    val tls: TlsConfig? = null,
    /** `ldap://host:389` or `ldaps://host:636`. */
    val url: String,
    val startTls: Boolean = false,
    val bindDn: String,
    val bindPassword: String,
    val baseDn: String,
    /** `{username}` is replaced by the escaped login name. */
    val userFilter: String = "(&(objectClass=person)(uid={username}))",
    val usernameAttribute: String = "uid",
    val displayNameAttribute: String = "cn",
    val emailAttribute: String = "mail",
    /** Attribute on the user entry listing group DNs. Empty = none. */
    val groupAttribute: String = "memberOf",
    /** Optional group search for servers without `memberOf`. */
    val groupSearch: LdapGroupSearch? = null,
    val timeoutSecs: Long = DEFAULT_TIMEOUT_SECS,
) {
    companion object {
        const val DEFAULT_TIMEOUT_SECS = 5L

        fun read(s: Section) =
            LdapConfig(
                enabled = s.boolean("enabled", true),
                tls = s.sectionOrNull("tls")?.let(TlsConfig::read),
                url = s.string("url"),
                startTls = s.boolean("start_tls", false),
                bindDn = s.string("bind_dn"),
                bindPassword = s.string("bind_password"),
                baseDn = s.string("base_dn"),
                userFilter = s.string("user_filter", "(&(objectClass=person)(uid={username}))"),
                usernameAttribute = s.string("username_attribute", "uid"),
                displayNameAttribute = s.string("display_name_attribute", "cn"),
                emailAttribute = s.string("email_attribute", "mail"),
                groupAttribute = s.string("group_attribute", "memberOf"),
                groupSearch = s.sectionOrNull("group_search")?.let(LdapGroupSearch::read),
                timeoutSecs = s.long("timeout_secs", DEFAULT_TIMEOUT_SECS),
            ).also { s.done() }
    }
}

data class LdapGroupSearch(
    val baseDn: String,
    /** `{user_dn}` and `{username}` are replaced by escaped values. */
    val filter: String = "(&(objectClass=groupOfNames)(member={user_dn}))",
    val nameAttribute: String = "cn",
) {
    companion object {
        fun read(s: Section) =
            LdapGroupSearch(
                baseDn = s.string("base_dn"),
                filter = s.string("filter", "(&(objectClass=groupOfNames)(member={user_dn}))"),
                nameAttribute = s.string("name_attribute", "cn"),
            ).also { s.done() }
    }
}

data class OidcConfig(
    val enabled: Boolean = true,
    /** Overrides the shared `tls` block for this provider. */
    val tls: TlsConfig? = null,
    val issuerUrl: String,
    val clientId: String,
    val clientSecret: String? = null,
    val scopes: List<String> = listOf("profile", "email"),
    val usernameClaim: String = "preferred_username",
    val groupsClaim: String = "groups",
) {
    companion object {
        fun read(s: Section) =
            OidcConfig(
                enabled = s.boolean("enabled", true),
                tls = s.sectionOrNull("tls")?.let(TlsConfig::read),
                issuerUrl = s.string("issuer_url"),
                clientId = s.string("client_id"),
                clientSecret = s.stringOrNull("client_secret"),
                scopes = if (s.has("scopes")) s.stringList("scopes") else listOf("profile", "email"),
                usernameClaim = s.string("username_claim", "preferred_username"),
                groupsClaim = s.string("groups_claim", "groups"),
            ).also { s.done() }
    }
}

/** Role-based access: roles carry permissions; users and groups carry roles. */
data class AccessConfig(
    /** Roles granted to every authenticated user. */
    val defaultRoles: List<String> = emptyList(),
    /** role -> permissions (`*` and `prefix.*` wildcards allowed). */
    val roles: Map<String, List<String>> = emptyMap(),
    /** group name or LDAP DN -> roles. */
    val groupRoles: Map<String, List<String>> = emptyMap(),
    /** username / OIDC subject -> roles. */
    val userRoles: Map<String, List<String>> = emptyMap(),
    /** External grants service; when set it replaces the maps above. */
    val external: ExternalGrantsConfig? = null,
) {
    companion object {
        fun read(s: Section) =
            AccessConfig(
                defaultRoles = s.stringList("default_roles"),
                roles = s.stringListMap("roles"),
                groupRoles = s.stringListMap("group_roles"),
                userRoles = s.stringListMap("user_roles"),
                external = s.sectionOrNull("external")?.let(ExternalGrantsConfig::read),
            ).also { s.done() }
    }
}

/**
 * Grants loaded from an external service and cached locally. The service
 * receives `POST {url}` with `{"subject", "display_name", "email", "groups"}`
 * and answers `{"roles": [...], "permissions": [...]}`, or `404` for an
 * identity that holds nothing.
 */
data class ExternalGrantsConfig(
    val url: String,
    val timeoutSecs: Long = DEFAULT_TIMEOUT_SECS,
    /** Fresh answers are reused this long; stale ones bridge an outage for at most twice this long. */
    val cacheTtlSecs: Long = DEFAULT_CACHE_TTL_SECS,
    val bearerToken: String? = null,
    val tls: TlsConfig? = null,
) {
    companion object {
        const val DEFAULT_TIMEOUT_SECS = 3L
        const val DEFAULT_CACHE_TTL_SECS = 300L

        fun read(s: Section) =
            ExternalGrantsConfig(
                url = s.string("url"),
                timeoutSecs = s.long("timeout_secs", DEFAULT_TIMEOUT_SECS),
                cacheTtlSecs = s.long("cache_ttl_secs", DEFAULT_CACHE_TTL_SECS),
                bearerToken = s.stringOrNull("bearer_token"),
                tls = s.sectionOrNull("tls")?.let(TlsConfig::read),
            ).also { s.done() }
    }
}

data class ScriptConfig(
    val name: String,
    val title: String,
    val description: String = "",
    /** Section of the script list. Scripts without a group are listed last. */
    val group: String? = null,
    /** Inline SQL. Exactly one of `sql` / `sql_file` is required. */
    val sql: String? = null,
    /** SQL file path relative to the configuration file. */
    val sqlFile: String? = null,
    /** Permissions the caller needs (all of them). */
    val permissions: List<String> = emptyList(),
    val params: List<ParamConfig> = emptyList(),
    val charts: List<ChartConfig> = emptyList(),
    val table: Boolean = true,
    val maxRows: Int? = null,
    /** Rows per page of the table; charts always use `max_rows`. */
    val pageSize: Int? = null,
    val timeoutSecs: Long? = null,
) {
    companion object {
        fun read(s: Section) =
            ScriptConfig(
                name = s.string("name"),
                title = s.string("title"),
                description = s.string("description", ""),
                group = s.stringOrNull("group"),
                sql = s.stringOrNull("sql"),
                sqlFile = s.stringOrNull("sql_file"),
                permissions = s.stringList("permissions"),
                params = s.sections("params").map(ParamConfig::read),
                charts = s.sections("charts").map(ChartConfig::read),
                table = s.boolean("table", true),
                maxRows = s.intOrNull("max_rows"),
                pageSize = s.intOrNull("page_size"),
                timeoutSecs = s.longOrNull("timeout_secs"),
            ).also { s.done() }
    }
}

data class OptionConfig(
    val value: String,
    val label: String,
)

data class ParamConfig(
    val name: String,
    val label: String? = null,
    val type: String,
    val required: Boolean = false,
    /** Any scalar, kept as text (`default: 5` and `default: "5"` are the same). */
    val default: String? = null,
    val help: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val maxLength: Int? = null,
    val pattern: String? = null,
    /** Each option is a bare scalar or `{value, label}`. */
    val options: List<OptionConfig> = emptyList(),
) {
    companion object {
        fun read(s: Section): ParamConfig {
            val options =
                s.list("options").mapIndexed { i, item ->
                    if (item is ConfigObject) {
                        val o = Section(item, "${s.path}.options[$i]")
                        val value = o.stringOrNull("value").orEmpty()
                        OptionConfig(value, o.stringOrNull("label") ?: value).also { o.done() }
                    } else {
                        val value =
                            scalarText(item)
                                ?: throw ConfigError.Invalid("${s.path}.options[$i] must be a scalar or an object")
                        OptionConfig(value, value)
                    }
                }
            return ParamConfig(
                name = s.string("name"),
                label = s.stringOrNull("label"),
                type = s.string("type"),
                required = s.boolean("required", false),
                default = s.stringOrNull("default"),
                help = s.stringOrNull("help"),
                min = s.doubleOrNull("min"),
                max = s.doubleOrNull("max"),
                maxLength = s.intOrNull("max_length"),
                pattern = s.stringOrNull("pattern"),
                options = options,
            ).also { s.done() }
        }
    }
}

data class ChartConfig(
    val title: String? = null,
    val type: String,
    val x: String,
    val y: List<String>,
) {
    companion object {
        fun read(s: Section) =
            ChartConfig(
                title = s.stringOrNull("title"),
                type = s.string("type"),
                x = s.string("x"),
                y = s.stringList("y"),
            ).also { s.done() }
    }
}
