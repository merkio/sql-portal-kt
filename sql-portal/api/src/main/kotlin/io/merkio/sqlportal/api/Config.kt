// The `server` and `ui` sections of the configuration.
package io.merkio.sqlportal.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerConfig(
    val bind: String = "0.0.0.0:8080",
    /** Externally visible base URL, used to build the OIDC redirect URI. */
    @SerialName("public_url") val publicUrl: String = "http://localhost:8080",
    val session: SessionConfig = SessionConfig(),
)

@Serializable
data class SessionConfig(
    @SerialName("cookie_name") val cookieName: String = "sp_session",
    /** Set the `Secure` cookie attribute (required behind HTTPS). */
    val secure: Boolean = false,
    @SerialName("ttl_minutes") val ttlMinutes: Long = DEFAULT_TTL_MINUTES,
) {
    companion object {
        const val DEFAULT_TTL_MINUTES = 60L
    }
}

@Serializable
data class UiConfig(
    val title: String = "Script Portal",
    /** Chart.js bundle. Point this at a self-hosted copy to avoid the CDN. */
    @SerialName("chartjs_url") val chartjsUrl: String = DEFAULT_CHARTJS,
) {
    fun toSettings() = UiSettings(title, chartjsUrl)

    companion object {
        const val DEFAULT_CHARTJS = "https://cdn.jsdelivr.net/npm/chart.js@4.4.7/dist/chart.umd.min.js"
    }
}
