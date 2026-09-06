# Immutable binary values

`no.beint.vev.Binary` maps a bounded immutable value to PostgreSQL `bytea`.
Every mapped binary component requires `@VevBinary` with an explicit byte bound
and the name of its migration-installed length check. Mutable `byte[]`, Kotlin
`ByteArray`, writable buffers, JDBC blobs, and arbitrary converters are not mapped.

For example, an attachment-sized value can declare a 20 MiB limit and a one-row
batch/page ceiling:

```java
@Entity
@Table(name = "document", schema = "files")
@VevRows(1)
public record Document(
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id", nullable = false) Long id,
        @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
        @Version @Column(name = "version", nullable = false) Integer version,
        @VevBinary(maximumBytes = 20 * 1024 * 1024, check = "document_content_max")
        @Column(name = "content", nullable = false) Binary content) {}
```

The migration must include the matching constraint, alongside the complete key,
grant, fingerprint, and forced-RLS contract:

```sql
CONSTRAINT document_content_max CHECK (octet_length(content) <= 20971520)
```

`@Column.length` retains its default because Jakarta's character length does not
express this byte bound. Precision and scale must remain zero. Binary components
are ordinary values; they cannot be identifiers, tenant keys, or versions. Nullable
components explicitly declare `nullable = true`; SQL null and an empty byte string
remain distinct. Java source records and separately compiled Java/Kotlin records
follow the same mapping contract.

## Bounds and schema verification

The value type accepts at most 32 MiB. Each column declares a limit from one byte
through that maximum, and the complete row multiplied by its row limit plus a
paging sentinel must fit the existing 64 MiB materialized-result estimate. Thus a
full 32 MiB column cannot fit even a one-row page once the sentinel and object
overhead are counted. Use the actual application limit; [`@VevRows`](row-limits.md)
permits large values without weakening the result budget. This is a retained
snapshot estimate, not a cap on the application's total heap or JDBC buffers.

Every binary column needs exactly one generated bound check, counted within the
32-check entity limit. The manifest describes it with `kind: "BINARY_MAXIMUM"`,
`column`, `maximumBytes`, and a canonical quoted expression. Changing its name or
bound changes the model fingerprint. Column `maximumLength` is measured in bytes
for Binary, and in code points for character columns.

Bootstrap verifies the complete check set and approved expression dependencies
before comparing definitions. PostgreSQL's own identifier formatter supplies the
expected deparsed spelling, including reserved column names. Missing, renamed,
weakened, tightened, differently targeted, unvalidated, or unenforced checks fail
verification. Vev never executes the expression metadata or installs the check.
Additional domain checks use the ordinary [check-constraint contract](check-constraints.md).

Small binary values such as digests may have typed equality indexes and
tenant-scoped unique constraints. Their maximum bytes count toward the same
1,536-byte worst-case B-tree key budget, including tenant/identifier keys and
overhead. Large payload indexes are rejected at compilation.

## Ownership and I/O

`Binary.copyOf(bytes)` takes a defensive copy; the caller must not modify the
source concurrently with that copy. `fromHex` decodes bounded hexadecimal pairs.
`toByteArray` and `copyTo` copy into application-owned storage. `byteAt`,
`asReadOnlyBuffer`, and independent `openStream` cursors expose no writable backing
array. The stream's inherited transfer helpers cannot hand that array to an
arbitrary output stream. Equality and hashing compare content; ordering compares
unsigned bytes. `toString` describes only the byte count.

The PostgreSQL codec copies JDBC result arrays into owned snapshots. Scalar binds
read the immutable value through an in-memory stream. Batch binds supply defensive
arrays in pgjdbc's required `byte[][]` representation for one-dimensional `bytea[]`,
including null elements. Existing array cleanup, returned-payload verification,
input-order correlation, tenant isolation, and complete transaction rollback also
apply to binary writes. Oversized application values fail before SQL.

This API materializes bounded values. It does not offer database streaming, partial
LOB updates, object-store integration, or a performance advantage claim. Synthetic
PostgreSQL fixtures cover 20 MiB identity creation, scalar and batch updates,
nullable Kotlin batches, digest indexes, mutable-driver-buffer isolation, altered
constraints, and rollback on corrupted results or array cleanup failure.
