package io.merkio.sqlportal.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.merkio.sqlportal.application.Invocation
import io.merkio.sqlportal.application.OidcCallback
import io.merkio.sqlportal.application.PageChoice
import io.merkio.sqlportal.application.ScriptOutcome
import io.merkio.sqlportal.application.ScriptRun
import io.merkio.sqlportal.domain.access.AuthException
import io.merkio.sqlportal.domain.access.Principal
import io.merkio.sqlportal.domain.access.User
import io.merkio.sqlportal.domain.query.QueryException
import io.merkio.sqlportal.domain.scripts.ChartOutcome
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.event.Level
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/**
 * Installs every route and cross-cutting plugin of the portal: sessions,
 * security headers, request ids, request logging and HTTP metrics.
 */
fun Application.portal(
    state: AppState,
    settings: RouterSettings,
) {
    checkTemplates()
    if (settings.cookieSecure) {
        // A secure cookie needs HTTPS; behind a TLS-terminating proxy the scheme comes from X-Forwarded-Proto.
        install(XForwardedHeaders)
    }
    install(AutoHeadResponse)
    install(CallId) {
        retrieveFromHeader("X-Request-Id")
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() && it.length <= MAX_REQUEST_ID }
        replyToHeader("X-Request-Id")
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("request_id")
        // Only the path is logged: query strings may carry parameters.
        format { call -> "${call.response.status()?.value} ${call.request.httpMethod.value} ${call.request.path()}" }
    }
    install(SecurityHeaders) { chartjsUrl = state.ui.chartjsUrl }
    install(HttpMetrics) { registry = state.registry }
    install(Sessions) {
        cookie<PortalSession>(
            settings.cookieName,
            ExpiringMemoryStorage(settings.sessionTtlMinutes.minutes),
        ) {
            serializer = PortalSessionSerializer
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = settings.cookieSecure
            cookie.extensions["SameSite"] = "Lax"
            cookie.maxAgeInSeconds = settings.sessionTtlMinutes * SECONDS_PER_MINUTE
        }
    }
    val handlers = Handlers(state)
    routing {
        get("/") { handlers.index(this) }
        get("/login") { handlers.loginPage(this) }
        post("/login") { handlers.loginSubmit(this) }
        get("/login/oidc") { handlers.oidcLogin(this) }
        get("/auth/callback") { handlers.oidcCallback(this) }
        post("/logout") { handlers.logout(this) }
        get("/scripts/{name}") { handlers.scriptPage(this) }
        get("/api/scripts") { handlers.apiList(this) }
        get("/api/scripts/{name}") { handlers.apiRun(this) }
        get("/healthz") { call.respondJson(buildJsonObject { put("status", "ok") }) }
        get("/readyz") { handlers.readyz(this) }
        get("/static/app.css") { call.respondAsset(Assets.css, "text/css") }
        get("/static/app.js") { call.respondAsset(Assets.js, "text/javascript") }
        if (settings.metricsEnabled) {
            get(settings.metricsPath) {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondText(state.metrics(), ContentType.parse("text/plain; version=0.0.4; charset=utf-8"))
            }
        }
        route("{...}") { handle { handlers.notFound(this) } }
    }
}

private const val MAX_REQUEST_ID = 200
private const val SECONDS_PER_MINUTE = 60

