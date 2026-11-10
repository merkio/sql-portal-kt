# Architecture

Script Portal follows **clean architecture** with **DDD** building blocks, module for module like the Rust
version it was ported from. Layers are separate Gradle modules, so a dependency that breaks a layer rule must
first be declared in a `build.gradle.kts`, and the `checkArchitecture` task (part of `./gradlew check`)
rejects it.

## Build

- `settings.gradle.kts` includes the build `build-plugins/` (convention plugins `sqlportal.kotlin-library` and
  `sqlportal.kotlin-serialization`: Java toolchain from `java` in the version catalog (25), warnings as errors, Kotest on JUnit 5, ktlint, detekt, JaCoCo).
- `gradle/libs.versions.toml` holds every library and plugin version, for the modules and for `build-plugins`.
- The root `build.gradle.kts` adds the aggregated coverage report and gate and `checkArchitecture`. The
  dependency audit is `make audit` (Trivy over the shipped jars).

## Modules

```
common/                datasources/                                  sql-portal/
  tls-config             sql-executor  ◄─────────────────┐             domain ──────────► sql-executor
  template-render        jdbc-executor ─► sql-executor    │             application ─────► domain
  prometheus-metrics     sqlite-executor ─► jdbc-executor │             adapters ────────► domain, *-executor, tls-config
                         postgres-executor ─► jdbc-executor, tls-config api ─────────────► application (+ domain), template-render
                         clickhouse-executor ─► sql-executor, tls-config deployment ─────► all of the above, prometheus-metrics
                                                                        testing (tests) ─► domain
                                                                        integration-tests ► everything (tests only)
```

| module | role | may depend on (project modules) |
|---|---|---|
| `sql-portal:domain` | model and ports; no web, database or identity-provider libraries | `sql-executor` (placeholder scanning) |
| `sql-portal:application` | use cases | `domain` |
| `sql-portal:adapters` | port implementations; `QueryExecutor` over the datasource executors | `domain`, the `*-executor` modules, `tls-config` |
| `sql-portal:api` | the only module that knows the Ktor server: routes, sessions, middleware, views; the `Readiness` port | `domain`, `application`, `template-render` |
| `sql-portal:deployment` | configuration loading, composition root (Koin) + `script-portal` CLI; health checks and observability; the bundled configuration and logback.xml; owns the runtime classpath (JDBC drivers) | all layers, `prometheus-metrics` |
| `sql-portal:testing` | in-memory fakes of the ports | `domain`; only as a test dependency |
| `sql-portal:integration-tests` | integration and end-to-end tests | everything |
| `datasources:sql-executor` | the contract: `SqlExecutor`, `SqlQuery`, `SqlResult`, `SqlException`, `Dialect`, `Cell`, `Statement`, placeholder scanning; no I/O | — |
| `datasources:jdbc-executor` | `JdbcConnections` (plain driver connections, opened once and reused, validated after idling, replaced when broken) and `JdbcExecutor` (statement timeouts, error mapping) | `sql-executor` |
| `datasources:sqlite-executor`, `postgres-executor` | dialect + JDBC executor of one database; the driver is a runtime choice of the application | `sql-executor`, `jdbc-executor`, `tls-config` |
| `datasources:clickhouse-executor` | dialect + executor over the HTTP interface (JDK HTTP client) | `sql-executor`, `tls-config` |
| `common:prometheus-metrics` | Prometheus registry with caller-supplied histogram buckets | — |
| `common:tls-config` | `TlsConfig` (the `tls` block) and `TlsOptions` as JSSE objects (trust, client identity, `SSLContext`) | — |
| `common:template-render` | Freemarker template sets (HTML autoescaping for `.ftlh`), `contentVersion` for cache busting | — |

