package io.merkio.sqlportal.application

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/** Records metric calls for assertions. */
class RecordingMetrics : Metrics {
    val auth = CopyOnWriteArrayList<Pair<String, String>>()
    val scripts = CopyOnWriteArrayList<Triple<String, String, Int>>()

    override fun authAttempt(
        provider: String,
        outcome: String,
    ) {
        auth += provider to outcome
    }

    override fun scriptRun(
        script: String,
        outcome: String,
        duration: Duration,
        rows: Int,
    ) {
        scripts += Triple(script, outcome, rows)
    }
}
