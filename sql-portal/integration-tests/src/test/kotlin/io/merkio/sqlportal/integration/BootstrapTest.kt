package io.merkio.sqlportal.integration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import io.merkio.sqlportal.adapters.sql.DatabaseSetupException
import io.merkio.sqlportal.deployment.config.ConfigError
import io.merkio.sqlportal.deployment.observability.portalRegistry
import io.merkio.sqlportal.deployment.wiring.buildApp

/** The real composition root (`buildApp`) with the bundled configuration of the local environment. */
class BootstrapTest :
    FunSpec({
        suspend fun app(overrides: String = ""): Application.() -> Unit = buildApp(runtime(overrides), portalRegistry())

        test("the demo configuration builds a working application") {
            val module = app()
            testApplication {
                application(module)
                client.get("/readyz").status shouldBe HttpStatusCode.OK
                client.get("/scripts/monthly_trend").bodyAsText() shouldContain "2024-06"
                client.get("/metrics").bodyAsText() shouldContain
                    "script_runs_total{outcome=\"success\",script=\"monthly_trend\"}"
            }
        }

        test("metrics can be switched off and moved") {
            val off = app("observability.metrics_enabled = false")
            testApplication {
                application(off)
                client.get("/metrics").status shouldBe HttpStatusCode.NotFound
            }
            val moved = app("observability.metrics_path = /prom")
            testApplication {
                application(moved)
                client.get("/prom").status shouldBe HttpStatusCode.OK
                client.get("/metrics").status shouldBe HttpStatusCode.NotFound
            }
        }

        test("enabling auth without providers fails before any adapter starts") {
            shouldThrow<ConfigError> { runtime("auth.enabled = true") }.message shouldContain
                "neither auth.ldap nor auth.oidc"
        }

        test("an unreachable database fails startup") {
            shouldThrow<DatabaseSetupException> {
                app("database.url = \"sqlite:///nonexistent-dir/x.db?mode=ro\"\ndatabase.migrations.enabled = false")
            }
        }

        test("production wiring builds every configured adapter without connecting") {
            // LDAP, OIDC and the grants service point at a closed port: wiring must
            // succeed, and only the optional OIDC probe reports down.
            val text =
                """
                auth {
                  enabled = true
                  ldap { enabled = true, url = "ldap://127.0.0.1:1", bind_dn = "cn=svc", bind_password = p, base_dn = "dc=example,dc=org" }
                  oidc { enabled = true, issuer_url = "https://127.0.0.1:1", client_id = portal }
                }
                access.external { url = "https://127.0.0.1:1/grants", cache_ttl_secs = 60 }
                """.trimIndent()
            val module = app(text)
            testApplication {
                application(module)
                val r = client.get("/readyz")
                val body = r.bodyAsText()
                r.status shouldBe HttpStatusCode.OK
                body shouldContain "\"name\":\"oidc\""
                body shouldContain "\"status\":\"down\""
            }
        }

        test("migrations run only when enabled explicitly") {
            runtime().database.migrations shouldNotBe null
            val disabled = app("database.migrations.enabled = false")
            testApplication {
                application(disabled)
                client.get("/scripts/monthly_trend").status shouldNotBe HttpStatusCode.OK
            }
        }

        test("enabled migrations need an existing directory") {
            shouldThrow<ConfigError> {
                runtime("database.migrations.dir = migrations/does-not-exist")
            }.message shouldContain
                "not a directory"
        }
    })
