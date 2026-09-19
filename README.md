# Vev

SQL-first persistence for **JDK 27, PostgreSQL 18, and Kotlin**.

Write PostgreSQL queries. Vev applies your Flyway migrations to a disposable local
PostgreSQL cluster, asks PostgreSQL to parse and describe each query, and generates
typed Kotlin functions and result classes. Application queries are never executed
during generation. There is no entity model, query DSL, reflection-based mapper,
persistence context, or Jakarta dependency.

## Use

Install PostgreSQL 18 with `postgres`, `initdb`, and `pg_ctl` on `PATH`. Run Gradle
with JDK 27. SQL-first artifacts use `no.beint.vev:runtime:1.0.0`,
`no.beint.vev:compiler:1.0.0`, and the `no.beint.vev` Gradle plugin.

```kotlin
plugins {
    kotlin("jvm") version "2.4.20"
    id("no.beint.vev") version "1.0.0"
}

repositories { mavenCentral() }

tasks.named<no.beint.vev.gradle.GenerateSql>("generateVev") {
    packageName.set("example.db")
    className.set("Queries")
}
```

Add `mavenCentral()` to `pluginManagement.repositories` in settings. The plugin
adds the runtime dependency and wires generation into Kotlin compilation.
The application supplies pgjdbc and its connection pool. Kotlin 2.4.20 emits JVM
26 bytecode; compile and run with JDK 27. Set `JvmTarget.JVM_26` in Kotlin builds
that otherwise derive their bytecode target from the JDK 27 toolchain.

Place Flyway migrations in `src/main/resources/db/migration`. For example:

```sql
CREATE TABLE customer (
    id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id integer NOT NULL,
    name text NOT NULL
);
```

Place one named query in each file under `src/main/sql`:

```sql
-- name: customersAfter :many
-- tenant: tenantId
-- type: id CustomerId
-- type: after CustomerId
-- rows: 1000
SELECT id, name FROM customer
WHERE tenant_id = :tenantId AND id > :after
ORDER BY id
LIMIT :limit::integer;
```

The compiler generates a `CustomerId` value class, a result with non-null `id`
and `name`, and a function taking `TenantSession`, `CustomerId`, and `Int`:

```kotlin
val database = no.beint.vev.Vev(dataSource)
val customers = database.read(42) { session ->
    Queries.customersAfter(session, CustomerId(0), 100)
}
```

## Query contracts

| Directive | Meaning |
|---|---|
| `-- name: find :many` | Return a bounded list; default maximum 1,000 rows |
| `-- name: find :one` | Require exactly one row |
| `-- name: find :optional` | Return one row or `null`; reject multiple rows |
| `-- name: change :exec` | Return a `Long` affected-row count; no result set |
| `-- nullable: name, from` | Allow null for the listed input parameters |
| `-- type: id CustomerId` | Generate a distinct scalar domain type for that input or output name |
| `-- tenant: tenantId` | Require `TenantSession`; bind this integer parameter from its scope |
| `-- rows: 500` | Override the materialization ceiling, between 1 and 1,000,000 |

Directives precede SQL. Parameters and aliases use ASCII identifiers. Result
properties convert snake_case to camelCase. Use explicit result columns and
distinct aliases; wildcards, multiple statements, ambiguous parameters, unknown
types, and duplicate generated names fail the build. Use PostgreSQL casts to
resolve ambiguous inputs, such as `:name::text IS NULL`. Repeated named inputs must
have the same PostgreSQL type at each occurrence. Values are always bound;
SQL literals, quoted identifiers, nested comments, dollar quoting, casts, and
PostgreSQL `?` operators are lexed without confusing them with parameters.
Use separate named queries for different identifiers or sort orders; SQL structure
is not assembled from runtime strings. Public `Session` execution methods are
generator plumbing: manually supplying SQL to them bypasses build-time checking.

**Nullability is conservative.** A plain SELECT can inherit NOT NULL from origin
columns. Outer joins, set operations, grouping, CTEs, subqueries, and returned DML
rows default to nullable results. Standalone `EXISTS` and `COUNT(*)` projections
are recognized as non-null. Other expressions remain nullable when a proof is
unavailable. There is no unsafe non-null override. Changing a query to an outer
join therefore changes generated types and makes unsafe application access fail
compilation. Declaring nullable inputs is an explicit caller contract, not proof
that every database constraint accepts NULL.

