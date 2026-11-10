package io.merkio.sqlportal.adapters.grants

import io.merkio.sqlportal.domain.access.AuthException
import io.merkio.sqlportal.domain.access.GrantSource
import io.merkio.sqlportal.domain.access.Grants
import io.merkio.sqlportal.domain.access.User
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Local cache in front of a grant source.
 *
 * A lookup is answered from the cache while the entry is younger than the
 * TTL. Otherwise the inner source is asked. If it fails, an entry younger than
 * twice the TTL is used, so a short outage does not lock everyone out, but
 * revocations still take effect within two TTLs. Failures are never cached.
 */
class CachedGrants(
    private val inner: GrantSource,
    private val ttl: Duration,
    private val clock: TimeSource = TimeSource.Monotonic,
) : GrantSource {
    private class Entry(
        val grants: Grants,
        val fetched: TimeMark,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    private val staleLimit get() = ttl * 2

    override suspend fun grantsFor(user: User): Grants {
        val key = key(user)
        val cached = entries[key]
        if (cached != null && cached.fetched.elapsedNow() < ttl) return cached.grants
        return try {
            inner.grantsFor(user).also { store(key, it) }
        } catch (e: AuthException) {
            if (cached == null || cached.fetched.elapsedNow() >= staleLimit) throw e
            log.warn("grant source unavailable for {}; using cached grants: {}", user.subject, e.message)
            cached.grants
        }
    }

    private fun store(
        key: String,
        grants: Grants,
    ) {
        if (entries.size >= MAX_ENTRIES) {
            entries.entries.removeIf { it.value.fetched.elapsedNow() >= staleLimit }
            if (entries.size >= MAX_ENTRIES) entries.clear()
        }
        entries[key] = Entry(grants, clock.markNow())
    }

    private companion object {
        val log = LoggerFactory.getLogger(CachedGrants::class.java)

        /** Upper bound on cached identities; the cache is cleared when it is full. */
        const val MAX_ENTRIES = 10_000

        /** Identity and groups decide the grants, so both are part of the key. */
        fun key(user: User): String {
            val groups =
                user.groups
                    .map { it.trim().lowercase() }
                    .toSortedSet()
                    .joinToString(",")
            return "${user.subject.trim().lowercase()}|$groups"
        }
    }
}
