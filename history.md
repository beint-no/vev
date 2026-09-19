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
