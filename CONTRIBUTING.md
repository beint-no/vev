# Contributing

Use JDK 27 and PostgreSQL 18. Run `./gradlew clean check releaseBundle` before
submitting a change. Add compiler rejection or independent consumer compilation
tests for type-system changes, and PostgreSQL integration tests for execution
semantics. Preserve original failures and explicit connection ownership.

SQL semantics belong to PostgreSQL. When nullability cannot be proved, expose a
nullable result. Never silently fall back to an unchecked mapping or add an unsafe
non-null override. Extend the actual application query corpus before expanding
the public API. Keep new runtime dependencies justified and measured.

Performance changes need equivalent outcomes, statement counts, result checks,
warmup, fresh forks, and raw JMH output. Report allocation and latency separately.
Passing tests does not establish a general performance advantage.
