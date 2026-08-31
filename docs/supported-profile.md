# Supported profile and rejection matrix

> **Status: experimental.** The implementation and its compile-failure fixtures are authoritative. Anything not explicitly accepted must be treated as unsupported.

Vev interprets a safe, closed selection of Jakarta Persistence 4.0.0-M6 annotations as nonconforming source metadata. Jakarta Persistence 4 forbids records as entities, while Vev requires records, so an accepted Vev mapping is not a Jakarta entity and cannot simultaneously be managed by Hibernate or another Jakarta provider. Vev is not a Jakarta Persistence provider and has not passed the Jakarta Persistence TCK.

## Entity profile

| Area | Initial safe profile | Rejection policy |
|---|---|---|
| Entity shape | Top-level, public, non-generic immutable Java record hydrated through its canonical constructor | Reject mutable classes, abstract/nested/generic entities, field mutation, and reflective hydration |
| Table | One explicit PostgreSQL table; explicit lowercase table, schema, and column names | Reject implicit naming, catalogs, secondary tables, mixed-case or unsafe identifiers |
| Identifier | One non-null, stable, never-reused assigned scalar `@Id` using `Integer`, `Long`, `Short`, `String`, or `UUID`; string IDs have exact length 128 and deterministic PostgreSQL collation | Reject every `@GeneratedValue`, composite or derived identity, unstable equality type, nullable ID, and nondeterministic collation |
| Basic columns | Explicit `@Column`, including an explicitly written `nullable = true` or `nullable = false`; eager bounded scalar values with exact generated JDBC metadata | Reject Jakarta-default nullability, lazy basics, implicit names, unbounded strings/numerics, and unrecognized Java/JDBC mappings |
| Mutable entity | Mandatory non-null `Integer`, `Long`, or `Short` `@Version`, initially zero, with explicit applied/missing/conflict outcomes | Reject mutation plans without a version token and negative or non-integral versions |
| Append-only entity | Explicit Vev `@AppendOnly`; generated plans expose insert/read but no update | Reject attempts to update through the safe API |
| Equality index | `@VevIndex` on an ordinary scalar value; explicit schema-unique index name; at most 16 per entity; bounded generated equality pages and nullable-only `IS NULL` pages | Reject index annotations on ID/tenant/version, unique or partial/expression/include indexes, wrong key order, excessive retained-key size, and undeclared secondary indexes |
| Transient state | Not accepted in the first record profile | Every record component must be an explicitly mapped scalar column |
| Tenancy | Explicit Vev `@TenantKey`, an opaque model-typed `TenantScope<Model,T>` minted only after the generated single-use `TenantAuthority<Model,T>` is claimed by one verified `PgVev`, and generated structural tenant predicates | Reject cross-model, foreign-authority, reused-authority, missing, null, wrong-type, or entity/scope-mismatched tenant state before SQL |

The initial PostgreSQL codec surface is intentionally small: `boolean`/`Boolean`, `int`/`Integer`, `long`/`Long`, `short`/`Short`, `String`, `BigDecimal`, `LocalDate`, `LocalDateTime`, `Instant`, and `UUID`. Runtime values must have the codec's exact boxed class; subclasses are rejected. `String` requires an exact `@Column(length=...)` from 1 through 65,535 code points; string IDs and tenant keys require 128. `BigDecimal` requires precision 1 through 128 and a scale from zero through that precision, and values must have exactly that scale. Temporal values are finite proleptic-ISO years 0001 through 9999 at microsecond precision; PostgreSQL infinity sentinels and lossy nanoseconds are rejected. A mapping is accepted only after its processor and PostgreSQL conformance fixtures exist; other scalar, temporal, and enum representations are rejected until then.

A closed model has at most 128 entities and an entity has at most 64 columns. The compiler and runtime enforce both limits without trusting user-supplied collection sizes. The generated maximum row shape multiplied by the 1,001-row internal page bound must fit a 64 MiB materialized-result budget. Strings reject U+0000 and malformed UTF-16 before JDBC binding. Numeric and temporal limits, tenant equality, assigned-ID presence, initial version zero, and version overflow are checked before SQL.

The current experiment treats ID non-reuse within a tenant as an application/schema invariant but cannot attest it. Vev therefore exposes neither physical delete nor create-capable upsert, and its verified application role must have no `DELETE` privilege. This removes the library's previous delete/reinsert ABA path, but privileged out-of-band changes can still violate the invariant.

