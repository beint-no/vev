package no.beint.vev.benchmark;

import com.zaxxer.hikari.HikariDataSource;
import no.beint.vev.Batch;
import no.beint.vev.TenantAuthority;
import no.beint.vev.TenantScope;
import no.beint.vev.pg.PgVev;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 1_024, batchSize = 1)
@Measurement(iterations = 2_048, batchSize = 1)
@Fork(3)
@Threads(1)
public class VevBatchUpdateBenchmark {
    private static final TenantAuthority<BenchmarkModelVev.Model, Integer> TENANT_AUTHORITY =
            BenchmarkModelVev.newTenantAuthority();
    private static final String RAW_UPDATE_SQL = """
            WITH "__vev_arrays" AS MATERIALIZED (
                SELECT ?::pg_catalog.int8[] AS "id",
                       ?::pg_catalog.int4[] AS "tenant_id",
                       ?::pg_catalog.int8[] AS "version",
                       ?::pg_catalog.numeric[] AS "balance",
                       ?::pg_catalog.int4 AS "__vev_expected"
            ), "__vev_input" AS MATERIALIZED (
                SELECT "__vev_row".*
                  FROM "__vev_arrays"
                  CROSS JOIN LATERAL ROWS FROM (
                      pg_catalog.unnest("__vev_arrays"."id"),
                      pg_catalog.unnest("__vev_arrays"."tenant_id"),
                      pg_catalog.unnest("__vev_arrays"."version"),
                      pg_catalog.unnest("__vev_arrays"."balance")
                  ) WITH ORDINALITY AS "__vev_row"(
                      "id", "tenant_id", "version", "balance", "__vev_ordinality")
                 WHERE pg_catalog.cardinality("__vev_arrays"."id") = "__vev_arrays"."__vev_expected"
                   AND pg_catalog.cardinality("__vev_arrays"."tenant_id") = "__vev_arrays"."__vev_expected"
                   AND pg_catalog.cardinality("__vev_arrays"."version") = "__vev_arrays"."__vev_expected"
                   AND pg_catalog.cardinality("__vev_arrays"."balance") = "__vev_arrays"."__vev_expected"
            ), "__vev_matched" AS MATERIALIZED (
                SELECT "__vev_input"."__vev_ordinality"
                  FROM "__vev_input"
                  JOIN "vev_bench"."update_account" AS "__vev_target"
                    ON "__vev_target"."id" = "__vev_input"."id"
                   AND "__vev_target"."tenant_id" = "__vev_input"."tenant_id"
                   AND "__vev_target"."version" = "__vev_input"."version"
            ), "__vev_applied" AS (
                UPDATE "vev_bench"."update_account" AS "__vev_target"
                   SET "balance" = "__vev_input"."balance",
                       "version" = "__vev_target"."version" + 1
                  FROM "__vev_input"
                 WHERE "__vev_target"."id" = "__vev_input"."id"
                   AND "__vev_target"."tenant_id" = "__vev_input"."tenant_id"
                   AND "__vev_target"."version" = "__vev_input"."version"
                   AND (SELECT pg_catalog.count(*) FROM "__vev_matched") =
                       (SELECT "__vev_expected" FROM "__vev_arrays")
                RETURNING "__vev_input"."__vev_ordinality",
                          "__vev_target"."id",
                          "__vev_target"."tenant_id",
                          "__vev_target"."version",
                          "__vev_target"."balance"
            )
            SELECT "__vev_ordinality", "id", "tenant_id", "version", "balance"
              FROM "__vev_applied"
             ORDER BY "__vev_ordinality"
            """;

    private HikariDataSource dataSource;
    private PgVev<BenchmarkModelVev.Model, Integer> vev;
    private TenantScope<BenchmarkModelVev.Model, Integer> tenant;
    private long expectedVersion;

