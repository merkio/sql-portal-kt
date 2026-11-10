package io.merkio.sqlportal.integration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.merkio.sqlportal.deployment.observability.PrometheusMetrics
import io.merkio.sqlportal.deployment.observability.describeAndInit
import io.merkio.sqlportal.deployment.observability.portalRegistry
import kotlin.time.Duration.Companion.milliseconds

/** The Prometheus pipeline end to end: HTTP and business metrics in one registry. */
class MetricsTest :
    FunSpec({
        test("HTTP and business metrics are exposed") {
            val registry = portalRegistry()
            describeAndInit(registry, "test", true)
            Harness.start(Options(authEnabled = false, registry = registry)).use { h ->
                h.get("/healthz")
                h.get("/scripts/monthly_trend")
                h.get("/definitely-not-a-route")
            }
            PrometheusMetrics(registry).authAttempt("ldap", "invalid_credentials")
            PrometheusMetrics(registry).scriptRun("monthly_trend", "success", 5.milliseconds, 6)

            PrometheusMetrics(registry).sqlExecuted("monthly_trend", "table", "success", 30.milliseconds)

            val text = registry.scrape()
            // SQL execution time is a histogram: buckets, sum and count per script, purpose and outcome.
            text shouldContain
                """sql_execution_duration_seconds_bucket{outcome="success",purpose="table",script="monthly_trend",le="0.05"} 1"""
            text shouldContain
                """sql_execution_duration_seconds_bucket{outcome="success",purpose="table",script="monthly_trend",le="0.025"} 0"""
            text shouldContain "sql_execution_duration_seconds_count"
            text shouldContain "sql_execution_duration_seconds_sum"
            text shouldContain """http_requests_total{method="GET",path="/healthz",status="200"} 1"""
            text shouldContain """path="/scripts/{name}""""
            text shouldContain "http_request_duration_seconds_bucket"
            text shouldContain "http_requests_in_flight"
            text shouldContain """auth_attempts_total{outcome="invalid_credentials",provider="ldap"} 1"""
            text shouldContain """script_runs_total{outcome="success",script="monthly_trend"} 2"""
            text shouldContain """build_info{version="test"} 1"""
            text shouldContain "auth_enabled 1"
            // Unknown paths never create per-path series (bounded cardinality).
            text shouldNotContain "definitely-not-a-route"
            text shouldContain """path="unmatched""""
        }
    })
