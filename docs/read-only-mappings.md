# Read-only mappings

`@VevReadOnly` declares that a mapped record has no write capability, even inside
a lexical write transaction. This is different from running a mutable mapping
inside `vev.read(...)`, or an append-only mapping which permits insertion.

```java
@Entity
@VevReadOnly
@Table(name = "retained_entry", schema = "example")
public record RetainedEntry(
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id", nullable = false) Integer id,
        @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
        @Version @Column(name = "version", nullable = false) Long version,
        @Column(name = "label", nullable = false, length = 64,
                insertable = false, updatable = false) String label) {}
```

The generated plan supports point/batch reads, bounded ID scans, and declared
index queries. It does not implement assigned insertion, generated creation,
versioned mutation, or deletion capabilities, and emits no creation-input record.
Calls to those native write APIs fail compilation. The runtime rejects
inconsistent metadata that combines the read-only contract with a write
capability and does not compile insert, update, create, or delete statements for
that plan. The Jakarta facade can read these mappings but cannot insert them.

A stored identity and an optional `@Version` describe data rather than permission
to change it. Identity columns retain their exact type, identity mode, owned
sequence shape, and noncycling bounds. The application role has no privileges
on that sequence. The runtime does not retain a creation sequence token for the
read-only plan. Stored versions must still be non-null, non-negative `Integer`,
`Long`, or `Short` values; their maximum value is valid because reads do not
increment it. A read-only record without `@Version` is also supported.

Every column still requires explicit nullability and a supported bounded scalar
mapping. `@Column(insertable = false)` and `updatable = false` are accepted for
read-only mappings: either flag value leaves the entire entity without mutation
capabilities, so these redundant flags do not change its fingerprint. They remain
rejected on mappings which expose writes. `@AppendOnly` and `@VevDelete` cannot
be combined with `@VevReadOnly`.

## Database contract

Bootstrap requires table `SELECT` without grant option, no column/table write
privileges, and no identity-sequence privileges. Existing restrictions on schema
creation, table ownership, role capabilities, triggers, indexes, constraints,
and defaults still apply. Unexpected INSERT, UPDATE, DELETE, or sequence access
fails verification, including a single column-level write grant.

The model fingerprint records read-only semantics. Its deterministic schema
manifest adds `readOnly: true`, empty insert/update lists, `delete: false`, and
an empty identity-sequence privilege list where applicable. Migrations must
install matching role grants and the new fingerprint before the runtime is
constructed. A mapping change alone cannot remove an application's existing
database privileges.

This contract keeps the existing mandatory tenant key, explicit tenant
predicates, forced RLS, and closed-model restrictions. It does not make a table
global, bypass tenant ownership, permit arbitrary views or partial column sets,
or introduce shared-data access. Global reference data, authentication data, and
cross-tenant administration require separately specified access boundaries.

Read failures use normal transaction poisoning and rollback, including rollback
of earlier writes in a write transaction when malformed stored data is read and
the callback catches the exception. Synthetic coverage includes Java and Kotlin,
source and compiled records, assigned and stored identity columns, optional
versions, both JDBC transfer modes, all existing read families, rejected write
call sites, privilege drift, and a negative stored-version rollback case.
