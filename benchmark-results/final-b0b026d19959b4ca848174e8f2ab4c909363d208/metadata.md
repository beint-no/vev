# Current Vev / Hibernate 8 benchmark metadata

## Evidence identity

- Frozen implementation commit: `b0b026d19959b4ca848174e8f2ab4c909363d208`.
- Aggregate of tracked file hashes before evidence files: `3e3c9e2205c3275fdae997b194c81fef76663494ca6a3376ca8c27faa65e3496`.
- Aggregate command: `git ls-files -z | sort -z | xargs -0 shasum -a 256 | shasum -a 256`.
- The implementation worktree was clean when the frozen commit was created. The final release gate ran against the fully staged publication tree. `benchmark-results/` is ignored during collection, then reviewed and force-added.
- Evidence directory: `benchmark-results/final-b0b026d19959b4ca848174e8f2ab4c909363d208`.
- Local APFS birth and modification times, recorded here because JMH JSON does not carry process timestamps: A1 2026-08-31T08:39:22+0200–08:40:54+0200; B1 08:41:11+0200–08:42:44+0200; B2 08:46:35+0200–08:48:09+0200; A2 08:48:21+0200–08:51:04+0200.

## Protocol and exact commands

The four processes ran in A–B–B–A order. There was an analysis interval between B1 and B2. JMH forks, not the Gradle launcher, are the measurement JVMs.

```shell
./gradlew :vev-benchmark-vev:run --no-build-cache --rerun-tasks --console=plain --args='(VevBenchmark.(indexedEmail|indexedActive32)|VevBatchUpdateBenchmark.(updateMultiple32|rawUpdateMultiple32)) -t 1 -prof gc -foe true -rf json -rff /Users/gregtaube/r/wt-vev-modern-query-core/benchmark-results/final-b0b026d19959b4ca848174e8f2ab4c909363d208/a1-vev.json'
./gradlew :vev-benchmark-hibernate:run --no-build-cache --rerun-tasks --console=plain --args='(HibernateEntityAgentBenchmark.(indexedEmail|indexedActive32)|HibernateBatchUpdateBenchmark.updateMultiple32) -t 1 -prof gc -foe true -rf json -rff /Users/gregtaube/r/wt-vev-modern-query-core/benchmark-results/final-b0b026d19959b4ca848174e8f2ab4c909363d208/b1-hibernate.json'
./gradlew :vev-benchmark-hibernate:run --no-build-cache --rerun-tasks --console=plain --args='(HibernateEntityAgentBenchmark.(indexedEmail|indexedActive32)|HibernateBatchUpdateBenchmark.updateMultiple32) -t 1 -prof gc -foe true -rf json -rff /Users/gregtaube/r/wt-vev-modern-query-core/benchmark-results/final-b0b026d19959b4ca848174e8f2ab4c909363d208/b2-hibernate.json'
./gradlew :vev-benchmark-vev:run --no-build-cache --rerun-tasks --console=plain --args='(VevBenchmark.(indexedEmail|indexedActive32)|VevBatchUpdateBenchmark.(updateMultiple32|rawUpdateMultiple32)) -t 1 -prof gc -foe true -rf json -rff /Users/gregtaube/r/wt-vev-modern-query-core/benchmark-results/final-b0b026d19959b4ca848174e8f2ab4c909363d208/a2-vev.json'
```

One launch before A1 used a relative result path. JMH rejected it before trial setup because the module working directory did not contain that directory. It produced no measurements or fixture changes; the absolute-path command above then ran A1.

Read benchmarks use annotation-defined average-time mode, five one-second warmup iterations, eight one-second measurement iterations, three forks, and one thread. Each raw read record contains 24 iteration-average samples.

Write benchmarks use annotation-defined `SingleShotTime`, 1,024 warmup invocations, 2,048 measured invocations, three forks, and one thread. One operation is one committed 32-row batch; `@OperationsPerInvocation` is deliberately absent. Each raw write record contains 6,144 operation samples. GC normalized allocation is in bytes per operation.

## Validation gates

- `./gradlew clean check integrationTest --no-build-cache --rerun-tasks --console=plain`: 57 Gradle tasks, successful.
- Unit/processor/benchmark/integration XML reports contain 174 tests, zero failures, zero errors, zero skipped.
- The integration suite includes 54 PostgreSQL tests, including set-based insert/update order, stale/missing atomicity, concurrent-update serialization, rollback poisoning, version overflow, JDBC array cleanup failure, and every temporal array codec.
- The published JPMS consumer compiled from the isolated generated Maven repository.
- Twelve public binary/source/Javadoc JARs were built twice from clean state; every corresponding SHA-256 matched.
- Both benchmark module test suites were rerun uncached after the final benchmark-fairness guard and passed.
- JMH discovered every requested workload. Every fork's setup parity checks and every write teardown state check passed.
- Post-campaign SQL re-attested exact row counts, ranges, sums, RLS, owners, fill factor, indexes, fingerprint, and ownership markers.

## Software

- Java: OpenJDK 26.0.2.1, runtime `26.0.2.1+1-7`, Oracle Corporation build, aarch64.
- Gradle 9.7.1; embedded Kotlin 2.4.0.
- JMH 1.37.
- Hibernate ORM `8.0.0.Beta1`.
- Jakarta Persistence `4.0.0-M6`.
- HikariCP 7.1.0.
- PostgreSQL JDBC 42.7.13.
- PostgreSQL server 18.4 (Homebrew), server version number 180004, aarch64 Apple Darwin.

