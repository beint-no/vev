# Tenant threat model

> **Security status: not certified for hostile multi-tenant production use.** This document defines required controls and test gates; it does not assert that the experimental implementation satisfies all of them.

## Protected property

For a discriminator-column deployment, a principal operating in tenant A must be unable to read, insert, update, or delete tenant B's rows, even when identifiers collide, caller input is malicious, application code is buggy, or a transaction changes execution context.

Availability attacks, a compromised application process, a malicious JDBC driver or `DataSource`, wire compromise, and database-superuser compromise are outside this boundary. Code running inside the application process can access its credentials and tenant-authority objects, so authorization before scope creation, database privileges, TLS/deployment isolation, and dependency integrity remain necessary.

## Threats

- a primary-key lookup omits the tenant predicate;
- a batch operation applies tenant filtering to reads but not writes;
- an entity carrying tenant B's key is saved while tenant A is active;
- a mutable thread-local tenant changes midway through a transaction;
- application code fabricates a tenant value or scope, crosses generated models, or reuses one authority across database runtimes;
- a task inherits a connection or tenant context across a concurrent boundary;
- arbitrary native SQL bypasses generated predicates;
- a secondary uniqueness rule, exclusion constraint, or foreign key adds unmodeled cross-tenant semantics;
- connection pooling leaks session state such as `search_path`, role, or row-security variables;
- diagnostics expose tenant data or bound values.

## Required controls

Tenant isolation must be structural, not an optional query filter:

1. A scoped entity declares exactly one Vev `@TenantKey` whose Java type is part of the generated mapping.
2. Every generated model has a phantom marker `Model`; its entity types, keys, operations, authority, and scopes carry that marker so normal cross-model mixing does not compile.
3. Every `PgVev` exclusively reserves one generated `TenantAuthority<Model,T>`, verifies the model fingerprint and database endpoint, then permanently claims the authority. It can mint `TenantScope<Model,T>` only after that claim and cannot be reused by another runtime. An absent, erased, or foreign-claim scope fails before connection acquisition even when its key type and value match.
4. Every generated read and write shape contains the tenant predicate where row ownership matters.
5. Inserts validate that the immutable entity tenant equals the active tenant before binding either value; a different entity tenant fails before SQL.
6. The active tenant scope is immutable and pinned for the lexical transaction. A change poisons the transaction.
7. Batch and bulk operations retain the same invariant and remain bounded.
8. Native SQL is excluded from the tenant-safe profile unless a separate compiler can prove equivalent constraints.
9. Ordinary inheritance, secondary indexes, and foreign keys touching mapped tables are rejected until their full tenant and execution semantics are generated and attested.
10. Every connection first installs and reads back a transaction-local `pg_catalog`-only path and rejects a retained `pg_temp` schema using a fully qualified parse-safe statement, then independently installs and verifies the remaining context and re-attests it immediately before commit. Pool state is never trusted as already clean.
11. Logs, exceptions, and telemetry record operation categories and SQLSTATE where permitted, never SQL text, entity values, tenant values, or bound values.

The current runtime requires PostgreSQL row-level security as defense in depth, including a forced, exact role-specific policy and least-privilege grants. Vev must not use RLS as an excuse to omit generated tenant predicates, and generated predicates do not replace those database controls.

## Required adversarial tests

A tenant-capable release needs automated PostgreSQL tests for:

- colliding identifiers in two tenants;
- missing, null, wrong-type, changed, and foreign-authority tenant context;
- cross-tenant entity insertion and update;
- point, range, count, existence, batch, bulk, and delete operations;
- continuation after a documented recoverable pre-SQL tenant rejection, and rollback after a poisoned tenant-context failure;
- transaction suspension/resumption and nested boundaries;
- virtual-thread and structured-concurrency context propagation;
- pool reuse after every supported failure path;
- generated and attested composite tenant foreign-key enforcement before any foreign key is accepted.

Tests must use synthetic tenants and synthetic records. Production-derived tenant identifiers or database dumps must never be committed to this repository.

## Reporting an isolation defect

Any plausible cross-tenant read or write is a security vulnerability. Do not publish a reproduction in an issue. Follow [the security policy](../SECURITY.md).
