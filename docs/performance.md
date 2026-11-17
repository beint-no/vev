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

## Vev 2.2.0 observations

Local macOS arm64, JDK 27.0.1, Kotlin 2.5.0-Beta2, PostgreSQL 18.6 and pgjdbc 42.7.14.
The table uses the quick configuration; errors are JMH confidence-interval half-widths.

| Five-row native DTO projection | Mean µs/op | Allocation B/op |
|---|---:|---:|
| Vev | 54.82 ± 2.86 | 2,315 |
| Hibernate 7.4.14 | 57.35 ± 0.49 | 7,875 |

For 1,000 varied queries, schema setup took 1.412s, SQL description/generation
0.188s and Kotlin compilation 4.192s. The migrated database
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

## Correctness and CI

The schema corpus contains 432 ReAI tables, views, functions, indexes, constraints and
triggers, with pinned provenance. Execution fixtures are synthetic: three tenants,
900 vouchers and 1,800 postings. The suite checks supported column mappings, invalid SQL,
nullability, arrays, tenant isolation, constraints, RLS, concurrent writes and failure cleanup.
Grouped-file tests cover quoted/nested-comment markers, independent metadata, unknown
and duplicate directives, stable generation, failed compilation and stale output removal.
Independent Kotlin/Java consumers exercise the actual release bundle and Gradle plugin.

Linux and macOS correctness tasks execute against their installed PostgreSQL; compilation
may be cached, test results are not. The performance job requires all 24 variants, complete
forks/iterations and the build report. Gross timing, allocation and build regressions fail.
Weekly CI runs the longer configuration. Raw JSON is retained below and as CI artifacts.

Benchmark clusters disable durability; write benchmarks roll back to preserve their data.
They measure mapping/query execution, rather than durable commits, network latency or
production contention. PostgreSQL permissions and business invariants also need application
execution tests. The ReAI trial validates its generated queries against current migrations
and compares behavior on the restored local database.

[Runtime matrix](measurements/2.2.0-matrix.json) ·
[Build phases](measurements/2.2.0-build.json) · [Environment](measurements/environment.json)
