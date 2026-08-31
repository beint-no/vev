# AOT and schema pipeline

> **Status: experimental pipeline contract.** Database migrations remain the application's responsibility.

Vev moves mapping discovery, member access, and supported query construction out of runtime execution. At startup the PostgreSQL runtime constructs a closed set of fixed statements from validated generated metadata; request data never supplies a statement shape. The intended pipeline has four gates.

## 1. Source analysis

The annotation processor reads selected source-level Jakarta Persistence annotations without loading mapped classes. Jakarta Persistence 4 forbids records as entities, so Vev deliberately interprets those annotations on its required immutable records as nonconforming source metadata. It rejects implicit names and access strategies, requires every `@Column` to explicitly state `nullable = true` or `nullable = false`, then resolves record components, identifiers, column flags, Java types, and Vev-specific safety metadata into a closed intermediate model. Jakarta's default nullability is never silently inherited.

`@VevIndex(name = "...")` is accepted only on an ordinary scalar component. The processor validates the explicit PostgreSQL identifier, component and codec type, nullable/required distinction, per-entity index-count bound, generated-token name, schema-wide name uniqueness, and a conservative retained-key budget. It does not infer uniqueness or accept an index on an identifier, tenant key, or version token.

The model is valid only when every encountered Jakarta Persistence or Hibernate annotation and every accepted annotation attribute is either implemented or explicitly rejected. Other provider namespaces are not a compatibility surface and must not be assumed to affect generated behavior. Unresolved Java types fail compilation.

The processor intentionally does not advertise Gradle incremental annotation processing. A closed model and every entity it names must be presented as source in the same `javac` invocation so Vev can prove that accessors, canonical constructors, initializer blocks, and static state contain no hidden hydration behavior. Build tools must fully recompile the affected source set when a closed model is processed; accepting previously compiled entity bytecode would weaken this source-level proof and is rejected.

## 2. Deterministic generation

For each accepted entity, the processor emits deterministic source containing:

- an immutable per-entity plan and a closed model registry consumed by the runtime and Jakarta adapter;
- stable PostgreSQL identifiers and column roles from which the runtime constructs fixed quoted statements;
- immutable typed metadata consumed by Vev's closed built-in binders and row readers;
- identity-stable typed query tokens for each generated scalar equality index, with an `IS NULL` seam only for nullable tokens;
- a deterministic mapping fingerprint, including declared index identities, and table/tenant metadata used by runtime checks;
- no environment-specific values or credentials.

Generated plan and registry names use a Vev-specific `Vev` suffix instead of Jakarta's static-metamodel `_` suffix. Vev and a Jakarta/Hibernate metamodel processor may coexist in one build only for distinct source types; a Vev record cannot simultaneously be a Jakarta/Hibernate entity. The Vev processor claims only the `@VevModel` trigger annotation; it inspects the listed mapping annotations transitively without taking ownership of them from other processors.

The processor does not emit a DDL manifest, migration, general query AST, or user-extensible SQL plan. Current multi-row queries are the PostgreSQL runtime's bounded ID scan and the generated-index equality/nullable families. Each supports a typed exclusive-key continuation. There is no arbitrary predicate, runtime DSL, `OFFSET`, projection, join, or unbounded query path; arbitrary implementations of the public query interface are rejected at execution.

Generated output must be reproducible for identical sources, compiler options, and dependency versions. Timestamps, absolute paths, host names, and iteration-order accidents do not belong in generated artifacts.

## 3. Schema verification

Compilation proves source consistency, not database consistency. In particular, it cannot inspect a deployed database. When `PgVev` is constructed, the current runtime reserves the generated model's single-use tenant authority and performs live startup verification of:

