package no.beint.vev.benchmark;

import com.zaxxer.hikari.HikariDataSource;
import no.beint.vev.Batch;
import no.beint.vev.BoundedQuery;
import no.beint.vev.EntityLookup;
import no.beint.vev.QueryLimit;
import no.beint.vev.Rows;
import no.beint.vev.TenantAuthority;
import no.beint.vev.TenantScope;
import no.beint.vev.pg.PgQueries;
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
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(3)
@Threads(1)
public class VevBenchmark {
    private static final int TENANT_ID = 7;
    private static final long FIND_ONE_ID = 7_777L;
    private static final int SCAN_LIMIT = 256;
    private static final String INDEXED_EMAIL_VALUE = "account-7777@example.test";
    private static final int INDEXED_EMAIL_LIMIT = 1;
    private static final int INDEXED_ACTIVE_LIMIT = 32;
    private static final TenantAuthority<BenchmarkModelVev.Model, Integer> TENANT_AUTHORITY =
            BenchmarkModelVev.newTenantAuthority();
    private static final BoundedQuery<BenchmarkModelVev.Model, Account> ACCOUNT_SCAN =
            PgQueries.scanById(AccountVev.INSTANCE, new QueryLimit(SCAN_LIMIT));
    private static final BoundedQuery<BenchmarkModelVev.Model, Account> ACCOUNT_BY_EMAIL =
            PgQueries.equal(AccountVev.EMAIL, INDEXED_EMAIL_VALUE, new QueryLimit(INDEXED_EMAIL_LIMIT));
    private static final BoundedQuery<BenchmarkModelVev.Model, Account> ACTIVE_ACCOUNTS =
            PgQueries.equal(AccountVev.ACTIVE, true, new QueryLimit(INDEXED_ACTIVE_LIMIT));
    private static final String RAW_COLUMNS = "\"id\", \"tenant_id\", \"version\", \"email\", \"balance\", \"active\"";
    private static final String RAW_FIND_SQL = "SELECT " + RAW_COLUMNS
            + " FROM \"vev_bench\".\"account\" WHERE \"id\" = ? AND \"tenant_id\" = ?";
    private static final String RAW_FIND_MULTIPLE_SQL = "SELECT (\"__vev_row\".\"id\" IS NOT NULL), "
            + "\"__vev_row\".\"id\", \"__vev_row\".\"tenant_id\", \"__vev_row\".\"version\", "
            + "\"__vev_row\".\"email\", \"__vev_row\".\"balance\", \"__vev_row\".\"active\""
            + " FROM pg_catalog.unnest(?::pg_catalog.int8[]) WITH ORDINALITY"
            + " AS \"__vev_requested\"(\"key\", \"ordinality\")"
            + " LEFT JOIN \"vev_bench\".\"account\" AS \"__vev_row\""
            + " ON \"__vev_row\".\"id\" = \"__vev_requested\".\"key\""
            + " AND \"__vev_row\".\"tenant_id\" = ? ORDER BY \"__vev_requested\".\"ordinality\"";
    private static final String RAW_SCAN_SQL = "SELECT " + RAW_COLUMNS
            + " FROM \"vev_bench\".\"account\" WHERE \"tenant_id\" = ? ORDER BY \"id\" LIMIT ?";
    private static final String RAW_INDEXED_EMAIL_SQL = "SELECT " + RAW_COLUMNS
            + " FROM \"vev_bench\".\"account\" WHERE \"tenant_id\" = ? AND \"email\" = ?"
            + " ORDER BY \"id\" LIMIT ?";
    private static final String RAW_INDEXED_ACTIVE_SQL = "SELECT " + RAW_COLUMNS
            + " FROM \"vev_bench\".\"account\" WHERE \"tenant_id\" = ? AND \"active\" = ?"
            + " ORDER BY \"id\" LIMIT ?";

    private HikariDataSource dataSource;
    private PgVev<BenchmarkModelVev.Model, Integer> vev;
    private TenantScope<BenchmarkModelVev.Model, Integer> tenant;
    private Batch<Long> keys32;
    private Batch<Long> keys256;
    private Long[] rawKeys32;
    private Long[] rawKeys256;

