# Final Vev / Hibernate 8 benchmark metadata

## Evidence identity

- Frozen Git commit: `4b2b23f10d4352d86834c4f43993d1288ba82020`
- Aggregate of all tracked file hashes: `b7d502af2b502a95e2245377d6eeaec7a3554040b19ee64c72f5332da16c0a8a`
- Aggregate command: `git ls-files -z | sort -z | xargs -0 shasum -a 256 | shasum -a 256`
- Git status was clean at the initial gate and every pre/post-process gate. `benchmark-results/` is intentionally ignored.
- Every gate before and after A1, B1, B2, and A2 returned the exact commit and aggregate above. The final post-A2 gate also matched.
- Evidence directory: `benchmark-results/final-4b2b23f10d4352d86834c4f43993d1288ba82020`
- Environment snapshot: 2026-08-30T21:29:35Z. End re-attestation: 2026-08-30T21:39:54Z.
- Measurements from superseded freezes were deleted and are not included in this package.

The tracked-content aggregate is an evidence identity, not a replacement for a signed release artifact or a cryptographic transparency log.

## Protocol

The four JMH processes ran sequentially in the counterbalanced order A1–B1–B2–A2:

1. `01-vev.json` — Vev A1
2. `02-hibernate.json` — Hibernate B1
3. `03-hibernate.json` — Hibernate B2
4. `04-vev.json` — Vev A2

The complete commands were:

```shell
./gradlew :vev-benchmark-vev:run --no-daemon --args='VevBenchmark\.(transactionOnly|findOne|findMultiple32|findMultiple256|boundedScan)$ -wi 6 -i 8 -f 2 -w 750ms -r 750ms -t 1 -prof gc -rf json -rff /Users/gregtaube/r/wt-vev-jakarta4-core/benchmark-results/final-4b2b23f10d4352d86834c4f43993d1288ba82020/01-vev.json'
./gradlew :vev-benchmark-hibernate:run --no-daemon --args='HibernateEntityAgentBenchmark\.(transactionOnly|findOne|findMultiple32|findMultiple256|boundedScan)$ -wi 6 -i 8 -f 2 -w 750ms -r 750ms -t 1 -prof gc -rf json -rff /Users/gregtaube/r/wt-vev-jakarta4-core/benchmark-results/final-4b2b23f10d4352d86834c4f43993d1288ba82020/02-hibernate.json'
./gradlew :vev-benchmark-hibernate:run --no-daemon --args='HibernateEntityAgentBenchmark\.(transactionOnly|findOne|findMultiple32|findMultiple256|boundedScan)$ -wi 6 -i 8 -f 2 -w 750ms -r 750ms -t 1 -prof gc -rf json -rff /Users/gregtaube/r/wt-vev-jakarta4-core/benchmark-results/final-4b2b23f10d4352d86834c4f43993d1288ba82020/03-hibernate.json'
./gradlew :vev-benchmark-vev:run --no-daemon --args='VevBenchmark\.(transactionOnly|findOne|findMultiple32|findMultiple256|boundedScan)$ -wi 6 -i 8 -f 2 -w 750ms -r 750ms -t 1 -prof gc -rf json -rff /Users/gregtaube/r/wt-vev-jakarta4-core/benchmark-results/final-4b2b23f10d4352d86834c4f43993d1288ba82020/04-vev.json'
```

All four used the same settings:

- JMH 1.37, average-time mode
- workloads: `transactionOnly`, `findOne`, `findMultiple32`, `findMultiple256`, `boundedScan`
- 6 warmup iterations × 750 ms
- 8 measurement iterations × 750 ms
- 2 forks
- 1 thread
- GC profiler enabled; normalized allocation reported in B/op
- compiler blackholes auto-detected by JMH
- raw JDBC lower-bound workloads excluded

Every JSON file contains exactly five benchmark records. Every record reports 2 × 8 raw primary measurements, 2 × 8 normalized-allocation measurements, `avgt`, `us/op`, `B/op`, one thread, six warmups, eight measurements, two forks, and 750 ms iteration durations.

Pooled descriptive statistics concatenate the 32 JMH iteration-average samples per lane and workload (two processes × two forks × eight measurements). The median is the ordinary sample median. P95 is nearest rank: sorted element `ceil(0.95 × 32)`, the 31st observation. This p95 is a percentile of iteration averages, not an operation-level tail-latency measurement. Samples are clustered within four forks and two processes. Pooled statistics do not manufacture an inferential confidence interval across the two process replicates.

The JMH `scoreError` values preserved in `run-results.csv` are each process's JMH 99.9% error half-width. They are not independent machine-level confidence intervals and are not used to assert statistical superiority.

## Pre-measurement gates

