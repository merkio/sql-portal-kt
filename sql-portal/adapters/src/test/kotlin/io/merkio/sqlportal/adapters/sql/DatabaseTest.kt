package io.merkio.sqlportal.adapters.sql

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.merkio.sqlportal.common.tls.TlsOptions
import io.merkio.sqlportal.domain.common.Value
import io.merkio.sqlportal.domain.query.QueryException
import io.merkio.sqlportal.domain.query.QueryRequest
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

/** `connectDatabase`, the domain adapter over the datasources, and migrations, on in-memory SQLite. */
class DatabaseTest :
    FunSpec({
        fun memory() = connectDatabase("sqlite::memory:", 1, 2.seconds, TlsOptions())

        fun req(
            sql: String,
            vararg params: Pair<String, Value>,
        ) = QueryRequest(sql, params.toList(), 10, 2.seconds)

        test("a file database in a missing directory cannot be opened read-only") {
            shouldThrow<DatabaseSetupException> {
                connectDatabase("sqlite:///nonexistent-dir/x.db?mode=ro", 1, 1.seconds, TlsOptions())
            }
        }

        test("values cross the adapter as domain values") {
            val db = memory()
            db.runScript("CREATE TABLE t (id INTEGER, name TEXT, price REAL);")
            db.runScript("INSERT INTO t VALUES (1, 'a', 1.5), (2, NULL, 2.5);")
            val r = db.execute(req("SELECT id, name, price FROM t WHERE id >= :lo ORDER BY id", "lo" to Value.Int(1)))
            r.columns shouldBe listOf("id", "name", "price")
            r.rows shouldBe
                listOf(
                    listOf(Value.Int(1), Value.Text("a"), Value.Float(1.5)),
                    listOf(Value.Int(2), Value.Null, Value.Float(2.5)),
                )
            shouldThrow<QueryException.Database> { db.execute(req("SELECT * FROM missing")) }
        }

        test("unsupported schemes and TLS on sqlite are rejected") {
            shouldThrow<DatabaseSetupException> { connectDatabase("mysql://x", 1, 1.seconds, TlsOptions()) }
            shouldThrow<DatabaseSetupException> {
                connectDatabase("sqlite::memory:", 1, 1.seconds, TlsOptions(insecure = true))
            }.message shouldContain "postgres://"
        }

        context("migrations") {
            test("nothing runs when migrations are not given") {
                val db = memory()
                prepareDatabase(db, null, emptyList())
                db.execute(req("SELECT COUNT(*) AS n FROM sqlite_master WHERE type = 'table'")).rows[0][0] shouldBe
                    Value.Int(0)
            }

            test("applies migrations in name order and skips other files") {
                val dir = tempdir().toPath()
                dir.resolve("0002_rows.sql").writeText("INSERT INTO t VALUES (1), (2);")
                dir.resolve("0001_table.sql").writeText("CREATE TABLE t (id INTEGER);")
                dir.resolve("notes.txt").writeText("not SQL")
                val db = memory()
                applyMigrations(db, dir) shouldBe 2
                db.execute(req("SELECT COUNT(*) AS n FROM t")).rows[0][0] shouldBe Value.Int(2)
            }

            test("a failing init file names the file") {
                val dir = tempdir().toPath()
                val bad = dir.resolve("bad.sql").apply { writeText("NOT SQL AT ALL;") }
                val db = memory()
                shouldThrow<DatabaseSetupException> { runInitFiles(db, listOf(bad)) }.message shouldContain "bad.sql"
            }
        }
    })
