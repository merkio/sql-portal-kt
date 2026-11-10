package io.merkio.sqlportal.adapters.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.merkio.sqlportal.domain.access.AuthProvider
import io.merkio.sqlportal.domain.access.User
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class ConfigTest :
    FunSpec({
        val dir = tempdir().toPath()
        val env = mapOf("A" to "alpha", "FLAG" to "false", "N" to "7")

        fun load(
            hocon: String,
            base: Path = dir,
        ) = ConfigLoader.fromString(hocon, base, env)

        fun invalid(hocon: String) = shouldThrow<ConfigError>(load(hocon)::validate).message.orEmpty()

        val noAuth = "database.url = \"sqlite::memory:\"\nauth.enabled = false\n"

        context("loading") {
            test("a minimal config uses defaults") {
                val c = load("database { url = \"sqlite::memory:\" }").app
                c.auth.enabled shouldBe true
                c.server.bind shouldBe "0.0.0.0:8080"
                c.observability.metricsPath shouldBe "/metrics"
                c.database.readOnlyScripts shouldBe true
                c.scripts.shouldBeEmpty()
            }

            test("environment substitution feeds typed fields, with defaults") {
                val c =
                    load(
                        """
                        database {
                          url = "sqlite::memory:"
                          url = ${'$'}{?DB}
                          max_connections = ${'$'}{N}
                        }
                        auth.enabled = ${'$'}{FLAG}
                        ui.title = "x-"${'$'}{A}
                        """.trimIndent(),
                    ).app
                c.database.url shouldBe "sqlite::memory:"
                c.database.maxConnections shouldBe 7
                c.auth.enabled shouldBe false
                c.ui.title shouldBe "x-alpha"
            }

            test("a missing variable without default is an error") {
                shouldThrow<ConfigError.MissingEnv> { load("database.url = ${'$'}{NOPE}") }.name shouldBe "NOPE"
            }

            test("quoted values, like SQL, are never expanded") {
                val c =
                    load(
                        noAuth + "scripts = [{ name = a, title = A, sql = \"SELECT '${'$'}{NOT_EXPANDED}'\" }]",
                    ).app
                c.scripts[0].sql shouldBe "SELECT '${'$'}{NOT_EXPANDED}'"
            }

            test("unknown keys are rejected with their path") {
                shouldThrow<ConfigError.Invalid> { load("database { url = x, bogus = 1 }") }.message shouldContain
                    "database.bogus"
                shouldThrow<ConfigError.Invalid> { load("database.url = x\nbogus = 1") }.message shouldContain "'bogus'"
            }

            test("syntax errors and missing files are reported") {
                shouldThrow<ConfigError.Syntax> { load("database { url = ") }
                shouldThrow<ConfigError.Io> { ConfigLoader.fromPath(Path.of("/definitely/not/here.conf")) }
                shouldThrow<ConfigError.Invalid> { load("server.bind = x") }.message shouldContain "database"
            }

            test("wrongly typed values name their key") {
                shouldThrow<ConfigError.Invalid> {
                    load(
                        "database { url = x, max_connections = lots }",
                    )
                }.message shouldContain
                    "database.max_connections must be a number"
                shouldThrow<ConfigError.Invalid> {
                    load(
                        "database.url = x\nauth.enabled = maybe",
                    )
                }.message shouldContain
                    "auth.enabled"
            }
        }

        context("mapping") {
            val full =
                """
                database.url = "sqlite::memory:"
                auth.enabled = false
                access {
                  default_roles = [viewer]
                  roles { viewer = ["scripts.public.read"], admin = ["*"] }
                  group_roles { "cn=admins,ou=groups" = [admin] }
                  user_roles { alice = [admin] }
                }
                scripts = [
                  {
                    name = sales
                    title = Sales
                    description = "Sales per region"
                    permissions = [scripts.sales.read]
                    max_rows = 50
                    timeout_secs = 5
                    sql = "SELECT region, SUM(amount) AS total FROM sales WHERE sold_on >= :from_date AND region = :region GROUP BY region"
                    params = [
                      { name = from_date, label = From, type = date, required = true, default = "2024-01-01" }
                      { name = region, type = select, default = "", options = ["", EU, { value = US, label = "United States" }] }
                    ]
                    charts = [{ type = bar, x = region, y = [total] }]
                  }
                ]
                """.trimIndent()

            test("maps a full config to the domain") {
                val cfg = load(full)
                cfg.validate()
                val s = cfg.buildScripts().single()
                s.name.value shouldBe "sales"
                s.maxRows shouldBe 50
                s.timeoutSecs shouldBe 5
                s.requiredPermissions[0].value shouldBe "scripts.sales.read"
                s.params[0].label shouldBe "From"
                s.params[1].options.map { it.label } shouldBe listOf("", "EU", "United States")
                s.charts[0].title shouldBe "Sales"
                s.runsWithoutInput shouldBe true
            }

            test("builds the access policy with quoted DN keys") {
                val policy = load(full).buildPolicy()
                policy.resolve(User("alice", "a", provider = AuthProvider.LDAP)).roles shouldContain "admin"
                policy
                    .resolve(
                        User("x", "x", groups = listOf("CN=Admins,OU=Groups"), provider = AuthProvider.LDAP),
                    ).roles shouldContain
                    "admin"
            }

            test("enabled auth requires a provider") {
                invalid("database.url = x") shouldContain "neither auth.ldap nor auth.oidc"
                invalid(
                    "database.url = x\nauth.ldap { enabled = false, url = l, bind_dn = b, bind_password = p, base_dn = d }",
                ) shouldContain "neither"
                load(noAuth).validate()
            }

            test("scripts need exactly one SQL source") {
                invalid(noAuth + "scripts = [{ name = a, title = A }]") shouldContain "exactly one"
            }

            test("write statements are rejected when read-only") {
                invalid(noAuth + "scripts = [{ name = a, title = A, sql = \"DELETE FROM t\" }]") shouldContain
                    "read-only"
                load(
                    noAuth +
                        "database.read_only_scripts = false\nscripts = [{ name = a, title = A, sql = \"DELETE FROM t\" }]",
                ).validate()
            }

            test("duplicate names, undeclared placeholders and bad defaults are rejected") {
                invalid(
                    noAuth +
                        "scripts = [{name = a, title = A, sql = \"SELECT 1\"}, {name = a, title = B, sql = \"SELECT 2\"}]",
                ) shouldContain
                    "duplicate"
                invalid(noAuth + "scripts = [{name = a, title = A, sql = \"SELECT :x\"}]") shouldContain ":x"
                invalid(
                    noAuth +
                        "scripts = [{name = a, title = A, sql = \"SELECT :n\", params = [{name = n, type = integer, default = abc}]}]",
                ) shouldContain "default"
                invalid(
                    noAuth +
                        "scripts = [{name = a, title = A, sql = \"SELECT :n\", params = [{name = n, type = colour}]}]",
                ) shouldContain "unknown type"
            }

            test("SQL is read from a file relative to the config") {
                val base = dir.resolve("sqlfile").createDirectories()
                base.resolve("q.sql").writeText("SELECT 42 AS answer")
                load(
                    noAuth + "scripts = [{name = a, title = A, sql_file = q.sql}]",
                    base,
                ).buildScripts()[0].sql shouldBe
                    "SELECT 42 AS answer"
                shouldThrow<ConfigError.Io> {
                    load(
                        noAuth + "scripts = [{name = a, title = A, sql_file = nope.sql}]",
                    ).buildScripts()
                }
            }

            test("bad metrics paths and URLs are rejected") {
                invalid(noAuth + "observability.metrics_path = metrics") shouldContain "metrics_path"
                invalid(noAuth + "server.public_url = nope") shouldContain "public_url"
                invalid(noAuth + "ui.chartjs_url = nope") shouldContain "chartjs_url"
                load(noAuth + "ui.chartjs_url = \"/static/chart.js\"").validate()
            }

            test("page sizes are read and bounded") {
                fun build(size: String) =
                    load(
                        noAuth + "scripts = [{name = a, title = A, sql = \"SELECT 1\", page_size = $size}]",
                    )
                build("40").buildScripts()[0].pageSize shouldBe 40
                shouldThrow<ConfigError>(build("0")::validate)
                shouldThrow<ConfigError>(build("501")::validate)
            }

            test("numeric option values and labels are read as text") {
                val cfg =
                    load(
                        noAuth +
                            """
                            scripts = [{
                              name = a, title = A, sql = "SELECT 1"
                              params = [{ name = year, type = select, options = [{ value = "2024", label = 2024 }, { value = 2023 }, ALL] }]
                            }]
                            """.trimIndent(),
                    )
                cfg
                    .buildScripts()[0]
                    .params[0]
                    .options
                    .map { it.value to it.label } shouldBe
                    listOf("2024" to "2024", "2023" to "2023", "ALL" to "ALL")
            }

            test("groups are trimmed and must not be empty") {
                fun build(group: String) =
                    load(
                        noAuth + "scripts = [{name = a, title = A, sql = \"SELECT 1\", group = \"$group\"}]",
                    )
                build("  Sales  ").buildScripts()[0].group shouldBe "Sales"
                shouldThrow<ConfigError>(build("   ")::validate)
            }

            test("migrations run only when enabled and need a directory") {
                load(noAuth).databaseMigrations().shouldBeNull()
                load(noAuth + "database.migrations.enabled = false").databaseMigrations().shouldBeNull()
                invalid(noAuth + "database.migrations { enabled = true, dir = does-not-exist }") shouldContain
                    "not a directory"
                dir.resolve("migrations/sqlite").createDirectories()
                load(noAuth + "database.migrations.enabled = true").databaseMigrations() shouldBe
                    dir.resolve("migrations/sqlite")
            }

            test("the runtime value reflects the switches") {
                load(noAuth).toRuntime().auth shouldBe AuthRuntime.Disabled
                val rt =
                    load(
                        "database.url = x\nauth.oidc { issuer_url = \"https://idp\", client_id = portal }\n" +
                            "access.external.url = \"https://perms.internal/p\"",
                    ).toRuntime()
                rt.auth
                    .shouldBeInstanceOf<AuthRuntime.Enabled>()
                    .oidc!!
                    .settings.clientId shouldBe "portal"
                rt.grants
                    .shouldBeInstanceOf<GrantsRuntime.External>()
                    .connection.settings.cacheTtl.inWholeSeconds shouldBe
                    300
            }
        }

        context("tls") {
            val ca = dir.resolve("ca.pem").apply { writeText("dummy") }

            test("tls is rejected for sqlite") {
                invalid(
                    "database { url = \"sqlite::memory:\", tls.insecure = true }\nauth.enabled = false",
                ) shouldContain
                    "postgres://"
            }

            test("missing files and conflicting options are reported") {
                invalid(
                    "database { url = \"postgres://db/app\", tls.ca_file = no-such-ca.pem }\nauth.enabled = false",
                ) shouldContain
                    "not found"
                invalid(
                    "database { url = \"postgres://db/app\", tls { ca_file = ca.pem, insecure = true } }\nauth.enabled = false",
                ) shouldContain
                    "cannot be combined"
                invalid(
                    "database { url = \"postgres://db/app\", tls { ca_file = ca.pem, cert_file = tls.crt } }\nauth.enabled = false",
                ) shouldContain
                    "set together"
                invalid(
                    "database { url = \"postgres://db/app\", tls { cert_file = ca.pem, key_file = ca.pem } }\nauth.enabled = false",
                ) shouldContain
                    "needs ca_file"
            }

            test("the CA file resolves against the config directory") {
                val cfg =
                    load(
                        "database { url = \"clickhouses://ch.internal/analytics\", tls.ca_file = ca.pem }\nauth.enabled = false",
                    )
                cfg.validate()
                cfg.databaseTls().caFile shouldBe ca
            }

            val providers =
                "auth.ldap { url = \"%s\", bind_dn = svc, bind_password = p, base_dn = \"dc=x\" }\n" +
                    "auth.oidc { issuer_url = \"https://idp.internal\", client_id = portal }\n"

            test("the shared block applies to every TLS connection and only to those") {
                val cfg =
                    load(
                        "tls.ca_file = ca.pem\ndatabase.url = \"postgres://db/app\"\n" +
                            providers.format("ldaps://ldap.internal"),
                    )
                cfg.validate()
                listOf(cfg.databaseTls(), cfg.ldapTls(), cfg.oidcTls()).map { it.caFile } shouldBe listOf(ca, ca, ca)

                val plain =
                    load(
                        "tls.ca_file = ca.pem\ndatabase.url = \"sqlite::memory:\"\n" +
                            providers.format("ldap://ldap.internal"),
                    )
                plain.validate()
                plain.databaseTls().isSet shouldBe false
                plain.ldapTls().isSet shouldBe false
                plain.oidcTls().isSet shouldBe true
            }

            test("a per-connection block replaces the shared one") {
                val cfg =
                    load(
                        "tls.insecure = true\ndatabase { url = \"postgres://db/app\", tls.ca_file = ca.pem }\nauth.enabled = false",
                    )
                cfg.validate()
                cfg.databaseTls().caFile shouldBe ca
                cfg.databaseTls().insecure shouldBe false
            }

            test("explicit LDAP TLS on plain LDAP is rejected") {
                invalid(
                    "database.url = \"sqlite::memory:\"\n" +
                        "auth.ldap { url = \"ldap://l\", tls.ca_file = ca.pem, bind_dn = s, bind_password = p, base_dn = d }",
                ) shouldContain "ldaps://"
            }
        }

        context("external grants") {
            fun external(body: String) = load(noAuth + "access.external { $body }")

            test("the service is accepted with defaults") {
                val cfg = external("url = \"https://perms.internal/permissions\"")
                cfg.validate()
                cfg.app.access.external!!
                    .let { (it.timeoutSecs to it.cacheTtlSecs) shouldBe (3L to 300L) }
            }

            test("the URL must be http or https, and explicit TLS needs https") {
                shouldThrow<ConfigError>(external("url = \"ftp://p/x\"")::validate).message shouldContain
                    "http:// or https://"
                shouldThrow<ConfigError>(
                    external("url = \"http://p/x\", tls.insecure = true")::validate,
                ).message shouldContain
                    "https://"
            }

            test("the shared block applies only to https services") {
                val cfg = load("tls.insecure = true\n" + noAuth + "access.external.url = \"http://p/x\"")
                cfg.validate()
                cfg.grantsTls().isSet shouldBe false
            }

            test("zero timeouts are rejected") {
                shouldThrow<ConfigError>(external("url = \"https://p/x\", cache_ttl_secs = 0")::validate)
            }
        }
    })
