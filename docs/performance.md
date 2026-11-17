# Vev 2.1 performance and verification

Run these separately so build measurements do not compete with JMH:

```sh
./gradlew :benchmarks:buildPerformance
./gradlew :benchmarks:jmh
python3 scripts/check-performance.py
```

For focused investigations use `-PbenchmarkInclude=HibernateBenchmark` (a JMH
regular expression). The full CI gate requires the complete matrix, not a filtered run.

## Local release measurements, 2026-11-17

JDK 27.0.1, Kotlin 2.5.0-Beta2, PostgreSQL 18.6, pgjdbc 42.7.14,
Hibernate 7.4.14.Final, macOS arm64. One thread, three independent forks,
three two-second warmups and five two-second measurements per fork, with GC profiling.
Timing errors are JMH confidence-interval half-widths, not request percentiles.
No other builds or application checks were launched by this session during these runs.

| Workload and baseline | Vev 2.1 µs/op | Baseline µs/op | Vev / baseline allocation B/op |
|---|---:|---:|---:|
| Five-row projection, JDBC baseline | 71.044 ± 14.963 | 62.651 ± 14.510 | 2,293 / 2,164 |
| 600-row ledger, JDBC baseline | 561.456 ± 28.071 | 569.480 ± 12.989 | 400,244 / 400,093 |
| Five-row projection, Hibernate native DTO baseline | 55.391 ± 4.505 | 55.998 ± 0.378 | 2,292 / 7,663 |
| Optional row absent, Vev 2.0 list path baseline | 37.652 ± 1.340 | 37.945 ± 4.106 | 1,229 / 1,240 |
| Optional row present, Vev 2.0 list path baseline | 39.611 ± 4.474 | 56.119 ± 8.069 | 1,344 / 1,543 |

The Hibernate baseline is a warm native SQL constructor DTO projection into the
same Kotlin result class. It has no managed entities, association loading, Spring
repository proxy or deliberately expensive mapper. Both paths create their logical
execution scope on the same pre-opened JDBC connection and commit per operation.
Factory initialization and pool acquisition are outside the timed work. Bindings,
fetch sizes, values, ordering and result types match; setup asserts exact parity.
The same text cast uses standard `CAST` syntax accepted by both named-parameter parsers.
This measures native projection overhead, not arbitrary HQL/entity workloads or ReAI
request latency. Database work dominates small reads; allocation differences are larger.

The single-row baseline retains the previous `list(sql, 1, ...)` implementation.
The new path avoids temporary result lists and fetches two rows to detect cardinality
violations without an extra one-row-cursor fetch. Missing optional rows remain valid;
extra rows, NULL mapped rows, decoder/binding failures and caught failures poison the
scope. Execution tests verify multi-row INSERT RETURNING is rolled back after violation.

## Build costs

| Distinct queries | Bootstrap/migrations seconds | SQL generation seconds | Kotlin compilation seconds |
|---:|---:|---:|---:|
| 10 | 1.504 | 0.015 | 1.686 |
| 100 | 1.504 | 0.046 | 1.489 |
| 1,000 | 1.504 | 0.196 | 4.052 |

The build workload describes 10/100/1,000 distinct queries against the 432-table
schema corpus, mixing projections, CTEs, windows, lateral joins and arrays.
The migrated cluster is shared across these samples; bootstrap is not paid three times.
All query generation costs and actual generated Kotlin compilation are measured
separately. The first Kotlin invocation includes compiler initialization; subsequent
samples, including 1,000 queries, run in the warmed compiler JVM. These are local
observations, not full Gradle clean-build or cross-machine guarantees.
Gradle TestKit verifies no-op/configuration-cache reuse, SQL-only edits, stale-output
removal, schema revalidation, schema-only edits skipping Kotlin compilation, task-selected
PostgreSQL binaries and one shared cluster/schema across modules.

## Runtime footprint

Vev's core JAR is **15,397 bytes**, with no external dependencies; `jdeps` reports
only `java.base` and `java.sql`. The Hibernate 7.4.14.Final core JAR is
**15,350,391 bytes**, before its transitive dependencies. These are compressed core
JAR sizes, not equal-capability bundles or whole-application savings. Both still need
a JDBC driver and connection pool; generated Kotlin uses the application's Kotlin runtime.
The ReAI pilot adds Vev while Hibernate remains, so it does not yet shrink the application
or remove Hibernate initialization. Vev's compiler, Gradle plugin and cluster are absent
from the application's runtime artifact.

## CI coverage and limits

The complete quick matrix contains 24 variants: paired five-row reads, 600-row ledger,
1/100/600-row pages, INSERT RETURNING, 1/32/512-row bulk writes, two Hibernate/Vev native
projections, and old/new optional-single-row paths with zero/one result. CI uses two
forks, two 500ms warmups and three 500ms measurements; weekly runs use the longer
configuration. Missing results, incomplete forks/iterations and gross paired timing,
allocation or build regressions fail the gate. Quick runs are smoke signals.

The ReAI workload has three synthetic tenants, 900 vouchers and 1,800 postings. All
set-based writes roll back to keep data stable; this is not durable commit throughput.
Temporary benchmark clusters disable durability, and no test imports production rows.
Linux and macOS correctness runs execute 6,900 checks against their installed PostgreSQL,
including the three trial report queries, release-bundle consumers and Kotlin/Java API use.
No cached test result is accepted as a fresh CI verification. Compilation caches remain enabled.

Additional ReAI application validation: a clean web-app build; deliberate invalid SQL
rejected before Kotlin compilation; successful dev startup; 15 original/generated SQL
multiset comparisons across three restored-data tenants and 14,955 returned rows with
zero differences; authenticated supplier/customer reports and drill-down; and the supplier
cost API returning 421 entries with an empty unknown-supplier filter. Runtime smoke checks
used the shared restored local database with Flyway disabled to avoid applying unrelated
pending main-branch migrations. All current Flyway migrations were validated separately in
Vev's disposable build database. This is correctness evidence, not an application speed test.

Raw results: [core/ORM/single-row](measurements/2.1.0-core.json),
[complete quick matrix](measurements/2.1.0-matrix.json),
[build phases](measurements/2.1.0-build.json), [environment](measurements/environment.json).
The suite does not cover remote-network latency, TLS, lock contention, production
throughput or a Hibernate-free ReAI startup. Those require application measurements.
