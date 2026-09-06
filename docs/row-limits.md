# Row limits for larger snapshots

`@VevRows` declares the maximum batch and query-page size for one entity. The
default remains 1,000. A smaller explicit limit permits larger bounded values
while retaining the compiler's 64 MiB materialized-result estimate.

```java
@Entity
@Table(name = "message", schema = "dispatch")
@VevRows(8)
public record Message(
    @Id @Column(name = "id", nullable = false) Long id,
    @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
    @Version @Column(name = "version", nullable = false) Integer version,
    @Column(name = "body", nullable = false, length = 65535) String body) {}
```

The generated `MessageVev.INSTANCE.maximumRows()` returns eight. A point lookup
or single write retains its ordinary behavior. Batches can contain zero through
eight inputs; query pages can request one through eight snapshots. The global
`Batch` and `QueryLimit` ceilings remain 1,000.

The bound applies uniformly to `findMultiple`, `insertMultiple`,
`createMultiple`, `updateMultiple`, ID scans, indexed equality pages, nullable
index pages, and every continuation form. Duplicate lookup keys count as inputs.
An oversized request fails before application SQL or row-buffer allocation. It
does not truncate, split, or execute part of a batch, and this input-validation
failure does not poison the surrounding transaction.

Both the compiler and frozen runtime model verify the declared limit is 1–1,000
and that the maximum row estimate times **limit + 1** fits 64 MiB. The extra row
reserves space for pagination's `hasMore` sentinel. Lowering the limit cannot
make an intrinsically oversized row safe. The estimate describes the retained
result shape; it does not bound all driver buffers, input objects held by the
application, concurrent transactions, or PostgreSQL memory.

Only generated constant metadata is consumed. Runtime construction captures the
limit once; executing a query does not call a mutable metadata supplier.
Declarations work on source and compiled Java records and Kotlin `@JvmRecord`
classes. The manifest records the limit. A nondefault limit changes the model
fingerprint, while explicitly declaring the existing default preserves it.
Update the migration-managed fingerprint when changing a model's row contract.

Row limits also bound [immutable binary values](binary-values.md). They add no
JSON, XML, array, streaming, or projection codec by themselves.
Those mappings need their own immutable value contract, database bounds, and
failure-path verification.
