// `script-portal` command: CLI, configuration loading and the HTTP server.
package io.merkio.sqlportal.deployment

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.merkio.sqlportal.adapters.config.ConfigError
import io.merkio.sqlportal.adapters.config.ConfigLoader
import io.merkio.sqlportal.adapters.config.toRuntime
import io.merkio.sqlportal.adapters.config.validate
import io.merkio.sqlportal.adapters.sql.DatabaseSetupException
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Path
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("io.merkio.sqlportal.deployment.Main")

/** The command line; [env] reads environment variables (SCRIPT_PORTAL_CONFIG). */
class Cli(
    env: (String) -> String? = System::getenv,
) : CoreCliktCommand(name = "script-portal") {
    init {
        // clikt-core reads no environment variables unless told how.
        configureContext { readEnvvar = env }
    }

    val config by option(
        "-c",
        "--config",
        envvar = "SCRIPT_PORTAL_CONFIG",
        help = "Path to the HOCON configuration file",
    ).path()
        .default(Path.of("config/config.conf"))

    override val invokeWithoutSubcommand = true

    override fun help(context: Context) = "Config-driven SQL script portal"

    override fun run() {
        if (currentContext.invokedSubcommand == null) serve(config)
    }
}

class Serve(
    private val cli: Cli,
) : CoreCliktCommand(name = "serve") {
    override fun help(context: Context) = "Run the web server (default)"

    override fun run() = serve(cli.config)
}

class CheckConfig(
    private val cli: Cli,
) : CoreCliktCommand(name = "check-config") {
    override fun help(context: Context) = "Validate the configuration and exit"

    override fun run() {
        val loaded = failOnConfigError { ConfigLoader.fromPath(cli.config).also { it.validate() } }
        echo("configuration OK (${loaded.app.scripts.size} scripts)")
    }
}

class Healthcheck : CoreCliktCommand(name = "healthcheck") {
    private val addr by option(help = "host:port of a running instance").default("127.0.0.1:8080")
    private val path by option(help = "Path to probe").default("/readyz")

    override fun help(context: Context) = "Probe a running instance (used by the Docker HEALTHCHECK)"

    override fun run() {
        val status = runCatching { probe(addr, path) }.getOrElse { "error: ${it.message}" }
        if (status.split(' ').getOrNull(1) != "200") {
            echo("unhealthy: $status", err = true)
            throw ProgramResult(1)
        }
    }
}

/** A minimal HTTP/1.0 request, so the distroless image needs no curl. */
private fun probe(
    addr: String,
    path: String,
): String {
    val (host, port) = hostPort(addr)
    Socket().use { socket ->
        socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
        socket.soTimeout = PROBE_TIMEOUT_MS
        socket.getOutputStream().write("GET $path HTTP/1.0\r\nHost: $addr\r\nConnection: close\r\n\r\n".toByteArray())
        return socket
            .getInputStream()
            .bufferedReader()
            .readLine()
            .orEmpty()
    }
}

private const val PROBE_TIMEOUT_MS = 3000

private fun hostPort(bind: String): Pair<String, Int> {
    val port = bind.substringAfterLast(':').toIntOrNull() ?: throw IllegalArgumentException("invalid address '$bind'")
    return bind.substringBeforeLast(':').removePrefix("[").removeSuffix("]") to port
}

private fun <T> failOnConfigError(block: () -> T): T =
    try {
        block()
    } catch (e: ConfigError) {
        System.err.println(e.message)
        throw ProgramResult(1)
    }

private fun serve(config: Path) {
    val loaded = failOnConfigError { ConfigLoader.fromPath(config) }
    configureLogging(loaded.app.observability.logLevel, loaded.app.observability.logFormat)
    val runtime = failOnConfigError { loaded.toRuntime() }
    val registry = portalRegistry()
    describeAndInit(registry, VERSION, loaded.app.auth.enabled)
    val module =
        try {
            runBlocking { buildApp(runtime, registry) }
        } catch (e: DatabaseSetupException) {
            log.error("startup failed: {}", e.message, e)
            throw ProgramResult(1)
        }
    val (host, port) = hostPort(System.getenv("SCRIPT_PORTAL_BIND") ?: runtime.server.bind)
    val server = embeddedServer(CIO, host = host, port = port, module = module)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info("shutting down")
            server.stop(GRACE_MS, STOP_TIMEOUT_MS)
        },
    )
    log.info("listening on {}:{} (version {})", host, port, VERSION)
    try {
        server.start(wait = true)
    } catch (e: IOException) {
        log.error("cannot listen on {}:{}: {}", host, port, e.message)
        throw ProgramResult(1)
    }
}

private const val GRACE_MS = 1_000L
private const val STOP_TIMEOUT_MS = 10_000L

/** The full command with its subcommands. */
fun portalCommand(env: (String) -> String? = System::getenv): Cli {
    val cli = Cli(env)
    cli.subcommands(Serve(cli), CheckConfig(cli), Healthcheck())
    return cli
}

fun main(args: Array<String>) {
    val cli = portalCommand()
    try {
        cli.parse(args)
    } catch (e: CliktError) {
        cli.echoFormattedHelp(e)
        exitProcess(e.statusCode)
    }
}
