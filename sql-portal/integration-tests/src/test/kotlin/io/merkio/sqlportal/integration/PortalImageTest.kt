package io.merkio.sqlportal.integration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration

/**
 * The shipped image (SCRIPT_PORTAL_IMAGE, default script-portal:test): HTTP
 * endpoints, metrics, security headers, the built-in commands, a startup
 * failure, and the image talking to PostgreSQL over a container network.
 * The image is never pulled; build it first (`make image-test`).
 */
class PortalImageTest :
    FunSpec({
        tags(Docker)
        val java = listOf("/usr/bin/java", "-cp", "/app/lib/*", "io.merkio.sqlportal.deployment.MainKt")

        fun portal(
            config: String? = null,
            network: Network? = null,
        ): GenericContainer<*> =
            GenericContainer(portalImage)
                .labelled()
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/readyz").forStatusCode(200).withStartupTimeout(Duration.ofSeconds(90)))
                .apply {
                    if (config != null) {
                        withCopyToContainer(Transferable.of(config), "/app/config/test.conf")
                        withEnv("SCRIPT_PORTAL_CONFIG", "/app/config/test.conf")
                    }
                    if (network != null) withNetwork(network)
                }

        val demo = portal()
        val http = HttpClient(Java)
        beforeSpec { demo.start() }
        afterSpec {
            http.close()
            demo.stop()
        }

        suspend fun get(
            container: GenericContainer<*>,
            path: String,
        ): HttpResponse = http.get("http://${container.host}:${container.getMappedPort(8080)}$path")

        suspend fun json(
            container: GenericContainer<*>,
            path: String,
        ): Pair<HttpStatusCode, JsonElement> {
            val r = get(container, path)
            return r.status to
                kotlinx.serialization.json.Json
                    .parseToJsonElement(r.bodyAsText())
        }

        test("the demo image is live and ready") {
            get(demo, "/healthz").status shouldBe HttpStatusCode.OK
            val (status, ready) = json(demo, "/readyz")
            status shouldBe HttpStatusCode.OK
            ready.jsonObject["status"] shouldBe JsonPrimitive("ready")
            ready.jsonObject["checks"]!!.jsonArray[0].jsonObject["status"] shouldBe JsonPrimitive("up")
        }

        test("the demo image lists and runs scripts through the API") {
            val (status, list) = json(demo, "/api/scripts")
            status shouldBe HttpStatusCode.OK
            list.jsonObject["scripts"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content } shouldContain
                "top_products"
            val (runStatus, run) = json(demo, "/api/scripts/top_products?limit=2")
            runStatus shouldBe HttpStatusCode.OK
            run.jsonObject["columns"].toString() shouldBe """["product","units","revenue"]"""
            run.jsonObject["rows"]!!.jsonArray.size shouldBe 2
            val (invalidStatus, invalid) = json(demo, "/api/scripts/top_products?limit=0")
            invalidStatus shouldBe HttpStatusCode.UnprocessableEntity
            invalid.jsonObject["fields"]!!.jsonArray[0].jsonObject["param"] shouldBe JsonPrimitive("limit")
        }

        test("the demo image serves HTML with security headers") {
            val page = get(demo, "/")
            page.status shouldBe HttpStatusCode.OK
            for (name in listOf(
                "Content-Security-Policy",
                "X-Frame-Options",
                "X-Content-Type-Options",
                "X-Request-Id",
            )) {
                page.headers[name] shouldNotBe null
            }
            page.bodyAsText() shouldContain "<html"
        }

        test("the demo image exposes Prometheus metrics") {
            get(demo, "/healthz")
            val metrics = get(demo, "/metrics")
            metrics.status shouldBe HttpStatusCode.OK
            val text = metrics.bodyAsText()
            text shouldContain "http_requests_total"
            text shouldContain "build_info{version="
            text shouldContain "auth_enabled 0"
        }

        test("the built-in healthcheck command reports health") {
            // Podman ignores HEALTHCHECK in OCI images, so the command is run directly.
            demo.execInContainer(*(java + listOf("healthcheck", "--path", "/readyz")).toTypedArray()).exitCode shouldBe
                0
            demo
                .execInContainer(
                    *(java + listOf("healthcheck", "--path", "/no-such-path")).toTypedArray(),
                ).exitCode shouldNotBe
                0
        }

        test("check-config validates the bundled configuration") {
            GenericContainer(portalImage)
                .labelled()
                .withCommand("check-config")
                .waitingFor(Wait.forLogMessage(".*configuration OK.*", 1).withStartupTimeout(Duration.ofSeconds(60)))
                .use { it.start() }
        }

        test("an invalid configuration fails at startup") {
            // Authentication enabled without any provider is rejected.
            GenericContainer(portalImage)
                .labelled()
                .withCopyToContainer(
                    Transferable.of("database.url = \"sqlite::memory:\"\nauth.enabled = true\n"),
                    "/app/config/test.conf",
                ).withEnv("SCRIPT_PORTAL_CONFIG", "/app/config/test.conf")
                .waitingFor(
                    Wait
                        .forLogMessage(
                            ".*neither auth.ldap nor auth.oidc.*",
                            1,
                        ).withStartupTimeout(Duration.ofSeconds(60)),
                ).use { it.start() }
        }

        test("the image runs scripts against PostgreSQL on a shared network") {
            Network.newNetwork().use { network ->
                val postgres =
                    PostgreSQLContainer(
                        "postgres:16-alpine",
                    ).labelled().withNetwork(network).withNetworkAliases("pg")
                postgres.use { pg ->
                    pg.start()
                    val config =
                        """
                        database.url = "postgres://${pg.username}:${pg.password}@pg:5432/${pg.databaseName}"
                        auth.enabled = false
                        scripts = [
                          { name = server_version, title = "Server version", sql = "SELECT version() AS version" }
                          {
                            name = doubled, title = Doubled, sql = "SELECT CAST(:n AS integer) * 2 AS doubled"
                            params = [{ name = n, type = integer, required = true }]
                          }
                        ]
                        """.trimIndent()
                    portal(config, network).use { app ->
                        app.start()
                        val (status, ready) = json(app, "/readyz")
                        status shouldBe HttpStatusCode.OK
                        ready.jsonObject["checks"]!!.jsonArray[0].jsonObject["status"] shouldBe JsonPrimitive("up")
                        val (_, version) = json(app, "/api/scripts/server_version")
                        val rows = version.jsonObject["rows"]!!.jsonArray
                        rows[0].jsonArray[0].jsonPrimitive.content shouldStartWith "PostgreSQL 16"
                        val (_, doubled) = json(app, "/api/scripts/doubled?n=21")
                        doubled.jsonObject["rows"]!!.jsonArray[0].jsonArray[0] shouldBe JsonPrimitive(42)
                    }
                }
            }
        }
    })
