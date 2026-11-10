package io.merkio.sqlportal.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

class MiddlewareTest :
    FunSpec({
        test("the CSP allows only the chart origin") {
            val csp = contentSecurityPolicy("https://cdn.jsdelivr.net/npm/chart.js@4/dist/chart.umd.min.js")
            csp shouldContain "script-src 'self' https://cdn.jsdelivr.net;"
            csp shouldNotContain "unsafe-inline"
            csp shouldContain "frame-ancestors 'none'"
        }

        test("the CSP for a self-hosted library") {
            contentSecurityPolicy("/static/chart.js") shouldContain "script-src 'self';"
        }

        test("sessions expire after inactivity and activity extends them") {
            val clock = TestTimeSource()
            val storage = ExpiringMemoryStorage(10.minutes, clock)
            storage.write("a", "x")
            clock += 9.minutes
            storage.read("a") shouldBe "x"
            clock += 9.minutes
            storage.read("a") shouldBe "x"
            clock += 11.minutes
            shouldThrow<NoSuchElementException> { storage.read("a") }
            storage.write("b", "y")
            storage.invalidate("b")
            shouldThrow<NoSuchElementException> { storage.read("b") }
        }
    })
