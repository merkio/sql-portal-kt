# Architecture

Script Portal follows **clean architecture** with **DDD** building blocks, module for module like the Rust
version it was ported from. Layers are separate Gradle modules, so a dependency that breaks a layer rule must
first be declared in a `build.gradle.kts`, and the `checkArchitecture` task (part of `./gradlew check`)
rejects it.

## Build

- `settings.gradle.kts` includes the build `build-logic/` (convention plugins `sqlportal.kotlin-library` and
  `sqlportal.kotlin-serialization`: JVM 21 toolchain, warnings as errors, Kotest on JUnit 5, ktlint, detekt, JaCoCo).
- `gradle/libs.versions.toml` holds every library and plugin version, for the modules and for `build-logic`.
- The root `build.gradle.kts` adds the aggregated coverage report and gate, the OWASP dependency check and
  `checkArchitecture`.

## Modules

```
common/                         datasources/                     sql-portal/
  tls-config                      sql-executor  ◄──┐               domain ──────────► sql-executor
  template-render                 sqlite-dialect ──┤               application ─────► domain
  prometheus-metrics              postgres-dialect ┤               adapters ────────► domain, sql-executor, *-dialect, tls-config
                                  clickhouse-dialect┘              api ─────────────► application (+ domain), template-render
                                                                   deployment ──────► all of the above, prometheus-metrics
                                                                   testing (tests) ─► domain
                                                                   integration-tests ► everything (tests only)
```

| module | role | may depend on (project modules) |
|---|---|---|
| `sql-portal:domain` | model and ports; no web, database or identity-provider libraries | `sql-executor` (placeholder scanning) |
| `sql-portal:application` | use cases | `domain` |
| `sql-portal:adapters` | port implementations | `domain`, the datasource modules, `tls-config` |
| `sql-portal:api` | the only module that knows the Ktor server: routes, sessions, middleware, views; the `Readiness` port | `domain`, `application`, `template-render` |
| `sql-portal:deployment` | composition root (Koin) + `script-portal` CLI; health checks and observability; owns the runtime classpath (JDBC drivers) | all layers, `prometheus-metrics` |
| `sql-portal:testing` | in-memory fakes of the ports | `domain`; only as a test dependency |
| `sql-portal:integration-tests` | integration and end-to-end tests | everything |
| `datasources:sql-executor` | dialect contract (`Dialect`, `Cell`, `Statement`, placeholder scanning), no I/O | — |
| `datasources:*-dialect` | one dialect each, no I/O | `sql-executor` |
| `common:prometheus-metrics` | Prometheus registry with caller-supplied histogram buckets | — |
| `common:tls-config` | `TlsOptions` as JSSE objects (trust, client identity, `SSLContext`) | — |
| `common:template-render` | Freemarker template sets (HTML autoescaping for `.ftlh`), `contentVersion` for cache busting | — |

Only `deployment` and the integration tests depend on `api`; nothing but tests depends on `deployment` or `testing`.
`common/*` modules depend on nothing in `sql-portal/` or `datasources/`. The domain uses no infrastructure library
(Ktor, JDBC, LDAP, JOSE, Micrometer, Freemarker, Koin, logging, coroutines); the application adds only coroutines
and SLF4J.

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
| `ScriptService` | list / authorize / run with permission checks, parameter binding, paging, charts, metrics; `AuthorizedScript` can only be created by `authorize` |
| `Metrics` | the port the use cases report to (auth attempts, script runs), and `NoopMetrics` |

## Adapters (`sql-portal/adapters`)

| package | implements |
|---|---|
| `sql` | `QueryExecutor` for SQLite and PostgreSQL (JDBC, HikariCP, timeouts cancel the statement) and ClickHouse (HTTP via the Ktor client); `connectDatabase` picks one by URL scheme; migrations and init files; `PortalSslFactory` gives the PostgreSQL driver the portal's TLS options |
| `auth` | `LdapAuthenticator` (UnboundID), `OidcAdapter` (discovery, PKCE, ID-token verification with Nimbus JOSE) |
| `grants` | `RemoteGrants` (HTTP grants service) and `CachedGrants` (TTL cache that bridges short outages) |
| `config` | HOCON loading with strict key checks (`Section`), the file model, validation and mapping to `RuntimeConfig` |
| `http` | the outbound HTTP client: no redirects, TLS options applied |