- exactly PostgreSQL major 18 over one TCP primary endpoint; current CI verifies 18.6;
- an endpoint identity consisting of server address and port, PostgreSQL system identifier, postmaster start time, database name and OID, session user, current role, and recovery state;
- an application role that is neither superuser nor `BYPASSRLS`, is not a member of a role with either capability, and is not the mapped-table owner or a member of the owner role;
- `SELECT` access and no data-mutation privilege on `public.vev_schema_fingerprint`, plus exactly one matching migration-installed fingerprint row;
- an exact generated column set with built-in PostgreSQL types, exact `varchar` and `numeric` modifiers, nullability, deterministic collations whose recorded provider version is current, no defaults, no identity, no generated columns, and no missing-value catalog state; the database-default collation is attested through `pg_database`, while named collations are attested through `pg_collation`;
- one exact immediate built-in B-tree `(tenant, id)` primary key, which bounds the implemented tenant/ID scan work;
- the exact generated set of non-unique, immediate, built-in B-tree secondary indexes, each with no predicate, expression, included column, constraint ownership, custom option, or non-default ordering and with keys exactly `(tenant, indexed value, id)` under the expected collation and built-in default operator classes;
- permanent logged nonpartitioned non-inherited built-in heap tables, with no rewrite rules, foreign keys, check constraints, unique secondary indexes, or undeclared indexes touching a mapped table;
- schema `USAGE` without `CREATE`, table `SELECT`, exact column-level `INSERT`, exact mutable-value/version column-level `UPDATE`, no table `DELETE`, and no effective `TRUNCATE`, `REFERENCES`, `TRIGGER`, or `MAINTAIN` privilege;
- no enabled user trigger; and
- enabled and forced RLS with exactly one permissive `FOR ALL` policy, restricted to the application role, whose `USING` and `WITH CHECK` expressions exactly compare the tenant column with `vev.tenant_id`.

Vev requires a dedicated pgjdbc `DataSource` whose connections already report an exact `pg_catalog` search path, UTF-8 client and server encodings, standard-conforming strings, and integer datetimes. Bootstrap and every runtime checkout fail if that trusted session baseline differs or if PostgreSQL reports a retained temporary schema. Vev deliberately does not toggle `search_path` per transaction: PostgreSQL reports that setting to pgjdbc, whose prepared-query cache is invalidated when it changes. Built-in casts remain schema-qualified as defense in depth. Bootstrap then enters its read-only catalog boundary. Each runtime connection uses `SERIALIZABLE`, enables synchronous commit, installs transaction-local tenant and timeout state, and verifies the pinned endpoint identity, exact generated fingerprint, and session state both after configuration and immediately before commit. The single-use tenant authority is claimed only after bootstrap succeeds.

The verifier does not derive the fingerprint from the live catalog or prove business-level invariants. It accepts only the exact generated non-unique equality indexes and rejects check constraints, unique indexes, and every foreign key rather than pretending to understand their semantics.

Drift is fatal by default. A missing column or incompatible type must not trigger a reflective fallback, implicit DDL, or best-effort coercion.

Vev does not create or migrate production schemas. Use a dedicated migration tool and verify the post-migration schema before application traffic is accepted.

## 4. Runtime execution

Runtime receives already validated generated plans. Values are bound through typed JDBC operations; dynamic values are not interpolated into SQL. Identifier selection is generated, not supplied by request data. `insertMultiple` and `updateMultiple` bind one PostgreSQL array per mapped column and one expected cardinality to a single fixed statement each. `ROWS FROM (unnest(...)) WITH ORDINALITY` preserves input position, duplicate entity keys are rejected before SQL, and every `RETURNING` snapshot is checked column-for-column against its input before commit is possible. The update statement materializes a complete tenant/identifier/version match before its data-modifying CTE may change any row.

Catalog verification runs once for each `PgVev` construction; the exact fingerprint, pinned endpoint identity, and transaction-local settings are checked on every checkout and immediately before commit. Reusing an instance across an out-of-band migration, PostgreSQL restart, failover, promotion, or pool reconfiguration is unsupported. A future cache must never turn a failed or stale verification into an accepted schema.

The `DataSource` is part of the trusted deployment boundary. It must be dedicated to Vev, use pgjdbc, establish the required immutable session baseline before checkout, and resolve to one PostgreSQL 18 primary through a direct TCP connection. Unix-domain sockets, multi-host JDBC URLs, replicas, transparent failover, and PgBouncer transaction or statement pooling are outside this profile because they can change the attested backend underneath a `PgVev` instance.

## Recommended delivery gate

```text
compile mappings
      -> inspect generated-source diff
      -> build reproducibly
      -> apply migrations to the verified PostgreSQL release (currently 18.6)
      -> install the generated fingerprint and verify live catalog expectations
      -> run positive and negative integration suites
      -> publish or deploy
```

Any step that cannot run in CI should be documented as an unresolved release risk.
