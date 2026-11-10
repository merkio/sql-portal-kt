package io.merkio.sqlportal.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.merkio.sqlportal.application.AuthMode
import io.merkio.sqlportal.application.AuthService
import io.merkio.sqlportal.application.NoopMetrics
import io.merkio.sqlportal.application.ScriptService
import io.merkio.sqlportal.domain.access.AccessPolicy
import io.merkio.sqlportal.domain.access.LocalGrants
import io.merkio.sqlportal.domain.common.Value
import io.merkio.sqlportal.domain.query.QueryResult
import io.merkio.sqlportal.domain.scripts.ScriptDefinition
import io.merkio.sqlportal.domain.scripts.ScriptName
import io.merkio.sqlportal.testing.FakeExecutor
import io.merkio.sqlportal.testing.InMemoryCatalog
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

class PortalTest :
    FunSpec({
        val registry = SimpleMeterRegistry()
        val state =
            AppState(
                auth = AuthService(AuthMode.Disabled, LocalGrants(AccessPolicy()), NoopMetrics),
                scripts =
                    ScriptService(
                        InMemoryCatalog(
                            listOf(ScriptDefinition.of(ScriptName.of("hello"), "Hello", "SELECT 1 AS one")),
                        ),
                        FakeExecutor.returning(QueryResult(listOf("one"), listOf(listOf(Value.Int(1))))),
                        NoopMetrics,
                    ),
                health = { ReadinessReport(true, listOf(CheckResult("database", true, true))) },
                metrics = { "x 1\n" },
                ui = UiSettings("T", "/static/chart.js"),
                registry = registry,
            )

        test("pages, API, operations and metrics labels") {
            testApplication {
                application { portal(state, RouterSettings()) }
                client.get("/").bodyAsText() shouldContain "href=\"/scripts/hello\""
                client.get("/scripts/hello").bodyAsText() shouldContain "<td class=\"num\">1</td>"
                client.get("/api/scripts/hello").bodyAsText() shouldContain "\"rows\":[[1]]"
                client.get("/healthz").bodyAsText() shouldBe """{"status":"ok"}"""
                client.get("/readyz").bodyAsText() shouldContain "\"status\":\"up\""
                val metrics = client.get("/metrics")
                metrics.headers["Content-Type"]!! shouldStartWith "text/plain; version=0.0.4"
                client.get("/nope/nope").status shouldBe HttpStatusCode.NotFound
            }
            val paths =
                registry
                    .find("http.requests")
                    .counters()
                    .map { it.id.getTag("path") }
                    .toSet()
            paths shouldBe
                setOf("/", "/scripts/{name}", "/api/scripts/{name}", "/healthz", "/readyz", "/metrics", UNMATCHED)
        }
    })
