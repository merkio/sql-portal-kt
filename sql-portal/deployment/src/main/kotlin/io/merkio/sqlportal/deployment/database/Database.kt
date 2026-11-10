// `database` section → the QueryExecutor, with migrations and init files applied.
package io.merkio.sqlportal.deployment.database

import io.merkio.sqlportal.adapters.sql.connectDatabase
import io.merkio.sqlportal.adapters.sql.prepareDatabase
import io.merkio.sqlportal.common.tls.TlsOptions
import io.merkio.sqlportal.domain.query.QueryExecutor
import java.nio.file.Path
import kotlin.time.Duration

/** The validated `database` section, with paths and TLS options resolved. */
data class DatabaseRuntime(
    val url: String,
    val maxConnections: Int,
    val acquireTimeout: Duration,
    val tls: TlsOptions,
    val initFiles: List<Path>,
    /** Set only when migrations are enabled in the configuration. */
    val migrations: Path?,
)

/** Connects (unless an executor is supplied) and prepares the database. */
suspend fun databaseExecutor(
    database: DatabaseRuntime,
    supplied: QueryExecutor?,
): QueryExecutor {
    val executor =
        supplied ?: connectDatabase(database.url, database.maxConnections, database.acquireTimeout, database.tls)
    prepareDatabase(executor, database.migrations, database.initFiles)
    return executor
}
