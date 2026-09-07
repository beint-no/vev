# Changelog

## 1.0.0 — 2026-09-07

First stable release of the documented native Vev API for JDK 27 and PostgreSQL 18.

- Compile immutable Java and Kotlin records into bounded typed mapping/query plans.
- Support explicit tenant scopes, shared/read-only records, scalar/enum/binary/text
  values, typed keyset queries, identity creation and optimistic atomic writes.
- Verify exact schema keys, indexes, references, checks, declared defaults,
  historical missing-value storage, privileges and row security at startup.
- Add explicit tenant-key foreign keys to an external registry, including declared
  administrative cascade deletion without exposing registry data access.
- Require generated-plan ABI 7 and matching processor/runtime versions.
- Publish reproducible Maven repository artifacts with sources and Javadoc,
  a native 1.x compatibility policy, and documented migration boundaries.

Validation: 373 passing tests and an isolated published-artifact consumer on
OpenJDK `27+35-2325` and PostgreSQL 18.6. ReAI's Activity catalogue and timesheet
Activity reads have application parity evidence; the remaining Hibernate migration
continues separately. The optional Jakarta 4 milestone facade remains
nonconforming. Final JDK 27 verification and representative performance comparisons
remain follow-up work. See [release scope](docs/1.0-release-gates.md).