Only `deployment` and the integration tests depend on `api`; nothing but tests depends on `deployment` or `testing`.
`common/*` modules depend on nothing in `sql-portal/` or `datasources/`; `datasources/*` depend on nothing in
`sql-portal/`, and use no application framework or pool library. The domain uses no infrastructure library
(Ktor, JDBC, LDAP, JOSE, Micrometer, Freemarker, Koin, logging, coroutines); the application adds only coroutines
and kotlin-logging (`KotlinLogging`). Every module logs through kotlin-logging; Logback is the backend, configured
only in `deployment`.

## Domain (`sql-portal/domain`)

Bounded contexts, one package each. Ports are interfaces in the context they belong to; they are `suspend` where the
implementation does I/O.

| context | concepts | ports |
|---|---|---|
| `common` | `Value`, `DomainException`, number parsing and formatting | — |
| `access` | `Permission`, `PermissionSet`, `User`, `Principal`, `Grants`, `AccessPolicy`, `LocalGrants`, `AuthException` | `CredentialAuthenticator`, `OidcProvider`, `GrantSource`, `ScriptAccessRule` |
| `query` | `QueryRequest`, `QueryResult`, `QueryException`, `PageRequest`, `PageInfo` | `QueryExecutor` |
| `scripts` | `ScriptDefinition` (aggregate), `ScriptName`, `ParamDefinition`, `ChartDefinition`, `isReadOnlySelect`, `ConfigScriptCatalog` | `ScriptCatalog` |

## Application (`sql-portal/application`)

| class | use case |
|---|---|
| `AuthService` | the master `enabled` switch, LDAP login, the OIDC flow (state check), principal resolution (fail closed when grants are unavailable) |
| `ScriptService` | list, and `execute`: find, authorize, bind, page, run and chart in one call that returns a `ScriptOutcome` (`Ran`, `NotFound`, `Forbidden`, `NeedsInput`, `Invalid`, `Failed`); the HTML page and the JSON API only render it. Every SQL statement is timed (`sql_execution_duration_seconds`). |
| `Metrics` | the port the use cases report to (auth attempts, script runs, SQL execution time), and `NoopMetrics` |

## Adapters (`sql-portal/adapters`)

| package | implements |
|---|---|
| `sql` | `connectDatabase` picks the datasource executor by URL scheme and adapts it to `QueryExecutor` (values ↔ cells, `SqlException` → `QueryException`); migrations and init files; `DatabaseConfig` |
| `auth` | `LdapAuthenticator` (UnboundID), `OidcAdapter` (discovery, PKCE, ID-token verification with Nimbus JOSE); `LdapConfig`, `OidcConfig` |
| `grants` | `RemoteGrants` (HTTP grants service) and `CachedGrants` (TTL cache that bridges short outages); `ExternalGrantsConfig` |
| `http` | the outbound HTTP client: no redirects, TLS options applied |

## API (`sql-portal/api`)

Everything HTTP, on Ktor: `Application.portal(AppState, RouterSettings)` installs sessions (in-memory, expiring on
inactivity), security headers, request ids, call logging, HTTP metrics labelled by route template, and the routes.
Views build plain view models and render Freemarker templates from `resources/sqlportal/templates`; the stylesheet and
script are served from `resources/sqlportal/static`. The module declares the `Readiness` port behind `/readyz`, which
deployment implements.

## Deployment (`sql-portal/deployment`)

The only module that knows concrete adapters. `Main.kt` (the entry point: no arguments; it loads the configuration the
environment variables select and serves HTTP with the CIO engine and graceful shutdown) is the only file in the root
package; every other part has its package, which holds
its configuration data class (where it has one) and builds its part from the `RuntimeConfig`, as a Koin module where it
is part of the object graph:

