# Vev

> [!CAUTION]
> **EXPERIMENTAL** — Vev is a pre-1.0 research project. It is not production-ready, API-stable, or Jakarta Persistence TCK compliant.

Vev explores an ahead-of-time, PostgreSQL-first persistence model for modern JVM applications. Instead of recreating a stateful ORM session, Vev interprets a deliberately small selection of Jakarta Persistence annotations as source metadata and compiles them into immutable mapping metadata and direct bind/read plans used by a stateless persistence API. Unsupported mappings are intended to fail compilation rather than acquire approximate runtime behavior.

Safety and performance are design goals, not established product claims. The repository currently demonstrates a narrow implementation and synthetic test/benchmark fixtures; it does not establish that Vev is safer, faster, or otherwise superior to Hibernate or another persistence implementation.

The current baseline is:

- JDK 26, with no compatibility target for older JDKs;
- exactly PostgreSQL major 18; the current CI fixture is PostgreSQL 18.4;
- the preview `jakarta.persistence:jakarta.persistence-api:4.0.0-M6` contract;
- a closed, documented selection of Jakarta Persistence annotations reused as Vev metadata, not a conforming subset of the provider specification.

Jakarta Persistence 4.0.0-M6 is a milestone release. Vev's annotation profile and API may change as the specification changes. Do not infer compatibility with a final Jakarta Persistence 4 release.

The Jakarta Persistence 4 [`@Entity` contract](https://jakarta.ee/specifications/persistence/4.0/apidocs/jakarta.persistence/jakarta/persistence/entity) forbids records as entities. Vev deliberately requires immutable records and therefore uses selected Jakarta annotations as **nonconforming source metadata**. A Vev record is not a Jakarta entity, cannot simultaneously be managed by Hibernate or another Jakarta provider, and requires a separate or rewritten record model during migration.

## Design direction

The Jakarta-facing experiment is shaped like the preview [`EntityAgent`](https://jakarta.ee/specifications/persistence/4.0/apidocs/jakarta.persistence/jakarta/persistence/entityagent), which performs operations without a persistence context and returns detached entities. The current `vev-jakarta4` adapter is deliberately nonconforming: it implements only selected operations and does not yet honor every inherited option, property, lifecycle, or exception contract. It must not be treated as a Jakarta provider implementation. Its selected surface maps onto the native `TransactionExecutor`, lexical `ReadTx`/`WriteTx`, and explicit `ReadEntities`/`WriteEntities` contracts. Generated per-entity plans contain binders, row readers, and mapping metadata; the PostgreSQL runtime constructs and caches the only permitted SQL shapes from that validated metadata.

The current immutable-record facade supports detached `find`/`get`, ordered multiple lookups, assigned-value insert, homogeneous insert batches, and single version-qualified delete. Insert is admitted only under the closed profile: generated/default values, triggers, and rewrite rules are forbidden, and the returned database row must exactly equal the input snapshot or the transaction cannot commit. Jakarta's `void` update, upsert, and refresh operations are rejected before SQL because an immutable snapshot cannot be synchronized in place. Non-empty Jakarta batch delete is also rejected before SQL because its ordered recoverable-failure contract can expose partial effects. Use the native typed mutation APIs when replacement state or exhaustive batch outcomes must be returned.

Consequently, the initial design has no transparent dirty checking, lazy entity proxies, session identity map, or implicit cascade graph. Loaded entities are detached ordinary objects. Transaction scope, tenant scope, and writes remain visible in application control flow. The current adapter is available only inside a lexical transaction callback, closes automatically, and must not escape or cross a thread boundary.

```java
@Entity
@Table(name = "catalog_item", schema = "catalog")
public record CatalogItem(
    @Id @Column(name = "id", nullable = false) UUID id,
    @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenantId,
    @Version @Column(name = "version", nullable = false) long version,
    @Column(name = "sku", nullable = false, length = 64) String sku
) {}

@VevModel(entities = CatalogItem.class)
public final class CatalogModel {}

var tenantAuthority = CatalogModelVev.newTenantAuthority();
var vev = new PgVev<>(dataSource, CatalogModelVev.POSTGRES, tenantAuthority);
var tenant = tenantAuthority.scope(42);
var persisted = vev.write(tenant, tx ->
    tx.entities().insert(CatalogItemVev.INSTANCE, item));
```

`TenantAuthority<Model,T>` is an application capability, not a value factory to recreate at each call site. The generated factory binds its phantom model type and mapping fingerprint at compile time. `PgVev` exclusively reserves the capability, verifies one database endpoint, and claims it only after verification succeeds. The authority can then mint scopes for that runtime and cannot be reused for another `PgVev`. A cross-model scope does not type-check; erased or foreign scopes are rejected before connection acquisition.

The migration must provide the matching schema-qualified table, exact `(tenant, id)` primary key, forced row-level security policy, exact column-level mutation grants, and a fingerprint row matching the generated model identity. Vev generates no migration or schema manifest. This is illustrative source, not a compatibility promise.

## Repository layout

| Module | Responsibility |
|---|---|
| `vev-core` | Dialect-neutral transaction and entity contracts |
| `vev-postgres` | PostgreSQL 18 execution and schema behavior (18.4 currently verified) |
| `vev-processor` | Ahead-of-time mapping validation and source generation |
| `vev-jakarta4` | Deliberately nonconforming Jakarta Persistence 4.0 milestone `EntityAgent`-shaped facade and annotation adapter |
| `vev-integration-tests` | Synthetic PostgreSQL integration fixtures |
| `vev-benchmark-vev` | Isolated Vev JMH workloads |
| `vev-benchmark-hibernate` | Isolated Hibernate ORM 8.0.0.Beta1 JMH baseline |

No artifact is currently published. To verify the source tree with JDK 26:

```shell
./gradlew clean check integrationTest
```

Integration verification requires an administrator connection to disposable PostgreSQL 18. Tests and examples use synthetic data only.

The reviewed local A–B–B–A evidence bundle, including raw JMH JSON and checksums, is preserved under [`benchmark-results`](benchmark-results/final-4b2b23f10d4352d86834c4f43993d1288ba82020/report.md). It is evidence for one machine and five read-only workloads, not a general performance claim.

CI runs the complete verification on exact Temurin and Oracle builds of the common JDK `26.0.2+10` baseline, plus separate floating JDK 26 lanes that request each vendor's current security update. Every lane records and validates the installed runtime identity.

## Safety boundary

Vev is not a drop-in Hibernate replacement. Some annotation spellings are reusable, but a Hibernate/Jakarta entity class is not: the current Vev profile requires a separate immutable record that no Jakarta provider may manage as an entity. Annotation reuse does not imply entity-model, lifecycle, transaction, query, locking, or caching compatibility. Hibernate-specific annotations are not part of the initial public profile unless a document names them explicitly.

Read the boundaries before experimenting:

- [Architecture](docs/architecture.md)
- [Supported profile and rejection matrix](docs/supported-profile.md)
- [AOT and schema pipeline](docs/aot-schema-pipeline.md)
- [Tenant threat model](docs/tenant-threat-model.md)
- [Migration expectations](docs/migration.md)
- [Benchmark policy](docs/benchmark-policy.md)
- [Limitations](docs/limitations.md)
- [Roadmap](docs/roadmap.md)

Security reports should follow [SECURITY.md](SECURITY.md). Contributions should follow [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Apache License 2.0. See [LICENSE](LICENSE).
