# Design history

Vev's initial exploration used Jakarta annotations to compile immutable entity
mappings into a restricted PostgreSQL runtime. It established useful ideas about
explicit writes, generated mapping, lexical transactions, and tenant capabilities.
However, its entity query surface could not express the joins and projections our
applications needed. Exact schema attestation also rejected legitimate indexes
and deferred business constraints. Its Jakarta facade did not provide provider
compatibility.

We evaluated three directions: managed entities, a generated SQL DSL such as
jOOQ, and SQL compiled into typed functions. Managed entities retain value for
existing graph-oriented workflows. jOOQ remains a strong choice for dynamic SQL.
For our PostgreSQL-only applications, named SQL gives the most direct review
surface and lets the database validate semantics during the build.

The SQL-first design therefore uses PostgreSQL Parse/Describe, generated Kotlin
contracts, conservative nullability, distinct scalar domain types, and a small
explicit JDBC runtime. It preserves database constraints and treats compiler
diagnostics as a core interface for both humans and coding agents. It does not
attempt a second PostgreSQL parser/type system or claim that static checking
proves business rules or performance.

The previous exploration remains in Git history at `v1.0.0`. SQL-first 1.0.0 uses
new artifact names (`runtime`, `compiler`, `gradle-plugin`) and tag `sql-v1.0.0`;
published historical coordinates and tags are not overwritten. There is no legacy
compatibility layer in the current source tree.

## SQL-first 2.0.0

Applying SQL-first 1.0.0 to a large PostgreSQL application exposed four gaps, each
fixed without widening the design.

Generation booted one cluster per Gradle module. Measured against a 195-module
repository with 119 migrations, that is about five seconds of `initdb`, startup and
migration replay per module. A build service now owns one cluster per build and names
one database per migration-set digest, so modules describe against it concurrently.
Cluster lifecycle moved to a dependency-free `cluster` module that both the compiler
and the Gradle plugin use.

Enum columns generated as `String`. An application that already owns its enums does not
want a parallel generated type, so `-- enum:` maps a textual column or parameter onto an
existing Kotlin enum and fails on a constant Kotlin does not declare.

Conservative nullability is sound but, for CTEs, aggregates and `INSERT ... RETURNING`,
it pushed `!!` into call sites. `-- notnull:` narrows the declared type while keeping the
generated check, so a wrong declaration fails on the offending row rather than decoding a
wrong value. This is the one deliberate reversal of 1.0.0's "no non-null override", taken
because the alternative moved the same risk somewhere less visible.

`citext`, `xml` and `inet` were unsupported, and typed `getObject` does not work for them
in pgjdbc, so text-like columns now read through `getString`. Array element types were
narrowed to those with verified JDBC decoding. `spring` adds `VevTemplate` for running
generated queries inside a Spring-managed transaction alongside Hibernate.