## API (`sql-portal/api`)

Everything HTTP, on Ktor: `Application.portal(AppState, RouterSettings)` installs sessions (in-memory, expiring on
inactivity), security headers, request ids, call logging, HTTP metrics labelled by route template, and the routes.
Views build plain view models and render Freemarker templates from `resources/sqlportal/templates`; the stylesheet and
script are served from `resources/sqlportal/static`. The module declares the `Readiness` port behind `/readyz`, which
deployment implements.

## Deployment (`sql-portal/deployment`)

The only module that knows concrete adapters. Each file configures one part of the application from the
`RuntimeConfig`, as a Koin module where it is part of the object graph:

| file | builds from config |
|---|---|
| `Database.kt` | the `QueryExecutor`, with migrations and init files applied |
| `Access.kt` | the `GrantSource` (local policy or external service behind the cache) |
| `Auth.kt` | the `AuthMode` and the `AuthService` with its providers |
| `Scripts.kt` | the `ScriptService` with catalog and access rule |
| `Health.kt` | `HealthProbe`, `DatabaseProbe`/`OidcProbe`, `HealthService` (implements `Readiness`) |
| `Observability.kt` | Logback setup, the Prometheus registry, process gauges, `PrometheusMetrics` (implements `Metrics`) |
| `Wiring.kt` | `wire()` assembles the modules; `Overrides` lets tests (or another module) replace single adapters; `portalModule`/`buildApp` give the Ktor module |
| `Main.kt` | the CLI: `serve` (CIO engine, graceful shutdown), `check-config`, `healthcheck` |

## Request flow (script page)

```
GET /scripts/x?_run=1&p=v
  → session cookie → User → AuthService.principal (GrantSource) | redirect /login
  → ScriptService.authorize: catalog lookup, access rule (404 / 403)
  → ScriptService.run: bindParams (validation → 422) → count + page queries → QueryExecutor (timeout, max_rows) → charts
  → views (escaped HTML; chart specs as JSON safe for <script>) → security headers → HTTP metrics
```

## Tests

Tests are written at the lowest layer that can express the behaviour:

1. **Domain unit tests**: pure, per context.
2. **Application unit tests**: use cases over the fakes in `sql-portal:testing`.
3. **Adapter tests**: SQL against in-memory SQLite, configuration parsing and rules, LDAP against an in-memory directory,
   OIDC against a local provider with signed tokens, the grants client against a local service.
4. **In-process integration tests** (`integration-tests`): the real Ktor module from the demo config through the
   production wiring, with fake identity providers. No Docker.
5. **Architecture rules**: `checkArchitecture` on the declared module dependencies.
6. **Container tests** (Kotest tag `docker`, `-Pdocker`), with Testcontainers: the PostgreSQL and ClickHouse adapters
   against real servers, and the shipped image (HTTP endpoints, metrics, security headers, the built-in `healthcheck`
   and `check-config` commands, a startup failure on invalid config, and the image talking to PostgreSQL over a
   container network).

## Extension points

- **Another database**: a dialect module in `datasources/` implementing `Dialect`, plus a `QueryExecutor` adapter in
  `adapters/.../sql/` selected in `connectDatabase`. Add a container test.
- **Another identity provider**: a port next to `CredentialAuthenticator`/`OidcProvider` in `domain/access`, an adapter
  in `adapters/.../auth/`, wiring in `deployment/Auth.kt`.
- **Persistent sessions**: replace `ExpiringMemoryStorage` in `api/.../Portal.kt` with a Ktor `SessionStorage` backed by
  a shared store.
- **Another access rule**: pass a `ScriptAccessRule` in `Overrides.scriptAccess`.
- **More chart kinds / form widgets**: extend `ChartKind`/`ParamKind` in the domain, then the configuration mapping and
  the templates.
