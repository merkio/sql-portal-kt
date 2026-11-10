package io.merkio.sqlportal.domain.access

import io.merkio.sqlportal.domain.common.DomainException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.SortedSet

@Serializable
enum class AuthProvider(
    val label: String,
) {
    @SerialName("ldap")
    LDAP("ldap"),

    @SerialName("oidc")
    OIDC("oidc"),

    /** Authentication is switched off in the configuration. */
    @SerialName("disabled")
    DISABLED("disabled"),
}

/** An identity asserted by an authentication provider. */
@Serializable
data class User(
    val subject: String,
    val displayName: String,
    val email: String? = null,
    val groups: List<String> = emptyList(),
    val provider: AuthProvider,
)

/** What an identity is granted: its roles and the permissions they carry. */
data class Grants(
    val roles: SortedSet<String> = sortedSetOf(),
    val permissions: PermissionSet = PermissionSet(),
)

/**
 * An authenticated user together with the roles and permissions resolved for
 * them.
 */
data class Principal(
    val user: User,
    val roles: SortedSet<String>,
    val permissions: PermissionSet,
) {
    fun can(permission: Permission) = permissions.grants(permission)

    /** `true` when every permission is granted (an empty list is always allowed). */
    fun canAll(permissions: List<Permission>) = permissions.all(::can)

    companion object {
        /** The principal used when authentication is disabled: full access. */
        fun anonymousAdmin() =
            Principal(
                User("anonymous", "Anonymous", provider = AuthProvider.DISABLED),
                sortedSetOf(),
                PermissionSet.all(),
            )
    }
}

/**
 * Maps users and their groups to roles, and roles to permissions.
 *
 * Matching of group and user names is case-insensitive so that LDAP DNs and
 * IdP group names behave predictably.
 */
class AccessPolicy(
    private val roles: Map<String, List<String>> = emptyMap(),
    groupRoles: Map<String, List<String>> = emptyMap(),
    userRoles: Map<String, List<String>> = emptyMap(),
    private val defaultRoles: List<String> = emptyList(),
) {
    private val groupRoles = groupRoles.mapKeys { it.key.trim().lowercase() }
    private val userRoles = userRoles.mapKeys { it.key.trim().lowercase() }

    init {
        // Every referenced role must be defined.
        val referenced = this.groupRoles.values.flatten() + this.userRoles.values.flatten() + defaultRoles
        referenced.firstOrNull { it !in roles }?.let { role ->
            throw DomainException(
                "role '$role' is referenced in the access configuration but not defined under 'roles'",
            )
        }
    }

    /** Roles and permissions this policy grants to a user. */
    fun grants(user: User): Grants {
        val assigned = sortedSetOf<String>()
        assigned += defaultRoles
        userRoles[user.subject.trim().lowercase()]?.let { assigned += it }
        for (group in user.groups) {
            groupRoles[group.trim().lowercase()]?.let { assigned += it }
        }
        return Grants(assigned, PermissionSet(assigned.flatMap { roles[it].orEmpty() }))
    }

    /** The principal for an authenticated user. */
    fun resolve(user: User): Principal {
        val grants = grants(user)
        return Principal(user, grants.roles, grants.permissions)
    }
}

/** Failures of authentication and of loading grants. */
sealed class AuthException(
    message: String,
    cause: Throwable?,
) : RuntimeException(message, cause) {
    /** Short, low-cardinality label used for metrics. */
    abstract val outcome: String

    class InvalidCredentials(
        cause: Throwable? = null,
    ) : AuthException("invalid credentials", cause) {
        override val outcome = "invalid_credentials"
    }

    class Disabled : AuthException("authentication is disabled", null) {
        override val outcome = "not_enabled"
    }

    class ProviderNotEnabled : AuthException("authentication provider is not enabled", null) {
        override val outcome = "not_enabled"
    }

    class Rejected(
        val reason: String,
        cause: Throwable? = null,
    ) : AuthException("authentication request rejected: $reason", cause) {
        override val outcome = "rejected"
    }

    class ProviderUnavailable(
        val reason: String,
        cause: Throwable? = null,
    ) : AuthException("authentication provider unavailable: $reason", cause) {
        override val outcome = "unavailable"
    }
}
