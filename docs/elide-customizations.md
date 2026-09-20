# Customizations on top of basic Elide

This project is not a stock `elide-spring-boot-starter` app: entity models +
autoconfiguration + a datasource. It layers a full schema-per-tenant
multi-tenancy system, tenant-aware caching, per-request observability, and a
handful of Elide lifecycle hooks on top. This doc inventories every
deviation from default Elide/Hibernate behavior, why it exists, and where to
find it.

Context: the "notes" domain is a stand-in — the real target is a financial
books/products platform, where tenant isolation across cache, search index,
and database is a hard requirement, not a nice-to-have. Most of the
complexity below exists to prove that isolation out ahead of the real model.

## 1. Multi-tenancy layer (`tenant/`)

Schema-per-tenant, resolved from an API key on each request:

1. `TenantHeaderFilter` (`OncePerRequestFilter`) reads `X-API-KEY` on
   requests under `elide.json-api.path`/`elide.graphql.path` only. No
   header → `TenantInfo.getDefaultTenant()` ("public"); unknown key → `401`
   (JSON:API or GraphQL error body depending on path). Known key → looked up
   via `TenantInfo.getTenantForKey` (a lazily-built reverse map of
   `svc.tenant.<name>=<key>`).
2. The resolved tenant is stashed in `TenantContext`, a plain
   (non-inheritable) `ThreadLocal<String>`.
3. Every Elide Spring controller method returns
   `Callable<ResponseEntity<String>>`, so Spring MVC dispatches actual
   handling to a separate `task-N` worker thread — after the filter's
   `finally` has already cleared the ThreadLocal on the servlet thread.
   `TenantAsyncConfiguration` (a `WebMvcConfigurer` registering a
   `CallableProcessingInterceptor`) bridges this: it captures the tenant as
   a request attribute in `beforeConcurrentHandling` (servlet thread) and
   restores it into `TenantContext` in `preProcess` (worker thread),
   clearing it again in `postProcess`/`handleTimeout`/`handleError`/
   `afterCompletion`.
4. `RequestTenantResolver` implements Hibernate's
   `CurrentTenantIdentifierResolver<String>`, reading `TenantContext.get()`
   (falling back to the default tenant for startup/background work).
   `validateExistingCurrentSessions()` returns `true` so Hibernate rejects
   reusing a session across tenants.
5. `SchemaMultiTenantConnectionProvider` (in `config/`) implements
   `MultiTenantConnectionProvider<String>`: on `getConnection(tenantId)` it
   validates the tenant against `TenantInfo`, then calls
   `Connection.setSchema(tenantId)`; on `releaseConnection` it resets the
   schema back to default before returning the connection to the Hikari
   pool — pooled connections must not leak schema state to the next
   borrower/tenant.
6. `TenantSchemaInitializer` (`ApplicationRunner`, `@Order(1)`) provisions
   each key-protected tenant's schema and hand-written `note` table DDL at
   boot (the default/public schema is provisioned by Hibernate's own
   `ddl-auto`, which runs earlier than any `ApplicationRunner`). The DDL is
   manually kept in sync with `Note`'s JPA + bean-validation annotations —
   a documented maintenance trap.
7. `MockNoteSeeder` (`@Profile("dev")`, `@Order(2)`) seeds fake notes per
   tenant using `sessionFactory.withOptions().tenantIdentifier(tenantId)`,
   since there's no HTTP request to resolve a tenant from at startup.
8. `TenantInfo` (`@ConfigurationProperties(prefix="svc")`) is the single
   source of truth binding `svc.tenant.*` to tenant IDs/keys, with
   `"public"` hardcoded as the keyless default tenant.

Stock Elide has no multi-tenancy concept at all — one datasource, one
schema. See also project memory: [[tenant-isolation-is-load-bearing]].

## 2. Elide/Hibernate default overrides (`config/`)

- **`ElideStoreConfiguration`** — registers a `DataStoreBuilderCustomizer`
  (`@Order(2)`) that replaces every `JpaDataStore` with a
  `QuerySourceAwareSearchDataStore`-wrapped `SearchDataStore`, because
  `elide-spring-boot-autoconfigure` 7.2.0 auto-wires the *aggregation*
  store but ships no search-store support at all. Also defines the
  `defaultDataSource` bean (`autowireCandidate=false`, resolved lazily by
  name) that feeds `TenantAwareDataSource` into Elide's aggregation-store
  `queryEngine(...)` factory — a second unqualified `DataSource` bean would
  otherwise break every other autoconfigured `DataSource` injection point.
- **`SchemaMultiTenantConnectionProvider`** — see section 1; not present in
  stock Elide/Hibernate.