- `:vev-benchmark-vev:test` was forced to rerun: 42 tests, 0 failures, 0 errors, 0 skipped.
- `:vev-benchmark-hibernate:test` was forced to rerun: 42 tests, 0 failures, 0 errors, 0 skipped.
- `:vev-benchmark-hibernate:prepareBenchmarkData` prepared 10,000 rows and printed combined checksum `4931598488410721866`.
- All five Vev workloads and all five Hibernate workloads passed one real-fork, 50 ms, no-warmup hostile-search-path smokes using runtime URL option `search_path=public,pg_catalog`. Smoke values are excluded from the evidence.
- A preliminary Vev smoke was inadvertently invoked once with the ordinary loopback runtime URL before the hostile URL was applied. It was a single 50 ms, no-warmup, no-profiler smoke; its numbers were discarded. The required hostile Vev and Hibernate smokes then both passed before A1. This may have warmed shared OS/PostgreSQL state, but each timed process used fresh JVM forks and its prescribed warmups, and each Vev trial rebuilt and analyzed its fixture as documented below.

At every fork's trial setup, Vev compared all five framework results with its independently coded raw JDBC path; Hibernate compared all five results with deterministic expected entities/checksums. Each measured invocation computed and returned its result checksum. No setup, shape, missing-row, fingerprint, transaction-envelope, or checksum check failed.

## Software

- Java: OpenJDK 26.0.2.1, build `26.0.2.1+1-7`, aarch64; Gradle identified vendor as Oracle Corporation.
- Gradle 9.7.1; embedded Kotlin 2.4.0.
- JMH 1.37.
- Hibernate ORM `8.0.0.Beta1`.
- Jakarta Persistence `4.0.0-M6`.
- HikariCP 7.1.0.
- PostgreSQL JDBC 42.7.13.
- `psql` client 18.6 (Homebrew).
- PostgreSQL server 18.4 (Homebrew), server version number 180004, compiled for aarch64 Apple Darwin.

## Driver, statement-cache, fetch, and warm-state policy

- Both timed lanes used PostgreSQL JDBC 42.7.13 with the plain runtime URL `jdbc:postgresql://127.0.0.1:5432/vev_bench`; no URL property changed driver caching or preparation.
- The driver jar's defaults are `prepareThreshold=5`, `preparedStatementCacheQueries=256`, `preparedStatementCacheSizeMiB=5`, and `defaultRowFetchSize=0`, all per connection. Hikari adds no separate prepared-statement cache.
- Hibernate sets `hibernate.jdbc.fetch_size=256` and `hibernate.jdbc.batch_size=0`; its scan overrides the fetch/max result count to 257.
- Vev does not set a global fetch size for find or batch lookup. Its batch is one prepared array/`unnest` query rather than a JDBC statement batch. Its scan sets a fetch size of 257 to retrieve 256 mapped entities plus a sentinel.
- Each fork starts in a fresh JVM with a newly created pool/driver cache. Trial setup performs parity verification and may exercise statements before six warmup iterations. By measurement, frequently used statements can cross the driver's preparation threshold on the active connection.
- PostgreSQL and OS caches were not flushed, and PostgreSQL was not restarted between processes. Shared cache state therefore carried through A1–B1–B2–A2. The counterbalance and fork warmups limit but do not eliminate this effect; Vev's per-fork rebuild/`ANALYZE` is a separate disclosed asymmetry. Cache hits, server generic/custom plan selection, and thermal state were not instrumented.

## Host

- MacBook Pro `Mac17,6`, Apple M5 Max.
- 18 reported physical and logical cores; System Information described 6 Super and 12 Performance cores.
- 64 GiB RAM (`68719476736` bytes).
- macOS 26.6.2, build 25G83, arm64.
- AC power throughout; battery 100% and charged at start and end. `pmset` reported AC `powermode 0`; no semantic interpretation is assumed.
- Start uptime/load: 12 days 17:59; load averages 3.87, 3.66, 3.26.
- End uptime/load: 12 days 18:09; load averages 2.80, 3.31, 3.28.
- No dedicated host isolation, CPU pinning, thermal telemetry, or background-process suppression was applied.

## PostgreSQL

- Local loopback endpoint `127.0.0.1:5432`; database `vev_bench`; not in recovery.
- `data_checksums=off`
- `effective_cache_size=4 GiB`
- `fsync=on`
- `full_page_writes=on`
- `jit=on`
- `max_connections=100`
- `max_parallel_workers_per_gather=2`
- `random_page_cost=4`
- `shared_buffers=128 MiB`
- `synchronous_commit=on`
- `track_io_timing=off`

## Fixture and post-run re-attestation

