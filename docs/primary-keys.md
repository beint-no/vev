# Physical primary keys and tenant traversal

This page describes tenant-owned records. Explicit [shared reference mappings](shared-reference-mappings.md) instead require an ID-only primary key, with no tenant traversal prefix or tenant-qualified reference key.

The scalar `@Id` is the identifier carried by Vev's typed API. The PostgreSQL
primary key may contain that identifier alone or together with the tenant column.
Declare a physical shape when it differs from the default:

| Declaration | Exact PostgreSQL primary key |
|---|---|
| No annotation, or `@VevPrimaryKey(Shape.TENANT_ID)` | `(tenant, id)` |
| `@VevPrimaryKey(Shape.ID_TENANT)` | `(id, tenant)` |
| `@VevPrimaryKey(Shape.ID)` | `(id)` |

`Shape` is `VevPrimaryKey.Shape`; the columns use the explicit names of the
record's `@TenantKey` and `@Id` components. This is physical schema metadata,
not support for multiple `@Id` components, `@EmbeddedId`, or `@IdClass`.
Those application identifier representations remain separate release work.

The physical shape never removes tenant predicates or forced RLS from operations.
Reads and writes still use the lexical tenant. An ID-only key deliberately makes
identifier uniqueness global: an assigned-ID collision can reveal that an ID is
already occupied in another tenant, without allowing access to that tenant's row.
Use the tenant-qualified primary-key profile when identifiers must be reusable
across tenants or that existence signal is unacceptable.

## Keep tenant traversal indexed

Every accepted shape must support a B-tree path beginning with `(tenant, id)`.
The default primary key supplies it. For another shape, declare either:

- `@VevIndex` on the `@Id` component, requiring an exact non-unique `(tenant, id)`
  index; or
- a named Jakarta `@UniqueConstraint` whose columns are exactly `(tenant, id)`.

An ordinary index is enough for traversal; it does not need to enforce another
unique constraint. An ID index emits a required typed token such as `EntryVev.ID`
and supports the same equality-query API as other required indexes. It has two
columns; an ordinary value index retains `(tenant, value, id)`.

```java
@Entity
@VevPrimaryKey(VevPrimaryKey.Shape.ID)
@Table(name = "entry", schema = "ledger", uniqueConstraints =
        @UniqueConstraint(name = "entry_id_tenant_key", columnNames = {"id", "tenant_id"}))
public record Entry(
        @VevIndex(name = "entry_tenant_id_idx")
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id", nullable = false) Long id,
        @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
        @Version @Column(name = "version", nullable = false) Long version,
        @Column(name = "description", nullable = false, length = 128) String description) {
}
```

The migration for this shape contains `PRIMARY KEY (id)`, the declared unique
constraint `UNIQUE (id, tenant_id)`, and the declared non-unique index
`entry_tenant_id_idx ON ledger.entry (tenant_id, id)`. It must also install the
complete identity, RLS, privilege, and column contract described by the generated
manifest. Existing IDs and the tenant-qualified business key can be retained.

A declared `(tenant, id)` unique constraint can serve both tenant traversal and
foreign-key enforcement, avoiding a separate traversal index when that shape
already fits the schema. Do not drop an existing referenced constraint merely to
reduce the index count. Any constraint consolidation requires an explicit
migration and workload evidence.

## References still enforce tenant equality

A foreign key cannot target a global ID alone. `@VevReference` always pairs the
reference value with its tenant, and pairs the target ID with the target tenant.
An ID-only primary-key target therefore needs a declared named unique constraint
over exactly `(id, tenant)` or `(tenant, id)`. Both orders are supported and
attested exactly. Other business unique constraints retain their tenant-first
ordinary-value contract.

For example, a child can declare
`@VevReference(name = "line_entry_fk", target = Entry.class, tenantFirst = false)`
on its scalar `entryId`. The database must enforce
`FOREIGN KEY (entry_id, tenant_id) REFERENCES ledger.entry (id, tenant_id)`.
The target is its declared unique key, and Vev verifies the backing index and
all built-in integrity triggers. Cross-tenant references fail even when the
parent's ID is globally unique.

Foreign-key column order is independent of physical index order. Both the
reference's declared column order and the unique index's declared key order are
verified; PostgreSQL may use that unique index for a reference listing the paired
columns in the opposite order.

## Verification boundary

The compiler rejects an ID-first primary key without a declared tenant traversal
path, and rejects a reference to an ID-only target without tenant-qualified
uniqueness. The runtime captures the shape once, attests the exact primary-key
columns and immediate B-tree enforcement, verifies every declared secondary
index, and rejects missing, reordered, partial, included, or unexpectedly unique
traversal indexes. The manifest and mapping fingerprint include physical key
shape and declared indexes; explicitly declaring the default preserves its
existing manifest identity.

Synthetic PostgreSQL coverage includes assigned and generated global IDs,
identifier-first composite keys, ordered pagination, typed ID equality queries,
tenant-filtered reads, reference creation through alternate unique keys,
cross-tenant rejection, and schema mutations. This does not establish acceptance
of an application's entire schema or performance across its actual workload.
