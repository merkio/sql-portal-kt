package io.merkio.sqlportal.domain.access

import io.merkio.sqlportal.domain.scripts.ScriptDefinition
import kotlinx.serialization.Serializable

/** Verifies a username/password pair against a directory (LDAP). */
fun interface CredentialAuthenticator {
    /** @throws AuthException */
    suspend fun authenticate(
        username: String,
        password: String,
    ): User
}

/** Transient values that must survive the redirect to the identity provider. */
@Serializable
data class OidcPending(
    val state: String,
    val nonce: String,
    val pkceVerifier: String,
)

data class OidcAuthorization(
    val redirectUrl: String,
    val pending: OidcPending,
)

/** An OpenID Connect relying-party flow (authorization code + PKCE). */
interface OidcProvider {
    /** @throws AuthException */
    suspend fun begin(): OidcAuthorization

    /** @throws AuthException */
    suspend fun complete(
        code: String,
        pending: OidcPending,
    ): User

    /** Checks that the provider can be reached (discovery succeeds); throws when it cannot. */
    suspend fun probe()
}

/**
 * Where roles and permissions come from: the local access configuration or an
 * external service. Failures must be reported, never answered with a guess.
 */
fun interface GrantSource {
    /** @throws AuthException.ProviderUnavailable when the grants cannot be loaded. */
    suspend fun grantsFor(user: User): Grants
}

/** Grants computed from the local access configuration. */
class LocalGrants(
    private val policy: AccessPolicy,
) : GrantSource {
    override suspend fun grantsFor(user: User) = policy.grants(user)
}

/**
 * Decides whether a principal may use a script. The default implementation
 * checks permissions; a deployment can supply its own through the wiring overrides.
 */
fun interface ScriptAccessRule {
    fun canAccess(
        principal: Principal,
        script: ScriptDefinition,
    ): Boolean
}
