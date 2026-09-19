# Performance evidence

Vev aims for predictable SQL execution and small mapping overhead. This benchmark
does **not** establish that Vev is faster than JDBC, Hibernate, or either application.

Measured on 2026-09-19: Apple M5 Max, macOS 27.0 arm64, OpenJDK 27+35-2325,
PostgreSQL 18.6 (Homebrew), pgjdbc 42.7.13, Kotlin 2.4.20 and JMH 1.36.

| Implementation | Average µs/op | JMH error ± µs/op | Allocated bytes/op |
|---|---:|---:|---:|
| Generated Vev | 55.198 | 1.465 | 2,244.0 |
| Handwritten JDBC | 53.666 | 0.869 | 2,163.9 |

The generated path's point estimate is about 2.9% slower and allocates about 80
additional bytes per operation. Timing confidence intervals overlap. Earlier
short exploratory runs changed the timing ranking, so small differences should
not guide an application decision. The error column is JMH's confidence interval
half-width for the average, not a request latency percentile.

Both paths run the same SQL with the same parameter types, fetch size, five rows,
result classes, null checks, list materialization, row bound and commit. Setup
asserts exact result equality. Vev additionally checks its lexical session and
PostgreSQL version. Each fork owns a disposable local PostgreSQL cluster. There
is one benchmark thread, three forks, three two-second warmups and five two-second
measurement iterations per fork. The GC profiler measures JVM allocations.
No other application build was run during this final measurement.

This is a warm, tiny-table, loopback read workload. It excludes connection-pool
acquisition, TLS/network distance, contention, large results, application logic,
RLS context installation, and build-time generation. It cannot predict production
throughput, tail latency, or write performance. Query plans, indexes, transaction
boundaries and round trips need separate application measurements.

The runtime tests independently assert one prepared/executed statement per
ordinary generated query, including tenant-predicate queries. `withRls` explicitly
adds three context-management statements around its unit of work. Result bounds
fail instead of silently truncating.

Reproduce with `./gradlew :benchmarks:jmh`. Raw JMH measurements, fork samples and
allocation metrics are retained in [jdk27-pg18.json](performance/jdk27-pg18.json).
