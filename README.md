# Script Portal (Kotlin)

A small web service that turns **SQL scripts defined in a configuration file** into authenticated web pages.
Each script gets a generated parameter form, a result table and optional charts. Users sign in
with **LDAP** and/or **OpenID Connect (OIDC)**; what they may run is controlled by a
role → permission model in the same config. Authentication can be switched off globally with a
single flag.

This is the Kotlin port of [sql-portal](../sql-portal) (Rust/axum): the same features, pages, API and
layer structure, built with **Kotlin 2, Ktor 3 and Gradle**, structured as clean architecture / DDD and
developed test-first. Differences from the Rust version are listed [at the end](#differences-from-the-rust-version).

## Features

- **Config-driven scripts**: SQL (inline or from a file), typed parameters (`text`, `integer`,
  `number`, `boolean`, `date`, `select`), and charts (`line`, `bar`, `pie`, `doughnut`, `scatter`).
  Parameters are **bound**, never interpolated into SQL.
- **LDAP login** (search-then-bind with a service account, group lookup via `memberOf` or a group search).
- **OIDC login** (authorization code flow + PKCE, ID-token verification, group claim).
- **One flag disables every authorizer**: `auth.enabled = false`.
- **Permissions in config**: roles carry permissions (`*` and `prefix.*` wildcards); users and
  groups carry roles; each script lists the permissions it requires.
- **Pages**: `/` lists the scripts you may run; `/scripts/<name>` shows the form and results.
  JSON API under `/api/scripts`.
- **Operations**: `/healthz` (liveness), `/readyz` (readiness, checks the database),
  `/metrics` (Prometheus), structured logs (text or JSON) with request ids.
- **Docker image** (distroless, non-root, built-in `HEALTHCHECK`) and a JVM distribution for every OS.

## Project structure

One Gradle build (Kotlin DSL) with an included build for the conventions, in three groups:

```
build-logic/     included build: convention plugins (Kotlin, ktlint, detekt, JaCoCo)
gradle/libs.versions.toml   every library and plugin version
common/          shared libraries, independent of the portal
  tls-config/        TLS options as JSSE objects (CA, client identity, insecure)
  prometheus-metrics/  Prometheus registry with histogram buckets
  template-render/   Freemarker template sets, content versions
datasources/     the dialect contract, no I/O
  sql-executor/      Dialect, Cell, Statement, placeholder scanning
sql-portal/      the application
  domain/            model and ports, no web/database/identity-provider libraries
  application/       use cases: AuthService, ScriptService, the Metrics port
  adapters/          port implementations: sql/{postgres,sqlite,clickhouse}/ (dialect + I/O per engine), auth/, grants/, config/
  api/               everything Ktor: routes, sessions, middleware, views, templates
  deployment/        composition root (one Koin module per configured part) + the CLI
  testing/           in-memory fakes of the ports (test dependency only)
  integration-tests/ in-process, database and Docker-image tests
```

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the layer rules.

## Quick start

Requirements: JDK 21 (the Gradle wrapper fetches everything else).

```bash
# demo: authentication disabled, in-memory SQLite with generated sales data
make run          # ./gradlew :sql-portal:deployment:run --args="--config $PWD/config/config.demo.conf"
# open http://localhost:8080
```

```bash
# or with Docker (the image defaults to the demo config)
docker build -t script-portal .
docker run --rm -p 8080:8080 script-portal

# portal + Prometheus
docker compose up --build
```

> ⚠️ The bundled **demo config disables authentication**. Never expose it beyond a trusted machine.

The distribution (`make dist`, or the archive attached to a release) contains `bin/script-portal`
(and `bin/script-portal.bat` for Windows):

```bash
script-portal --config my.conf check-config    # validate configuration and exit
script-portal --config my.conf serve           # run (default)
script-portal healthcheck --addr 127.0.0.1:8080 --path /readyz
```

`SCRIPT_PORTAL_CONFIG` (config path, default `config/config.conf`) and `SCRIPT_PORTAL_BIND` (listen
address) may be set as environment variables. `JAVA_OPTS` passes options to the JVM.

## Configuration

The configuration is [HOCON](https://github.com/lightbend/config/blob/main/HOCON.md).
See [`config/config.example.conf`](config/config.example.conf) for the complete, commented reference and
[`config/config.demo.conf`](config/config.demo.conf) for a runnable example.

**Environment.** Write the default, then an optional override from the environment on the next line;
booleans and numbers work too:

```hocon
auth {
  enabled = true
  enabled = ${?AUTH_ENABLED}
}
database.url = ${DATABASE_URL}     # without "?" the variable is required
```

Values in quotes, including SQL in triple quotes, are never expanded. Quote values that contain `:` or `//`,
e.g. `"sqlite::memory:"` and URLs. **Unknown keys are rejected**, so a typo is reported instead of ignored.

### Authentication

```hocon
auth {
  enabled = true        # false = ALL authorizers off; everyone is an anonymous administrator
  ldap { enabled = true, url = "ldaps://ldap.example.org", bind_dn = "...", bind_password = ${LDAP_BIND_PASSWORD}, base_dn = "..." }
  oidc { enabled = true, issuer_url = "https://idp.example.org/realms/main", client_id = portal, client_secret = ${OIDC_CLIENT_SECRET} }
}
```

The OIDC redirect URI to register at the identity provider is `<server.public_url>/auth/callback`.
With `auth.enabled = true` at least one provider must be enabled, otherwise startup fails.

### Permissions

```hocon
access {
  default_roles = []                      # every authenticated user
  roles {
    admin = ["*"]
    analyst = ["scripts.sales.*"]
  }
  group_roles {                           # LDAP CN, LDAP DN or OIDC group name (case-insensitive)
    analysts = [analyst]
    "cn=admins,ou=groups,dc=example,dc=org" = [admin]
  }
  user_roles {
    alice = [admin]
  }
}

scripts = [
  {
    name = sales_by_region
    permissions = [scripts.sales.read]    # caller needs ALL listed permissions; empty = any signed-in user
    ...
  }
]
```

Scripts a user may not run are hidden from `/` by default. "Show all scripts" (`/?all=1`) lists them as **locked**, with
their description and the permissions they require, and a locked script cannot be opened (it returns `403`). The API does
the same with `GET /api/scripts?all=1`, where each script carries `accessible` and `required_permissions`.

#### External grants service

Instead of the maps above, roles and permissions can come from a service you run. The portal checks it on each request and
keeps the answers in a local cache:

```hocon
access.external {
  url = "https://permissions.internal/v1/permissions"   # http:// or https://
  timeout_secs = 3
  cache_ttl_secs = 300        # fresh answers are reused for this long
  bearer_token = ${?PERMISSIONS_TOKEN}                  # optional
  tls { ca_file = /etc/portal-tls/ca.crt }              # optional; otherwise the shared tls block applies to https://
}
```

- Request: `POST {url}` with JSON `{"subject", "display_name", "email", "groups"}`.
- Response: `200` with `{"roles": [...], "permissions": [...]}`. `404` means the identity holds nothing.
- Anything else, or no answer, is an error. A user whose grants cannot be loaded is denied everything and the failure is
  logged. An answer from the cache can be used during an outage only while it is younger than twice `cache_ttl_secs`, so
  revoked access stops working within that window.
- The same TLS options as the database and LDAP connections apply: a CA, a client certificate for mutual TLS, or
  `insecure` for development.

### Scripts, forms and charts

```hocon
scripts = [
  {
    name = top_products              # URL: /scripts/top_products  ([a-z0-9_-])
    title = "Top products"
    description = "Best sellers, optionally per region."
    permissions = [scripts.sales.read]
    max_rows = 50                    # default 1000; extra rows are cut and flagged
    timeout_secs = 10                # default 30
    sql = """                        # or: sql_file = scripts/top_products.sql
      SELECT product, SUM(amount) AS revenue
      FROM sales
      WHERE (:region = '' OR region = :region) AND amount >= COALESCE(:min_amount, 0)
      GROUP BY product ORDER BY revenue DESC LIMIT :limit
    """
    params = [                       # each :name used in the SQL must be declared here
      { name = region, type = select, default = "", options = [{ value = "", label = All }, EU, US] }
      { name = min_amount, type = number, min = 0 }
      { name = limit, type = integer, min = 1, max = 20, default = 5, required = true }
    ]
    charts = [
      { title = Revenue, type = pie, x = product, y = [revenue] }   # x = labels, y = value columns
    ]
    table = true                     # set false to show charts only
  }
]
```

Parameter options: `label`, `required`, `default`, `help`, `min`/`max` (numbers), `max_length`/`pattern` (text),
`options` (select). Scripts whose parameters all have defaults run on page load; others run on form submit.
Parameters travel in the query string, so results are bookmarkable and shareable.

### SQL safety and database support

- `database.read_only_scripts = true` (default) accepts only a single `SELECT`/`WITH` statement without write
  keywords. It is a safety net — **also use a read-only database account**.
- Supported URLs: `sqlite:` (`sqlite::memory:`, `sqlite:file.db`, `sqlite:///abs/file.db?mode=ro`) and
  `postgres://user:pass@host:5432/db` over JDBC with a HikariCP pool, and `clickhouse://` / `clickhouses://`
  (HTTP interface, port 8123 / 8443 by default), e.g. `clickhouse://reader:secret@ch.internal:8123/analytics`.
- **Migrations are off by default.** Set `database.migrations.enabled = true` to apply the `*.sql` files of
  `database.migrations.dir` (default `migrations/sqlite`) in file-name order at startup, before `init_sql_files`.
  Nothing is applied implicitly, and a missing directory is a startup error. The demo configuration enables them
  explicitly to load its sample data.
- Each database is one package under `adapters/.../sql/` (`postgres`, `sqlite`, `clickhouse`): its dialect (SQL rules,
  built on the `sql-executor` contract) and its `QueryExecutor`, both internal. `connectDatabase` is the only way in.
  Adding a database means a new package there and a branch in `connectDatabase`.
- ClickHouse: `:name` placeholders become typed server parameters (`{name:Int64}`, `{name:String}`, …), so
  values are never spliced into the SQL. Scripts run with `readonly=2` on the server, and each query gets a
  `max_execution_time` equal to the script timeout. Use a read-only ClickHouse user as well.
- PostgreSQL: parameters are cast to the type the server expects; results are converted so that every column type
  displays (numerics as numbers, dates, timestamps, uuids, json, arrays, intervals as text). Timestamps with time zone
  are rendered in the JVM time zone (`TZ`, UTC in the image). Write the jsonb operators `?`, `?|` and `?&` as they are;
  the portal escapes them for JDBC.
- Timeouts cancel the running statement at the database.
- **Packaged drivers.** `-Pdatabases=postgres` (or `sqlite`, or `sqlite,postgres`; default `all`) packages only those
  JDBC drivers, e.g. `docker build --build-arg DATABASES=postgres .`. ClickHouse needs no driver.
- **TLS for every outbound connection** (database, LDAP, OIDC, grants service). Set one shared `tls` block at the top level:
  ```hocon
  tls {
    ca_file = /etc/portal-tls/ca.crt     # CA, or the server's own self-signed certificate
    cert_file = /etc/portal-tls/tls.crt  # optional client certificate (mutual TLS)
    key_file = /etc/portal-tls/tls.key   # its private key (PEM: PKCS#8, PKCS#1 or SEC1)
    insecure = false                     # true skips verification; development only
  }
  ```
  A connection that uses TLS (`postgres://`, `clickhouses://`, `ldaps://` or `start_tls = true`, OIDC, an `https://`
  grants service) takes this block. Plain connections ignore it. To give one connection its own settings, add a `tls`
  block under it (`database.tls`, `auth.ldap.tls`, `auth.oidc.tls`, `access.external.tls`); that block replaces the
  shared one. The CA is trusted in addition to the JVM's default certificates.
- **Kubernetes:** mount a `kubernetes.io/tls` secret (`tls.crt`, `tls.key`) plus the CA (for example cert-manager's
  `ca.crt`) into the pod, and point the three paths at it. Relative paths resolve against the config file.
- `cert_file` needs `key_file`, and needs `ca_file` (or `insecure`) so the server is still verified.

## Try it locally: the showcase

The demo configuration enables the SQLite migrations explicitly (`database.migrations`), so the sample schema and data are
created at startup in memory. Nothing else is needed: `make run`, then open http://localhost:8080.

The `Showcase` scripts cover every way the page presents selected data:

| Script | Presentation |
|---|---|
| Showcase 1 · table only | the default: sortable, filterable table, no charts |
| Showcase 2 · bar chart, two series | bar chart with two series, table underneath |
| Showcase 3 · line chart with gaps | line chart; NULL values break the line |
| Showcase 4 · pie chart | pie chart; a zero value has no slice |
| Showcase 5 · doughnut chart, table hidden | `table = false` |
| Showcase 6 · scatter plot, table hidden | numeric points, no table |
| Showcase 7 · several charts and a table | three charts of different kinds over one result |
| Showcase 8 · truncated result | `max_rows`: the badge shows the cap |
| Showcase 9 · no rows | empty states for charts and table |
| Showcase 10 · chart error next to a valid chart | a broken chart is reported, the other still draws |
| Showcase 11 · every parameter kind | text, integer, number, boolean, date, select; defaults, limits, help, validation |
| Showcase 12 · column types in a table | NULLs, 0/1 booleans, decimals, dates, markup-like and non-ASCII text |
| Showcase 13 · wide table | twelve columns; the header stays in place while scrolling |
| Showcase 14 · filters with a bar and a pie | select, date range and number filters feed two charts |
| Showcase 15 · time series with filters | region and date filters; a line over the months that have sales |
| Showcase 16 · top N with a switch | integer limit and a boolean switch; bar and doughnut |
| Showcase 17 · scatter with filters | segment and minimum-spend filters; scatter with a paged table |
| Showcase 18 · search with paging | free-text search, ten rows per page, no charts |
| Showcase 19 · 20,000 rows, 100 per page | only the requested page is fetched; the pager covers 200 pages |

The `Gallery` scripts put several chart kinds over the same data, with filters that change the charts:

| Script | Charts | Filters |
|---|---|---|
| Gallery 1 · all five chart kinds | line, bar, pie, doughnut, scatter | year (select) |
| Gallery 2 · quarterly revenue | bar (four lines), line (two premium lines) | year (select) |
| Gallery 3 · brand share | pie, doughnut | brand text, minimum share (number) |
| Gallery 4 · sensor readings, paged | line (temperature and humidity), scatter | location, date range |
| Gallery 5 · survey by age band | bar, line, doughnut | segment (select), minimum age (integer) |
| Gallery 6 · daily traffic, paged | line, bar | date range, minimum visits, weekdays only (boolean) |

The data and the scripts are in `config/migrations/sqlite/` and `config/config.demo.conf`. The tests in
`sql-portal/integration-tests/src/test/kotlin/.../PresentationTest.kt` check each mode.

### Sharing a view

"Share link" above the results copies a link to the page with the parameters in the form, the page number and the rows per
page. Opening the link shows the same rows. Without JavaScript the button is a plain link to the same view.

### Grouping scripts

Set `group` on a script to list it under a heading on the start page. Groups are sorted by name, case-insensitively;
scripts without a group are listed last under "Other scripts". The search box matches the group name too, and hides
headings that have no matches. The JSON API returns the group of each script.

### Paging

A script with a table shows one page at a time. The page size is `page_size` (default 25, between 1 and 500). The count
and the page are two queries, so a large result is never loaded in full:

```hocon
{
  name = big_report
  page_size = 100
  max_rows = 200        # charts are drawn from the first 200 rows of the result
  sql = "SELECT ... ORDER BY ..."
}
```

Above the table, a "Rows per page" choice (5, 10, 25, 50, 100, and the script's own page size) starts at page 1 when
changed. It is kept in the URL as `_size`, so `?_size=10&_page=3` shows rows 21–30. Scripts whose table is hidden have no
paging and no choice.

The pager below the table shows `Page N of M`, arrows on both sides of the page numbers, and a window of page numbers
around the current page with gaps for distant ones. It keeps the form values in each link. It is not shown when the
result fits on one page. `?_page=N` selects a page; out-of-range numbers are clamped. Put an `ORDER BY` in the script so
that the pages are stable. "Export page as CSV" exports the rows on the current page. The JSON API is not paged and still
returns up to `max_rows` rows.

## HTTP endpoints

| Path | Purpose |
|---|---|
| `GET /` | List of scripts available to the signed-in user |
| `GET /scripts/{name}` | Parameter form and results (`?_run=1&param=value`) |
| `GET /login`, `POST /login` | LDAP sign-in form |
| `GET /login/oidc`, `GET /auth/callback` | OIDC sign-in |
| `POST /logout` | Sign out |
| `GET /api/scripts`, `GET /api/scripts/{name}?param=value` | JSON API (401 JSON when not signed in) |
| `GET /healthz` | Liveness: the process is up |
| `GET /readyz` | Readiness: `200`/`503` with per-check JSON (database is critical; OIDC discovery is reported but non-critical) |
| `GET /metrics` | Prometheus exposition (path configurable, can be disabled) |

## Metrics

| Metric | Type | Labels |
|---|---|---|
| `http_requests_total` | counter | `method`, `path` (route template), `status` |
| `http_request_duration_seconds` | histogram | `method`, `path`, `status` |
| `http_requests_in_flight` | gauge | |
| `auth_attempts_total` | counter | `provider`, `outcome` |
| `script_runs_total` | counter | `script`, `outcome` |
| `script_run_duration_seconds` | histogram | `script` |
| `script_rows_returned_total` | counter | `script` |
| `sql_execution_duration_seconds` | histogram | `script`, `purpose` (`table`, `page`, `count`, `chart`), `outcome` |
| `build_info`, `auth_enabled` | gauge | `version` |

`sql_execution_duration_seconds` times each statement in the database only (one script run can send several: count,
page, chart). Plot a latency percentile with
`histogram_quantile(0.95, sum by (le, script) (rate(sql_execution_duration_seconds_bucket[5m])))`.

Logs go to stderr through Logback, as text or one JSON object per line (`observability.log_format = json`). Every request
gets an `X-Request-Id` (taken from the request or generated) that appears in its log lines as `request_id`.
`observability.log_level` sets the root level and optional per-logger levels (`info,io.merkio.sqlportal=debug`);
the `SCRIPT_PORTAL_LOG` environment variable overrides it.

## Security notes

- Passwords are only ever sent to your directory (LDAP bind as the user); empty passwords are rejected up front
  (many servers treat them as anonymous binds). LDAP filter input is escaped. Use `ldaps://` or `start_tls`.
- Sessions use an HttpOnly, `SameSite=Lax` cookie (set `server.session.secure = true` behind HTTPS; a TLS-terminating
  proxy must then send `X-Forwarded-Proto: https`), a new session id on login, and CSRF tokens on login/logout.
  OIDC uses PKCE, `state` and `nonce`; the pending state is single-use. ID tokens are verified against the provider's
  keys (signature, issuer, audience, expiry, nonce).
- Responses carry a strict CSP (scripts only from `self` and the configured chart library origin), `X-Frame-Options: DENY`, etc.
  Set `ui.chartjs_url` to a self-hosted file (e.g. `/static/chart.js` via your proxy) to avoid the CDN.
- **Not included** (put these in front, or extend): login rate limiting/lockout, TLS termination, a persistent/shared session store.
  Sessions are held **in memory** and expire after `server.session.ttl_minutes` of inactivity: they are lost on restart and
  not shared between replicas — run one replica or use sticky sessions, or replace `ExpiringMemoryStorage` in
  `sql-portal/api/.../Portal.kt` with a persistent Ktor `SessionStorage`.
- Metrics and health endpoints are unauthenticated; restrict them at the network level if needed.

## Development

```bash
make fmt          # ktlint format
make lint         # ktlint + detekt + architecture rules
make test         # unit + in-process integration tests; no Docker needed
make test-docker  # everything above + PostgreSQL, ClickHouse and the portal image (builds it first)
make test-e2e     # only the end-to-end checks of the portal image
make coverage     # JaCoCo report over all modules; fails below COVERAGE_MIN (70) percent of lines
make audit        # OWASP dependency check (set NVD_API_KEY)
```

Versions of every library and plugin are in `gradle/libs.versions.toml`; the build conventions (JVM 21 toolchain, Kotest
on JUnit 5, ktlint, detekt, JaCoCo) are plugins in the included build `build-logic/`. `./gradlew build` runs compilation,
lint, detekt, every test without containers, and `checkArchitecture`.

**Tests.** Unit tests sit next to the code in each module (Kotest). Integration tests live in
`sql-portal/integration-tests/src/test/kotlin/`:

| spec | runs against | needs |
|---|---|---|
| `HttpApiTest`, `PresentationTest`, `BootstrapTest`, `MetricsTest` | the real Ktor module, in process, fake identity providers, SQLite in memory | nothing |
| `PostgresTest`, `ClickHouseTest` | PostgreSQL 16 / ClickHouse 24.8 containers | `-Pdocker` |
| `PortalImageTest` | the portal Docker image (`SCRIPT_PORTAL_IMAGE`, default `script-portal:test`), alone and with PostgreSQL on a shared network | `-Pdocker` + the image |

The LDAP and OIDC adapters are tested against an in-memory LDAP directory (UnboundID) and a local OpenID provider with
real signed ID tokens; the grants client against a local HTTP service. The TLS options (private CA with host name
verification, `insecure`, mutual TLS) are tested on a real HTTPS connection (`TlsConnectionTest`); PostgreSQL over TLS
uses the same `SSLContext` through `PortalSslFactory`. The layer rules are checked by the
`checkArchitecture` Gradle task on the declared dependencies of each module.

Container tests use [Testcontainers](https://java.testcontainers.org) and work with Docker or Podman
(`DOCKER_HOST` is honoured, e.g. `unix:///run/user/1000/podman/podman.sock`). Every container carries the label
`org.sql-portal.integration-test` and is stopped by its spec, also on failure; Ryuk is off by default
(`TESTCONTAINERS_RYUK_DISABLED`). The portal image is never pulled, so build it first (`make image-test`). If a run is
killed before cleanup, `make test-docker-clean` removes leftovers.

### CI and releases

- `.github/workflows/ci.yml` runs on every pull request: ktlint, detekt and the architecture rules, the tests and the
  shipped configuration files, coverage (JaCoCo, fails below `COVERAGE_MIN`, default 70 %), the container tests against a
  freshly built image (plus a check of the image's `HEALTHCHECK` under Docker), and the OWASP dependency check
  (secret `NVD_API_KEY` recommended).
- `.github/workflows/release.yml` runs on tags like `v1.2.3`: it re-runs the build (the tag must match `version` in
  `gradle.properties`), attaches the JVM distribution (`.tar.gz` and `.zip`, with checksums; runs anywhere with Java 21)
  to the GitHub release, and pushes a multi-arch image to Docker Hub. Configure the secrets `DOCKERHUB_USERNAME` and
  `DOCKERHUB_TOKEN` (and optionally the variable `DOCKERHUB_REPOSITORY`).

```bash
# cut a release
# 1. bump version in gradle.properties, commit
git tag v0.2.0 && git push origin v0.2.0
```

## License

MIT
