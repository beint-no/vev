# Declared database defaults

Vev accepts an explicit database default on an ordinary VALUE column through
Jakarta `@Column.options`:

```java
@Entity
@VevReadOnly
@Table(name = "retained_flag", schema = "example")
public record RetainedFlag(
        @Id @Column(name = "id", nullable = false) java.util.UUID id,
        @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
        @Column(name = "enabled", nullable = false, options = "DEFAULT true") Boolean enabled) {}
```

This preserves an existing physical default without changing Vev's value
semantics. Read-only and writable records use the same default metadata.
Jakarta defines [`Column.options`](https://jakarta.ee/specifications/persistence/4.0/apidocs/jakarta.persistence/jakarta/persistence/column)
as a database-specific DDL fragment. Vev interprets its `DEFAULT` form as exact
schema expectations; it does not generate or execute that fragment as DDL.
This remains nonconforming annotation reuse, not Jakarta provider conformance.

## Metadata contract

The compiler accepts the `DEFAULT` keyword followed by a nonempty expression,
normalizing keyword case and surrounding whitespace. The expression must be
well-formed Unicode, contain no U+0000, and have at most 4,096 UTF-16 characters.
Its content is the exact `pg_get_expr(adbin, adrelid, false)` representation from
PostgreSQL 18 under the bootstrap display context. For example, a varchar default
may require `DEFAULT 'draft'::character varying`; an integer expression might be
`DEFAULT (2 + 3)`. Expression spelling, casts, parentheses, and internal whitespace
are significant. Expressions are never inserted into a Vev SQL statement.

Blank options mean no default. A differently prefixed option and a nonempty
`columnDefinition` remain rejected. A bare `DEFAULT NULL` is rejected because
PostgreSQL omits that stored default; omit the option instead. Other expressions
that PostgreSQL simplifies to no stored default also cannot satisfy a declared
expression. Defaults on identifiers, tenant keys, and versions are rejected.
Identity generation retains its separate capability, sequence, and privilege
contract.

The compiler validates placement and metadata bounds. It does not parse SQL or
prove that the expression is valid for the column. Bootstrap verifies the actual
stored expression and its result type. Additional DDL clauses, comments, or
unrecognized expression text cannot pass that exact stored-expression check.
The combined check/default metadata retained by one model is limited to 16 MiB
of UTF-16 characters at both compiler and runtime boundaries.

A declared default changes the mapping fingerprint and adds `defaultExpression`
to that column's schema manifest. Columns without defaults retain their previous
manifest and fingerprint representation. Default metadata was introduced in ABI 6; current ABI 7 includes default
metadata; mappings compiled against earlier ABIs require regeneration with a
matching processor and runtime. No production dependency is added.

## Bootstrap verification

Column verification requires stored-default presence to match the declaration.
Vev then reads bounded PostgreSQL expression-tree data, requires the expression's
result type to match the verified column type, and rejects column-variable
references. It uses the same finite PostgreSQL 18 node/type/operator/function/
collation profile as [check constraints](check-constraints.md). This includes
ordinary scalar constants, approved casts and arithmetic; it does not approve
arbitrary functions, volatile defaults, identity/session expression nodes, custom
types, or input/output coercions merely because they appear in a default. The
transaction-clock defaults below have an additional, separate approval contract.

All dependency types are approved before deparsing can invoke a constant type's
output function. Vev never evaluates a default to test it. Only after dependency
approval does it compare the bounded deparsed text with the declared expression.
A missing, extra, differently typed, altered, or unapproved default prevents
startup. Query failures and nested result/statement/connection cleanup failures
leave the tenant authority unclaimed, permitting a corrected bootstrap retry.

Existing type, column-bound, collation, privilege, RLS, and generated-column
checks remain. Default acceptance does not prove the correctness of another
writer or that every evaluation meets the application's domain rules. Retain
business constraints and review external writes. PostgreSQL
[`pg_attrdef`](https://www.postgresql.org/docs/18/catalog-pg-attrdef.html) stores the
expression metadata used for this comparison.

## Transaction-clock defaults

VALUE columns may declare PostgreSQL `CURRENT_DATE`, `CURRENT_TIMESTAMP`,
`LOCALTIME`, `LOCALTIMESTAMP`, `now()`, or `transaction_timestamp()` defaults.
The three SQL time/timestamp forms also accept their native explicit precision
from zero through six. These return transaction-start values when another writer
omits a column; see PostgreSQL's
[current date/time contract](https://www.postgresql.org/docs/18/functions-datetime.html#FUNCTIONS-DATETIME-CURRENT).
The declaration still uses exact stored text: `CURRENT_TIMESTAMP`,
`CURRENT_TIMESTAMP(3)`, and `now()` are different schema expectations.

Bootstrap validates each native SQL clock node's operation, PostgreSQL result
type, and precision. Named clocks additionally require their exact built-in
internal, stable, zero-argument timestamp signature, with matching stored call
inputs and result types. All surrounding expression dependencies must satisfy
the existing approved profile. Vev never evaluates the expression while
verifying it. Arbitrary clock wrappers, `clock_timestamp()`,
`statement_timestamp()`, offset `CURRENT_TIME`, and user/role/session/catalog
expressions remain unsupported.

Clock approval applies only to defaults. CHECK constraints cannot use these
clocks, including after the same bootstrap catalog cache has approved one for a
default. Their truth changes with time, so they do not establish a persistent
row invariant. The verifier preserves this distinction for nested expressions,
cached functions, malformed signatures, and corrected retries.

Vev writes still bind every timestamp and nullable value explicitly. Precision
in a default expression never rounds a supplied value or narrows the column's
verified storage precision. Applications remain responsible for creation/update
timestamps; this feature does not add automatic lifecycle callbacks or database
clock queries to mutations. Normal temporal bounds and finite-value checks
remain in force. No generated-plan ABI, request SQL, or dependency change is
required.

## Historical values from column additions

PostgreSQL can add a column with a nonvolatile default without rewriting old
rows. It evaluates that expression during the migration and retains a datum for
physically absent attributes. A later default change affects new writes;
historical reads still use the retained datum. A physical rewrite materializes
it into the rows. See PostgreSQL's
[column migration documentation](https://www.postgresql.org/docs/18/ddl-alter.html)
and [`pg_attribute` catalog contract](https://www.postgresql.org/docs/18/catalog-pg-attribute.html).

Vev accepts this native storage on mapped columns when `atthasmissing` has a
nonnull `attmissingval`. Bootstrap checks only presence; it never retrieves,
deparses, or evaluates that datum. PostgreSQL reads it through the already
verified built-in column type, and Vev validates the resulting row normally.
A flagged attribute with no catalog datum is rejected. The fingerprint table
retains its separate stricter storage contract.

The historical value is row data, so it need not equal the current default and
is not included in a fingerprint, manifest, or generated plan. A migration may
have dropped the old default entirely, including defaults once used to populate
an identifier, tenant key, or version. Such columns must still satisfy their
current no-default mapping and every structural validation. This does not
permit a current structural-column default or weaken identity generation.
No application backfill, normalization, or table rewrite is required just to
remove `atthasmissing`; ordinary validated migrations remain responsible for
what values they install.

Synthetic coverage retains older tuples across changed and dropped defaults,
a binary-compatible varchar widening, Java/Kotlin and shared read-only reads,
tenant/key/version storage, every supported scalar family, indexed predicates,
explicit single/batch writes, optimistic conflicts, and both JDBC wire modes.
It also checks unchanged reads after a physical rewrite, current-default drift,
and transaction rollback after invalid historical versions, enum names, and
end-of-day times. This storage support requires no generated-plan ABI change,
new dependency, extra bootstrap query, or request SQL change.

## Explicit values remain explicit

Assigned insertion and generated-identity creation still bind every application
VALUE component. Generated inputs still require those fields. Vev supplies its
structural tenant/version values and handles identity generation separately.
Updates likewise use explicit replacement values. There is no omitted-field
sentinel, `DEFAULT` expression in mutation SQL, or substitution of a database
default for `null`, `false`, zero, or an empty value. Nullability and all scalar
bounds remain checked before SQL.

Rows inserted by another authorized writer using PostgreSQL defaults can be read
normally. Materialization still validates every scalar, tenant, identity, stored
version, enum name, and bound. Failed reads and failed writes retain the same
transaction poisoning and rollback behavior; a failing row in a batch rolls back
the entire lexical transaction even when application code catches its exception.

Synthetic coverage includes Java and separately compiled Kotlin, assigned and
identity writes, mutable and read-only records, both JDBC wire modes, nullable
values, booleans, integral/decimal values, varchar/text, enum names, UUID, binary,
and finite date/time values. It also verifies exact fingerprint/source/binary
metadata, combined memory bounds, retained physical defaults, schema drift,
pre-deparse dependency rejection, root-type/variable corruption, and nested
bootstrap resource failures. Query SQL is unchanged by default metadata; this is
functional evidence, not a measured application performance claim.
