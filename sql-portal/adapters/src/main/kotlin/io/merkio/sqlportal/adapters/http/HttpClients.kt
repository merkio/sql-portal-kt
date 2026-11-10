package io.merkio.sqlportal.adapters.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.merkio.sqlportal.common.tls.TlsOptions
import kotlin.time.Duration

/**
 * An HTTP client for outbound calls (ClickHouse, OIDC, grants service). It
 * never follows redirects, which could send credentials to another host, and
 * applies the connection's TLS options.
 */
fun httpClient(
    tls: TlsOptions,
    requestTimeout: Duration,
    connectTimeout: Duration = requestTimeout,
): HttpClient {
    val sslContext = if (tls.isSet) tls.sslContext() else null
    return HttpClient(Java) {
        expectSuccess = false
        followRedirects = false
        engine {
            config {
                if (sslContext != null) sslContext(sslContext)
                connectTimeout(java.time.Duration.ofMillis(connectTimeout.inWholeMilliseconds))
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = requestTimeout.inWholeMilliseconds
            connectTimeoutMillis = connectTimeout.inWholeMilliseconds
        }
    }
}
