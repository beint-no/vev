# Release procedure

`vevVersion` in `gradle.properties` controls artifact versions and the plugin's runtime
and compiler coordinates. Publish matching cluster, runtime, compiler, Gradle plugin
and plugin marker artifacts.

1. Run `CI=true ./gradlew clean check releaseBundle :benchmarks:jmhClasses`.
2. Run `./gradlew :benchmarks:buildPerformance`, then `./gradlew :benchmarks:jmh`.
   Run `python3 scripts/check-performance.py` and retain raw measurement evidence.
3. Run `python3 scripts/verify-release.py` against the bundled Maven repository.
4. Review the diff and require Linux/macOS correctness plus performance CI.
5. Tag the tested merge as `sql-v<version>` and attach the signed Maven bundle and
   SHA-256 checksum to its GitHub release.
6. Append the validated Maven tree to `maven/` on `codex/maven-repository` in this
   repository. Preserve published version files, merge the version lists in Maven
   metadata and regenerate its checksums. GitHub Pages serves that branch directly.
7. Verify public artifact checksums and compile/run an independent consumer using
   `https://beint-no.github.io/vev/maven`.

Keep signing/publishing credentials outside Git. Published coordinates are immutable.
Maven Central is a separate publication target; use `publishAndReleaseToMavenCentral`
when available and verify it independently. The GitHub Pages repository is the supported
public distribution documented in the README.
