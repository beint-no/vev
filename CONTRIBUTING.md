# Contributing

Use JDK 27, Kotlin 2.5.0-Beta2 and PostgreSQL 18. Before release run
`CI=true ./gradlew clean check releaseBundle :benchmarks:jmhClasses` and the performance suite.
Keep tests committed. Compiler changes need PostgreSQL rejection cases and independent
consumer checks; runtime changes need database execution and failure tests.

Keep one named query per SQL file; use its generated method name as the filename.
Keep SQL semantics in PostgreSQL. Do not add an ORM compatibility layer, another
SQL type system or runtime query DSL. Tenant scope is the default; global SQL opts
out explicitly. Scoped values do not replace predicates, authorization or RLS.

Never import production rows or credentials. The authorized ReAI DDL has pinned
provenance. Generate synthetic data independently. Review fixture updates for private
literals, external effects and credentials, update catalog-size expectations, and
run the full execution suite.

Performance claims require equivalent results/SQL/statement counts, warmup,
independent forks, allocation profiling, raw output, toolchains and limitations.
Measure SQL analysis separately from generated Kotlin compilation. Do not hide
unsupported cases or weaken assertions to improve results.
