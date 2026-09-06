# Declared PostgreSQL check constraints

Vev accepts Jakarta Persistence 4.0.0-M6 `@Table(check = ...)` and
`@Column(check = ...)` as explicit schema metadata. Both forms describe the same
physical PostgreSQL table constraint. This does not make record mappings
conforming Jakarta entities, add native queries, or execute annotation text.

```java
@Entity
@Table(name = "shipment", schema = "dispatch",
    check = @CheckConstraint(name = "shipment_label_check",
        constraint = "(length(btrim((label)::text)) > 0)"))
public record Shipment(
    @Id @Column(name = "id", nullable = false) Long id,
    @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
    @Version @Column(name = "version", nullable = false) Integer version,
    @Column(name = "label", nullable = false, length = 64) String label) {}
```

The independently reviewed migration creates
`CONSTRAINT shipment_label_check CHECK (length(btrim(label)) > 0)`. The annotation
must contain PostgreSQL 18's **exact**
`pg_get_expr(conbin, conrelid, false)` output, including casts, parentheses,
whitespace, and newlines. Vev does not infer SQL types or normalize away
meaningful syntax. Use the trusted migration environment to obtain this form.

Names are explicit lowercase identifiers and must not collide with another
check, unique constraint, or outgoing foreign key on the same table. Options
must be empty. Each entity accepts at most 32 checks, each expression at most
4,096 UTF-16 code units, and a closed model at most 16,777,216 expression code
units. Empty text, NUL, and malformed Unicode fail compilation. Declarations
are sorted by name; changing a name or expression changes the fingerprint.
Source and separately compiled Java records generate identical metadata, and
the schema manifest includes every expected definition.

The compiler verifies the annotation shape, bounds, and names. It does not
certify SQL syntax, expression purity, or business correctness from the text.
Bootstrap proves the live database contract before making the runtime usable:

1. The complete named constraint set must match. Checks must be local, validated,
   enforced, immediate, non-inherited ordinary table checks. `NO INHERIT` and
   arbitrary constraint options are outside this profile.
2. Vev reads the stored PostgreSQL 18 expression tree as bounded data. Unknown
   nodes or fields, more than 2,048 nodes, excessive nesting, and trees over
   1 MiB are rejected. Variables must reference the exact mapped local columns,
   using physical attribute numbers even after earlier columns were dropped.
3. Every expression type, collation, operator, and function must pass the catalog
   allowlist. User functions, user operators, custom types, volatile functions,
   security-definer functions, set-returning calls, I/O coercions, and system
   expressions such as `CURRENT_DATE` are rejected. `pg_depend` alone would miss
   pinned builtin dependencies and is not used as a purity proof.
4. Only after type approval does Vev request the deparsed definition and compare
   it exactly. This ordering matters: deparsing a constant can call its type's
   output function. Verification never evaluates the check itself.

The current node profile includes scalar variables/constants, boolean and null
tests, approved scalar operators/functions, relabels, one-dimensional array
construction/coercion and scalar-array comparisons, `NULLIF`, `IS DISTINCT FROM`,
`CASE`, and `COALESCE`. Approved builtin operations cover integer/decimal
arithmetic and comparisons, text trimming/case/length/regular expressions,
null counts, and selected date/time operations. Date arithmetic includes
`date + integer` and `date - integer` (calendar days) and `date - date` (integer
day difference), through the pinned immutable builtin implementations. See the
[PostgreSQL date/time operator contract](https://www.postgresql.org/docs/18/functions-datetime.html).
The reversed `integer + date` SQL wrapper and arbitrary interval operations
remain outside this reviewed set. A user function with the same name cannot
pass the catalog boundary. Leap/year boundaries and nullable dates retain
PostgreSQL semantics; arithmetic overflow, like a failed constraint, rolls back
the whole lexical transaction. The small stable-function
allowlist (`concat`, `concat_ws`, `date`, `date_trunc`, `timestamptz`) depends only
on approved scalar inputs and the verified UTC context with `DateStyle = ISO, MDY`
and `IntervalStyle = postgres`. Display settings must match the dedicated pool's
session baseline and are checked again before commit without another SQL round trip. Type
inspection rejects polymorphic concatenation of arrays, binary data, and JSON
values; their formatting is outside the approved scalar context. Type
inspection can accept builtin constants such as `bytea` and `jsonb`; accepting
a constant does not itself add an entity codec. The separately specified
[Binary mapping](binary-values.md) supplies a bounded `bytea` codec and its
required generated length check.

[`@VevText`](text-values.md) uses a generated character-length check for bounded
PostgreSQL text. Generated bounds count toward the same entity and model limits
as ordinary declared checks, and receive the same dependency approval.

The exact allowlist lives in `PgCheckCatalog`; additions require synthetic
compiler and database evidence. The internal node representation is tied to
PostgreSQL 18 and is not a cross-version API. PostgreSQL upgrades require
conformance verification; unexpected representation changes fail closed.
Builtin catalog integrity remains part of the trusted database boundary.

Catalog results are cached only during bootstrap. There is no expression parser,
reflection, constraint evaluation, or extra statement on Vev's read/write path.
PostgreSQL enforces checks during writes. A failure rolls back the entire lexical
transaction, including an identity batch and earlier writes, even when application
code catches the immediate exception. Identity sequence allocations are not
rolled back. A check succeeds for SQL `NULL` as PostgreSQL specifies; use an
explicit non-null column when required.

Purity and metadata bounds do not prove that a regex or composed expression is
cheap. Review constraint costs against representative writes; normal transaction
and statement timeouts still apply. No benchmark advantage or complete
application-schema compatibility follows from this feature.

References: [PostgreSQL constraint catalog](https://www.postgresql.org/docs/18/catalog-pg-constraint.html),
[function catalog](https://www.postgresql.org/docs/18/catalog-pg-proc.html), and
[PostgreSQL 18.6 expression-node definitions](https://github.com/postgres/postgres/blob/REL_18_6/src/include/nodes/primnodes.h).
