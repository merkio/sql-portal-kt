// The `auth.ldap` and `auth.oidc` sections of the configuration.
package io.merkio.sqlportal.adapters.auth

import io.merkio.sqlportal.common.tls.TlsConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * LDAP sign-in. The connection settings may stay empty while the provider is
 * disabled, so an environment can carry the block and switch it on by variable.
 */
@Serializable
data class LdapConfig(
    val enabled: Boolean = true,
    /** Overrides the shared `tls` block for this directory. */
    val tls: TlsConfig? = null,
    /** `ldap://host:389` or `ldaps://host:636`. */
    val url: String = "",
    @SerialName("start_tls") val startTls: Boolean = false,
    @SerialName("bind_dn") val bindDn: String = "",
    @SerialName("bind_password") val bindPassword: String = "",
    @SerialName("base_dn") val baseDn: String = "",
    /** `{username}` is replaced by the escaped login name. */
    @SerialName("user_filter") val userFilter: String = "(&(objectClass=person)(uid={username}))",
    @SerialName("username_attribute") val usernameAttribute: String = "uid",
    @SerialName("display_name_attribute") val displayNameAttribute: String = "cn",
    @SerialName("email_attribute") val emailAttribute: String = "mail",
    /** Attribute on the user entry listing group DNs. Empty = none. */
    @SerialName("group_attribute") val groupAttribute: String = "memberOf",
    /** Optional group search for servers without `memberOf`. */
    @SerialName("group_search") val groupSearch: LdapGroupSearch? = null,
    @SerialName("timeout_secs") val timeoutSecs: Long = DEFAULT_TIMEOUT_SECS,
) {
    /** The connection is encrypted (`ldaps://` or StartTLS). */
    val usesTls: Boolean get() = url.startsWith("ldaps://") || startTls

    companion object {
        const val DEFAULT_TIMEOUT_SECS = 5L
    }
}

@Serializable
data class LdapGroupSearch(
    @SerialName("base_dn") val baseDn: String,
    /** `{user_dn}` and `{username}` are replaced by escaped values. */
    val filter: String = "(&(objectClass=groupOfNames)(member={user_dn}))",
    @SerialName("name_attribute") val nameAttribute: String = "cn",
)

/** OIDC sign-in (authorization code flow with PKCE). */
@Serializable
data class OidcConfig(
    val enabled: Boolean = true,
    /** Overrides the shared `tls` block for this provider. */
    val tls: TlsConfig? = null,
    @SerialName("issuer_url") val issuerUrl: String = "",
    @SerialName("client_id") val clientId: String = "",
    @SerialName("client_secret") val clientSecret: String? = null,
    /** Requested in addition to `openid`, which is always requested. */
    val scopes: List<String> = listOf("profile", "email"),
    @SerialName("username_claim") val usernameClaim: String = "preferred_username",
    @SerialName("groups_claim") val groupsClaim: String = "groups",
)
