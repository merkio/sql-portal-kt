package io.merkio.sqlportal.deployment.observability

import ch.qos.logback.classic.Level
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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

        test("SQL execution time is a histogram") {
            val registry = portalRegistry()
            PrometheusMetrics(registry).sqlExecuted("sales", "table", "success", 30.milliseconds)
            registry.scrape() shouldContain
                """sql_execution_duration_seconds_bucket{outcome="success",purpose="table",script="sales",le="0.05"} 1"""
        }

        test("logs are text locally and JSON in the cluster environments") {
            logFormat { null } shouldBe "TEXT"
            logFormat(mapOf("APP_ENV" to "local")::get) shouldBe "TEXT"
            for (env in listOf("k8s", "uat", "prod")) logFormat(mapOf("APP_ENV" to env)::get) shouldBe "JSON"
            logFormat(mapOf("APP_ENV" to "prod", "LOG_FORMAT" to "text")::get) shouldBe "TEXT"
            logFormat(mapOf("LOG_FORMAT" to "json")::get) shouldBe "JSON"
        }

        test("log levels apply the configured directives, SCRIPT_PORTAL_LOG first") {
            val context = checkNotNull(logbackContext())
            applyLogLevels("info,io.merkio.test.a=debug") { null }
            context.getLogger("io.merkio.test.a").level shouldBe Level.DEBUG
            applyLogLevels("info") { if (it == "SCRIPT_PORTAL_LOG") "info,io.merkio.test.b=warn" else null }
            context.getLogger("io.merkio.test.b").level shouldBe Level.WARN
        }
    })
