package io.merkio.sqlportal.adapters.sql

import io.github.oshai.kotlinlogging.KotlinLogging
import io.merkio.sqlportal.common.tls.TlsOptions
import io.merkio.sqlportal.domain.query.QueryException
import io.merkio.sqlportal.domain.query.QueryExecutor
import io.merkio.sqlportal.sqlexecutor.SqlExecutor
import io.merkio.sqlportal.sqlexecutor.clickhouse.ClickHouseExecutor
import io.merkio.sqlportal.sqlexecutor.postgres.PostgresExecutor
import io.merkio.sqlportal.sqlexecutor.sqlite.SqliteExecutor
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.time.Duration

private val log = KotlinLogging.logger {}

/** The database could not be connected or prepared at startup. */
class DatabaseSetupException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Picks the datasource from the URL scheme: `clickhouse://` and
 * `clickhouses://` use the ClickHouse HTTP interface, `postgres://` and
 * `sqlite:` plain JDBC connections that are opened once and reused. The
 * result speaks the domain's [QueryExecutor].
 *
 * @throws DatabaseSetupException when the database cannot be reached or the
 *   driver for it is not part of this build.
 */
fun connectDatabase(
    url: String,
    maxConnections: Int,
    acquireTimeout: Duration,
    tls: TlsOptions,
): QueryExecutor = DatasourceExecutor(connectDatasource(url, maxConnections, acquireTimeout, tls))

private fun connectDatasource(
    url: String,
    maxConnections: Int,
    acquireTimeout: Duration,
    tls: TlsOptions,
): SqlExecutor =
    try {
        when {
            url.startsWith("clickhouse://") || url.startsWith("clickhouses://") ->
                ClickHouseExecutor.connect(url, acquireTimeout, tls)
            url.startsWith("postgres://") || url.startsWith("postgresql://") -> {
                requireDriver("org.postgresql.Driver", "PostgreSQL")
                PostgresExecutor.connect(url, maxConnections, acquireTimeout, tls)
            }
            url.startsWith("sqlite:") -> {
                require(!tls.isSet) { "database.tls applies only to postgres:// and clickhouses:// URLs" }
                requireDriver("org.sqlite.JDBC", "SQLite")
                SqliteExecutor.connect(url, maxConnections, acquireTimeout)
            }
            else -> throw IllegalArgumentException("unsupported database URL scheme in '${url.substringBefore(':')}:'")
        }
    } catch (e: IllegalArgumentException) {
        throw DatabaseSetupException(e.message.orEmpty(), e)
    } catch (e: IllegalStateException) {
        throw DatabaseSetupException(e.message.orEmpty(), e)
    }

private fun requireDriver(
    className: String,
    database: String,
) {
    try {
        Class.forName(className)
    } catch (e: ClassNotFoundException) {
        throw DatabaseSetupException("$database support is not included in this build (see -Pdatabases)", e)
    }
}

/** Executes the given SQL files in order. */
suspend fun runInitFiles(
    executor: QueryExecutor,
    files: List<Path>,
) {
    for (file in files) {
        val sql =
            try {
                file.readText()
            } catch (e: IOException) {
                throw DatabaseSetupException("cannot read init file $file: ${e.message}", e)
            }
        try {
            executor.runScript(sql)
        } catch (e: QueryException) {
            throw DatabaseSetupException("init file $file failed: ${e.message}", e)
        }
        log.info { "init script executed: $file" }
    }
}

/** Applies every `*.sql` file of [dir] in file-name order. Returns how many ran. */
suspend fun applyMigrations(
    executor: QueryExecutor,
    dir: Path,
): Int {
    val files =
        try {
            Files.list(dir).use { stream -> stream.toList() }
        } catch (e: IOException) {
            throw DatabaseSetupException("cannot read migrations directory $dir: ${e.message}", e)
        }.filter { it.isRegularFile() && it.extension == "sql" }.sortedBy { it.name }
    runInitFiles(executor, files)
    return files.size
}

/**
 * Runs the migrations (when given), then the configured init files. The
 * caller decides whether migrations run; this function never guesses.
 */
suspend fun prepareDatabase(
    executor: QueryExecutor,
    migrations: Path?,
    initFiles: List<Path>,
) {
    if (migrations != null) {
        val applied = applyMigrations(executor, migrations)
        log.info { "$applied migrations applied from $migrations" }
    }
    runInitFiles(executor, initFiles)
}
