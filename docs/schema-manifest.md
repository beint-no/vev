# Generated schema manifest

Each successful `@VevModel` compilation writes UTF-8 JSON to
`META-INF/vev/<qualified-model-name>.schema.json` in the compiler's class output.
Normal JAR packaging includes the resource. The generated registry exposes its
classpath-relative path as `SCHEMA_MANIFEST`; no runtime scan is required.

The manifest is produced from the same validated mapping model as the generated
Java plans. Entity order is lexical by qualified name; column order follows the
record declaration. It contains no build timestamp, local path, host identity,
credential, or application row. Repeated compilations produce identical bytes.
A rejected mapping emits no manifest for that model. Clean builds remain necessary
when a model is renamed or removed, since javac cannot remove old output resources.

Format version 1 identifies itself with `format: "vev-schema"`,
`formatVersion: 1`, `postgresqlMajor: 18`, and `profile: "tenant-record-v1"`.
Consumers must reject unknown format versions or profiles. The resource includes:

- the model's qualified name and the exact generated mapping fingerprint;
- entity Java types, schema/table identifiers, append-only semantics, and maximum row counts;
- ordered columns with boxed Java type, PostgreSQL type, nullability, structural
  role, string code-point or binary byte length, decimal precision, scale, and sorted enum names;
- ordered primary-key and secondary-index columns, plus named tenant-scoped unique constraints with distinct-null and immediate enforcement semantics;
- explicit scalar references with composite source/target columns, exact target relation, and immediate non-cascading enforcement;
- named exact check expressions and generated binary bounds with `kind: "BINARY_MAXIMUM"`, column, and maximum bytes;
- assigned/identity identifier strategy and the required owned-sequence contract for identity creation;
- required enabled/forced tenant row security and its transaction-local setting;
- the exact application insert/update column sets and the absence of delete access.

The profile also requires the catalog restrictions in
[the schema pipeline](aot-schema-pipeline.md#3-schema-verification), including
absence of arbitrary column defaults, undeclared identity generation, extra constraints, and undeclared
indexes. The manifest is a reviewable description of generated expectations, not a
DDL script or a complete description of a live database. It deliberately does not
choose deployment role names, grant credentials, install a fingerprint, or run
migrations. Existing `PgVev` startup attestation remains authoritative for the live
catalog and privileges. There is no automated migration comparison task yet.

Keep constraints which protect business correctness. If a required foreign key,
check, unique constraint, or identity generator is outside the accepted profile,
expand and verify Vev's contract before migrating that table.
