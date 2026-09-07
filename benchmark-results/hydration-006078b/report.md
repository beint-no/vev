# Direct generated row-reader allocation campaign

The generated reader removes the temporary array formerly used to hydrate each
entity. Across two runs per Vev version, the 256-key workload allocated about
10.2 KB less per operation (6.7%). It returns **255 present snapshots and one
missing result**, consistent with removing one approximately 40-byte array per
present row. Point-read allocation was essentially unchanged. These findings
apply only to this six-column synthetic workload.

**This campaign establishes no latency advantage.** Before/after latency drifted
across runs, and the first baseline lacked complete host telemetry. Hibernate's
point-read means were lower than Vev's in these runs. Every result is retained,
including the initially mismatched Hibernate fetch-size configuration. No result
was discarded based on its score. This is engineering evidence, not a general ORM
performance ranking or a completed application replacement gate.

## Frozen code and reference correction

- Vev before and original Hibernate reference: `25852b84b29548678dc13a4d8430c0e5db3d8a45`.
- Vev after: `006078b85bedbc2440d169dce2e73417c1dddcfc`.
- Corrected Hibernate reference: `1a32db00142d1bd9dda6ef4276bad24ba83e23d0`.

The sequence was A1, H1, B1, B2, H2, A2, H3, H4. A is Vev before; B is Vev after.
H1/H2 used the older reference setting `hibernate.jdbc.fetch_size=256`. Review
found that Vev point and batch reads use the driver default of zero. H3/H4 use
zero as well, from a separately committed reference build; bounded page methods
continue to set their own explicit fetch size, but are not timed here. H1/H2 remain
visible as diagnostic results and are not the corrected reference comparison.
H3/H4 occurred after the original sequence, which further limits latency inference.
The two Vev versions used identical settings throughout.

The after change constructs records directly through generated checked JDBC
reads. It preserves scalar validation, tenant/key/version checks, exact returned
payload comparison, transaction ownership, and rollback behavior. Enum codecs
remain initialized once. The matching compiler/runtime generation SPI changed;
application mappings require recompilation. Validation at the after commit:
274 passing tests, including Java/Kotlin compiled models, 512-entity compilation,
all supported scalar shapes, adversarial reads/writes, and a published consumer.
The reference correction also passed its module checks and parity setup.

## Measurements

Each table reports JMH's mean and reported 99.9% error, median, p95, sampled
operation count, and normalized allocated bytes. JMH confidence intervals do not
account for uncontrolled host drift. There are 24 measurement iterations per run
and workload (three independent JVM forks with eight iterations each).
The CSV retains full numeric precision and allocation errors.

### findMultiple256

| Run | Samples | Mean ± error (µs/op) | Median (µs) | p95 (µs) | Allocated (B/op) |
|---|---:|---:|---:|---:|---:|
| a1-before | 57,683 | 418.727 ± 0.232 | 416.256 | 440.320 | 151490.302 |
| h1-hibernate | 21,245 | 1137.522 ± 1.428 | 1128.448 | 1187.840 | 328305.555 |
| b1-after | 58,495 | 412.639 ± 0.236 | 410.624 | 434.176 | 141295.986 |
| b2-after | 58,528 | 412.872 ± 1.568 | 410.624 | 436.736 | 141277.888 |
| h2-hibernate | 21,207 | 1137.916 ± 1.535 | 1128.448 | 1189.888 | 328311.098 |
| a2-before | 60,362 | 400.187 ± 0.327 | 396.800 | 424.448 | 151496.649 |
| h3-hibernate-fetch-zero | 21,378 | 1129.539 ± 1.521 | 1124.352 | 1185.792 | 328276.533 |
| h4-hibernate-fetch-zero | 21,259 | 1136.393 ± 1.509 | 1126.400 | 1187.840 | 328106.010 |

### findOne

| Run | Samples | Mean ± error (µs/op) | Median (µs) | p95 (µs) | Allocated (B/op) |
|---|---:|---:|---:|---:|---:|
| a1-before | 135,294 | 178.727 ± 0.122 | 177.408 | 196.608 | 10061.477 |
| h1-hibernate | 142,187 | 169.675 ± 0.179 | 167.168 | 190.720 | 12974.159 |
| b1-after | 134,898 | 178.863 ± 0.133 | 177.408 | 196.608 | 10041.567 |
| b2-after | 134,896 | 178.988 ± 0.134 | 177.664 | 196.864 | 10056.572 |
| h2-hibernate | 145,282 | 166.202 ± 0.185 | 164.096 | 187.904 | 13068.723 |
| a2-before | 141,409 | 170.811 ± 0.128 | 168.704 | 190.464 | 10044.815 |
| h3-hibernate-fetch-zero | 143,241 | 168.560 ± 0.164 | 166.144 | 189.696 | 12763.060 |
| h4-hibernate-fetch-zero | 143,690 | 167.972 ± 0.165 | 165.632 | 189.184 | 12859.355 |

The equal-weight mean of the two Vev batch-allocation run scores is 151,493.475 B/op before and 141,286.937 B/op after: a reduction of 10,206.539 B/op (6.737%). This descriptive allocation summary does not pool latency samples or assert statistical independence between operations.

## Environment and workload