- **`FilterDialectConfiguration`** — adds `DefaultFilterDialect`
  (bracket-operator `filter[type.field][op]=value` syntax) alongside the
  RSQL dialect Elide registers by default. Without this, bracket-syntax
  filters (required for `infix`/`prefix` search operators) fail with
  `"Invalid query parameter: filter[...]"`, because Elide's
  fallback-to-default-dialect logic only triggers when the dialect list is
  *empty* — RSQL being present short-circuits it.
- **`CacheProperties`** (`@ConfigurationProperties(prefix="svc.cache")`)
  — selects `EMBEDDED` vs `REMOTE` Infinispan mode, each with its own JCache
  `CachingProvider` and config resource (`infinispan.xml` vs
  `infinispan/hotrod-client.properties`). Not an Elide concept — plain infra
  bolt-on. See [[local-infinispan-server]].
- **`TenantAwareJCacheRegionFactory`** (extends `JCacheRegionFactory`) —
  gives every **(entity, tenant)** pair its own Infinispan cache region
  (beyond the cache-key tenant scoping Hibernate already does natively via
  `DefaultCacheKeysFactory`), so one tenant's cache traffic can't evict
  another's hot entries. Overrides `getFromCache`/`putIntoCache`/
  `removeFromCache`/`clearCache` to route by
  `SharedSessionContractImplementor.getTenantIdentifier()`; session-less
  `evictData()`/`evictData(Object)` intentionally fans out to *all*
  tenants (global eviction must not leave other tenants stale). Also feeds
  `QuerySourceRecorder.recordCacheLookup` on every read.
- **`HibernateTenancyConfiguration`** — wires `RequestTenantResolver`,
  `SchemaMultiTenantConnectionProvider`, `TenantAwareJCacheRegionFactory`,
  and `QuerySourceStatementInspector` into Hibernate via
  `HibernatePropertiesCustomizer` beans (rather than class-name properties)
  because these classes need Spring-managed dependencies injected, whereas
  Hibernate's `StrategySelector` would otherwise instantiate a bare no-arg
  class outside the container.
- **`AnalyticsQueryEngineConfiguration`** — manually rebuilds Elide's
  aggregation-store `QueryEngine` bean (mirroring
  `ElideAutoConfiguration.AggregationStoreConfiguration.queryEngine(...)`)
  purely so its `ConnectionDetails` can wrap a `TenantAwareDataSource`
  instead of the raw pooled datasource. Necessary because the aggregation
  store talks raw JDBC, bypassing Hibernate's tenant resolver entirely —
  without this, analytics queries (`noteStats`) would always read one
  fixed schema regardless of the caller's tenant.

## 3. Observability (`observability/`)

Elide provides no per-request attribution across cache/index/database
tiers out of the box. This package bolts it on:

- **`QuerySourceRecorder`** — per-request `ThreadLocal` tally (`cacheHits`,
  `cacheMisses`, `indexQueries`, `databaseLoads`, `lastSql`, `tenantId`),
  propagated across the async servlet→worker-thread boundary by the same
  `TenantAsyncConfiguration` interceptor used for tenancy.
- **`QuerySourceStatementInspector`** — implements Hibernate's
  `StatementInspector` SPI to count SQL `SELECT`s (writes excluded) as
  `DATABASE`-sourced and capture the last SQL text, independent of and
  complementary to `show-sql`.
- **`QuerySourceAwareSearchDataStore`** — wraps `SearchDataStore`'s read
  transactions and infers index-vs-database attribution without
  duplicating Elide's private `canSearch()` logic, by checking whether the
  filtered field's column name appears in the emitted SQL's `WHERE` clause
  (index-served reads still issue a `SELECT ... WHERE id IN (?)`
  fetch-by-primary-key, so absence of the filtered field is the signal).
- **`QuerySourceLoggingFilter`** (`@Order(HIGHEST_PRECEDENCE)`) — begins
  the recorder, and, because Elide's async dispatch means work isn't done
  by the time `doFilter` returns, registers a `QuerySourceSummaryListener`
  (`AsyncListener`) to emit the final one-line summary (`GET
  /api/v1/notes/<id> tenant=... status=... servedBy=... cacheHits=...`)
  from the async completion/timeout/error callback rather than the
  filter's own `finally`.

## 4. `TenantAwareDataSource` (`datastore/`)

A `DataSource` decorator that wraps the raw pooled datasource and calls
`Connection.setSchema(TenantContext.get())` on every `getConnection()`/
`getConnection(user,pass)` call. It exists specifically because Elide's
aggregation (analytics) store talks plain JDBC rather than going through
Hibernate, so it cannot see `RequestTenantResolver` at all. Two beans in
`config/` (`ElideStoreConfiguration.tenantAwareDataSource` and
`AnalyticsQueryEngineConfiguration.queryEngine`) construct instances of it
and feed them into the search-store JPA path and the analytics
`QueryEngine` respectively. It resolves the raw `"dataSource"` bean lazily
by name via `BeanFactory` (not constructor injection) to dodge
circular/ambiguous bean resolution.

