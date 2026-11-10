package io.merkio.sqlportal.deployment

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class CliTest :
    FunSpec({
        val demo = Path.of(System.getProperty("sqlportal.repoRoot", "../.."), "config", "config.demo.conf").toString()

        test("the configuration path comes from SCRIPT_PORTAL_CONFIG") {
            portalCommand(mapOf("SCRIPT_PORTAL_CONFIG" to demo)::get).parse(listOf("check-config"))
            shouldThrow<ProgramResult> {
                portalCommand(mapOf("SCRIPT_PORTAL_CONFIG" to "/no/such.conf")::get).parse(listOf("check-config"))
            }.statusCode shouldBe 1
        }

        test("--config wins over the environment") {
            portalCommand(
                mapOf("SCRIPT_PORTAL_CONFIG" to "/no/such.conf")::get,
            ).parse(listOf("--config", demo, "check-config"))
        }

        test("healthcheck fails for an address nobody listens on") {
            shouldThrow<ProgramResult> {
                portalCommand { null }.parse(listOf("healthcheck", "--addr", "127.0.0.1:1"))
            }.statusCode shouldBe
                1
        }
    })
