# Vev 2.0.0

Kotlin-first, tenant-scoped SQL for JDK 27 and PostgreSQL 18.

Write PostgreSQL SQL. Vev applies Flyway migrations to a disposable build database,
asks PostgreSQL to parse and describe queries, and generates typed Kotlin APIs and
JDBC mappings. Application queries are not executed during generation. There is no
ORM, query DSL, persistence context, reflective mapper, JPA, or Spring adapter.

## Use

Install PostgreSQL 18 (`postgres`, `initdb`, `pg_ctl`) and run Gradle on JDK 27.
Use the public Maven repository in `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        maven("https://beint-no.github.io/vev/maven") {
            content { includeGroup("no.beint.vev") }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        maven("https://beint-no.github.io/vev/maven") {
            content { includeGroup("no.beint.vev") }
        }
        mavenCentral()
    }
}
```

The group filter keeps unrelated dependency lookups off this repository. It is
served from the existing Vev repository's publication branch and needs no consumer
credentials. The GitHub release also includes a signed standalone Maven repository
zip for offline use.

```kotlin
plugins {
    kotlin("jvm") version "2.5.0-Beta2"
    id("no.beint.vev") version "2.0.0"
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

The plugin adds Vev's dependency-free runtime. Supply pgjdbc and your connection
pool. Compiler dependencies, including Flyway, do not enter the runtime graph.
The default migration location is `src/main/resources/db/migration`.
Place one named query per file in `src/main/sql`:

```sql
-- name: customers :many
SELECT id, name FROM customer
WHERE tenant_id = :tenantId AND id > :after
ORDER BY id LIMIT :limit::int;
```

```kotlin
val db = no.beint.vev.Vev(dataSource)
val customers = db.read(42) { Queries.customers(after = 0, limit = 100) }
// A separately declared UPDATE query:
val changed = db.write(42) { Queries.renameCustomer(id = 123, name = "Example") }
```

Generated functions resolve the active transaction through a JDK `ScopedValue` and
bind `:tenantId` from it. No session or tenant argument appears in query calls.
Kotlin retains data classes, named arguments, nullability and scalar value classes.
Static JVM methods support Java; value-class parameters use their underlying JVM
representation in Java. The Kotlin compiler owns all Kotlin metadata and bytecode.

## Tenant scope

`read(tenantId)` and `write(tenantId)` require positive integer IDs. Queries are
tenant-scoped by default and must contain a required integer `:tenantId` parameter.
Global SQL explicitly opts out:

```sql
-- name: exchangeRate :optional
-- scope: global
SELECT rate FROM exchange_rate WHERE currency = :currency;
```

Use `db.readGlobal { ... }` or `db.writeGlobal { ... }` for global-only work.
Global reference queries may also run in a tenant transaction. Tenant queries in a
global transaction fail before preparing SQL. Calls outside transactions fail.
Nested Vev transactions are rejected before acquiring another connection.

**Binding is not authorization or proof of a correct tenant predicate.** Obtain
the tenant from authenticated context. Write explicit tenant predicates and tenant
equality in joins. Keep composite foreign keys, least-privilege roles and RLS where
required. Vev does not inject predicates, rewrite joins, or certify tenant isolation.

For RLS, construct `Vev(dataSource, Vev.DEFAULT_ISOLATION, true)`. It installs transaction-local
`vev.tenant_id` once per transaction, clearing it for global work and adding one statement. PostgreSQL resets
it on commit/rollback. Policies should fail closed for a missing setting, for example
`nullif(current_setting('vev.tenant_id', true), '')::int`. Use a nonowner,
non-superuser application role; owners ordinarily bypass RLS. Ordinary parameter
binding adds no context-management statements.

Transactions are synchronous and confined to their owning thread. Virtual threads
are supported. Do not suspend/dispatch coroutine work inside the callback, or share
sessions with child threads. Scoped values are not coroutine context propagation.
Escaped and cross-thread sessions fail closed.

## Query contracts

| Directive | Contract |
|---|---|
| `-- name: query :many` | Bounded materialized list, default maximum 1,000 rows |
| `-- name: query :one` | Exactly one row; missing/extra rows fail |
| `-- name: query :optional` | Zero or one row; extra rows fail |
| `-- name: query :exec` | Affected-row count as `Long` |
| `-- name: query :void` | Execute a void-returning function for its effect |
| `-- scope: global` | Explicit opt-out from automatic tenant binding |
| `-- nullable: name, note` | Nullable inputs |
| `-- notnull: id, total` | Non-null result assertion, checked on returned rows |
| `-- type: id CustomerId` | Distinct Kotlin scalar value class |
| `-- enum: status example.Status` | Existing textual Kotlin enum |
| `-- rows: 500` | Materialization ceiling, from 1 to 1,000,000 |

Values are bound. Use casts for ambiguous inputs (`:name::text IS NULL`) and real
arrays for `= ANY(:ids::int[])`. List result columns explicitly. Wildcards, multiple
statements, unsupported types, ambiguous parameters, duplicate names and incompatible
result contracts fail generation. Snake-case result names become camelCase properties.

Nullability is conservative for outer joins, CTEs, grouping, set operations,
expressions and returned DML. `notnull` is a runtime-checked assertion, not a static
proof. Constraints, cardinality, enum contents, concurrent outcomes and business
rules still require runtime checks. The compiler does not execute function bodies
or prove application permissions, execution plans or production schema equivalence.

Supported scalars: boolean, smallint/int/bigint, real/double precision, numeric,
text/varchar/char/name/citext, XML, inet, UUID, date/time/timestamp/timestamptz,
bytea, JSON and JSONB. Numeric uses `BigDecimal`; temporal types use `java.time`,
with `OffsetDateTime` for timestamptz. JSON and text-like special types use `String`.
One-dimensional boolean/number/string/UUID arrays retain nullable elements.
Unsupported types fail; cast deliberately where appropriate. Multidimensional
arrays and values not representable by the JVM type fail decoding.

SQL structure is static. Use separate queries for different identifiers/sort orders.
Bulk work can use set-based SQL and typed arrays, without a runtime query builder.

## Transactions

One pooled connection, one callback, one commit and one close. Reads are
database-enforced read-only. Isolation inherits the pool configuration (PostgreSQL
defaults to READ COMMITTED). Explicit READ COMMITTED, REPEATABLE READ and SERIALIZABLE
overrides are available; an override adds pgjdbc's isolation-setting SQL statement. Query failures poison a transaction even when caught
inside the callback. Commit failures preserve SQLState and are never automatically
retried. Cleanup errors are suppressed on the original error. Cleanup failure after
a successful commit is logged without reporting the committed write as failed.

`Session.borrow(connection, tenantId) { ... }` participates in caller-owned JDBC
transactions without committing, rolling back or closing them. The caller must
roll back on failure and manage any RLS setting. Public Session execution methods
are generator plumbing; manually supplying SQL bypasses build-time validation.

## Builds and verification

The task's optional `postgresBin` selects its PostgreSQL binary directory; all modules
in a build must select the same directory. One lazy Gradle build service owns one
disposable PostgreSQL cluster and one
migration replay per distinct schema. Modules share it. Reusable isolated Gradle
classloaders avoid compiler JVM launches per module. Generated implementations are
split per query with a stable public facade; SQL-only edits leave unrelated files
untouched. Removed queries remove owned generated files. Gradle skips unchanged
generation entirely. Configuration cache is tested. Shared output caching is disabled
because installed extensions are external state. Force regeneration with
`--rerun-tasks` after changing installed extensions. No database daemon is retained
between builds. `initdb` must run as a non-root user.

Build/deployment PostgreSQL majors, extensions, search path and relevant settings
must match. Build checks do not attest production schema or permissions and do not
remove PostgreSQL's runtime query preparation/planning.

```sh
CI=true ./gradlew clean check releaseBundle :benchmarks:jmhClasses
./gradlew :benchmarks:buildPerformance :benchmarks:jmh
python3 scripts/check-performance.py
```

The schema-only ReAI corpus contains 432 tables including one unlogged table, plus
views, functions, triggers, indexes and constraints. Provenance is pinned in
`test-fixtures/reai/provenance.txt`. No production rows are included. Execution
fixtures are independently synthetic: three tenants with overlapping identifiers,
900 vouchers and 1,800 postings.

Thousands of database-backed checks exercise column contracts and SQL rejections.
Execution covers generated scalar/array readers, Unicode, NULLs, empty arrays,
joins, CTEs, windows, lateral joins, composite foreign keys, deferred triggers,
tenant cascades, RLS, failures and virtual threads. Independent Kotlin/Java consumers
and Gradle TestKit verify the actual API. CI executes tests against its installed
PostgreSQL rather than restoring cached test results. CI checks Linux/macOS correctness and
paired JDBC benchmarks with allocations and separate build-phase timings.

Performance gates detect gross regressions, not production SLAs. No benchmark
establishes a Hibernate speedup or predicts application throughput/startup gains.
See [current analysis](docs/analysis.md), [performance](docs/performance.md), and
[release procedure](docs/releases.md).
