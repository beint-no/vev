# Roadmap after 1.0

Vev 1.0 establishes the documented native API, generated mapping plans and
PostgreSQL verification contract. Future work is driven by application needs and
measured results. The detailed [release scope](1.0-release-gates.md) records what
is and is not proved today.

1. **Finish the ReAI migration.** Add closed typed projections, joins and predicates
   needed by actual repositories; define explicit aggregate write plans and
   transaction integration before replacing Hibernate writes. Preserve deferred
   business constraints and compare real workflow outcomes on disposable data.
2. **Measure and tune.** Compare stable Hibernate, Vev and diagnostic JDBC controls
   on equivalent workloads. Record latency, throughput, allocations, startup,
   compile cost and query counts with host telemetry. Tune batch sizes, returned
   row verification, indexes and JDBC preparation only from reliable evidence.
3. **Make adoption easier.** Add manifest-versus-migration tooling, clearer catalog
   diagnostics, tenant-bound opaque continuation cursors, and framework adapters
   with explicit ownership. Extend scalar/composite representations through exact
   generated contracts rather than runtime reflection or SQL escape hatches.
4. **Strengthen maintenance.** Verify final JDK 27 and production images, automate
   binary API/dependency checks and releases, deepen failure injection and compiler
   fuzzing, and document upgrade/recovery experience from application deployments.
5. **Follow Jakarta 4 selectively.** Replace milestone dependencies when final
   contracts are available. Keep the optional facade clearly scoped; assess TCK
   participation separately from native ORM development.

Preview APIs are welcome when they simplify ownership or failure reasoning, or
produce measured improvements. Preview adoption alone is not a performance result.
No roadmap item implies a release date or a universal performance claim.
