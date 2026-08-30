# Migration expectations

> Vev is not currently a drop-in Hibernate replacement.

Reusing Jakarta annotations can reduce source migration, but annotations are only one part of ORM behavior. Applications may depend on session identity, flush timing, proxies, cascades, JPQL, repository proxies, callbacks, lock modes, provider annotations, or exception behavior without making those dependencies obvious.

The current experiment consumes a closed Jakarta Persistence `4.0.0-M6` annotation subset and has not passed the Jakarta Persistence TCK. Compatibility with Hibernate ORM 8.0.0.Beta1 is evaluated operation by operation, not inferred from shared annotations.

## Candidate selection

Start with a flat aggregate that has:

- one table and one non-null assigned scalar identifier whose value is never reused within a tenant;
- explicit columns using accepted basic types;
- no relationship persistence, inheritance, converter, callback, or lazy field;
- explicit transaction ownership;
- a small, measurable query surface;
- synthetic integration fixtures that represent nullability and constraint edges.

If an aggregate does not fit the supported profile, leave it on the existing provider. Do not remove an annotation merely to make compilation succeed unless the corresponding behavior is intentionally replaced.

## Suggested migration sequence

1. Inventory entity annotations, provider annotations, repositories, JPQL/HQL, Criteria, `EntityManager` use, locks, callbacks, and transactional call sites.
2. Select one bounded aggregate and reduce its mapped-table constraints to Vev's verified primary-key, `NOT NULL`, and row-security profile.
3. Add Vev processing and treat every rejection as a compatibility decision.
4. Review generated metadata and the runtime's fixed SQL/schema contract in CI.
5. Run differential reads against disposable representative synthetic schemas; compare canonical values and row counts.
6. Observe shadow reads before changing the authoritative read path.
7. Move writes only after rollback, assigned-key, constraint, serialization, indeterminate-commit, and cleanup-failure semantics are verified.
8. Keep a reversible routing boundary until the new path has operational evidence.

Do not dual-write two persistence implementations inside one request unless atomicity and replay semantics are explicitly designed. Shadow writes should target disposable data or a replayable test environment.

## Semantic changes to expect

| Existing ORM behavior | Vev expectation |
|---|---|
| Managed identity and dirty checking | Explicit stateless reads and version-qualified writes |
| Lazy proxies | Explicit follow-up queries or application composition |
| Cascade and orphan removal | Explicit service operations; relationship and business constraints on mapped tables are not accepted yet |
| JPQL/HQL/Criteria | Generated point/batch operations and the bounded ID scan; a broader typed query compiler is future work |
| Spring Data repository proxy | Explicit agent integration; no current registrar promise |
| Lifecycle callbacks | Explicit application behavior |
| Provider tenant filters | An explicitly injected tenant authority, opaque per-tenant scopes, structurally generated tenant predicates, and forced database row security |
| Automatic schema management | External migrations followed by schema verification |
| Provider exception taxonomy | Vev/JDBC failure contract; audit caller expectations |

The deployment boundary changes too. A `PgVev` instance pins one verified PostgreSQL 18 TCP primary, database, role, server incarnation, model fingerprint, and single-use tenant authority. Do not put it behind multi-host discovery, a replica/failover router, a Unix socket, or PgBouncer transaction/statement pooling. Rebuild and re-verify the instance with a fresh generated authority after a migration, PostgreSQL restart, promotion, credential/role change, or endpoint change.

Vev never retries a write. Treat a commit exception as outcome-indeterminate and reconcile it using an application operation identifier before considering another attempt. Serialization retry must replay the complete lexical transaction and every surrounding effect must be independently idempotent; do not retry only the last SQL statement.

Before enabling delete or upsert, prove that an identifier is never reused. The current version counter restarts at zero for a new row and therefore cannot distinguish two incarnations with the same tenant/ID pair. A database sequence or UUID generator alone is not an attestation; the migration and domain process must make reuse impossible until Vev gains an explicit incarnation/identifier-registry contract.

## Rollback plan

A migration is reversible only if the old implementation can still read the schema and no Vev-only write changes have made that impossible. Define the rollback trigger, routing mechanism, data reconciliation method, and responsible operator before enabling writes.

Compatibility work should proceed aggregate by aggregate. An application-wide switch is not a safe experimental rollout unit.
