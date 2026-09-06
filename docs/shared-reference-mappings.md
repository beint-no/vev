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

Include the record in the same `@VevModel` as tenant-owned mappings. The model
must contain at least one tenant-owned record to establish its tenant-key type
and transaction authority. A standalone global reader or administrative writer
needs a separate future authority contract; inventing a tenant column or a
sentinel tenant value is not supported.

Shared reads still require a scope minted by the verified runtime's authority.
They retain lexical ownership, thread confinement, serializable transactions,
deadlines, fingerprint verification, bounded results, checked scalar hydration,
and rollback after a failed read. The generated `PgSharedEntityPlan` has no
`tenantCodec`, `tenantColumn`, or `tenantKeyOf` methods. It cannot implement
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

Declared query indexes have `(value, id)` keys, or `(id)` for an identifier index.
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
cascade. Incoming and outgoing foreign keys must remain declared inside the
closed model, including incoming references to read-only shared tables. Mapping
a shared table alone does not waive that restriction for an incremental adoption.

The fingerprint includes shared ownership. The schema manifest adds
`shared: true`, `readOnly: true`, the global primary/index/unique/reference shapes,
`rowSecurity: {enabled: false, forced: false, policies: []}`, and empty write and
sequence privileges. It contains no tenant column or tenant setting for that
table. Migrations must install the matching schema and fingerprint.

Synthetic verification covers Java source and compiled records, Kotlin records,
assigned/stored identity and optional-version combinations, both wire formats,
all read families in two tenants, tenant-to-shared and shared self-references,
compile-time rejection of writes and unsafe ownership combinations, unexpected
policies/privileges/constraints, and rollback of earlier tenant writes after a
caught shared-read or foreign-key failure. This feature does not establish a
performance advantage or complete application replacement readiness.
