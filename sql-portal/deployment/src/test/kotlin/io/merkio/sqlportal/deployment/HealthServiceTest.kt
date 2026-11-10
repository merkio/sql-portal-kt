package io.merkio.sqlportal.deployment

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.merkio.sqlportal.testing.FakeExecutor
import io.merkio.sqlportal.testing.FakeOidc
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HealthServiceTest :
    FunSpec({
        class Probe(
            override val name: String,
            private val ok: Boolean,
            override val critical: Boolean = true,
            private val slow: Boolean = false,
        ) : HealthProbe {
            override suspend fun check() {
                if (slow) delay(5.seconds)
                check(ok) { "probe failed" }
            }
        }

        test("ready when all probes pass") {
            val r = HealthService(listOf(Probe("db", true))).readiness()
            r.ready shouldBe true
            r.checks.size shouldBe 1
        }

        test("a critical failure means not ready") {
            val r = HealthService(listOf(Probe("db", false), Probe("oidc", true, critical = false))).readiness()
            r.ready shouldBe false
            r.checks[0].detail shouldBe "probe failed"
        }

        test("a non-critical failure keeps the service ready") {
            val r = HealthService(listOf(Probe("oidc", false, critical = false))).readiness()
            r.ready shouldBe true
            r.checks[0].ok shouldBe false
        }

        test("a slow probe times out") {
            val r = HealthService(listOf(Probe("db", true, slow = true)), 20.milliseconds).readiness()
            r.ready shouldBe false
            r.checks[0].detail shouldBe "timed out"
        }

        test("no probes means ready") {
            HealthService(emptyList()).readiness().ready shouldBe true
        }

        test("the database probe is critical and the OIDC probe is not") {
            DatabaseProbe(FakeExecutor.empty()).let {
                it.check()
                it.critical shouldBe true
                it.name shouldBe "database"
            }
            OidcProbe(FakeOidc()).let {
                it.check()
                it.critical shouldBe false
            }
        }
    })
