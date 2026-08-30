package no.beint.vev.benchmark;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Executor;

final class BenchmarkDatabase {
    private static final String DATABASE = "vev_bench";
    private static final String APPLICATION_USER = "vev_bench_app";
    private static final String APPLICATION_PASSWORD = "vev_bench_password";
    private static final String OWNER_ROLE = "vev_bench_owner";
    static final String FIXTURE_OWNERSHIP_MARKER = "vev-owned-fixture:vev_bench:v1";
    private static final String ALLOW_REMOTE_DESTRUCTIVE_SETUP_ENVIRONMENT_VARIABLE =
            "VEV_BENCH_ALLOW_REMOTE_DESTRUCTIVE_SETUP";
    private static final String ALLOW_REMOTE_DESTRUCTIVE_SETUP_VALUE = "vev_bench";
    private static final String POSTGRESQL_JDBC_PREFIX = "jdbc:postgresql://";
    private static final int POSTGRESQL_18 = 180_000;
    private static final int POSTGRESQL_19 = 190_000;
    private static final int STATEMENT_TIMEOUT_MILLISECONDS = 30_000;
    private static final int TRANSACTION_TIMEOUT_MILLISECONDS = 120_000;
    private static final int NETWORK_TIMEOUT_MILLISECONDS = 180_000;
    private static final Executor NETWORK_TIMEOUT_EXECUTOR = command ->
            Thread.ofVirtual().name("vev-raw-benchmark-network-timeout").start(command);

    private BenchmarkDatabase() {
    }

    static HikariDataSource initialize() throws SQLException {
        String adminJdbcUrl = environment(
                "VEV_BENCH_ADMIN_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/postgres");
        requireDestructiveSetupUrlAllowed(
                adminJdbcUrl,
                System.getenv(ALLOW_REMOTE_DESTRUCTIVE_SETUP_ENVIRONMENT_VARIABLE));
        String adminUser = environment("VEV_BENCH_ADMIN_USER", "postgres");
        String adminPassword = environment("VEV_BENCH_ADMIN_PASSWORD", "");
        createDatabaseAndRole(adminJdbcUrl, adminUser, adminPassword);
        String defaultRuntimeJdbcUrl = databaseUrl(adminJdbcUrl, DATABASE);
        String runtimeJdbcUrl = environment("VEV_BENCH_JDBC_URL", defaultRuntimeJdbcUrl);
        String runtimeUser = environment("VEV_BENCH_USER", APPLICATION_USER);
        String runtimePassword = environment("VEV_BENCH_PASSWORD", APPLICATION_PASSWORD);
        try (Connection connection = DriverManager.getConnection(
                databaseUrl(adminJdbcUrl, DATABASE), adminUser, adminPassword)) {
            verifyPostgres18(connection);
            rebuildSyntheticSchema(connection, runtimeUser);
        }
        HikariConfig configuration = new HikariConfig();
        configuration.setPoolName("vev-benchmark");
        configuration.setJdbcUrl(runtimeJdbcUrl);
        configuration.setUsername(runtimeUser);
        configuration.setPassword(runtimePassword);
        configuration.setMinimumIdle(8);
        configuration.setMaximumPoolSize(8);
        configuration.setAutoCommit(false);
        configuration.setTransactionIsolation("TRANSACTION_SERIALIZABLE");
        configuration.setConnectionInitSql("SET search_path = public");
        configuration.setConnectionTimeout(10_000);
        configuration.setInitializationFailTimeout(10_000);
        return new HikariDataSource(configuration);
    }

