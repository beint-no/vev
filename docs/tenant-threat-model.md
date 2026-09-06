# Tenant threat model

> **Security status: not certified for hostile multi-tenant production use.** This document defines required controls and test gates; it does not assert that the experimental implementation satisfies all of them.

## Protected property

For a tenant-owned discriminator-column mapping, a principal operating in tenant A must be unable to read, insert, update, or delete tenant B's rows, even when identifiers collide, caller input is malicious, application code is buggy, or a transaction changes execution context.

Availability attacks, a compromised application process, a malicious JDBC driver or `DataSource`, wire compromise, and database-superuser compromise are outside this boundary. Code running inside the application process can access its credentials and tenant-authority objects, so authorization before scope creation, database privileges, TLS/deployment isolation, and dependency integrity remain necessary.

## Threats

- a tenant-owned primary-key lookup omits the tenant predicate;
- sensitive or tenant-owned rows are incorrectly declared as globally shared reference data;
- a batch operation applies tenant filtering to reads but not writes;
- an entity carrying tenant B's key is saved while tenant A is active;
- a mutable thread-local tenant changes midway through a transaction;
- application code fabricates a tenant value or scope, crosses generated models, or reuses one authority across database runtimes;
- a task inherits a connection or tenant context across a concurrent boundary;
- arbitrary native SQL bypasses generated predicates;
- an undeclared, globally unique, malformed, partial, or expression index, exclusion constraint, or undeclared/malformed foreign key adds unmodeled cross-tenant semantics;
- connection pooling leaks session state such as `search_path`, role, or row-security variables;
- diagnostics expose tenant data or bound values.

## Required controls

Tenant isolation must be structural, not an optional query filter:

1. A scoped entity declares exactly one Vev `@TenantKey` whose Java type is part of the generated mapping.
2. Every generated model has a phantom marker `Model`; its entity types, keys, operations, authority, and scopes carry that marker so normal cross-model mixing does not compile.
3. Every `PgVev` exclusively reserves one generated `TenantAuthority<Model,T>`, verifies the model fingerprint and database endpoint, then permanently claims the authority. It can mint `TenantScope<Model,T>` only after that claim and cannot be reused by another runtime. An absent, erased, or foreign-claim scope fails before connection acquisition even when its key type and value match.
4. Every generated read and write shape contains the tenant predicate where row ownership matters.
5. Identity creation inputs omit tenant, ID, and version fields; the runtime binds the lexical tenant and verifies returned ownership. Assigned inserts validate that the immutable entity tenant equals the active tenant before binding either value; a different entity tenant fails before SQL.
6. The active tenant scope is immutable and pinned for the lexical transaction. A change poisons the transaction.
7. Batch and bulk operations retain the same invariant and remain bounded. Batch insert rejects duplicate entity keys before one typed-array statement; batch update rejects duplicates before its one guarded typed-array statement and rolls back the complete lexical transaction if one row is stale, missing, or returned unexpectedly.
8. Native SQL is excluded from the tenant-safe profile unless a separate compiler can prove equivalent constraints.
9. An accepted secondary index is generated from `@VevIndex`, is non-unique, and is live-attested with exact `(tenant, indexed value, id)` B-tree keys, declared `(tenant, indexed value, ordering value, id)` keys with verified direction, or `(tenant, id)` identifier keys. Declared `@UniqueConstraint` backing indexes enforce immediate distinct-null uniqueness with tenant-first ordinary-value keys or declared tenant-qualified identifier pairs and exact built-in operators. Undeclared or differently shaped indexes and ordinary inheritance are rejected. Declared checks require exact definitions and approved PostgreSQL 18 expression nodes, types, collations, operators, and functions; custom types are rejected before deparsing can invoke their output functions. See [check constraints](check-constraints.md). Foreign keys require generated `@VevReference` metadata, exact tenant-composite column/type/collation correspondence, a target inside the closed model, immediate non-cascading enforcement, and all built-in integrity triggers enabled.
10. A dedicated pgjdbc pool must establish an exact `pg_catalog`/UTF-8 baseline with `DateStyle = ISO, MDY` and `IntervalStyle = postgres` before checkout. Vev fails a changed baseline or retained `pg_temp` schema instead of mutating `search_path`, then independently installs and verifies the remaining transaction context and re-attests it immediately before commit. Avoiding path changes preserves pgjdbc prepared-query caching; pooled state is still verified, never assumed clean.
11. Logs, exceptions, and telemetry record operation categories and SQLSTATE where permitted, never SQL text, entity values, tenant values, or bound values.