The initial live-schema profile is equally closed. Every mapped relation must be a permanent, logged, nonpartitioned, non-inherited built-in heap table with no rewrite rules or enabled user triggers. It has the exact immediate built-in B-tree `(tenant, id)` primary key plus exactly the generated `@VevIndex` set. Each secondary index must be non-unique, immediate, built-in B-tree, have keys exactly `(tenant, indexed value, id)` with default ascending/null ordering and expected collations/operator classes, and have no predicate, expression, included column, constraint ownership, custom option, or custom tablespace. Check constraints, unique indexes, and every foreign key touching a mapped table remain rejected until those structures are represented in generated metadata and their tenant and execution semantics can be attested.

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
| Physical delete and create-capable upsert | Absent | Removing a row can make an assigned ID reusable and reintroduce version-zero ABA; lifecycle retirement is a versioned update |
| Check constraints, unique indexes, and foreign keys | Rejected | The current generator and startup verifier do not yet model their complete tenant and domain semantics |
| Native SQL in entity/repository metadata | Rejected in the safe profile | Arbitrary SQL cannot be proven to preserve mapping and tenant invariants |

## Query and repository profile

The native surface is implemented for generated point reads, ordered bounded batch reads, insert, versioned update, append-only insert/read, and ID-ordered traversal through `PgQueries.scanById` and `PgQueries.scanByIdAfter`. A generated `@VevIndex` token adds `PgQueries.equal`/`equalAfter`; only a `PgNullableIndex` token type-checks with `isNull`/`isNullAfter`. Each page requires a `QueryLimit` from 1 through 1,000, fetches at most one sentinel row beyond that limit, materializes detached results, and reports `hasMore`. Continuation uses a generated `EntityKey<Model,Entity,Key>` as an exclusive cursor and a second fixed SQL shape. The cursor is relative to the active lexical tenant rather than tenant-bound; PostgreSQL predicates and RLS preserve isolation, but Vev cannot detect accidental reuse under another tenant. Pages share a consistent snapshot only when executed in one lexical transaction. The runtime accepts only its internal query representations; implementing `BoundedQuery` does not grant arbitrary SQL. There is no public predicate AST, runtime DSL, projection, join, `OFFSET`, or unbounded query path.

`insertMultiple` validates the complete bounded input and duplicate entity keys before SQL, binds one typed PostgreSQL array per column, expands them with ordinality in one statement, and verifies each returned snapshot in input order. `updateMultiple` also uses one fixed typed-array/ordinality statement. Its materialized preflight must match every tenant, identifier, and expected version before any row is updated; it then returns only ordered `Applied` outcomes whose non-version values exactly match the request and whose versions advance by one. A duplicate is rejected before SQL, while a stale, missing, or unexpectedly returned member poisons and rolls back the complete lexical transaction.

The Jakarta adapter is a deliberately nonconforming `EntityAgent`-shaped facade. It currently supports detached `find`/`get` and their ordered multiple variants, selected cache-mode options as no-cache semantics, `fetch()` as a no-op for already-loaded supported values, assigned-value insert, and homogeneous insert batches. Insert is accepted only because the schema profile forbids generated/default values and executable database hooks; every returned immutable snapshot must exactly equal its input. The facade does not yet honor every inherited option, property, lifecycle, or exception contract and must not be treated as an implementation supplied by a Jakarta provider. Immutable Jakarta `void` update and refresh operations are rejected before SQL; use the typed native API that returns the new snapshot. Upsert and delete are rejected because the native safe surface deliberately does not expose them. The following Jakarta or framework surfaces are outside the safe profile unless a later release explicitly lists them:

- `EntityManager` and persistence-unit bootstrapping;
- JPQL, HQL, Criteria, named queries, stored procedures, and entity graphs;
- Spring Data repositories, derived queries, query by example, and lazy references;
- lock modes and pessimistic-lock timeout semantics;
- second-level/query caches and session-level identity;
- transparent dirty checking, automatic flush, merge, and in-place refresh of immutable records.

## Diagnostic standard

A rejection should identify the entity member, unsupported feature, and safe next action. Falling back to reflection, accepting an annotation while ignoring a semantic attribute, or delaying a known incompatibility until the first production query is a defect.

This fail-early rule has an important boundary: source and generated-query errors should fail compilation, while facts about a live PostgreSQL catalog can only be proved at `PgVev` startup. Vev does not currently generate a canonical DDL/schema manifest that a build can compare with a migration.

The exported PostgreSQL plan SPIs are a linker surface for generated application code, not a supported handwritten extension point. Only unmodified annotation-processor output is inside the generated-plan safety profile; custom executable plan behavior is fully trusted by the runtime.

Compile-failure fixtures are part of the compatibility contract. Every newly accepted annotation attribute needs positive generation coverage, PostgreSQL integration coverage, and at least one nearby negative case.
