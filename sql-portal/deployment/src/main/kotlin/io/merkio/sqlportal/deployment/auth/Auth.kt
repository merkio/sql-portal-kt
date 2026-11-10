// `auth` section → the AuthService and its identity providers.
package io.merkio.sqlportal.deployment.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import io.merkio.sqlportal.adapters.auth.LdapAuthenticator
import io.merkio.sqlportal.adapters.auth.LdapConfig
import io.merkio.sqlportal.adapters.auth.OidcAdapter
import io.merkio.sqlportal.adapters.auth.OidcConfig
import io.merkio.sqlportal.application.AuthMode
import io.merkio.sqlportal.application.AuthService
import io.merkio.sqlportal.application.Providers
import io.merkio.sqlportal.common.tls.TlsOptions
import kotlinx.serialization.Serializable
import org.koin.dsl.module

private val log = KotlinLogging.logger {}

@Serializable
data class AuthConfig(
    /** Master switch: `false` disables all authorizers and serves everyone as an anonymous administrator. */
    val enabled: Boolean = true,
    val ldap: LdapConfig? = null,
    val oidc: OidcConfig? = null,
)

/** A configured provider with the TLS options that apply to it. */
data class Provider<S>(
    val settings: S,
    val tls: TlsOptions,
)

/** The validated `auth` section: disabled, or the providers that are switched on. */
sealed interface AuthRuntime {
    /** Every request is an anonymous administrator. */
    data object Disabled : AuthRuntime

    data class Enabled(
        val ldap: Provider<LdapConfig>?,
        val oidc: Provider<OidcConfig>?,
    ) : AuthRuntime
}

/** The authentication mode: the supplied one, or providers built from the configuration. */
fun authModule(
    auth: AuthRuntime,
    publicUrl: String,
    supplied: AuthMode?,
) = module {
    single<AuthMode> { supplied ?: modeFromConfig(auth, publicUrl) }
    single { AuthService(get(), get(), get()) }
}

/** The OIDC provider of an enabled mode; its discovery endpoint is probed by `/readyz`. */
fun AuthMode.oidcProvider() = (this as? AuthMode.Enabled)?.providers?.oidc

private fun modeFromConfig(
    auth: AuthRuntime,
    publicUrl: String,
): AuthMode =
    when (auth) {
        AuthRuntime.Disabled -> {
            log.warn { "authentication is DISABLED: every request is served as an anonymous administrator" }
            AuthMode.Disabled
        }
        is AuthRuntime.Enabled ->
            AuthMode.Enabled(
                Providers(
                    ldap =
                        auth.ldap?.let {
                            log.info { "LDAP authentication enabled: ${it.settings.url}" }
                            LdapAuthenticator(it.settings, it.tls)
                        },
                    oidc =
                        auth.oidc?.let {
                            log.info { "OIDC authentication enabled: ${it.settings.issuerUrl}" }
                            OidcAdapter(it.settings, publicUrl, it.tls)
                        },
                ),
            )
    }