## 5. Lifecycle hooks (`hook/`)

- **`NoteNormalizePreSecurityHook`** (PRESECURITY, CREATE+UPDATE) — trims/
  collapses whitespace in `Note.body` before Elide's security checks and
  Bean Validation run; deliberately in this phase because it's the only
  one where mutating the entity still affects what gets validated/
  persisted.
- **`NoteAuditPreCommitHook`** (PRECOMMIT, CREATE+UPDATE+DELETE,
  `oncePerRequest=false`) — logs an audit line per changed field (using the
  `ChangeSpec` old/new values) inside the still-rollback-able transaction,
  so a thrown exception here aborts the write.
- **`NotePostCommitHook`** (POSTCOMMIT, CREATE, default
  `oncePerRequest=true`) — logs after durable commit; the placeholder for
  side effects that must not fire on a rolled-back write (event publish,
  notification).
- **`HookInvocationRecorder`** — a bounded (`MAX_INVOCATIONS=100`)
  in-memory `CopyOnWriteArrayList` used purely to make hook firings
  observable/testable.

Stock Elide entities need zero lifecycle hooks; these exist to exercise the
PRESECURITY/PRECOMMIT/POSTCOMMIT extension points for the kind of audit
trail and derived-field logic a real financial-entity model would need.

## 6. `Note` entity (`model/Note.java`)

Beyond a plain JPA entity:

- `@Include(name="notes")` — Elide JSON:API exposure.
- `@Indexed` + `@FullTextField(searchable=Searchable.YES)` on `body` —
  Hibernate Search/Lucene; explicit `searchable=YES` is required since
  `@FullTextField`'s default `Searchable.DEFAULT` silently fails the
  search-store's eligibility check.
- `@Cacheable` / `@Cache(usage=READ_WRITE)` — Hibernate L2 cache strategy,
  documented with a decision table for future entities (READ_WRITE vs
  NONSTRICT_READ_WRITE vs uncached).
- Five stacked `@LifeCycleHookBinding` annotations wiring the three hooks
  above at specific operations/phases.
- `@PrePersist onCreate()` stamps `createdDate`.

Notably absent: no `@ReadPermission`/`@UpdatePermission`/`@CreatePermission`/
`@DeletePermission` checks anywhere. Access control is done entirely by the
tenant/schema-routing layer (API key → schema), not Elide's own
permission-expression model.

## 7. Custom non-Elide endpoints (`controllers/`)

Plain Spring MVC, entirely outside Elide's auto-generated JSON:API/GraphQL
surface, coexisting under the same `/api/v1` base path:

- **`HelloController`** — trivial `GET /api/v1/hello` sanity endpoint.
- **`UploadController`** — `POST /api/v1/uploads` (multipart), delegates to
  a pluggable `List<UploadHandler>` strategy chain (first handler whose
  `supports(contentType, fileName)` returns true wins); ships with no
  handler implementations — every upload is accepted/measured/reported as
  `"none"` by default via `UploadResult.notHandled`. Bounded by
  `spring.servlet.multipart.max-file-size`.
- **`UploadHandler`** / **`UploadResult`** — the strategy interface and the
  JSON response record (`fileName`, `contentType`, `size`, `handler`,
  `details`, `errors`).

Demonstrates that hand-written REST resources can coexist with
Elide-managed ones and still be picked up by springdoc for OpenAPI (see
section 9) — an intentional template pattern, not something Elide
provides.

## 8. Build/config infra

Beyond a stock `elide-spring-boot-starter` + datasource
(`build.gradle.kts`, `application.properties`, `infinispan/hotrod-client.properties`):

- **Caching** — `hibernate-jcache` + `infinispan-jcache` +
  `infinispan-core` + `infinispan-jcache-remote` +
  `infinispan-client-hotrod` (both embedded and remote JCache providers
  shipped; mode picked at runtime via `svc.cache.mode`). Not using
  Infinispan's native Hibernate provider because it only supports
  Hibernate ORM 6.6, while this project runs 7.4.