    @Setup(Level.Trial)
    public void setup() throws SQLException {
        dataSource = BenchmarkDatabase.initialize();
        try {
            vev = new PgVev<>(dataSource, BenchmarkModelVev.POSTGRES, TENANT_AUTHORITY);
            tenant = TENANT_AUTHORITY.scope(BatchUpdateWorkload.TENANT_ID);
            try (Connection connection = dataSource.getConnection()) {
                BenchmarkDatabase.verifySeed(connection, BatchUpdateWorkload.TENANT_ID);
                BenchmarkDatabase.verifyUpdateRows(connection, BatchUpdateWorkload.TENANT_ID, 0L);
            }
            verifyNativeAndRawParity();
            expectedVersion = 0L;
        } catch (SQLException | RuntimeException | Error failure) {
            dataSource.close();
            throw failure;
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws SQLException {
        if (dataSource == null) {
            return;
        }
        try {
            try (Connection connection = dataSource.getConnection()) {
                BenchmarkDatabase.verifyUpdateRows(
                        connection,
                        BatchUpdateWorkload.TENANT_ID,
                        expectedVersion);
            }
        } finally {
            try (Connection connection = dataSource.getConnection()) {
                BenchmarkDatabase.resetUpdateRows(connection, BatchUpdateWorkload.TENANT_ID);
            } finally {
                dataSource.close();
            }
        }
    }

    @Benchmark
    public long updateMultiple32() {
        long currentVersion = expectedVersion;
        Batch<UpdateAccount> requested = BatchUpdateWorkload.request(currentVersion);
        long checksum = vev.write(tenant, transaction -> BatchUpdateWorkload.checksum(
                requested,
                transaction.entities().updateMultiple(UpdateAccountVev.INSTANCE, requested)));
        expectedVersion = Math.addExact(currentVersion, 1L);
        return checksum;
    }

    @Benchmark
    public long rawUpdateMultiple32() throws SQLException {
        long currentVersion = expectedVersion;
        Batch<UpdateAccount> requested = BatchUpdateWorkload.request(currentVersion);
        long checksum = rawWrite(connection -> rawUpdate(connection, requested));
        expectedVersion = Math.addExact(currentVersion, 1L);
        return checksum;
    }

    private void verifyNativeAndRawParity() throws SQLException {
        Batch<UpdateAccount> requested = BatchUpdateWorkload.request(0L);
        IllegalStateException rollbackSignal = new IllegalStateException("rollback Vev batch-update setup probe");
        long[] nativeChecksum = new long[1];
        try {
            vev.write(tenant, transaction -> {
                nativeChecksum[0] = BatchUpdateWorkload.checksum(
                        requested,
                        transaction.entities().updateMultiple(UpdateAccountVev.INSTANCE, requested));
                throw rollbackSignal;
            });
            throw new IllegalStateException("Vev batch-update setup probe unexpectedly committed");
        } catch (IllegalStateException failure) {
            if (failure != rollbackSignal) {
                throw failure;
            }
        }
        try (Connection connection = dataSource.getConnection()) {
            BenchmarkDatabase.verifyUpdateRows(connection, BatchUpdateWorkload.TENANT_ID, 0L);
        }

        long rawChecksum;
        try (Connection connection = dataSource.getConnection()) {
            BenchmarkDatabase.configureTenant(connection, BatchUpdateWorkload.TENANT_ID, false);
            try {
                rawChecksum = rawUpdate(connection, requested);
                long persistedChecksum = BatchUpdateWorkload.checksum(
                        requested,
                        BenchmarkDatabase.readUpdateRows(connection, BatchUpdateWorkload.TENANT_ID));
                if (persistedChecksum != rawChecksum) {
                    throw new IllegalStateException("Raw batch-update RETURNING rows differ from stored state");
                }
                BenchmarkDatabase.verifyTenantContext(connection, BatchUpdateWorkload.TENANT_ID, false);
            } finally {
                connection.rollback();
            }
        }
        if (nativeChecksum[0] != rawChecksum) {
            throw new IllegalStateException(
                    "Batch-update checksum mismatch: Vev=" + nativeChecksum[0] + ", raw=" + rawChecksum);
        }
        try (Connection connection = dataSource.getConnection()) {
            BenchmarkDatabase.verifyUpdateRows(connection, BatchUpdateWorkload.TENANT_ID, 0L);
        }
    }

    private long rawWrite(RawWork work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            BenchmarkDatabase.configureTenant(connection, BatchUpdateWorkload.TENANT_ID, false);
            try {
                long result = work.run(connection);
                BenchmarkDatabase.verifyTenantContext(connection, BatchUpdateWorkload.TENANT_ID, false);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    private static long rawUpdate(Connection connection, Batch<UpdateAccount> requested) throws SQLException {
        Long[] ids = new Long[BatchUpdateWorkload.SIZE];
        Integer[] tenants = new Integer[BatchUpdateWorkload.SIZE];
        Long[] versions = new Long[BatchUpdateWorkload.SIZE];
        BigDecimal[] balances = new BigDecimal[BatchUpdateWorkload.SIZE];
        for (int index = 0; index < BatchUpdateWorkload.SIZE; index++) {
            UpdateAccount account = requested.get(index);
            ids[index] = account.id();
            tenants[index] = account.tenantId();
            versions[index] = account.version();
            balances[index] = account.balance();
        }
        List<UpdateAccount> returned = new ArrayList<>(BatchUpdateWorkload.SIZE);
        try (Array idArray = connection.createArrayOf("pg_catalog.int8", ids);
             Array tenantArray = connection.createArrayOf("pg_catalog.int4", tenants);
             Array versionArray = connection.createArrayOf("pg_catalog.int8", versions);
             Array balanceArray = connection.createArrayOf("pg_catalog.numeric", balances);
             PreparedStatement statement = connection.prepareStatement(RAW_UPDATE_SQL)) {
            statement.setArray(1, idArray);
            statement.setArray(2, tenantArray);
            statement.setArray(3, versionArray);
            statement.setArray(4, balanceArray);
            statement.setInt(5, BatchUpdateWorkload.SIZE);
            try (ResultSet resultSet = statement.executeQuery()) {
                for (int index = 0; index < BatchUpdateWorkload.SIZE; index++) {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("Raw batch update returned too few rows");
                    }
                    long ordinality = resultSet.getLong(1);
                    if (resultSet.wasNull() || ordinality != index + 1L) {
                        throw new IllegalStateException("Raw batch update returned invalid ordinality at " + index);
                    }
                    returned.add(new UpdateAccount(
                            resultSet.getLong(2),
                            resultSet.getInt(3),
                            resultSet.getLong(4),
                            resultSet.getBigDecimal(5)));
                }
                if (resultSet.next()) {
                    throw new IllegalStateException("Raw batch update returned too many rows");
                }
            }
        }
        return BatchUpdateWorkload.checksum(requested, returned);
    }

    @FunctionalInterface
    private interface RawWork {
        long run(Connection connection) throws SQLException;
    }
}