- Table: `vev_bench.account`.
- 10,000 rows, ids 1 through 10,000, id sum 50,005,000.
- All rows belong to tenant 7.
- Unique primary-key index: `(tenant_id, id)`.
- Owner: `vev_bench_owner`.
- Row-level security enabled and forced.
- Exact application-role policy applies tenant filtering/checking through `vev.tenant_id`.
- Database, owner role, and application role all retained exact marker `vev-owned-fixture:vev_bench:v1` after the campaign.
- Model fingerprint after the campaign: `no.beint.vev.benchmark.BenchmarkModel` → `sha256:8e4130992b0b6d472b47061e21a3de98381bb2b6a42d431b3c89e0fd3283b791`.
- Post-run row count, id bounds/sum, tenant bounds, markers, and fingerprint all matched the prepared fixture.

The setup safety gate accepts a literal numeric loopback admin target by default. Remote destructive setup requires the exact opt-in value and still rejects malformed, non-PostgreSQL, multi-host, userinfo, query, fragment, invalid-port, and missing-database URLs. An opt-in never bypasses a wrong or missing ownership marker. Each admin mutation connection re-attests the database and markers.

## Shared timed transaction envelope

Both lanes use a fixed Hikari pool of eight, `autoCommit=false`, JDBC `SERIALIZABLE`, and read-only transactions. Each invocation mirrors this safety envelope as closely as the APIs allow:

- fresh rollback before work;
- 180-second JDBC network timeout and readback;
- parse-safe first statement installs `pg_catalog` search path, reads it back, reads `pg_my_temp_schema()`, and rejects a retained temporary schema before any later statement;
- UTF-8 client/server transport installation and readback in its own statement;
- tenant 7, statement timeout 30 seconds, lock timeout 30 seconds, transaction timeout 120 seconds, `search_path=pg_catalog`, row security on, synchronous commit on, and UTC;
- built-in casts qualified through `pg_catalog` in the context/envelope SQL;
- database OID/system identity, endpoint, server start, recovery state, current/session roles, isolation, read-only status, encodings, network timeout, GUCs, absence of a temp schema, and the exact indexed model fingerprint attested before work and/or before commit as applicable;
- commit on success and rollback on failure;
- input order, missing sentinel, result shapes, and checksums verified at trial setup;
- second-level/query caches disabled in Hibernate; Vev has no corresponding entity/query cache in this path.

Hibernate uses immediate acquisition-and-hold so the provider work and envelope checks use the same physical connection. Vev's raw verification helper uses the same safety envelope but is excluded from timed results.

## Residual workload and implementation asymmetries

The envelope parity above does not make the public APIs or execution mechanics identical. These twelve differences are material and must accompany any interpretation; `report.md` puts them before its result tables:

1. Vev uses its native lexical/generated-plan API; Hibernate uses Jakarta `EntityAgent` backed by `StatelessSession`.
2. Vev reuses one `PgVev`; Hibernate opens/closes an agent per invocation.
3. Vev maps an immutable record; Hibernate maps a mutable no-argument entity.
4. Vev uses tenant authority plus scalar `Long` key; Hibernate uses a composite `IdClass`.
5. Vev returns a typed `Batch<EntityLookup<...>>`; Hibernate returns `List<Entity|null>`.
6. Vev uses AOT fixed SQL and one array/`unnest` batch; Hibernate uses provider-generated `EntityAgent` SQL.
7. Vev reuses a static compiled scan plan; Hibernate creates an HQL `SelectionQuery` per invocation.
8. Vev maps 256 scan entities and only advances over the sentinel; Hibernate materializes all 257 entities before its checksum interprets list size as `hasMore`.
9. Provider-specific connection/transaction wrappers differ, and Vev additionally pays for `ScopedValue` tenant authority and its poisoning guard.
10. Every Vev fork rebuilds and `ANALYZE`s the disposable fixture/fingerprint; Hibernate verifies the current same fixture. This setup is outside timing and warmups follow.
11. Vev `transactionOnly` reads the transaction tenant accessor; Hibernate returns the known tenant constant because `EntityAgent` has no tenant accessor.
12. Successful reads do not exercise exception, rollback-failure, or poisoning semantics.

Input order, the missing sentinel, result shape, and trial-setup checksums are equivalent. These asymmetries prohibit a universal faster/lower-allocation claim from this campaign.

## Raw-file checksums

The same values are in `SHA256SUMS`:

| File | SHA-256 |
|---|---|
| `01-vev.json` | `e38453e02304a97f22b2dc80077b43f9f3bbc4efff0f965114ab09eb93366b8e` |
| `02-hibernate.json` | `04bcb01b6ef9d0d9516a2ed2c2886b35438bbd19feb47ffe4a81c3d4297eeda3` |
| `03-hibernate.json` | `efbb7ab765e7821999d2440bb428b30a303b21a2b57d06f291182e5aa2a0c257` |
| `04-vev.json` | `b698c5cb498fc5b02eea3e11077b6a7c7501d415cbf56287169730b7dc62d4b2` |
