package io.merkio.sqlportal.application

import kotlin.time.Duration

/**
 * Business-level metrics recorded by the use cases. The composition root
 * decides where they go (Prometheus in production).
 */
interface Metrics {
    /** One login attempt with `provider` (`ldap`, `oidc`) and its outcome. */
    fun authAttempt(
        provider: String,
        outcome: String,
    )

    /** One script request with its outcome, duration and rows returned. */
    fun scriptRun(
        script: String,
        outcome: String,
        duration: Duration,
        rows: Int,
    )

    /**
     * One SQL statement sent to the database. [purpose] is `table`, `page`,
     * `count` or `chart`; [outcome] is `success`, `timeout` or `error`.
     * Only the time spent in the database is measured.
     */
    fun sqlExecuted(
        script: String,
        purpose: String,
        outcome: String,
        duration: Duration,
    )
}

/** Metrics sink that discards everything (tests, tooling). */
object NoopMetrics : Metrics {
    override fun authAttempt(
        provider: String,
        outcome: String,
    ) = Unit

    override fun scriptRun(
        script: String,
        outcome: String,
        duration: Duration,
        rows: Int,
    ) = Unit

    override fun sqlExecuted(
        script: String,
        purpose: String,
        outcome: String,
        duration: Duration,
    ) = Unit
}
