# Architecture

> **Status: experimental design contract.** This document describes the direction of Vev 0.1, not a stable public API.

## EntityAgent first

The Jakarta compatibility surface starts with the preview [`jakarta.persistence.EntityAgent`](https://jakarta.ee/specifications/persistence/4.0/apidocs/jakarta.persistence/jakarta/persistence/entityagent), not an `EntityManager`. It performs operations without a persistence context and returns detached entity instances.

The Jakarta contract describes an application-managed `EntityAgent` as persistence-unit scoped, non-thread-safe, and closeable. Vev's current adapter narrows that lifecycle: `VevEntityAgents` constructs it inside a lexical `PgVev` transaction callback and closes it automatically. It may operate on multiple accepted types but cannot be manually committed or shared.

One generated entity plan represents one accepted entity mapping and contains immutable execution data:

- exact table and column identifiers;
- typed JDBC binders and row readers;
- identifier and tenant-key access where applicable;
- schema expectations needed to detect drift.

The PostgreSQL runtime constructs and caches fixed statement shapes from that validated metadata. Raw SQL is not an entity-plan SPI, so a hand-written plan cannot replace a point read with an arbitrary statement.

Generated plans are intended to be reusable because they do not retain a connection, transaction, loaded entity, or mutable query state. `EntityAgent`, query, transaction, and execution-context lifecycles follow their own stricter contracts.

An entity returned by an `EntityAgent` is an ordinary detached snapshot. Vev does not promise that two reads of the same row return the same Java object. Mutation of that object does not schedule a database update. Writes occur only through explicit operations backed by generated plans.

With the current immutable record profile, the adapter can perform single version-qualified delete but cannot honor Jakarta's `void` insert, update, upsert, or refresh contract without losing the database-produced identifier or version. Those operations fail before SQL. Non-empty Jakarta batch delete also fails before SQL instead of exposing ordered partial effects after a recoverable late conflict. The native typed API returns the database-produced snapshot from insert, returns applied/missing/conflict outcomes from update and upsert, and returns explicit delete outcomes; an applied update or upsert contains the replacement snapshot.

The native execution path is `TransactionExecutor` to a lexical `ReadTx` or `WriteTx`, then `ReadEntities` or `WriteEntities`. The `vev-jakarta4` module adapts its selected `EntityAgent` operations onto that smaller native contract. It does not implement the complete Jakarta Persistence provider surface.

## Why stateless is the default

A persistence context couples identity, dirty checking, flushing, proxy initialization, cascade traversal, and transaction lifecycle. Those features are useful, but reproducing them partially is dangerous. Vev's initial contract leaves them out so that a database effect can be traced to a visible call and a generated SQL shape.

This direction favors:

- bounded allocations and predictable ownership;
- no lazy load outside a transaction;
- no implicit flush before an unrelated query;
- no hidden graph traversal on write;
- easier use with virtual threads and structured concurrency;
- compile-time rejection when stateful ORM semantics are required.

It also means applications relying on managed-entity behavior must be redesigned or remain on their existing ORM.

## Component boundaries

```text
Build time:
Jakarta-annotated source ---> vev-processor ---> diagnostics
                                      |
                                      +--------> entity plans + model registry/fingerprint ---> javac

Runtime:
application ---> vev-jakarta4 selected EntityAgent adapter
                                      |
                                      v
                    vev-core transaction and entity contracts
                                      |
                                      v
                         vev-postgres execution ---> PostgreSQL 18.x (18.4 verified)

Generated plans ---------------------^
```

`vev-core` must not depend on Jakarta Persistence, Hibernate ORM, Spring, or a PostgreSQL driver. Annotation interpretation and plan generation belong to the processor. Jakarta `EntityAgent` lifecycle adaptation belongs to `vev-jakarta4`. PostgreSQL-specific SQL and catalog behavior belong to `vev-postgres`.

Benchmark modules stay outside the runtime graph. In particular, the Hibernate baseline must never become a transitive dependency of Vev.

## Runtime rules

The target runtime contract is:

1. A caller presents an opaque `TenantScope<Model,T>` minted by the generated, single-use authority permanently claimed by that verified `PgVev`, then enters an explicit lexical read or write transaction callback.
2. A caller selects a generated entity operation or the supported bounded ID-scan factory and supplies typed inputs.
3. The PostgreSQL runtime obtains a connection to the one pinned TCP primary and configures a bounded `SERIALIZABLE` transaction with synchronous commit, UTC, verified tenant/RLS state, and database/network deadlines.
4. The runtime verifies that the entity plan belongs to the closed generated model and that a query is a runtime-created safe query, then executes the internally compiled and cached SQL shape with validated bound values.
5. Immediately before commit the runtime re-attests endpoint, database, role, tenant, encoding, isolation, deadline, and read/write state; it then closes JDBC resources deterministically and translates failures without retrying implicitly.

Connections, prepared statements, and application-managed `EntityAgent` instances must not cross concurrent task boundaries. Immutable generated plans may be shared; an execution context may not unless its contract explicitly permits it.

## Failure policy

The processor is the first safety boundary. A mapping is accepted only if every encountered Jakarta or Hibernate persistence annotation in the closed model is implemented or rejected. Unknown or ambiguous mappings are errors, not warnings.

The processor owns only the `@VevModel` trigger annotation and emits Vev-suffixed types, leaving Jakarta entity annotations and conventional `Entity_` static-metamodel names available to Hibernate or another processor during migration.

Runtime validation remains necessary for facts unavailable to the compiler, including schema drift, transaction state, tenant-authority binding, constraint failures, and connection ownership. Model marker types prevent ordinary cross-model mixing at compilation. Raw/erased or foreign-authority scopes are rejected before connection acquisition even when their Java type and tenant value match. JDBC failures, generated-plan invariant failures, and failures escaping the lexical callback poison the transaction. Documented optimistic outcomes and rejected pre-SQL inputs remain recoverable because neither means that PostgreSQL state is uncertain. Vev has no savepoint recovery after poisoning.

A SQL exception from `commit()` has an indeterminate outcome and is reported as such; callers must not retry it automatically. Once `commit()` has returned successfully, a later connection-close failure cannot reinterpret the committed operation as failed. Vev emits a best-effort JFR cleanup event without bind values instead. Serialization failures are never retried inside Vev: an application may retry the entire lexical transaction only when it has separately proved that all surrounding effects are replay-safe.

Optimistic update and delete outcomes are classified atomically against one PostgreSQL command snapshot: a tenant-visible row with another version is `Conflict`, while no visible row is `Missing`. This avoids an extra round trip and does not lock an already-stale row merely to classify it. A version-zero upsert that loses a concurrent invisible insert is explicitly `Conflict`; serialization failures at stronger isolation levels remain database failures and poison the lexical transaction.

## Compatibility layers

Jakarta annotations are inputs to an adapter; they are not Vev's runtime architecture. Future adapters may interpret other metadata formats, but each adapter must produce the same closed intermediate model and the same rejection guarantees.

Hibernate compatibility is a migration concern, not a runtime dependency. Vev does not load Hibernate metadata, implement Hibernate SPIs, or claim session-semantic equivalence.
