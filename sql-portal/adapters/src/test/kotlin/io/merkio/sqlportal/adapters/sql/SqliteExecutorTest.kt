package io.merkio.sqlportal.adapters.sql

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.merkio.sqlportal.common.tls.TlsOptions
import io.merkio.sqlportal.domain.common.Value
import io.merkio.sqlportal.domain.query.QueryException
import io.merkio.sqlportal.domain.query.QueryRequest
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SqliteExecutorTest :
    FunSpec({
        suspend fun exec(): SqliteExecutor {
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
            vararg params: Pair<String, Value>,
            maxRows: Int = 10,
        ) = QueryRequest(sql, params.toList(), maxRows, 2.seconds)

        test("selects typed rows") {
            val r = exec().execute(req("SELECT id, name, price, note FROM t ORDER BY id"))
            r.columns shouldBe listOf("id", "name", "price", "note")
            r.rows shouldHaveSize 3
            r.rows[0] shouldBe listOf(Value.Int(1), Value.Text("a"), Value.Float(1.5), Value.Null)
            r.rows[1][3] shouldBe Value.Text("x")
            r.truncated shouldBe false
        }

        test("computed columns decode") {
            val r = exec().execute(req("SELECT COUNT(*) AS n, SUM(price) AS s, 'lit' AS l, 1 + 1 AS two FROM t"))
            r.rows[0] shouldBe listOf(Value.Int(3), Value.Float(7.5), Value.Text("lit"), Value.Int(2))
        }

        test("binds parameters by name including repeats") {
            val r =
                exec().execute(
                    req(
                        "SELECT id FROM t WHERE id >= :lo AND id <= :hi AND name <> :skip AND id >= :lo ORDER BY id",
                        "lo" to Value.Int(1),
                        "hi" to Value.Int(3),
                        "skip" to Value.Text("b"),
                    ),
                )
            r.rows.map { it[0] } shouldBe listOf(Value.Int(1), Value.Int(3))
        }

        test("null, float and bool params") {
            val r =
                exec().execute(
                    req(
                        "SELECT :n IS NULL AS is_null, :f * 2 AS doubled",
                        "n" to Value.Null,
                        "f" to Value.Float(1.25),
                    ),
                )
            r.rows[0] shouldBe listOf(Value.Int(1), Value.Float(2.5))
        }

        test("sql injection attempts are just data") {
            val e = exec()
            e
                .execute(
                    req("SELECT COUNT(*) FROM t WHERE name = :name", "name" to Value.Text("a' OR '1'='1")),
                ).rows[0][0] shouldBe
                Value.Int(0)
            e.execute(req("SELECT * FROM t")).rows shouldHaveSize 3
        }

        test("truncates to max rows") {
            val r = exec().execute(req("SELECT id FROM t ORDER BY id", maxRows = 2))
            r.rows shouldHaveSize 2
            r.truncated shouldBe true
        }

        test("an empty result has no rows") {
            val r = exec().execute(req("SELECT id FROM t WHERE id > 100"))
            r.rows.shouldBeEmpty()
            r.truncated shouldBe false
        }

        test("database errors are mapped") {
            shouldThrow<QueryException.Database> { exec().execute(req("SELECT * FROM missing_table")) }
        }

        test("a missing parameter value is an error") {
            shouldThrow<QueryException.Database> { exec().execute(req("SELECT :x")) }.message shouldContain ":x"
        }

        test("boolean columns decode as integers") {
            exec().execute(req("SELECT active FROM flags")).rows[0][0] shouldBe Value.Int(1)
        }

        test("slow queries time out") {
            val e = SqliteExecutor.connect("sqlite::memory:", 1, 2.seconds)
            val endless =
                "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) SELECT COUNT(*) FROM c"
            shouldThrow<QueryException.Timeout> {
                e.execute(
                    QueryRequest(endless, maxRows = 1, timeout = 300.milliseconds),
                )
            }
            // The connection is usable afterwards.
            e.execute(req("SELECT 1 AS one")).rows[0][0] shouldBe Value.Int(1)
        }

        test("ping works") {
            exec().ping()
        }

        test("in-memory databases keep one connection whatever is configured") {
            val db = SqliteExecutor.connect("sqlite::memory:", 5, 2.seconds)
            db.runScript("CREATE TABLE sales (x INTEGER);")
            db.runScript("INSERT INTO sales VALUES (1), (2);")
            db.execute(req("SELECT COUNT(*) AS n FROM sales")).rows[0][0] shouldBe Value.Int(2)
        }

        test("sqlite urls") {
            SqliteUrl.parse("sqlite::memory:") shouldBe
                SqliteUrl("jdbc:sqlite::memory:", inMemory = true, readOnly = false)
            SqliteUrl.parse("sqlite://data.db?mode=ro") shouldBe
                SqliteUrl("jdbc:sqlite:data.db", inMemory = false, readOnly = true)
            SqliteUrl.parse("sqlite:///var/x.db").jdbcUrl shouldBe "jdbc:sqlite:/var/x.db"
            SqliteUrl.parse("sqlite://x?mode=memory").inMemory shouldBe true
        }

        test("a file database in a missing directory cannot be opened read-only") {
            shouldThrow<DatabaseSetupException> {
                connectDatabase("sqlite:///nonexistent-dir/x.db?mode=ro", 1, 1.seconds, TlsOptions())
            }
        }

        test("unsupported schemes and TLS on sqlite are rejected") {
            shouldThrow<DatabaseSetupException> { connectDatabase("mysql://x", 1, 1.seconds, TlsOptions()) }
            shouldThrow<DatabaseSetupException> {
                connectDatabase("sqlite::memory:", 1, 1.seconds, TlsOptions(insecure = true))
            }.message shouldContain "postgres://"
        }

        context("migrations") {
            test("nothing runs when migrations are not given") {
                val db = SqliteExecutor.connect("sqlite::memory:", 1, 2.seconds)
                prepareDatabase(db, null, emptyList())
                db.execute(req("SELECT COUNT(*) AS n FROM sqlite_master WHERE type = 'table'")).rows[0][0] shouldBe
                    Value.Int(0)
            }

            test("applies migrations in name order and skips other files") {
                val dir = tempdir().toPath()
                dir.resolve("0002_rows.sql").writeText("INSERT INTO t VALUES (1), (2);")
                dir.resolve("0001_table.sql").writeText("CREATE TABLE t (id INTEGER);")
                dir.resolve("notes.txt").writeText("not SQL")
                val db = SqliteExecutor.connect("sqlite::memory:", 1, 2.seconds)
                applyMigrations(db, dir) shouldBe 2
                db.execute(req("SELECT COUNT(*) AS n FROM t")).rows[0][0] shouldBe Value.Int(2)
            }

            test("a failing init file names the file") {
                val dir = tempdir().toPath()
                val bad = dir.resolve("bad.sql").apply { writeText("NOT SQL AT ALL;") }
                val db = SqliteExecutor.connect("sqlite::memory:", 1, 2.seconds)
                shouldThrow<DatabaseSetupException> { runInitFiles(db, listOf(bad)) }.message shouldContain "bad.sql"
            }
        }
    })
