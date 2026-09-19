# Releases

SQL-first Vev uses `no.beint.vev:runtime`, `no.beint.vev:compiler`, and
`no.beint.vev:gradle-plugin`, with the Gradle marker `no.beint.vev`. Version 1.0.0
is tagged `sql-v1.0.0`. Use matching compiler, plugin, and runtime versions and
regenerate application sources on upgrades.

Release gates:

1. `./gradlew clean check releaseBundle` on JDK 27 and PostgreSQL 18.
2. Compile application trials against the generated Maven repository, then verify
   them against the published artifacts. Inspect the runtime dependency graph.
3. Run JMH and retain raw output with exact versions and limitations. Performance
   evidence is descriptive, not a machine-independent pass/fail threshold.
4. Inspect the complete diff and generated APIs. Commit, merge, and tag the tested
   source. Publish signed artifacts with `publishAndReleaseToMavenCentral` and
   attach the Maven repository bundle plus SHA-256 checksums to the GitHub release.

`releaseBundle` writes `build/distributions/vev-sql-1.0.0-maven.zip`. It is also a
standalone Maven repository: extract it and add its directory to both
`pluginManagement.repositories` and application dependency repositories.
The compiler/runtime jars, sources, Javadoc, POMs, module metadata and plugin
marker are included. Signing credentials are supplied externally through the
standard Maven Publish plugin properties; never put secrets in this repository.

Only the latest 1.x release is maintained. No support is promised for older JDKs
or PostgreSQL majors. Conservative nullability may become more precise in later
releases; unsupported type support is added only with compiler and database tests.
