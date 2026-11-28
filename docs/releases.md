# Release procedure

`vevVersion` in `gradle.properties` controls artifact versions and the plugin's runtime
and compiler coordinates. Publish matching cluster, runtime, compiler, Gradle plugin
and plugin marker artifacts to Maven Central.

1. Run `CI=true ./gradlew clean check releaseBundle :benchmarks:jmhClasses`.
2. Run `./gradlew :benchmarks:buildPerformance`, then `./gradlew :benchmarks:jmh`.
   Run `python3 scripts/check-performance.py` and retain raw measurement evidence.
3. Run `python3 scripts/verify-release.py` against the bundled Maven repository.
4. Review the diff and require Linux/macOS correctness plus performance CI.
5. Tag the tested merge as `sql-v<version>` and attach the signed Maven bundle and
   SHA-256 checksum to its GitHub release. Attach `results.json` and `build-performance.json`
   from that source tree’s successful performance job before the CI artifacts expire.
6. Publish signed artifacts with `./gradlew publishAndReleaseToMavenCentral`.
7. Verify an independent Kotlin/Java consumer resolves `no.beint.vev` from Maven Central.

Keep signing/publishing credentials outside Git. Published Maven Central coordinates are
immutable. Each release targets the current JDK and PostgreSQL major version without
carrying compatibility layers for older versions.
