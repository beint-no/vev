# Benchmark policy

> Benchmarks are experimental engineering evidence, not product claims.

Vev maintains separate JMH modules for the Vev workload and the comparison runtime. Isolation prevents Hibernate ORM from becoming a Vev runtime dependency and makes each process's classpath auditable.

The preview comparison baseline is Hibernate ORM `8.0.0.Beta1`, which itself is prerelease software. The comparison module explicitly resolves Jakarta Persistence `4.0.0-M6`; this is an experimental milestone/beta pairing, not evidence of compatibility between final releases. Results must name both exact versions. They do not establish performance against a final Hibernate ORM 8 release or a production-tuned Hibernate application.

## Publication requirements

A benchmark report is publishable only when it records:

- Vev, benchmark, and comparison commit identifiers;
- JDK vendor and exact JDK 26 version;
- operating system, architecture, CPU, memory, and power mode;
- PostgreSQL version, JDBC driver version, and database location;
- Jakarta Persistence and Hibernate versions, including milestone/beta labels;
- synthetic schema, row count, seed, data distribution, and indexes;
- complete JMH command, forks, warmups, measurements, threads, mode, and profilers;
- pool size, transaction boundary, fetch size, timeout, statement-cache policy, and cache warm state;
- execution order, sample count, confidence/error, median, p95, and allocation per operation;
- raw machine-readable JMH output and its checksum.

Reports must not contain production-derived data, host names, credentials, tenant identifiers, or private application names.

## Comparison rules

Equivalent workloads must:

1. use the same synthetic rows, schema, indexes, PostgreSQL server, driver, and connection-pool settings;
2. return and consume equivalent data so dead-code elimination cannot change the result;
3. use comparable transaction and state-management semantics;
4. apply equivalent fetch sizes and transaction safety envelopes, including isolation, read-only mode, deadlines, encoding, tenant context, model fingerprint, synchronous commit, and pre-commit attestation;
5. run in separate fresh JVM forks with the requested runtime only;
6. distinguish bootstrap, steady-state reads, writes, and allocation measurements;
7. verify result parity before timing begins.

## Destructive fixture safety

Both benchmark lanes rebuild the same disposable fixture and must apply the same setup guard. The administrator URL is limited by default to a simple numeric `127.0.0.1` or `[::1]` JDBC target. Another address requires the exact `VEV_BENCH_ALLOW_REMOTE_DESTRUCTIVE_SETUP=vev_bench` opt-in and must still parse as one single-host PostgreSQL JDBC target with a valid port, simple database path, and no query or fragment. The opt-in cannot authorize multi-host, user-info, missing-database, parameterized, fragmented, malformed, or non-PostgreSQL administrator URLs.

Address opt-in is never ownership opt-in. Before setup alters or drops fixture state, the `vev_bench` database and both fixed roles must each have the exact object comment `vev-owned-fixture:vev_bench:v1`. Newly created objects are marked immediately; pre-existing objects with a missing or different comment fail closed, even when remote setup is explicitly enabled. The database connection used for schema DDL must also re-attest the current database name and all three markers.

The measured transaction envelope must install and read back `pg_catalog` in its own first parse-safe SQL statement, before UTF-8 setup or the remaining context query. That first result must also prove that `pg_my_temp_schema()` is zero, because PostgreSQL implicitly searches an allocated temporary schema before `pg_catalog` for relations and types. Built-in cast targets in the envelope must be explicitly qualified under `pg_catalog`. Verification should include a connection whose initial explicit search path puts a non-catalog schema before `pg_catalog`, plus focused validation that a retained temporary-schema OID is rejected, so this ordering is exercised rather than assumed.

A Vev Jakarta `EntityAgent` workload should first be compared with Hibernate's implementation of the same milestone `EntityAgent` operations where available. In Hibernate ORM 8.0.0.Beta1, `SessionFactory.createEntityAgent()` returns Hibernate's `StatelessSession` implementation; reports should state both the standard entry point and provider type. A separately opened stateless or managed-session comparison is a distinct workload. If one side uses batching, caching, generated keys, or prepared-statement reuse, the other side must use the closest equivalent or the asymmetry must be prominent.

The current Vev lane measures the native lexical transaction/generated-plan API, while the Hibernate lane obtains `StatelessSession` through `createEntityAgent()` and uses a provider selection query for the bounded scan. Both measured transaction envelopes establish and re-attest the same serializable PostgreSQL safety context. This is a comparison of equivalent stateless outcomes where parity checks pass, not a claim that both lanes exercise the same public API, SQL strategy, object identifier representation, or connection-management implementation.

Reports for the current lanes must also disclose that Vev reuses its runtime while Hibernate opens and closes an agent for each invocation; Vev maps an immutable record with a tenant-scoped scalar identifier while Hibernate maps a mutable entity with a composite `IdClass`; and Vev's bounded scan materializes 256 entities then advances over one sentinel row while Hibernate's list query materializes all 257 entities. Those differences are properties of the compared APIs and implementations, not noise to remove from the result.

## Interpretation

Do not describe Vev as faster, lower-allocation, or more scalable from one machine or one entity shape. Report absolute measurements and uncertainty first. A regression in any representative workload remains part of the result; it must not be hidden behind a favorable aggregate.

No performance number belongs in the main README until it is reproduced, reviewed, tied to raw output, and bounded to the exact workload. Even then, it is evidence rather than a universal claim.
