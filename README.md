# Vev

Typed PostgreSQL queries for Kotlin, validated during the build.

Write SQL. Vev applies your Flyway migrations to a disposable PostgreSQL database,
uses PostgreSQL Parse/Describe to validate queries, and generates Kotlin data classes
and JDBC implementations. The runtime has no external dependencies.

Requires JDK 27 or newer, Kotlin 2.5.0-Beta2 and PostgreSQL 19. The build machine needs the
PostgreSQL 19 server binaries (`postgres`, `initdb`, `pg_ctl`); the deployed application only
needs JDBC and its pool. The plugin uses `PATH`, then the standard Homebrew, Debian/Ubuntu,
PGDG RPM and Postgres.app locations; set `postgresBin` for any other directory.

## Setup

Vev artifacts and the Gradle plugin marker are published to Maven Central. Include
`mavenCentral()` in both `pluginManagement` and `dependencyResolutionManagement` in
`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
```

In the application module:

```kotlin
plugins {
    kotlin("jvm") version "2.5.0-Beta2"
    id("no.beint.vev") version "5.5.1"
}
kotlin {
    jvmToolchain(27)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_27) }
}
tasks.named<no.beint.vev.gradle.GenerateSql>("generateVev") {
    packageName.set("example.db")
    migrations.set(rootProject.layout.projectDirectory.dir("db/src/main/resources/db/migration"))
}
```

The plugin adds `no.beint.vev:runtime`. Supply pgjdbc and your connection pool.
The default migration directory is `src/main/resources/db/migration`.

Apply the plugin in each module that owns SQL. It reads that module’s `src/main/sql`
and generates Kotlin `internal` query objects, result rows and value classes. Use them
directly in the owning module and its tests. For operations shared with other Kotlin
modules, expose a function and return type owned by the module. Java callers can
access Kotlin `internal` declarations on the JVM.

## Queries

Put queries in `src/main/sql`. Use one named query per file, named after its generated method:

`customer.sql`:

```sql
-- vev.name: customer :optional
SELECT id, name FROM customer WHERE tenant_id = :tenantId AND id = :id;
```

`customers.sql`:

```sql
-- vev.name: customers :many
SELECT id, name FROM customer
WHERE tenant_id = :tenantId AND id > :after
ORDER BY id LIMIT :limit::int;
```

```kotlin
val db = no.beint.vev.Db(dataSource)
val customer = db.read(42) { Queries.customer(it, id = 123) }
val page = db.read(42) { Queries.customers(it, after = 0, limit = 100) }
```

`db.read(tenantId)` / `db.write(tenantId)` (or `db.tenantRead` / `db.tenantWrite` with a configured
`tenantSupplier`) provide re-entrant, capability-typed lexical transactions. Every generated query
takes a sealed capability scope as its first parameter:

| Scope | Allowed queries |
|---|---|
| `ReadScope` | Global read-only queries |
| `WriteScope : ReadScope` | Global read and write/locking queries |
| `TenantReadScope : ReadScope` | Tenant read-only and global read-only queries |
| `TenantWriteScope : TenantReadScope, WriteScope` | All tenant and global read/write/locking queries |

At build time Vev classifies each query as read or write/locking (DML, `FOR UPDATE`/`SHARE`,
sequence mutations, advisory locks, `:void`, or `-- vev.access: write`) and checks relation scans
and mutations in PostgreSQL's generic plan. Calling a write or locking query inside a read scope,
a tenant query inside a global scope, or any query outside a transaction fails Kotlin compilation.
Generated methods bind `:tenantId` from the scope; it is absent from their parameter lists.
Tenant IDs must be positive.

