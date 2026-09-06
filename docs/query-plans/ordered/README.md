# Ordered continuation plan evidence

Captured on 2026-09-06 by the integration suite for the ordered-index change.
The checked-in SQL is the exact prepared entity SQL observed during Vev
execution; its `?` placeholders use the parameters below. Each JSON file is the
unfiltered PostgreSQL `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` of that SQL on the
same fixture through the application role. The SQL files add only a trailing
semicolon and newline. These files contain synthetic data exclusively.

Environment: OpenJDK RC `27+35-2325`, pgjdbc `42.7.13`, PostgreSQL 18.6
(Homebrew, aarch64, Apple clang 21.0.0), database UTF8 / libc `C` collation,
macOS 26.6.2 on Apple M5 Max. The disposable local PostgreSQL instance uses port
55439. No planner method was forced or disabled. Timings reflect a local,
warm-cache test run and are not benchmark results or performance comparisons.

The fixture creates an ordinary table with integer `id`, `tenant_id`, `version`,
nullable varchar(64) `category`, boolean `enabled`, integer `rank_value`, and
non-null varchar(64) `order`. It inserts IDs 1 through 20,000, all in tenant 7,
with version 0, category `group`, enabled true, rank `id % 100`, and `order`
equal to the decimal ID string. `ANALYZE` runs before the query. The table has a
`(tenant_id, id)` primary key, forced tenant RLS, the exact scoped role policy,
and these indexes:

```sql
CREATE INDEX ranked_item_category_idx ON vev_it.ranked_item
    (tenant_id, category, rank_value, id);
CREATE INDEX ranked_item_enabled_idx ON vev_it.ranked_item
    (tenant_id, enabled, "order" DESC, id DESC);
```

| Evidence | JDBC parameters in order | Observed access |
|---|---|---|
| [Ascending SQL](ordered-index-query.sql), [raw plan](ordered-index-explain.json) | `7`, `"group"`, `50`, `10000`, `9` | Category index, tuple `>` condition, no Sort node |
| [Descending SQL](descending-ordered-index-query.sql), [raw plan](descending-ordered-index-explain.json) | `7`, `true`, `"15000"`, `10000`, `9` | Enabled index, tuple `<` condition, no Sort node |

Vev requested 8 snapshots and fetched one sentinel to report `hasMore`. The
EXPLAIN runs use a read-only SERIALIZABLE transaction with tenant setting 7,
then roll back. Each index scan reports 9 actual rows. Plan choices may differ
with row counts, selectivity, statistics, settings, PostgreSQL builds, or data
ordering. This evidence verifies the selected query shapes; it does not measure
ORM overhead or establish a latency advantage over Hibernate.

Reproduce with JDK 27 and `VEV_TEST_ADMIN_JDBC_URL` pointing to a disposable
PostgreSQL 18 cluster, then run `./gradlew integrationTest`. The owned-fixture
rules in [CONTRIBUTING](../../../CONTRIBUTING.md) apply. The two tests are
`orderedContinuationUsesItsDeclaredIndexWithoutASeparateSortOnTheSyntheticFixture`
and `descendingOrderedContinuationUsesItsDeclaredIndexWithoutASeparateSort` in
[VevPostgresIntegrationTest](../../../vev-integration-tests/src/integrationTest/java/no/beint/vev/it/VevPostgresIntegrationTest.java).
Generated SQL and raw plans are written under that module's `build/reports`.
The adjacent `SHA256SUMS` covers the four evidence files exactly.
