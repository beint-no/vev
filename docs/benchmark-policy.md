# Benchmark policy

> Benchmarks are experimental engineering evidence, not product claims.

Vev maintains separate JMH modules for the Vev workload and the comparison runtime. Isolation prevents Hibernate ORM from becoming a Vev runtime dependency and makes each process's classpath auditable.

The preview comparison baseline is Hibernate ORM `8.0.0.Beta1`, which itself is prerelease software. The comparison module explicitly resolves Jakarta Persistence `4.0.0-M6`; this is an experimental milestone/beta pairing, not evidence of compatibility between final releases. Results must name both exact versions. They do not establish performance against a final Hibernate ORM 8 release or a production-tuned Hibernate application.

## Publication requirements

A benchmark report is publishable only when it records:

- Vev, benchmark, and comparison commit identifiers;
- JDK vendor and exact JDK 26 version;
- operating system, architecture, CPU, memory, and power mode;
- PostgreSQL version, JDBC driver version, and database location;
- Jakarta Persistence and Hibernate versions, including milestone/beta labels;
- synthetic schema, row count, seed, data distribution, and indexes;
- complete JMH command, forks, warmups, measurements, threads, mode, and profilers;
- pool size, transaction boundary, fetch size, timeout, statement-cache policy, and cache warm state;
- execution order, sample count, confidence/error, median, p95, and allocation per operation;
- raw machine-readable JMH output and its checksum.

Reports must not contain production-derived data, host names, credentials, tenant identifiers, or private application names.

## Comparison rules

Equivalent workloads must:

1. use the same synthetic rows, schema, indexes, PostgreSQL server, driver, and connection-pool settings;
2. return and consume equivalent data so dead-code elimination cannot change the result;
3. use comparable transaction and state-management semantics;
4. apply equivalent fetch sizes and transaction safety envelopes, including isolation, read-only mode, deadlines, encoding, tenant context, model fingerprint, synchronous commit, and pre-commit attestation;
5. run in separate fresh JVM forks with the requested runtime only;
6. distinguish bootstrap, steady-state reads, writes, and allocation measurements;
7. verify result parity before timing begins.

Batch-update comparisons use a dedicated table containing exactly 32 tenant-scoped rows and no secondary indexes. Each measured trial recreates the owned synthetic fixture and starts those rows at version zero, each invocation updates all 32 rows and commits, and teardown verifies the complete persisted state before resetting it. The payload alternates between two exact values by resulting-version parity. Benchmark code must never add rows during timing.

Mutating workloads use fixed-count `SingleShotTime`, not a time-based throughput loop. Otherwise the faster lane performs more updates during the same interval and reaches a different version height and MVCC state. One reported operation is one committed 32-row batch; it must not be divided with `@OperationsPerInvocation(32)`.

Every batch-update result must validate order, id, tenant, expected version, resulting version, and every returned or provider-mutated value before mixing all of them into the same checksum shape. Setup must additionally compare provider-visible state with rows read inside a transaction that is then rolled back.

Reports must prominently disclose the unavoidable SQL and API asymmetry. Vev's native bounded API performs one typed-array statement with an all-member preflight and ordered full-row `RETURNING`. Hibernate ORM 8.0.0.Beta1 `EntityAgent.updateMultiple()` mutates the input objects, returns `void`, and sends 32 per-row optimistic updates as one explicit JDBC batch; it detects stale members from JDBC update counts and returns no database snapshots. Automatic factory-level batching remains disabled because the stateless `updateMultiple()` operation controls its own batch. Vev's raw set-based JDBC method is a diagnostic lower bound, not an ORM competitor. The comparison does not imply that Vev's Jakarta-shaped facade implements `updateMultiple()`.

## Destructive fixture safety

Both benchmark lanes rebuild the same disposable fixture and must apply the same setup guard. The administrator URL is limited by default to a simple numeric `127.0.0.1` or `[::1]` JDBC target. Another address requires the exact `VEV_BENCH_ALLOW_REMOTE_DESTRUCTIVE_SETUP=vev_bench` opt-in and must still parse as one single-host PostgreSQL JDBC target with a valid port, simple database path, and no query or fragment. The opt-in cannot authorize multi-host, user-info, missing-database, parameterized, fragmented, malformed, or non-PostgreSQL administrator URLs.