- **Search** — `elide-datastore-search` (Hibernate Search), index
  root pinned to `build/lucene-indexes` to avoid cross-test Lucene lock
  contention; `hibernate.search.multi_tenancy.tenant_ids=public,tenant_a,
  tenant_b` partitions the index per tenant. `svc.search.mode`
  (`SearchProperties`) selects the backend at runtime — `lucene` (default,
  local filesystem, no infra needed), or `opensearch`/`elasticsearch`
  (`application-opensearch.properties` / `application-elasticsearch.properties`,
  activated via matching Spring profile), both served by the same
  `hibernate-search-backend-elasticsearch` artifact and distinguished only
  by `hibernate.search.backend.version`'s `opensearch:` prefix. Solr is not
  offered: Hibernate Search 8.x has no Solr backend (removed after its 5.x
  line). See [README's Search § Backend](../README.md#backend) for
  activation details. `svc.search.enabled=false` removes `SearchDataStore`
  from Elide's store list entirely (`ElideStoreConfiguration`), falling
  every read back to plain JPA — `infix`/`prefix` filters still work, just
  always database-served rather than index-served, since those operators
  are generic to Elide's predicate engine, not exclusive to the search
  store.
- **Analytics** — `elide.aggregation-store.*` properties:
  `default-dialect=h2` (overriding Elide's default `Hive` dialect, which
  produces SQL H2 can't run), `query-cache.enabled=false` (critical fix:
  the aggregation query cache keys by SQL text only, with no tenant
  dimension, so leaving it on would leak one tenant's `noteStats` rows to
  another).
- **`spring.jpa.properties.hibernate.cache.use_query_cache=false`** — L2
  query cache disabled for the same tenant-leak reason: `QueryKey` has no
  tenant identifier, unlike entity cache keys
  (`DefaultCacheKeysFactory#createEntityKey`, which does fold in the
  tenant).
- **`spring.jpa.properties.hibernate.multiTenancy=SCHEMA`** — activates
  Hibernate's schema-per-tenant strategy.
- **`spring.jpa.open-in-view=true`** — kept deliberately (documented as no
  longer load-bearing for tenancy now that `TenantAsyncConfiguration`
  exists, but pinned to match what the app was tested against).
- **Observability logging** — `logging.level.com.empyrean.elide.
  observability=INFO`, with a commented-out TRACE line for
  `TenantAwareJCacheRegionFactory` (a `$` in property keys breaks nested-
  class logger binding).
- **Micrometer/Prometheus** (`micrometer-registry-prometheus`,
  `management.endpoints.web.exposure.include=health,info,prometheus`) and
  **springdoc-openapi-starter-webmvc-ui** — neither ships with bare
  `elide-spring-boot-starter`.
- **`infinispan/hotrod-client.properties`** — only consulted in `remote`
  cache mode; lives in its own `infinispan/` subfolder alongside the
  `infinispan` Spring profile so everything remote-Infinispan-related is
  co-located, but is not itself a Spring file — it's Infinispan's own Hot
  Rod client config format, read directly by `RemoteCacheManager` via
  `ConfigSettings.CONFIG_URI`, with its own `${env.VAR}` placeholder syntax
  incompatible with Spring's. Configures per-cache topology (bounded/evicting
  entity regions named `com.empyrean.elide.model.Note.<tenant>`,
  non-evicting timestamps region) and a Java-serialization marshaller with
  an explicit deserialization allowlist (`org.hibernate.cache.*`,
  `java.sql.*`, etc.), since Hibernate's `CacheKeyImplementation`/
  disassembled field values cross the wire, not the `Note` entity itself.
  See [[local-infinispan-server]].

## 9. FilterDialect / permissions / OpenAPI

- **FilterDialect** — covered in section 2; `FilterDialectConfiguration`
  is the only custom `FilterDialect` in the project.
- **Permissions** — no custom Elide `Check`/`@ReadPermission` etc. classes
  exist anywhere in the codebase. Access control is entirely the
  tenant/API-key/schema-routing mechanism, not Elide's permission-
  expression system.
- **OpenAPI** — no custom OpenAPI Java config class exists; it's
  springdoc's default auto-scan (`springdoc-openapi-starter-webmvc-ui`)
  combined with Elide's own `/api-docs`. Two recent fix commits:
  - `764f7a1` *"fix: point Swagger UI at the document that includes custom
    endpoints"* — the vendored `static/swagger/swagger-initializer.js`
    pointed at `/api-docs`, which under Elide-on-Spring only contains
    Elide's generated entity paths. Fixed to point at `/v3/api-docs`
    (springdoc's merged document, which includes
    `HelloController`/`UploadController` too, since both springdoc and
    Elide build on the same `io.swagger.v3` object model). Added a
    regression test
    (`ApiDocsTest.swaggerUiLoadsTheDocumentThatContainsEverything`).
  - `a7716f8` *"chore: drop three dead OpenAPI properties"* — removed
    `notes.json-api.title` (a dead property with no effect) and
    `springdoc.api-docs.path`/`springdoc.swagger-ui.path` (both just
    restating springdoc's own defaults).
  - `application.properties` also silences one specific swagger-core
    logger (`io.swagger.v3.core.util.ReferenceTypeUtils=OFF`) to suppress
    a benign `ERROR` log caused by Elide's dynamic HJSON-defined analytics
    `TableType` (e.g. `noteStats`) having no backing Java class for
    swagger-core to reflect over.
