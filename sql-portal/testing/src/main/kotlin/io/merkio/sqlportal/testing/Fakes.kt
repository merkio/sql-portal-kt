// In-memory fakes for the ports, used by unit and integration tests.
package io.merkio.sqlportal.testing

import io.merkio.sqlportal.domain.access.AuthException
import io.merkio.sqlportal.domain.access.AuthProvider
import io.merkio.sqlportal.domain.access.CredentialAuthenticator
import io.merkio.sqlportal.domain.access.OidcAuthorization
import io.merkio.sqlportal.domain.access.OidcPending
import io.merkio.sqlportal.domain.access.OidcProvider
import io.merkio.sqlportal.domain.access.User
import io.merkio.sqlportal.domain.query.QueryException
import io.merkio.sqlportal.domain.query.QueryExecutor
import io.merkio.sqlportal.domain.query.QueryRequest
import io.merkio.sqlportal.domain.query.QueryResult
import io.merkio.sqlportal.domain.scripts.ScriptCatalog
import io.merkio.sqlportal.domain.scripts.ScriptDefinition
import java.util.concurrent.CopyOnWriteArrayList

/** LDAP fake accepting exactly one username/password pair. */
class FakeLdap(
    private val username: String,
    private val password: String,
    private val groups: List<String> = emptyList(),
) : CredentialAuthenticator {
    override suspend fun authenticate(
        username: String,
        password: String,
    ): User {
        if (username != this.username || password != this.password) throw AuthException.InvalidCredentials()
        return User(username, "$username (ldap)", "$username@example.org", groups, AuthProvider.LDAP)
    }
}

/** OIDC fake: `begin` issues fixed state, `complete` accepts `good-code` or `c`. */
class FakeOidc(
    private val groups: List<String> = emptyList(),
) : OidcProvider {
    override suspend fun begin() =
        OidcAuthorization(
            "https://idp.example.org/authorize?state=state-123",
            OidcPending("state-123", "nonce-123", "verifier-123"),
        )

    override suspend fun complete(
        code: String,
        pending: OidcPending,
    ): User {
        if (code != "good-code" && code != "c") throw AuthException.Rejected("bad code")
        check(pending.pkceVerifier == "verifier-123") { "PKCE verifier was not passed through" }
        return User("oidc-user", "OIDC User", groups = groups, provider = AuthProvider.OIDC)
    }

    override suspend fun probe() = Unit
}

/** Executor returning a canned result (or error) and remembering every request. */
class FakeExecutor private constructor(
    private val result: QueryResult?,
    private val error: QueryException?,
) : QueryExecutor {
    private val received = CopyOnWriteArrayList<QueryRequest>()

    val requests: List<QueryRequest> get() = received.toList()

    val lastRequest: QueryRequest? get() = received.lastOrNull()

    override suspend fun execute(request: QueryRequest): QueryResult {
        received += request
        error?.let { throw it }
        return result ?: QueryResult()
    }

    override suspend fun ping() = Unit

    override suspend fun runScript(sql: String) = Unit

    companion object {
        fun empty() = FakeExecutor(null, null)

        fun returning(result: QueryResult) = FakeExecutor(result, null)

        fun failing(error: QueryException) = FakeExecutor(null, error)
    }
}

/** Catalog over a fixed list, in the given order. */
class InMemoryCatalog(
    private val scripts: List<ScriptDefinition>,
) : ScriptCatalog {
    override fun all() = scripts

    override fun get(name: String) = scripts.firstOrNull { it.name.value == name }
}
