# Vev

Typed PostgreSQL queries for Kotlin, validated during the build.

Write SQL. Vev applies your Flyway migrations to a disposable PostgreSQL database,
uses PostgreSQL Parse/Describe to validate queries, and generates Kotlin data classes
and JDBC implementations. The runtime has no external dependencies.

Requires JDK 27, Kotlin 2.5.0-Beta2 and PostgreSQL 18. Install `postgres`, `initdb` and
`pg_ctl` on the build machine; the deployed application only needs JDBC and its pool.

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
    id("no.beint.vev") version "2.6.0"
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
Tenant IDs must be positive. SQL must include the required integer bind and explicit
tenant predicates, including joins. Use authenticated context and composite foreign
keys to enforce tenant relationships.

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
| `-- vev.rows: 500` | Materialization ceiling, 1–1,000,000; exceeding it fails |

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

`Session.borrow(connection, tenantId) { ... }` participates in an existing transaction;
its connection must have auto-commit disabled. The caller owns commit, rollback and
close, and must propagate failures to its transaction manager. Flush pending ORM writes
before borrowing a connection while migrating an application incrementally. The caller also
enforces framework read-only policies; a framework hint need not make PostgreSQL read-only.

For PostgreSQL RLS, `Vev(dataSource, Vev.DEFAULT_ISOLATION, true)` sets transaction-local
`vev.tenant_id`. Global work clears the value. Policies can read
`nullif(current_setting('vev.tenant_id', true), '')::int`; use an application role subject
to the policy. Borrowed scopes leave RLS configuration to their caller.

## Build behavior

Generation uses a fresh build-local PostgreSQL cluster, shared across modules with
the same migrations. Unchanged generation is skipped. SQL edits rewrite only changed
implementations; schema changes revalidate queries. Identical generated files retain
their timestamps. The public Kotlin facade remains stable when signatures do.

Application statements are described, not executed. Runtime constraints, permissions,
concurrency and business rules remain database/execution concerns. Match deployment's
PostgreSQL major, extensions and search path. Installed extensions are external inputs;
force regeneration after changing them. Shared output caching is disabled.

See [design](docs/analysis.md), [performance and verification](docs/performance.md),
[contributing](CONTRIBUTING.md), and [release procedure](docs/releases.md).