internal suspend fun ApplicationCall.respondJson(
    body: JsonElement,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondText(body.toString(), ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondAsset(
    content: String,
    type: String,
) {
    response.header(HttpHeaders.CacheControl, "public, max-age=3600")
    respondText(content, ContentType.parse(type).withCharset(Charsets.UTF_8))
}

/** Redirect with 303, so the browser follows with a GET. */
internal suspend fun ApplicationCall.seeOther(location: String) {
    response.header(HttpHeaders.Location, location)
    respond(HttpStatusCode.SeeOther)
}

private class Handlers(
    private val state: AppState,
) {
    private val ui = state.ui

    private suspend fun RoutingContext.html(
        status: HttpStatusCode,
        title: String,
        nav: Nav?,
        body: String,
        withCharts: Boolean = false,
    ) = call.respondText(
        page(ui, title, nav, body, withCharts),
        ContentType.Text.Html.withCharset(Charsets.UTF_8),
        status,
    )

    private fun RoutingContext.nav(principal: Principal) =
        Nav(principal.user.displayName, if (state.auth.isEnabled) call.csrfToken() else null)

    /** The principal for HTML pages; anonymous requests are redirected to the login page. */
    private suspend fun RoutingContext.webPrincipal(): Principal? {
        val principal = state.auth.principal(call.session.user)
        if (principal == null) call.seeOther("/login")
        return principal
    }

    /** The principal for the JSON API; anonymous requests get a 401. */
    private suspend fun RoutingContext.apiPrincipal(): Principal? {
        val principal = state.auth.principal(call.session.user)
        if (principal == null) call.respondJson(error("authentication required"), HttpStatusCode.Unauthorized)
        return principal
    }

    private fun error(message: String) = buildJsonObject { put("error", message) }

    suspend fun index(ctx: RoutingContext): Unit =
        with(ctx) {
            val principal = webPrincipal() ?: return
            val showAll = call.request.queryParameters["all"] == "1"
            val all = state.scripts.listAll(principal)
            val locked = all.count { !it.accessible }
            val scripts = if (showAll) all else all.filter { it.accessible }
            html(HttpStatusCode.OK, "Scripts", nav(principal), indexBody(scripts, showAll, locked))
        }

    suspend fun notFound(ctx: RoutingContext): Unit =
        with(ctx) {
            html(
                HttpStatusCode.NotFound,
                "Not found",
                null,
                messageBody("Page not found", "The page you asked for does not exist."),
            )
        }

    // ---------------------------------------------------------------- auth

    private suspend fun RoutingContext.renderLogin(
        status: HttpStatusCode,
        error: String?,
    ) = html(
        status,
        "Sign in",
        null,
        loginBody(state.auth.ldapEnabled, state.auth.oidcEnabled, call.csrfToken(), error),
    )

    suspend fun loginPage(ctx: RoutingContext): Unit =
        with(ctx) {
            if (!state.auth.isEnabled || call.session.user != null) return call.seeOther("/")
            renderLogin(HttpStatusCode.OK, null)
        }

    /** A new session id on privilege change prevents session fixation. */
    private fun RoutingContext.storeLogin(user: User) {
        val previous = call.session
        call.sessions.clear<PortalSession>()
        call.sessions.set(PortalSession(user = user, csrf = previous.csrf))
    }

    suspend fun loginSubmit(ctx: RoutingContext): Unit =
        with(ctx) {
            val form = call.receiveParameters()
            if (!state.auth.ldapEnabled) {
                return renderLogin(
                    HttpStatusCode.BadRequest,
                    "Directory sign-in is not enabled.",
                )
            }
            if (!call.verifyCsrf(
                    form["csrf"],
                )
            ) {
                return renderLogin(HttpStatusCode.Forbidden, "Your session expired. Please try again.")
            }
            try {
                storeLogin(state.auth.loginLdap(form["username"].orEmpty(), form["password"].orEmpty()))
                call.seeOther("/")
            } catch (e: AuthException) {
                val (status, message) = authErrorMessage(e)
                renderLogin(status, message)
            }
        }

    suspend fun oidcLogin(ctx: RoutingContext): Unit =
        with(ctx) {
            try {
                val auth = state.auth.oidcBegin()
                call.updateSession { it.copy(oidcPending = auth.pending) }
                call.seeOther(auth.redirectUrl)
            } catch (e: AuthException) {
                val (status, message) = authErrorMessage(e)
                renderLogin(status, message)
            }
        }

    suspend fun oidcCallback(ctx: RoutingContext): Unit =
        with(ctx) {
            val params = call.request.queryParameters
            if (params["error"] !=
                null
            ) {
                return renderLogin(HttpStatusCode.Unauthorized, "The identity provider denied the sign-in.")
            }
            // The pending state is single-use.
            val pending = call.session.oidcPending
            if (pending != null) call.updateSession { it.copy(oidcPending = null) }
            val code = params["code"]
            val returned = params["state"]
            if (pending == null || code == null || returned == null) {
                return renderLogin(HttpStatusCode.BadRequest, "The sign-in request is invalid or has expired.")
            }
            try {
                storeLogin(state.auth.oidcComplete(OidcCallback(code, returned), pending))
                call.seeOther("/")
            } catch (e: AuthException) {
                val (status, message) = authErrorMessage(e)
                renderLogin(status, message)
            }
        }

    suspend fun logout(ctx: RoutingContext): Unit =
        with(ctx) {
            val form = call.receiveParameters()
            if (!call.verifyCsrf(form["csrf"])) return call.respond(HttpStatusCode.Forbidden)
            call.sessions.clear<PortalSession>()
            call.seeOther(if (state.auth.isEnabled) "/login" else "/")
        }

    private fun authErrorMessage(e: AuthException): Pair<HttpStatusCode, String> =
        when (e) {
            is AuthException.InvalidCredentials -> HttpStatusCode.Unauthorized to "Invalid username or password."
            is AuthException.ProviderUnavailable ->
                HttpStatusCode.ServiceUnavailable to
                    "The sign-in service is temporarily unavailable. Please try again later."
            is AuthException.Disabled, is AuthException.ProviderNotEnabled ->
                HttpStatusCode.BadRequest to "This sign-in method is not enabled."
            is AuthException.Rejected -> HttpStatusCode.Unauthorized to "Sign-in was rejected."
        }

    // ------------------------------------------------------------- scripts

    private fun queryParams(ctx: RoutingContext): MutableMap<String, String> =
        ctx.call.request.queryParameters
            .entries()
            .associate { (k, v) -> k to v.first() }
            .toMutableMap()

    private fun queryErrorMessage(
        e: QueryException,
        timeoutSecs: Long,
    ): Pair<HttpStatusCode, String> =
        when (e) {
            is QueryException.Timeout ->
                HttpStatusCode.GatewayTimeout to
                    "The query did not finish within $timeoutSecs seconds."
            is QueryException.Unavailable ->
                HttpStatusCode.ServiceUnavailable to
                    "The database is currently unavailable."
            is QueryException.Database ->
                HttpStatusCode.InternalServerError to "The query could not be completed. The error was logged."
        }

    suspend fun scriptPage(ctx: RoutingContext): Unit =
        with(ctx) {
            val principal = webPrincipal() ?: return
            val nav = nav(principal)
            val params = queryParams(this)
            val sent = "_run" in params
            val outcome =
                state.scripts.execute(
                    principal,
                    call.parameters["name"].orEmpty(),
                    params,
                    PageChoice(params["_page"]?.toIntOrNull() ?: 1, params["_size"]?.toIntOrNull()),
                    if (sent) Invocation.FORM_SENT else Invocation.FORM_OPENED,
                )
            when (outcome) {
                ScriptOutcome.Forbidden ->
                    html(
                        HttpStatusCode.Forbidden,
                        "Forbidden",
                        nav,
                        messageBody("Access denied", "You do not have permission to run this script."),
                    )
                ScriptOutcome.NotFound ->
                    html(
                        HttpStatusCode.NotFound,
                        "Not found",
                        nav,
                        messageBody("Script not found", "There is no script with that name."),
                    )
                is ScriptOutcome.NeedsInput ->
                    html(
                        HttpStatusCode.OK,
                        outcome.script.title,
                        nav,
                        scriptBody(outcome.script, outcome.input, emptyList(), null, null),
                    )
                is ScriptOutcome.Ran -> {
                    val run = outcome.run
                    html(
                        HttpStatusCode.OK,
                        run.script.title,
                        nav,
                        scriptBody(run.script, outcome.input, emptyList(), run, null),
                        run.charts.any { it is ChartOutcome.Drawn },
                    )
                }
                is ScriptOutcome.Invalid ->
                    html(
                        HttpStatusCode.UnprocessableEntity,
                        outcome.script.title,
                        nav,
                        scriptBody(
                            outcome.script,
                            outcome.input,
                            outcome.errors,
                            null,
                            "Please correct the highlighted fields.",
                        ),
                    )
                is ScriptOutcome.Failed -> {
                    val (status, message) = queryErrorMessage(outcome.error, outcome.script.timeoutSecs)
                    html(
                        status,
                        outcome.script.title,
                        nav,
                        scriptBody(outcome.script, outcome.input, emptyList(), null, message),
                    )
                }
            }
        }

    // ----------------------------------------------------------------- API

    suspend fun apiList(ctx: RoutingContext): Unit =
        with(ctx) {
            val principal = apiPrincipal() ?: return
            val listed =
                if (call.request.queryParameters["all"] == "1") {
                    state.scripts.listAll(principal)
                } else {
                    state.scripts.listAvailable(principal)
                }
            call.respondJson(
                buildJsonObject {
                    putJsonArray("scripts") {
                        for (s in listed) {
                            addJsonObject {
                                put("name", s.name)
                                put("title", s.title)
                                put("description", s.description)
                                if (s.group == null) put("group", JsonNull) else put("group", s.group)
                                put("accessible", s.accessible)
                                putJsonArray("required_permissions") { s.requiredPermissions.forEach { add(it) } }
                            }
                        }
                    }
                },
            )
        }

    private fun runJson(run: ScriptRun) =
        buildJsonObject {
            put("script", run.script.name.value)
            put("title", run.script.title)
            putJsonArray("columns") { run.result.columns.forEach { add(it) } }
            putJsonArray("rows") {
                for (row in run.result.rows) add(JsonArray(row.map(::jsonOf)))
            }
            put("truncated", run.result.truncated)
            put("duration_ms", run.duration.inWholeMilliseconds)
        }

    suspend fun apiRun(ctx: RoutingContext): Unit =
        with(ctx) {
            val principal = apiPrincipal() ?: return
            when (
                val outcome =
                    state.scripts.execute(
                        principal,
                        call.parameters["name"].orEmpty(),
                        queryParams(this),
                    )
            ) {
                ScriptOutcome.NotFound -> call.respondJson(error("script not found"), HttpStatusCode.NotFound)
                ScriptOutcome.Forbidden -> call.respondJson(error("permission denied"), HttpStatusCode.Forbidden)
                is ScriptOutcome.NeedsInput -> call.respondJson(error("script needs input"), HttpStatusCode.BadRequest)
                is ScriptOutcome.Ran -> call.respondJson(runJson(outcome.run))
                is ScriptOutcome.Invalid ->
                    call.respondJson(
                        buildJsonObject {
                            put("error", "invalid parameters")
                            putJsonArray("fields") {
                                outcome.errors.forEach {
                                    addJsonObject {
                                        put("param", it.param)
                                        put("message", it.message)
                                    }
                                }
                            }
                        },
                        HttpStatusCode.UnprocessableEntity,
                    )
                is ScriptOutcome.Failed -> {
                    val (status, message) = queryErrorMessage(outcome.error, outcome.script.timeoutSecs)
                    call.respondJson(error(message), status)
                }
            }
        }

    // ---------------------------------------------------------- operations

    suspend fun readyz(ctx: RoutingContext): Unit =
        with(ctx) {
            val report = state.health.readiness()
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val body =
                buildJsonObject {
                    put("status", if (report.ready) "ready" else "not_ready")
                    putJsonArray("checks") {
                        for (c in report.checks) {
                            addJsonObject {
                                put("name", c.name)
                                put("status", if (c.ok) "up" else "down")
                                put("critical", c.critical)
                                if (c.detail == null) put("detail", JsonNull) else put("detail", c.detail)
                            }
                        }
                    }
                }
            call.respondJson(body, if (report.ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable)
        }
}
