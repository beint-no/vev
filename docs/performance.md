# Performance and verification

The suite measures three separate costs: migrating a fresh build database, describing
and generating SQL contracts, and compiling generated Kotlin. Runtime benchmarks compare
equivalent results against handwritten JDBC and an optimized Hibernate native DTO projection.

```sh
CI=true ./gradlew clean check releaseBundle :benchmarks:jmhClasses
python3 scripts/verify-release.py
./gradlew :benchmarks:buildPerformance
./gradlew :benchmarks:jmh
python3 scripts/check-performance.py
```

Run measurements without competing builds. `-PbenchmarkQuick=true` selects two forks,
two 500ms warmups and three 500ms measurements. The default uses three forks, three
2-second warmups and five 2-second measurements. `-PbenchmarkInclude=HibernateBenchmark`
selects a focused investigation; the CI gate requires the complete matrix.

## Vev 3.0.0 observations

Local macOS arm64, JDK 27.0.1, Kotlin 2.5.0-Beta2, PostgreSQL 19.0 and pgjdbc 42.7.14.
The table uses the quick configuration against optimal `fetchSize = 0` baselines; errors are JMH confidence-interval half-widths.

| Five-row native DTO projection | Mean µs/op | Allocation B/op |
|---|---:|---:|
| Vev | 41.51 ± 3.10 | 1,876 |
| Handwritten JDBC | 38.47 ± 1.19 | 1,858 |
| Hibernate 7.4.14 | 44.59 ± 10.74 | 7,564 |

| Paged ledger read (ReAI schema) | Vev µs/op (B/op) | Handwritten JDBC µs/op (B/op) |
|---|---:|---:|
| 1 row | 49.34 ± 4.70 (2,299 B) | 50.10 ± 7.77 (2,303 B) |
| 100 rows | 104.03 ± 9.69 (63,010 B) | 105.92 ± 4.66 (64,600 B) |
| 600 rows | 346.43 ± 12.25 (377,964 B) | 355.98 ± 12.88 (399,808 B) |

For 1,000 varied queries, cold schema setup took 1.658s, SQL description/generation
0.440s and Kotlin compilation 8.615s. On subsequent Gradle builds with unchanged migrations,
the schema-digest template cache clones the migrated data directory via copy-on-write and skips
both `initdb` and Flyway replay. Gradle separately verifies skipped no-op generation,
configuration-cache reuse, schema-template reuse, and unchanged output timestamps.

The Hibernate comparison maps the same native SQL into the same Kotlin result class,
with identical values, ordering, fetch size and transaction boundaries. Pool acquisition
and SessionFactory initialization are outside the timed operation. Setup asserts parity.
The paired JDBC workloads cover five-row projections, 600-row ledger reads, 1/100/600-row
pages, INSERT RETURNING and 1/32/512-row bulk writes. The single-row pair compares the
current direct decoder with the former list-based path for zero/one result.

The runtime JAR is about 18 KB and depends only on `java.base` and `java.sql`. Hibernate
7.4.14's core JAR is about 15.35 MB before transitive dependencies. Driver, pool, generated
classes and Kotlin runtime are additional costs. Keeping Hibernate in an application also
keeps its entity metadata and startup work. Library timings do not establish a whole-app gain.

## Replacing an entity save

The project-settings workload uses the real schema and equivalent stored numeric values.
It covers insert, changed update and unchanged saves, each with a nullable or non-null rate.
Every trial checks returned and stored state before timing. JDBC executes exactly the same
upsert SQL as Vev. Hibernate executes the former ReAI selection followed by persist/merge
and flush, with tenant filtering and a fresh session on the same connection.

A focused changed-update run uses three forks, three 2-second warmups and five 2-second
measurements per fork:

| Changed existing rate | Mean µs/op | Allocation B/op |
|---|---:|---:|
| Vev atomic SQL | 47.58 ± 2.00 | 1,747 |
| Same-SQL JDBC | 43.41 ± 0.25 | 1,618 |
| Hibernate find/save/flush | 66.34 ± 2.13 | 17,302 |

The operation goes from two statements to one. The focused result has about 28% lower
mean latency and 90% lower allocation than the former entity path. ReAI's hybrid application
still creates JPA transactions; measure actual request gains during rollout.

The full quick matrix retains all six settings cases. An unchanged non-null save measured
44.54 µs/op for Vev and 44.80 µs/op for Hibernate, with overlapping timing intervals.
Hibernate skips its UPDATE for unchanged state; the simple SQL upsert still writes.
Treat statement count, allocation, locking and database write cost separately.
`-PbenchmarkSettingsUpdate=true` reproduces the focused run and writes a separate JSON file.

## Correctness and CI

The schema corpus contains 432 ReAI tables, views, functions, indexes, constraints and
triggers, with pinned provenance. Execution fixtures are synthetic: three tenants,
900 vouchers and 1,800 postings. The suite checks supported column mappings, invalid SQL,
nullability, arrays, tenant isolation, constraints, concurrent writes and failure cleanup.
Write coverage includes old/new RETURNING values, triggers, generated columns,
nullable upserts, concurrent first saves, optimistic conflicts, serialization/deadlock SQLSTATE,
lock timeouts, read-only enforcement, savepoints and rollback after decoding failures.
Generated binding lambdas execute at their JVM slot boundaries; oversized inputs fail generation.
Single-query file tests reject additional statements and query contracts, including
markers after terminated SQL. Quoted/commented markers remain SQL text. Tests cover
unknown/duplicate directives, SQLSTATE diagnostics, stable generation and stale output removal.
Independent Kotlin/Java consumers exercise the actual release bundle and Gradle plugin.

Linux and macOS correctness tasks execute against their installed PostgreSQL; compilation
may be cached, test results are not. The performance job requires all 42 variants, complete
forks/iterations and the build report. Gross timing, allocation and build regressions fail.
Weekly CI runs the longer configuration. Raw JSON is retained below and as CI artifacts.

Benchmark clusters disable durability; write benchmarks roll back to preserve their data.
They measure mapping/query execution, rather than durable commits, network latency or
production contention. PostgreSQL permissions and business invariants also need application
execution tests. The ReAI trial validates its generated queries against current migrations
and compares behavior on the restored local database.

[Runtime matrix](measurements/2.5.0-matrix.json) ·
[Build phases](measurements/2.5.0-build.json) ·
[Focused entity-write comparison](measurements/2.5.0-settings-update-long.json) · [Environment](measurements/environment.json)
