# Explicit physical deletion

`@VevDelete` opts one immutable record into version-checked physical deletion.
It requires both `@GeneratedValue(strategy = IDENTITY)` and `@Version`.
Assigned-ID records and `@AppendOnly` records fail compilation if they opt in.
The annotation is retained on separately compiled Java and Kotlin records.
An entity without this annotation has no generated `DeletableEntityType`
capability, no compiled delete statement, and must have no database `DELETE`
privilege.

```java
@Entity
@VevDelete
@Table(name = "temporary_entry", schema = "example")
public record TemporaryEntry(
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id", nullable = false) Integer id,
        @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
        @Version @Column(name = "version", nullable = false) Integer version,
        @Column(name = "label", nullable = false, length = 64) String label) {}
```

Use the generated capability, identifier, and expected version explicitly:

```java
var target = new DeleteTarget<>(TemporaryEntryVev.INSTANCE, entry.id(), entry.version());
DeleteResult<ModelVev.Model, TemporaryEntry, Integer, Integer> result =
        vev.write(scope, tx -> tx.entities().delete(target));
```

The exhaustive result is `Deleted`, `Conflict`, or `Missing`, each carrying the
original target. The lexical scope supplies the tenant. A key which exists only
under another tenant is missing to this operation. An existing row with another
version is a conflict. Both outcomes are recoverable within the transaction;
neither deletes a row. A successful deletion verifies the returned identifier,
tenant, and exact expected version. Deletion accepts the largest valid version
token because it does not increment it. Negative versions fail before SQL.

The operation does not hydrate or transmit the entity's value columns. Knowing
the identifier and expected version is sufficient; a large document need not be
loaded simply to delete it. Application authorization and business checks remain
the caller's responsibility.

## Identifier lifetime

Deleting an assigned identifier and reinserting it at version zero can make a
stale reference appear current. Vev therefore keeps assigned-ID deletion and
create-capable upsert unavailable. A deletable entity exposes only generated
creation, whose verified sequence increases by one without cycling, and never
assigned insertion. The role has sequence `USAGE`, without `UPDATE`, grant
options, or sequence ownership. It cannot reset the sequence through this
contract. Deleted identifiers cannot be recreated through Vev's public API.

Sequence values are not gapless. Rolled-back creations still consume values.
Database administration, restores, out-of-band writers, and sequence changes
remain outside that guarantee: they must preserve identifier non-reuse and
invalidate/rebuild the runtime under the normal migration rules. Deletion does
not establish an incarnation token for arbitrary assigned identifiers.

## Batches and failures

```java
Batch<DeleteResult.Deleted<ModelVev.Model, TemporaryEntry, Integer, Integer>> deleted =
        vev.write(scope, tx -> tx.entities().deleteMultiple(
                TemporaryEntryVev.INSTANCE, Batch.copyOf(targets)));
```

Every target must use the exact same generated mapping token. The entity's
`@VevRows` limit applies, up to 1,000 targets. Duplicate identifiers, foreign
mapping tokens, invalid key/version types, and exceeded bounds are rejected
before preparing a statement. Empty batches execute no entity statement.

A nonempty batch uses one fixed statement with typed identifier and version
arrays, one lexical tenant, and expected cardinality. A materialized preflight
must match every requested version before deletion is enabled. Returned rows
carry input ordinality and the actual deleted identifier, tenant, and version;
the runtime checks each and returns results in input order. No payload array,
per-row statement, or hidden batch splitting is used. Transaction setup and
pre-commit attestation still have their normal database calls.

A stale or missing batch member poisons the complete lexical transaction. So do
constraint violations, serialization failures, unexpected result rows, and JDBC
execution or cleanup failures, even if the callback catches the original
exception. Earlier inserts, updates, and deletes roll back together. Vev does not
retry implicitly or expose a partially successful batch. Existing commit-outcome
and connection-close contracts also apply.

## Schema and migration

The mapping fingerprint includes the deletion capability. The generated manifest
sets `privileges.delete` to `true` only for opted-in entities. Startup requires
exactly that table-level `DELETE` grant without grant option; extra delete access
on any other mapped entity or missing access on a deletable entity fails.
Forced RLS, explicit tenant predicates, immediate `NO ACTION` foreign keys,
declared checks, and all other schema restrictions remain in force. Neither
database cascades nor implicit relationship cleanup are introduced.

An existing application's hard-delete paths need review: which entities may be
deleted, which expected version the caller possesses, how conflicts are handled,
and which referencing rows must be explicitly handled first. Do not remove a
foreign key, reinterpret an append-only history, or silently convert retirement
into deletion. The Jakarta facade continues to reject its `void` delete methods;
use the native typed outcome contract.

The PostgreSQL implementation uses documented
[`DELETE ... USING ... RETURNING`](https://www.postgresql.org/docs/18/sql-delete.html)
and [data-modifying CTE](https://www.postgresql.org/docs/18/queries-with.html#QUERIES-WITH-MODIFYING)
semantics. Synthetic integration coverage includes maximum-sized batches,
input-order correlation, Kotlin mappings in text and binary transfer modes,
version exhaustion, tenant isolation, immediate foreign-key failures, concurrent
delete/update races, malformed results, and cleanup failures. This is functional
and failure evidence; no latency or throughput improvement is claimed.
