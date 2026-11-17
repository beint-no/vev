# Current architecture analysis

Vev 2.1.0 targets a PostgreSQL-only, tenant-based application designed without an ORM.
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

## Adopting 2.1 in ReAI

Begin with native report projections. The limited trial replaces three counterparty
queries, leaving service contracts and UI unchanged. Each query remains ordinary
SQL with explicit tenant joins, typed arrays, generated immutable result rows and
asserted non-null business outputs. PostgreSQL checks all three against ReAI's
actual Flyway migrations at build time, without connecting to a developer's database.

During coexistence, ReAI's repository flushes pending JPA changes and uses Spring JDBC to borrow the
existing transaction connection inside the Spring transaction. Vev owns no second
transaction or connection in this path. This bridge belongs to the application;
there is no Hibernate compatibility module in Vev. Removing the bridge becomes
possible when its owning service uses JDBC transactions throughout.

| Concern | Vev | ReAI's JPA/Hibernate approach |
|---|---|---|
| Native PostgreSQL validation | Server Parse/Describe during build | Native query errors generally appear on execution |
| Application types | Generated Kotlin parameters, rows, nullable properties, value classes | Entities/DTOs can be typed; native interface projection contracts are separately maintained |
| HQL/JPQL checking | Not applicable | Hibernate Processor offers compile-time checking; Spring also validates many queries at startup |
| Tenant handling | Required scoped integer bind; explicit SQL predicates | Entity tenant discriminator/filter support; native SQL still needs its own predicates |
| Nullability | Conservative inference plus checked `notnull` assertions | Kotlin entity annotations and projection declarations do not prove SQL-expression nullability |
| Query work | Generated JDBC mapping; no entity tracking or query translation | Native DTO queries can already be efficient; managed entities add persistence-context work |
| Transactions | Explicit lexical scope or borrowed JDBC transaction | Mature Spring/JPA propagation, entity flushing and persistence context |
| Writes | Explicit SQL, row counts, RETURNING, version predicates and database constraints | Dirty checking, cascades and entity lifecycle handled by ORM |
| Kotlin usability | Named arguments and immutable data classes; SQL is edited directly | Convenient entity navigation and repository CRUD; native SQL mappings add separate declarations |
| Build cost | Fresh migration/description on change, generated Kotlin compilation; no-op generation skipped | Entity/application compilation; processor checking costs extra if enabled |
| Startup | No entity/repository/query-model initialization in Vev | Entity metadata and repository initialization remain while Hibernate is present |
| Runtime size | Tiny dependency-free Java core; driver/pool and existing Kotlin runtime still needed | Hibernate core plus JPA, bytecode and other dependencies; exact core comparison in performance notes |
| Maintenance | We own the compiler, type mappings, tests and release compatibility | Broad ecosystem, many mature integrations and maintainers |

Recommend gradual adoption for read-heavy reports, then explicit bounded writes.
Do not mechanically translate the entity graph. Each migrated write needs a decision
about flushing, optimistic locking, cascade behavior, auditing and transaction scope.
The database must keep enforcing tenant relationships and accounting invariants.
Keep Hibernate where its entity lifecycle still earns its cost, and remove it only
when a module's remaining behavior is deliberately replaced. A three-query pilot
cannot establish a whole-application startup or throughput gain.

Direct bytecode generation remains deferred. Kotlin compilation is a measured build
cost, but matching Kotlin's metadata and ABI would add a second compiler backend.
The source generator already yields JVM bytecode, Java interoperability and fast
execution without giving up Kotlin's tooling.

References: [Hibernate Processor](https://hibernate.org/orm/processor/),
[Spring JPA transaction sharing](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/orm/jpa/JpaTransactionManager.html),
[pgjdbc statement preparation](https://jdbc.postgresql.org/documentation/server-prepare/).
