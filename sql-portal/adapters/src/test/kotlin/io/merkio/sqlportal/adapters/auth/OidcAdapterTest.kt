package io.merkio.sqlportal.adapters.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.request.header
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.merkio.sqlportal.adapters.TestServer
import io.merkio.sqlportal.adapters.config.OidcConfig
import io.merkio.sqlportal.common.tls.TlsOptions
import io.merkio.sqlportal.domain.access.AuthException
import io.merkio.sqlportal.domain.access.AuthProvider
import java.security.MessageDigest
import java.util.Base64
import java.util.Date

class OidcAdapterTest :
    FunSpec({
        val cfg = OidcConfig(issuerUrl = "https://idp.example.org", clientId = "portal")

        test("claims with a group array map to a user") {
            val u =
                userFromClaims(
                    mapOf(
                        "sub" to "123",
                        "preferred_username" to "alice",
                        "name" to "Alice L.",
                        "email" to "alice@example.org",
                        "groups" to listOf("analysts", "ops"),
                    ),
                    cfg,
                )!!
            u.subject shouldBe "alice"
            u.displayName shouldBe "Alice L."
            u.email shouldBe "alice@example.org"
            u.groups shouldBe listOf("analysts", "ops")
            u.provider shouldBe AuthProvider.OIDC
        }

        test("falls back to sub and splits string groups") {
            val u = userFromClaims(mapOf("sub" to "123", "groups" to "a, b,,c"), cfg)!!
            u.subject shouldBe "123"
            u.displayName shouldBe "123"
            u.groups shouldBe listOf("a", "b", "c")
        }

        test("custom claim names are honoured and a subject is required") {
            val custom = cfg.copy(usernameClaim = "email", groupsClaim = "roles")
            val u = userFromClaims(mapOf("sub" to "1", "email" to "a@b.c", "roles" to listOf("x")), custom)!!
            u.subject shouldBe "a@b.c"
            u.groups shouldBe listOf("x")
            userFromClaims(mapOf("name" to "x"), cfg).shouldBeNull()
        }

        test("the redirect URL is built from the public URL") {
            OidcAdapter(cfg, "https://portal.example.org/", TlsOptions()).redirectUrl shouldBe
                "https://portal.example.org/auth/callback"
        }

        test("an unreachable issuer is unavailable, not a crash") {
            val adapter = OidcAdapter(cfg.copy(issuerUrl = "http://127.0.0.1:1"), "http://localhost", TlsOptions())
            shouldThrow<AuthException.ProviderUnavailable> { adapter.begin() }
            shouldThrow<AuthException.ProviderUnavailable> { adapter.probe() }
        }

        context("against an identity provider") {
            val key: RSAKey = RSAKeyGenerator(2048).keyID("k1").generate()
            var issuer = ""
            var nonceOverride: String? = null
            var audience = "portal"
            val codes = mutableMapOf<String, Pair<String, String>>() // code -> (challenge, nonce)

            fun idToken(nonce: String): String {
                val now = System.currentTimeMillis()
                val claims =
                    JWTClaimsSet
                        .Builder()
                        .issuer(issuer)
                        .subject("u-1")
                        .audience(audience)
                        .issueTime(Date(now))
                        .expirationTime(Date(now + 60_000))
                        .claim("nonce", nonceOverride ?: nonce)
                        .claim("preferred_username", "alice")
                        .claim("groups", listOf("admins"))
                        .build()
                return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), claims)
                    .apply { sign(RSASSASigner(key)) }
                    .serialize()
            }

            val idp =
                TestServer {
                    routing {
                        get("/.well-known/openid-configuration") {
                            call.respondText(
                                """{"issuer":"$issuer","authorization_endpoint":"$issuer/authorize",
                                "token_endpoint":"$issuer/token","jwks_uri":"$issuer/jwks"}""",
                                ContentType.Application.Json,
                            )
                        }
                        get(
                            "/jwks",
                        ) { call.respondText(JWKSet(key.toPublicJWK()).toString(), ContentType.Application.Json) }
                        post("/token") {
                            val form = call.receiveParameters()
                            val expectedAuth =
                                "Basic " + Base64.getEncoder().encodeToString("portal:s3cret".toByteArray())
                            val (challenge, nonce) =
                                codes[form["code"]]
                                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val verifier = form["code_verifier"].orEmpty()
                            val computed =
                                Base64.getUrlEncoder().withoutPadding().encodeToString(
                                    MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
                                )
                            if (call.request.header("Authorization") != expectedAuth || computed != challenge) {
                                return@post call.respond(HttpStatusCode.Unauthorized)
                            }
                            call.respondText(
                                """{"id_token":"${idToken(nonce)}","token_type":"Bearer"}""",
                                ContentType.Application.Json,
                            )
                        }
                    }
                }
            issuer = idp.url()
            afterSpec { idp.close() }

            val adapter =
                OidcAdapter(cfg.copy(issuerUrl = issuer, clientSecret = "s3cret"), "http://portal", TlsOptions())

            /** Starts a sign-in and lets the provider issue a code for it. */
            suspend fun authorize(): Pair<String, io.merkio.sqlportal.domain.access.OidcPending> {
                val auth = adapter.begin()
                val url = Url(auth.redirectUrl)
                url.toString() shouldStartWith "$issuer/authorize"
                url.parameters["redirect_uri"] shouldBe "http://portal/auth/callback"
                url.parameters["scope"] shouldBe "openid profile email"
                url.parameters["code_challenge_method"] shouldBe "S256"
                val code = "code-${codes.size}"
                codes[code] = url.parameters["code_challenge"]!! to url.parameters["nonce"]!!
                return code to auth.pending
            }

            test("the code flow with PKCE yields a verified user") {
                adapter.probe()
                val (code, pending) = authorize()
                val user = adapter.complete(code, pending)
                user.subject shouldBe "alice"
                user.groups shouldBe listOf("admins")
            }

            test("a wrong PKCE verifier or code is rejected by the provider") {
                val (code, pending) = authorize()
                shouldThrow<AuthException.Rejected> { adapter.complete(code, pending.copy(pkceVerifier = "forged")) }
                shouldThrow<AuthException.Rejected> { adapter.complete("unknown", pending) }
            }

            test("a token with another nonce or audience fails verification") {
                val (code, pending) = authorize()
                nonceOverride = "replayed"
                shouldThrow<AuthException.Rejected> { adapter.complete(code, pending) }.message shouldContain
                    "verification"
                nonceOverride = null
                audience = "someone-else"
                shouldThrow<AuthException.Rejected> { adapter.complete(code, pending) }
                audience = "portal"
            }
        }
    })
