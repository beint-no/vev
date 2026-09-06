# Shared reference mappings

`@VevShared` explicitly declares rows which every tenant in a closed model may
read. It requires `@VevReadOnly`, forbids `@TenantKey`, and grants no mutation
capability. Missing tenant metadata alone is rejected. This annotation is a
reviewed data-sharing decision, not a way to make an unsupported mapping compile.
Authentication, credentials, tenant membership, installation lifecycle, and
administrative records must not be classified as shared just because their
existing mapping lacks a tenant discriminator.

```java
@Entity
@VevShared
@VevReadOnly
@Table(name = "category", schema = "example",
       uniqueConstraints = @UniqueConstraint(name = "category_code_key", columnNames = "code"))
public record Category(
        @Id @Column(name = "id", nullable = false) Integer id,
        @Column(name = "code", nullable = false, length = 32) String code,
        @VevIndex(name = "category_label_idx")
        @Column(name = "label", nullable = true, length = 64) String label) {}
```

The model normally infers its tenant-key type from tenant-owned mappings. A model
containing only shared records declares the real application's lexical tenant type:

```java
@VevModel(entities = Category.class, tenantType = Integer.class)
public final class ReferenceModel {}
```

`tenantType` accepts Integer, Long, Short, String, or UUID. The integral primitive
forms `int.class`, `long.class`, and `short.class` normalize to their boxed types;
other primitives, arrays, arbitrary classes, and `Void.class` are rejected.
The default `void.class` means inference and still requires a tenant-owned mapping.
An explicit type in a mixed model must match every mapped tenant key. Redundant
matching declarations preserve the inferred model's fingerprint and generated
output. Empty models remain rejected.

The type establishes the transaction scope contract; it does not authorize a
principal or create row ownership. Application authorization still precedes
minting a scope for the actual request or job's tenant. No synthetic tenant
column or sentinel tenant is needed. Unscoped global administration and private
authentication records require a separate future authority contract.

Shared reads still require a scope minted by the verified runtime's authority.
They retain lexical ownership, thread confinement, serializable transactions,
deadlines, fingerprint verification, bounded results, checked scalar hydration,
and rollback after a failed read. The generated `PgSharedEntityPlan` has no
`tenantCodec`, `tenantColumn`, or `tenantKeyOf` methods. Its `scopeType` class
is captured once during model construction and must match every other plan's
lexical tenant type, including when no row stores a tenant key. It cannot implement
`PgTenantEntityPlan` or any insertion, creation, versioned mutation, or deletion
capability. Shared rows are readable inside both read and write transactions.
An optional stored identity or version follows the read-only mapping contract;
no sequence privileges or creation token are acquired.

Point reads, ordered batch reads, ID traversal, and all declared equality/null
index pages omit the tenant predicate only for this explicit shared mapping.
They preserve their existing ordering, missing-result, sentinel, and row-budget
contracts. The same global rows are visible under different valid tenant scopes;
tenant-owned rows in the same transaction remain protected by their predicates
and forced RLS.

## Exact PostgreSQL contract

The migration installs an ordinary permanent heap table with the complete mapped
column set, supported codecs, bounds, checks, and the existing restrictions on
ownership, defaults, generated columns, inheritance, triggers, and rewrite rules.
Its primary key is exactly `(id)`. `@VevPrimaryKey(ID)` may state that explicitly;
other shapes are rejected. No additional tenant traversal index is required.

Declared query indexes have `(value, id)` keys, `(id)` for an identifier index,
or the exact [ordered-query](ordered-index-queries.md) value/order/ID shape.
Named unique constraints contain one to 32 ordinary VALUE columns with the same
immediate `NULLS DISTINCT`, default built-in operator, collation, and retained-key
budget requirements. Identifiers and versions cannot be added to a shared
business unique constraint. Indexes and unique constraints together remain
limited to 16 per entity.

The application role has SELECT without grant option, no write or sequence
privileges, and no row ownership. Both RLS flags must be false and the table must
have zero policies, including disabled or currently ineffective policies.
Bootstrap counts unexpected policies without deparsing their expressions. It
rejects both partial grants and unexpected schema shapes before claiming the
runtime's authority. Tenant-owned tables continue to require enabled, forced RLS
with the exact role-specific tenant policy.

A tenant-owned or shared row may declare a scalar `@VevReference` to a shared
record in the same closed model. The FK is exactly `(reference)` to `(target id)`;
no tenant column is fabricated. Omit `tenantFirst` for these references; `false`
is rejected because a scalar global reference has no tenant order. Shared rows
cannot reference tenant-owned rows. Existing tenant-to-tenant references retain
their exact two-column ownership contract.

All references still require matching scalar types, typmods and collations,
validated/enforced immediate `MATCH SIMPLE` and `NO ACTION` behavior, and all four
built-in integrity triggers. There is no automatic relationship loading or
cascade. Complete closure is the default. Explicit
[`@VevReadOnly(externalIncomingReferences = true)`](read-only-mappings.md#external-incoming-references)
places incoming references from unmapped source tables outside this model's
attestation contract. Outgoing, self, and within-model references remain fully
declared and verified. The option changes no physical constraint or write grant;
application migrations retain responsibility for external writer correctness.

The fingerprint includes shared ownership. The schema manifest adds
`shared: true`, `readOnly: true`, the global primary/index/unique/reference shapes,
`rowSecurity: {enabled: false, forced: false, policies: []}`, and empty write and
sequence privileges. It contains no tenant column or tenant setting for that
table. Models containing only shared records additionally record the boxed
`tenantScopeType` at the manifest root and include it in the fingerprint. Changing
that type changes the scope contract even when physical tables stay the same.
Migrations must install the matching schema and fingerprint.

The generated-plan ABI is 5. Recompile all mappings with matching processor and
runtime versions; older binaries fail before reading new scope or reference
boundary metadata. The schema fingerprint and generated-plan ABI serve different contracts.

Synthetic verification covers Java source and compiled records, Kotlin records,
assigned/stored identity and optional-version combinations, both wire formats,
all read families in two tenants, tenant-to-shared and shared self-references,
compile-time rejection of writes and unsafe ownership combinations, unexpected
policies/privileges/constraints, and rollback of earlier tenant writes after a
caught shared-read or foreign-key failure. This feature does not establish a
performance advantage or complete application replacement readiness. Models
containing only Java/Kotlin shared records also verify real tenant scopes, every
read family, failed-bootstrap authority reuse, rejection of foreign scopes before
connection access, SELECT-only grants, rollback after caught read failures, and
model-specific fingerprint checks. The isolated published-artifact consumer
compiles and initializes a shared-only UUID-scoped model from packaged libraries.