    static void verifySeed(Connection connection, int tenantId) throws SQLException {
        configureTenant(connection, tenantId);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*), min(id), max(id), sum(id) FROM \"vev_bench\".\"account\" WHERE tenant_id = ?")) {
            statement.setInt(1, tenantId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()
                        || resultSet.getLong(1) != 10_000
                        || resultSet.getLong(2) != 1
                        || resultSet.getLong(3) != 10_000
                        || resultSet.getLong(4) != 50_005_000L
                        || resultSet.next()) {
                    throw new IllegalStateException("Synthetic benchmark seed checksum is invalid");
                }
            }
        }
        connection.rollback();
    }

    static void configureTenant(Connection connection, int tenantId) throws SQLException {
        connection.setNetworkTimeout(NETWORK_TIMEOUT_EXECUTOR, NETWORK_TIMEOUT_MILLISECONDS);
        if (connection.getNetworkTimeout() != NETWORK_TIMEOUT_MILLISECONDS) {
            throw new IllegalStateException("Raw benchmark connection did not preserve the network deadline");
        }
        connection.setAutoCommit(false);
        connection.rollback();
        connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        connection.setReadOnly(true);
        installTrustedSearchPath(connection);
        installUtf8Transport(connection);
        String tenantValue = Integer.toString(tenantId);
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH configured AS MATERIALIZED (
                    SELECT pg_catalog.set_config('vev.tenant_id', ?, true) AS tenant,
                           pg_catalog.set_config('statement_timeout', '30000ms', true) AS timeout,
                           pg_catalog.set_config('transaction_timeout', '120000ms', true) AS transaction_timeout,
                           pg_catalog.set_config('lock_timeout', '30000ms', true) AS lock_timeout,
                           pg_catalog.set_config('search_path', 'pg_catalog', true) AS search_path,
                           pg_catalog.set_config('row_security', 'on', true) AS row_security,
                           pg_catalog.set_config('synchronous_commit', 'on', true) AS synchronous_commit,
                           pg_catalog.set_config('TimeZone', 'UTC', true) AS time_zone
                )
                SELECT configured.tenant,
                       configured.timeout,
                       configured.transaction_timeout,
                       configured.lock_timeout,
                       configured.search_path,
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
                       pg_catalog.current_database(), session_user, current_user,
                       pg_catalog.pg_is_in_recovery(),
                       pg_catalog.pg_my_temp_schema() = 0,
                       (SELECT pg_catalog.count(*) = 1
                          FROM public.vev_schema_fingerprint fingerprint
                         WHERE fingerprint.model_name = ?
                           AND fingerprint.fingerprint = ?)
                  FROM configured
                """)) {
            statement.setString(1, tenantValue);
            statement.setString(2, BenchmarkModelVev.IDENTITY.name());
            statement.setString(3, BenchmarkModelVev.IDENTITY.fingerprint());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()
                        || !tenantValue.equals(resultSet.getString(1))
                        || !resultSet.getString(2).equals(resultSet.getString(10))
                        || "0".equals(resultSet.getString(2))
                        || !resultSet.getString(3).equals(resultSet.getString(11))
                        || "0".equals(resultSet.getString(3))
                        || !resultSet.getString(4).equals(resultSet.getString(12))
                        || "0".equals(resultSet.getString(4))
                        || !"pg_catalog".equals(resultSet.getString(5))
                        || !"on".equals(resultSet.getString(6))
                        || !"on".equals(resultSet.getString(7))
                        || !"UTC".equals(resultSet.getString(8))
                        || !tenantValue.equals(resultSet.getString(9))
                        || !"UTF8".equals(resultSet.getString(13))
                        || !"UTF8".equals(resultSet.getString(14))
                        || !"pg_catalog".equals(resultSet.getString(15))
                        || !"on".equals(resultSet.getString(16))
                        || !"on".equals(resultSet.getString(17))
                        || !"UTC".equals(resultSet.getString(18))
                        || !"on".equals(resultSet.getString(19))
                        || !"serializable".equals(resultSet.getString(20))
                        || !DATABASE.equals(resultSet.getString(21))
                        || !APPLICATION_USER.equals(resultSet.getString(22))
                        || !APPLICATION_USER.equals(resultSet.getString(23))
                        || resultSet.getBoolean(24)
                        || !resultSet.getBoolean(25)
                        || !resultSet.getBoolean(26)
                        || resultSet.next()) {
                    throw new IllegalStateException("Raw benchmark transaction context changed");
                }
            }
        }
    }

    static void verifyTenantContext(Connection connection, int tenantId) throws SQLException {
        if (connection.getNetworkTimeout() != NETWORK_TIMEOUT_MILLISECONDS) {
            throw new IllegalStateException("Raw benchmark connection escaped the network deadline");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
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
                       pg_catalog.current_database(), session_user, current_user,
                       pg_catalog.pg_is_in_recovery(),
                       pg_catalog.pg_my_temp_schema() = 0,
                       (SELECT pg_catalog.count(*) = 1
                          FROM public.vev_schema_fingerprint fingerprint
                         WHERE fingerprint.model_name = ?
                           AND fingerprint.fingerprint = ?)
                """);
             ResultSet resultSet = bindModelFingerprint(statement).executeQuery()) {
            if (!resultSet.next()
                    || !Integer.toString(tenantId).equals(resultSet.getString(1))
                    || resultSet.getLong(2) != STATEMENT_TIMEOUT_MILLISECONDS
                    || resultSet.getLong(3) != TRANSACTION_TIMEOUT_MILLISECONDS
                    || resultSet.getLong(4) != STATEMENT_TIMEOUT_MILLISECONDS
                    || !"UTF8".equals(resultSet.getString(5))
                    || !"UTF8".equals(resultSet.getString(6))
                    || !"pg_catalog".equals(resultSet.getString(7))
                    || !"on".equals(resultSet.getString(8))
                    || !"on".equals(resultSet.getString(9))
                    || !"serializable".equals(resultSet.getString(10))
                    || !"on".equals(resultSet.getString(11))
                    || !"UTC".equals(resultSet.getString(12))
                    || !DATABASE.equals(resultSet.getString(13))
                    || !APPLICATION_USER.equals(resultSet.getString(14))
                    || !APPLICATION_USER.equals(resultSet.getString(15))
                    || resultSet.getBoolean(16)
                    || !resultSet.getBoolean(17)
                    || !resultSet.getBoolean(18)
                    || resultSet.next()) {
                throw new IllegalStateException("Raw benchmark transaction context changed after the workload");
            }
        }
    }

    private static PreparedStatement bindModelFingerprint(PreparedStatement statement) throws SQLException {
        statement.setString(1, BenchmarkModelVev.IDENTITY.name());
        statement.setString(2, BenchmarkModelVev.IDENTITY.fingerprint());
        return statement;
    }

    private static void installUtf8Transport(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH configured AS MATERIALIZED (
                    SELECT pg_catalog.set_config('client_encoding', 'UTF8', true) AS client_encoding
                )
                SELECT configured.client_encoding,
                       pg_catalog.current_setting('client_encoding'),
                       pg_catalog.current_setting('server_encoding')
                  FROM configured
                """);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()
                    || !"UTF8".equals(resultSet.getString(1))
                    || !"UTF8".equals(resultSet.getString(2))
                    || !"UTF8".equals(resultSet.getString(3))
                    || resultSet.next()) {
                throw new IllegalStateException("Raw benchmark requires UTF-8 transport");
            }
        }
    }

    private static void installTrustedSearchPath(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH configured AS MATERIALIZED (
                    SELECT pg_catalog.set_config('search_path', 'pg_catalog', true) AS search_path
                )
                SELECT configured.search_path,
                       pg_catalog.current_setting('search_path'),
                       pg_catalog.pg_my_temp_schema()
                  FROM configured
                """);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new IllegalStateException("Raw benchmark could not install a trusted search path");
            }
            String configuredSearchPath = resultSet.getString(1);
            String currentSearchPath = resultSet.getString(2);
            long temporarySchemaOid = resultSet.getLong(3);
            boolean temporarySchemaOidWasNull = resultSet.wasNull();
            requireTrustedSearchPathResult(
                    configuredSearchPath,
                    currentSearchPath,
                    temporarySchemaOid,
                    temporarySchemaOidWasNull,
                    resultSet.next());
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
            throw new IllegalStateException("Raw benchmark could not install a trusted search path");
        }
    }

    private static void createDatabaseAndRole(String adminJdbcUrl, String adminUser, String adminPassword)
            throws SQLException {
        try (Connection connection = DriverManager.getConnection(adminJdbcUrl, adminUser, adminPassword)) {
            installDestructiveSetupSearchPath(connection);
            verifyPostgres18(connection);
            boolean ownerRoleExists = requireOwnedRoleIfExists(connection, OWNER_ROLE);
            boolean applicationRoleExists = requireOwnedRoleIfExists(connection, APPLICATION_USER);
            boolean databaseExists = requireOwnedDatabaseIfExists(connection);
            try (Statement statement = connection.createStatement()) {
                if (!ownerRoleExists) {
                    statement.execute("CREATE ROLE " + OWNER_ROLE
                            + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
                    statement.execute("COMMENT ON ROLE " + OWNER_ROLE
                            + " IS '" + FIXTURE_OWNERSHIP_MARKER + "'");
                }
                if (!applicationRoleExists) {
                    statement.execute("CREATE ROLE " + APPLICATION_USER
                            + " LOGIN PASSWORD '" + APPLICATION_PASSWORD + "'"
                            + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
                    statement.execute("COMMENT ON ROLE " + APPLICATION_USER
                            + " IS '" + FIXTURE_OWNERSHIP_MARKER + "'");
                }
                if (!databaseExists) {
                    statement.execute("CREATE DATABASE " + DATABASE + " OWNER " + OWNER_ROLE);
                    statement.execute("COMMENT ON DATABASE " + DATABASE
                            + " IS '" + FIXTURE_OWNERSHIP_MARKER + "'");
                }
            }
            requireFixtureOwnershipMarkers(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER ROLE " + OWNER_ROLE
                        + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
                statement.execute("ALTER ROLE " + APPLICATION_USER + " PASSWORD '" + APPLICATION_PASSWORD + "' "
                        + "NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
                statement.execute("REVOKE " + OWNER_ROLE + " FROM " + APPLICATION_USER);
                statement.execute("ALTER DATABASE " + DATABASE + " OWNER TO " + OWNER_ROLE);
            }
        }
    }

    private static void rebuildSyntheticSchema(Connection connection, String runtimeUser) throws SQLException {
        requireCurrentFixtureOwnership(connection);
        String quotedRuntimeUser = quoteIdentifier(runtimeUser);
        try (Statement statement = connection.createStatement()) {
            statement.execute("REVOKE CREATE, TEMPORARY ON DATABASE vev_bench FROM PUBLIC");
            statement.execute("DROP SCHEMA IF EXISTS vev_bench CASCADE");
            statement.execute("CREATE SCHEMA vev_bench AUTHORIZATION " + OWNER_ROLE);
            statement.execute("""
                    CREATE TABLE vev_bench.account (
                        id bigint NOT NULL,
                        tenant_id integer NOT NULL,
                        version bigint NOT NULL,
                        email varchar(255) NOT NULL,
                        balance numeric(19, 4) NOT NULL,
                        active boolean NOT NULL,
                        PRIMARY KEY (tenant_id, id)
                    )
                    """);
            statement.execute("ALTER TABLE vev_bench.account OWNER TO " + OWNER_ROLE);
            statement.execute("ALTER TABLE vev_bench.account ENABLE ROW LEVEL SECURITY");
            statement.execute("ALTER TABLE vev_bench.account FORCE ROW LEVEL SECURITY");
            statement.execute("CREATE POLICY account_tenant ON vev_bench.account FOR ALL TO "
                    + quotedRuntimeUser
                    + " USING (tenant_id = pg_catalog.current_setting('vev.tenant_id', true)::pg_catalog.int4)"
                    + " WITH CHECK (tenant_id = pg_catalog.current_setting('vev.tenant_id', true)::pg_catalog.int4)");
            statement.execute("GRANT USAGE ON SCHEMA vev_bench TO " + quotedRuntimeUser);
            statement.execute("GRANT SELECT, DELETE ON vev_bench.account TO " + quotedRuntimeUser);
            statement.execute("GRANT INSERT (id, tenant_id, version, email, balance, active) "
                    + "ON vev_bench.account TO " + quotedRuntimeUser);
            statement.execute("GRANT UPDATE (version, email, balance, active) "
                    + "ON vev_bench.account TO " + quotedRuntimeUser);
            statement.execute("""
                    INSERT INTO vev_bench.account (id, tenant_id, version, email, balance, active)
                    SELECT generated_id,
                           7,
                           0,
                           'account-' || generated_id || '@example.test',
                           ((generated_id % 100000)::pg_catalog.numeric / 100)::pg_catalog.numeric(19, 4),
                           generated_id % 2 = 0
                      FROM generate_series(1, 10000) AS generated_id
                    """);
            statement.execute("ANALYZE vev_bench.account");
            statement.execute("DROP TABLE IF EXISTS public.vev_schema_fingerprint");
            statement.execute("""
                    CREATE TABLE public.vev_schema_fingerprint (
                        model_name varchar(128) PRIMARY KEY,
                        fingerprint varchar(71) NOT NULL
                    )
                    """);
            statement.execute("ALTER TABLE public.vev_schema_fingerprint OWNER TO " + OWNER_ROLE);
            statement.execute("INSERT INTO public.vev_schema_fingerprint(model_name, fingerprint) VALUES ('"
                    + BenchmarkModelVev.IDENTITY.name() + "', '" + BenchmarkModelVev.IDENTITY.fingerprint() + "') "
                    + "ON CONFLICT (model_name) DO UPDATE SET fingerprint = EXCLUDED.fingerprint");
            statement.execute("REVOKE ALL ON public.vev_schema_fingerprint FROM PUBLIC, " + quotedRuntimeUser);
            statement.execute("GRANT SELECT ON public.vev_schema_fingerprint TO " + quotedRuntimeUser);
            statement.execute("GRANT USAGE ON SCHEMA public TO " + quotedRuntimeUser);
        }
    }

    private static void verifyPostgres18(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SHOW server_version_num")) {
            if (!resultSet.next()
                    || resultSet.getInt(1) < POSTGRESQL_18
                    || resultSet.getInt(1) >= POSTGRESQL_19
                    || resultSet.next()) {
                throw new IllegalStateException("Vev benchmarks require PostgreSQL 18.x");
            }
        }
    }

    static void requireDestructiveSetupUrlAllowed(String jdbcUrl, String allowRemoteDestructiveSetup) {
        if (isLiteralLoopbackJdbcUrl(jdbcUrl)
                || (ALLOW_REMOTE_DESTRUCTIVE_SETUP_VALUE.equals(allowRemoteDestructiveSetup)
                && isSingleHostPostgresqlJdbcUrl(jdbcUrl))) {
            return;
        }
        throw new IllegalStateException(
                "Destructive benchmark setup requires a literal 127.0.0.1 or [::1] JDBC URL; "
                        + "set VEV_BENCH_ALLOW_REMOTE_DESTRUCTIVE_SETUP=vev_bench exactly to allow another target");
    }

    private static boolean isSingleHostPostgresqlJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith(POSTGRESQL_JDBC_PREFIX)) {
            return false;
        }
        try {
            URI parsed = URI.create(jdbcUrl.substring("jdbc:".length()));
            String path = parsed.getPath();
            int port = parsed.getPort();
            return parsed.getHost() != null
                    && parsed.getUserInfo() == null
                    && parsed.getRawQuery() == null
                    && parsed.getFragment() == null
                    && (port == -1 || port >= 1 && port <= 65_535)
                    && path != null
                    && path.length() > 1
                    && path.indexOf('/', 1) == -1
                    && isSimpleDatabaseName(path.substring(1));
        } catch (IllegalArgumentException invalidUrl) {
            return false;
        }
    }

    static void requireExactOwnershipMarker(String fixtureObject, String marker) {
        if (!FIXTURE_OWNERSHIP_MARKER.equals(marker)) {
            throw new IllegalStateException(
                    fixtureObject + " is not marked as the owned disposable Vev benchmark fixture");
        }
    }

    private static boolean requireOwnedRoleIfExists(Connection connection, String role) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pg_catalog.shobj_description(fixture_role.oid, 'pg_authid')
                  FROM pg_catalog.pg_roles fixture_role
                 WHERE fixture_role.rolname = ?
                """)) {
            statement.setString(1, role);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return false;
                }
                requireExactOwnershipMarker("role " + role, resultSet.getString(1));
                if (resultSet.next()) {
                    throw new IllegalStateException("PostgreSQL returned duplicate benchmark roles");
                }
                return true;
            }
        }
    }

    private static boolean requireOwnedDatabaseIfExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pg_catalog.shobj_description(fixture_database.oid, 'pg_database')
                  FROM pg_catalog.pg_database fixture_database
                 WHERE fixture_database.datname = ?
                """)) {
            statement.setString(1, DATABASE);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return false;
                }
                requireExactOwnershipMarker("database " + DATABASE, resultSet.getString(1));
                if (resultSet.next()) {
                    throw new IllegalStateException("PostgreSQL returned duplicate benchmark databases");
                }
                return true;
            }
        }
    }

    private static void requireFixtureOwnershipMarkers(Connection connection) throws SQLException {
        if (!requireOwnedRoleIfExists(connection, OWNER_ROLE)
                || !requireOwnedRoleIfExists(connection, APPLICATION_USER)
                || !requireOwnedDatabaseIfExists(connection)) {
            throw new IllegalStateException("The owned disposable Vev benchmark fixture is incomplete");
        }
    }

    private static void requireCurrentFixtureOwnership(Connection connection) throws SQLException {
        installDestructiveSetupSearchPath(connection);
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT pg_catalog.current_database()")) {
            if (!resultSet.next()
                    || !DATABASE.equals(resultSet.getString(1))
                    || resultSet.next()) {
                throw new IllegalStateException("Destructive benchmark setup connected to the wrong database");
            }
        }
        requireFixtureOwnershipMarkers(connection);
    }

    private static void installDestructiveSetupSearchPath(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH configured AS MATERIALIZED (
                    SELECT pg_catalog.set_config('search_path', 'pg_catalog', false) AS search_path
                )
                SELECT configured.search_path,
                       pg_catalog.current_setting('search_path')
                  FROM configured
                """);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()
                    || !"pg_catalog".equals(resultSet.getString(1))
                    || !"pg_catalog".equals(resultSet.getString(2))
                    || resultSet.next()) {
                throw new IllegalStateException("Benchmark setup could not install a trusted search path");
            }
        }
    }

    private static boolean isLiteralLoopbackJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith(POSTGRESQL_JDBC_PREFIX)) {
            return false;
        }
        int databaseSeparator = jdbcUrl.indexOf('/', POSTGRESQL_JDBC_PREFIX.length());
        if (databaseSeparator < 0 || databaseSeparator == jdbcUrl.length() - 1) {
            return false;
        }
        String authority = jdbcUrl.substring(POSTGRESQL_JDBC_PREFIX.length(), databaseSeparator);
        String database = jdbcUrl.substring(databaseSeparator + 1);
        if (!isSimpleDatabaseName(database)) {
            return false;
        }
        return isLiteralLoopbackAuthority(authority, "127.0.0.1")
                || isLiteralLoopbackAuthority(authority, "[::1]");
    }

    private static boolean isSimpleDatabaseName(String database) {
        for (int index = 0; index < database.length(); index++) {
            char character = database.charAt(index);
            if (!(character >= 'a' && character <= 'z')
                    && !(character >= 'A' && character <= 'Z')
                    && !(character >= '0' && character <= '9')
                    && character != '_'
                    && character != '-'
                    && character != '.') {
                return false;
            }
        }
        return !database.isEmpty();
    }

    private static boolean isLiteralLoopbackAuthority(String authority, String host) {
        if (authority.equals(host)) {
            return true;
        }
        String portPrefix = host + ':';
        if (!authority.startsWith(portPrefix)) {
            return false;
        }
        String port = authority.substring(portPrefix.length());
        if (port.isEmpty() || port.length() > 5) {
            return false;
        }
        for (int index = 0; index < port.length(); index++) {
            char character = port.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        int portNumber = Integer.parseInt(port);
        return portNumber >= 1 && portNumber <= 65_535;
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static String databaseUrl(String adminJdbcUrl, String database) {
        int queryStart = adminJdbcUrl.indexOf('?');
        int end = queryStart < 0 ? adminJdbcUrl.length() : queryStart;
        int slash = adminJdbcUrl.lastIndexOf('/', end - 1);
        if (slash < "jdbc:postgresql://".length()) {
            throw new IllegalArgumentException("VEV_BENCH_ADMIN_JDBC_URL must include a database name");
        }
        String query = queryStart < 0 ? "" : adminJdbcUrl.substring(queryStart);
        return adminJdbcUrl.substring(0, slash + 1) + database + query;
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
