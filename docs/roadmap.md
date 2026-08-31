# Roadmap

> This roadmap expresses sequencing, not dates or release commitments.

## Phase 0 — closed foundation

- stabilize the generated entity-plan model and either implement the complete inherited `EntityAgent` contracts or replace the current facade with a Vev-specific interface;
- keep `vev-core` free of ORM, framework, and dialect dependencies;
- define deterministic generation and reproducible artifacts;
- establish positive, negative, and compiler-diagnostic fixtures;
- keep the native write surface at insert plus versioned update; require lifecycle retirement to be an update until an incarnation-safe deletion design exists;
- publish the safe profile and reject everything outside it, including implicit nullability;
- add dependency, license, and vulnerability review gates.

## Phase 1 — PostgreSQL scalar persistence

- complete typed scalar bind/read conformance on PostgreSQL 18;
- deepen assigned-ID insert, point/batch read, explicit update, generated scalar equality/null lookup, and bounded keyset-ordering coverage;
- benchmark and tune the guarded set-based `updateMultiple` statement across representative row widths and batch sizes without weakening duplicate rejection, input order, exact snapshot verification, or full rollback on stale/missing members;
- define transaction ownership and rollback-only behavior;
- benchmark pgjdbc prepare thresholds, binary transfer, connection-pool behavior, generated-index selectivity, and typed-array batch sizes before fixing performance defaults;
- add JFR/metrics events that never record bind values;
- rerun the current generated-index and write campaign on an isolated host with before/during/after CPU, I/O, and thermal telemetry, then add set-based insert and representative row-width/batch-size coverage without universal performance claims.

## Phase 2 — safety depth

- deepen and adversarially test the implemented structural tenant-isolation boundary;
- broaden version-token conformance while preserving mandatory optimistic locking for mutable entities;
- generate a deterministic canonical schema manifest and a build task that compares reviewed migrations with generated expectations; retain live startup attestation for database facts a compiler cannot prove;
- generate and attest explicit check/unique constraints and tenant-composite foreign keys before permitting them, rather than weakening the database to fit Vev;
- design and attest an incarnation token or append-only identifier registry before reconsidering physical delete or create-capable upsert;
- verify virtual-thread and structured-concurrency ownership;
- test pool state reset, cancellation, timeout, and failure recovery;
- fuzz processor inputs and generated SQL boundaries.

## Phase 3 — query and aggregate breadth

- extend the closed typed query compiler from scalar equality/null pages to typed projections and explicitly costed predicates without exposing raw SQL or a runtime AST escape hatch;
- replace tenant-relative `EntityKey` pagination with opaque model/entity/tenant/predicate-bound continuation cursors;
- evaluate embeddables and composite keys without weakening equality guarantees;
- define explicit aggregate write plans before accepting relationships or cascades;
- introduce converters only with deterministic AOT and JDBC contracts;
- add framework integration behind explicit opt-in modules.

## Phase 4 — compatibility evaluation

- track the final Jakarta Persistence 4 specification and replace milestone dependencies;
- assess the legal and technical requirements for Jakarta Persistence TCK participation;
- build a broader differential corpus against stable Hibernate ORM releases;
- document which Hibernate annotations can be compiled exactly and reject the rest;
- decide whether a provider-compatible facade is safe or whether Vev should remain an explicit persistence API.

Production readiness would require final Jakarta 4 and Hibernate 8 comparison baselines, stable dependencies, API compatibility policy, supported upgrade paths, security response ownership, release automation, failure-injection coverage, operational evidence, and independent review. None is implied by completion of an individual phase.
