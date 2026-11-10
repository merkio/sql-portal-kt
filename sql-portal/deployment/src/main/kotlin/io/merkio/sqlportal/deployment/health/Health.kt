/*
 * Health checks: the probes, the HealthService that aggregates them, and its
 * wiring. `/healthz` and `/readyz` are served through the api Readiness port.
 */
package io.merkio.sqlportal.deployment.health

import io.merkio.sqlportal.api.CheckResult
import io.merkio.sqlportal.api.Readiness
import io.merkio.sqlportal.api.ReadinessReport
import io.merkio.sqlportal.application.AuthMode
import io.merkio.sqlportal.deployment.auth.oidcProvider
import io.merkio.sqlportal.domain.access.OidcProvider
import io.merkio.sqlportal.domain.query.QueryExecutor
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.koin.dsl.module
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A dependency check used by the readiness endpoint. */
interface HealthProbe {
    val name: String

    /** A failing critical probe makes the service "not ready". */
    val critical: Boolean get() = true

    /** Throws when the dependency is not usable; the message is reported. */
    suspend fun check()
}

/** Critical probe: can the database run `SELECT 1`? */
class DatabaseProbe(
    private val executor: QueryExecutor,
) : HealthProbe {
    override val name = "database"

    override suspend fun check() = executor.ping()
}

/**
 * Non-critical probe: is the OIDC provider reachable? An unreachable IdP only
 * breaks SSO logins, so it must not take the whole pod out of rotation.
 */
class OidcProbe(
    private val provider: OidcProvider,
) : HealthProbe {
    override val name = "oidc"
    override val critical = false

    override suspend fun check() = provider.probe()
}

/** Liveness is trivial (the process answers); readiness aggregates the probes. */
class HealthService(
    private val probes: List<HealthProbe>,
    private val probeTimeout: Duration = 3.seconds,
) : Readiness {
    /** Runs every probe. Only critical failures make the service not ready. */
    override suspend fun readiness(): ReadinessReport {
        val checks =
            probes.map { probe ->
                val detail =
                    try {
                        withTimeout(probeTimeout) { probe.check() }
                        null
                    } catch (_: TimeoutCancellationException) {
                        "timed out"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.message ?: e.toString()
                    }
                CheckResult(probe.name, detail == null, probe.critical, detail)
            }
        return ReadinessReport(checks.all { it.ok || !it.critical }, checks)
    }
}

/** Database probe first, then OIDC discovery (when enabled), then any extras. */
fun healthModule(extra: List<HealthProbe>) =
    module {
        single {
            val probes = mutableListOf<HealthProbe>(DatabaseProbe(get()))
            get<AuthMode>().oidcProvider()?.let { probes += OidcProbe(it) }
            HealthService(probes + extra)
        }
    }
