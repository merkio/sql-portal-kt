// `observability` section → log levels, the Prometheus registry and the Metrics port.
package io.merkio.sqlportal.deployment.observability

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import io.github.oshai.kotlinlogging.DelegatingKLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.merkio.sqlportal.application.Metrics
import io.merkio.sqlportal.common.metrics.Buckets
import io.merkio.sqlportal.common.metrics.DEFAULT_LATENCY_BUCKETS
import io.merkio.sqlportal.common.metrics.prometheusRegistry
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** The version from the jar manifest, `dev` when running from classes. */
val VERSION: String = ObservabilityConfig::class.java.`package`?.implementationVersion ?: "dev"

/** The `observability` section. */
@Serializable
data class ObservabilityConfig(
    /** Root level and optional overrides, e.g. `info,io.merkio.sqlportal=debug`. */
    @SerialName("log_level") val logLevel: String = "info",
    @SerialName("metrics_enabled") val metricsEnabled: Boolean = true,
    @SerialName("metrics_path") val metricsPath: String = "/metrics",
)

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

    override fun sqlExecuted(
        script: String,
        purpose: String,
        outcome: String,
        duration: Duration,
    ) {
        Timer
            .builder("sql.execution.duration")
            .description("Time spent executing SQL in the database, by script, purpose and outcome")
            .tags("script", script, "purpose", purpose, "outcome", outcome)
            .register(registry)
            .record(duration.toJavaDuration())
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

/** Logback's context, reached through the logger KotlinLogging creates; `null` with another backend. */
fun logbackContext(): LoggerContext? =
    (
        (
            KotlinLogging.logger(
                Logger.ROOT_LOGGER_NAME,
            ) as? DelegatingKLogger<*>
        )?.underlyingLogger as? Logger
    )?.loggerContext

/**
 * Applies the configured log levels: a root level with optional per-logger
 * overrides (`info,io.merkio.sqlportal=debug`). `SCRIPT_PORTAL_LOG` overrides
 * the configured value. The output format is chosen by logback.xml from the
 * environment (see [LogFormatDefiner]).
 */
fun applyLogLevels(
    level: String,
    vars: (String) -> String? = System::getenv,
) {
    val context = logbackContext() ?: return
    val directives = (vars("SCRIPT_PORTAL_LOG")?.takeIf { it.isNotBlank() } ?: level).split(',')
    for (directive in directives.map { it.trim() }.filter { it.isNotEmpty() }) {
        if ('=' in directive) {
            context.getLogger(directive.substringBefore('=').trim()).level =
                Level.toLevel(directive.substringAfter('='), Level.INFO)
        } else {
            context.getLogger(Logger.ROOT_LOGGER_NAME).level = Level.toLevel(directive, Level.INFO)
        }
    }
}
