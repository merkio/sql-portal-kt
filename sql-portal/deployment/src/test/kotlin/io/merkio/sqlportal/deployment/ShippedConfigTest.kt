package io.merkio.sqlportal.deployment

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.merkio.sqlportal.adapters.config.AuthRuntime
import io.merkio.sqlportal.adapters.config.ConfigLoader
import java.nio.file.Path
import kotlin.io.path.readText

/** The configuration files in config/ load and validate. */
class ShippedConfigTest :
    FunSpec({
        val dir = Path.of(System.getProperty("sqlportal.repoRoot", "../..")).resolve("config")

        fun load(
            name: String,
            env: Map<String, String> = emptyMap(),
        ) = ConfigLoader.load(dir.resolve(name).readText(), dir, env)

        test("the demo configuration is valid and has auth switched off") {
            val rt = load("config.demo.conf")
            rt.auth shouldBe AuthRuntime.Disabled
            rt.scripts shouldHaveAtLeastSize 20
        }

        test("the default configuration is valid without environment") {
            load("config.conf").scripts shouldHaveAtLeastSize 20
        }

        test("the example configuration is valid when its secrets are provided") {
            val rt =
                load(
                    "config.example.conf",
                    mapOf(
                        "DATABASE_URL" to "sqlite::memory:",
                        "LDAP_BIND_PASSWORD" to "x",
                        "OIDC_CLIENT_SECRET" to "y",
                    ),
                )
            val auth = rt.auth.shouldBeInstanceOf<AuthRuntime.Enabled>()
            (auth.ldap != null && auth.oidc != null) shouldBe true
        }
    })
