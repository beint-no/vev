# AOT and schema pipeline

> **Status: experimental pipeline contract.** Database migrations remain the application's responsibility.

Vev moves mapping discovery, member access, and query parsing out of runtime execution. At startup the PostgreSQL runtime constructs a closed set of fixed statements from validated generated metadata; request data never supplies a statement shape. The intended pipeline has four gates.

## 1. Source analysis

The annotation processor reads source-level Jakarta Persistence metadata without loading entity classes. It rejects implicit names and access strategies, then resolves explicit record components, identifiers, column flags, Java types, and Vev-specific safety metadata into a closed intermediate model.

The model is valid only when every encountered Jakarta Persistence or Hibernate annotation and every accepted annotation attribute is either implemented or explicitly rejected. Other provider namespaces are not a compatibility surface and must not be assumed to affect generated behavior. Unresolved Java types fail compilation.

The processor intentionally does not advertise Gradle incremental annotation processing. A closed model and every entity it names must be presented as source in the same `javac` invocation so Vev can prove that accessors, canonical constructors, initializer blocks, and static state contain no hidden hydration behavior. Build tools must fully recompile the affected source set when a closed model is processed; accepting previously compiled entity bytecode would weaken this source-level proof and is rejected.

## 2. Deterministic generation

For each accepted entity, the processor emits deterministic source containing:

- an immutable per-entity plan and a closed model registry consumed by the runtime and Jakarta adapter;
- stable PostgreSQL identifiers and column roles from which the runtime constructs fixed quoted statements;
- immutable typed metadata consumed by Vev's closed built-in binders and row readers;
- a deterministic mapping fingerprint and table/tenant metadata used by runtime checks;
- no environment-specific values or credentials.

Generated plan and registry names use a Vev-specific `Vev` suffix instead of Jakarta's static-metamodel `_` suffix, so Vev and a Jakarta/Hibernate metamodel processor can coexist during a staged migration. The Vev processor claims only the `@VevModel` trigger annotation; it inspects the listed entity annotations transitively without taking ownership of them from other processors.

The processor does not emit a DDL manifest, migration, general query AST, or user-extensible SQL plan. The only current multi-row query factory is the PostgreSQL runtime's bounded `scanById`; arbitrary implementations of the public query interface are rejected at execution.

Generated output must be reproducible for identical sources, compiler options, and dependency versions. Timestamps, absolute paths, host names, and iteration-order accidents do not belong in generated artifacts.

## 3. Schema verification

Compilation proves source consistency, not database consistency. When `PgVev` is constructed, the current runtime reserves the generated model's single-use tenant authority and verifies:

- exactly PostgreSQL major 18 over one TCP primary endpoint; current CI verifies 18.4 only;
- an endpoint identity consisting of server address and port, PostgreSQL system identifier, postmaster start time, database name and OID, session user, current role, and recovery state;
- an application role that is neither superuser nor `BYPASSRLS`, is not a member of a role with either capability, and is not the mapped-table owner or a member of the owner role;
- `SELECT` access and no data-mutation privilege on `public.vev_schema_fingerprint`, plus exactly one matching migration-installed fingerprint row;
- an exact generated column set with built-in PostgreSQL types, exact `varchar` and `numeric` modifiers, nullability, deterministic collations, no defaults, no identity, no generated columns, and no missing-value catalog state;
- one exact immediate built-in B-tree `(tenant, id)` primary key, which bounds the implemented tenant/ID scan work;
- permanent logged nonpartitioned non-inherited built-in heap tables, with no rewrite rules, secondary indexes, or foreign keys touching a mapped table;
- schema `USAGE` without `CREATE`, table `SELECT`, exact column-level `INSERT`, exact mutable-value/version column-level `UPDATE` plus table `DELETE` only for versioned entities, and no effective `TRUNCATE`, `REFERENCES`, `TRIGGER`, or `MAINTAIN` privilege;
- no enabled user trigger; and
- enabled and forced RLS with exactly one permissive `FOR ALL` policy, restricted to the application role, whose `USING` and `WITH CHECK` expressions exactly compare the tenant column with `vev.tenant_id`.

Before any parse-sensitive expression is prepared, both bootstrap and runtime checkout execute a separate fully qualified statement that installs and reads back a transaction-local `pg_catalog`-only search path and rejects any retained temporary schema. This extra check is required because PostgreSQL implicitly searches `pg_temp` for relations and types even when it is absent from `search_path`; built-in casts are schema-qualified as defense in depth. Bootstrap then enters its read-only catalog boundary. Each runtime connection uses `SERIALIZABLE`, enables synchronous commit, installs transaction-local tenant and timeout state, and verifies the pinned endpoint identity, exact generated fingerprint, and session state both after configuration and immediately before commit. The single-use tenant authority is claimed only after bootstrap succeeds.

The verifier does not derive the fingerprint from the live catalog or prove business-level invariants. It rejects check constraints, secondary indexes, and every foreign key rather than pretending to understand their semantics.

Drift is fatal by default. A missing column or incompatible type must not trigger a reflective fallback, implicit DDL, or best-effort coercion.

Vev does not create or migrate production schemas. Use a dedicated migration tool and verify the post-migration schema before application traffic is accepted.

## 4. Runtime execution

Runtime receives already validated generated plans. Values are bound through typed JDBC operations; dynamic values are not interpolated into SQL. Identifier selection is generated, not supplied by request data.

Catalog verification runs once for each `PgVev` construction; the exact fingerprint, pinned endpoint identity, and transaction-local settings are checked on every checkout and immediately before commit. Reusing an instance across an out-of-band migration, PostgreSQL restart, failover, promotion, or pool reconfiguration is unsupported. A future cache must never turn a failed or stale verification into an accepted schema.

The `DataSource` is part of the trusted deployment boundary. It must resolve to one PostgreSQL 18 primary through a direct TCP connection. Unix-domain sockets, multi-host JDBC URLs, replicas, transparent failover, and PgBouncer transaction or statement pooling are outside this profile because they can change the attested backend underneath a `PgVev` instance.

## Recommended delivery gate

```text
compile mappings
      -> inspect generated-source diff
      -> build reproducibly
      -> apply migrations to the verified PostgreSQL release (currently 18.4)
      -> install the generated fingerprint and verify live catalog expectations
      -> run positive and negative integration suites
      -> publish or deploy
```

Any step that cannot run in CI should be documented as an unresolved release risk.
