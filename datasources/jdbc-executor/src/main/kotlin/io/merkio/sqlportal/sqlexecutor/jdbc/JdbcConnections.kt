package io.merkio.sqlportal.sqlexecutor.jdbc

import io.merkio.sqlportal.sqlexecutor.SqlException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLTransientConnectionException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Plain JDBC connections, opened once and reused for every statement.
 *
 * At most [size] connections exist; each is opened on first use (the first
 * one at construction, so an unreachable database fails at startup) and kept
 * open afterwards. A connection that was idle for a while is validated before
 * it is handed out, and one that broke is replaced by a new one. A caller
 * waits at most [acquireTimeout] for a free connection.
 *
 * @param open opens one connection; it is called again only to replace a broken one.
 * @throws IllegalStateException when the first connection cannot be opened.
 */
class JdbcConnections(
    private val size: Int,
    private val acquireTimeout: Duration,
    private val open: () -> Connection,
) : AutoCloseable {
    private class Slot(
        var connection: Connection?,
    ) {
        var lastUsed: TimeMark = TimeSource.Monotonic.markNow()
    }

    private val free = ConcurrentLinkedQueue<Slot>()
    private val permits: Semaphore
    private val slots: List<Slot>

    @Volatile private var closed = false

    init {
        require(size >= 1) { "at least one connection is needed" }
        val first =
            try {
                open()
            } catch (e: SQLException) {
                throw IllegalStateException("cannot connect to the database: ${rootMessage(e)}", e)
            }
        slots = List(size) { Slot(if (it == 0) first else null) }
        free.addAll(slots)
        permits = Semaphore(size)
    }

    /**
     * Runs [block] on a connection of its own, which is returned for reuse
     * afterwards. [block] is blocking JDBC code: call this on the IO dispatcher.
     *
     * @throws SqlException.Unavailable when no connection is free in time or none can be opened.
     */
    suspend fun <T> use(block: (Connection) -> T): T {
        if (closed) throw SqlException.Unavailable("the connections are closed")
        withTimeoutOrNull(acquireTimeout) { permits.acquire() }
            ?: throw SqlException.Unavailable("no database connection free within $acquireTimeout")
        val slot = checkNotNull(free.poll()) { "a permit without a free connection" }
        try {
            val connection = ready(slot)
            try {
                return block(connection)
            } catch (e: SQLException) {
                if (isBroken(connection, e)) discard(slot)
                throw e
            }
        } finally {
            slot.lastUsed = TimeSource.Monotonic.markNow()
            free.add(slot)
            permits.release()
        }
    }

    /** The slot's connection, validated after a long idle time, or a new one. */
    private fun ready(slot: Slot): Connection {
        slot.connection?.let { current ->
            val usable =
                runCatching {
                    !current.isClosed && (slot.lastUsed.elapsedNow() < IDLE_BEFORE_CHECK || current.isValid(CHECK_SECS))
                }.getOrDefault(false)
            if (usable) return current
            discard(slot)
        }
        return try {
            open().also { slot.connection = it }
        } catch (e: SQLException) {
            throw SqlException.Unavailable(rootMessage(e), e)
        }
    }

    private fun discard(slot: Slot) {
        slot.connection?.let { runCatching { it.close() } }
        slot.connection = null
    }

    /** Closes every connection; statements running at that moment fail. */
    override fun close() {
        closed = true
        slots.forEach(::discard)
    }

    private companion object {
        /** A connection used within this time is handed out without a round trip. */
        val IDLE_BEFORE_CHECK = 30.seconds
        const val CHECK_SECS = 2
    }
}

/** The connection is gone (network, server restart): it must not be reused. */
private fun isBroken(
    connection: Connection,
    e: SQLException,
): Boolean =
    e is SQLTransientConnectionException ||
        e is SQLNonTransientConnectionException ||
        e.sqlState?.startsWith("08") == true ||
        runCatching { connection.isClosed }.getOrDefault(true)

/** The innermost message of [e], usually the most specific one. */
fun rootMessage(e: Throwable): String {
    var cause = e
    while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
    return cause.message ?: cause.toString()
}
