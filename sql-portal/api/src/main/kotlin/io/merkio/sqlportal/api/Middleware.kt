package io.merkio.sqlportal.api

import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.util.AttributeKey
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TimeSource

/** Builds the Content-Security-Policy for the given chart library URL. */
fun contentSecurityPolicy(chartjsUrl: String): String {
    val origin =
        runCatching { URI(chartjsUrl) }
            .getOrNull()
            ?.takeIf { it.isAbsolute && !it.host.isNullOrEmpty() }
            ?.let { "${it.scheme}://${it.host}" + if (it.port >= 0) ":${it.port}" else "" }
    val scriptSrc = if (origin == null) "'self'" else "'self' $origin"
    return "default-src 'self'; script-src $scriptSrc; style-src 'self'; img-src 'self' data:; " +
        "connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"
}

/** Defensive security headers, including a CSP that only allows same-origin scripts and the chart library. */
internal val SecurityHeaders =
    createApplicationPlugin("SecurityHeaders", { SecurityHeadersConfig() }) {
        val csp = contentSecurityPolicy(pluginConfig.chartjsUrl)
        onCall { call ->
            with(call.response.headers) {
                append("Content-Security-Policy", csp)
                append("X-Content-Type-Options", "nosniff")
                append("X-Frame-Options", "DENY")
                append("Referrer-Policy", "same-origin")
                append("Permissions-Policy", "geolocation=(), microphone=(), camera=()")
            }
        }
    }

internal class SecurityHeadersConfig {
    var chartjsUrl: String = ""
}

private val RouteTemplateKey = AttributeKey<String>("RouteTemplate")
private val StartKey = AttributeKey<TimeSource.Monotonic.ValueTimeMark>("RequestStart")

/** Path label for a matched route: its template (`/scripts/{name}`), never the raw path. */
internal fun routeTemplate(route: RoutingNode): String {
    val text = route.toString().replace(Regex("""/\([^)]*\)"""), "")
    return when {
        "{...}" in text -> UNMATCHED
        text.isEmpty() -> "/"
        else -> text
    }
}

internal const val UNMATCHED = "unmatched"

/**
 * Records request count, latency and the in-flight gauge, labelled with the
 * matched route template, which keeps cardinality bounded.
 */
internal val HttpMetrics =
    createApplicationPlugin("HttpMetrics", { HttpMetricsConfig() }) {
        val registry = pluginConfig.registry ?: return@createApplicationPlugin
        val inFlight = registry.gauge("http.requests.in.flight", AtomicInteger(0))
        application.monitor.subscribe(RoutingRoot.RoutingCallStarted) { call ->
            call.attributes.put(RouteTemplateKey, routeTemplate(call.route))
        }
        on(CallSetup) { call ->
            call.attributes.put(StartKey, TimeSource.Monotonic.markNow())
            inFlight.incrementAndGet()
        }
        on(ResponseSent) { call ->
            inFlight.decrementAndGet()
            val tags =
                listOf(
                    "method",
                    call.request.local.method.value,
                    "path",
                    call.attributes.getOrNull(RouteTemplateKey) ?: UNMATCHED,
                    "status",
                    (call.response.status()?.value ?: 0).toString(),
                ).toTypedArray()
            registry.counter("http.requests", *tags).increment()
            val elapsed = call.attributes.getOrNull(StartKey)?.elapsedNow() ?: return@on
            Timer
                .builder("http.request.duration")
                .tags(*tags)
                .register(registry)
                .record(java.time.Duration.ofNanos(elapsed.inWholeNanoseconds))
        }
    }

internal class HttpMetricsConfig {
    var registry: MeterRegistry? = null
}
