package io.merkio.sqlportal.adapters.auth

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.BadJOSEException
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import io.ktor.client.HttpClient
import io.ktor.client.request.basicAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.merkio.sqlportal.adapters.http.httpClient
import io.merkio.sqlportal.common.tls.TlsOptions
import io.merkio.sqlportal.domain.access.AuthException
import io.merkio.sqlportal.domain.access.AuthProvider
import io.merkio.sqlportal.domain.access.OidcAuthorization
import io.merkio.sqlportal.domain.access.OidcPending
import io.merkio.sqlportal.domain.access.OidcProvider
import io.merkio.sqlportal.domain.access.User
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.ParseException
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

/**
 * OpenID Connect relying party: authorization code flow with PKCE, `state`
 * and `nonce`, and ID-token verification (signature against the provider's
 * keys, issuer, audience, expiry, nonce).
 */
class OidcAdapter(
    private val cfg: OidcConfig,
    publicUrl: String,
    tls: TlsOptions,
    private val http: HttpClient = httpClient(tls, requestTimeout = 10.seconds),
) : OidcProvider {
    /** The redirect URI to register at the provider. */
    val redirectUrl = "${publicUrl.trimEnd('/')}/auth/callback"

    private val lock = Mutex()
    private var discovered: Discovery? = null
    private var keys: JWKSet? = null

    @Serializable
    private data class Discovery(
        val issuer: String,
        @SerialName("authorization_endpoint") val authorizationEndpoint: String,
        @SerialName("token_endpoint") val tokenEndpoint: String,
        @SerialName("jwks_uri") val jwksUri: String,
    )

    @Serializable
    private data class TokenResponse(
        @SerialName("id_token") val idToken: String? = null,
    )

    /** Discovers the provider lazily, so an IdP outage at startup does not prevent the service from starting. */
    private suspend fun discovery(): Discovery =
        lock.withLock {
            discovered ?: fetchJson<Discovery>("${cfg.issuerUrl.trimEnd('/')}/.well-known/openid-configuration")
                .also {
                    if (it.issuer.trimEnd('/') != cfg.issuerUrl.trimEnd('/')) {
                        throw AuthException.ProviderUnavailable(
                            "discovery issuer '${it.issuer}' does not match '${cfg.issuerUrl}'",
                        )
                    }
                    discovered = it
                }
        }

    private suspend inline fun <reified T> fetchJson(url: String): T = JSON.decodeFromString<T>(fetchText(url))

    private suspend fun fetchText(url: String): String {
        val response =
            try {
                http.get(url)
            } catch (e: IOException) {
                throw AuthException.ProviderUnavailable(e.message ?: e.toString(), e)
            }
        if (!response.status.isSuccess()) throw AuthException.ProviderUnavailable("$url answered ${response.status}")
        return response.bodyAsText()
    }

    override suspend fun begin(): OidcAuthorization {
        val d =
            try {
                discovery()
            } catch (e: SerializationException) {
                throw AuthException.ProviderUnavailable("invalid discovery document: ${e.message}", e)
            }
        val pending = OidcPending(state = random(), nonce = random(), pkceVerifier = random())
        val url =
            URLBuilder(d.authorizationEndpoint)
                .apply {
                    parameters.append("response_type", "code")
                    parameters.append("client_id", cfg.clientId)
                    parameters.append("redirect_uri", redirectUrl)
                    parameters.append(
                        "scope",
                        (listOf("openid") + cfg.scopes.filter { it != "openid" }).joinToString(" "),
                    )
                    parameters.append("state", pending.state)
                    parameters.append("nonce", pending.nonce)
                    parameters.append("code_challenge", challenge(pending.pkceVerifier))
                    parameters.append("code_challenge_method", "S256")
                }.buildString()
        return OidcAuthorization(url, pending)
    }

    override suspend fun complete(
        code: String,
        pending: OidcPending,
    ): User {
        val d = discovery()
        val response =
            try {
                http.submitForm(
                    d.tokenEndpoint,
                    parameters {
                        append("grant_type", "authorization_code")
                        append("code", code)
                        append("redirect_uri", redirectUrl)
                        append("code_verifier", pending.pkceVerifier)
                        if (cfg.clientSecret == null) append("client_id", cfg.clientId)
                    },
                ) {
                    // client_secret_basic: both parts form-encoded (RFC 6749, 2.3.1).
                    cfg.clientSecret?.let { basicAuth(formEncode(cfg.clientId), formEncode(it)) }
                }
            } catch (e: IOException) {
                throw AuthException.ProviderUnavailable(e.message ?: e.toString(), e)
            }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw AuthException.Rejected("token exchange failed: ${response.status}")
        val idToken =
            try {
                JSON.decodeFromString<TokenResponse>(body).idToken
            } catch (e: SerializationException) {
                throw AuthException.Rejected("token exchange failed: ${e.message}", e)
            } ?: throw AuthException.Rejected("provider returned no ID token")
        val claims = verify(idToken, d, pending.nonce)
        return userFromClaims(claims.toJSONObject(), cfg) ?: throw AuthException.Rejected("ID token has no subject")
    }

    /** Verifies the token; the provider's keys are reloaded once when a key is unknown (rotation). */
    private suspend fun verify(
        token: String,
        d: Discovery,
        nonce: String,
    ): JWTClaimsSet {
        val jwt =
            try {
                SignedJWT.parse(token)
            } catch (e: ParseException) {
                throw AuthException.Rejected("malformed ID token", e)
            }
        val cached = lock.withLock { keys }
        val current = cached ?: loadKeys(d)
        return try {
            process(jwt, current, d, nonce)
        } catch (e: BadJOSEException) {
            if (cached == null) throw rejected(e)
            try {
                process(jwt, loadKeys(d), d, nonce)
            } catch (retry: BadJOSEException) {
                throw rejected(retry)
            }
        } catch (e: JOSEException) {
            throw rejected(e)
        }
    }

    private suspend fun loadKeys(d: Discovery): JWKSet {
        val set =
            try {
                JWKSet.parse(fetchText(d.jwksUri))
            } catch (e: ParseException) {
                throw AuthException.ProviderUnavailable("invalid JWKS: ${e.message}", e)
            }
        lock.withLock { keys = set }
        return set
    }

    private fun process(
        jwt: SignedJWT,
        keys: JWKSet,
        d: Discovery,
        nonce: String,
    ): JWTClaimsSet {
        val processor = DefaultJWTProcessor<SecurityContext>()
        processor.jwsKeySelector = JWSVerificationKeySelector(SIGNING_ALGORITHMS, ImmutableJWKSet(keys))
        processor.jwtClaimsSetVerifier =
            DefaultJWTClaimsVerifier(
                cfg.clientId,
                JWTClaimsSet
                    .Builder()
                    .issuer(d.issuer)
                    .claim("nonce", nonce)
                    .build(),
                setOf("sub", "exp", "iat"),
            )
        return processor.process(jwt, null)
    }

    override suspend fun probe() {
        discovery()
    }

    private fun rejected(e: Exception) = AuthException.Rejected("ID token verification failed: ${e.message}", e)

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val RANDOM = SecureRandom()
        const val RANDOM_BYTES = 32
        val SIGNING_ALGORITHMS =
            setOf(
                JWSAlgorithm.RS256,
                JWSAlgorithm.RS384,
                JWSAlgorithm.RS512,
                JWSAlgorithm.PS256,
                JWSAlgorithm.PS384,
                JWSAlgorithm.PS512,
                JWSAlgorithm.ES256,
                JWSAlgorithm.ES384,
                JWSAlgorithm.ES512,
                JWSAlgorithm.EdDSA,
            )

        fun random(): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(RANDOM_BYTES).also(RANDOM::nextBytes))

        fun challenge(verifier: String): String =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

        fun formEncode(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)
    }
}

/** Maps verified ID-token claims to a domain user; `null` without a subject. */
fun userFromClaims(
    claims: Map<String, Any?>,
    cfg: OidcConfig,
): User? {
    val subject = claims["sub"] as? String ?: return null
    val username = claims[cfg.usernameClaim] as? String ?: subject
    val groups =
        when (val raw = claims[cfg.groupsClaim]) {
            is List<*> -> raw.filterIsInstance<String>()
            is String -> raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
    return User(
        // The readable username is used for role mapping; `sub` stays reachable
        // through user_roles by configuring the claim accordingly.
        subject = username,
        displayName = claims["name"] as? String ?: username,
        email = claims["email"] as? String,
        groups = groups,
        provider = AuthProvider.OIDC,
    )
}
