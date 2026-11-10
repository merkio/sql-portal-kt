// `access` section → the GrantSource (roles and permissions per identity).
package io.merkio.sqlportal.deployment

import io.merkio.sqlportal.adapters.config.GrantsRuntime
import io.merkio.sqlportal.adapters.config.RuntimeConfig
import io.merkio.sqlportal.adapters.grants.CachedGrants
import io.merkio.sqlportal.adapters.grants.RemoteGrants
import io.merkio.sqlportal.domain.access.GrantSource
import io.merkio.sqlportal.domain.access.LocalGrants
import org.koin.core.module.Module
import org.koin.dsl.module
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("io.merkio.sqlportal.deployment.Access")

/** The supplied source, the local role maps, or the external grants service behind a TTL cache. */
fun accessModule(
    rt: RuntimeConfig,
    supplied: GrantSource?,
): Module =
    module {
        single<GrantSource> {
            supplied ?: when (val grants = rt.grants) {
                GrantsRuntime.Local -> LocalGrants(rt.policy)
                is GrantsRuntime.External -> {
                    val settings = grants.connection.settings
                    log.info("grants loaded from an external service at {}", settings.url)
                    val remote =
                        RemoteGrants(settings.url, settings.bearerToken, settings.timeout, grants.connection.tls)
                    CachedGrants(remote, settings.cacheTtl)
                }
            }
        }
    }
