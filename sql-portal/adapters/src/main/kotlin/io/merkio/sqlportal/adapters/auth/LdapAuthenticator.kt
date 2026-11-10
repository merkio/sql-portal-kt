package io.merkio.sqlportal.adapters.auth

import com.unboundid.ldap.sdk.Filter
import com.unboundid.ldap.sdk.LDAPConnection
import com.unboundid.ldap.sdk.LDAPConnectionOptions
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.LDAPURL
import com.unboundid.ldap.sdk.ResultCode
import com.unboundid.ldap.sdk.SearchRequest
import com.unboundid.ldap.sdk.SearchResultEntry
import com.unboundid.ldap.sdk.SearchScope
import com.unboundid.ldap.sdk.extensions.StartTLSExtendedRequest
import io.merkio.sqlportal.adapters.config.LdapConfig
import io.merkio.sqlportal.common.tls.TlsOptions
import io.merkio.sqlportal.domain.access.AuthException
import io.merkio.sqlportal.domain.access.AuthProvider
import io.merkio.sqlportal.domain.access.CredentialAuthenticator
import io.merkio.sqlportal.domain.access.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import javax.net.ssl.SSLContext

/** LDAP authentication: search the user with a service account, then bind as the user. */
class LdapAuthenticator(
    private val cfg: LdapConfig,
    tls: TlsOptions,
) : CredentialAuthenticator {
    private val url = LDAPURL(cfg.url)
    private val ssl: SSLContext? = if (url.scheme == "ldaps" || cfg.startTls) tls.sslContext() else null

    private val options =
        LDAPConnectionOptions().apply {
            val millis = cfg.timeoutSecs.coerceAtLeast(1) * MILLIS
            connectTimeoutMillis = millis.toInt()
            responseTimeoutMillis = millis
        }

    override suspend fun authenticate(
        username: String,
        password: String,
    ): User {
        val login = username.trim()
        // An empty password would turn into an unauthenticated ("anonymous")
        // bind on many servers, which "succeeds". Never allow it.
        if (login.isEmpty() || password.isEmpty()) throw AuthException.InvalidCredentials()
        return withContext(Dispatchers.IO) { authenticateBlocking(login, password) }
    }

    private fun authenticateBlocking(
        login: String,
        password: String,
    ): User =
        try {
            connect().use { service ->
                // 1. Look the user up with the service account.
                service.bind(cfg.bindDn, cfg.bindPassword)
                val entry = findUser(service, login)

                // 2. Verify the password with a bind as the user on a fresh connection
                //    (re-binding the service connection would change its identity).
                verifyPassword(entry.dn, password)

                // 3. Collect groups (memberOf and/or group search) and build the user.
                val user = userFromEntry(attributes(entry), cfg, login)
                val extra = searchGroups(service, entry.dn, login).filter { it !in user.groups }
                user.copy(groups = user.groups + extra)
            }
        } catch (e: LDAPException) {
            throw AuthException.ProviderUnavailable(e.message ?: e.resultCode.toString()).apply { initCause(e) }
        }

    private fun verifyPassword(
        dn: String,
        password: String,
    ) {
        connect().use { asUser ->
            try {
                asUser.bind(dn, password)
            } catch (e: LDAPException) {
                val failure =
                    if (e.resultCode == ResultCode.INVALID_CREDENTIALS) {
                        AuthException.InvalidCredentials()
                    } else {
                        AuthException.ProviderUnavailable("unexpected LDAP result ${e.resultCode}")
                    }
                throw failure.apply { initCause(e) }
            }
        }
    }

    private fun connect(): LDAPConnection {
        val connection =
            if (url.scheme == "ldaps") {
                LDAPConnection(ssl!!.socketFactory, options, url.host, url.port)
            } else {
                LDAPConnection(options, url.host, url.port)
            }
        if (cfg.startTls) {
            val result = connection.processExtendedOperation(StartTLSExtendedRequest(ssl))
            if (result.resultCode != ResultCode.SUCCESS) {
                connection.close()
                throw AuthException.ProviderUnavailable("StartTLS failed: ${result.resultCode}")
            }
        }
        return connection
    }

    private fun findUser(
        ldap: LDAPConnection,
        login: String,
    ): SearchResultEntry {
        val attrs = listOf(cfg.usernameAttribute, cfg.displayNameAttribute, cfg.emailAttribute, cfg.groupAttribute)
        val request =
            SearchRequest(
                cfg.baseDn,
                SearchScope.SUB,
                buildUserFilter(cfg.userFilter, login),
                *attrs
                    .filter {
                        it.isNotEmpty()
                    }.toTypedArray(),
            )
        request.sizeLimit = 2
        val entries =
            try {
                ldap.search(request).searchEntries
            } catch (e: LDAPException) {
                if (e.resultCode != ResultCode.SIZE_LIMIT_EXCEEDED) throw e
                log.warn("ambiguous LDAP user filter matched several entries")
                throw AuthException.InvalidCredentials().apply { initCause(e) }
            }
        return when (entries.size) {
            1 -> entries.single()
            0 -> throw AuthException.InvalidCredentials()
            else -> {
                log.warn("ambiguous LDAP user filter matched several entries")
                throw AuthException.InvalidCredentials()
            }
        }
    }

    private fun searchGroups(
        ldap: LDAPConnection,
        userDn: String,
        login: String,
    ): List<String> {
        val search = cfg.groupSearch ?: return emptyList()
        val filter =
            search.filter
                .replace("{user_dn}", Filter.encodeValue(userDn))
                .replace("{username}", Filter.encodeValue(login))
        val found = ldap.search(search.baseDn, SearchScope.SUB, filter, search.nameAttribute).searchEntries
        return found.flatMap { entry -> listOf(entry.dn) + entry.getAttributeValues(search.nameAttribute).orEmpty() }
    }

    private companion object {
        val log = LoggerFactory.getLogger(LdapAuthenticator::class.java)
        const val MILLIS = 1000L

        fun attributes(entry: SearchResultEntry): Map<String, List<String>> =
            entry.attributes.associate { it.name to it.values.toList() }
    }
}

