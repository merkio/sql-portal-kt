package io.merkio.sqlportal.deployment.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.merkio.sqlportal.deployment.auth.AuthRuntime
import kotlin.io.path.isDirectory

/** The bundled default.conf and the environment files load and validate. */
class ShippedConfigTest :
    FunSpec({
        val cluster =
            mapOf(
                "DATABASE_URL" to "postgres://portal:secret@db:5432/portal",
                "LDAP_ENABLED" to "true",
                "LDAP_URL" to "ldaps://ldap.internal:636",
                "LDAP_BIND_DN" to "cn=svc,dc=x",
                "LDAP_BIND_PASSWORD" to "pw",
                "LDAP_BASE_DN" to "ou=people,dc=x",
                "OIDC_ENABLED" to "true",
                "OIDC_ISSUER_URL" to "https://idp.internal/realms/main",
                "OIDC_CLIENT_SECRET" to "s",
                "PUBLIC_URL" to "https://portal.internal",
            )

        test("local: auth off, in-memory SQLite with the bundled demo migrations and scripts") {
            val rt = ConfigLoader.load("local", vars = emptyMap())
            rt.environment shouldBe "local"
            rt.auth shouldBe AuthRuntime.Disabled
            rt.database.url shouldBe "sqlite::memory:"
            rt.database.maxConnections shouldBe 1
            rt.database.migrations
                .shouldNotBeNull()
                .isDirectory() shouldBe true
            rt.scripts shouldHaveAtLeastSize 20
            rt.scripts.first { it.name.value == "monthly_trend" }.sql shouldContain "SELECT"
        }

        for (env in listOf("k8s", "uat", "prod")) {
            test("$env: valid with the cluster variables, secure cookies, auth on, no migrations") {
                val rt = ConfigLoader.load(env, vars = cluster)
                rt.environment shouldBe env
                rt.database.url shouldBe "postgres://portal:secret@db:5432/portal"
                rt.database.migrations shouldBe null
                rt.server.session.secure shouldBe true
                rt.server.publicUrl shouldBe "https://portal.internal"
                val auth = rt.auth.shouldBeInstanceOf<AuthRuntime.Enabled>()
                auth.ldap
                    .shouldNotBeNull()
                    .settings.bindPassword shouldBe "pw"
                auth.oidc
                    .shouldNotBeNull()
                    .settings.clientId shouldBe "script-portal"
            }

            test("$env: DATABASE_URL is required") {
                shouldThrow<ConfigError.MissingEnv> {
                    ConfigLoader.load(env, vars = cluster - "DATABASE_URL")
                }.name shouldBe "DATABASE_URL"
            }
        }

        test("uat builds on k8s and only changes the title") {
            ConfigLoader.load("uat", vars = cluster).ui.title shouldBe "Script Portal (UAT)"
            ConfigLoader.load("prod", vars = cluster).ui.title shouldBe "Script Portal"
        }

        test("an unknown environment is an error") {
            shouldThrow<ConfigError.Invalid> { ConfigLoader.load("staging", vars = emptyMap()) }.message shouldContain
                "unknown environment 'staging'"
        }

        test("overrides win over the environment and the defaults") {
            val rt = ConfigLoader.load("local", "ui.title = Mine\ndatabase.migrations.enabled = false")
            rt.ui.title shouldBe "Mine"
            rt.database.migrations shouldBe null
            rt.ui.chartjsUrl shouldContain "chart.js"
        }
    })
