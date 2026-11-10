package io.merkio.sqlportal.adapters.grants

import io.merkio.sqlportal.common.tls.TlsConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `access.external` section: grants loaded from an external service and
 * cached locally. The service receives `POST {url}` with `{"subject",
 * "display_name", "email", "groups"}` and answers `{"roles": [...],
 * "permissions": [...]}`, or `404` for an identity that holds nothing.
 */
@Serializable
data class ExternalGrantsConfig(
    val url: String,
    @SerialName("timeout_secs") val timeoutSecs: Long = DEFAULT_TIMEOUT_SECS,
    /** Fresh answers are reused this long; stale ones bridge an outage for at most twice this long. */
    @SerialName("cache_ttl_secs") val cacheTtlSecs: Long = DEFAULT_CACHE_TTL_SECS,
    @SerialName("bearer_token") val bearerToken: String? = null,
    val tls: TlsConfig? = null,
) {
    companion object {
        const val DEFAULT_TIMEOUT_SECS = 3L
        const val DEFAULT_CACHE_TTL_SECS = 300L
    }
}
