# Ordered index queries

`@VevIndex` can fix the ordering of an equality query at compilation. Its
`orderBy` member names an exact mapped database column; it never accepts a SQL
expression or a request-supplied name. The filter and ordering columns must be
distinct VALUE components, and the ordering column must be non-null. The filter
may be nullable. Ordering by ID alone retains the existing `PgIndex` API.

For example, these components inside a mapped record declare a descending page
of entries in one category:

```java
@VevIndex(name = "entry_category_idx", orderBy = "priority",
          direction = VevIndex.Direction.DESC)
@Column(name = "category", nullable = true, length = 64) String category,
@Column(name = "priority", nullable = false) Integer priority
```

The processor emits `PgNullableOrderedIndex<Model, Entry, Integer, String,
Integer>` for an Integer-ID record. Required filters instead receive
`PgRequiredOrderedIndex`. The last type argument is the ordering value type.
The generated token exposes one immutable direction: `ASC` by default, or
`DESC` for both the ordering value and ID tie-breaker. `DESC` without `orderBy`
is rejected. A nullable ordering value, mixed directions, several ordering
columns, expression ordering, and a runtime sort selection are unsupported.

## Pages and cursors

```java
var first = tx.entities().many(
        PgQueries.equal(EntryVev.CATEGORY, "ready", new QueryLimit(20)));
if (first.hasMore()) {
    var last = first.values().getLast();
    var cursor = EntryVev.CATEGORY.cursor(
            last.priority(), EntryVev.INSTANCE.key(last.id()));
    var next = tx.entities().many(
            PgQueries.equalAfter(EntryVev.CATEGORY, "ready", cursor, new QueryLimit(20)));
}
```

`isNull` and `isNullAfter` accept only the nullable ordered token. They filter
null categories while ordering by the separate, non-null priority. A cursor
contains the ordering value and entity key, so ties continue by ID without
skipping the remaining equal-priority rows. Passing an ID-only cursor to an
ordered query or passing the wrong ordering type fails normal Java compilation.
The runtime also rejects erased wrong types and cursors from another exact
index token; constructing a lookalike token cannot add executable queries.

The cursor is relative to the active lexical scope and does not bind tenant
authority, filter value, or snapshot. Keep the same filter and run related pages
inside one transaction when traversal must observe one snapshot. Reusing a
cursor under another tenant still enforces that tenant's predicates and RLS,
but Vev cannot diagnose that caller logic mistake. Separate transactions can
observe intervening inserts, updates, and deletions. The cursor need not name a
currently existing row. It defines an exclusive position in PostgreSQL's order.
String, enum, UUID, binary, and temporal ordering follows the verified PostgreSQL
codec and collation; Vev does not sort snapshots with Java comparators.

Each page uses one prepared entity statement, binds all request values, and
fetches at most the requested limit plus one sentinel. The limit must fit the
entity's `@VevRows` contract. Cursor values retain the column's exact scalar
bounds, including decimal scale and temporal precision, before SQL preparation.
Returned snapshots retain the normal value, tenant, key, version, and filter
checks. JDBC, materialization, and cleanup failures poison the transaction;
earlier writes roll back even when application code catches the query failure.

## Exact schema contract

The migration installs one non-unique B-tree in the declared order:

```sql
CREATE INDEX entry_category_idx ON work.entry
    (tenant_id, category, priority DESC, id DESC);
```

Tenant and filter prefixes use default ascending/nulls-last ordering. The two
non-null tail columns use `ASC NULLS LAST` or `DESC NULLS FIRST` together.
An explicitly shared read-only mapping omits only the tenant prefix. Bootstrap
verifies every key's position, direction, null placement, collation and default
builtin operator class, along with the existing exact index-set and index-shape
checks. Reordered keys, different directions, partial/expression/include indexes,
and undeclared indexes fail startup. Equivalent alternative physical ordering
is deliberately outside this exact migration contract.

Ordering strings, including STRING enum columns, have a maximum declared bound
of 256 code points. All keys together, including tenant, filter, ordering value,
and identifier, must fit the conservative 1,536-byte retained-key budget.
Adding the ordering column or changing direction changes the model fingerprint.
The schema manifest lists all index columns in order; a descending index also
records a matching `directions` array. Its absence means every key is ASC with
default null placement. Review and apply the matching migration before starting
a new runtime.

The current generated-plan SPI is ABI 4 (ordered query tokens were introduced in ABI 3). Recompile existing mappings with the matching
processor/runtime even when an ordinary ID-ordered index's schema is unchanged.
Ordered tokens and ID-ordered tokens share metadata through `PgQueryIndex` but
remain distinct query capabilities; casting one into the other is unsupported.

## SQL and evidence

An ascending continuation binds `(ordering_value, id) > (?, ?)` and orders both
values ascending. A descending continuation binds the corresponding `<` tuple
and orders both descending. Leading equality keys and a range on the remaining
B-tree keys let PostgreSQL select an indexed continuation; its planner remains
free to choose another plan for other data distributions. See PostgreSQL's
[multicolumn index](https://www.postgresql.org/docs/18/indexes-multicolumn.html),
[row comparison](https://www.postgresql.org/docs/18/functions-comparisons.html#ROW-WISE-COMPARISON),
and [index ordering](https://www.postgresql.org/docs/18/indexes-ordering.html)
contracts.

The integration suite covers both directions, ties across page boundaries,
nullable filters, missing cursor rows, scoped and shared mappings, both pgjdbc
wire modes, Java and compiled Kotlin records, pre-SQL rejection, altered catalog
shapes, and caught-failure rollback. It also captures the actual generated SQL
and `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` for 20,000 synthetic rows in each
direction. The checked-in [query-plan evidence](query-plans/ordered/README.md)
shows a tuple index condition without a separate sort for those fixtures.
This is plan-shape evidence, not a latency benchmark, a general plan guarantee,
or proof of application replacement. Representative comparison against the
application's Hibernate version remains a release gate.