## Host and interference

- MacBook Pro `Mac17,6`, Apple M5 Max.
- 18 reported physical/logical cores and 64 GiB RAM.
- macOS 26.6.2 build 25G83, arm64.
- AC power, battery charged, AC `powermode 0` as reported by `pmset`.
- No host isolation, CPU affinity, thermal telemetry, background-process suppression, database restart, or cache flush was applied.
- Immediately after A2, uptime was 13 days 1:08 and load averages were 6.28 / 7.30 / 7.30.
- The first post-run process snapshot showed macOS `StorageManagementService`, `MailStorageManagement`, and `ApplicationsStorageExtension` consuming approximately 180% combined CPU. `WindowServer` and `mediaanalysisd` were also active.
- Concurrent `iostat` samples showed approximately 2,500–6,900 IOPS and 13–49 MiB/s. A follow-up still showed `StorageManagementService` active, load near 7, and approximately 7,800–8,000 IOPS at 52–55 MiB/s.
- These services were not killed, suspended, reniced, or otherwise manipulated.
- No equivalent pre-A1 CPU/I/O snapshot exists. Latency is therefore rejected rather than adjusted.

## PostgreSQL and fixture

- Endpoint: local TCP `127.0.0.1:5432`; not in recovery.
- `data_checksums=off`, `fsync=on`, `full_page_writes=on`, `jit=on`, `synchronous_commit=on`, `track_io_timing=off`.
- `shared_buffers=128 MiB`, `effective_cache_size=4 GiB`, `max_connections=100`, `max_parallel_workers_per_gather=2`, `random_page_cost=4`.
- Read table: `vev_bench.account`, exactly 10,000 tenant-7 rows, ids 1–10,000, id sum 50,005,000.
- Read columns are `id bigint`, `tenant_id integer`, `version bigint`, `email varchar(255)`, `balance numeric(19,4)`, and `active boolean`, all non-null, with primary key `(tenant_id,id)`.
- Read data is deterministic and has no random seed: every version is zero; email is `account-{id}@example.test`; balance is `(id % 100000) / 100` at scale four; and `active` is true exactly for even ids. The indexed-email value matches id 7,777 once; indexed-active has 5,000 matches and requests the first 32 plus one sentinel in id order.
- Read indexes: exact primary key `(tenant_id, id)`, exact non-unique B-trees `(tenant_id, email, id)` and `(tenant_id, active, id)`.
- Write table: `vev_bench.update_account`, exactly 32 tenant-7 rows, ids 1–32, id sum 528, no secondary indexes, `fillfactor=50`.
- Write columns are `id bigint`, `tenant_id integer`, `version bigint`, and `balance numeric(19,4)`, all non-null, with primary key `(tenant_id,id)`. The deterministic reset has version zero and balance `(200000 + id) / 100` at scale four; measured transitions alternate the balance base between 100,000 and 200,000 according to resulting-version parity.
- Every write trial recreates the owned fixture with version-zero rows. A measured invocation alternates an exact payload according to resulting-version parity and commits one 32-row update.
- Post-campaign write rows were all reset to version zero; balances ranged from 2000.0100 through 2000.3200.
- Both tables retained forced RLS and owner `vev_bench_owner`.
- Fingerprint: `sha256:7227fca5a880759306c997d7118a47553364860b65292b23d74946c981193e89`.
- Database and fixed roles retained exact ownership marker `vev-owned-fixture:vev_bench:v1`.

The measured JDBC URL in both lanes must exactly equal the `vev_bench` URL derived from the guarded administrator URL. A differing `VEV_BENCH_JDBC_URL` is rejected before setup or timing, so fixture recreation and measurement cannot target different databases.

## Shared execution envelope

Both compared lanes use an eight-connection Hikari pool, `autoCommit=false`, direct pgjdbc, one thread, serializable transactions, synchronous commit, tenant 7, forced RLS, UTC, exact `pg_catalog` search path, UTF-8, no retained temporary schema, exact fingerprint, and pre-commit connection/session/database/role attestation. Shared deadlines are 10 seconds for Hikari connection acquisition and initialization, 30 seconds for statements, 30 seconds for locks, 120 seconds for transactions, and 180 seconds for the JDBC network timeout.

Neither lane overrides pgjdbc 42.7.13 statement preparation or statement-cache properties; both use the identical driver defaults. Hibernate second-level and query caches are disabled; Vev has no corresponding caches. No database or cache flush occurs between processes or trials. Annotation-defined warmups precede every measurement, while fixture, parity, and setup probes execute outside timed benchmark methods. Effective indexed-query fetch sizes are 2 for indexed-email and 33 for indexed-active in both lanes. Updates have no fetch size; 256 is only the unused general default for other read workloads.

For indexed reads, both request a sentinel row and consume equivalent entity/checksum outcomes. Vev maps the public limit and advances once; Hibernate materializes the sentinel. Vev reuses generated query objects; Hibernate creates/binds a typed HQL query on its newly opened agent. Vev uses an immutable record with a tenant-scoped scalar identifier; Hibernate uses a mutable entity with a composite `IdClass`.

For updates, Vev's one guarded typed-array statement and Hibernate's 32 optimistic per-row statements in one explicit JDBC batch have different SQL and assurance semantics. The raw Vev set-based JDBC method is diagnostic only. `report.md` keeps this asymmetry adjacent to the results.
