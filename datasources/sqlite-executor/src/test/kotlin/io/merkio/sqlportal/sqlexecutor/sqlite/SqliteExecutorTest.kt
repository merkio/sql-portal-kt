package io.merkio.sqlportal.sqlexecutor.sqlite

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.merkio.sqlportal.sqlexecutor.Cell
import io.merkio.sqlportal.sqlexecutor.SqlException
import io.merkio.sqlportal.sqlexecutor.SqlExecutor
import io.merkio.sqlportal.sqlexecutor.SqlQuery
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class SqliteExecutorTest :
    FunSpec({
        suspend fun exec(): SqlExecutor {
            val e = SqliteExecutor.connect("sqlite::memory:", 1, 2.seconds)
            e.runScript(
                """
                CREATE TABLE t (id INTEGER, name TEXT, price REAL, flag INTEGER, note TEXT);
                CREATE TABLE flags (id INTEGER, active BOOLEAN);
                INSERT INTO flags VALUES (1, 1);
                INSERT INTO t VALUES (1, 'a', 1.5, 1, NULL), (2, 'b', 2.5, 0, 'x'), (3, 'c', 3.5, 1, NULL);
                """.trimIndent(),
            )
            return e
        }

        fun req(
            sql: String,
            vararg params: Pair<String, Cell>,
            maxRows: Int = 10,
        ) = SqlQuery(sql, params.toList(), maxRows, 2.seconds)

        test("selects typed rows") {
            val r = exec().query(req("SELECT id, name, price, note FROM t ORDER BY id"))
            r.columns shouldBe listOf("id", "name", "price", "note")
            r.rows shouldHaveSize 3
            r.rows[0] shouldBe listOf(Cell.Int(1), Cell.Text("a"), Cell.Float(1.5), Cell.Null)
            r.rows[1][3] shouldBe Cell.Text("x")
            r.truncated shouldBe false
        }

        test("computed columns decode") {
            val r = exec().query(req("SELECT COUNT(*) AS n, SUM(price) AS s, 'lit' AS l, 1 + 1 AS two FROM t"))
            r.rows[0] shouldBe listOf(Cell.Int(3), Cell.Float(7.5), Cell.Text("lit"), Cell.Int(2))
        }

        test("binds parameters by name including repeats") {
            val r =
                exec().query(
                    req(
                        "SELECT id FROM t WHERE id >= :lo AND id <= :hi AND name <> :skip AND id >= :lo ORDER BY id",
                        "lo" to Cell.Int(1),
                        "hi" to Cell.Int(3),
                        "skip" to Cell.Text("b"),
                    ),
                )
            r.rows.map { it[0] } shouldBe listOf(Cell.Int(1), Cell.Int(3))
        }

        test("null, float and bool params") {
            val r =
                exec().query(
                    req(
                        "SELECT :n IS NULL AS is_null, :f * 2 AS doubled",
                        "n" to Cell.Null,
                        "f" to Cell.Float(1.25),
                    ),
                )
            r.rows[0] shouldBe listOf(Cell.Int(1), Cell.Float(2.5))
        }

        test("sql injection attempts are just data") {
            val e = exec()
            e
                .query(
                    req("SELECT COUNT(*) FROM t WHERE name = :name", "name" to Cell.Text("a' OR '1'='1")),
                ).rows[0][0] shouldBe
                Cell.Int(0)
            e.query(req("SELECT * FROM t")).rows shouldHaveSize 3
        }

        test("truncates to max rows") {
            val r = exec().query(req("SELECT id FROM t ORDER BY id", maxRows = 2))
            r.rows shouldHaveSize 2
            r.truncated shouldBe true
        }

        test("an empty result has no rows") {
            val r = exec().query(req("SELECT id FROM t WHERE id > 100"))
            r.rows.shouldBeEmpty()
            r.truncated shouldBe false
        }

        test("database errors are mapped") {
            shouldThrow<SqlException.Failed> { exec().query(req("SELECT * FROM missing_table")) }
        }

        test("a missing parameter value is an error") {
            shouldThrow<SqlException.Failed> { exec().query(req("SELECT :x")) }.message shouldContain ":x"
        }

        test("boolean columns decode as integers") {
            exec().query(req("SELECT active FROM flags")).rows[0][0] shouldBe Cell.Int(1)
        }

        test("slow queries time out") {
            val e = SqliteExecutor.connect("sqlite::memory:", 1, 2.seconds)
            val endless =
                "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) SELECT COUNT(*) FROM c"
            shouldThrow<SqlException.Timeout> {
                e.query(
                    SqlQuery(endless, maxRows = 1, timeout = 300.milliseconds),
                )
            }
            // The connection is usable afterwards.
            e.query(req("SELECT 1 AS one")).rows[0][0] shouldBe Cell.Int(1)
        }

        test("ping works") {
            exec().ping()
        }

        test("in-memory databases keep one connection whatever is configured") {
            val db = SqliteExecutor.connect("sqlite::memory:", 5, 2.seconds)
            db.runScript("CREATE TABLE sales (x INTEGER);")
            db.runScript("INSERT INTO sales VALUES (1), (2);")
            db.query(req("SELECT COUNT(*) AS n FROM sales")).rows[0][0] shouldBe Cell.Int(2)
        }

        test("a file database keeps its connections open and serves concurrent queries") {
            val file = tempdir().toPath().resolve("shared.db")
            val db = SqliteExecutor.connect("sqlite:$file", 2, 2.seconds)
            db.runScript("CREATE TABLE n (x INTEGER); INSERT INTO n VALUES (1), (2), (3);")
            coroutineScope {
                (1..20).map { async { db.query(req("SELECT COUNT(*) FROM n")).rows[0][0] } }.awaitAll()
            }.toSet() shouldBe setOf(Cell.Int(3))
            db.close()
            shouldThrow<SqlException.Unavailable> { db.ping() }
        }

        test("a file database in a missing directory cannot be opened read-only") {
            shouldThrow<IllegalStateException> {
                SqliteExecutor.connect(
                    "sqlite:///nonexistent-dir/x.db?mode=ro",
                    1,
                    1.seconds,
                )
            }
        }

        test("sqlite urls") {
            SqliteUrl.parse("sqlite::memory:") shouldBe
                SqliteUrl("jdbc:sqlite::memory:", inMemory = true, readOnly = false)
            SqliteUrl.parse("sqlite://data.db?mode=ro") shouldBe
                SqliteUrl("jdbc:sqlite:data.db", inMemory = false, readOnly = true)
            SqliteUrl.parse("sqlite:///var/x.db").jdbcUrl shouldBe "jdbc:sqlite:/var/x.db"
            SqliteUrl.parse("sqlite://x?mode=memory").inMemory shouldBe true
        }
    })
