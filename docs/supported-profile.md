# Supported profile and rejection matrix

> **Status: experimental.** The implementation and its compile-failure fixtures are authoritative. Anything not explicitly accepted must be treated as unsupported.

Vev interprets a safe, closed selection of Jakarta Persistence 4.0.0-M6 annotations as nonconforming source metadata. Jakarta Persistence 4 forbids records as entities, while Vev requires records, so an accepted Vev mapping is not a Jakarta entity and cannot simultaneously be managed by Hibernate or another Jakarta provider. Vev is not a Jakarta Persistence provider and has not passed the Jakarta Persistence TCK.

## Entity profile

| Area | Initial safe profile | Rejection policy |
|---|---|---|
| Entity shape | Top-level, public, non-generic immutable Java record hydrated through its canonical constructor | Reject mutable classes, abstract/nested/generic entities, field mutation, and reflective hydration |
| Table | One explicit PostgreSQL table; explicit lowercase table, schema, and column names | Reject implicit naming, catalogs, secondary tables, mixed-case or unsafe identifiers |
| Identifier | One non-null, stable, never-reused assigned scalar `@Id` using `Integer`, `Long`, `Short`, `String`, or `UUID`; string IDs have exact length 128 and deterministic PostgreSQL collation; explicit integer `IDENTITY` uses the separate creation capability | Reject other generators, composite or derived identity, unstable equality type, nullable ID, and nondeterministic collation |
| Basic columns | Explicit `@Column`, including an explicitly written `nullable = true` or `nullable = false`; eager bounded scalar values with exact generated JDBC metadata | Reject Jakarta-default nullability, lazy basics, implicit names, unbounded strings/numerics, and unrecognized Java/JDBC mappings |
| Mutable entity | Mandatory non-null `Integer`, `Long`, or `Short` `@Version`, initially zero, with explicit applied/missing/conflict outcomes | Reject mutation plans without a version token and negative or non-integral versions |
| Append-only entity | Explicit Vev `@AppendOnly`; generated plans expose insert/read but no update | Reject attempts to update through the safe API |
| Read-only entity | Explicit `@VevReadOnly`, optional stored identity/version metadata, SELECT-only grants | No mutation capabilities or write SQL; rejects combination with append-only/deletion and any write/sequence privilege |
| Shared reference entity | Explicit `@VevShared` plus `@VevReadOnly`; ID-only key; no tenant column; SELECT-only, intentionally visible to all model tenants | Reject implicit sharing, writes, mixed ownership, RLS policies, references to tenant-owned rows, and models with neither a tenant-owned mapping nor an explicit supported `@VevModel.tenantType` |
| Equality index | `@VevIndex` on an ordinary scalar value or the identifier; explicit schema-unique index name; at most 16 query indexes and unique constraints combined per entity; bounded generated equality pages and nullable-only `IS NULL` pages | Reject index annotations on tenant/version, unique or partial/expression/include indexes, wrong key order, excessive retained-key size, and undeclared secondary indexes |
| Ordered equality index | Explicit `@VevIndex.orderBy` naming a distinct non-null VALUE column; ASC or DESC for both ordering value and ID; typed value/ID cursor | Reject nullable or structural ordering columns, runtime expressions, mixed directions, and incompatible cursor types; see [ordered index queries](ordered-index-queries.md) |
| Enum columns | Explicit `@Enumerated(EnumType.STRING)` with bounded `varchar` and generated enum-typed index tokens | Reject implicit/ordinal mappings, `@EnumeratedValue`, unknown stored names, empty enums, over 1,024 constants, and names exceeding the column bound |
| Binary columns | Immutable `Binary` with explicit `@VevBinary` byte bound, named verified length check, and a compatible row limit | Reject raw arrays, buffers, blobs, implicit bounds, and binary structural keys; see [binary values](binary-values.md) |
| Text columns | String VALUE components with `@VevText`, explicit `@Column.length`, and a named verified character-length check | Reject implicit bounds, wrong units, structural keys, and arbitrary DDL fragments; see [text values](text-values.md) |
| Local time | `LocalTime` as PostgreSQL `time` with exact microseconds and typed indexes | Reject lossy nanoseconds, PostgreSQL `24:00:00`, narrower column precision, and legacy/offset time values; see [local time](local-time.md) |
| Column defaults | Explicit `@Column(options = "DEFAULT …")` on VALUE components; exact stored expressions and approved PostgreSQL 18 dependencies, including explicitly verified transaction-clock defaults | Reject structural-key defaults, drift, unsafe expressions, and omitted-field writes; explicit values including null/false remain explicit; see [column defaults](column-defaults.md) |
| Check constraints | Named `@Table.check` and `@Column.check` metadata, exact PostgreSQL 18 definitions, and bootstrap approval of every expression dependency | Reject unmodeled or altered checks, arbitrary options, unsupported expression nodes, and unapproved database behavior; see [check constraints](check-constraints.md) |
| Transient state | Not accepted in the first record profile | Every record component must be an explicitly mapped scalar column |
| Tenancy | Explicit Vev `@TenantKey`, an opaque model-typed `TenantScope<Model,T>` minted only after the generated single-use `TenantAuthority<Model,T>` is claimed by one verified `PgVev`, and generated structural tenant predicates | Reject cross-model, foreign-authority, reused-authority, missing, null, wrong-type, or entity/scope-mismatched tenant state before SQL |

