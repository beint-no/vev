# Performance verification

Run `./gradlew :benchmarks:buildPerformance :benchmarks:jmh`, then
`python3 scripts/check-performance.py`. Do not run other application builds/load
concurrently with final measurements. Release JSON is retained in `docs/measurements`;
GitHub Actions uploads its raw reports.

The build suite uses the full ReAI schema and 10/100/1,000 distinct SQL statements
mixing projections, CTEs, windows, lateral joins and arrays. Disposable-cluster
bootstrap/migrations, SQL description/generation and actual Kotlin compilation are
reported separately. The first Kotlin invocation includes cold compiler initialization;
later samples share its JVM. This is not an incremental application-build benchmark.
Gradle TestKit verifies up-to-date/configuration-cache reuse, SQL-only edits, schema
invalidation and shared schema lifecycle. Compiler tests verify stale-output removal.

JMH compares generated/handwritten JDBC for five-row customer projections and
600-row joined ledger reads. Each fork owns a cluster and asserts exact result parity
before timing. SQL, bindings, result classes, ceilings, fetch sizes and commits match.
Scope and facade overhead are included. The ReAI dataset is synthetic: three tenants,
900 vouchers and 1,800 postings. It is not production load.

Default: one thread, three forks, three two-second warmups and five two-second
measurements, with GC profiling. `-PbenchmarkQuick=true` uses two forks, two 500ms
warmups and three 500ms measurements for PR feedback. CI runs longer measurements
weekly. Quick runs are smoke signals, not publication-grade claims.

CI rejects gross statistically separated timing regressions, excessive allocations,
and very large build-time regressions. Thresholds are guardrails rather than
cross-machine guarantees. Confidence intervals/raw samples are retained. No comparison
covers Hibernate, pool acquisition, TLS, remote networks, writes, contention or
production throughput. Application startup gains need a separate ReAI measurement.

## Local release measurements, 2026-11-17

JDK 27.0.1, Kotlin 2.5.0-Beta2, PostgreSQL 18.6, pgjdbc 42.7.14, macOS arm64.
No competing builds ran during the measurements. Three independent forks were used.
Timing errors below are JMH confidence-interval half-widths, not request percentiles.

| Workload | Generated µs/op | JDBC µs/op | Generated / JDBC allocation B/op |
|---|---:|---:|---:|
| Five-row projection | 54.864 ± 0.353 | 54.415 ± 0.568 | 2,292 / 2,164 |
| 600-row ledger | 564.744 ± 5.017 | 565.456 ± 5.388 | 400,231 / 400,095 |

For 1,000 varied queries: bootstrap/migrations 1.436s, description/generation
0.225s, Kotlin compilation 5.198s. The bootstrap was shared across the 10/100/1,000
query samples. These are local observations, not universal speed guarantees.
Raw JSON and environment details are in `docs/measurements`.
