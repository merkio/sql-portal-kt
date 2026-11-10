package io.merkio.sqlportal.deployment.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.writeText

/** Everything the process needs comes from environment variables: APP_ENV and SCRIPT_PORTAL_CONFIG. */
class FromEnvironmentTest :
    FunSpec({
        val extra = tempdir().toPath().resolve("extra.conf").apply { writeText("ui.title = Overridden") }

        test("the environment comes from APP_ENV and defaults to local") {
            ConfigLoader.fromEnvironment(emptyMap()).environment shouldBe "local"
            ConfigLoader.fromEnvironment(mapOf("APP_ENV" to " Local ")).environment shouldBe "local"
            shouldThrow<ConfigError.Invalid> { ConfigLoader.fromEnvironment(mapOf("APP_ENV" to "staging")) }
                .message shouldContain "unknown environment 'staging'"
        }

        test("a cluster environment needs its variables") {
            shouldThrow<ConfigError.MissingEnv> { ConfigLoader.fromEnvironment(mapOf("APP_ENV" to "prod")) }
            val rt =
                ConfigLoader.fromEnvironment(
                    mapOf("APP_ENV" to "prod", "DATABASE_URL" to "sqlite::memory:", "AUTH_ENABLED" to "false"),
                )
            rt.environment shouldBe "prod"
        }

        test("SCRIPT_PORTAL_CONFIG names a file applied on top") {
            ConfigLoader.fromEnvironment(mapOf("SCRIPT_PORTAL_CONFIG" to extra.toString())).ui.title shouldBe
                "Overridden"
            ConfigLoader.fromEnvironment(mapOf("SCRIPT_PORTAL_CONFIG" to " ")).ui.title shouldBe "Script Portal (local)"
            shouldThrow<ConfigError.Io> {
                ConfigLoader.fromEnvironment(
                    mapOf("SCRIPT_PORTAL_CONFIG" to "/no/such.conf"),
                )
            }
        }
    })