The measured JDBC URL must exactly equal the `vev_bench` URL derived from that guarded administrator URL. `VEV_BENCH_JDBC_URL` may spell that same value explicitly, but cannot redirect a lane to another host, port, or database. This makes per-trial recreation, fill factor, indexes, seed state, and server identity common to the fixture and measured connections.

Address opt-in is never ownership opt-in. Before setup alters or drops fixture state, the `vev_bench` database and both fixed roles must each have the exact object comment `vev-owned-fixture:vev_bench:v1`. Newly created objects are marked immediately; pre-existing objects with a missing or different comment fail closed, even when remote setup is explicitly enabled. The database connection used for schema DDL must also re-attest the current database name and all three markers.

Each measured pool must establish `search_path = pg_catalog` when a physical connection is created, before a benchmark invocation can borrow it, and must use UTF-8 transport. Vev verifies that pgjdbc reports this exact session baseline and fails a retained temporary-schema OID; it does not change `search_path` inside a measured transaction because that change invalidates pgjdbc's prepared-query cache. The Hibernate lane must use the same physical-connection baseline for comparison, even if its transaction envelope performs additional read-back checks. Built-in cast targets must remain explicitly qualified under `pg_catalog`. Adversarial connection-state tests belong in integration verification, outside timing.

A future Vev `EntityAgent`-shaped facade workload should first be compared with Hibernate's implementation of the same milestone operations where available. Such a workload comparison would cover selected outcomes, not establish that the Vev facade conforms to the Jakarta contract. In Hibernate ORM 8.0.0.Beta1, `SessionFactory.createEntityAgent()` returns Hibernate's `StatelessSession` implementation; reports should state both the standard entry point and provider type. A separately opened stateless or managed-session comparison is a distinct workload. If one side uses batching, caching, generated keys, or prepared-statement reuse, the other side must use the closest equivalent or the asymmetry must be prominent.

The current Vev lane measures the native lexical transaction/generated-plan API, while the Hibernate lane obtains `StatelessSession` through `createEntityAgent()` and uses provider selection queries for bounded reads. Vev's immutable generated query objects are constructed once outside timing and reused; a Hibernate selection query is necessarily created and bound against the newly opened agent during each invocation. Both measured transaction envelopes establish and re-attest the same serializable PostgreSQL safety context. This is a comparison of equivalent stateless outcomes where parity checks pass, not a claim that both lanes exercise the same public API, query lifecycle, SQL strategy, object identifier representation, or connection-management implementation.

Reports for the current lanes must also disclose that Vev reuses its runtime and preconstructed query objects while Hibernate opens and closes an agent and creates/binds each selection query per invocation; Vev maps an immutable record with a tenant-scoped scalar identifier while Hibernate maps a mutable entity with a composite `IdClass`; and, for every bounded page with a sentinel, Vev materializes only the public limit then advances the result set once while Hibernate's list query materializes the sentinel entity too. Those differences are properties of the compared APIs and implementations, not noise to remove from the result.

The [current A–B–B–A bundle](../benchmark-results/final-b0b026d19959b4ca848174e8f2ab4c909363d208/report.md) exercises `@VevIndex` equality queries and the all-or-nothing 32-row update on the frozen implementation commit. Its latency comparison is rejected: post-A2 telemetry showed severe unrelated CPU and storage activity, and without comparable pre-run or in-run telemetry host interference cannot be excluded. Every sample is retained without filtering. Stable normalized-allocation measurements may be interpreted only for the exact published workloads and environment; the campaign supports no latency conclusion. It does not cover typed-array `insertMultiple`. The [earlier bundle](../benchmark-results/final-4b2b23f10d4352d86834c4f43993d1288ba82020/report.md) remains historical read-only evidence and must not be attributed to the current tranche. Hibernate ORM `8.0.0.Beta1` remains a prerelease baseline, not evidence about a final Hibernate ORM 8 release.

## Interpretation

Do not describe Vev as faster, lower-allocation, or more scalable from one machine or one entity shape. Report absolute measurements and uncertainty first. A regression in any representative workload remains part of the result; it must not be hidden behind a favorable aggregate.

No performance number belongs in the main README until it is reproduced, reviewed, tied to raw output, and bounded to the exact workload. Even then, it is evidence rather than a universal claim.
