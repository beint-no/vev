# Limitations

Vev is experimental, unpublished, and not production-ready.

## Platform

- JDK 26 is the minimum and only supported Java baseline.
- Exactly PostgreSQL major 18 is accepted. Earlier and later majors fail verification; the current fixture is 18.4.
- Jakarta Persistence `4.0.0-M6` is a preview dependency and may change incompatibly.
- Vev is not Jakarta Persistence TCK compliant and is not a complete persistence provider.
- Jakarta Persistence 4 forbids records as entities. Vev requires immutable records and reuses selected Jakarta annotations only as nonconforming source metadata; a Vev record cannot simultaneously be managed by Hibernate or another Jakarta provider.
- Public APIs and generated source names may change without deprecation during the experiment.

## Persistence semantics

- The Jakarta-facing experiment is a deliberately nonconforming `EntityAgent`-shaped facade with no persistence context. It implements only selected operations and does not yet honor every inherited option, property, lifecycle, or exception contract; it must not be treated as a Jakarta provider implementation. Application-managed facades are not thread-safe.
- There is no persistence-context identity guarantee or transparent dirty checking.
- Lazy entity proxies, relationship persistence, cascades, orphan removal, inheritance, embeddables, composite keys, converters, callbacks, and second-level cache are outside the initial safe profile.
- `EntityManager`, JPQL, HQL, Criteria, named queries, entity graphs, and provider session APIs are not implemented.
- Only explicitly documented `EntityAgent`-shaped operations are in scope; Vev does not currently supply a complete `EntityManagerFactory` or Jakarta provider lifecycle.
- For the immutable record profile, the `EntityAgent`-shaped facade supports reads, assigned-value insert, homogeneous insert batches, and single version-qualified delete. Insert requires an exact returned-snapshot match. Its `void` update, upsert, and refresh methods reject before SQL because they cannot synchronize replacement state in place. Non-empty Jakarta batch delete also rejects before SQL to avoid hidden ordered partial effects; native typed mutations return replacement snapshots and exhaustive batch outcomes.
- Spring Data repository compatibility and repository auto-registration are not promised.
- Native entities are immutable records. Every non-append-only record requires generated optimistic versioning; unversioned updates and deletes are outside the safe profile.
- Assigned identifiers must never be reused within a tenant. The runtime does not yet attest this invariant; delete followed by reuse can cause optimistic-lock ABA, and a version-zero upsert can recreate a missing identity. Domains that permit reuse must not use current delete/upsert support.
- Pessimistic locking and provider lock modes are unsupported.
- Native SQL is not part of the tenant-safe generated profile.
- `PgEntityPlan` and `PgVersionedEntityPlan` are exported only so generated application code can link to the runtime. Handwritten, transformed, proxied, or substituted implementations are fully trusted and outside the generated-plan safety profile; structural `PgModel` validation cannot attest their executable methods.
- Each `PgVev` consumes one generated `TenantAuthority<Model,T>` after database verification. It cannot be shared with or reused by another runtime; scopes minted by a foreign claim are incompatible even for the same tenant-key type and value.
- The only implemented multi-row query is a mandatory-bounded, ID-ordered scan created by `PgQueries.scanById`. `PgQueries.scanByIdAfter` continues after a generated type-bound entity key. The key is relative to the active lexical tenant rather than carrying tenant authority, so reusing it under another tenant safely scans that other tenant but is a caller logic error Vev cannot yet detect. All pages must execute inside one lexical transaction to share one PostgreSQL snapshot; separate transactions may observe intervening writes. Forged query-interface implementations, arbitrary predicates, projections, joins, offsets, and unbounded reads are rejected or unavailable.
- A closed model is limited to 128 entities, each entity to 64 columns, one batch or bounded scan result to 1,000 application values, and one materialized result shape to 64 MiB.

## Operations

- Vev does not generate or apply production migrations.
- Current bootstrap verifies the exact PostgreSQL 18 TCP primary/database/role identity inside a trusted catalog boundary; fingerprint-table read-only access without trigger, maintenance, or grant-option privileges; no executable fingerprint-table hooks or incoming foreign keys; the migration-installed mapping fingerprint; permanent logged nonpartitioned non-inherited built-in heap tables; exact columns/type modifiers/nullability/collations without defaults, identity columns, generated values, or missing-value state; one exact immediate built-in B-tree `(tenant, id)` primary key; exact column-level mutation grants; schema `USAGE` without `CREATE`; absence of check constraints, secondary indexes, foreign keys, rewrite rules, and enabled user triggers; and one exact role-specific forced-RLS policy. It does not recompute the fingerprint from the catalog or prove business-level invariants.
- A trusted `DataSource` must target one primary through direct TCP. Unix sockets, multi-host URLs, replicas, transparent failover, and PgBouncer transaction/statement pooling are unsupported. A database restart, promotion, migration, or endpoint-identity change requires constructing and verifying a new `PgVev` with a new authority.
- Every transaction first replaces pooled `search_path` state with a read-back `pg_catalog`-only path and rejects any retained temporary schema, then uses `SERIALIZABLE`, synchronous commit, UTC, bounded lock/statement/transaction/network timeouts, an exact fingerprint check, and pre-commit state attestation. These safety checks are deliberate overhead and cannot be disabled in the safe profile.
- Vev performs no implicit retry. A commit exception has an indeterminate outcome and must not be retried automatically. A serialization failure may be retried only by replaying the complete lexical operation under an application-owned idempotency policy.
- Multi-tenancy is not certified for hostile production use.
- Distributed transactions, JTA, savepoint recovery, and cross-database transactions are not supported unless a future release says otherwise.
- Vev's lexical transaction and failure semantics differ from the milestone `EntityAgent` exception-recovery rules; this is one reason the current facade is deliberately nonconforming and Vev does not claim TCK compliance.
- There is no long-term support policy, compatibility window, or production incident response SLA.

## Evidence

- Integration tests use synthetic fixtures and cover only documented shapes.
- Benchmarks cover selected workloads and cannot establish universal superiority.
- No benchmark result is a capacity plan or production sizing recommendation.
- Passing this repository's tests is not equivalent to passing the Jakarta Persistence TCK.

Use the project to evaluate architecture and contribute narrowly scoped experiments. Do not use it to hold irreplaceable production data.
