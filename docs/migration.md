# Migration expectations

> Vev is not a drop-in Hibernate replacement or a general ORM. Migration is an explicit redesign onto a narrower persistence model.

Jakarta Persistence 4 forbids records as entities, while Vev deliberately requires immutable records. Vev reuses selected Jakarta annotations only as nonconforming source metadata. An existing Hibernate/Jakarta entity cannot become a Vev mapping in place or be managed by both implementations; migration requires a separate or rewritten record model.

Reusing some annotation spellings can reduce mechanical mapping work, but annotations are only one part of ORM behavior. Applications may depend on session identity, flush timing, proxies, cascades, JPQL, repository proxies, callbacks, lock modes, provider annotations, or exception behavior without making those dependencies obvious.

The current experiment consumes a closed selection of Jakarta Persistence `4.0.0-M6` annotations, is deliberately nonconforming, and has not passed the Jakarta Persistence TCK. The `vev-jakarta4` module is an `EntityAgent`-shaped facade whose selected behavior is evaluated operation by operation; it is not a Jakarta provider implementation. Comparisons with Hibernate ORM 8.0.0.Beta1 are not evidence of source-model or provider compatibility.

## Candidate selection

Start with a flat aggregate that has:

- one table and one non-null assigned scalar identifier whose value is never reused within a tenant;
- explicit columns using accepted basic types, with `nullable = true` or `nullable = false` written on every `@Column`;
- no relationship persistence, inheritance, converter, callback, or lazy field;
- explicit transaction ownership;
- a small, measurable query surface expressible as point/batch ID access, bounded ID traversal, or generated scalar equality/`IS NULL` pages;
- synthetic integration fixtures that represent nullability and constraint edges.

The mapped table cannot currently carry check constraints, foreign keys, or unique secondary indexes. Each supported lookup index must be declared with `@VevIndex` and installed by the migration as the exact non-unique B-tree `(tenant, indexed value, id)`. If removing those database constraints would weaken an aggregate, it is not a migration candidate yet.

If an aggregate does not fit the supported profile, leave it on the existing provider. Do not remove an annotation merely to make compilation succeed unless the corresponding behavior is intentionally replaced.

## Suggested migration sequence

1. Inventory entity annotations, provider annotations, repositories, JPQL/HQL, Criteria, `EntityManager` use, locks, callbacks, and transactional call sites.
2. Select one bounded aggregate and reduce its mapped-table constraints to Vev's verified primary-key, declared non-unique equality-index, nullability, and row-security profile.
3. Introduce a separate immutable Vev record model, add Vev processing, and treat every rejection as a compatibility decision. A Jakarta/Hibernate processor may remain in the same build for the distinct legacy entity types.
4. Review generated metadata and the runtime's fixed SQL/schema contract in CI. Compilation does not inspect the live database; start `PgVev` against a migrated disposable PostgreSQL 18 database to exercise catalog attestation.
5. Run differential reads against disposable representative synthetic schemas; compare canonical values and row counts.
6. Observe shadow reads before changing the authoritative read path.
7. Move writes only after rollback, assigned-key, set-based batch-insert, all-or-nothing update-batch, constraint, serialization, indeterminate-commit, and cleanup-failure semantics are verified.
8. Keep a reversible routing boundary until the new path has operational evidence.

Do not dual-write two persistence implementations inside one request unless atomicity and replay semantics are explicitly designed. Shadow writes should target disposable data or a replayable test environment.

## Semantic changes to expect

| Existing ORM behavior | Vev expectation |
|---|---|
| Managed identity and dirty checking | Explicit stateless reads, insert, and version-qualified update |
| Lazy proxies | Explicit follow-up queries or application composition |
| Cascade and orphan removal | Explicit service operations; relationship and business constraints on mapped tables are not accepted yet |
| JPQL/HQL/Criteria | Generated point/batch operations, ID-ordered bounded scans, and generated scalar equality/`IS NULL` pages with typed exclusive-key continuation; no arbitrary DSL, projection, join, `OFFSET`, or unbounded query |
| Spring Data repository proxy | Explicit agent integration; no current registrar promise |
| Lifecycle callbacks | Explicit application behavior |
| Provider tenant filters | An explicitly injected tenant authority, opaque per-tenant scopes, structurally generated tenant predicates, and forced database row security |
| Automatic schema management | External migrations followed by schema verification |
| Provider exception taxonomy | Vev/JDBC failure contract; audit caller expectations |

The deployment boundary changes too. A `PgVev` instance pins one verified PostgreSQL 18 TCP primary, database, role, server incarnation, model fingerprint, and single-use tenant authority. Give it a dedicated pgjdbc `DataSource` configured so every new connection already reports exact `pg_catalog` search path and UTF-8 transport. Retained temporary schemas fail checkout. Vev does not repair the path per transaction, which keeps resolution fixed and preserves pgjdbc prepared-query caching. Do not put the instance behind multi-host discovery, a replica/failover router, a Unix socket, or PgBouncer transaction/statement pooling. Rebuild and re-verify it with a fresh generated authority after a migration, PostgreSQL restart, promotion, credential/role change, or endpoint change.

The application role must have only the exact generated column-level `INSERT` and, for versioned mappings, mutable-column/version `UPDATE` grants. It must not have `DELETE`. Hard delete and create-capable upsert are not migration equivalents; represent retirement as an explicit versioned state update. `insertMultiple` rejects duplicate IDs before SQL and verifies each `RETURNING` snapshot in input order. `updateMultiple` is one guarded set-based statement and rolls back the complete lexical transaction if any member is stale, missing, or returned unexpectedly.

Vev never retries a write. Treat a commit exception as outcome-indeterminate and reconcile it using an application operation identifier before considering another attempt. Serialization retry must replay the complete lexical transaction and every surrounding effect must be independently idempotent; do not retry only the last SQL statement.

Assigned identifiers remain permanent logical identities. Vev removes physical delete and create-capable upsert from the application surface specifically to avoid a delete/reinsert version-zero ABA path; privileged out-of-band maintenance must preserve the same non-reuse rule.

## Rollback plan

A migration is reversible only if the old implementation can still read the schema and no Vev-only write changes have made that impossible. Define the rollback trigger, routing mechanism, data reconciliation method, and responsible operator before enabling writes.

Compatibility work should proceed aggregate by aggregate. An application-wide switch is not a safe experimental rollout unit.