| package | builds |
|---|---|
| `config` | `ConfigLoader`: bundled `config/default.conf` ← `config/env/<APP_ENV>.conf` ← optional `SCRIPT_PORTAL_CONFIG` file; environment variables in HOCON and inside strings; a strict shape check against the data classes (unknown keys, missing keys, types, with paths); decoding with kotlinx-serialization-hocon into `AppConfig`; validation into `RuntimeConfig`. `ConfigFiles` finds referenced files next to the `SCRIPT_PORTAL_CONFIG` file or in the bundled resources (also inside the jar). |
| `database` | the `QueryExecutor`, with migrations and init files applied |
| `access` | `AccessConfig`; the `GrantSource` (local policy or external service behind the cache) |
| `auth` | `AuthConfig`; the `AuthMode` and the `AuthService` with its providers |
| `scripts` | `ScriptConfig`, `ParamConfig`, `ChartConfig` and their mapping to the domain; the `ScriptService` with catalog and access rule |
| `health` | `HealthProbe`, `DatabaseProbe`/`OidcProbe`, `HealthService` (implements `Readiness`) |
| `observability` | `ObservabilityConfig`; log levels; the logback definers that choose text (local) or JSON (cluster) output; the Prometheus registry, process gauges, `PrometheusMetrics` (implements `Metrics`) |
| `wiring` | `wire()` assembles the modules; `Overrides` lets tests (or another module) replace single adapters; `portalModule`/`buildApp` give the Ktor module |

Resources: `config/` (`default.conf`, `scripts.conf`, `env/*.conf`, `migrations/`, `scripts/`) and `logback.xml`.

## Request flow (script page)

```
GET /scripts/x?_run=1&p=v
  → session cookie → User → AuthService.principal (GrantSource) | redirect /login
  → ScriptService.execute: catalog lookup, access rule (404 / 403)
     bindParams (validation → 422) → count + page queries → QueryExecutor (timeout, max_rows) → charts
  → views (escaped HTML; chart specs as JSON safe for <script>) → security headers → HTTP metrics
```

## Tests

Tests are written at the lowest layer that can express the behaviour:

1. **Domain unit tests**: pure, per context.
2. **Application unit tests**: use cases over the fakes in `sql-portal:testing`.
3. **Datasource tests**: dialects, URL mapping, the SQLite executor (connection reuse, concurrency, timeouts).
4. **Adapter tests**: the domain executor over in-memory SQLite, migrations, LDAP against an in-memory directory,
   OIDC against a local provider with signed tokens, the grants client against a local service.
5. **Deployment tests**: configuration loading, layering, substitution and rules; every bundled environment; the CLI;
   log format selection.
6. **In-process integration tests** (`integration-tests`): the real Ktor module from the local environment through the
   production wiring, with fake identity providers. No Docker.
7. **Architecture rules**: `checkArchitecture` on the declared module dependencies.
8. **Container tests** (Kotest tag `docker`, `-Pdocker`), with Testcontainers: the PostgreSQL and ClickHouse adapters
   against real servers, and the shipped image (HTTP endpoints, metrics, security headers, startup failures on an invalid
   configuration and on missing environment variables, and the image talking to PostgreSQL over a
   container network).

## Extension points

- **Another database**: a `datasources/<db>-executor` module with its `Dialect` and `SqlExecutor` (on
  `jdbc-executor` for a JDBC database), selected in `connectDatabase`. Add a container test.
- **Another identity provider**: a port next to `CredentialAuthenticator`/`OidcProvider` in `domain/access`, an adapter
  in `adapters/.../auth/`, wiring in `deployment/auth/Auth.kt`.
- **Persistent sessions**: replace `ExpiringMemoryStorage` in `api/.../Portal.kt` with a Ktor `SessionStorage` backed by
  a shared store.
- **Another access rule**: pass a `ScriptAccessRule` in `Overrides.scriptAccess`.
- **More chart kinds / form widgets**: extend `ChartKind`/`ParamKind` in the domain, then the configuration mapping
  (`deployment/scripts`) and the templates.
- **Another environment**: add `config/env/<name>.conf` to the deployment resources and set `APP_ENV=<name>`.
