# Releasing Vev 2.0.0

Publish matching `no.beint.vev:cluster`, `runtime`, `compiler`, `gradle-plugin` and
Gradle marker `no.beint.vev` artifacts. Regenerate sources with matching versions.
Supported: JDK 27, Kotlin 2.5.0-Beta2, PostgreSQL 18 and matching pgjdbc.

1. Run `./gradlew clean check releaseBundle :benchmarks:jmhClasses`.
2. Run `./gradlew :benchmarks:buildPerformance :benchmarks:jmh`, retain raw JSON and
   environment information, and run `python3 scripts/check-performance.py`.
3. Run `python3 scripts/verify-release.py` for independent Kotlin/Java consumers from
   the bundled Maven repository, without injected classpaths. Inspect the runtime
   dependency graph and release repository.
4. Review and require Linux/macOS correctness plus performance CI on the tested revision.
5. Tag the tested merge as `sql-v2.0.0`. Published Maven Central coordinates are
   immutable and must not be overwritten.
6. Publish signed artifacts with externally supplied credentials using
   `publishAndReleaseToMavenCentral` when available.
7. Attach `build/distributions/vev-sql-2.0.0-maven.zip` and SHA-256 checksum to the
   GitHub release. Verify tag, POMs and assets describe the same tested revision.

The bundle is a standalone Maven repository. GitHub assets and Maven Central are
separate distribution channels. Never claim Central publication from a local bundle.
Keep all signing/publishing secrets outside Git and CI artifacts.
