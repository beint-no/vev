# Vev versus Hibernate ORM 8.0.0.Beta1: final local read-path evidence

## Outcome

This experiment is encouraging but deliberately narrow. Under one local PostgreSQL 18.4 host, one thread, and the matched serializable safety envelope, pooled Vev mean latency was lower in four of five workloads and effectively tied on `findOne`. Its normalized allocation was lower in all five. The larger descriptive differences were:

- `findMultiple32`: Vev used 11.63% less mean time and 36.63% fewer mean allocated bytes.
- `findMultiple256`: Vev used 46.30% less mean time and 49.91% fewer mean allocated bytes, but Hibernate's latency was highly variable, so this magnitude is not a precise effect estimate.
- `boundedScan`: Vev used 13.30% less mean time and 34.92% fewer mean allocated bytes.

`transactionOnly` differed by only -0.66% latency for Vev, and `findOne` differed by +0.06% latency for Vev. Those are descriptive ties at the resolution of this campaign. The result does not establish statistical superiority, production superiority, or Hibernate replacement readiness: there are only two process replicates per lane, one machine, one database, five read-only microbenchmarks, and inherent workload differences listed below.

## Twelve non-equivalences that constrain interpretation

These are not hidden footnotes; they are part of what the benchmark measures.

1. Vev exercises its native lexical/generated-plan API. Hibernate exercises Jakarta Persistence 4 `EntityAgent` through Hibernate's `StatelessSession` implementation.
2. Vev reuses one `PgVev` runtime; Hibernate opens and closes an `EntityAgent` for every measured invocation.
3. Vev maps to an immutable generated-plan record; Hibernate maps to a mutable entity with the required no-argument construction shape.
4. Vev uses a tenant authority plus a scalar `Long` entity key; Hibernate represents the tenant-qualified identity through a composite `IdClass`.
5. Vev batch lookup returns a typed `Batch<EntityLookup<...>>`; Hibernate returns an ordered `List<Entity|null>`.
6. Vev's AOT plan executes fixed SQL and a single PostgreSQL `unnest` batch statement; Hibernate uses provider-generated `EntityAgent` SQL.
7. Vev's bounded scan uses a static compiled plan; Hibernate creates an HQL `SelectionQuery` on every invocation.
8. Vev maps 256 scan entities and advances the result set once to detect the sentinel. Hibernate materializes all 257 entities and then checks the sentinel.
9. Provider-specific connection/transaction wrappers differ. Vev also carries its `ScopedValue` tenant authority and transaction-poisoning guard; these inherent costs cannot be made identical without ceasing to measure the libraries.
10. Each Vev fork rebuilds and `ANALYZE`s the disposable schema and fingerprint. Each Hibernate fork verifies the latest same fixture without rebuilding it. Setup is outside measurement and each fork warms up, while A–B–B–A only partially controls resulting cache/order effects.
11. `transactionOnly` reads Vev's transaction tenant accessor. Hibernate returns the already-known tenant constant because `EntityAgent` has no corresponding tenant accessor.
12. Failure-path exception, rollback, and transaction-poisoning semantics are not exercised by the successful read workloads.

Both lanes disable second-level/query caching. Inputs, input order, one missing batch sentinel, result shapes, and trial-setup checksums are equivalent. The transaction/database security envelope is intentionally close; its details and residual setup mechanics are in `metadata.md`.

## Pooled latency

Lower is better. Each lane/workload pools 32 JMH iteration-average samples. P95 uses nearest rank and is not an operation-level tail-latency percentile. “Vev Δ” is `(Vev mean / Hibernate mean - 1) × 100`; a negative number favors Vev. Order changes are signed second-process versus first-process score changes.

| Workload | Vev mean / median / p95 (µs/op) | Hibernate mean / median / p95 (µs/op) | Vev Δ mean | A2 vs A1 | B2 vs B1 |
|---|---:|---:|---:|---:|---:|
| `transactionOnly` | 324.370 / 324.218 / 326.321 | 326.517 / 326.355 / 332.231 | -0.66% | +0.04% | -0.44% |
| `findOne` | 375.496 / 373.892 / 385.276 | 375.278 / 374.811 / 387.976 | +0.06% | +1.09% | -0.60% |
| `findMultiple32` | 423.443 / 422.964 / 428.546 | 479.175 / 475.224 / 494.534 | -11.63% | +0.02% | -3.19% |
| `findMultiple256` | 1529.224 / 1511.390 / 1630.825 | 2847.914 / 2734.776 / 4166.426 | -46.30% | -5.93% | +9.36% |
| `boundedScan` | 487.647 / 487.094 / 499.817 | 562.457 / 560.179 / 583.856 | -13.30% | +0.88% | -1.80% |

The 256-key Hibernate result is clearly unstable: B1 was 2720.630 ± 1027.475 µs/op and B2 was 2975.198 ± 1000.200 µs/op, with raw observations spanning approximately 1.35–4.33 ms/op. Vev's two process scores were 1575.991 ± 34.482 and 1482.456 ± 22.682 µs/op. It is fair to say this campaign observed lower and much tighter Vev latency for that workload; it is not fair to present 46.30% as a universal or statistically resolved advantage.

## Pooled normalized allocation

Lower is better. Values are bytes per operation from JMH's GC profiler. The same 32 iteration-average samples and nearest-rank P95 are used.

