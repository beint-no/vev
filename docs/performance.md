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

## Vev 2.5.0 observations

Local macOS arm64, JDK 27.0.1, Kotlin 2.5.0-Beta2, PostgreSQL 18.6 and pgjdbc 42.7.14.
The table uses the quick configuration; errors are JMH confidence-interval half-widths.

| Five-row native DTO projection | Mean µs/op | Allocation B/op |
|---|---:|---:|
| Vev | 53.95 ± 0.44 | 2,314 |
| Hibernate 7.4.14 | 56.14 ± 0.83 | 7,822 |

For 1,000 varied queries, schema setup took 1.502s, SQL description/generation
0.199s and Kotlin compilation 4.025s. The migrated database
is shared across the 10/100/1,000-query samples; the first Kotlin sample initializes the
compiler and later samples use its warmed JVM. Gradle separately verifies skipped no-op
generation, configuration-cache reuse and unchanged output timestamps.

The Hibernate comparison maps the same native SQL into the same Kotlin result class,
with identical values, ordering, fetch size and transaction boundaries. Pool acquisition
and SessionFactory initialization are outside the timed operation. Setup asserts parity.
The paired JDBC workloads cover five-row projections, 600-row ledger reads, 1/100/600-row
pages, INSERT RETURNING and 1/32/512-row bulk writes. The single-row pair compares the
current direct decoder with the former list-based path for zero/one result.

The runtime JAR is about 15 KB and depends only on `java.base` and `java.sql`. Hibernate
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
nullability, arrays, tenant isolation, constraints, RLS, concurrent writes and failure cleanup.
Write coverage includes PostgreSQL 18 old/new RETURNING values, triggers, generated columns,
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