For tenant-owned records, physical key shapes, required tenant traversal indexes, and references to alternate unique keys are described in [primary keys](primary-keys.md). Multiple `@Id` components and embedded identifiers remain unsupported.

Explicit integer `@GeneratedValue(strategy = IDENTITY)` uses generated creation inputs and verified PostgreSQL identity sequences. See [generated identifiers](generated-identifiers.md) for the API, exact schema and privilege contract, batch correlation, and sequence visibility limits.

The initial PostgreSQL codec surface is intentionally small: `boolean`/`Boolean`, `int`/`Integer`, `long`/`Long`, `short`/`Short`, `String`, `BigDecimal`, `LocalDate`, `LocalDateTime`, `LocalTime`, `Instant`, `UUID`, and bounded immutable `Binary`, plus enum names declared with `@Enumerated(EnumType.STRING)`. Runtime values must have the codec's exact boxed class; an enum constant with a class body is accepted only when its declaring enum matches the generated type. Default varchar String mappings require an exact `@Column(length=...)` from 1 through 65,535 code points; string IDs and tenant keys require 128. `@VevText` selects PostgreSQL text with an explicit code-point limit and a verified database length constraint, as specified in [text values](text-values.md). `BigDecimal` requires precision 1 through 128 and a scale from zero through that precision, and values must have exactly that scale. Date-bearing values use finite proleptic-ISO years 0001 through 9999; time-bearing values use exact microsecond precision. LocalTime excludes PostgreSQL end-of-day 24:00; PostgreSQL infinity sentinels and lossy nanoseconds are rejected. A mapping is accepted only after its processor and PostgreSQL conformance fixtures exist; other scalar, temporal, and enum representations are rejected until then. Enum names are resolved through an immutable map initialized from the generated `values()` call; there is no reflective lookup per row. Overridden `toString()` values and ordinal positions never affect persistence. Renaming, removing, or adding a constant changes the model fingerprint; reordering declarations does not. Unknown stored names poison the transaction. Enum initialization is application code and is part of the trusted mapping boundary.

Records may be supplied as source or as separately compiled dependencies on the
ordinary class path. Compiled record bytecode must pass the same pure snapshot
contract through exact constructor, accessor, and initialization checks; see the [compiler pipeline](aot-schema-pipeline.md).
[Kotlin record mappings](kotlin-records.md) preserve normal parameter null checks
and require exact Kotlin/database nullability. Named-module dependency records
remain unsupported. No application class is loaded during verification.

A closed model has at most 512 entities and an entity has at most 64 columns. Its lexical tenant type is inferred from tenant-owned records, or explicitly declared through `@VevModel.tenantType` for a model containing only shared records. Explicit declarations must match all mapped tenant keys; integral primitives normalize to boxed types. This supplies no additional row or mutation authority. The compiler and runtime enforce both limits without trusting user-supplied collection sizes. The generated maximum row shape multiplied by the declared row limit plus one paging sentinel must fit a 64 MiB materialized-result budget. The default limit is 1,000; [@VevRows](row-limits.md) can declare a smaller batch/page limit for larger bounded snapshots. Strings reject U+0000 and malformed UTF-16 before JDBC binding. Numeric and temporal limits, tenant equality, assigned-ID presence, initial version zero, and version overflow are checked before SQL.

