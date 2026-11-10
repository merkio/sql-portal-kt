package io.merkio.sqlportal.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.merkio.sqlportal.domain.access.Permission
import io.merkio.sqlportal.domain.access.PermissionSet
import io.merkio.sqlportal.domain.access.Principal
import io.merkio.sqlportal.domain.access.ScriptAccessRule
import io.merkio.sqlportal.domain.common.Value
import io.merkio.sqlportal.domain.query.PageRequest
import io.merkio.sqlportal.domain.query.QueryException
import io.merkio.sqlportal.domain.query.QueryResult
import io.merkio.sqlportal.domain.scripts.ChartDefinition
import io.merkio.sqlportal.domain.scripts.ChartKind
import io.merkio.sqlportal.domain.scripts.ChartOutcome
import io.merkio.sqlportal.domain.scripts.ParamDefinition
import io.merkio.sqlportal.domain.scripts.ParamKind
import io.merkio.sqlportal.domain.scripts.ScriptDefinition
import io.merkio.sqlportal.domain.scripts.ScriptException
import io.merkio.sqlportal.domain.scripts.ScriptName
import io.merkio.sqlportal.testing.FakeExecutor
import io.merkio.sqlportal.testing.InMemoryCatalog

class ScriptServiceTest :
    FunSpec({
        fun script(
            name: String,
            title: String = name.uppercase(),
            vararg perms: String,
        ) = ScriptDefinition
            .of(ScriptName.of(name), title, "SELECT 1")
            .copy(requiredPermissions = perms.map(Permission::of))

        fun principal(vararg grants: String) =
            Principal.anonymousAdmin().copy(permissions = PermissionSet(grants.toList()))

        fun service(
            scripts: List<ScriptDefinition>,
            executor: FakeExecutor = FakeExecutor.empty(),
            metrics: Metrics = NoopMetrics,
        ) = ScriptService(InMemoryCatalog(scripts), executor, metrics)

        val keyValue = QueryResult(listOf("k", "v"), listOf(listOf(Value.Text("a"), Value.Int(1))))

        test("lists only permitted scripts, sorted by title") {
            val svc =
                service(
                    listOf(
                        script("b", "Zeta", "a.read"),
                        script("a", "alpha", "a.read"),
                        script("c", "Hidden", "secret"),
                        script("d", "Open"),
                    ),
                )
            svc.listAvailable(principal("a.read")).map { it.title } shouldBe listOf("alpha", "Open", "Zeta")
        }

        test("a replacement access rule is used for listing and authorizing") {
            val denyAll = ScriptAccessRule { _, _ -> false }
            val svc = ScriptService(InMemoryCatalog(listOf(script("a"))), FakeExecutor.empty(), NoopMetrics, denyAll)
            svc.listAvailable(principal("x")).shouldBeEmpty()
            shouldThrow<ScriptException.Forbidden> { svc.authorize(principal("x"), "a") }
        }

        test("authorize distinguishes not found and forbidden") {
            val svc = service(listOf(script("a", "A", "x")))
            shouldThrow<ScriptException.NotFound> { svc.authorize(principal(), "missing") }
            shouldThrow<ScriptException.Forbidden> { svc.authorize(principal(), "a") }
            svc
                .authorize(principal("x"), "a")
                .script.name.value shouldBe "a"
        }

        test("listing and authorizing agree") {
            val svc = service(listOf(script("a", "A", "x"), script("b"), script("c", "C", "y")))
            for (grants in listOf(emptyArray(), arrayOf("x"), arrayOf("x", "y"))) {
                val p = principal(*grants)
                for (name in listOf("a", "b", "c")) {
                    val listed = svc.listAvailable(p).any { it.name == name }
                    val allowed = runCatching { svc.authorize(p, name) }.isSuccess
                    listed shouldBe allowed
                }
            }
        }

        test("run executes with bound params and builds charts") {
            val s =
                script("a", "A", "x")
                    .copy(
                        sql = "SELECT :n",
                        params = listOf(ParamDefinition("n", ParamKind.INTEGER, required = true)),
                        charts = listOf(ChartDefinition("c", ChartKind.BAR, "k", listOf("v"))),
                    ).validate()
            val exec = FakeExecutor.returning(keyValue)
            val metrics = RecordingMetrics()
            val svc = service(listOf(s), exec, metrics)
            val run = svc.run(svc.authorize(principal("x"), "a"), mapOf("n" to "7"))
            run.result.rows shouldHaveSize 1
            run.charts[0].shouldBeInstanceOf<ChartOutcome.Drawn>()
            exec.lastRequest.shouldNotBeNull().params shouldBe listOf("n" to Value.Int(7))
            metrics.scripts shouldBe listOf(Triple("a", "success", 1))
        }

        test("invalid params are rejected without touching the database") {
            val s =
                script("a").copy(
                    sql = "SELECT :n",
                    params = listOf(ParamDefinition("n", ParamKind.INTEGER, required = true)),
                )
            val exec = FakeExecutor.empty()
            val metrics = RecordingMetrics()
            val svc = service(listOf(s), exec, metrics)
            shouldThrow<ScriptException.InvalidParams> { svc.run(svc.authorize(principal(), "a"), emptyMap()) }
                .errors shouldHaveSize 1
            exec.lastRequest.shouldBeNull()
            metrics.scripts[0].second shouldBe "invalid_params"
        }

        test("denied access is recorded and never authorized") {
            val exec = FakeExecutor.empty()
            val metrics = RecordingMetrics()
            shouldThrow<ScriptException.Forbidden> {
                service(listOf(script("a", "A", "x")), exec, metrics).authorize(principal(), "a")
            }
            exec.lastRequest.shouldBeNull()
            metrics.scripts[0].second shouldBe "forbidden"
        }

        test("unknown scripts use a bounded metric label") {
            val metrics = RecordingMetrics()
            runCatching { service(emptyList(), metrics = metrics).authorize(principal(), "whatever") }
            metrics.scripts[0] shouldBe Triple("_unknown", "not_found", 0)
        }

        test("query errors are propagated and classified") {
            val metrics = RecordingMetrics()
            val svc = service(listOf(script("a")), FakeExecutor.failing(QueryException.Timeout()), metrics)
            val e = shouldThrow<ScriptException.Query> { svc.run(svc.authorize(principal(), "a"), emptyMap()) }
            e.error.shouldBeInstanceOf<QueryException.Timeout>()
            metrics.scripts[0].second shouldBe "timeout"
        }

        test("chart errors do not fail the run") {
            val s = script("a").copy(charts = listOf(ChartDefinition("c", ChartKind.LINE, "missing", listOf("v"))))
            val svc = service(listOf(s), FakeExecutor.returning(QueryResult(listOf("k"), listOf(listOf(Value.Int(1))))))
            val run = svc.run(svc.authorize(principal(), "a"), emptyMap())
            run.charts[0].shouldBeInstanceOf<ChartOutcome.Failed>()
        }

        test("a paged run counts, pages and charts with separate queries") {
            val s = script("a").copy(charts = listOf(ChartDefinition("c", ChartKind.BAR, "k", listOf("v"))))
            val count = QueryResult(listOf("n"), listOf(listOf(Value.Int(60))))
            val exec = FakeExecutor.returning(count)
            val svc = service(listOf(s), exec)
            val run = svc.run(svc.authorize(principal(), "a"), emptyMap(), PageRequest(2, 25))
            run.page.shouldNotBeNull().let { (it.number to it.total) shouldBe (2 to 60L) }
            exec.requests shouldHaveSize 3
            exec.requests[0].sql shouldContain "COUNT(*)"
            exec.requests[1].sql shouldContain "LIMIT 25 OFFSET 25"
        }

        test("a script without a table is never paged") {
            val s =
                script("a").copy(
                    showTable = false,
                    charts = listOf(ChartDefinition("c", ChartKind.BAR, "k", listOf("v"))),
                )
            val exec = FakeExecutor.returning(keyValue)
            val svc = service(listOf(s), exec)
            svc.run(svc.authorize(principal(), "a"), emptyMap(), PageRequest(2, 25)).page.shouldBeNull()
            exec.requests shouldHaveSize 1
        }

        test("every SQL statement is timed with its purpose and outcome") {
            val s = script("a").copy(charts = listOf(ChartDefinition("c", ChartKind.BAR, "k", listOf("v"))))
            val metrics = RecordingMetrics()
            val svc =
                service(
                    listOf(s),
                    FakeExecutor.returning(QueryResult(listOf("n"), listOf(listOf(Value.Int(5))))),
                    metrics,
                )
            svc.run(svc.authorize(principal(), "a"), emptyMap(), PageRequest(1, 25))
            metrics.sql.map { it.second } shouldBe listOf("count", "page", "chart")
            metrics.sql.all { it.first == "a" && it.third == "success" } shouldBe true

            val failing = RecordingMetrics()
            val bad = service(listOf(script("a")), FakeExecutor.failing(QueryException.Timeout()), failing)
            shouldThrow<ScriptException.Query> { bad.run(bad.authorize(principal(), "a"), emptyMap()) }
            failing.sql shouldBe listOf(Triple("a", "table", "timeout"))
        }

        test("execute reports not found, forbidden, input, invalid and failed as outcomes") {
            val needs =
                script("n").copy(
                    sql = "SELECT :n",
                    params = listOf(ParamDefinition("n", ParamKind.INTEGER, required = true)),
                )
            val flag =
                script("f")
                    .copy(
                        sql = "SELECT :b",
                        params = listOf(ParamDefinition("b", ParamKind.BOOLEAN, default = "true")),
                    ).validate()
            val svc = service(listOf(script("a", "A", "x"), needs, flag), FakeExecutor.returning(keyValue))
            svc.execute(principal(), "missing", emptyMap()) shouldBe ScriptOutcome.NotFound
            svc.execute(principal(), "a", emptyMap()) shouldBe ScriptOutcome.Forbidden
            svc
                .execute(principal(), "n", emptyMap(), invocation = Invocation.FORM_OPENED)
                .shouldBeInstanceOf<ScriptOutcome.NeedsInput>()
            svc
                .execute(principal(), "n", emptyMap(), invocation = Invocation.FORM_SENT)
                .shouldBeInstanceOf<ScriptOutcome.Invalid>()
                .errors shouldHaveSize 1
            svc
                .execute(principal("x"), "a", emptyMap(), invocation = Invocation.FORM_OPENED)
                .shouldBeInstanceOf<ScriptOutcome.Ran>()
            // An unchecked box is absent from a posted form; only then does it mean false.
            svc
                .execute(principal(), "f", emptyMap(), invocation = Invocation.FORM_SENT)
                .shouldBeInstanceOf<ScriptOutcome.Ran>()
                .input["b"] shouldBe "false"
            svc
                .execute(principal(), "f", emptyMap())
                .shouldBeInstanceOf<ScriptOutcome.Ran>()
                .input.keys
                .shouldBeEmpty()

            val failing = service(listOf(script("a")), FakeExecutor.failing(QueryException.Unavailable("down")))
            failing
                .execute(principal(), "a", emptyMap())
                .shouldBeInstanceOf<ScriptOutcome.Failed>()
                .error
                .shouldBeInstanceOf<QueryException.Unavailable>()
        }

        test("execute clamps an out-of-range page size to the script's") {
            val count = QueryResult(listOf("n"), listOf(listOf(Value.Int(60))))
            val svc = service(listOf(script("a").copy(pageSize = 10)), FakeExecutor.returning(count))
            val ran =
                svc
                    .execute(
                        principal(),
                        "a",
                        emptyMap(),
                        PageChoice(2, 100_000),
                    ).shouldBeInstanceOf<ScriptOutcome.Ran>()
            ran.run.page
                .shouldNotBeNull()
                .size shouldBe 10
        }
    })
