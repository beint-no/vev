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

The runtime JDBC URL is derived from the admin URL by replacing its database name with `vev_bench`. `VEV_BENCH_JDBC_URL` may repeat that exact derived value, but a different value is rejected so fixture recreation and measurement cannot silently target different databases:

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

CREATE INDEX account_email_vev_idx
    ON vev_bench.account USING btree (tenant_id, email, id);
CREATE INDEX account_active_vev_idx
    ON vev_bench.account USING btree (tenant_id, active, id);

CREATE TABLE vev_bench.update_account (
    id bigint NOT NULL,
    tenant_id integer NOT NULL,
    version bigint NOT NULL,
    balance numeric(19,4) NOT NULL,
    PRIMARY KEY (tenant_id, id)
) WITH (fillfactor = 50);
```

`update_account` contains exactly 32 rows for tenant 7. Its balance alternates between two exact values according to version parity, so each measured update changes data without cumulative arithmetic drift. The owned fixture is recreated with version-zero rows before every measured write trial, and teardown verifies then resets the state. There are no secondary indexes, making repeated fixed-row updates HOT-eligible and preventing a faster implementation from growing indexed history. The benchmark never inserts rows during timing.

Before JMH begins, the lane verifies PostgreSQL 18.x, the complete JDBC dataset checksum, Hibernate result checksums, read-only connections, serializable isolation, network and database deadlines, database identity, and pool dimensions. Both measured lanes use dedicated Hikari pools initialized once with the exact `pg_catalog` search path. Every transaction then reads the pgjdbc startup/session parameter status without issuing a GUC-changing statement, rejects a session that has retained a temporary schema, and applies the remaining tenant, deadline, row-security, synchronous-commit, and time-zone GUCs transaction-locally. This preserves pgjdbc's prepared-query cache across transactions while retaining the same fail-closed session baseline in Vev, Hibernate, and the raw JDBC envelope. Built-in cast targets in the envelope are explicitly qualified under `pg_catalog`. The fixture enables and forces a tenant row-level security policy on `tenant_id`; every bounded workload requests and fetches one additional sentinel row, and Hibernate's second-level and query caches are disabled.

## Workloads

Every measured invocation opens a `StatelessSession`, acquires and holds one connection, establishes a fresh serializable transaction boundary, consumes a deterministic checksum, commits, and closes the agent. Read workloads require read-only mode; the batch-update workload requires read-write mode. The boundary mirrors Vev's safety envelope: it rolls back any prior JDBC transaction, applies and verifies a 180-second network timeout, checks the immutable `pg_catalog`/UTF-8 pgjdbc baseline without changing it, rejects retained temporary schemas, then installs and reads back tenant 7, 30-second statement and lock timeouts, a 120-second transaction timeout, row security, synchronous commit, UTC, and one exact indexed model fingerprint. Before commit it re-attests the fingerprint, network deadline, encodings, GUCs, isolation, read-only status, database OID and system identifier, server endpoint and start time, recovery status, roles, and absence of a temporary schema. HikariCP 7.1.0 uses a fixed minimum and maximum of eight connections. JDBC fetch size is 256, automatic JDBC batching is disabled, and second-level and query caches are disabled.

The JMH class contains:

- transaction and tenant-context boundary only;
- `EntityAgent.find()` for `(id=7777, tenant_id=7)`;
- `EntityAgent.findMultiple()` for ids 1 through 31 plus missing id 20001;
- `EntityAgent.findMultiple()` for ids 1 through 255 plus missing id 20001;
- an ordered, tenant-qualified scan with `setMaxResults(257)` over a known-larger 10,000-row dataset, materializing all 257 results and consuming the extra list entry as `hasMore` evidence.
- a typed HQL equality lookup for `email='account-7777@example.test'`, requesting two rows for a one-row result and consuming the complete entity plus `hasMore=false`;
- a typed HQL equality lookup for `active=true`, requesting and materializing 33 rows, consuming 32 complete entities plus the sentinel as `hasMore=true`.
- `EntityAgent.updateMultiple()` over the fixed 32-row update table. Hibernate ORM 8.0.0.Beta1 temporarily sets the stateless session batch size to the list size, emits 32 optimistic row updates as one explicit JDBC batch, executes it, and restores the prior size. The workload validates every mutated entity's ordered id, tenant, exact balance, and one-step version transition before committing.

`findMultiple()` is intentional: the Jakarta 4 contract preserves input positions and returns `null` for the missing id, whereas `getMultiple()` must throw when any requested row is absent.

List or run the benchmarks through the isolated runtime classpath:

```shell
./gradlew :vev-benchmark-hibernate:run --args='-l'
./gradlew :vev-benchmark-hibernate:run --args='HibernateEntityAgentBenchmark -rf json -rff build/hibernate-jmh.json'
./gradlew :vev-benchmark-hibernate:run --args='HibernateBatchUpdateBenchmark -rf json -rff build/hibernate-update-jmh.json'
```

The write class uses fixed-count `SingleShotTime` iterations and reports microseconds per committed 32-row batch. This is intentional: a time-based loop would let the faster lane create more versions and MVCC churn before later samples. Setup performs an untimed update/readback/rollback probe, and teardown verifies the exact persisted version and payload state.

This is an outcome comparison, not identical SQL. Vev executes one typed-array statement with whole-batch preflight and ordered full-row `RETURNING`; Hibernate executes 32 per-row optimistic updates in one JDBC batch, determines success from update counts, mutates caller-owned entities, and returns no database snapshots. Vev uses its bounded native `WriteEntities.updateMultiple` API because its experimental Jakarta-shaped facade does not expose this operation. The Vev module also includes a raw JDBC control that mirrors Vev's set-based SQL; it is a lower-bound diagnostic, not a third ORM implementation.

Counterbalanced comparisons must run the Vev and Hibernate processes in alternating fresh forks with identical JDK, PostgreSQL, schema, rows, indexes, HikariCP configuration, serializable transaction envelope, fetch size, thread count, warmup, measurement, and profiler settings. Preserve the raw JMH output and environment metadata; do not publish a favorable summary without the complete comparison evidence.
