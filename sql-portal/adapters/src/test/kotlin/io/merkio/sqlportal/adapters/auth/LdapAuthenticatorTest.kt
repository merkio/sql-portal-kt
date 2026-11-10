package io.merkio.sqlportal.adapters.auth

import com.unboundid.ldap.listener.InMemoryDirectoryServer
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.merkio.sqlportal.adapters.config.LdapConfig
import io.merkio.sqlportal.adapters.config.LdapGroupSearch
import io.merkio.sqlportal.common.tls.TlsOptions
import io.merkio.sqlportal.domain.access.AuthException
import io.merkio.sqlportal.domain.access.AuthProvider

class LdapAuthenticatorTest :
    FunSpec({
        val base = LdapConfig(url = "ldap://x", bindDn = "cn=svc", bindPassword = "p", baseDn = "dc=example,dc=org")

        test("the filter escapes special characters") {
            val f = buildUserFilter("(&(objectClass=person)(uid={username}))", "a*)(uid=*")
            f shouldNotContain "a*)(uid=*"
            f shouldContain """a\2a\29\28uid=\2a"""
            buildUserFilter("(uid={username})", "alice") shouldBe "(uid=alice)"
        }

        test("the CN is taken from a DN") {
            cnFromDn("cn=Admins,ou=groups,dc=x") shouldBe "Admins"
            cnFromDn("CN = ops ,ou=g") shouldBe "ops"
            cnFromDn("ou=groups,dc=x").shouldBeNull()
            cnFromDn("garbage").shouldBeNull()
        }

        test("an entry maps to a user with CN and DN groups") {
            val u =
                userFromEntry(
                    mapOf(
                        "uid" to listOf("alice"),
                        "cn" to listOf("Alice Liddell"),
                        "mail" to listOf("alice@example.org"),
                        "memberOf" to listOf("cn=analysts,ou=groups,dc=example,dc=org", "ou=odd,dc=x"),
                    ),
                    base,
                    "login",
                )
            u.subject shouldBe "alice"
            u.displayName shouldBe "Alice Liddell"
            u.email shouldBe "alice@example.org"
            u.groups shouldBe listOf("analysts", "cn=analysts,ou=groups,dc=example,dc=org", "ou=odd,dc=x")
            u.provider shouldBe AuthProvider.LDAP
        }

        test("attribute lookup is case-insensitive and has fallbacks") {
            val u = userFromEntry(mapOf("MEMBEROF" to listOf("cn=g,dc=x")), base, "bob")
            u.subject shouldBe "bob"
            u.displayName shouldBe "bob"
            u.email.shouldBeNull()
            u.groups shouldContain "g"
            userFromEntry(
                mapOf("memberOf" to listOf("cn=g,dc=x")),
                base.copy(groupAttribute = ""),
                "bob",
            ).groups.shouldBeEmpty()
        }

        test("empty credentials are rejected before any network I/O") {
            val auth = LdapAuthenticator(base, TlsOptions())
            shouldThrow<AuthException.InvalidCredentials> { auth.authenticate("alice", "") }
            shouldThrow<AuthException.InvalidCredentials> { auth.authenticate("  ", "pw") }
        }

        test("an unreachable server is unavailable") {
            val auth = LdapAuthenticator(base.copy(url = "ldap://127.0.0.1:1", timeoutSecs = 1), TlsOptions())
            shouldThrow<AuthException.ProviderUnavailable> { auth.authenticate("alice", "pw") }
        }

        context("against a directory") {
            val directory =
                InMemoryDirectoryServer(
                    InMemoryDirectoryServerConfig("dc=example,dc=org").apply {
                        addAdditionalBindCredentials("cn=svc", "service-secret")
                    },
                )
            directory.add("dn: dc=example,dc=org", "objectClass: domain", "dc: example")
            directory.add("dn: ou=people,dc=example,dc=org", "objectClass: organizationalUnit", "ou: people")
            directory.add("dn: ou=groups,dc=example,dc=org", "objectClass: organizationalUnit", "ou: groups")
            directory.add(
                "dn: uid=alice,ou=people,dc=example,dc=org",
                "objectClass: inetOrgPerson",
                "uid: alice",
                "cn: Alice Liddell",
                "sn: Liddell",
                "mail: alice@example.org",
                "userPassword: wonderland",
            )
            directory.add(
                "dn: cn=analysts,ou=groups,dc=example,dc=org",
                "objectClass: groupOfNames",
                "cn: analysts",
                "member: uid=alice,ou=people,dc=example,dc=org",
            )
            directory.startListening()
            afterSpec { directory.shutDown(true) }

            fun auth(
                bindPassword: String = "service-secret",
                groupSearch: LdapGroupSearch? = LdapGroupSearch(baseDn = "ou=groups,dc=example,dc=org"),
            ) = LdapAuthenticator(
                base.copy(
                    url = "ldap://127.0.0.1:${directory.listenPort}",
                    bindDn = "cn=svc",
                    bindPassword = bindPassword,
                    groupSearch = groupSearch,
                ),
                TlsOptions(),
            )

            test("search then bind signs the user in with groups from the group search") {
                val u = auth().authenticate("alice", "wonderland")
                u.subject shouldBe "alice"
                u.displayName shouldBe "Alice Liddell"
                u.email shouldBe "alice@example.org"
                u.groups shouldContainAll listOf("cn=analysts,ou=groups,dc=example,dc=org", "analysts")
            }

            test("a wrong password or an unknown user are invalid credentials") {
                shouldThrow<AuthException.InvalidCredentials> { auth().authenticate("alice", "nope") }
                shouldThrow<AuthException.InvalidCredentials> { auth().authenticate("mallory", "x") }
                shouldThrow<AuthException.InvalidCredentials> { auth().authenticate("*", "x") }
            }

            test("a broken service account is unavailable, not invalid credentials") {
                shouldThrow<AuthException.ProviderUnavailable> {
                    auth(
                        bindPassword = "wrong",
                    ).authenticate("alice", "wonderland")
                }
            }
        }
    })
