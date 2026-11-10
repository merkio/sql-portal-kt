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
import io.merkio.sqlportal.adapters.config.ConfigError
import io.merkio.sqlportal.adapters.sql.DatabaseSetupException
import io.merkio.sqlportal.deployment.buildApp
import io.merkio.sqlportal.deployment.portalRegistry

/** The real composition root (`buildApp`) with the shipped configuration files. */
class BootstrapTest :
    FunSpec({
        suspend fun app(text: String = configText()): Application.() -> Unit = buildApp(runtime(text), portalRegistry())

        fun withDemo(replace: Pair<String, String>) = configText().replace(replace.first, replace.second)

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
            val off = app(withDemo("auth {" to "observability.metrics_enabled = false\nauth {"))
            testApplication {
                application(off)
                client.get("/metrics").status shouldBe HttpStatusCode.NotFound
            }
            val moved = app(withDemo("auth {" to "observability.metrics_path = /prom\nauth {"))
            testApplication {
                application(moved)
                client.get("/prom").status shouldBe HttpStatusCode.OK
                client.get("/metrics").status shouldBe HttpStatusCode.NotFound
            }
        }

        test("enabling auth without providers fails before any adapter starts") {
            shouldThrow<ConfigError> { runtime(withDemo("enabled = false" to "enabled = true")) }.message shouldContain
                "neither auth.ldap nor auth.oidc"
        }

        test("an unreachable database fails startup") {
            shouldThrow<DatabaseSetupException> {
                app(
                    withDemo(
                        "\"sqlite::memory:\"" to "\"sqlite:///nonexistent-dir/x.db?mode=ro\"",
                    ),
                )
            }
        }

        test("production wiring builds every configured adapter without connecting") {
            // LDAP, OIDC and the grants service point at a closed port: wiring must
            // succeed, and only the optional OIDC probe reports down.
            val text =
                withDemo(
                    "auth {\n  enabled = false" to
                        """
                        auth {
                          enabled = true
                          ldap { url = "ldap://127.0.0.1:1", bind_dn = "cn=svc", bind_password = p, base_dn = "dc=example,dc=org" }
                          oidc { issuer_url = "https://127.0.0.1:1", client_id = portal }
                        """.trimIndent(),
                ).replace(
                    "access {\n",
                    "access {\n  external { url = \"https://127.0.0.1:1/grants\", cache_ttl_secs = 60 }\n",
                )
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
            configText() shouldContain "migrations {\n    enabled = true"
            val disabled = app(withDemo("migrations {\n    enabled = true" to "migrations {\n    enabled = false"))
            testApplication {
                application(disabled)
                client.get("/scripts/monthly_trend").status shouldNotBe HttpStatusCode.OK
            }
        }

        test("enabled migrations need an existing directory") {
            shouldThrow<ConfigError> {
                runtime(withDemo("dir = migrations/sqlite" to "dir = migrations/does-not-exist"))
            }.message shouldContain
                "not a directory"
        }
    })
