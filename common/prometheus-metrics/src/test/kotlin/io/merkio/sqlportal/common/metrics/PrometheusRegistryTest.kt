package io.merkio.sqlportal.common.metrics

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.Duration

class PrometheusRegistryTest :
    FunSpec({
        test("timers matching the suffix get buckets") {
            val registry = prometheusRegistry(listOf(Buckets(".duration", DEFAULT_LATENCY_BUCKETS)))
            registry.timer("call.duration").record(Duration.ofMillis(200))
            registry.timer("payload.time").record(Duration.ofMillis(10))
            val text = registry.scrape()
            text shouldContain """call_duration_seconds_bucket{le="0.25"} 1"""
            text shouldNotContain "payload_time_seconds_bucket"
        }

        test("empty buckets are rejected") {
            shouldThrow<IllegalArgumentException> { Buckets(".duration", emptyList()) }.message shouldContain
                ".duration"
        }
    })
