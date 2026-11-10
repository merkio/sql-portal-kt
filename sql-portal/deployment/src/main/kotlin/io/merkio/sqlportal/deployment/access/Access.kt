// `access` section → the GrantSource (roles and permissions per identity).
package io.merkio.sqlportal.deployment.access

import io.github.oshai.kotlinlogging.KotlinLogging
import io.merkio.sqlportal.adapters.grants.CachedGrants
import io.merkio.sqlportal.adapters.grants.ExternalGrantsConfig
import io.merkio.sqlportal.adapters.grants.RemoteGrants
import io.merkio.sqlportal.common.tls.TlsOptions
import io.merkio.sqlportal.domain.access.AccessPolicy
import io.merkio.sqlportal.domain.access.GrantSource
import io.merkio.sqlportal.domain.access.LocalGrants
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.time.Duration

private val log = KotlinLogging.logger {}

/** Role-based access: roles carry permissions; users and groups carry roles. */
@Serializable
data class AccessConfig(
    /** Roles granted to every authenticated user. */
    @SerialName("default_roles") val defaultRoles: List<String> = emptyList(),
    /** role -> permissions (`*` and `prefix.*` wildcards allowed). */
    val roles: Map<String, List<String>> = emptyMap(),
    /** group name or LDAP DN -> roles. */
    @SerialName("group_roles") val groupRoles: Map<String, List<String>> = emptyMap(),
    /** username / OIDC subject -> roles. */
    @SerialName("user_roles") val userRoles: Map<String, List<String>> = emptyMap(),
    /** External grants service; when set it replaces the maps above. */
    val external: ExternalGrantsConfig? = null,
)

/** Where grants come from at runtime. */
sealed interface GrantsRuntime {
    /** The role maps of the configuration ([AccessPolicy]). */
    data object Local : GrantsRuntime

    data class External(
        val url: String,
        val timeout: Duration,
        val cacheTtl: Duration,
        val bearerToken: String?,
        val tls: TlsOptions,
    ) : GrantsRuntime
}

/** The supplied source, the local role maps, or the external grants service behind a TTL cache. */
fun accessModule(
    grants: GrantsRuntime,
    policy: AccessPolicy,
    supplied: GrantSource?,
): Module =
    module {
        single<GrantSource> {
            supplied ?: when (grants) {
                GrantsRuntime.Local -> LocalGrants(policy)
                is GrantsRuntime.External -> {
                    log.info { "grants loaded from an external service at ${grants.url}" }
                    CachedGrants(
                        RemoteGrants(grants.url, grants.bearerToken, grants.timeout, grants.tls),
                        grants.cacheTtl,
                    )
                }
            }
        }
    }
