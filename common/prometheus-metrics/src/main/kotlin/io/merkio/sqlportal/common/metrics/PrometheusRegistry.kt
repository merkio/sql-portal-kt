/*
 * A Prometheus registry for Micrometer with caller-supplied histogram buckets.
 * Knows nothing about the metrics an application records.
 */
package io.merkio.sqlportal.common.metrics

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

/** Latency buckets in seconds, from 5 ms to 30 s. */
val DEFAULT_LATENCY_BUCKETS = listOf(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0)

/**
 * Histogram buckets (in seconds) for every timer whose Micrometer name ends
 * with [nameSuffix], e.g. `.duration` for `http.request.duration`, exported
 * as `http_request_duration_seconds_bucket`.
 */
data class Buckets(
    val nameSuffix: String,
    val seconds: List<Double>,
) {
    init {
        require(seconds.isNotEmpty()) { "invalid buckets for '*$nameSuffix': no values" }
    }
}

/** A registry that renders the Prometheus text format. */
fun prometheusRegistry(buckets: List<Buckets>): PrometheusMeterRegistry {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    registry.config().meterFilter(
        object : MeterFilter {
            override fun configure(
                id: Meter.Id,
                config: DistributionStatisticConfig,
            ): DistributionStatisticConfig {
                if (id.type != Meter.Type.TIMER) return config
                val match = buckets.firstOrNull { id.name.endsWith(it.nameSuffix) } ?: return config
                return DistributionStatisticConfig
                    .builder()
                    .serviceLevelObjectives(*match.seconds.map { it * NANOS_PER_SECOND }.toDoubleArray())
                    .build()
                    .merge(config)
            }
        },
    )
    return registry
}

/** Timer histograms are configured in nanoseconds. */
private const val NANOS_PER_SECOND = 1e9
