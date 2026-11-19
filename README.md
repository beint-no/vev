# Vev

Typed PostgreSQL queries for Kotlin, validated during the build.

Write SQL. Vev applies your Flyway migrations to a disposable PostgreSQL database,
uses PostgreSQL Parse/Describe to validate queries, and generates Kotlin data classes
and JDBC implementations. The runtime has no external dependencies.

Requires JDK 27, Kotlin 2.5.0-Beta2 and PostgreSQL 18. The build machine needs the
PostgreSQL 18 server binaries (`postgres`, `initdb`, `pg_ctl`); the deployed application only
needs JDBC and its pool. The plugin uses `PATH`, then the standard Homebrew, Debian/Ubuntu,
PGDG RPM and Postgres.app locations; set `postgresBin` for any other directory.

## Setup

Add the public repository to both resolution scopes in `settings.gradle.kts`:

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

In the application module:

```kotlin
plugins {
    kotlin("jvm") version "2.5.0-Beta2"
    id("no.beint.vev") version "3.0.0"
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
val db = no.beint.vev.Vev(dataSource)
val customer = db.read(42) { Queries.customer(id = 123) }
val page = db.read(42) { Queries.customers(after = 0, limit = 100) }
```

`read(tenantId)` / `write(tenantId)` provide a lexical transaction. Generated methods
bind `:tenantId` from its scoped value; it is absent from their parameter lists.
Tenant IDs must be positive.

At build time Vev checks relation scans in PostgreSQL's generic plan. Each scan of a
table with a `tenant_id` column must show a restriction to the bound tenant, directly or
through a tenant-key equality with another checked scan. Vev names the table when it cannot
establish that restriction. Compare with `:tenantId` directly where a materialized CTE
or correlated subquery hides the relationship from the planner.

For writes, review both the tenant value assigned to a row and the key that selects an
existing row in `ON CONFLICT DO UPDATE`. Those decisions sit outside the scan check.
Opaque functions, triggers and tables with other tenancy conventions need application
review. Composite foreign keys enforce tenant consistency between stored references.

Global queries declare `-- vev.scope: global`. Run them in `readGlobal` / `writeGlobal`,
or within a tenant transaction. Tenant queries require a tenant transaction.

### Query directives

Directives are compiler inputs written as SQL comments. Keep them before the SQL,
starting with `vev.name`. PostgreSQL ignores them, so SQL editors and database tools
can still read the file. Unknown `vev.` directives and duplicate singleton directives
fail the build. The original unprefixed spelling (`-- name:` etc.) is also accepted.

| Directive | Meaning |
|---|---|
| `-- vev.name: find :many` | Bounded list; maximum 1,000 rows by default |
| `-- vev.name: find :one` | Exactly one row |
| `-- vev.name: find :optional` | Zero or one row |
| `-- vev.name: change :exec` | Mutation affected-row count as `Long` |
| `-- vev.name: lock :void` | Execute a void-returning function |
| `-- vev.scope: global` | No implicit tenant bind |
| `-- vev.nullable: name, note` | Nullable inputs |
| `-- vev.notnull: id, total` | Assert and check non-null output values |
| `-- vev.type: id CustomerId` | Generate a scalar Kotlin value class |
| `-- vev.enum: status example.Status` | Map a textual column/input to an existing Kotlin enum |
| `-- vev.rows: 500` | Optional `:many` result ceiling (default 1,000). Reading one extra row fails the call |

Parameters are bound values. Cast ambiguous inputs, such as `:name::text IS NULL`,
and use PostgreSQL arrays for lists: `id = ANY(:ids::int[])`. List returned columns
explicitly; internal CTEs and subqueries may use wildcards. Result names in snake_case become Kotlin camelCase properties.
Nullability is conservative for expressions, joins, CTEs and aggregates; `notnull`
is a checked assertion rather than an inference. Unsupported types and result rows
exceeding Kotlin’s JVM parameter limit fail generation with a query-local diagnostic.
The same check covers captured query arguments; use PostgreSQL arrays instead of hundreds
of separate binds.

Keep one query per file so agents can discover, read and change its complete contract
without loading neighboring queries. Multiple statements or named queries in a file
fail the build. Organize related files in directories. Reuse database views/functions
when shared semantics have an owner and execution tests; SQL text includes are absent.

## Transactions

Vev commits successful callbacks and rolls back failed callbacks. SQL, decoding,
cardinality and row-bound failures poison the scope even when caught. No retry occurs
automatically. Nested scopes are rejected.

Transactions stay on their owning thread and within the callback. Virtual threads
work. Suspending a coroutine or sharing the connection with child threads is unsupported.
Default isolation comes from the connection pool; the constructor accepts an explicit
JDBC isolation override. Configure the pool with auto-commit enabled for owned scopes.

`Session.borrowTenant(connection, tenantId) { ... }` participates in an existing transaction;
its connection must have auto-commit disabled. The caller owns commit, rollback and
close, and must propagate failures to its transaction manager. Flush pending ORM writes
before borrowing a connection while migrating an application incrementally. The caller also
enforces framework read-only policies; a framework hint need not make PostgreSQL read-only.

## Build behavior

Generation uses a fresh build-local PostgreSQL cluster, shared across modules with
the same migrations. Unchanged generation is skipped. SQL edits rewrite only changed
implementations; schema changes revalidate queries. Identical generated files retain
their timestamps. The Kotlin facade remains stable when signatures do.

Application statements are described, not executed. Runtime constraints, permissions,
concurrency and business rules remain database/execution concerns. Match deployment's
PostgreSQL major, extensions and search path. Installed extensions are external inputs;
force regeneration after changing them. Shared output caching is disabled.

See [design](docs/analysis.md), [performance and verification](docs/performance.md),
[contributing](CONTRIBUTING.md), and [release procedure](docs/releases.md).