- OpenJDK RC `27+35-2325`, OpenJDK 64-Bit Server VM, native arm64, no additional JVM arguments.
- macOS 26.6.2 / Darwin 25.6.0, Apple M5 Max, 18 logical CPUs, 64 GiB memory.
  AC power was observed during the campaign, battery fully charged, configured AC `powermode=0`.
- PostgreSQL 18.6 on the same machine in the isolated, ownership-attested
  `vev_bench` fixture. All lanes derived their runtime URL from the same numeric
  loopback administrator URL on dedicated port 55439. No remote database or
  application-derived row was used.
- pgjdbc 42.7.13; HikariCP 7.1.0, minimum/maximum pool size 8, one benchmark thread.
- Hibernate ORM `8.0.0.Beta1` and Jakarta Persistence `4.0.0-M6`, both explicitly
  prerelease dependencies. This is not a measurement against a final provider or
  the application's production Hibernate version.
- JMH 1.37, sample-time mode, three JVM forks, five one-second warmups and eight
  one-second measurements per workload per run, GC profiler. Setup, schema
  recreation, provider bootstrap, and parity checks occur outside timing.
- Each trial recreates the same deterministic 10,000-row synthetic account table,
  with sequential IDs, a single fixture tenant, generated example-domain email
  strings, decimal `(19,4)` balances derived from IDs, and alternating booleans.
  The primary key is tenant-qualified; email and active have tenant-leading
  indexes. A separate 32-row update fixture is prepared but not timed here.
- `findOne` reads the fixed present ID 7,777. `findMultiple256` requests IDs 1–255
  plus one absent ID and consumes ordered results including the missing position.
  Both implementations verify seed and result checksums against JDBC before timing.
- Each measured invocation includes a complete read-only `SERIALIZABLE`
  transaction, tenant/fingerprint context, synchronous commit, UTC, 30-second
  statement/lock deadlines, 120-second transaction deadline, 180-second network
  timeout, and pre-commit connection-state attestation. Physical connections start
  with `search_path=pg_catalog` and UTF-8. Prepared statement cache/threshold
  settings are pgjdbc defaults; setup and warmups warm caches independently in
  each fresh fork. Hibernate's second-level/query caches and JDBC write batching
  are disabled.
- Vev reuses its runtime and maps an immutable record with a scoped scalar ID.
  Hibernate opens/closes a `StatelessSession` through `createEntityAgent()` each
  invocation and maps a mutable entity with `IdClass`. SQL strategy, identifier
  packaging, provider lifecycle, and object shape differ. The results compare
  equivalent checked outcomes for these operations, not identical APIs or a TCK
  conformance claim.

## Host limitations and retained evidence

The first shared telemetry capture began late in A1 and is not a complete
per-run trace for A1/H1. Later runs have a pre-run snapshot and periodic `top`
output, with the owned monitor terminated after each lane. The complete traces
show substantial aggregate CPU idle time, but memory use was high with compressed
memory, and unrelated application activity was not controlled. Aggregate idle
percentages cannot prove absence of scheduler, storage, thermal, or per-core
interference. Builds and database verification were idle during measured lanes;
light source inspection and editing occurred. The repeat timing variation is
therefore retained without attributing it to the reader change.

The [raw directory](raw/) contains every JMH JSON result (losslessly gzip-compressed), full stdout, available
execution timestamps/arguments, library archive hashes, and host telemetry.
Local installation/output paths were replaced with `<JDK_HOME>` and `<CAMPAIGN>`;
no scores, histograms, warnings, or samples were filtered. Original unredacted
output SHA-256 values are retained separately from the checked-in file hashes.
[metrics.csv](metrics.csv) contains extracted full-precision metrics;
[telemetry-summary.json](telemetry-summary.json) reports aggregate CPU idle samples;
[SHA256SUMS](SHA256SUMS) verifies the checked-in bundle.

## Reproduction

Use the exact JDK build and a separately prepared disposable PostgreSQL 18 cluster.
The fixture deliberately recreates only ownership-attested benchmark state.
Follow the repository benchmark policy before supplying an administrator URL.
Build each immutable checkout with the checked-in wrapper:

```sh
./gradlew :vev-benchmark-vev:installDist :vev-benchmark-hibernate:installDist
```

Keep each distribution and its libraries separate. Run the launchers serially
with `VEV_BENCH_ADMIN_JDBC_URL` pointing to the same disposable administrator URL
and `JAVA_HOME` selecting the recorded JDK. For Vev use the filter
`^no.beint.vev.benchmark.VevBenchmark.(findOne|findMultiple256)$`; for Hibernate use
`^no.beint.vev.benchmark.hibernate.HibernateEntityAgentBenchmark.(findOne|findMultiple256)$`.
Every run used these remaining arguments:

```text
-f 3 -wi 5 -i 8 -w 1s -r 1s -t 1 -bm sample -prof gc -rf json -rff RUN.json
```

Capture host conditions before and throughout each run, retain all raw output,
perform no concurrent build or database workload, and report every difference
from this environment. Repeat allocation findings across more entity widths,
codecs, result sizes, write paths, and deployment environments before generalizing.
Compile/startup cost, application workflow performance, and production recovery
are not measured by this campaign.
