# Releases and compatibility

Vev 1.0.0 stabilizes the documented native API in `vev-core` and `vev-postgres`.
It is distributed as a GitHub release with a versioned Maven repository archive,
sources, Javadoc, POMs, Gradle module metadata, and SHA-256 checksums. Artifacts are
not published to Maven Central by this release procedure.

## Install 1.0.0

Download `vev-1.0.0-maven.zip` and `SHA256SUMS` from the
[1.0.0 release](https://github.com/beint-no/vev/releases/tag/v1.0.0).
Verify the archive's SHA-256 checksum against the release checksum file, then
extract it into a persistent local directory or your organization's Maven repository.
No GitHub credentials are needed to consume the downloaded archive.

```kotlin
repositories {
    maven {
        url = uri("/absolute/path/to/extracted-vev-repository")
        content { includeGroup("no.beint.vev") }
    }
    mavenCentral()
}
dependencies {
    implementation("no.beint.vev:vev-postgres:1.0.0")
    annotationProcessor("no.beint.vev:vev-processor:1.0.0")
    compileOnly("jakarta.persistence:jakarta.persistence-api:4.0.0-M6")
}
```

Run Gradle, Java compilation, and the application on JDK 27. The release was
verified on OpenJDK `27+35-2325` and PostgreSQL 18.6. Final JDK distribution
verification is still required before the ReAI production rollout. Kotlin record
consumers currently require the [documented bytecode configuration](kotlin-records.md).
The native runtime needs no Jakarta provider. Add `vev-jakarta4:1.0.0` only when
using its explicitly limited milestone facade.

## Compatibility promise

Within 1.x, patches preserve documented native source behavior and binary API
compatibility; minor releases may add opt-in mappings and typed operations. Unsafe
or incorrectly accepted database shapes may be rejected by a corrective release,
with a migration note. No release may silently weaken tenant isolation, optimistic
version checks, or whole-transaction failure semantics to gain compatibility.

Generated plan interfaces are a compiler/runtime SPI, not a handwritten extension
API. Keep processor, core, and PostgreSQL modules on the same exact version and
regenerate all mappings on upgrade. ABI 7 is required for 1.0.0. A schema fingerprint
change requires a reviewed application migration before deployment; regenerating
an unchanged fingerprint does not itself require a schema change.

The optional Jakarta 4 facade follows a milestone specification and is outside the
native compatibility promise. It is not TCK compliant. PostgreSQL major versions,
server topology, schema privileges, and supported mappings remain explicit parts
of the [profile](supported-profile.md). Upgrade support covers the latest 1.x patch;
there is no long-term support or incident-response SLA.

## Maintainer procedure

Use a dedicated feature worktree. Set the exact release version, update the scope
and next-step notes, and run the checked-in wrapper on JDK 27 against a disposable,
Vev-owned PostgreSQL 18 fixture:

```shell
VEV_TEST_ADMIN_JDBC_URL=jdbc:postgresql://127.0.0.1:55439/postgres \
  ./gradlew clean check integrationTest releaseBundle
```

`check` includes an isolated consumer of the published Maven/JPMS artifacts.
`releaseBundle` includes only files under the exact version, excluding timestamped
repository-root metadata and previous versions. Archive timestamps and ordering
are deterministic. Rebuild the bundle and compare hashes before publication.
Inspect archive contents, dependency metadata, licenses, and the intended Git diff.
Record application verification separately from library tests.

Commit and push the verified change, squash the reviewed release PR, fast-forward
the primary `main` checkout, and tag that exact commit `v1.0.0`. Create the GitHub
release with the bundle and its SHA-256 checksum file, and verify the remote tag,
release metadata, downloaded archive hash, and consumer build. Do not overwrite
released artifact bytes; publish a patch version for corrections. This procedure
does not deploy ReAI or merge its application migration PR.
