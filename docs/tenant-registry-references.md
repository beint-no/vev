# Tenant-registry references

`@VevTenantReference` declares the actual tenant key's foreign key to an external
registry. The registry is not a generated entity and grants no read, write, or
administrative capability through Vev.

```java
@VevTenantReference(name = "entry_tenant_fk", schema = "registry",
    table = "tenant", column = "id", onDelete = VevTenantReference.OnDelete.CASCADE)
@TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId
```

The migration must supply that exact constraint on `(tenant_id)`, referencing the
registry's single-column primary key. The key types, bounds, and collations must
match. Enforcement is validated, immediate, nondeferrable, local, and MATCH SIMPLE.
ON UPDATE is always NO ACTION. ON DELETE defaults to NO ACTION; CASCADE requires
an explicit annotation. Composite registry keys, SET NULL/DEFAULT, other component
roles, and mapping the registry itself in the same model are rejected. Ordinary
`@VevReference` contracts retain their existing closed-model and action rules.

All declarations in one model must identify the same registry and tenant-key
shape. Existing mappings without a registry declaration remain valid; their
writes do not acquire a registry membership check. Minting a `TenantScope` never
queries the registry and is not an authorization or membership lookup. Trusted
application authentication still determines which scopes may be minted.

Bootstrap verifies the external permanent, logged, nonpartitioned, noninherited
builtin heap and exact builtin primary key once per model. The Vev role must not
own the registry, belong to its owning role, create objects in its schema, or hold
any table or column data privilege on the registry, including SELECT. Unrelated
registry columns and defaults are outside the mapped model and are not read or
executed. A separate administrator owns registry maintenance.

PostgreSQL foreign-key checks bypass row security; Vev does not depend on the
registry's read policies to decide whether a tenant key exists. This is PostgreSQL's
[integrity contract](https://www.postgresql.org/docs/18/ddl-rowsecurity.html).
The synthetic suite verifies writes with a throwing registry policy and no data
grants. Missing registry keys fail the database write and poison the whole lexical
transaction, including earlier writes if the caller catches the failure.

Declared CASCADE permits a separate administrator to delete a tenant's referencing
rows through PostgreSQL. It is not a Vev deletion API or an optimistic per-row
operation. Registry deletion, tenant retirement, backups, and identifier non-reuse
remain explicit administrative responsibilities. NO ACTION references can prevent
that deletion; a constraint failure leaves all affected rows intact.

The manifest records `targetKind: "TENANT_REGISTRY"`, the exact source and target
columns, action, and absence of registry data privileges. Changes affect the model
fingerprint. Generated-plan ABI 7 captures this metadata once; regenerate mappings
with the same processor and runtime release.
