# Roadmap

> This roadmap expresses sequencing, not dates or release commitments.

## Phase 0 — closed foundation

- stabilize the generated entity-plan model and selected `EntityAgent` adapter;
- keep `vev-core` free of ORM, framework, and dialect dependencies;
- define deterministic generation and reproducible artifacts;
- establish positive, negative, and compiler-diagnostic fixtures;
- publish the safe profile and reject everything outside it;
- add dependency, license, and vulnerability review gates.

## Phase 1 — PostgreSQL scalar persistence

- complete typed scalar bind/read conformance on PostgreSQL 18;
- cover assigned-ID insert, point read, explicit update, delete, count, and bounded ordering;
- define transaction ownership and rollback-only behavior;
- strengthen the migration-owned fingerprint and live-catalog contract without generating production DDL;
- add JFR/metrics events that never record bind values;
- run isolated, reproducible JMH workloads without performance claims.

## Phase 2 — safety depth

- deepen and adversarially test the implemented structural tenant-isolation boundary;
- broaden version-token conformance while preserving mandatory optimistic locking for mutable entities;
- design and attest an incarnation token or append-only identifier registry that eliminates delete/reinsert ABA before production use;
- verify virtual-thread and structured-concurrency ownership;
- test pool state reset, cancellation, timeout, and failure recovery;
- fuzz processor inputs and generated SQL boundaries.

## Phase 3 — query and aggregate breadth

- design a closed typed query compiler;
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

Production readiness would require stable dependencies, API compatibility policy, supported upgrade paths, security response ownership, release automation, failure-injection coverage, operational evidence, and independent review. None is implied by completion of an individual phase.
