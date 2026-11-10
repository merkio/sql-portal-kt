/*
 * Composition root of the SQL Portal.
 *
 * Every part of the application is configured from the validated RuntimeConfig
 * in its own Koin module:
 *
 * | file              | builds                                                 |
 * |-------------------|--------------------------------------------------------|
 * | Database.kt       | the QueryExecutor (SQLite / PostgreSQL / ClickHouse)   |
 * | Access.kt         | the GrantSource (local policy or external service)     |
 * | Auth.kt           | the AuthService with LDAP / OIDC providers             |
 * | Scripts.kt        | the ScriptService with its catalog and access rule     |
 * | Health.kt         | the HealthService with database and OIDC probes        |
 * | Observability.kt  | logging, Prometheus and the Metrics port               |
 *
 * [wire] assembles them; tests replace single adapters through [Overrides].
 */
package io.merkio.sqlportal.deployment

import io.ktor.server.application.Application
import io.merkio.sqlportal.adapters.config.RuntimeConfig
import io.merkio.sqlportal.api.AppState
import io.merkio.sqlportal.api.RouterSettings
import io.merkio.sqlportal.api.UiSettings
import io.merkio.sqlportal.api.portal
import io.merkio.sqlportal.application.AuthMode
import io.merkio.sqlportal.application.AuthService
import io.merkio.sqlportal.application.Metrics
import io.merkio.sqlportal.application.NoopMetrics
import io.merkio.sqlportal.application.ScriptService
import io.merkio.sqlportal.domain.access.GrantSource
import io.merkio.sqlportal.domain.access.ScriptAccessRule
import io.merkio.sqlportal.domain.query.QueryExecutor
import io.micrometer.core.instrument.MeterRegistry
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/** Adapters a caller may replace. Anything left `null` is built from the configuration. */
data class Overrides(
    val executor: QueryExecutor? = null,
    val auth: AuthMode? = null,
    val grants: GrantSource? = null,
    val scriptAccess: ScriptAccessRule? = null,
    val metrics: Metrics? = null,
    /** Probes added after the database and OIDC probes. */
    val extraProbes: List<HealthProbe> = emptyList(),
)

/** The application services, ready to be served. */
class Services(
    val auth: AuthService,
    val scripts: ScriptService,
    val health: HealthService,
    val ui: UiSettings,
    val settings: RouterSettings,
)

/**
 * Builds the object graph. Prepares the database (migrations, init files)
 * through whichever executor is used. Business metrics go to [registry] unless
 * a metrics sink is supplied.
 */
suspend fun wire(
    rt: RuntimeConfig,
    overrides: Overrides = Overrides(),
    registry: MeterRegistry? = null,
): Services {
    val executor = databaseExecutor(rt, overrides.executor)
    val koin =
        koinApplication {
            modules(
                module {
                    single<QueryExecutor> { executor }
                    single<Metrics> { overrides.metrics ?: registry?.let(::PrometheusMetrics) ?: NoopMetrics }
                },
                accessModule(rt, overrides.grants),
                authModule(rt, overrides.auth),
                scriptsModule(rt, overrides.scriptAccess),
                healthModule(overrides.extraProbes),
            )
        }.koin
    return Services(
        auth = koin.get(),
        scripts = koin.get(),
        health = koin.get(),
        ui = UiSettings(rt.ui.title, rt.ui.chartjsUrl),
        settings =
            RouterSettings(
                metricsEnabled = rt.observability.metricsEnabled,
                metricsPath = rt.observability.metricsPath,
                cookieName = rt.server.session.cookieName,
                cookieSecure = rt.server.session.secure,
                sessionTtlMinutes = rt.server.session.ttlMinutes,
            ),
    )
}

/** The Ktor module serving wired services. [metrics] renders the exposition text at the metrics path. */
fun portalModule(
    services: Services,
    metrics: () -> String,
    registry: MeterRegistry? = null,
): Application.() -> Unit =
    {
        portal(
            AppState(services.auth, services.scripts, services.health, metrics, services.ui, registry),
            services.settings,
        )
    }

/** The production application over [registry]: default overrides, metrics served from the registry. */
suspend fun buildApp(
    rt: RuntimeConfig,
    registry: io.micrometer.prometheusmetrics.PrometheusMeterRegistry,
    overrides: Overrides = Overrides(),
): Application.() -> Unit = portalModule(wire(rt, overrides, registry), registry::scrape, registry)
