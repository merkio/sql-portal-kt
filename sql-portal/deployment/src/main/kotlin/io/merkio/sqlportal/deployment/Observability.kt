// `observability` section → logging, the Prometheus registry and the Metrics port.
package io.merkio.sqlportal.deployment

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.encoder.Encoder
import io.merkio.sqlportal.adapters.config.LogFormat
import io.merkio.sqlportal.application.Metrics
import io.merkio.sqlportal.common.metrics.Buckets
import io.merkio.sqlportal.common.metrics.DEFAULT_LATENCY_BUCKETS
import io.merkio.sqlportal.common.metrics.prometheusRegistry
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import net.logstash.logback.encoder.LogstashEncoder
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** The version from the jar manifest, `dev` when running from classes. */
val VERSION: String = HealthService::class.java.`package`?.implementationVersion ?: "dev"

/** Every `*.duration` timer (HTTP and scripts) uses latency buckets. */
fun portalRegistry(): PrometheusMeterRegistry =
    prometheusRegistry(listOf(Buckets(".duration", DEFAULT_LATENCY_BUCKETS)))

/** Declares the process-level gauges. */
fun describeAndInit(
    registry: MeterRegistry,
    version: String,
    authEnabled: Boolean,
) {
    Gauge
        .builder("build.info") { 1.0 }
        .description("Build information (always 1)")
        .tag("version", version)
        .register(registry)
    Gauge
        .builder("auth.enabled") { if (authEnabled) 1.0 else 0.0 }
        .description("1 when authentication is enabled, 0 when disabled")
        .register(registry)
}

/** The Metrics port on Micrometer, exported by the Prometheus registry. */
class PrometheusMetrics(
    private val registry: MeterRegistry,
) : Metrics {
    override fun authAttempt(
        provider: String,
        outcome: String,
    ) {
        Counter
            .builder("auth.attempts")
            .description("Authentication attempts by provider and outcome")
            .tags("provider", provider, "outcome", outcome)
            .register(registry)
            .increment()
    }

    override fun scriptRun(
        script: String,
        outcome: String,
        duration: Duration,
        rows: Int,
    ) {
        Counter
            .builder("script.runs")
            .description("Script executions by script and outcome")
            .tags("script", script, "outcome", outcome)
            .register(registry)
            .increment()
        Timer
            .builder("script.run.duration")
            .description("Script execution latency")
            .tag("script", script)
            .register(registry)
            .record(duration.toJavaDuration())
        if (rows > 0) {
            Counter
                .builder("script.rows.returned")
                .description("Rows returned by scripts")
                .tag("script", script)
                .register(registry)
                .increment(rows.toDouble())
        }
    }
}

/**
 * Configures Logback: `level` is a root level with optional per-logger
 * overrides (`info,io.merkio.sqlportal=debug`). `SCRIPT_PORTAL_LOG` overrides
 * the configured level. JSON output is one object per line, for log shippers.
 */
fun configureLogging(
    level: String,
    format: LogFormat,
) {
    val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
    val directives = (System.getenv("SCRIPT_PORTAL_LOG")?.takeIf { it.isNotBlank() } ?: level).split(',')
    val encoder: Encoder<ILoggingEvent> =
        when (format) {
            LogFormat.JSON -> LogstashEncoder()
            LogFormat.TEXT ->
                PatternLayoutEncoder().apply {
                    pattern = "%d{ISO8601} %-5level [%X{request_id:--}] %logger{36} - %msg%n"
                }
        }
    encoder.context = context
    encoder.start()
    val appender =
        ConsoleAppender<ILoggingEvent>().apply {
            this.context = context
            name = "console"
            this.encoder = encoder
            target = "System.err"
            start()
        }
    val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
    root.detachAndStopAllAppenders()
    root.addAppender(appender)
    for (directive in directives.map { it.trim() }.filter { it.isNotEmpty() }) {
        if ('=' in directive) {
            context.getLogger(directive.substringBefore('=').trim()).level =
                Level.toLevel(directive.substringAfter('='), Level.INFO)
        } else {
            root.level = Level.toLevel(directive, Level.INFO)
        }
    }
}
