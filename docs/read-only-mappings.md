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
still apply. [Declared VALUE-column defaults](column-defaults.md) are verified as
metadata and do not grant insertion or change read behavior. Unexpected INSERT, UPDATE, DELETE, or sequence access
fails verification, including a single column-level write grant.

The model fingerprint records read-only semantics. Its deterministic schema
manifest adds `readOnly: true`, empty insert/update lists, `delete: false`, and
an empty identity-sequence privilege list where applicable. Migrations must
install matching role grants and the new fingerprint before the runtime is
constructed. A mapping change alone cannot remove an application's existing
database privileges.

By itself, this annotation retains the mandatory tenant key, explicit tenant
predicates and forced RLS. Closed reference verification remains the default;
the explicit external incoming option below changes only that boundary. Intentionally shared
reference rows require the separate [@VevShared contract](shared-reference-mappings.md).
Neither annotation permits arbitrary views, partial column sets, authentication
access, or cross-tenant administration.

Read failures use normal transaction poisoning and rollback, including rollback
of earlier writes in a write transaction when malformed stored data is read and
the callback catches the exception. Synthetic coverage includes Java and Kotlin,
source and compiled records, assigned and stored identity columns, optional
versions, both JDBC transfer modes, all existing read families, rejected write
call sites, privilege drift, and a negative stored-version rollback case.

## External incoming references

Complete incoming/outgoing foreign-key closure is the default. A SELECT-only
mapping can explicitly accept incoming references from tables outside its model:

```java
@VevReadOnly(externalIncomingReferences = true)
```

This option works with tenant-owned and explicitly shared records. It changes
only bootstrap's incoming reference boundary. All outgoing references, self
references, and incoming references from any mapped source must still be declared
and exactly verified. A source in another schema counts as mapped when both its
schema and table match a generated plan. Writable targets and read-only mappings
without the option retain complete closure.

External incoming constraints are outside this model's attestation contract:
their columns, source types, referential actions, validation state, and write-time
integrity triggers are neither endorsed nor required to match Vev's writable
reference profile. Bootstrap filters these constraints before its bounded result
limit, using bound names from the frozen model. It does not load external rows,
deparse external expressions, or invoke external source functions. JDBC catalog
arrays and other resources close on success and failure; a failed bootstrap
leaves tenant authority unclaimed.

The option does not change physical constraints, database rows, SELECT-only
privileges, identity-sequence restrictions, RLS, or any generated read statement.
A tenant-owned target still requires forced RLS. A shared target still requires
explicit all-tenant visibility with RLS disabled and no policies. There are no
write capabilities, including inside a write callback. PostgreSQL table triggers
respond to writes rather than SELECT; its referential actions perform updates or
deletes on the source table when the target changes. That write behavior cannot
be reached through this read-only plan. See PostgreSQL's
[trigger behavior](https://www.postgresql.org/docs/18/trigger-definition.html)
and [foreign-key actions](https://www.postgresql.org/docs/18/sql-createtable.html).

Application migrations retain and independently validate the external schema and
its business constraints. This boundary permits incremental read-only adoption;
it does not verify the external writer, prove its referential integrity, enable
relationships to unmapped targets, or provide an administrative write API. Moving
a source into this model requires declaring and attesting its references. Making
the target writable requires restoring full reference closure and verifying its
write contracts first.

The option participates in the mapping fingerprint and adds
`externalIncomingReferences: true` to the entity manifest. Omission and explicit
`false` produce identical fingerprints and metadata. Generated-plan ABI 5 adds
`PgReadOnlyEntityPlan.externalIncomingReferences()`; recompile older mappings
with the matching processor/runtime. The choice is captured once only after the
runtime verifies absence of mutation capabilities.