/** Substitutes the escaped user name into the configured filter template. */
fun buildUserFilter(
    template: String,
    username: String,
): String = template.replace("{username}", Filter.encodeValue(username))

/** The value of the first RDN when it is `cn=`: `cn=ops,ou=g` -> `ops`. */
fun cnFromDn(dn: String): String? {
    val first = dn.substringBefore(',').trim()
    if ('=' !in first) return null
    val key = first.substringBefore('=').trim()
    val value = first.substringAfter('=').trim()
    return value.takeIf { key.equals("cn", ignoreCase = true) && it.isNotEmpty() }
}

/**
 * Maps an LDAP entry to a domain user. Groups contain both the full DN and
 * the CN, so either can be used in `access.group_roles`. Attribute names are
 * matched case-insensitively (servers differ in attribute casing).
 */
fun userFromEntry(
    attrs: Map<String, List<String>>,
    cfg: LdapConfig,
    login: String,
): User {
    fun values(name: String) =
        attrs.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            .orEmpty()

    fun first(name: String) = values(name).firstOrNull { it.isNotEmpty() }

    val subject = first(cfg.usernameAttribute) ?: login
    val groups = linkedSetOf<String>()
    if (cfg.groupAttribute.isNotEmpty()) {
        for (dn in values(cfg.groupAttribute)) {
            cnFromDn(dn)?.let { groups += it }
            groups += dn
        }
    }
    return User(
        subject = subject,
        displayName = first(cfg.displayNameAttribute) ?: subject,
        email = first(cfg.emailAttribute),
        groups = groups.toList(),
        provider = AuthProvider.LDAP,
    )
}