    @Setup(Level.Trial)
    public void setup() throws SQLException {
        dataSource = BenchmarkDatabase.initialize();
        vev = new PgVev<>(dataSource, BenchmarkModelVev.POSTGRES, TENANT_AUTHORITY);
        tenant = TENANT_AUTHORITY.scope(TENANT_ID);
        keys32 = batch(32);
        keys256 = batch(256);
        rawKeys32 = keys32.values().toArray(Long[]::new);
        rawKeys256 = keys256.values().toArray(Long[]::new);
        try (Connection connection = dataSource.getConnection()) {
            BenchmarkDatabase.verifySeed(connection, TENANT_ID);
        }
        verifyChecksum("transactionOnly", transactionOnlyValue(), rawTransactionOnlyValue());
        verifyChecksum("findOne", findOneValue(), rawFindOneValue());
        verifyChecksum("findMultiple32", findMultipleValue(keys32), rawFindMultipleValue(rawKeys32));
        verifyChecksum("findMultiple256", findMultipleValue(keys256), rawFindMultipleValue(rawKeys256));
        verifyChecksum("boundedScan", boundedScanValue(), rawBoundedScanValue());
        verifyChecksum("indexedEmail", indexedEmailValue(), rawIndexedEmailValue());
        verifyChecksum("indexedActive32", indexedActive32Value(), rawIndexedActive32Value());
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Benchmark
    public long transactionOnly() {
        return transactionOnlyValue();
    }

    @Benchmark
    public long findOne() {
        return findOneValue();
    }

    @Benchmark
    public long findMultiple32() {
        return findMultipleValue(keys32);
    }

    @Benchmark
    public long findMultiple256() {
        return findMultipleValue(keys256);
    }

    @Benchmark
    public long boundedScan() {
        return boundedScanValue();
    }

    @Benchmark
    public long indexedEmail() {
        return indexedEmailValue();
    }

    @Benchmark
    public long indexedActive32() {
        return indexedActive32Value();
    }

    @Benchmark
    public long rawTransactionOnly() throws SQLException {
        return rawTransactionOnlyValue();
    }

    @Benchmark
    public long rawFindOne() throws SQLException {
        return rawFindOneValue();
    }

    @Benchmark
    public long rawFindMultiple32() throws SQLException {
        return rawFindMultipleValue(rawKeys32);
    }

    @Benchmark
    public long rawFindMultiple256() throws SQLException {
        return rawFindMultipleValue(rawKeys256);
    }

    @Benchmark
    public long rawBoundedScan() throws SQLException {
        return rawBoundedScanValue();
    }

    @Benchmark
    public long rawIndexedEmail() throws SQLException {
        return rawIndexedEmailValue();
    }

    @Benchmark
    public long rawIndexedActive32() throws SQLException {
        return rawIndexedActive32Value();
    }

    private long transactionOnlyValue() {
        return vev.read(tenant, transaction -> transaction.tenant().tenantId().longValue());
    }

    private long findOneValue() {
        return vev.read(tenant, transaction -> checksum(transaction.entities()
                .find(AccountVev.INSTANCE.key(FIND_ONE_ID))
                .orElseThrow()));
    }

    private long findMultipleValue(Batch<Long> keys) {
        return vev.read(tenant, transaction -> checksum(
                transaction.entities().findMultiple(AccountVev.INSTANCE, keys)));
    }

    private long boundedScanValue() {
        return vev.read(tenant, transaction -> checksum(transaction.entities().many(ACCOUNT_SCAN)));
    }

    private long indexedEmailValue() {
        return vev.read(tenant, transaction -> checksum(transaction.entities().many(ACCOUNT_BY_EMAIL)));
    }

    private long indexedActive32Value() {
        return vev.read(tenant, transaction -> checksum(transaction.entities().many(ACTIVE_ACCOUNTS)));
    }

    private long rawTransactionOnlyValue() throws SQLException {
        return rawRead(connection -> TENANT_ID);
    }

    private long rawFindOneValue() throws SQLException {
        return rawRead(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(RAW_FIND_SQL)) {
                statement.setLong(1, FIND_ONE_ID);
                statement.setInt(2, TENANT_ID);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("Synthetic find-one row is missing");
                    }
                    return checksum(readAccount(resultSet, 1));
                }
            }
        });
    }

    private long rawFindMultipleValue(Long[] keys) throws SQLException {
        return rawRead(connection -> {
            long checksum = 1;
            try (Array keyArray = connection.createArrayOf("bigint", keys);
                 PreparedStatement statement = connection.prepareStatement(RAW_FIND_MULTIPLE_SQL)) {
                statement.setArray(1, keyArray);
                statement.setInt(2, TENANT_ID);
                try (ResultSet resultSet = statement.executeQuery()) {
                    for (Long requestedKey : keys) {
                        if (!resultSet.next()) {
                            throw new IllegalStateException("Synthetic ordered batch returned too few rows");
                        }
                        checksum = mix(checksum, requestedKey);
                        if (resultSet.getBoolean(1)) {
                            checksum = mix(checksum, checksum(readAccount(resultSet, 2)));
                        } else {
                            checksum = mix(checksum, -1);
                        }
                    }
                    if (resultSet.next()) {
                        throw new IllegalStateException("Synthetic ordered batch returned too many rows");
                    }
                }
            }
            return checksum;
        });
    }

    private long rawBoundedScanValue() throws SQLException {
        return rawRead(connection -> {
            long checksum = 1;
            int count = 0;
            boolean hasMore;
            try (PreparedStatement statement = connection.prepareStatement(RAW_SCAN_SQL)) {
                statement.setInt(1, TENANT_ID);
                statement.setInt(2, SCAN_LIMIT + 1);
                statement.setFetchSize(SCAN_LIMIT + 1);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (count < SCAN_LIMIT && resultSet.next()) {
                        checksum = mix(checksum, checksum(readAccount(resultSet, 1)));
                        count++;
                    }
                    hasMore = resultSet.next();
                }
            }
            checksum = mix(checksum, count);
            return mix(checksum, hasMore ? 1 : 0);
        });
    }

    private long rawIndexedEmailValue() throws SQLException {
        return rawRead(connection -> rawIndexedChecksum(
                connection,
                RAW_INDEXED_EMAIL_SQL,
                INDEXED_EMAIL_LIMIT,
                statement -> statement.setString(2, INDEXED_EMAIL_VALUE)));
    }

    private long rawIndexedActive32Value() throws SQLException {
        return rawRead(connection -> rawIndexedChecksum(
                connection,
                RAW_INDEXED_ACTIVE_SQL,
                INDEXED_ACTIVE_LIMIT,
                statement -> statement.setBoolean(2, true)));
    }

    private static long rawIndexedChecksum(
            Connection connection,
            String sql,
            int limit,
            RawIndexedValueBinder valueBinder) throws SQLException {
        long checksum = 1;
        int count = 0;
        boolean hasMore;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, TENANT_ID);
            valueBinder.bind(statement);
            statement.setInt(3, Math.addExact(limit, 1));
            statement.setFetchSize(Math.addExact(limit, 1));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (count < limit && resultSet.next()) {
                    checksum = mix(checksum, checksum(readAccount(resultSet, 1)));
                    count++;
                }
                hasMore = resultSet.next();
            }
        }
        checksum = mix(checksum, count);
        return mix(checksum, hasMore ? 1 : 0);
    }

    private long rawRead(RawWork work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            BenchmarkDatabase.configureTenant(connection, TENANT_ID);
            try {
                long result = work.run(connection);
                BenchmarkDatabase.verifyTenantContext(connection, TENANT_ID);
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

    private static Batch<Long> batch(int size) {
        List<Long> keys = new ArrayList<>(size);
        for (long key = 1; key < size; key++) {
            keys.add(key);
        }
        keys.add(20_001L);
        return Batch.copyOf(keys);
    }

    private static Account readAccount(ResultSet resultSet, int firstColumn) throws SQLException {
        return new Account(
                resultSet.getLong(firstColumn),
                resultSet.getInt(firstColumn + 1),
                resultSet.getLong(firstColumn + 2),
                resultSet.getString(firstColumn + 3),
                resultSet.getBigDecimal(firstColumn + 4),
                resultSet.getBoolean(firstColumn + 5));
    }

    private static long checksum(Batch<EntityLookup<BenchmarkModelVev.Model, Account, Long>> lookups) {
        long checksum = 1;
        for (EntityLookup<BenchmarkModelVev.Model, Account, Long> lookup : lookups) {
            checksum = mix(checksum, lookup.key().value());
            checksum = lookup instanceof EntityLookup.Found<BenchmarkModelVev.Model, Account, Long> found
                    ? mix(checksum, checksum(found.entity()))
                    : mix(checksum, -1);
        }
        return checksum;
    }

    private static long checksum(Rows<Account> rows) {
        long checksum = 1;
        for (Account account : rows.values()) {
            checksum = mix(checksum, checksum(account));
        }
        checksum = mix(checksum, rows.values().size());
        return mix(checksum, rows.hasMore() ? 1 : 0);
    }

    private static long checksum(Account account) {
        long checksum = account.id();
        checksum = mix(checksum, account.tenantId());
        checksum = mix(checksum, account.version());
        checksum = mix(checksum, account.email().hashCode());
        checksum = mix(checksum, account.balance().unscaledValue().longValue());
        checksum = mix(checksum, account.balance().scale());
        return mix(checksum, account.active() ? 1 : 0);
    }

    private static long mix(long checksum, long value) {
        return checksum * 31 + value;
    }

    private static void verifyChecksum(String workload, long vevChecksum, long rawChecksum) {
        if (vevChecksum != rawChecksum) {
            throw new IllegalStateException(workload + " checksum mismatch: Vev=" + vevChecksum + ", raw=" + rawChecksum);
        }
    }

    @FunctionalInterface
    private interface RawWork {
        long run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface RawIndexedValueBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
