package no.beint.vev.benchmark.hibernate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.postgresql.PGConnection;

final class BenchmarkDataset {
    static final String SCHEMA_NAME = "vev_bench";
    static final String TABLE_NAME = "account";
    static final String UPDATE_TABLE_NAME = "update_account";
    static final int TENANT_ID = 7;
    static final int ROW_COUNT = 10_000;
    static final int FETCH_SIZE = 256;
    static final long FIND_ONE_ID = 7_777L;
    static final long MISSING_ID = 20_001L;
    static final int FIND_MULTIPLE_32_PRESENT_COUNT = 31;
    static final int FIND_MULTIPLE_256_PRESENT_COUNT = 255;
    static final int SCAN_SIZE = 256;
    static final String INDEXED_EMAIL_VALUE = "account-7777@example.test";
    static final int INDEXED_EMAIL_LIMIT = 1;
    static final int INDEXED_ACTIVE_LIMIT = 32;
    static final int STATEMENT_TIMEOUT_MILLISECONDS = 30_000;
    static final int TRANSACTION_TIMEOUT_MILLISECONDS = 120_000;
    static final int NETWORK_TIMEOUT_MILLISECONDS = 180_000;
    static final String MODEL_NAME = "no.beint.vev.benchmark.BenchmarkModel";
    static final String MODEL_FINGERPRINT =
            "sha256:7227fca5a880759306c997d7118a47553364860b65292b23d74946c981193e89";
    static final String FIXTURE_OWNERSHIP_MARKER = "vev-owned-fixture:vev_bench:v1";

    private static final int POSTGRESQL_18_VERSION_NUMBER = 180_000;
    private static final int POSTGRESQL_19_VERSION_NUMBER = 190_000;
    private static final int INSERT_BATCH_SIZE = 512;
    private static final long COLLECTION_CHECKSUM_SEED = 0xbb67ae8584caa73bL;
    private static final long MISSING_VALUE_CHECKSUM = 0x3c6ef372fe94f82bL;
    private static final Executor NETWORK_TIMEOUT_EXECUTOR = command ->
            Thread.ofVirtual().name("vev-hibernate-benchmark-network-timeout").start(command);

    private BenchmarkDataset() {
    }

    static DatasetSummary prepare(
            BenchmarkAdminConfiguration adminConfiguration,
            BenchmarkDatabaseConfiguration databaseConfiguration) throws SQLException {
        prepareDatabase(adminConfiguration);
        try (var connection = adminConfiguration.openRuntimeConnection()) {
            assertPostgreSql18(connection);
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setReadOnly(false);
            try {
                createSchema(connection);
                replaceRows(connection);
                analyzeTable(connection);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                rollbackAfterFailure(connection, failure);
                throw failure;
            }
        }
        return verify(databaseConfiguration);
    }

    static DatasetSummary verify(BenchmarkDatabaseConfiguration configuration) throws SQLException {
        try (var connection = configuration.openConnection(true)) {
            assertPostgreSql18(connection);
            prepareReadConnection(connection);
            installTrustedSearchPath(connection);
            installUtf8Transport(connection);
            var databaseIdentity = readDatabaseIdentity(connection);
            applyTransactionContext(connection, databaseIdentity, true);
            var summary = verifyJdbcRows(connection);
            BatchUpdateWorkload.stateChecksum(0L, readUpdateRows(connection));
            verifyTransactionContext(connection, databaseIdentity, true);
            connection.rollback();
            return summary;
        }
    }

    static DatabaseIdentity verifyDatabaseIdentity(BenchmarkDatabaseConfiguration configuration) throws SQLException {
        try (var connection = configuration.openConnection(true)) {
            assertPostgreSql18(connection);
            prepareReadConnection(connection);
            installTrustedSearchPath(connection);
            installUtf8Transport(connection);
            var databaseIdentity = readDatabaseIdentity(connection);
            applyTransactionContext(connection, databaseIdentity, true);
            verifyTransactionContext(connection, databaseIdentity, true);
            connection.rollback();
            return databaseIdentity;
        }
    }

    static void resetUpdateRows(BenchmarkDatabaseConfiguration configuration) throws SQLException {
        try (var connection = configuration.openConnection(false)) {
            assertPostgreSql18(connection);
            prepareWriteConnection(connection);
            installTrustedSearchPath(connection);
            installUtf8Transport(connection);
            var databaseIdentity = readDatabaseIdentity(connection);
            applyTransactionContext(connection, databaseIdentity, false);
            try {
                try (var statement = connection.prepareStatement("""
                        UPDATE vev_bench.update_account
                           SET version = 0,
                               balance = ((200000 + id)::pg_catalog.numeric / 100)::pg_catalog.numeric(19, 4)
                         WHERE tenant_id = ?
                        """)) {
                    statement.setInt(1, TENANT_ID);
                    if (statement.executeUpdate() != BatchUpdateWorkload.SIZE) {
                        throw new IllegalStateException("Update fixture reset did not affect exactly 32 rows");
                    }
                }
                BatchUpdateWorkload.stateChecksum(0L, readUpdateRows(connection));
                verifyTransactionContext(connection, databaseIdentity, false);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                rollbackAfterFailure(connection, failure);
                throw failure;
            }
        }
    }

