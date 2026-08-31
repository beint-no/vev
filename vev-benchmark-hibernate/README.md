# Hibernate benchmark lane

This unpublished module is the isolated Hibernate ORM `8.0.0.Beta1` comparison lane. It uses JMH 1.37, the Jakarta Persistence `4.0.0-M6` API, and Hibernate's `StatelessSession`, which implements the milestone `EntityAgent` contract. It never creates an `EntityManager`.

The lane is experimental evidence for one synthetic workload. It contains no performance result or general comparison claim.

## Database setup

Use a disposable PostgreSQL 18.x server. By default, the setup task connects to `jdbc:postgresql://127.0.0.1:5432/postgres` as the local `postgres` user with an empty password. These admin overrides are available:

- `VEV_BENCH_ADMIN_JDBC_URL`
- `VEV_BENCH_ADMIN_USER`
- `VEV_BENCH_ADMIN_PASSWORD`

The setup is destructive inside its owned fixture: it reconciles two fixed roles, rebuilds the `vev_bench` schema, replaces the public model-fingerprint table, and reseeds the synthetic rows. The application role's fixed synthetic password is `vev_bench_password`. Never point the setup task at a shared or production PostgreSQL server.

Destructive setup fails closed behind two independent gates:

- Without an override, `VEV_BENCH_ADMIN_JDBC_URL` must use the numeric loopback literal `127.0.0.1` or `[::1]`, an optional valid port, and a simple database name with no URL parameters. `localhost` is intentionally not accepted because it requires name resolution.
- The `vev_bench` database, `vev_bench_owner` role, and `vev_bench_app` role must each carry the exact PostgreSQL object comment `vev-owned-fixture:vev_bench:v1` before any benchmark setup `ALTER` or `DROP`. A fresh setup adds these comments immediately when it creates the objects. A legacy, missing, or differently marked object is rejected before destructive DDL.

Remote destructive setup requires the exact opt-in `VEV_BENCH_ALLOW_REMOTE_DESTRUCTIVE_SETUP=vev_bench`. The opted-in administrator URL must still be one valid single-host PostgreSQL JDBC target with a simple database path and no query or fragment; multi-host failover URLs, user information, invalid ports, missing database names, parameters, and malformed or non-PostgreSQL URLs are rejected. This only relaxes the loopback-address gate; it never relaxes or creates a missing ownership marker on an existing object. Inspect and resolve any pre-existing unmarked objects manually rather than treating the opt-in as adoption authority.

```shell
./gradlew :vev-benchmark-hibernate:prepareBenchmarkData
```

The runtime JDBC URL is derived from the admin URL by replacing its database name with `vev_bench`. For an existing prepared database, the runtime connection may instead be overridden explicitly:

- `VEV_BENCH_JDBC_URL`
- `VEV_BENCH_USER`
- `VEV_BENCH_PASSWORD`

## Parity fixture

Both lanes use exactly 10,000 synthetic rows for tenant 7 in this table:

```sql
CREATE TABLE vev_bench.account (
    id bigint NOT NULL,
    tenant_id integer NOT NULL,
    version bigint NOT NULL,
    email varchar(255) NOT NULL,
    balance numeric(19,4) NOT NULL,
    active boolean NOT NULL,
    PRIMARY KEY (tenant_id, id)
);
```

Before JMH begins, the lane verifies PostgreSQL 18.x, the complete JDBC dataset checksum, Hibernate result checksums, read-only connections, serializable isolation, network and database deadlines, database identity, and pool dimensions. Every transaction installs and reads back `pg_catalog` in a dedicated first parse-safe statement before the UTF-8 and context statements, and that first result rejects a connection that has already allocated a temporary schema. Built-in cast targets in the envelope are explicitly qualified under `pg_catalog`. The fixture enables and forces a tenant row-level security policy on `tenant_id`; the 256-result scan requests and fetches one additional sentinel row, and Hibernate's second-level and query caches are disabled.

## Workloads

Every measured invocation opens a `StatelessSession`, acquires and holds one connection, establishes a fresh serializable read-only transaction boundary, consumes a deterministic checksum, commits, and closes the agent. The boundary mirrors Vev's safety envelope: it rolls back any prior JDBC transaction, applies and verifies a 180-second network timeout, verifies UTF-8 in its own roundtrip, then installs and reads back tenant 7, 30-second statement and lock timeouts, a 120-second transaction timeout, `pg_catalog` search path, row security, synchronous commit, UTC, and one exact indexed model fingerprint. Before commit it re-attests the fingerprint, network deadline, encodings, GUCs, isolation, read-only status, database OID and system identifier, server endpoint and start time, recovery status, roles, and absence of a temporary schema. HikariCP 7.1.0 uses a fixed minimum and maximum of eight connections. JDBC fetch size is 256, JDBC batching is disabled, and second-level and query caches are disabled.

The JMH class contains:

- transaction and tenant-context boundary only;
- `EntityAgent.find()` for `(id=7777, tenant_id=7)`;
- `EntityAgent.findMultiple()` for ids 1 through 31 plus missing id 20001;
- `EntityAgent.findMultiple()` for ids 1 through 255 plus missing id 20001;
- an ordered, tenant-qualified scan with `setMaxResults(257)` over a known-larger 10,000-row dataset, materializing all 257 results and consuming the extra list entry as `hasMore` evidence.

`findMultiple()` is intentional: the Jakarta 4 contract preserves input positions and returns `null` for the missing id, whereas `getMultiple()` must throw when any requested row is absent.

List or run the benchmarks through the isolated runtime classpath:

```shell
./gradlew :vev-benchmark-hibernate:run --args='-l'
./gradlew :vev-benchmark-hibernate:run --args='HibernateEntityAgentBenchmark -rf json -rff build/hibernate-jmh.json'
```

Counterbalanced comparisons must run the Vev and Hibernate processes in alternating fresh forks with identical JDK, PostgreSQL, schema, rows, indexes, HikariCP configuration, serializable transaction envelope, fetch size, thread count, warmup, measurement, and profiler settings. Preserve the raw JMH output and environment metadata; do not publish a favorable summary without the complete comparison evidence.
