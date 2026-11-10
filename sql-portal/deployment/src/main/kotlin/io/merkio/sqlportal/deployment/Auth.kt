// `auth` section → the AuthService and its identity providers.
package io.merkio.sqlportal.deployment

import io.merkio.sqlportal.adapters.auth.LdapAuthenticator
import io.merkio.sqlportal.adapters.auth.OidcAdapter
import io.merkio.sqlportal.adapters.config.AuthRuntime
import io.merkio.sqlportal.adapters.config.RuntimeConfig
import io.merkio.sqlportal.application.AuthMode
import io.merkio.sqlportal.application.AuthService
import io.merkio.sqlportal.application.Providers
import org.koin.dsl.module
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("io.merkio.sqlportal.deployment.Auth")

/** The authentication mode: the supplied one, or providers built from the configuration. */
fun authModule(
    rt: RuntimeConfig,
    supplied: AuthMode?,
) = module {
    single<AuthMode> { supplied ?: modeFromConfig(rt) }
    single { AuthService(get(), get(), get()) }
}

/** The OIDC provider of an enabled mode; its discovery endpoint is probed by `/readyz`. */
fun AuthMode.oidcProvider() = (this as? AuthMode.Enabled)?.providers?.oidc

private fun modeFromConfig(rt: RuntimeConfig): AuthMode =
    when (val auth = rt.auth) {
        AuthRuntime.Disabled -> {
            log.warn("authentication is DISABLED: every request is served as an anonymous administrator")
            AuthMode.Disabled
        }
        is AuthRuntime.Enabled ->
            AuthMode.Enabled(
                Providers(
                    ldap =
                        auth.ldap?.let {
                            log.info("LDAP authentication enabled: {}", it.settings.url)
                            LdapAuthenticator(it.settings, it.tls)
                        },
                    oidc =
                        auth.oidc?.let {
                            log.info("OIDC authentication enabled: {}", it.settings.issuerUrl)
                            OidcAdapter(it.settings, rt.server.publicUrl, it.tls)
                        },
                ),
            )
    }
