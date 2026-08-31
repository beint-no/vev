# Architecture

> **Status: experimental design contract.** This document describes the direction of Vev 0.2, not a stable public API.

## Native stateless kernel

Vev's architecture is its small native stateless API, not a Jakarta provider SPI or a general ORM. The Jakarta-facing experiment uses the preview [`jakarta.persistence.EntityAgent`](https://jakarta.ee/specifications/persistence/4.0/apidocs/jakarta.persistence/jakarta/persistence/entityagent), not an `EntityManager`, as an optional migration-shaped facade. The current adapter is deliberately nonconforming: it implements selected operations but does not honor every inherited option, property, lifecycle, or exception contract.

Jakarta Persistence 4 forbids records from being designated as entities. Vev deliberately accepts only immutable records and interprets selected Jakarta annotations as nonconforming source metadata. A Vev record is not a Jakarta entity and cannot simultaneously be managed by Hibernate or another Jakarta provider.

The Jakarta contract describes an application-managed `EntityAgent` as persistence-unit scoped, non-thread-safe, and closeable. Vev's current facade narrows that lifecycle: `VevEntityAgents` constructs it inside a lexical `PgVev` transaction callback and closes it automatically. It may operate on multiple accepted Vev mappings but cannot be manually committed or shared. Until the complete interface contract is implemented and verified, it must not be obtained or described as a Jakarta provider-managed agent.

One generated entity plan represents one accepted entity mapping and contains immutable execution data:

- exact table and column identifiers;
- typed JDBC binders and row readers;
- identifier and tenant-key access where applicable;
- identity-stable typed tokens for generated scalar equality indexes;
- schema expectations needed to detect drift.

The PostgreSQL runtime constructs and caches fixed statement shapes from that validated metadata. Raw SQL is not an entity-plan SPI, so a hand-written plan cannot replace a point read with an arbitrary statement.

Generated plans are intended to be reusable because they do not retain a connection, transaction, loaded entity, or mutable query state. The `EntityAgent`-shaped facade, query, transaction, and execution-context lifecycles follow their documented Vev contracts.

A mapped record returned by the facade is an ordinary detached snapshot. Vev does not promise that two reads of the same row return the same Java object. Mutation of that object does not schedule a database update. Writes occur only through explicit operations backed by generated plans.

With the current immutable record profile, the facade can perform assigned-value insert, including a homogeneous bounded batch. Insert is permitted only because the verified schema forbids generated/default values, triggers, and rewrite rules; Vev compares every returned database snapshot with its input and prevents commit on a mismatch. The facade cannot safely discard the replacement state or explicit outcome of update or refresh, so those operations fail before SQL. Physical delete and create-capable upsert are absent from the native API and rejected by the facade. The native typed API returns the verified snapshot from insert and an exhaustive applied/missing/conflict result from a single versioned update.

Native `insertMultiple` is one fixed set-based PostgreSQL statement: one typed array per column is expanded with ordinality, inserted, returned, restored to input order, and snapshot-verified. Duplicate keys fail before SQL. Native `updateMultiple` uses one fixed typed-array statement too. A materialized preflight must match every tenant, identifier, and expected version before its data-modifying CTE can update any row. Results are restored to input order and every non-version scalar plus the exact one-step version transition is verified. Duplicate keys fail before SQL; a stale, missing, malformed, or unexpectedly returned member poisons and rolls back the complete lexical transaction.

The native execution path is `TransactionExecutor` to a lexical `ReadTx` or `WriteTx`, then `ReadEntities` or `WriteEntities`. The `vev-jakarta4` module adapts its selected `EntityAgent`-shaped operations onto that smaller native contract. It neither conforms to the complete `EntityAgent` contract nor implements the complete Jakarta Persistence provider surface.

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
application ---> vev-jakarta4 nonconforming EntityAgent-shaped facade
                                      |
                                      v
                    vev-core transaction and entity contracts
                                      |
                                      v
                         vev-postgres execution ---> PostgreSQL 18.x (18.6 verified)

