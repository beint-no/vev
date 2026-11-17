# Current architecture analysis

Vev 2.0.0 targets a PostgreSQL-only, tenant-based application designed without an ORM.
It generates Kotlin contracts and JDBC implementations. Entity tracking, JPA/Spring
compatibility, cascade engines and runtime query DSLs are outside the design.

## Direct bytecode versus Kotlin generation

Direct JVM class-file generation is possible. It could remove generated-source
compilation, but equivalent bytecode does not inherently execute queries faster.
Kotlin tooling depends on metadata for properties, nullability, named arguments,
data classes and value classes. A direct backend must maintain metadata, boxing,
mangling, generics, bridge methods, equality/hash semantics and debug information
in sync with the class files. Java-shaped bytecode alone would weaken Kotlin's API.
See https://kotlinlang.org/docs/metadata-jvm.html.

Vev keeps Kotlin source generation so the Kotlin compiler owns this ABI. Static JVM
methods support Java consumers. Per-query implementations plus a stable public
facade make SQL-only changes local to an implementation file. The forwarding call
is a deliberate incremental-compilation boundary and is included in benchmarks.

The build benchmark separates bootstrap, description/generation and actual Kotlin
compilation for 10/100/1,000 distinct queries. A bytecode backend should be added
only when the measured remaining build cost warrants maintaining Kotlin's ABI.
It is not needed to provide a typed JVM API or a small runtime.

## Tenant API

`read(tenantId)` / `write(tenantId)` create a lexical JDBC transaction. Generated
functions resolve it through a private JDK ScopedValue and bind `:tenantId`.
`readGlobal` / `writeGlobal` are explicit transaction opt-outs; global SQL declares
`-- scope: global`. Global reference queries may run inside tenant transactions.
Tenant queries cannot run in global scope. Nested transactions fail before connection
acquisition. No mutable ThreadLocal state or automatic transaction retry is used.

Sessions are confined to their callback and thread. Virtual threads work; coroutine
suspension and dispatch inside a transaction do not. Scoped values are not a
coroutine propagation mechanism, and inheriting a scope never authorizes sharing
its JDBC connection with child threads.

The compiler verifies a required integer tenant parameter, not its placement in a
predicate. SQL stays explicit, including tenant equality in joins. Composite foreign
keys enforce object/tenant relationships. Optional RLS installs one transaction-local
setting and is tested under a nonowner role. Obtain scope from authenticated context.

## Scope of SQL checking

PostgreSQL Parse/Describe resolves names, operators, parameters and result shapes
without executing application statements. Kotlin compilation checks application
use of generated contracts. Unsupported mappings fail rather than silently decode.
Nullability is conservative when origin metadata is insufficient; a `notnull`
assertion checks each returned row and is not a static proof.

Constraints, deferred triggers, row counts, enum contents, plans and concurrent
outcomes need execution tests/database enforcement. Align PostgreSQL major,
extensions, search path, settings and role permissions with deployment. The build
database is not an attestation of the live production schema.

## Build and performance decisions

Share a cluster/schema per build and use reusable isolated compiler workers. Do not
rewrite identical files; remove stale owned outputs. Unchanged generation is skipped.
Schema changes conservatively invalidate queries. External extensions prevent safe
shared output caching without additional environment fingerprinting.

A persistent cross-build cluster and fine-grained schema dependency tracking are
intentionally absent: stale-schema, locking and cleanup failure modes need evidence
that their maintenance cost is justified by fresh-cluster timings.

The tests use ReAI's real schema and independently generated data. Runtime JMH
comparisons hold SQL/results/fetching/transactions constant against JDBC. Library
benchmarks do not prove ReAI-wide speedups. Removing entity mapping and repository
initialization should remove startup work, but this needs application measurement.

Default transaction isolation inherits the pool configuration. pgjdbc unconditionally
sends `SET SESSION CHARACTERISTICS` when `setTransactionIsolation` is called, so Vev
does not issue that call for the default. Explicit isolation overrides retain their
required database statement. PostgreSQL itself defaults to READ COMMITTED.
