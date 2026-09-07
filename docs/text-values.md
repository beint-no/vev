# Bounded PostgreSQL text

`@VevText` selects PostgreSQL `text` for an ordinary String component. It requires
an explicitly written Jakarta `@Column.length` and a named migration-installed
character-length constraint. Existing columns can retain their `text` storage;
Vev does not convert them to `varchar` or accept an unbounded result shape.

```java
@VevText(check = "document_body_max")
@Column(name = "body", nullable = true, length = 1048576)
String body
```

The matching database constraint is:

```sql
CONSTRAINT document_body_max CHECK (char_length(body) <= 1048576)
```

The declared length counts Unicode code points, including combining characters
individually. It does not count UTF-16 code units, UTF-8 bytes, or grapheme clusters.
The runtime preserves whitespace and does not normalize Unicode. Empty strings
and SQL null stay distinct. NUL and malformed UTF-16 fail before JDBC binding, as
they do for Vev's other string mappings.

The explicit length must be from one through 8,388,608 code points, and the whole
entity's worst-case row multiplied by the row limit plus one sentinel must fit the
64 MiB retained-result estimate. The top declared bound cannot fit a complete
one-row page once object overhead and the sentinel are counted. A one-million
code-point value can use a smaller [`@VevRows`](row-limits.md) limit. JDBC buffers
and other application allocations are outside that retained-result estimate.

`@VevText` accepts String VALUE components only. Identifiers, tenant keys,
versions, enum representations, blobs, and text streaming are outside this
mapping. Each column explicitly declares nullability; Kotlin record nullability
must match it. `@Column.columnDefinition` remains empty because `@VevText` selects
the closed storage contract, and arbitrary DDL snippets remain unsupported.
Numeric precision and scale retain their defaults. Ordinary varchar mappings
remain unchanged.

## Verification and generated metadata

The generated `PgCodecs.TEXT` uses exact PostgreSQL `text` metadata with no type
modifier. Startup rejects a varchar substitute, missing or changed length bound,
wrong column, byte-length check, unvalidated or unenforced check, or extra
undeclared constraint. Approved expression dependencies are inspected before
deparsing. PostgreSQL's own formatter produces the expected identifier spelling,
including reserved names; Vev never executes expression metadata.

The manifest records a `TEXT_MAXIMUM` check with `column`, `maximumCodePoints`, and
a canonical quoted expression. The name, storage type, and character bound form
part of the model fingerprint. Each generated length check counts toward the
32-check entity limit. Ordinary domain constraints remain separately declared
through the [check-constraint contract](check-constraints.md).

Short text values support typed equality/null pages and tenant-scoped unique
constraints. Query indexes retain the 256-code-point string ceiling and the
1,536-byte combined B-tree key budget. Large text indexes are rejected at
compilation. The database's attested deterministic collation defines equality;
the codec does not impose its own comparison rules.

Assigned and identity creation, scalar/batch versioned updates, tenant isolation,
exact returned values, and full rollback use the existing generated SQL paths.
Synthetic PostgreSQL coverage includes one million supplementary Unicode code
points, nullable Kotlin identity batches, trailing whitespace, combining marks,
paging, unique violations, changed constraints, and direct database rejection of
oversized values. No performance improvement is claimed without representative
measurements.

Before migrating an application column, choose its intended domain bound, inspect
all existing values, and add a validated matching constraint while preserving
other business checks. An observed maximum alone does not establish that bound.
