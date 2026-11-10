// Entry point: loads the configuration selected by the environment and serves HTTP.
package io.merkio.sqlportal.deployment

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.merkio.sqlportal.adapters.sql.DatabaseSetupException
import io.merkio.sqlportal.deployment.auth.AuthRuntime
import io.merkio.sqlportal.deployment.config.ConfigError
import io.merkio.sqlportal.deployment.config.ConfigLoader
import io.merkio.sqlportal.deployment.observability.VERSION
import io.merkio.sqlportal.deployment.observability.applyLogLevels
import io.merkio.sqlportal.deployment.observability.describeAndInit
import io.merkio.sqlportal.deployment.observability.portalRegistry
import io.merkio.sqlportal.deployment.wiring.buildApp
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.system.exitProcess

private val log = KotlinLogging.logger {}

private const val GRACE_MS = 1_000L
private const val STOP_TIMEOUT_MS = 10_000L

/**
 * Starts the portal. Everything comes from environment variables: `APP_ENV`
 * selects the bundled environment, `SCRIPT_PORTAL_CONFIG` names an optional
 * extra HOCON file, `SCRIPT_PORTAL_BIND` overrides the listen address. An
 * invalid configuration or an unreachable database ends the process with
 * status 1.
 */
fun main() {
    val runtime =
        try {
            ConfigLoader.fromEnvironment()
        } catch (e: ConfigError) {
            log.error { e.message }
            exitProcess(1)
        }
    applyLogLevels(runtime.observability.logLevel)
    log.info { "environment ${runtime.environment}" }
    val registry = portalRegistry()
    describeAndInit(registry, VERSION, runtime.auth !is AuthRuntime.Disabled)
    val module =
        try {
            runBlocking { buildApp(runtime, registry) }
        } catch (e: DatabaseSetupException) {
            log.error(e) { "startup failed: ${e.message}" }
            exitProcess(1)
        }
    val (host, port) = hostPort(System.getenv("SCRIPT_PORTAL_BIND") ?: runtime.server.bind)
    val server = embeddedServer(CIO, host = host, port = port, module = module)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info { "shutting down" }
            server.stop(GRACE_MS, STOP_TIMEOUT_MS)
        },
    )
    log.info { "listening on $host:$port (version $VERSION)" }
    try {
        server.start(wait = true)
    } catch (e: IOException) {
        log.error { "cannot listen on $host:$port: ${e.message}" }
        exitProcess(1)
    }
}

private fun hostPort(bind: String): Pair<String, Int> {
    val port = bind.substringAfterLast(':').toIntOrNull() ?: error("invalid listen address '$bind'")
    return bind.substringBeforeLast(':').removePrefix("[").removeSuffix("]") to port
}