Supported scalars: boolean; smallint/integer/bigint; real/double precision;
numeric (`BigDecimal`); text/varchar/char/name; UUID; date/time/timestamp/timestamptz
(`java.time`, with `OffsetDateTime` for timestamptz); bytea (`ByteArray`); json/jsonb
(`String`). One-dimensional arrays support booleans, numbers, strings and UUIDs,
retaining nullable elements. Unknown types fail generation; cast deliberately
where appropriate. Multidimensional arrays and values JDBC cannot represent
(for example non-finite numeric values as BigDecimal) fail decoding.

## Transactions and tenancy

`Vev.read` and `Vev.write` acquire one connection, execute one lexical callback,
commit once, and close it. Read transactions are database-enforced read-only.
The default isolation is READ COMMITTED; constructor configuration also accepts
REPEATABLE READ and SERIALIZABLE. No implicit retries, cascades, dirty checking,
or extra entity loads occur. Query errors and cardinality violations poison the
scope, even if caught inside its callback. Commit failures propagate with their
SQLState; an uncertain commit must not be blindly retried. A cleanup failure after
a successful commit is logged without reporting the write as failed.

`Session.borrow(connection) { ... }` joins a caller-owned transaction. It requires
autocommit off and never commits, rolls back, or closes the connection. The owner
must roll back on failure. With Spring JDBC, obtain the transaction-bound
connection through `JdbcTemplate` or `DataSourceUtils`; with Hibernate, use
`Session.doReturningWork` and flush pending managed changes when necessary.
Do not open an independent Vev transaction inside an operation that must be atomic
with an existing framework transaction.

`TenantSession.borrow(connection, tenantId) { ... }` binds a positive tenant
identifier without additional database statements. For RLS policies using
`vev.tenant_id`, explicitly wrap the work in `tenant.withRls { scope -> ... }`.
That scope reads, installs, and restores the transaction-local setting (three
additional statements). Place it around the whole unit of work, not each query.
The tenant identifier must be positive. Session use outside its lexical
scope or thread fails. Tenant-aware generated functions cannot be called with a
plain session or an arbitrary per-query tenant argument.

**Tenant scope is not authorization or a SQL predicate proof.** Resolve it from
the authenticated application context. Keep tenant predicates, composite foreign
keys, least-privilege roles, and PostgreSQL RLS where required. Vev does not add or
certify policies. The compiler does not prove business correctness, row counts,
constraint satisfaction, query plans, or concurrent outcomes. Database constraints,
including deferred constraint triggers, remain authoritative.

## Build and deployment

Generation requires only migrations and installed PostgreSQL binaries, never
production credentials or customer data. Every run owns a password-protected,
loopback-only cluster that is stopped and removed on success, failure, or normal
process termination. `initdb` must run as a non-root user. Migrations are trusted
build inputs and execute with privileges inside that disposable cluster.

Configure `queries`, `migrations`, `output`, or `postgresBin` on `generateVev` to
change their defaults. Optional `fixtures` supplies an additional Flyway location
for synthetic build-only callbacks when historical migrations require rows.
Keep it outside application resources. Concurrent-index migrations use Flyway
session locking. The fixture cluster has bounded statement and lock timeouts;
explicit migration settings can override them.

Gradle tracks query files, migration files, fixtures, compiler classpath, and the
PostgreSQL binary version. Unchanged generation is up-to-date; configuration cache
is supported. Shared output caching is disabled because extension installations
are external state. Regenerate after changing extensions. Generated Kotlin and
query hashes are under `build/generated/vev`; do not edit or commit them.

The deployment schema and name resolution must match the build schema. Generation
is not a live production-schema proof. Deploy migrations and application versions
with an explicit compatibility strategy. Vev leaves indexes, triggers, constraints,
connection pools, and database recovery under application ownership.

## Verification

```sh
./gradlew clean check releaseBundle
./gradlew :benchmarks:jmh
```

Tests include PostgreSQL-backed compiler rejection, independent Kotlin compile
failures, runtime type round trips, RLS, deferred constraints, transaction failures,
resource ownership, statement counts, and a Gradle TestKit consumer that verifies
configuration caching and migration invalidation. JMH compares equivalent generated
and handwritten JDBC work with result parity and allocation profiling. See
[performance evidence](docs/performance.md); no general speed advantage is claimed.

The runtime has no dependencies. Compiler, Flyway, Kotlin compiler tooling, test
libraries, and JMH stay outside the application runtime graph.

[Release process](docs/releases.md) · [Design history](history.md) · [Apache 2.0](LICENSE)
