package io.merkio.sqlportal.application

import io.github.oshai.kotlinlogging.KotlinLogging
import io.merkio.sqlportal.domain.access.AuthException
import io.merkio.sqlportal.domain.access.CredentialAuthenticator
import io.merkio.sqlportal.domain.access.GrantSource
import io.merkio.sqlportal.domain.access.Grants
import io.merkio.sqlportal.domain.access.OidcAuthorization
import io.merkio.sqlportal.domain.access.OidcPending
import io.merkio.sqlportal.domain.access.OidcProvider
import io.merkio.sqlportal.domain.access.Principal
import io.merkio.sqlportal.domain.access.User
import java.security.MessageDigest

/** Query parameters returned by the identity provider. */
data class OidcCallback(
    val code: String,
    val state: String,
)

/** The configured identity providers. Absent providers are `null`. */
data class Providers(
    val ldap: CredentialAuthenticator? = null,
    val oidc: OidcProvider? = null,
)

/**
 * Whether authentication is on at all. [Disabled] is the master switch: every
 * request is an anonymous administrator and no provider is consulted.
 */
sealed interface AuthMode {
    data object Disabled : AuthMode

    data class Enabled(
        val providers: Providers,
    ) : AuthMode
}

/** Use cases around authenticating users and resolving their permissions. */
class AuthService(
    private val mode: AuthMode,
    private val grants: GrantSource,
    private val metrics: Metrics,
) {
    private val log = KotlinLogging.logger {}

    /** The single place that turns the master switch into an error. */
    private fun providers(): Providers =
        when (mode) {
            AuthMode.Disabled -> throw AuthException.Disabled()
            is AuthMode.Enabled -> mode.providers
        }

    /** `false` means *all* authorizers are switched off. */
    val isEnabled: Boolean get() = mode is AuthMode.Enabled

    val ldapEnabled: Boolean get() = (mode as? AuthMode.Enabled)?.providers?.ldap != null

    val oidcEnabled: Boolean get() = (mode as? AuthMode.Enabled)?.providers?.oidc != null

    /**
     * Resolves the principal for a request, asking the grant source for the
     * user's grants. With authentication disabled every request is served as
     * an anonymous administrator. If the source cannot answer, the user is
     * denied everything (fail closed) and the failure is logged.
     */
    suspend fun principal(sessionUser: User?): Principal? {
        if (!isEnabled) return Principal.anonymousAdmin()
        val user = sessionUser ?: return null
        val granted =
            try {
                grants.grantsFor(user)
            } catch (e: AuthException) {
                log.warn { "grants unavailable for ${user.subject}; denying access: ${e.message}" }
                Grants()
            }
        return Principal(user, granted.roles, granted.permissions)
    }

    /** @throws AuthException */
    suspend fun loginLdap(
        username: String,
        password: String,
    ): User {
        val ldap = providers().ldap ?: throw AuthException.ProviderNotEnabled()
        return recorded("ldap") { ldap.authenticate(username, password) }
    }

    /** @throws AuthException */
    suspend fun oidcBegin(): OidcAuthorization {
        val oidc = providers().oidc ?: throw AuthException.ProviderNotEnabled()
        return oidc.begin()
    }

    /**
     * Completes the sign-in. The returned state must match the one issued by
     * [oidcBegin] (CSRF protection).
     *
     * @throws AuthException
     */
    suspend fun oidcComplete(
        callback: OidcCallback,
        pending: OidcPending,
    ): User {
        val oidc = providers().oidc ?: throw AuthException.ProviderNotEnabled()
        return recorded("oidc") {
            if (!constantTimeEquals(callback.state, pending.state)) throw AuthException.Rejected("state mismatch")
            oidc.complete(callback.code, pending)
        }
    }

    private inline fun recorded(
        provider: String,
        attempt: () -> User,
    ): User =
        try {
            attempt().also {
                metrics.authAttempt(provider, "success")
                log.info { "$provider login succeeded for ${it.subject}" }
            }
        } catch (e: AuthException) {
            metrics.authAttempt(provider, e.outcome)
            log.warn { "$provider login failed: ${e.message}" }
            throw e
        }
}

internal fun constantTimeEquals(
    a: String,
    b: String,
): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