Generated plans ---------------------^
```

`vev-core` must not depend on Jakarta Persistence, Hibernate ORM, Spring, or a PostgreSQL driver. Annotation interpretation and plan generation belong to the processor. The nonconforming `EntityAgent`-shaped facade belongs to `vev-jakarta4`. PostgreSQL-specific SQL and catalog behavior belong to `vev-postgres`.

Benchmark modules stay outside the runtime graph. In particular, the Hibernate baseline must never become a transitive dependency of Vev.

## Runtime rules

The target runtime contract is:

1. A caller presents an opaque `TenantScope<Model,T>` minted by the generated, single-use authority permanently claimed by that verified `PgVev`, then enters an explicit lexical read or write transaction callback.
2. A caller selects a generated entity operation, ID-ordered bounded scan, or generated-index equality/nullable page, optionally continuing after a generated type-bound key, and supplies typed inputs. Multiple pages share one database snapshot only when executed in the same lexical transaction; a continuation resumed in another transaction has normal keyset-pagination visibility of intervening writes.
3. The PostgreSQL runtime obtains a pgjdbc connection to the one pinned TCP primary, requires its dedicated pool baseline to already be exact `pg_catalog`/UTF-8 with no retained temporary schema, and configures a bounded `SERIALIZABLE` transaction with synchronous commit, UTC, verified tenant/RLS state, and database/network deadlines.
4. The runtime verifies that the entity plan belongs to the closed generated model and that a query is a runtime-created safe query, then executes the internally compiled and cached SQL shape with validated bound values.
5. Immediately before commit the runtime re-attests endpoint, database, role, tenant, encoding, isolation, deadline, and read/write state; it then closes JDBC resources deterministically and translates failures without retrying implicitly.

Connections, prepared statements, and application-managed Vev facades must not cross concurrent task boundaries. Immutable generated plans may be shared; an execution context may not unless its contract explicitly permits it.

## Failure policy

The processor is the first safety boundary. A mapping is accepted only if every encountered Jakarta or Hibernate persistence annotation in the closed model is implemented or rejected. Unknown or ambiguous mappings are errors, not warnings.

The processor owns only the `@VevModel` trigger annotation and emits Vev-suffixed types. It can run in the same build as a Jakarta/Hibernate metamodel processor only when they process distinct source types. A Vev record cannot also be a Jakarta/Hibernate entity, regardless of the generated type-name suffix.

Runtime validation remains necessary for facts unavailable to the compiler, including schema drift, transaction state, tenant-authority binding, constraint failures, and connection ownership. Model marker types prevent ordinary cross-model mixing at compilation. Raw/erased or foreign-authority scopes are rejected before connection acquisition even when their Java type and tenant value match. JDBC failures, generated-plan invariant failures, and failures escaping the lexical callback poison the transaction. Documented optimistic outcomes and rejected pre-SQL inputs remain recoverable because neither means that PostgreSQL state is uncertain. Vev has no savepoint recovery after poisoning.

A SQL exception from `commit()` has an indeterminate outcome and is reported as such; callers must not retry it automatically. Once `commit()` has returned successfully, a later connection-close failure cannot reinterpret the committed operation as failed. Vev emits a best-effort JFR cleanup event without bind values instead. Serialization failures are never retried inside Vev: an application may retry the entire lexical transaction only when it has separately proved that all surrounding effects are replay-safe.

One optimistic update is classified atomically against one PostgreSQL command snapshot: a tenant-visible row with another version is `Conflict`, while no visible row is `Missing`. This avoids an extra round trip and does not lock an already-stale row merely to classify it. In the batch API either every input returns `Applied` or the complete lexical transaction is poisoned and rolled back; Vev never exposes a partially successful default batch. Serialization failures remain database failures and poison the lexical transaction.

## Compatibility layers

Selected Jakarta annotations are reused as nonconforming source metadata; they are not evidence that the record is a Jakarta entity and are not Vev's runtime architecture. Future adapters may interpret other metadata formats, but each adapter must produce the same closed intermediate model and the same rejection guarantees.

Hibernate compatibility is a migration concern, not a runtime dependency. Vev does not load Hibernate metadata, implement Hibernate SPIs, or claim session-semantic equivalence.
