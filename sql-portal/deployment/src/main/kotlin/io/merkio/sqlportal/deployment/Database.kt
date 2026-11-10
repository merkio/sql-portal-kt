// `database` section → the QueryExecutor, with migrations and init files applied.
package io.merkio.sqlportal.deployment

import io.merkio.sqlportal.adapters.config.RuntimeConfig
import io.merkio.sqlportal.adapters.sql.connectDatabase
import io.merkio.sqlportal.adapters.sql.prepareDatabase
import io.merkio.sqlportal.domain.query.QueryExecutor

/** Connects (unless an executor is supplied) and prepares the database. */
suspend fun databaseExecutor(
    rt: RuntimeConfig,
    supplied: QueryExecutor?,
): QueryExecutor {
    val executor =
        supplied
            ?: connectDatabase(rt.database.url, rt.database.maxConnections, rt.database.acquireTimeout, rt.database.tls)
    prepareDatabase(executor, rt.database.migrations, rt.database.initFiles)
    return executor
}
