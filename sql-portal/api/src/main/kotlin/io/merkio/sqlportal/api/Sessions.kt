package io.merkio.sqlportal.api

import io.ktor.server.application.ApplicationCall
import io.ktor.server.sessions.SessionSerializer
import io.ktor.server.sessions.SessionStorage
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.merkio.sqlportal.domain.access.OidcPending
import io.merkio.sqlportal.domain.access.User
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** What the portal keeps per browser. Only the session id travels in the cookie. */
@Serializable
data class PortalSession(
    val user: User? = null,
    val csrf: String? = null,
    val oidcPending: OidcPending? = null,
)

internal object PortalSessionSerializer : SessionSerializer<PortalSession> {
    override fun serialize(session: PortalSession) = Json.encodeToString(session)

    override fun deserialize(text: String) = Json.decodeFromString<PortalSession>(text)
}

/**
 * In-memory session storage that forgets sessions idle for longer than [ttl].
 * Sessions are lost on restart and not shared between replicas.
 */
class ExpiringMemoryStorage(
    private val ttl: Duration,
    private val clock: TimeSource = TimeSource.Monotonic,
) : SessionStorage {
    private class Entry(
        val value: String,
        val touched: TimeMark,
    )

    private val sessions = ConcurrentHashMap<String, Entry>()

    override suspend fun write(
        id: String,
        value: String,
    ) {
        if (sessions.size >= SWEEP_THRESHOLD) sessions.entries.removeIf { it.value.touched.elapsedNow() >= ttl }
        sessions[id] = Entry(value, clock.markNow())
    }

    override suspend fun read(id: String): String {
        val entry = sessions[id]
        if (entry == null || entry.touched.elapsedNow() >= ttl) {
            sessions.remove(id)
            throw NoSuchElementException("session $id not found")
        }
        // Activity extends the session.
        sessions[id] = Entry(entry.value, clock.markNow())
        return entry.value
    }

    override suspend fun invalidate(id: String) {
        sessions.remove(id)
    }

    private companion object {
        const val SWEEP_THRESHOLD = 10_000
    }
}

private val random = SecureRandom()

internal fun randomToken(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))

private const val TOKEN_BYTES = 24

internal val ApplicationCall.session: PortalSession get() = sessions.get<PortalSession>() ?: PortalSession()

internal fun ApplicationCall.updateSession(change: (PortalSession) -> PortalSession) {
    sessions.set(change(session))
}

/** The session's CSRF token, created on first use. */
internal fun ApplicationCall.csrfToken(): String {
    session.csrf?.let { return it }
    val token = randomToken()
    updateSession { it.copy(csrf = token) }
    return token
}

/** Compares a submitted CSRF token with the session's in constant time. */
internal fun ApplicationCall.verifyCsrf(submitted: String?): Boolean {
    val expected = session.csrf ?: return false
    return submitted != null && MessageDigest.isEqual(expected.toByteArray(), submitted.toByteArray())
}
