package io.merkio.sqlportal.deployment

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.milliseconds

class ObservabilityTest :
    FunSpec({
        test("prometheus metrics emit the expected series") {
            val registry = portalRegistry()
            PrometheusMetrics(registry).authAttempt("ldap", "success")
            PrometheusMetrics(registry).scriptRun("sales", "success", 30.milliseconds, 4)
            describeAndInit(registry, "1.2.3", true)
            val text = registry.scrape()
            text shouldContain """auth_attempts_total{outcome="success",provider="ldap"} 1"""
            text shouldContain """script_runs_total{outcome="success",script="sales"} 1"""
            text shouldContain "script_run_duration_seconds_bucket"
            text shouldContain """script_rows_returned_total{script="sales"} 4"""
            text shouldContain """build_info{version="1.2.3"} 1"""
            text shouldContain "auth_enabled 1"
        }
    })
