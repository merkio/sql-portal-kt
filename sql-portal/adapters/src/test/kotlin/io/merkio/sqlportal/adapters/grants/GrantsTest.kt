package io.merkio.sqlportal.adapters.grants

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.merkio.sqlportal.adapters.TestServer
import io.merkio.sqlportal.common.tls.TlsOptions
import io.merkio.sqlportal.domain.access.AuthException
import io.merkio.sqlportal.domain.access.AuthProvider
import io.merkio.sqlportal.domain.access.GrantSource
import io.merkio.sqlportal.domain.access.Grants
import io.merkio.sqlportal.domain.access.Permission
import io.merkio.sqlportal.domain.access.PermissionSet
import io.merkio.sqlportal.domain.access.User
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class GrantsTest :
    FunSpec({
        fun user(
            subject: String,
            vararg groups: String,
        ) = User(subject, subject, "$subject@example.org", groups.toList(), AuthProvider.OIDC)

        fun Grants.can(p: String) = permissions.grants(Permission.of(p))

        context("cache") {
            class Source : GrantSource {
                val calls = AtomicInteger()
                val fail = AtomicBoolean()

                override suspend fun grantsFor(user: User): Grants {
                    calls.incrementAndGet()
                    if (fail.get()) throw AuthException.ProviderUnavailable("down")
                    return Grants(sortedSetOf(), PermissionSet(listOf("scripts.sales.read")))
                }
            }

            test("fresh entries do not reach the source") {
                val source = Source()
                val cache = CachedGrants(source, 60.seconds)
                val first = cache.grantsFor(user("alice", "Analysts"))
                cache.grantsFor(user("alice", "Analysts")) shouldBe first
                first.can("scripts.sales.read") shouldBe true
                source.calls.get() shouldBe 1
            }

            test("group membership is part of the key, case-insensitively") {
                val source = Source()
                val cache = CachedGrants(source, 60.seconds)
                cache.grantsFor(user("alice", "a"))
                cache.grantsFor(user("ALICE", "A"))
                cache.grantsFor(user("alice", "a", "b"))
                source.calls.get() shouldBe 2
            }

            test("expired entries are refreshed") {
                val clock = TestTimeSource()
                val source = Source()
                val cache = CachedGrants(source, 20.milliseconds, clock)
                cache.grantsFor(user("bob"))
                clock += 30.milliseconds
                cache.grantsFor(user("bob"))
                source.calls.get() shouldBe 2
            }

            test("an outage is bridged by recent entries only") {
                val clock = TestTimeSource()
                val source = Source()
                val cache = CachedGrants(source, 50.milliseconds, clock)
                cache.grantsFor(user("carol"))
                source.fail.set(true)
                clock += 60.milliseconds
                cache.grantsFor(user("carol")).can("scripts.sales.read") shouldBe true
                clock += 60.milliseconds
                shouldThrow<AuthException.ProviderUnavailable> { cache.grantsFor(user("carol")) }
            }

            test("failures with nothing cached are errors and are not cached") {
                val source = Source()
                val cache = CachedGrants(source, 60.seconds)
                source.fail.set(true)
                shouldThrow<AuthException.ProviderUnavailable> { cache.grantsFor(user("dave")) }
                source.fail.set(false)
                cache.grantsFor(user("dave"))
                source.calls.get() shouldBe 2
            }
        }

        context("remote service") {
            val lookups = AtomicInteger()
            val token = "s3cret"
            val service =
                TestServer {
                    routing {
                        post("/permissions") {
                            lookups.incrementAndGet()
                            if (call.request.header("Authorization") != "Bearer $token") {
                                return@post call.respond(HttpStatusCode.Unauthorized)
                            }
                            val body = call.receiveText()
                            when {
                                "\"subject\":\"alice\"" in body && "\"display_name\":\"alice\"" in body ->
                                    call.respondText(
                                        """{"roles":["analyst"],"permissions":["scripts.sales.read"],"extra":1}""",
                                        ContentType.Application.Json,
                                    )
                                "\"subject\":\"bad\"" in body -> call.respond(HttpStatusCode.InternalServerError)
                                else -> call.respond(HttpStatusCode.NotFound)
                            }
                        }
                    }
                }
            val url = service.url("/permissions")
            afterSpec { service.close() }
            beforeEach { lookups.set(0) }

            fun remote(bearer: String? = token) = RemoteGrants(url, bearer, 2.seconds, TlsOptions())

            test("grants are loaded once and then served from the cache") {
                val cache = CachedGrants(remote(), 60.seconds)
                val first = cache.grantsFor(user("alice", "analysts"))
                first.roles shouldContain "analyst"
                first.can("scripts.sales.read") shouldBe true
                cache.grantsFor(user("alice", "analysts")) shouldBe first
                lookups.get() shouldBe 1
            }

            test("unknown identities hold no grants and are cached") {
                val cache = CachedGrants(remote(), 60.seconds)
                cache.grantsFor(user("nobody")).roles.shouldBeEmpty()
                cache.grantsFor(user("nobody"))
                lookups.get() shouldBe 1
            }

            test("a wrong or missing token is an error") {
                shouldThrow<AuthException.ProviderUnavailable> { remote("wrong").grantsFor(user("alice")) }
                shouldThrow<AuthException.ProviderUnavailable> { remote(null).grantsFor(user("alice")) }
            }

            test("server errors are not cached") {
                val cache = CachedGrants(remote(), 60.seconds)
                shouldThrow<AuthException.ProviderUnavailable> { cache.grantsFor(user("bad")) }
                shouldThrow<AuthException.ProviderUnavailable> { cache.grantsFor(user("bad")) }
                lookups.get() shouldBe 2
            }

            test("an unreachable service is an error") {
                shouldThrow<AuthException.ProviderUnavailable> {
                    RemoteGrants(
                        "http://127.0.0.1:9/permissions",
                        token,
                        2.seconds,
                        TlsOptions(),
                    ).grantsFor(user("alice"))
                }
            }
        }
    })