Identifier non-reuse remains an application/schema invariant for privileged out-of-band changes. Within Vev, assigned-ID physical deletion and create-capable upsert remain unavailable. Opt-in `@VevDelete` accepts only versioned generated identities with noncycling increasing owned sequences and no assigned-insert capability. The role has exactly the declared deletion grants and cannot reset those sequences. See [physical deletion](physical-deletion.md).

`@VevReference(tenantFirst = false)` explicitly selects `(reference, tenant)` to `(ID, tenant)` column order. The default is tenant first. Both orders preserve tenant equality, but the live constraint must match the declared order exactly; the generated manifest and fingerprint record that choice. This permits existing identifier-first tenant-composite foreign keys without rebuilding them merely to reorder their columns.

The initial live-schema profile is equally closed. Every mapped relation must be a permanent, logged, nonpartitioned, non-inherited built-in heap table with no rewrite rules or enabled user triggers. It has the exact declared immediate built-in B-tree primary key (`(tenant, id)` by default) plus exactly the generated `@VevIndex` and named unique-constraint sets. For tenant-owned tables, each query index must be non-unique, immediate, built-in B-tree, have keys exactly `(tenant, indexed value, id)` or `(tenant, id)` for an identifier index with default ascending/null ordering, or `(tenant, indexed value, ordering value, id)` with the exact declared tail direction, and expected collations/operator classes, and have no predicate, expression, included column, constraint ownership, custom option, or custom tablespace. Undeclared check constraints and unique indexes remain rejected. Named tenant-qualified identifier pairs may use either column order; other business unique constraints remain tenant first. Explicit tenant-scoped `@UniqueConstraint` declarations require immediate, validated/enforced uniqueness with PostgreSQL `NULLS DISTINCT` semantics and exact built-in B-tree columns, collations, and default operator classes. `@VevReference` declares a scalar reference within the same closed model: its Java type and bounds must exactly match the target identifier. For tenant-owned targets, the runtime verifies the exact composite `(tenant, reference)` to `(target tenant, target id)` foreign key, or its explicitly declared identifier-first order, including built-in equality operators and four enabled integrity triggers. Only immediate, validated, enforced MATCH SIMPLE and NO ACTION semantics are accepted. Undeclared outgoing and within-model incoming references remain rejected. Read-only targets may explicitly place external incoming references outside model attestation through [`@VevReadOnly(externalIncomingReferences = true)`](read-only-mappings.md#external-incoming-references); complete closure remains the default and is mandatory for writable targets. Explicit [shared reference mappings](shared-reference-mappings.md) use global ID-only primary keys, `(value, id)` or `(value, ordering value, id)` query indexes, one or more VALUE columns for unique constraints, and scalar foreign keys when they are reference targets. Shared rows cannot reference tenant-owned rows; all other constraint enforcement and bounds remain.

## Explicit scalar references

A reference stays an identifier in the immutable snapshot. For example, a nullable
reference to an `Account` with a `UUID` identifier is:

```java
@VevReference(name = "work_item_account_fk", target = Account.class)
@Column(name = "account_id", nullable = true) UUID accountId
```

Both records must belong to the same `@VevModel`. The reviewed migration must
install the exact reference shown in the generated schema manifest, for example:

```sql
CONSTRAINT work_item_account_fk
    FOREIGN KEY (tenant_id, account_id)
    REFERENCES ledger.account (tenant_id, id)
    MATCH SIMPLE ON UPDATE NO ACTION ON DELETE NO ACTION NOT DEFERRABLE
```

A null scalar leaves the reference absent. A non-null scalar must identify a row
in the active tenant, including when another tenant has the same identifier.
Referential-integrity failures roll back the complete lexical transaction.
Self-references use the same contract. Vev performs no implicit fetch, cascade, or
insert reordering; callers insert referenced rows before their dependants, or use
one atomic batch for a self-referencing set.

## Tenant-scoped uniqueness

Vev interprets Jakarta's [`@UniqueConstraint`](https://jakarta.ee/specifications/persistence/4.0/apidocs/jakarta.persistence/jakarta/persistence/uniqueconstraint)
as an explicit migration contract:

```java
@Table(name = "user_handle", schema = "accounts", uniqueConstraints =
        @UniqueConstraint(name = "user_handle_tenant_handle_key",
                          columnNames = {"tenant_id", "handle"}))
```

The name is mandatory. `columnNames` must list distinct mapped database column
names, starting with the tenant column and followed by ordinary scalar values.
The primary identifier and version are not unique business-key components. Each
constraint has at most 32 columns and a conservative 1,536-byte maximum key size;
an entity has at most 16 query indexes and unique constraints combined.

The migration installs `CONSTRAINT user_handle_tenant_handle_key UNIQUE
(tenant_id, handle)`. Vev verifies the exact named constraint and backing B-tree,
column order, built-in default operator classes, matching collations, and immediate
enforcement. This uses PostgreSQL's [default distinct-null semantics](https://www.postgresql.org/docs/18/ddl-constraints.html#DDL-CONSTRAINTS-UNIQUE-CONSTRAINTS):
if any value component is null, multiple such rows are permitted. Otherwise the
same tuple may occur once per tenant. A violation poisons and rolls back the whole
lexical transaction, including earlier writes and other batch members.

Global uniqueness, `NULLS NOT DISTINCT`, deferred or partial constraints, included
columns, standalone unique indexes, `@Column(unique = true)`, and SQL fragments in
`@UniqueConstraint.options` are rejected. A unique declaration adds database
enforcement; it does not generate a query token or imply an application precheck.
Unique keys cannot be swapped through temporary duplicate states in an immediate
constraint. Applications must design such transitions explicitly.

## Deliberately rejected mappings

| Mapping or behavior | Initial status | Reason |
|---|---|---|
| `@OneToOne`, `@OneToMany`, `@ManyToOne`, `@ManyToMany` | Rejected | No implicit relationship loading or graph persistence |
| Cascades and orphan removal | Rejected | Database effects must remain explicit |
| `@Embedded`, `@EmbeddedId`, `@IdClass` | Rejected | Composite flattening and key equality are not yet specified |
| Inheritance and mapped superclasses | Rejected | SQL shape and discriminator semantics are not implemented |
| Secondary tables | Rejected | One generated entity plan maps one primary table in the initial profile |
| Mutable entity without `@Version` | Rejected | Silent last-write-wins mutation is outside the safe profile |
| Attribute converters | Rejected | Converter lifecycle and JDBC type selection need an AOT contract |
| Lifecycle callbacks and entity listeners | Rejected | Stateless agents do not emulate session lifecycle timing |
| Lazy basic fields | Rejected | Vev does not generate entity proxies |
| Provider formulas, generated timestamps, custom types | Rejected | Provider-specific behavior cannot be approximated safely |
| `@VevDelete` physical deletion | Explicit versioned generated identities only | Typed expected versions and outcomes, one-statement atomic batches, exact DELETE grants, no identifier reuse through Vev |
| Assigned-ID / append-only deletion and create-capable upsert | Absent | Assigned ID reuse can reintroduce version-zero ABA; append-only history must remain append-only |
| Undeclared checks and unique indexes | Rejected | Checks require exact named metadata and approved builtin expression dependencies; unique constraints require explicit scoped or shared declarations |
| Foreign keys | Explicit scalar `@VevReference` within the closed model; read-only opt-in for external incoming sources | Declared references require exact scoped-composite or shared-scalar keys, matching types/bounds, no cascade/deferral, and verified built-in enforcement; opted-in external incoming constraints remain outside attestation |
| Native SQL in entity/repository metadata | Rejected in the safe profile | Arbitrary SQL cannot be proven to preserve mapping and tenant invariants |

## Query and repository profile

The native surface is implemented for generated point reads, ordered bounded batch reads, insert, versioned update, append-only insert/read, and ID-ordered traversal through `PgQueries.scanById` and `PgQueries.scanByIdAfter`. A generated `@VevIndex` token adds `PgQueries.equal`/`equalAfter`; only nullable tokens type-check with `isNull`/`isNullAfter`. Explicit [ordered indexes](ordered-index-queries.md) use distinct required/nullable tokens, a non-null VALUE ordering column, and a fixed ASC or DESC value/ID order. Each page requires a `QueryLimit` from 1 through the entity’s generated `maximumRows()` (at most 1,000), fetches at most one sentinel row beyond that limit, materializes detached results, and reports `hasMore`. ID-ordered continuation uses a generated `EntityKey<Model,Entity,Key>`; explicit ordered indexes require a `PgOrderedCursor` holding both the ordering value and entity key and bound to that exact token. Both use a second fixed SQL shape. The cursor is relative to the active lexical tenant rather than tenant-bound; PostgreSQL predicates and RLS preserve isolation, but Vev cannot detect accidental reuse under another tenant. Pages share a consistent snapshot only when executed in one lexical transaction. The runtime accepts only its internal query representations; implementing `BoundedQuery` does not grant arbitrary SQL. There is no public predicate AST, runtime DSL, projection, join, `OFFSET`, or unbounded query path.

`insert` and `insertMultiple` require the generated `AssignedEntityType` capability. A plain `EntityType` or versioned-update capability cannot authorize an assigned-ID insertion. Generated plans for the current assigned-ID profile expose this capability explicitly.

`insertMultiple` validates the complete bounded input and duplicate entity keys before SQL, binds one typed PostgreSQL array per column, expands them with ordinality in one statement, and verifies each returned snapshot in input order. `updateMultiple` also uses one fixed typed-array/ordinality statement. Its materialized preflight must match every tenant, identifier, and expected version before any row is updated; it then returns only ordered `Applied` outcomes whose non-version values exactly match the request and whose versions advance by one. A duplicate is rejected before SQL, while a stale, missing, or unexpectedly returned member poisons and rolls back the complete lexical transaction.

The Jakarta adapter is a deliberately nonconforming `EntityAgent`-shaped facade. It currently supports detached `find`/`get` and their ordered multiple variants, selected cache-mode options as no-cache semantics, `fetch()` as a no-op for already-loaded supported values, assigned-value insert, and homogeneous insert batches. Insert is accepted only because the assigned-ID capability requires every identifier and VALUE component explicitly and the schema profile forbids executable database hooks; every returned immutable snapshot must exactly equal its input. The facade does not yet honor every inherited option, property, lifecycle, or exception contract and must not be treated as an implementation supplied by a Jakarta provider. Immutable Jakarta `void` update and refresh operations are rejected before SQL; use the typed native API that returns the new snapshot. Create-capable upsert remains absent. Facade delete methods also reject; native deletion requires the typed capability and explicit versioned outcome. The following Jakarta or framework surfaces are outside the safe profile unless a later release explicitly lists them:

- `EntityManager` and persistence-unit bootstrapping;
- JPQL, HQL, Criteria, named queries, stored procedures, and entity graphs;
- Spring Data repositories, derived queries, query by example, and lazy references;
- lock modes and pessimistic-lock timeout semantics;
- second-level/query caches and session-level identity;
- transparent dirty checking, automatic flush, merge, and in-place refresh of immutable records.

## Diagnostic standard

A rejection should identify the entity member, unsupported feature, and safe next action. Falling back to reflection, accepting an annotation while ignoring a semantic attribute, or delaying a known incompatibility until the first production query is a defect.

This fail-early rule has an important boundary: source and generated-query errors should fail compilation, while facts about a live PostgreSQL catalog can only be proved at `PgVev` startup. The processor emits a versioned [schema manifest](schema-manifest.md) for migration review and tooling. Live catalog verification remains mandatory; the manifest alone does not validate a migration.

The exported PostgreSQL plan SPIs are a linker surface for generated application code, not a supported handwritten extension point. Only unmodified annotation-processor output is inside the generated-plan safety profile; custom executable plan behavior is fully trusted by the runtime.

Compile-failure fixtures are part of the compatibility contract. Every newly accepted annotation attribute needs positive generation coverage, PostgreSQL integration coverage, and at least one nearby negative case.