| Workload | Vev mean / median / p95 (B/op) | Hibernate mean / median / p95 (B/op) | Vev Δ mean | A2 vs A1 | B2 vs B1 |
|---|---:|---:|---:|---:|---:|
| `transactionOnly` | 23,688.560 / 23,683.002 / 23,743.195 | 24,486.654 / 24,475.033 / 24,536.571 | -3.26% | +0.02% | +0.03% |
| `findOne` | 26,130.193 / 26,115.539 / 26,219.514 | 29,238.213 / 29,219.488 / 29,290.959 | -10.63% | -0.26% | +0.07% |
| `findMultiple32` | 46,841.734 / 47,347.887 / 47,425.549 | 73,923.293 / 74,524.503 / 74,616.264 | -36.63% | -2.16% | -1.58% |
| `findMultiple256` | 177,713.410 / 177,773.994 / 177,783.891 | 354,762.926 / 354,787.368 / 355,145.152 | -49.91% | -0.02% | +0.01% |
| `boundedScan` | 146,371.903 / 142,252.563 / 158,684.629 | 224,923.369 / 221,145.886 / 237,408.781 | -34.92% | -5.46% | +3.45% |

The bounded-scan allocation distributions show fork-level modes rather than one tight population: Vev observations cluster near 142 KB and 159 KB, while Hibernate observations cluster near 221 KB and 237 KB. The pooled means faithfully include both modes, but their precision should not be overstated.

## Complete process results

Every timed process result is shown, including unfavorable and noisy values. `±` is JMH's process-local 99.9% error half-width, not an across-machine interval.

| Run | Lane | Workload | Time (µs/op) | Allocation (B/op) |
|---|---|---|---:|---:|
| A1 | Vev | `transactionOnly` | 324.306 ± 1.401 | 23,686.583 ± 21.486 |
| A1 | Vev | `findOne` | 373.468 ± 1.988 | 26,164.158 ± 71.495 |
| A1 | Vev | `findMultiple32` | 423.405 ± 2.784 | 47,353.773 ± 27.726 |
| A1 | Vev | `findMultiple256` | 1575.991 ± 34.482 | 177,728.151 ± 96.583 |
| A1 | Vev | `boundedScan` | 485.514 ± 5.414 | 150,479.829 ± 8,640.025 |
| B1 | Hibernate | `transactionOnly` | 327.235 ± 2.789 | 24,482.695 ± 21.334 |
| B1 | Hibernate | `findOne` | 376.400 ± 6.817 | 29,228.299 ± 24.551 |
| B1 | Hibernate | `findMultiple32` | 486.952 ± 6.449 | 74,510.344 ± 42.581 |
| B1 | Hibernate | `findMultiple256` | 2720.630 ± 1027.475 | 354,739.950 ± 206.416 |
| B1 | Hibernate | `boundedScan` | 567.575 ± 11.484 | 221,112.173 ± 86.021 |
| B2 | Hibernate | `transactionOnly` | 325.800 ± 1.907 | 24,490.613 ± 22.774 |
| B2 | Hibernate | `findOne` | 374.155 ± 2.764 | 29,248.127 ± 44.859 |
| B2 | Hibernate | `findMultiple32` | 471.399 ± 2.092 | 73,336.242 ± 1,304.767 |
| B2 | Hibernate | `findMultiple256` | 2975.198 ± 1000.200 | 354,785.902 ± 165.831 |
| B2 | Hibernate | `boundedScan` | 557.338 ± 5.292 | 228,734.566 ± 8,300.861 |
| A2 | Vev | `transactionOnly` | 324.434 ± 0.938 | 23,690.537 ± 20.925 |
| A2 | Vev | `findOne` | 377.523 ± 5.491 | 26,096.227 ± 38.196 |
| A2 | Vev | `findMultiple32` | 423.481 ± 3.040 | 46,329.694 ± 1,081.316 |
| A2 | Vev | `findMultiple256` | 1482.456 ± 22.682 | 177,698.668 ± 122.290 |
| A2 | Vev | `boundedScan` | 489.780 ± 6.647 | 142,263.978 ± 31.795 |

Exact unrounded values are in `run-results.csv`. Pooled values are in `pooled-results.csv`; ratios are in `comparisons.csv`; all raw iterations and profiler metrics are in the four JSON files.

## What this supports

The evidence supports three limited conclusions for this implementation and environment:

1. Vev's safety-heavy transaction envelope does not impose a visible broad latency penalty relative to Hibernate's mirrored envelope on these reads.
2. The AOT/fixed-SQL design has a plausible and repeatable allocation advantage on all five workloads, especially batch and bounded reads.
3. Batch/scan performance is promising enough to justify broader testing; it does not yet establish a general ORM advantage.

It does not test writes, generated identities, optimistic conflicts, relationships, graph loading, joins, user-defined queries, migrations, schema evolution, concurrency, pool saturation, remote database latency, multiple tenants under load, failure poisoning, recovery, alternative PostgreSQL plans, or application end-to-end behavior. Hibernate and Jakarta dependencies are Beta/Milestone versions. JMH also warned that compiler blackholes are experimental on this JDK. A responsible next campaign needs multiple hosts/process days, randomized or Latin-square order, concurrent mixed read/write workloads, injected failure paths, and production-shaped schemas before any replacement claim.
