# Vev versus Hibernate ORM 8.0.0.Beta1: current indexed-read and batch-update campaign

## Outcome

This campaign is intentionally published as a **rejected latency comparison**. It completed without benchmark, parity, transaction, or fixture failures. Post-A2 telemetry showed severe unrelated CPU and storage activity, but no comparable pre-run or in-run telemetry was captured. Host interference therefore cannot be excluded or assigned to particular samples. Filtering the slower samples would create a favorable but invalid result, so every sample is preserved and no latency ratio is promoted.

One narrower observation remained repeatable despite the invalid latency context: JMH's normalized allocation was stable across the two processes for each lane. In these exact workloads, Vev allocated fewer bytes per operation than Hibernate ORM `8.0.0.Beta1`:

| Workload | Vev pooled mean (B/op) | Hibernate pooled mean (B/op) | Vev difference | Vev A1 / A2 | Hibernate B1 / B2 |
|---|---:|---:|---:|---:|---:|
| indexed equality, 32 rows plus sentinel | 27,731.118 | 40,735.946 | -31.92% | 27,717.377 / 27,744.859 | 40,705.023 / 40,766.869 |
| indexed equality, one row | 11,874.150 | 15,897.071 | -25.31% | 11,877.570 / 11,870.730 | 15,885.143 / 15,908.999 |
| committed optimistic update of 32 rows | 56,383.585 | 102,538.909 | -45.01% | 56,367.383 / 56,399.788 | 102,764.098 / 102,313.720 |

Every process contributed the same sample count for a given workload. Each lane's pooled mean is therefore the arithmetic mean of its two process-level means. `ratio = Vev pooled mean / Hibernate pooled mean`; `difference = (ratio - 1) × 100`. The raw-control overhead is computed from the native and raw Vev pooled means in the same way.

The Vev raw set-based update diagnostic averaged 42,813.195 B/op across A1 and A2. Native Vev therefore allocated 31.70% more than its raw JDBC control while still allocating 45.01% less than the compared Hibernate operation. These are descriptive, workload-specific allocation measurements from one JMH/GC-profiler implementation on one machine. They are not a general lower-allocation claim.

## Why latency was rejected

The complete A–B–B–A latency sequence is below. Values are process-local mean / median / p95 in microseconds per operation. Read p95 is a percentile of 24 one-second iteration averages, not an operation-level tail percentile. Write p95 is computed from 6,144 measured single-shot operations.

| Run | Workload | Mean / median / p95 (µs/op) |
|---|---|---:|
| A1 Vev | indexed 32 | 217.971 / 219.527 / 226.102 |
| B1 Hibernate | indexed 32 | 240.866 / 240.672 / 247.858 |
| B2 Hibernate | indexed 32 | 282.355 / 243.717 / 506.910 |
| A2 Vev | indexed 32 | 370.163 / 411.051 / 536.430 |
| A1 Vev | indexed one | 181.871 / 180.964 / 189.304 |
| B1 Hibernate | indexed one | 183.702 / 177.626 / 284.147 |
| B2 Hibernate | indexed one | 179.716 / 177.116 / 216.988 |
| A2 Vev | indexed one | 233.589 / 217.066 / 337.083 |
| A1 Vev | update 32 | 406.687 / 343.084 / 555.343 |
| B1 Hibernate | update 32 | 497.954 / 439.771 / 658.854 |
| B2 Hibernate | update 32 | 512.587 / 448.125 / 664.198 |
| A2 Vev | update 32 | 2,712.213 / 763.667 / 21,775.303 |
| A1 Vev raw | update 32 | 368.857 / 283.875 / 649.093 |
| A2 Vev raw | update 32 | 4,052.428 / 741.229 / 22,379.698 |

The second-process mean drift was +69.82% for Vev indexed-32, +28.44% for Vev indexed-one, and +566.91% for Vev update-32. Hibernate indexed-32 had already shown a transient slow fork in B2. In contrast, normalized-allocation drift stayed between -0.44% and +0.15% for every compared lane/workload.

Immediately after A2, system load averages were 6.28 / 7.30 / 7.30. macOS storage-management and Mail storage-analysis processes together consumed roughly 180% CPU in the first snapshot, while disk activity exceeded 6,000 IOPS. A follow-up snapshot still showed storage management active and approximately 8,000 IOPS. The services were not terminated or deprioritized. The absence of a matching pre-A1 process/I/O snapshot is itself a protocol deficiency. The latency data cannot distinguish runtime cost from this host-wide interference.

## Compared outcomes and unavoidable asymmetries

Both lanes use the same synthetic PostgreSQL 18.4 server, pgjdbc 42.7.13, eight-connection Hikari pools, serializable transactions, synchronous commit, tenant RLS policy, timeouts, UTC, exact fingerprint, connection/session attestation, rows, and indexes. Setup validates equivalent checksums before timing and teardown validates persisted write state.

The indexed workloads return and consume equivalent application-visible results, but not identical internal work. Vev reuses generated query objects and maps only the public result limit before advancing once over the sentinel. Hibernate opens/closes a Jakarta `EntityAgent` backed by `StatelessSession`, creates and binds a typed HQL selection query per invocation, and materializes the sentinel entity. Vev maps an immutable record with a tenant-scoped scalar identifier; Hibernate maps a mutable entity with a composite `IdClass`.

The update workload has a larger semantic difference which is part of the result:

- The measured Vev operation is native `WriteEntities.updateMultiple()`. It executes one typed-array statement with a materialized all-member tenant/id/version preflight, one guarded update, ordered full-row `RETURNING`, and exact snapshot/version verification. Vev's experimental Jakarta-shaped facade does not expose `EntityAgent.updateMultiple()`.
- Hibernate `EntityAgent.updateMultiple()` executes 32 optimistic per-row updates as one explicit JDBC batch, determines success from update counts, mutates caller-owned detached entities, returns `void`, and returns no database snapshots. Factory-level `hibernate.jdbc.batch_size` is disabled; `StatelessSession.updateMultiple()` temporarily controls its own explicit batch.
- Vev's raw JDBC method mirrors the set-based SQL as a lower-bound diagnostic. It is not an ORM competitor and is never used in a Vev-versus-Hibernate ratio.

The comparison therefore measures equivalent successful outcomes available from the two APIs, not identical SQL or identical guarantees.

## What this evidence supports

This package supports only these conclusions:

1. The new indexed and write benchmarks are executable in fresh JDK 26 forks against the exact PostgreSQL fixture, and all setup/teardown parity checks complete.
2. For these three exact operations, normalized allocation was stable across the counterbalanced repeats and descriptively lower for Vev.
3. The latency campaign is invalid for a Vev-versus-Hibernate performance conclusion and must be rerun on an isolated host with before/during/after CPU, I/O, and thermal telemetry.

It does not establish that Vev is faster, generally lower-allocation, production-ready, or a Hibernate replacement. It does not test relationships, joins, projections, application query diversity, concurrent clients, pool saturation, remote database latency, contention, failure paths, or end-to-end applications. Hibernate and Jakarta dependencies are prerelease Beta/Milestone builds. JMH also warned that compiler blackholes and its `sun.misc.Unsafe` use are experimental/deprecated on this JDK.

Exact process scores are in `run-results.csv`; the retained allocation comparison is in `allocation-comparisons.csv`; all iterations and profiler metrics are in the four raw JSON files. `metadata.md` records the protocol, environment, fixture, interference, and validation gates.
