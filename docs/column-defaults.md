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
manifest and fingerprint representation. Generated-plan ABI 6 includes default
metadata; mappings compiled against earlier ABIs require regeneration with a
matching processor and runtime. No production dependency is added.

## Bootstrap verification

Column verification requires stored-default presence to match the declaration.
Vev then reads bounded PostgreSQL expression-tree data, requires the expression's
result type to match the verified column type, and rejects column-variable
references. It uses the same finite PostgreSQL 18 node/type/operator/function/
collation profile as [check constraints](check-constraints.md). This includes
ordinary scalar constants, approved casts and arithmetic; it does not approve
arbitrary functions, volatile defaults, current-system expression nodes, custom
types, or input/output coercions merely because they appear in a default.

All dependency types are approved before deparsing can invoke a constant type's
output function. Vev never evaluates a default to test it. Only after dependency
approval does it compare the bounded deparsed text with the declared expression.
A missing, extra, differently typed, altered, or unapproved default prevents
startup. Query failures and nested result/statement/connection cleanup failures
leave the tenant authority unclaimed, permitting a corrected bootstrap retry.

All existing table, column, collation, privilege, RLS, generated-column, and
missing-value restrictions remain. In particular, PostgreSQL `atthasmissing`
state is not enabled by this feature. Default acceptance does not prove the
correctness of another writer or that every evaluation meets the application's
domain rules. Retain business constraints and review external writes. PostgreSQL
[`pg_attrdef`](https://www.postgresql.org/docs/18/catalog-pg-attrdef.html) stores the
expression metadata used for this comparison.

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
