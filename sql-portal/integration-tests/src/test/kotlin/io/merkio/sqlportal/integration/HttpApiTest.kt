package io.merkio.sqlportal.integration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HttpApiTest :
    FunSpec({
        lateinit var h: Harness
        lateinit var open: Harness
        beforeSpec {
            h = Harness.start()
            open = Harness.start(Options(authEnabled = false))
        }
        afterSpec {
            h.close()
            open.close()
        }

        context("authentication") {
            test("anonymous users are redirected to the login page") {
                h.get("/").let {
                    it.status shouldBe HttpStatusCode.SeeOther
                    it.location shouldBe "/login"
                }
                h.get("/scripts/sales_by_region").location shouldBe "/login"
            }

            test("the login page offers the LDAP form and the OIDC link") {
                val r = h.get("/login")
                r.status shouldBe HttpStatusCode.OK
                r.body shouldContain "name=\"username\""
                r.body shouldContain "/login/oidc"
                r.sessionCookie.shouldNotBeNull()
            }

            test("an LDAP login lists only permitted scripts") {
                val cookie = h.loginLdap("alice", "secret")
                val r = h.get("/", cookie)
                r.status shouldBe HttpStatusCode.OK
                r.body shouldContain "Sales by region"
                r.body shouldContain "Monthly trend"
                r.body shouldContain "Top products"
                r.body shouldNotContain "Order sizes"
                r.body shouldContain "action=\"/logout\""
            }

            test("forbidden and missing scripts") {
                val cookie = h.loginLdap("alice", "secret")
                h.get("/scripts/order_sizes", cookie).status shouldBe HttpStatusCode.Forbidden
                h.get("/scripts/nope", cookie).status shouldBe HttpStatusCode.NotFound
                h.get("/no/such/page").status shouldBe HttpStatusCode.NotFound
            }

            test("a wrong password is rejected generically") {
                val page = h.get("/login")
                val cookie = page.sessionCookie!!
                val r = h.postForm("/login", "username=alice&password=wrong&csrf=${page.csrf()}", cookie)
                r.status shouldBe HttpStatusCode.Unauthorized
                r.body shouldContain "Invalid username or password."
                h.get("/", cookie).status shouldBe HttpStatusCode.SeeOther
            }

            test("login requires a valid CSRF token") {
                val cookie = h.get("/login").sessionCookie!!
                h.postForm("/login", "username=alice&password=secret&csrf=forged", cookie).status shouldBe
                    HttpStatusCode.Forbidden
            }

            test("login rotates the session id") {
                val before = h.get("/login").sessionCookie
                val after = h.loginLdap("alice", "secret")
                after shouldNotBe before
            }

            test("logout ends the session") {
                val cookie = h.loginLdap("alice", "secret")
                val csrf = h.get("/", cookie).csrf()
                h.postForm("/logout", "csrf=bad", cookie).status shouldBe HttpStatusCode.Forbidden
                val r = h.postForm("/logout", "csrf=$csrf", cookie)
                r.status shouldBe HttpStatusCode.SeeOther
                r.location shouldBe "/login"
                h.get("/", cookie).status shouldBe HttpStatusCode.SeeOther
            }

            test("the OIDC flow grants roles from groups") {
                val start = h.get("/login/oidc")
                start.status shouldBe HttpStatusCode.SeeOther
                start.location!! shouldStartWith "https://idp.example.org/authorize"
                val callback = h.get("/auth/callback?code=good-code&state=state-123", start.sessionCookie)
                callback.status shouldBe HttpStatusCode.SeeOther
                callback.location shouldBe "/"
                val cookie = callback.sessionCookie!!
                // The fake OIDC user is in group "admins" -> admin role -> admin-only script visible.
                h.get("/", cookie).body shouldContain "Order sizes"
                h.get("/scripts/order_sizes", cookie).status shouldBe HttpStatusCode.OK
            }

            test("the OIDC callback rejects a state mismatch, replays and missing sessions") {
                val cookie = h.get("/login/oidc").sessionCookie
                h.get("/auth/callback?code=good-code&state=forged", cookie).status shouldBe HttpStatusCode.Unauthorized
                // The pending state is single-use.
                h.get("/auth/callback?code=good-code&state=state-123", cookie).status shouldBe HttpStatusCode.BadRequest
                h.get("/auth/callback?code=x&state=y").status shouldBe HttpStatusCode.BadRequest
                h.get("/auth/callback?error=access_denied").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        context("authentication disabled") {
            test("everything is served without login") {
                val home = open.get("/")
                home.status shouldBe HttpStatusCode.OK
                home.body shouldContain "Order sizes"
                home.body shouldNotContain "/logout"
                open.get("/scripts/order_sizes?_run=1&region=EU").status shouldBe HttpStatusCode.OK
                open.get("/login").location shouldBe "/"
            }

            test("login attempts are rejected") {
                open.get("/login/oidc").status shouldBe HttpStatusCode.BadRequest
            }
        }

        context("scripts") {
            test("text search filters rows on the server") {
                val widgets = open.get("/scripts/find_sales?_run=1&q=widget&from_date=2024-01-01&to_date=2024-06-30")
                widgets.status shouldBe HttpStatusCode.OK
                widgets.body shouldContain "Widget"
                widgets.body shouldNotContain "Gizmo"
                val apac = open.get("/scripts/find_sales?_run=1&q=apac&from_date=2024-01-01&to_date=2024-06-30")
                apac.body shouldContain "APAC"
                apac.body shouldNotContain ">EU<"
                open
                    .get(
                        "/scripts/find_sales?_run=1&q=zzz-no-match&from_date=2024-01-01&to_date=2024-06-30",
                    ).body shouldContain
                    "returned no rows"
            }

            test("the script page renders form, table and charts") {
                val r = open.get("/scripts/sales_by_region?_run=1&from_date=2024-01-01&to_date=2024-03-31")
                r.status shouldBe HttpStatusCode.OK
                r.body shouldContain "name=\"from_date\""
                r.body shouldContain "value=\"2024-03-31\""
                r.body shouldContain "<th scope=\"col\" tabindex=\"0\" aria-sort=\"none\">total</th>"
                r.body shouldContain "data-chart=\"chart-data-0\""
                r.body shouldContain "data-chart=\"chart-data-1\""
                r.body shouldContain "\"type\":\"bar\""
                r.body shouldContain "\"type\":\"doughnut\""
                r.body shouldContain "https://cdn.example.org/chart.js"
                r.body shouldContain "3 rows in"
            }

            test("scripts with defaults run on page load") {
                val r = open.get("/scripts/monthly_trend")
                r.status shouldBe HttpStatusCode.OK
                r.body shouldContain "2024-01"
                r.body shouldContain "\"type\":\"line\""
            }

            test("invalid parameters return 422 with field errors") {
                val r = open.get("/scripts/top_products?_run=1&limit=99&region=MARS")
                r.status shouldBe HttpStatusCode.UnprocessableEntity
                r.body shouldContain "must be at most 20"
                r.body shouldContain "not an allowed option"
                r.body shouldContain "field invalid"
            }

            test("an unchecked checkbox means false after submit") {
                open.get("/scripts/top_products?_run=1&region=APAC&limit=5").body shouldContain "returned no rows"
                open.get("/scripts/top_products?_run=1&region=APAC&limit=5&include_apac=true").body shouldContain
                    "<table"
            }

            test("a scatter script without table renders the chart only") {
                val r = open.get("/scripts/order_sizes?_run=1&region=EU")
                r.status shouldBe HttpStatusCode.OK
                r.body shouldContain "\"type\":\"scatter\""
                r.body shouldNotContain "<table"
            }

            test("HTML is escaped in the parameter echo") {
                val r = open.get("/scripts/sales_by_region?_run=1&from_date=%22%3E%3Cscript%3Ealert(1)%3C/script%3E")
                r.status shouldBe HttpStatusCode.UnprocessableEntity
                r.body shouldNotContain "<script>alert(1)"
            }
        }

        context("API") {
            test("the API requires authentication and returns JSON") {
                val anonymous = h.get("/api/scripts")
                anonymous.status shouldBe HttpStatusCode.Unauthorized
                anonymous.json().jsonObject["error"] shouldBe JsonPrimitive("authentication required")

                val cookie = h.loginLdap("alice", "secret")
                val list = h.get("/api/scripts", cookie)
                list.status shouldBe HttpStatusCode.OK
                list
                    .json()
                    .jsonObject["scripts"]!!
                    .jsonArray
                    .map { it.jsonObject["name"]!!.jsonPrimitive.content } shouldBe
                    listOf(
                        "find_sales",
                        "gallery_all_types",
                        "gallery_quarterly_bars",
                        "gallery_brand_share",
                        "gallery_sensor_readings",
                        "gallery_survey_bands",
                        "gallery_web_traffic",
                        "monthly_trend",
                        "sales_by_region",
                        "showcase_table",
                        "showcase_chart_error",
                        "showcase_params",
                        "showcase_column_types",
                        "showcase_wide_table",
                        "showcase_filter_bar_pie",
                        "showcase_filter_monthly_line",
                        "showcase_top_n_switch",
                        "showcase_scatter_filters",
                        "showcase_search_paged",
                        "showcase_big_table",
                        "showcase_bar",
                        "showcase_line",
                        "showcase_pie",
                        "showcase_doughnut",
                        "showcase_scatter",
                        "showcase_mixed",
                        "showcase_truncated",
                        "showcase_empty",
                        "top_products",
                    )

                val run = h.get("/api/scripts/top_products?limit=2", cookie)
                run.status shouldBe HttpStatusCode.OK
                run.json().jsonObject["columns"].toString() shouldBe """["product","units","revenue"]"""
                run
                    .json()
                    .jsonObject["rows"]!!
                    .jsonArray.size shouldBe 2

                h.get("/api/scripts/order_sizes", cookie).status shouldBe HttpStatusCode.Forbidden
                h.get("/api/scripts/zzz", cookie).status shouldBe HttpStatusCode.NotFound
                val bad = h.get("/api/scripts/top_products?limit=0", cookie)
                bad.status shouldBe HttpStatusCode.UnprocessableEntity
                bad
                    .json()
                    .jsonObject["fields"]!!
                    .jsonArray[0]
                    .jsonObject["param"] shouldBe JsonPrimitive("limit")
            }
        }

        context("operations") {
            test("health and readiness endpoints") {
                val live = h.get("/healthz")
                live.status shouldBe HttpStatusCode.OK
                live.json().jsonObject["status"] shouldBe JsonPrimitive("ok")
                val ready = h.get("/readyz")
                ready.status shouldBe HttpStatusCode.OK
                ready.json().jsonObject["status"] shouldBe JsonPrimitive("ready")
                ready
                    .json()
                    .jsonObject["checks"]!!
                    .jsonArray[0]
                    .jsonObject["name"] shouldBe JsonPrimitive("database")
                ready.headers["Cache-Control"] shouldBe "no-store"
            }

            test("readiness fails on a critical probe but not on an optional one") {
                Harness.start(Options(extraProbes = listOf(FailingProbe("broken", true)))).use { failing ->
                    val r = failing.get("/readyz")
                    r.status shouldBe HttpStatusCode.ServiceUnavailable
                    r.json().jsonObject["status"] shouldBe JsonPrimitive("not_ready")
                    failing.get("/healthz").status shouldBe HttpStatusCode.OK
                }
                Harness.start(Options(extraProbes = listOf(FailingProbe("flaky", false)))).use { flaky ->
                    val r = flaky.get("/readyz")
                    r.status shouldBe HttpStatusCode.OK
                    // Extra probes are reported after the database and OIDC probes.
                    val checks = r.json().jsonObject["checks"]!!.jsonArray
                    checks.last().jsonObject["status"] shouldBe JsonPrimitive("down")
                }
            }

            test("the metrics endpoint serves Prometheus text") {
                val r = h.get("/metrics")
                r.status shouldBe HttpStatusCode.OK
                r.headers["Content-Type"]!! shouldStartWith "text/plain; version=0.0.4"
                r.body shouldBe "# test metrics\n"
            }

            test("security headers and static assets") {
                val r = h.get("/login")
                val csp = r.headers["Content-Security-Policy"]!!
                csp shouldContain "script-src 'self' https://cdn.example.org;"
                csp shouldNotContain "unsafe-inline"
                r.headers["X-Frame-Options"] shouldBe "DENY"
                r.headers["X-Content-Type-Options"] shouldBe "nosniff"
                r.headers["X-Request-Id"].shouldNotBeNull()
                h.get("/static/app.css").headers["Content-Type"]!! shouldStartWith "text/css"
                h.get("/static/app.js").headers["Content-Type"]!! shouldStartWith "text/javascript"
            }

            test("the session cookie is HTTP-only and SameSite=Lax") {
                val setCookie =
                    h
                        .get(
                            "/login",
                        ).headers
                        .getAll("Set-Cookie")!!
                        .first { it.startsWith("sp_session=") }
                        .lowercase()
                setCookie shouldContain "httponly"
                setCookie shouldContain "samesite=lax"
            }
        }
    })
