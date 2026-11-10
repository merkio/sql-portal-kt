package io.merkio.sqlportal.domain.access

import io.merkio.sqlportal.domain.common.DomainException
import java.util.SortedSet

/** A permission required by a script, e.g. `scripts.sales.read`. */
@ConsistentCopyVisibility
data class Permission private constructor(
    val value: String,
) : Comparable<Permission> {
    override fun compareTo(other: Permission) = value.compareTo(other.value)

    override fun toString() = value

    companion object {
        /** @throws DomainException for blank values, whitespace or wildcards. */
        fun of(value: String): Permission {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) throw DomainException("permission must not be empty")
            if (trimmed.any { it.isWhitespace() } || '*' in trimmed) {
                throw DomainException("permission '$trimmed' must not contain whitespace or wildcards")
            }
            return Permission(trimmed)
        }
    }
}

/**
 * Permissions granted to a principal. Entries may be exact permissions, a
 * prefix wildcard (`scripts.sales.*`) or the global wildcard (`*`).
 */
class PermissionSet(
    grants: Iterable<String> = emptyList(),
) {
    private val entries: SortedSet<String> = grants.map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet()

    fun grants(required: Permission): Boolean =
        entries.any { grant ->
            when {
                grant == "*" -> true
                grant.endsWith("*") -> required.value.startsWith(grant.dropLast(1))
                else -> grant == required.value
            }
        }

    fun toList(): List<String> = entries.toList()

    fun isEmpty() = entries.isEmpty()

    override fun equals(other: Any?) = other is PermissionSet && other.entries == entries

    override fun hashCode() = entries.hashCode()

    override fun toString() = entries.toString()

    companion object {
        fun all() = PermissionSet(listOf("*"))
    }
}