Each scan of a table with a `tenant_id` column must show a restriction to the bound tenant, directly or
through a tenant-key equality with another checked scan. For mutations on tables with `tenant_id`,
`INSERT` must assign `:tenantId` (or a proven scan's `tenant_id`), `ON CONFLICT DO UPDATE` must
arbitrate on a tenant-keyed index or filter `tenant_id = :tenantId`, and `UPDATE`/`MERGE` must
not reassign `tenant_id`. Compare with `:tenantId` directly where a materialized CTE or
correlated subquery hides the relationship from the planner.

Opaque functions, triggers and tables without a `tenant_id` column remain outside the plan check
and need application review. Composite foreign keys enforce tenant consistency between stored
references.

Global queries declare `-- vev.scope: global`. Run them in `db.read` / `db.write`,
or within a tenant transaction. Tenant queries require a tenant scope.

### Query directives

Directives are compiler inputs written as SQL comments. Keep them before the SQL,
starting with `vev.name`. PostgreSQL ignores them, so SQL editors and database tools
can still read the file. Unknown `vev.` directives and duplicate singleton directives
fail the build, and so does a directive without the `vev.` prefix.

| Directive | Meaning |
|---|---|
| `-- vev.name: find :many` | List of every result row; bound it with `LIMIT` in SQL |
| `-- vev.name: find :one` | Exactly one row |
| `-- vev.name: find :optional` | Zero or one row |
| `-- vev.name: export :stream` | Stream rows via server-side cursor (`Consumer<Row>`) |
| `-- vev.name: change :exec` | Mutation affected-row count as `Long` |
| `-- vev.name: lock :void` | Execute a void-returning function |
| `-- vev.scope: global` | No implicit tenant bind |
| `-- vev.access: write` | Require a write scope even for a `SELECT` calling a custom mutating function |
| `-- vev.nullable: name, note` | Nullable inputs |
| `-- vev.notnull: id, total` | Assert and check non-null output values (redundant assertions fail the build) |
| `-- vev.type: id CustomerId` | Generate a scalar Kotlin value class |
| `-- vev.enum: status example.Status` | Map a textual column/input to an existing Kotlin enum |
| `-- vev.plan: custom` | Plan every execution with its parameter values (see optional filters) |
| `-- vev.allow: blob, unordered-limit` | Opt in to unbounded `bytea` columns in `:many`/wide queries (`blob`) or top-level `LIMIT`/`FETCH` without `ORDER BY` (`unordered-limit`) |

Parameters are bound values. Cast ambiguous inputs, such as `:name::text IS NULL`; a parameter after
`->` or `->>` must be cast (`:index::int` or `:key::text`) because PostgreSQL otherwise picks the text-key operator,
and use PostgreSQL arrays for lists: `id = ANY(:ids::int[])`. List returned columns
explicitly; internal CTEs and subqueries may use wildcards. Result names in snake_case become Kotlin camelCase properties.
Nullability is conservative for expressions, joins, CTEs and aggregates; `notnull`
is a checked assertion rather than an inference. Unsupported types and result rows
exceeding Kotlin’s JVM parameter limit fail generation with a query-local diagnostic.
The same check covers captured query arguments; use PostgreSQL arrays instead of hundreds
of separate binds.

Optional filters stay one static query: `WHERE (:status::text IS NULL OR status = :status)`, with sorting by
`CASE` or separate queries. pgJDBC server-prepares a statement after a few executions, and PostgreSQL may then
reuse a generic plan that underestimates multi-tenant/range/array selectivity or keeps disabled optional filters
alive. Vev automatically enables per-execution planning (`setPrepareThreshold(0)`) for queries with
`-- vev.nullable` `IS [NOT] NULL` checks, parameterized `WITH` CTEs, `ANY(:array)`, parameterized `BETWEEN`
ranges, sentinel parameter-to-literal comparisons (`:x = ''`, `:x <> 'all'`), or boolean-flag branches
(`NOT :flag`, `ON :flag AND`, `WHEN :flag THEN`), while keeping simple single-row lookups and DML on fast
server-prepared generic plans. Declare `-- vev.plan: custom` explicitly for any other parameter-skew pattern.

Keep one query per file so agents can discover, read and change its complete contract
without loading neighboring queries. Multiple statements or named queries in a file
fail the build. Organize related files in directories. Reuse database views/functions
when shared semantics have an owner and execution tests; SQL text includes are absent.

## Transactions

`Db` manages re-entrant lexical transactions. Outermost `read` / `write` calls commit successful
callbacks and roll back failed callbacks. Nested calls join the enclosing transaction on the same
JDBC connection, reject read-to-write escalation, isolation elevation, and cross-tenant switching,
and never poison an enclosing transaction when a caller catches a non-SQL domain exception. SQL,
decoding, and cardinality failures raise typed `VevException` subclasses (`VevSqlException`,
`VevDataException`, `VevSessionException`) and poison the transaction even when caught. No retry
occurs automatically.

Use `readNew` / `writeNew` for an independent transaction on a separate connection, `writeIsolated`
to assert that a write is not nested inside an enclosing transaction, `readRepeatable` /
`writeSerializable` for elevated isolation levels, `withTimeout(Duration)` for per-transaction
statement timeouts (applied to both PostgreSQL `SET LOCAL statement_timeout` and JDBC statements),
`withLockTimeout(Duration)` for per-transaction PostgreSQL `SET LOCAL lock_timeout` overrides,
`requireWriteTransaction()` for write-only helpers, and `savepoint` / `tenantSavepoint` for
recoverable sub-operations inside a write transaction (rolling back SQL/runtime errors and nested
callbacks without poisoning the enclosing transaction). Register hooks via `beforeCommit` (write
scopes only, preserving active tenant scope), `afterCommit` (runs all hooks after commit and
connection close, outside any transaction), and `afterCompletion`.

Transactions stay on their owning thread and within the callback. Virtual threads
work. Suspending a coroutine or sharing the connection with child threads is unsupported.
Default isolation comes from the connection pool; `Db.builder(dataSource)` accepts explicit
JDBC isolation, a query timeout (`Duration`), a lock timeout (`Duration`), a `tenantSupplier`, a
`transactionListener`, a `callbackDecorator`, and an `exceptionTranslator`. Both auto-commit-enabled
and auto-commit-disabled pools (such as HikariCP with `auto-commit: false`) are supported.
Read-only scopes, isolation overrides, and timeouts use transaction-scoped `SET TRANSACTION` /
`SET LOCAL` rather than mutating session state on pooled connections.

`Session.borrowRead`, `Session.borrowWrite`, `Session.borrowTenantRead`, and
`Session.borrowTenantWrite` (or `Db.builder(TransactionBackend)`) participate in a caller-owned
transaction when migrating an application incrementally. The connection must have auto-commit
disabled. The caller owns commit, rollback and close, and must propagate failures to its
transaction manager.

## Build behavior

Generation uses a disposable build-local PostgreSQL cluster, shared across modules with
the same migrations. Cleanly stopped migrated data directories are cached per schema digest
(under `~/.gradle/vev/schemas` by default, configurable via `schemaCacheDir`) and cloned with
copy-on-write on subsequent builds, skipping `initdb` and Flyway replay on SQL-only edits.
Unchanged generation is skipped. SQL edits rewrite only changed implementations; schema changes
revalidate queries. Identical generated files retain their timestamps. The Kotlin facade remains
stable when signatures do.

`check` (and so `build`) runs `verifyVevUsage`, which reads the module's compiled main classes and fails
when a generated query is never called, and `verifyVevCallGraph`, which checks cross-service `Db` call graphs
for read-to-write escalation, isolation elevation, nested `writeIsolated`, escaped scopes in deferred/async
lambdas, `@Scheduled` entry points calling ambient `Db.tenant*` without binding a tenant, nested explicit-tenant
switches, `@Transactional` annotations, and outbound HTTP/mail I/O inside transactions. In multi-module applications, configure `verifyVevCallGraph` on the
top-level application module with all subprojects' `classesDirs` to verify boundaries across module seams.
Generation and compilation accept unused queries, so SQL can be written and generated before the code that
calls it. Delete the SQL file of a query that is no longer called.

`verifyVevTableUsage` checks that every migrated table in the configured `schemas` (default `public`, excluding
extension-owned tables, child partitions, and migration history tables) is reachable from compiled Vev queries
(directly, via views/materialized views, triggers, or user-defined functions). It is opt-in so multi-module
builds sharing one migration directory can aggregate every module's `generateVev.relationsManifest` on the
top-level application module and wire it into `check`, with `ignoredTables` for intentional exceptions (external
callers or staged drops — entries that no longer exist or are already referenced by Vev queries fail the build).

A failing build reports every rejected query file at once and writes no generated code.
Application statements are described, not executed. Runtime constraints, permissions,
concurrency and business rules remain database/execution concerns. Match deployment's
PostgreSQL major, extensions and search path. Installed extensions are external inputs;
force regeneration after changing them. Shared output caching is disabled.

See [design](docs/analysis.md), [performance and verification](docs/performance.md),
[contributing](CONTRIBUTING.md), and [release procedure](docs/releases.md).
