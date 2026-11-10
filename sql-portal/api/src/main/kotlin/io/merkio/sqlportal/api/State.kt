package io.merkio.sqlportal.api

import io.merkio.sqlportal.application.AuthService
import io.merkio.sqlportal.application.ScriptService
import io.micrometer.core.instrument.MeterRegistry

/** Outcome of one dependency check. */
data class CheckResult(
    val name: String,
    val ok: Boolean,
    val critical: Boolean,
    val detail: String? = null,
)

/** Whether the service should receive traffic, and why. */
data class ReadinessReport(
    val ready: Boolean,
    val checks: List<CheckResult>,
)

/** The readiness port behind `/readyz`; the composition root implements it. */
fun interface Readiness {
    suspend fun readiness(): ReadinessReport
}

data class UiSettings(
    val title: String,
    val chartjsUrl: String,
)

/** Dependencies shared by all handlers. */
class AppState(
    val auth: AuthService,
    val scripts: ScriptService,
    val health: Readiness,
    /** Renders the Prometheus exposition text served at the metrics path. */
    val metrics: () -> String,
    val ui: UiSettings,
    /** Where the HTTP metrics are recorded; `null` records nothing. */
    val registry: MeterRegistry? = null,
)

/** Router-level settings that are not part of the shared state. */
data class RouterSettings(
    val metricsEnabled: Boolean = true,
    val metricsPath: String = "/metrics",
    val cookieName: String = "sp_session",
    val cookieSecure: Boolean = false,
    val sessionTtlMinutes: Long = 60,
)
