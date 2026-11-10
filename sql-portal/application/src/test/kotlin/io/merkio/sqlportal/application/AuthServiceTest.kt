package io.merkio.sqlportal.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.merkio.sqlportal.domain.access.AccessPolicy
import io.merkio.sqlportal.domain.access.AuthException
import io.merkio.sqlportal.domain.access.AuthProvider
import io.merkio.sqlportal.domain.access.GrantSource
import io.merkio.sqlportal.domain.access.Grants
import io.merkio.sqlportal.domain.access.LocalGrants
import io.merkio.sqlportal.domain.access.Permission
import io.merkio.sqlportal.domain.access.PermissionSet
import io.merkio.sqlportal.domain.access.User
import io.merkio.sqlportal.testing.FakeLdap
import io.merkio.sqlportal.testing.FakeOidc

class AuthServiceTest :
    FunSpec({
        val policy = AccessPolicy(roles = mapOf("r" to listOf("x.read")), defaultRoles = listOf("r"))

        fun service(
            enabled: Boolean,
            metrics: Metrics = NoopMetrics,
        ): AuthService {
            val mode =
                if (enabled) {
                    AuthMode.Enabled(Providers(FakeLdap("alice", "pw"), FakeOidc()))
                } else {
                    AuthMode.Disabled
                }
            return AuthService(mode, LocalGrants(policy), metrics)
        }

        fun user(subject: String) = User(subject, subject, provider = AuthProvider.LDAP)

        test("disabled auth serves the anonymous administrator") {
            val s = service(enabled = false)
            s.principal(null).shouldNotBeNull().can(Permission.of("any")) shouldBe true
            s.ldapEnabled shouldBe false
            s.oidcEnabled shouldBe false
            shouldThrow<AuthException.Disabled> { s.loginLdap("alice", "pw") }
            shouldThrow<AuthException.Disabled> { s.oidcBegin() }
        }

        test("enabled auth requires a session user") {
            service(enabled = true).principal(null).shouldBeNull()
        }

        test("the principal takes its grants from the grant source") {
            val grants = Grants(sortedSetOf("analyst"), PermissionSet(listOf("scripts.sales.read")))
            val s = AuthService(AuthMode.Enabled(Providers()), { grants }, NoopMetrics)
            val p = s.principal(user("zoe")).shouldNotBeNull()
            p.roles shouldContain "analyst"
            p.can(Permission.of("scripts.sales.read")) shouldBe true
        }

        test("an unavailable grant source denies everything") {
            val failing = GrantSource { throw AuthException.ProviderUnavailable("down") }
            val p = AuthService(AuthMode.Enabled(Providers()), failing, NoopMetrics).principal(user("zoe"))
            p.shouldNotBeNull().roles.shouldBeEmpty()
            p.can(Permission.of("scripts.sales.read")) shouldBe false
        }

        test("ldap login success and failure are recorded") {
            val metrics = RecordingMetrics()
            val s = service(enabled = true, metrics)
            s.loginLdap("alice", "pw").subject shouldBe "alice"
            shouldThrow<AuthException.InvalidCredentials> { s.loginLdap("alice", "bad") }
            metrics.auth shouldBe listOf("ldap" to "success", "ldap" to "invalid_credentials")
        }

        test("the principal resolves default roles") {
            val s = service(enabled = true)
            s.principal(s.loginLdap("alice", "pw")).shouldNotBeNull().roles shouldContain "r"
        }

        test("a missing provider is reported") {
            val s = AuthService(AuthMode.Enabled(Providers()), LocalGrants(policy), NoopMetrics)
            shouldThrow<AuthException.ProviderNotEnabled> { s.loginLdap("a", "b") }
            shouldThrow<AuthException.ProviderNotEnabled> { s.oidcBegin() }
        }

        test("the oidc flow checks the state") {
            val metrics = RecordingMetrics()
            val s = service(enabled = true, metrics)
            val auth = s.oidcBegin()
            shouldThrow<AuthException.Rejected> { s.oidcComplete(OidcCallback("c", "wrong"), auth.pending) }
            s.oidcComplete(OidcCallback("c", auth.pending.state), auth.pending).provider shouldBe AuthProvider.OIDC
            metrics.auth shouldBe listOf("oidc" to "rejected", "oidc" to "success")
        }

        test("constant time equality") {
            constantTimeEquals("abc", "abc") shouldBe true
            constantTimeEquals("abc", "abd") shouldBe false
            constantTimeEquals("abc", "ab") shouldBe false
        }
    })
