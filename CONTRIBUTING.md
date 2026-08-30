# Contributing

Vev welcomes narrowly scoped experimental contributions. The project is not production-ready, and accepting a patch does not make its API or behavior stable.

## Before proposing a change

- Read the [architecture](docs/architecture.md), [supported profile](docs/supported-profile.md), and [limitations](docs/limitations.md).
- Open a compatibility request before broadening the annotation profile or runtime semantics.
- Use the security process for tenant isolation, SQL injection, transaction safety, or supply-chain defects.
- Keep examples and tests synthetic. Do not contribute code or fixtures copied from a private application or production database.

## Development baseline

Use JDK 26 and the checked-in Gradle wrapper:

```shell
./gradlew clean check integrationTest
```

PostgreSQL integration tests require an administrator connection to disposable PostgreSQL 18. They create a dedicated synthetic `vev_it` database and fixed test roles. Existing database and role names are accepted only when they carry Vev's exact ownership marker, and every mutation connection re-attests those markers. The admin URL must use numeric loopback `127.0.0.1` or `[::1]` without connection parameters; `localhost` is intentionally rejected because it requires name resolution. A deliberately remote disposable server additionally requires `VEV_TEST_ALLOW_REMOTE_DESTRUCTIVE_SETUP=vev_it`. Remote URLs must still name one host and may not contain userinfo, query parameters, or fragments. That opt-in never overrides missing or incorrect ownership markers. Never point the fixture at a shared or production cluster.

All compiler warnings are errors. Published archive bytes must remain reproducible and must not embed local paths, timestamps, credentials, or host-specific metadata. Maven `-SNAPSHOT` staging metadata and filenames are timestamped by the repository format and are not release artifacts.

## Change requirements

An annotation-profile change should include:

- a precise semantic contract;
- positive processor coverage;
- negative compile-failure coverage for adjacent unsupported shapes;
- PostgreSQL 18 integration coverage;
- schema-verification coverage when database shape is affected;
- documentation updates to the profile and limitations.

A runtime change should include failure-path tests, resource cleanup, rollback behavior, and concurrency ownership where applicable. Tenant-sensitive changes also need adversarial cases from the [tenant threat model](docs/tenant-threat-model.md).

Every new production dependency needs an architectural justification: identify the owning module, explain why the JDK or an existing dependency is insufficient, and describe its runtime, security, and publication impact. Dependency updates must retain JDK 26 compatibility and the module boundary that keeps Hibernate out of Vev's runtime graph. Jakarta Persistence milestone or Hibernate beta upgrades must state that prerelease status explicitly.

## Benchmarks

Performance-sensitive work must follow the [benchmark policy](docs/benchmark-policy.md) and include comparable before-and-after results against the pinned Hibernate ORM `8.0.0.Beta1` baseline. Do not submit a favorable summary without raw JMH output, parity checks, environment metadata, and regressions. Benchmark fixtures must be deterministic and synthetic.

## Pull requests

Keep pull requests focused. Explain the supported behavior, the fail-closed boundary, and how the change was verified. Do not combine a dependency refresh, public API redesign, and mapping expansion without a compelling reason.

By intentionally submitting a contribution, you agree that it is provided under the repository's Apache License 2.0 terms, as described in section 5 of the license.
