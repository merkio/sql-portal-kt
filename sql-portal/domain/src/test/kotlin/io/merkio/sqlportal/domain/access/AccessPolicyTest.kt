package io.merkio.sqlportal.domain.access

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.merkio.sqlportal.domain.common.DomainException

class AccessPolicyTest :
    FunSpec({
        fun user(
            subject: String,
            vararg groups: String,
        ) = User(subject, subject, groups = groups.toList(), provider = AuthProvider.LDAP)

        val policy =
            AccessPolicy(
                roles =
                    mapOf(
                        "admin" to listOf("*"),
                        "analyst" to listOf("scripts.sales.*"),
                        "viewer" to listOf("scripts.public.read"),
                    ),
                groupRoles = mapOf("CN=Analysts,OU=Groups" to listOf("analyst")),
                userRoles = mapOf("Alice" to listOf("admin")),
                defaultRoles = listOf("viewer"),
            )

        test("default roles apply to everyone") {
            val p = policy.resolve(user("bob"))
            p.roles shouldContain "viewer"
            p.can(Permission.of("scripts.public.read")) shouldBe true
            p.can(Permission.of("scripts.sales.read")) shouldBe false
        }

        test("group mapping is case-insensitive") {
            val p = policy.resolve(user("bob", "cn=analysts,ou=groups"))
            p.roles shouldContain "analyst"
            p.can(Permission.of("scripts.sales.read")) shouldBe true
        }

        test("user mapping is case-insensitive") {
            val p = policy.resolve(user("ALICE"))
            p.roles shouldContain "admin"
            p.can(Permission.of("whatever")) shouldBe true
        }

        test("unknown group grants nothing extra") {
            policy.resolve(user("carol", "cn=other")).roles shouldHaveSize 1
        }

        test("rejects undefined role references") {
            shouldThrow<DomainException> {
                AccessPolicy(groupRoles = mapOf("g" to listOf("ghost")))
            }.message shouldContain "ghost"
            shouldThrow<DomainException> { AccessPolicy(defaultRoles = listOf("x")) }.message shouldContain "'x'"
        }

        test("the anonymous administrator may do anything") {
            Principal.anonymousAdmin().canAll(listOf(Permission.of("a"), Permission.of("b"))) shouldBe true
        }
    })
