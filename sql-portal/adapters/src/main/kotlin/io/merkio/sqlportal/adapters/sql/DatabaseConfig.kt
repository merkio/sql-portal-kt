package io.merkio.sqlportal.adapters.sql

import io.merkio.sqlportal.common.tls.TlsConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The `database` section: which database scripts run on, and how it is prepared. */
@Serializable
data class DatabaseConfig(
    /** `sqlite:...`, `postgres://...`, `clickhouse://...` or `clickhouses://...`. */
    val url: String,
    /** Connections opened at most; each is kept open and reused. In-memory SQLite always uses one. */
    @SerialName("max_connections") val maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
    /** How long a query waits for a free connection (and how long connecting may take). */
    @SerialName("acquire_timeout_secs") val acquireTimeoutSecs: Long = DEFAULT_ACQUIRE_SECS,
    /** SQL files executed once at startup, after the migrations. */
    @SerialName("init_sql_files") val initSqlFiles: List<String> = emptyList(),
    /** Schema and data migrations. Off unless enabled explicitly. */
    val migrations: MigrationsConfig? = null,
    /** Reject scripts that are not a single SELECT/WITH statement. */
    @SerialName("read_only_scripts") val readOnlyScripts: Boolean = true,
    /** TLS for the connection (`postgres://` and `clickhouses://` only). */
    val tls: TlsConfig? = null,
) {
    /** The URL uses a scheme that can be encrypted. */
    val supportsTls: Boolean
        get() = url.startsWith("postgres://") || url.startsWith("postgresql://") || url.startsWith("clickhouses://")

    companion object {
        const val DEFAULT_MAX_CONNECTIONS = 5
        const val DEFAULT_ACQUIRE_SECS = 5L
    }
}

/** Migrations run once at startup, in file-name order, before `init_sql_files`. */
@Serializable
data class MigrationsConfig(
    /** Explicit opt-in: nothing is applied unless this is `true`. */
    val enabled: Boolean = false,
    /** Directory of `*.sql` files. */
    val dir: String = "migrations/sqlite",
)
