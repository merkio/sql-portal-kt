package io.merkio.sqlportal.adapters.grants

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.merkio.sqlportal.adapters.http.httpClient
import io.merkio.sqlportal.common.tls.TlsOptions
import io.merkio.sqlportal.domain.access.AuthException
import io.merkio.sqlportal.domain.access.GrantSource
import io.merkio.sqlportal.domain.access.Grants
import io.merkio.sqlportal.domain.access.PermissionSet
import io.merkio.sqlportal.domain.access.User
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.time.Duration

/**
 * Loads roles and permissions from `POST {url}` of an external service, with
 * the same TLS options as the other connections.
 */
class RemoteGrants(
    private val url: String,
    private val bearerToken: String?,
    timeout: Duration,
    tls: TlsOptions,
    private val http: HttpClient = httpClient(tls, timeout),
) : GrantSource {
    @Serializable
    private data class LookupRequest(
        val subject: String,
        @SerialName("display_name") val displayName: String,
        val email: String?,
        val groups: List<String>,
    )

    @Serializable
    private data class LookupResponse(
        val roles: List<String> = emptyList(),
        val permissions: List<String> = emptyList(),
    )

    override suspend fun grantsFor(user: User): Grants {
        val response =
            try {
                http.post(url) {
                    contentType(ContentType.Application.Json)
                    bearerToken?.let { bearerAuth(it) }
                    setBody(JSON.encodeToString(LookupRequest(user.subject, user.displayName, user.email, user.groups)))
                }
            } catch (e: IOException) {
                throw unavailable(e.message ?: e.toString(), e)
            }
        // An identity the service does not know holds no grants.
        if (response.status == HttpStatusCode.NotFound) return Grants()
        if (!response.status.isSuccess()) throw unavailable("grants service answered ${response.status}")
        val body =
            try {
                JSON.decodeFromString<LookupResponse>(response.bodyAsText())
            } catch (e: SerializationException) {
                throw unavailable("invalid grants response: ${e.message}", e)
            }
        return Grants(body.roles.toSortedSet(), PermissionSet(body.permissions))
    }

    private fun unavailable(
        message: String,
        cause: Throwable? = null,
    ) = AuthException.ProviderUnavailable(message).apply { cause?.let(::initCause) }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
