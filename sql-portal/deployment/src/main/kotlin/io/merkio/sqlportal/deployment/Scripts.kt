// `scripts` section → the ScriptService with its catalog and access rule.
package io.merkio.sqlportal.deployment

import io.merkio.sqlportal.adapters.config.RuntimeConfig
import io.merkio.sqlportal.application.RequiredPermissions
import io.merkio.sqlportal.application.ScriptService
import io.merkio.sqlportal.domain.access.ScriptAccessRule
import io.merkio.sqlportal.domain.scripts.ConfigScriptCatalog
import io.merkio.sqlportal.domain.scripts.ScriptCatalog
import org.koin.dsl.module
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("io.merkio.sqlportal.deployment.Scripts")

/** The access rule defaults to "the caller holds every permission the script lists". */
fun scriptsModule(
    rt: RuntimeConfig,
    access: ScriptAccessRule?,
) = module {
    single<ScriptCatalog> {
        log.info("{} scripts loaded", rt.scripts.size)
        ConfigScriptCatalog(rt.scripts)
    }
    single<ScriptAccessRule> { access ?: RequiredPermissions }
    single { ScriptService(get(), get(), get(), get()) }
}