An explicitly declared global `(id)` primary key enforces uniqueness across tenants. Collisions can reveal identifier existence, while generated predicates and forced RLS continue to prevent access to another tenant's row. References to tenant-owned targets remain tenant-qualified. The default `(tenant, id)` profile retains independently reusable identifiers across tenants.

Explicit identity sequences are shared within a mapped table. Their progression can reveal aggregate allocation activity across tenants, and rollback leaves gaps. This profile protects row access, not sequence-activity confidentiality. Applications needing opaque identifiers must use assigned IDs.

For tenant-owned mappings, the runtime requires PostgreSQL row-level security as defense in depth, including a forced, exact role-specific policy and least-privilege grants. The application role has `DELETE` only on explicitly opted-in, versioned generated-identity entities, without grant option. Other mappings forbid the privilege. Deletion uses the same lexical tenant predicates and RLS; assigned-ID and append-only deletion remain unavailable. Vev must not use RLS as an excuse to omit generated tenant predicates, and generated predicates do not replace those database controls.

Explicit [shared reference mappings](shared-reference-mappings.md) grant every model
tenant read visibility and require SELECT-only access, disabled/unforced RLS, and
zero policies. They never infer shared access from a missing tenant key. Shared
and tenant ownership capabilities are mutually exclusive, and shared rows cannot
reference tenant-owned rows. Tenant-to-shared references use scalar global keys;
tenant-to-tenant references retain their composite ownership boundary. Every FK
remains inside the closed model. Application review must establish that all
shared columns are intended for every tenant; Vev cannot infer business secrecy
from a schema. The same lexical scope, bounded operations, and failure rollback
apply, including when shared reads occur after tenant writes. A model containing
only shared records explicitly declares `@VevModel.tenantType`; each generated
shared plan exposes that same boxed scope type, captured and cross-checked at
model construction. A missing declaration, unsupported type, or mismatch with
mapped tenant ownership fails early. Scopes still require a successfully verified,
single-use authority; failed bootstrap does not claim it. Declaring a type never
authorizes an unauthenticated caller or grants global administrative access.

## Required adversarial tests

A tenant-capable release needs automated PostgreSQL tests for:

- colliding identifiers in two tenants;
- identical shared reference visibility in both tenants while scoped rows stay isolated, rejected mixed ownership/write capabilities, dormant shared policies, unexpected shared grants, and rollback after caught shared read or FK failures;
- ordered equality/null pagination in both directions with tied values, colliding tenant IDs, exact-token cursors, rejected malformed index directions, and rollback after a caught query or cleanup failure;
- shared-only Java/Kotlin models with explicit scope types, failed-bootstrap authority reuse, wrong-type and foreign-model/authority rejection before connection acquisition, readonly grants, and model-specific fingerprint drift;
- missing, null, wrong-type, changed, and foreign-authority tenant context;
- cross-tenant entity insertion and update;
- point, generated-index equality/null, range, count, existence, batch, and bulk operations, plus optimistic single/batch deletion and proof that undeclared deletion capabilities remain unavailable;
- continuation after a documented recoverable pre-SQL tenant rejection, and rollback after a poisoned tenant-context failure;
- transaction suspension/resumption and nested boundaries;
- virtual-thread and structured-concurrency context propagation;
- pool reuse after every supported failure path;
- duplicate single and batch writes within a tenant, equivalent unique values across tenants, distinct-null composite keys, and missing/global/deferred/altered unique constraints or replacement indexes;
- cross-tenant reference writes, missing/reordered/wrong reference columns, disabled integrity triggers, deferred/unvalidated constraints, and cascade/MATCH FULL mutations of declared tenant-composite foreign keys.

Tests must use synthetic tenants and synthetic records. Production-derived tenant identifiers or database dumps must never be committed to this repository.

## Reporting an isolation defect

Any plausible cross-tenant read or write is a security vulnerability. Do not publish a reproduction in an issue. Follow [the security policy](../SECURITY.md).