    static void verifyUpdateRows(BenchmarkDatabaseConfiguration configuration, long version) throws SQLException {
        try (var connection = configuration.openConnection(true)) {
            assertPostgreSql18(connection);
            prepareReadConnection(connection);
            installTrustedSearchPath(connection);
            installUtf8Transport(connection);
            var databaseIdentity = readDatabaseIdentity(connection);
            applyTransactionContext(connection, databaseIdentity, true);
            BatchUpdateWorkload.stateChecksum(version, readUpdateRows(connection));
            verifyTransactionContext(connection, databaseIdentity, true);
            connection.rollback();
        }
    }

    static List<BenchmarkUpdateAccount> readUpdateRows(Connection connection) throws SQLException {
        var accounts = new ArrayList<BenchmarkUpdateAccount>(BatchUpdateWorkload.SIZE);
        try (var statement = connection.prepareStatement("""
                SELECT id, tenant_id, version, balance
                  FROM vev_bench.update_account
                 WHERE tenant_id = ?
                 ORDER BY id
                """)) {
            statement.setInt(1, TENANT_ID);
            statement.setFetchSize(BatchUpdateWorkload.SIZE);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    accounts.add(new BenchmarkUpdateAccount(
                            rows.getLong(1),
                            rows.getInt(2),
                            rows.getLong(3),
                            rows.getBigDecimal(4)));
                }
            }
        }
        return List.copyOf(accounts);
    }

    static BenchmarkAccountId identifier(long id) {
        return new BenchmarkAccountId(id, TENANT_ID);
    }

    static List<BenchmarkAccountId> findMultipleIdentifiers(int presentCount) {
        var identifiers = new ArrayList<BenchmarkAccountId>(presentCount + 1);
        for (long id = 1; id <= presentCount; id++) {
            identifiers.add(identifier(id));
        }
        identifiers.add(identifier(MISSING_ID));
        return List.copyOf(identifiers);
    }

    static BenchmarkAccount expectedAccount(long id) {
        if (id < 1 || id > ROW_COUNT) {
            throw new IllegalArgumentException("Identifier is outside the synthetic dataset: " + id);
        }
        return new BenchmarkAccount(
                id,
                TENANT_ID,
                0L,
                "account-" + id + "@example.test",
                BigDecimal.valueOf(id % 100_000, 2).setScale(4),
                id % 2 == 0);
    }

    static List<BenchmarkAccount> expectedAccounts(long firstId, int count) {
        var accounts = new ArrayList<BenchmarkAccount>(count);
        for (long id = firstId; id < firstId + count; id++) {
            accounts.add(expectedAccount(id));
        }
        return List.copyOf(accounts);
    }

    static List<BenchmarkAccount> expectedFindMultipleAccounts(int presentCount) {
        var accounts = new ArrayList<BenchmarkAccount>(presentCount + 1);
        accounts.addAll(expectedAccounts(1, presentCount));
        accounts.add(null);
        return accounts;
    }

    static List<BenchmarkAccount> expectedActiveAccounts(int count) {
        var accounts = new ArrayList<BenchmarkAccount>(count);
        for (long id = 2; accounts.size() < count; id += 2) {
            accounts.add(expectedAccount(id));
        }
        return List.copyOf(accounts);
    }

    static long benchmarkChecksum(BenchmarkAccount account) {
        if (account == null) {
            throw new IllegalStateException("Benchmark query returned no account");
        }
        return account.stableChecksum();
    }

    static long benchmarkChecksum(List<BenchmarkAccount> accounts) {
        return summarize(accounts).combinedChecksum();
    }

    static long findMultipleChecksum(
            List<BenchmarkAccount> accounts,
            List<BenchmarkAccountId> identifiers) {
        if (accounts.size() != identifiers.size()) {
            throw new IllegalStateException(
                    "Hibernate findMultiple returned " + accounts.size() + " values for " + identifiers.size() + " keys");
        }
        long checksum = 1;
        for (int index = 0; index < identifiers.size(); index++) {
            checksum = mix(checksum, identifiers.get(index).id());
            var account = accounts.get(index);
            checksum = mix(checksum, account == null ? -1 : account.stableChecksum());
        }
        return checksum;
    }

    static long boundedScanChecksum(List<BenchmarkAccount> accounts) {
        if (accounts.size() != SCAN_SIZE + 1) {
            throw new IllegalStateException(
                    "Hibernate bounded scan returned " + accounts.size() + " rows, expected " + (SCAN_SIZE + 1));
        }
        long checksum = 1;
        for (int index = 0; index < SCAN_SIZE; index++) {
            checksum = mix(checksum, accounts.get(index).stableChecksum());
        }
        checksum = mix(checksum, SCAN_SIZE);
        return mix(checksum, accounts.size() > SCAN_SIZE ? 1 : 0);
    }

    static long indexedPageChecksum(List<BenchmarkAccount> accounts, int limit) {
        if (limit < 1 || accounts.size() > Math.addExact(limit, 1)) {
            throw new IllegalStateException(
                    "Hibernate indexed page returned " + accounts.size() + " rows for limit " + limit);
        }
        long checksum = 1;
        int returned = Math.min(accounts.size(), limit);
        for (int index = 0; index < returned; index++) {
            checksum = mix(checksum, benchmarkChecksum(accounts.get(index)));
        }
        checksum = mix(checksum, returned);
        return mix(checksum, accounts.size() > limit ? 1 : 0);
    }

    static DatasetSummary summarize(List<BenchmarkAccount> accounts) {
        long checksum = COLLECTION_CHECKSUM_SEED;
        long identifierSum = 0;
        long identifierXor = 0;
        int presentCount = 0;
        for (int index = 0; index < accounts.size(); index++) {
            var account = accounts.get(index);
            var valueChecksum = account == null
                    ? MISSING_VALUE_CHECKSUM ^ index
                    : account.stableChecksum();
            checksum = Long.rotateLeft(checksum, 11) ^ valueChecksum;
            if (account != null) {
                var id = account.id();
                identifierSum += id;
                identifierXor ^= id;
                presentCount++;
            }
        }
        return new DatasetSummary(accounts.size(), presentCount, checksum, identifierSum, identifierXor);
    }

    static void requireSummary(String operation, DatasetSummary actual, DatasetSummary expected) {
        if (!actual.equals(expected)) {
            throw new IllegalStateException(operation + " checksum mismatch: expected " + expected + ", got " + actual);
        }
    }

    static void prepareReadConnection(Connection connection) throws SQLException {
        prepareConnection(connection, true);
    }

    static void prepareWriteConnection(Connection connection) throws SQLException {
        prepareConnection(connection, false);
    }

    private static void prepareConnection(Connection connection, boolean readOnly) throws SQLException {
        connection.setNetworkTimeout(NETWORK_TIMEOUT_EXECUTOR, NETWORK_TIMEOUT_MILLISECONDS);
        if (connection.getNetworkTimeout() != NETWORK_TIMEOUT_MILLISECONDS) {
            throw new IllegalStateException("Hibernate benchmark connection did not preserve the network deadline");
        }
        connection.setAutoCommit(false);
        connection.rollback();
        connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        connection.setReadOnly(readOnly);
    }

    static void installUtf8Transport(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                WITH configured AS MATERIALIZED (
                    SELECT pg_catalog.set_config('client_encoding', 'UTF8', true) AS client_encoding
                )
                SELECT configured.client_encoding,
                       pg_catalog.current_setting('client_encoding'),
                       pg_catalog.current_setting('server_encoding')
                  FROM configured
                """);
             var result = statement.executeQuery()) {
            if (!result.next()
                    || !"UTF8".equals(result.getString(1))
                    || !"UTF8".equals(result.getString(2))
                    || !"UTF8".equals(result.getString(3))
                    || result.next()) {
                throw new IllegalStateException("Hibernate benchmark requires UTF-8 transport");
            }
        }
    }

    static void installTrustedSearchPath(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                WITH configured AS MATERIALIZED (
                    SELECT pg_catalog.set_config('search_path', 'pg_catalog', true) AS search_path
                )
                SELECT configured.search_path,
                       pg_catalog.current_setting('search_path'),
                       pg_catalog.pg_my_temp_schema()
                  FROM configured
                """);
             var result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("Hibernate benchmark could not install a trusted search path");
            }
            var configuredSearchPath = result.getString(1);
            var currentSearchPath = result.getString(2);
            var temporarySchemaOid = result.getLong(3);
            var temporarySchemaOidWasNull = result.wasNull();
            requireTrustedSearchPathResult(
                    configuredSearchPath,
                    currentSearchPath,
                    temporarySchemaOid,
                    temporarySchemaOidWasNull,
                    result.next());
        }
    }

    static void requireTrustedSearchPathResult(
            String configuredSearchPath,
            String currentSearchPath,
            long temporarySchemaOid,
            boolean temporarySchemaOidWasNull,
            boolean additionalRow) {
        if (!"pg_catalog".equals(configuredSearchPath)
                || !"pg_catalog".equals(currentSearchPath)
                || temporarySchemaOid != 0
                || temporarySchemaOidWasNull
                || additionalRow) {
            throw new IllegalStateException("Hibernate benchmark could not install a trusted search path");
        }
    }

    static void requireTrustedSessionBaseline(Connection connection) throws SQLException {
        PGConnection postgres = connection.unwrap(PGConnection.class);
        requireTrustedSessionBaselineValues(
                postgres.getParameterStatus("search_path"),
                postgres.getParameterStatus("client_encoding"),
                postgres.getParameterStatus("server_encoding"),
                postgres.getParameterStatus("standard_conforming_strings"),
                postgres.getParameterStatus("integer_datetimes"));
    }

    static void requireTrustedSessionBaselineValues(
            String searchPath,
            String clientEncoding,
            String serverEncoding,
            String standardConformingStrings,
            String integerDatetimes) {
        if (!"pg_catalog".equals(searchPath)
                || !"UTF8".equals(clientEncoding)
                || !"UTF8".equals(serverEncoding)
                || !"on".equals(standardConformingStrings)
                || !"on".equals(integerDatetimes)) {
            throw new IllegalStateException("Hibernate benchmark requires Vev's immutable pgjdbc session baseline");
        }
    }

    static void verifyNoRetainedTempSchema(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT pg_catalog.pg_my_temp_schema()");
             var result = statement.executeQuery()) {
            if (!result.next()
                    || result.getLong(1) != 0
                    || result.wasNull()
                    || result.next()) {
                throw new IllegalStateException("Hibernate benchmark rejects sessions which retained a temporary schema");
            }
        }
    }

    static void applyTransactionContext(
            Connection connection,
            DatabaseIdentity databaseIdentity,
            boolean readOnly) throws SQLException {
        String tenantValue = Integer.toString(TENANT_ID);
        try (var statement = connection.prepareStatement("""
                WITH configured AS MATERIALIZED (
                    SELECT pg_catalog.set_config('vev.tenant_id', ?, true) AS tenant,
                           pg_catalog.set_config('statement_timeout', '30000ms', true) AS timeout,
                           pg_catalog.set_config('transaction_timeout', '120000ms', true) AS transaction_timeout,
                           pg_catalog.set_config('lock_timeout', '30000ms', true) AS lock_timeout,
                           pg_catalog.set_config('row_security', 'on', true) AS row_security,
                           pg_catalog.set_config('synchronous_commit', 'on', true) AS synchronous_commit,
                           pg_catalog.set_config('TimeZone', 'UTC', true) AS time_zone
                )
                SELECT configured.tenant,
                       configured.timeout,
                       configured.transaction_timeout,
                       configured.lock_timeout,
                       configured.row_security,
                       configured.synchronous_commit,
                       configured.time_zone,
                       pg_catalog.current_setting('vev.tenant_id'),
                       pg_catalog.current_setting('statement_timeout'),
                       pg_catalog.current_setting('transaction_timeout'),
                       pg_catalog.current_setting('lock_timeout'),
                       pg_catalog.current_setting('client_encoding'),
                       pg_catalog.current_setting('server_encoding'),
                       pg_catalog.current_setting('search_path'),
                       pg_catalog.current_setting('row_security'),
                       pg_catalog.current_setting('synchronous_commit'),
                       pg_catalog.current_setting('TimeZone'),
                       pg_catalog.current_setting('transaction_read_only'),
                       pg_catalog.current_setting('transaction_isolation'),
                       pg_catalog.current_database(),
                       database_identity.oid,
                       (pg_catalog.pg_control_system()).system_identifier,
                       pg_catalog.inet_server_addr()::pg_catalog.text,
                       pg_catalog.inet_server_port(),
                       (extract(epoch FROM pg_catalog.pg_postmaster_start_time()) * 1000000)::pg_catalog.int8,
                       pg_catalog.pg_is_in_recovery(),
                       session_user,
                       current_user,
                       pg_catalog.pg_my_temp_schema() = 0,
                       (SELECT pg_catalog.count(*) = 1
                          FROM public.vev_schema_fingerprint fingerprint
                         WHERE fingerprint.model_name = ?
                           AND fingerprint.fingerprint = ?)
                  FROM configured
                  JOIN pg_catalog.pg_database database_identity
                    ON database_identity.datname = pg_catalog.current_database()
                """)) {
            statement.setString(1, tenantValue);
            statement.setString(2, MODEL_NAME);
            statement.setString(3, MODEL_FINGERPRINT);
            try (var result = statement.executeQuery()) {
                if (!result.next()
                        || !tenantValue.equals(result.getString(1))
                        || !result.getString(2).equals(result.getString(9))
                        || "0".equals(result.getString(2))
                        || !result.getString(3).equals(result.getString(10))
                        || "0".equals(result.getString(3))
                        || !result.getString(4).equals(result.getString(11))
                        || "0".equals(result.getString(4))
                        || !"on".equals(result.getString(5))
                        || !"on".equals(result.getString(6))
                        || !"UTC".equals(result.getString(7))
                        || !tenantValue.equals(result.getString(8))
                        || !"UTF8".equals(result.getString(12))
                        || !"UTF8".equals(result.getString(13))
                        || !"pg_catalog".equals(result.getString(14))
                        || !"on".equals(result.getString(15))
                        || !"on".equals(result.getString(16))
                        || !"UTC".equals(result.getString(17))
                        || !(readOnly ? "on" : "off").equals(result.getString(18))
                        || !"serializable".equals(result.getString(19))
                        || !databaseIdentity.database().equals(result.getString(20))
                        || databaseIdentity.databaseOid() != result.getLong(21)
                        || databaseIdentity.systemIdentifier() != result.getLong(22)
                        || !Objects.equals(databaseIdentity.endpointAddress(), result.getString(23))
                        || databaseIdentity.endpointPort() != result.getInt(24)
                        || databaseIdentity.postmasterStartEpochMicros() != result.getLong(25)
                        || result.getBoolean(26)
                        || !databaseIdentity.role().equals(result.getString(27))
                        || !databaseIdentity.role().equals(result.getString(28))
                        || !result.getBoolean(29)
                        || !result.getBoolean(30)
                        || result.next()) {
                    throw new IllegalStateException("PostgreSQL did not apply the benchmark transaction context");
                }
            }
        }
    }

    static void verifyTransactionContext(
            Connection connection,
            DatabaseIdentity databaseIdentity,
            boolean readOnly) throws SQLException {
        if (connection.getNetworkTimeout() != NETWORK_TIMEOUT_MILLISECONDS) {
            throw new IllegalStateException("Hibernate benchmark connection escaped the network deadline");
        }
        try (var statement = connection.prepareStatement("""
                SELECT pg_catalog.current_setting('vev.tenant_id', true),
                       extract(epoch FROM pg_catalog.current_setting('statement_timeout')::pg_catalog.interval) * 1000,
                       extract(epoch FROM pg_catalog.current_setting('transaction_timeout')::pg_catalog.interval) * 1000,
                       extract(epoch FROM pg_catalog.current_setting('lock_timeout')::pg_catalog.interval) * 1000,
                       pg_catalog.current_setting('client_encoding'),
                       pg_catalog.current_setting('server_encoding'),
                       pg_catalog.current_setting('search_path'),
                       pg_catalog.current_setting('row_security'),
                       pg_catalog.current_setting('transaction_read_only'),
                       pg_catalog.current_setting('transaction_isolation'),
                       pg_catalog.current_setting('synchronous_commit'),
                       pg_catalog.current_setting('TimeZone'),
                       pg_catalog.current_database(),
                       database_identity.oid,
                       (pg_catalog.pg_control_system()).system_identifier,
                       pg_catalog.inet_server_addr()::pg_catalog.text,
                       pg_catalog.inet_server_port(),
                       (extract(epoch FROM pg_catalog.pg_postmaster_start_time()) * 1000000)::pg_catalog.int8,
                       pg_catalog.pg_is_in_recovery(),
                       session_user,
                       current_user,
                       pg_catalog.pg_my_temp_schema() = 0,
                       (SELECT pg_catalog.count(*) = 1
                          FROM public.vev_schema_fingerprint fingerprint
                         WHERE fingerprint.model_name = ?
                           AND fingerprint.fingerprint = ?)
                  FROM pg_catalog.pg_database database_identity
                 WHERE database_identity.datname = pg_catalog.current_database()
                """);
             var result = bindFingerprint(statement).executeQuery()) {
            if (!result.next()
                    || !Integer.toString(TENANT_ID).equals(result.getString(1))
                    || result.getLong(2) != STATEMENT_TIMEOUT_MILLISECONDS
                    || result.getLong(3) != TRANSACTION_TIMEOUT_MILLISECONDS
                    || result.getLong(4) != STATEMENT_TIMEOUT_MILLISECONDS
                    || !"UTF8".equals(result.getString(5))
                    || !"UTF8".equals(result.getString(6))
                    || !"pg_catalog".equals(result.getString(7))
                    || !"on".equals(result.getString(8))
                    || !(readOnly ? "on" : "off").equals(result.getString(9))
                    || !"serializable".equals(result.getString(10))
                    || !"on".equals(result.getString(11))
                    || !"UTC".equals(result.getString(12))
                    || !databaseIdentity.database().equals(result.getString(13))
                    || databaseIdentity.databaseOid() != result.getLong(14)
                    || databaseIdentity.systemIdentifier() != result.getLong(15)
                    || !Objects.equals(databaseIdentity.endpointAddress(), result.getString(16))
                    || databaseIdentity.endpointPort() != result.getInt(17)
                    || databaseIdentity.postmasterStartEpochMicros() != result.getLong(18)
                    || result.getBoolean(19)
                    || !databaseIdentity.role().equals(result.getString(20))
                    || !databaseIdentity.role().equals(result.getString(21))
                    || !result.getBoolean(22)
                    || !result.getBoolean(23)
                    || result.next()) {
                throw new IllegalStateException("Hibernate benchmark transaction context changed");
            }
        }
    }

    private static PreparedStatement bindFingerprint(PreparedStatement statement) throws SQLException {
        statement.setString(1, MODEL_NAME);
        statement.setString(2, MODEL_FINGERPRINT);
        return statement;
    }

    private static DatabaseIdentity readDatabaseIdentity(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT pg_catalog.current_database(),
                       database_identity.oid,
                       (pg_catalog.pg_control_system()).system_identifier,
                       pg_catalog.inet_server_addr()::pg_catalog.text,
                       pg_catalog.inet_server_port(),
                       (extract(epoch FROM pg_catalog.pg_postmaster_start_time()) * 1000000)::pg_catalog.int8,
                       pg_catalog.pg_is_in_recovery(),
                       session_user,
                       current_user,
                       pg_catalog.pg_my_temp_schema() = 0
                  FROM pg_catalog.pg_database database_identity
                 WHERE database_identity.datname = pg_catalog.current_database()
                """);
             var result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("PostgreSQL did not return the benchmark database identity");
            }
            var databaseIdentity = new DatabaseIdentity(
                    result.getString(1),
                    result.getLong(2),
                    result.getLong(3),
                    result.getString(4),
                    result.getInt(5),
                    result.getLong(6),
                    result.getString(8));
            if (result.getBoolean(7)
                    || !BenchmarkDatabaseConfiguration.DATABASE_NAME.equals(databaseIdentity.database())
                    || !BenchmarkDatabaseConfiguration.APPLICATION_USER.equals(databaseIdentity.role())
                    || databaseIdentity.endpointAddress() == null
                    || databaseIdentity.endpointAddress().isBlank()
                    || databaseIdentity.endpointPort() < 1
                    || databaseIdentity.endpointPort() > 65_535
                    || databaseIdentity.postmasterStartEpochMicros() <= 0
                    || !databaseIdentity.role().equals(result.getString(9))
                    || !result.getBoolean(10)
                    || result.next()) {
                throw new IllegalStateException("PostgreSQL benchmark database identity is unsafe");
            }
            return databaseIdentity;
        }
    }

    private static void prepareDatabase(BenchmarkAdminConfiguration configuration) throws SQLException {
        try (var connection = configuration.openConnection()) {
            installDestructiveSetupSearchPath(connection);
            assertPostgreSql18(connection);
            var ownerRoleExists = requireOwnedRoleIfExists(
                    connection,
                    BenchmarkDatabaseConfiguration.OWNER_ROLE);
            var applicationRoleExists = requireOwnedRoleIfExists(
                    connection,
                    BenchmarkDatabaseConfiguration.APPLICATION_USER);
            var databaseExists = requireOwnedDatabaseIfExists(connection);
            try (var statement = connection.createStatement()) {
                if (!ownerRoleExists) {
                    statement.execute("CREATE ROLE " + BenchmarkDatabaseConfiguration.OWNER_ROLE
                            + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
                    statement.execute("COMMENT ON ROLE " + BenchmarkDatabaseConfiguration.OWNER_ROLE
                            + " IS '" + FIXTURE_OWNERSHIP_MARKER + "'");
                }
                if (!applicationRoleExists) {
                    statement.execute("""
                            CREATE ROLE vev_bench_app
                            LOGIN PASSWORD 'vev_bench_password'
                            NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS
                            """);
                    statement.execute("COMMENT ON ROLE " + BenchmarkDatabaseConfiguration.APPLICATION_USER
                            + " IS '" + FIXTURE_OWNERSHIP_MARKER + "'");
                }
                if (!databaseExists) {
                    statement.execute("CREATE DATABASE vev_bench OWNER " + BenchmarkDatabaseConfiguration.OWNER_ROLE);
                    statement.execute("COMMENT ON DATABASE " + BenchmarkDatabaseConfiguration.DATABASE_NAME
                            + " IS '" + FIXTURE_OWNERSHIP_MARKER + "'");
                }
            }
            requireFixtureOwnershipMarkers(connection);
            try (var statement = connection.createStatement()) {
                statement.execute("ALTER ROLE " + BenchmarkDatabaseConfiguration.OWNER_ROLE
                        + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
                statement.execute("""
                        ALTER ROLE vev_bench_app
                        LOGIN PASSWORD 'vev_bench_password'
                        NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS
                        """);
                statement.execute("REVOKE " + BenchmarkDatabaseConfiguration.OWNER_ROLE
                        + " FROM " + BenchmarkDatabaseConfiguration.APPLICATION_USER);
                statement.execute("ALTER DATABASE vev_bench OWNER TO " + BenchmarkDatabaseConfiguration.OWNER_ROLE);
            }
        }
    }

    private static boolean requireOwnedRoleIfExists(Connection connection, String role) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT pg_catalog.shobj_description(fixture_role.oid, 'pg_authid')
                  FROM pg_catalog.pg_roles fixture_role
                 WHERE fixture_role.rolname = ?
                """)) {
            statement.setString(1, role);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return false;
                }
                requireExactOwnershipMarker("role " + role, result.getString(1));
                if (result.next()) {
                    throw new IllegalStateException("PostgreSQL returned duplicate benchmark roles");
                }
                return true;
            }
        }
    }

    private static boolean requireOwnedDatabaseIfExists(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT pg_catalog.shobj_description(fixture_database.oid, 'pg_database')
                  FROM pg_catalog.pg_database fixture_database
                 WHERE fixture_database.datname = ?
                """)) {
            statement.setString(1, BenchmarkDatabaseConfiguration.DATABASE_NAME);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return false;
                }
                requireExactOwnershipMarker(
                        "database " + BenchmarkDatabaseConfiguration.DATABASE_NAME,
                        result.getString(1));
                if (result.next()) {
                    throw new IllegalStateException("PostgreSQL returned duplicate benchmark databases");
                }
                return true;
            }
        }
    }

    static void requireExactOwnershipMarker(String fixtureObject, String marker) {
        if (!FIXTURE_OWNERSHIP_MARKER.equals(marker)) {
            throw new IllegalStateException(
                    fixtureObject + " is not marked as the owned disposable Vev benchmark fixture");
        }
    }

    private static void requireFixtureOwnershipMarkers(Connection connection) throws SQLException {
        if (!requireOwnedRoleIfExists(connection, BenchmarkDatabaseConfiguration.OWNER_ROLE)
                || !requireOwnedRoleIfExists(connection, BenchmarkDatabaseConfiguration.APPLICATION_USER)
                || !requireOwnedDatabaseIfExists(connection)) {
            throw new IllegalStateException("The owned disposable Vev benchmark fixture is incomplete");
        }
    }

    private static void requireCurrentFixtureOwnership(Connection connection) throws SQLException {
        installDestructiveSetupSearchPath(connection);
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT pg_catalog.current_database()")) {
            if (!result.next()
                    || !BenchmarkDatabaseConfiguration.DATABASE_NAME.equals(result.getString(1))
                    || result.next()) {
                throw new IllegalStateException("Destructive benchmark setup connected to the wrong database");
            }
        }
        requireFixtureOwnershipMarkers(connection);
    }

    private static void installDestructiveSetupSearchPath(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                WITH configured AS MATERIALIZED (
                    SELECT pg_catalog.set_config('search_path', 'pg_catalog', false) AS search_path
                )
                SELECT configured.search_path,
                       pg_catalog.current_setting('search_path')
                  FROM configured
                """);
             var result = statement.executeQuery()) {
            if (!result.next()
                    || !"pg_catalog".equals(result.getString(1))
                    || !"pg_catalog".equals(result.getString(2))
                    || result.next()) {
                throw new IllegalStateException("Benchmark setup could not install a trusted search path");
            }
        }
    }

    private static void createSchema(Connection connection) throws SQLException {
        requireCurrentFixtureOwnership(connection);
        try (var statement = connection.createStatement()) {
            statement.execute("REVOKE CREATE, TEMPORARY ON DATABASE vev_bench FROM PUBLIC");
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA_NAME + " CASCADE");
            statement.execute("CREATE SCHEMA " + SCHEMA_NAME
                    + " AUTHORIZATION " + BenchmarkDatabaseConfiguration.OWNER_ROLE);
            statement.execute("""
                    CREATE TABLE vev_bench.account (
                        id bigint NOT NULL,
                        tenant_id integer NOT NULL,
                        version bigint NOT NULL,
                        email varchar(255) NOT NULL,
                        balance numeric(19,4) NOT NULL,
                        active boolean NOT NULL,
                        PRIMARY KEY (tenant_id, id)
                    )
                    """);
            statement.execute("ALTER TABLE vev_bench.account OWNER TO " + BenchmarkDatabaseConfiguration.OWNER_ROLE);
            statement.execute("CREATE INDEX account_email_vev_idx ON vev_bench.account "
                    + "USING btree (tenant_id, email, id)");
            statement.execute("CREATE INDEX account_active_vev_idx ON vev_bench.account "
                    + "USING btree (tenant_id, active, id)");
            statement.execute("""
                    CREATE TABLE vev_bench.update_account (
                        id bigint NOT NULL,
                        tenant_id integer NOT NULL,
                        version bigint NOT NULL,
                        balance numeric(19,4) NOT NULL,
                        PRIMARY KEY (tenant_id, id)
                    ) WITH (fillfactor = 50)
                    """);
            statement.execute("ALTER TABLE vev_bench.update_account OWNER TO "
                    + BenchmarkDatabaseConfiguration.OWNER_ROLE);
            statement.execute("""
                    CREATE POLICY account_tenant ON vev_bench.account
                    FOR ALL TO vev_bench_app
                    USING (tenant_id = pg_catalog.current_setting('vev.tenant_id', true)::pg_catalog.int4)
                    WITH CHECK (tenant_id = pg_catalog.current_setting('vev.tenant_id', true)::pg_catalog.int4)
                    """);
            statement.execute("""
                    CREATE POLICY update_account_tenant ON vev_bench.update_account
                    FOR ALL TO vev_bench_app
                    USING (tenant_id = pg_catalog.current_setting('vev.tenant_id', true)::pg_catalog.int4)
                    WITH CHECK (tenant_id = pg_catalog.current_setting('vev.tenant_id', true)::pg_catalog.int4)
                    """);
            statement.execute("ALTER TABLE vev_bench.account ENABLE ROW LEVEL SECURITY");
            statement.execute("ALTER TABLE vev_bench.account FORCE ROW LEVEL SECURITY");
            statement.execute("ALTER TABLE vev_bench.update_account ENABLE ROW LEVEL SECURITY");
            statement.execute("ALTER TABLE vev_bench.update_account FORCE ROW LEVEL SECURITY");
            statement.execute("GRANT USAGE ON SCHEMA vev_bench TO vev_bench_app");
            statement.execute("GRANT SELECT ON vev_bench.account TO vev_bench_app");
            statement.execute("GRANT INSERT (id, tenant_id, version, email, balance, active) "
                    + "ON vev_bench.account TO vev_bench_app");
            statement.execute("GRANT UPDATE (version, email, balance, active) "
                    + "ON vev_bench.account TO vev_bench_app");
            statement.execute("GRANT SELECT ON vev_bench.update_account TO vev_bench_app");
            statement.execute("GRANT INSERT (id, tenant_id, version, balance) "
                    + "ON vev_bench.update_account TO vev_bench_app");
            statement.execute("GRANT UPDATE (version, balance) "
                    + "ON vev_bench.update_account TO vev_bench_app");
            statement.execute("DROP TABLE IF EXISTS public.vev_schema_fingerprint");
            statement.execute("""
                    CREATE TABLE public.vev_schema_fingerprint (
                        model_name varchar(128) PRIMARY KEY,
                        fingerprint varchar(71) NOT NULL
                    )
                    """);
            statement.execute("ALTER TABLE public.vev_schema_fingerprint OWNER TO "
                    + BenchmarkDatabaseConfiguration.OWNER_ROLE);
            statement.execute("INSERT INTO public.vev_schema_fingerprint(model_name, fingerprint) VALUES ('"
                    + MODEL_NAME + "', '" + MODEL_FINGERPRINT + "')");
            statement.execute("REVOKE ALL ON public.vev_schema_fingerprint FROM PUBLIC, vev_bench_app");
            statement.execute("GRANT SELECT ON public.vev_schema_fingerprint TO vev_bench_app");
            statement.execute("GRANT USAGE ON SCHEMA public TO vev_bench_app");
        }
    }

    private static void replaceRows(Connection connection) throws SQLException {
        try (var truncate = connection.createStatement()) {
            truncate.execute("TRUNCATE TABLE " + SCHEMA_NAME + "." + TABLE_NAME
                    + ", " + SCHEMA_NAME + "." + UPDATE_TABLE_NAME);
        }
        try (var insert = connection.prepareStatement("""
                INSERT INTO vev_bench.account
                    (id, tenant_id, version, email, balance, active)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            for (long id = 1; id <= ROW_COUNT; id++) {
                bindInsert(insert, expectedAccount(id));
                insert.addBatch();
                if (id % INSERT_BATCH_SIZE == 0) {
                    insert.executeBatch();
                }
            }
            if (ROW_COUNT % INSERT_BATCH_SIZE != 0) {
                insert.executeBatch();
            }
        }
        try (var insert = connection.prepareStatement("""
                INSERT INTO vev_bench.update_account (id, tenant_id, version, balance)
                VALUES (?, ?, ?, ?)
                """)) {
            for (BenchmarkUpdateAccount account : BatchUpdateWorkload.state(0L)) {
                insert.setLong(1, account.id());
                insert.setInt(2, account.tenantId());
                insert.setLong(3, account.version());
                insert.setBigDecimal(4, account.balance());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void bindInsert(PreparedStatement insert, BenchmarkAccount account) throws SQLException {
        insert.setLong(1, account.id());
        insert.setInt(2, account.tenantId());
        insert.setLong(3, account.version());
        insert.setString(4, account.email());
        insert.setBigDecimal(5, account.balance());
        insert.setBoolean(6, account.active());
    }

    private static void analyzeTable(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("ANALYZE " + SCHEMA_NAME + "." + TABLE_NAME);
            statement.execute("ANALYZE " + SCHEMA_NAME + "." + UPDATE_TABLE_NAME);
        }
    }

    private static DatasetSummary verifyJdbcRows(Connection connection) throws SQLException {
        var accounts = new ArrayList<BenchmarkAccount>(ROW_COUNT);
        try (var statement = connection.prepareStatement("""
                SELECT id, tenant_id, version, email, balance, active
                FROM vev_bench.account
                WHERE tenant_id = ?
                ORDER BY id
                """)) {
            statement.setInt(1, TENANT_ID);
            statement.setFetchSize(FETCH_SIZE);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    accounts.add(readAccount(rows));
                }
            }
        }
        var actual = summarize(accounts);
        var expected = summarize(expectedAccounts(1, ROW_COUNT));
        requireSummary("JDBC dataset verification", actual, expected);
        return actual;
    }

    private static BenchmarkAccount readAccount(ResultSet row) throws SQLException {
        return new BenchmarkAccount(
                row.getLong("id"),
                row.getInt("tenant_id"),
                row.getLong("version"),
                row.getString("email"),
                row.getBigDecimal("balance"),
                row.getBoolean("active"));
    }

    private static void assertPostgreSql18(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("SHOW server_version_num")) {
            if (!result.next()) {
                throw new IllegalStateException("PostgreSQL did not return server_version_num");
            }
            var versionNumber = result.getInt(1);
            if (versionNumber < POSTGRESQL_18_VERSION_NUMBER
                    || versionNumber >= POSTGRESQL_19_VERSION_NUMBER
                    || result.next()) {
                throw new IllegalStateException("PostgreSQL 18.x is required, got server_version_num=" + versionNumber);
            }
        }
    }

    private static void rollbackAfterFailure(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static long mix(long checksum, long value) {
        return checksum * 31 + value;
    }

    record DatasetSummary(
            int resultCount,
            int presentCount,
            long checksum,
            long identifierSum,
            long identifierXor) {
        long combinedChecksum() {
            return checksum
                    ^ Long.rotateLeft(identifierSum, 17)
                    ^ Long.rotateLeft(identifierXor, 41)
                    ^ ((long) resultCount << 32)
                    ^ presentCount;
        }
    }

    record DatabaseIdentity(
            String database,
            long databaseOid,
            long systemIdentifier,
            String endpointAddress,
            int endpointPort,
            long postmasterStartEpochMicros,
            String role) {
    }
}
