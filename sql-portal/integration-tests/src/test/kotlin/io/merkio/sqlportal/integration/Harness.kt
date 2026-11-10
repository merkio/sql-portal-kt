/*
 * In-process harness: the production Ktor module over the demo configuration,
 * through the same wiring the server uses, with fake identity providers and an
 * in-memory SQLite database.
 */
package io.merkio.sqlportal.integration

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseQueryString
import io.ktor.server.testing.TestApplication
import io.merkio.sqlportal.adapters.config.ConfigLoader
import io.merkio.sqlportal.adapters.config.RuntimeConfig
import io.merkio.sqlportal.adapters.sql.connectDatabase
import io.merkio.sqlportal.application.AuthMode
import io.merkio.sqlportal.application.NoopMetrics
import io.merkio.sqlportal.application.Providers
import io.merkio.sqlportal.common.tls.TlsOptions
import io.merkio.sqlportal.deployment.HealthProbe
import io.merkio.sqlportal.deployment.Overrides
import io.merkio.sqlportal.deployment.portalModule
import io.merkio.sqlportal.deployment.wire
import io.merkio.sqlportal.domain.access.LocalGrants
import io.merkio.sqlportal.testing.FakeLdap
import io.merkio.sqlportal.testing.FakeOidc
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds

val repoRoot: Path = Path.of(System.getProperty("sqlportal.repoRoot", "../.."))

/** The configuration file `config/<name>`, as text and with its directory. */
fun configText(name: String = "config.demo.conf") = repoRoot.resolve("config").resolve(name).readText()

fun runtime(text: String = configText()): RuntimeConfig =
    ConfigLoader.load(text, repoRoot.resolve("config"), emptyMap())

class Options(
    val authEnabled: Boolean = true,
    val extraProbes: List<HealthProbe> = emptyList(),
    val registry: MeterRegistry? = null,
)

class Reply(
    val status: HttpStatusCode,
    val headers: Headers,
    val body: String,
) {
    val location: String? get() = headers[HttpHeaders.Location]

    /** `name=value` of the session cookie set by this response, if any. */
    val sessionCookie: String?
        get() =
            headers
                .getAll(HttpHeaders.SetCookie)
                ?.firstOrNull { it.startsWith("sp_session=") }
                ?.substringBefore(';')

    fun json(): JsonElement = Json.parseToJsonElement(body)

    /** The hidden CSRF token of a rendered form. */
    fun csrf(): String = body.substringAfter("name=\"csrf\" value=\"").substringBefore('"')
}

class Harness private constructor(
    private val app: TestApplication,
) : AutoCloseable {
    private val client: HttpClient = app.createClient { followRedirects = false }

    private suspend fun reply(response: HttpResponse) = Reply(response.status, response.headers, response.bodyAsText())

    suspend fun get(
        uri: String,
        cookie: String? = null,
    ) = reply(client.get(uri) { cookie?.let { header(HttpHeaders.Cookie, it) } })

    suspend fun postForm(
        uri: String,
        form: String,
        cookie: String? = null,
    ) = reply(
        client.submitForm(uri, parseQueryString(form)) { cookie?.let { header(HttpHeaders.Cookie, it) } },
    )

    /** Signs in through the LDAP form and returns the authenticated session cookie. */
    suspend fun loginLdap(
        user: String,
        password: String,
    ): String {
        val page = get("/login")
        val cookie = checkNotNull(page.sessionCookie) { "no session cookie on the login page" }
        val reply = postForm("/login", "username=$user&password=$password&csrf=${page.csrf()}", cookie)
        check(reply.status == HttpStatusCode.SeeOther) { "login failed: ${reply.status} ${reply.body}" }
        return checkNotNull(reply.sessionCookie) { "no cycled session cookie" }
    }

    override fun close() {
        client.close()
        runBlocking { app.stop() }
    }

    companion object {
        /**
         * The production module over the demo configuration. Only the database
         * connection, the metrics sink and the identity providers are replaced,
         * and the UI settings are pinned so the tests can check them.
         */
        suspend fun start(options: Options = Options()): Harness {
            val base = runtime()
            val rt =
                base.copy(
                    ui = base.ui.copy(title = "Test Portal", chartjsUrl = "https://cdn.example.org/chart.js"),
                )
            val auth =
                if (options.authEnabled) {
                    AuthMode.Enabled(
                        Providers(FakeLdap("alice", "secret", listOf("analysts")), FakeOidc(listOf("admins"))),
                    )
                } else {
                    AuthMode.Disabled
                }
            val services =
                wire(
                    rt,
                    Overrides(
                        executor = connectDatabase("sqlite::memory:", 1, 5.seconds, TlsOptions()),
                        auth = auth,
                        grants = LocalGrants(rt.policy),
                        metrics = if (options.registry == null) NoopMetrics else null,
                        extraProbes = options.extraProbes,
                    ),
                    options.registry,
                )
            val app = TestApplication { application(portalModule(services, { "# test metrics\n" }, options.registry)) }
            app.start()
            return Harness(app)
        }
    }
}

/** A probe that always fails. */
class FailingProbe(
    override val name: String,
    override val critical: Boolean,
) : HealthProbe {
    override suspend fun check(): Unit = error("probe failed")
}
