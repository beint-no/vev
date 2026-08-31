package no.beint.vev.benchmark.hibernate;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

final class BenchmarkAdminConfiguration {
    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://127.0.0.1:5432/postgres";
    private static final String DEFAULT_USER = "postgres";
    private static final String ADMIN_JDBC_URL_ENVIRONMENT_VARIABLE = "VEV_BENCH_ADMIN_JDBC_URL";
    private static final String ADMIN_USER_ENVIRONMENT_VARIABLE = "VEV_BENCH_ADMIN_USER";
    private static final String ADMIN_PASSWORD_ENVIRONMENT_VARIABLE = "VEV_BENCH_ADMIN_PASSWORD";
    private static final String ALLOW_REMOTE_DESTRUCTIVE_SETUP_ENVIRONMENT_VARIABLE =
            "VEV_BENCH_ALLOW_REMOTE_DESTRUCTIVE_SETUP";
    private static final String ALLOW_REMOTE_DESTRUCTIVE_SETUP_VALUE = "vev_bench";
    private static final String POSTGRESQL_JDBC_PREFIX = "jdbc:postgresql://";

    private final String jdbcUrl;
    private final String user;
    private final String password;

    private BenchmarkAdminConfiguration(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    static BenchmarkAdminConfiguration fromEnvironment() {
        var jdbcUrl = nonBlankEnvironmentVariableOrDefault(ADMIN_JDBC_URL_ENVIRONMENT_VARIABLE, DEFAULT_JDBC_URL);
        requireDestructiveSetupUrlAllowed(
                jdbcUrl,
                System.getenv(ALLOW_REMOTE_DESTRUCTIVE_SETUP_ENVIRONMENT_VARIABLE));
        return new BenchmarkAdminConfiguration(
                jdbcUrl,
                nonBlankEnvironmentVariableOrDefault(ADMIN_USER_ENVIRONMENT_VARIABLE, DEFAULT_USER),
                environmentVariableOrDefault(ADMIN_PASSWORD_ENVIRONMENT_VARIABLE, ""));
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

    Connection openConnection() throws SQLException {
        return openConnection(jdbcUrl);
    }

    Connection openRuntimeConnection() throws SQLException {
        return openConnection(runtimeJdbcUrl());
    }

    private Connection openConnection(String targetJdbcUrl) throws SQLException {
        var properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        var connection = DriverManager.getConnection(targetJdbcUrl, properties);
        connection.setAutoCommit(true);
        return connection;
    }

    String runtimeJdbcUrl() {
        var queryStart = jdbcUrl.indexOf('?');
        var addressEnd = queryStart >= 0 ? queryStart : jdbcUrl.length();
        var databaseSeparator = jdbcUrl.lastIndexOf('/', addressEnd - 1);
        if (!jdbcUrl.startsWith("jdbc:postgresql://") || databaseSeparator < "jdbc:postgresql://".length()) {
            throw new IllegalStateException(
                    "Cannot derive the vev_bench JDBC URL; set VEV_BENCH_JDBC_URL explicitly");
        }
        return jdbcUrl.substring(0, databaseSeparator + 1)
                + BenchmarkDatabaseConfiguration.DATABASE_NAME
                + jdbcUrl.substring(addressEnd);
    }

    private static boolean isSingleHostPostgresqlJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith(POSTGRESQL_JDBC_PREFIX)) {
            return false;
        }
        try {
            var parsed = URI.create(jdbcUrl.substring("jdbc:".length()));
            var path = parsed.getPath();
            var port = parsed.getPort();
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

    private static boolean isLiteralLoopbackJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith(POSTGRESQL_JDBC_PREFIX)) {
            return false;
        }
        var databaseSeparator = jdbcUrl.indexOf('/', POSTGRESQL_JDBC_PREFIX.length());
        if (databaseSeparator < 0 || databaseSeparator == jdbcUrl.length() - 1) {
            return false;
        }
        var authority = jdbcUrl.substring(POSTGRESQL_JDBC_PREFIX.length(), databaseSeparator);
        var database = jdbcUrl.substring(databaseSeparator + 1);
        if (!isSimpleDatabaseName(database)) {
            return false;
        }
        return isLiteralLoopbackAuthority(authority, "127.0.0.1")
                || isLiteralLoopbackAuthority(authority, "[::1]");
    }

    private static boolean isSimpleDatabaseName(String database) {
        for (var index = 0; index < database.length(); index++) {
            var character = database.charAt(index);
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
        var portPrefix = host + ':';
        if (!authority.startsWith(portPrefix)) {
            return false;
        }
        var port = authority.substring(portPrefix.length());
        if (port.isEmpty() || port.length() > 5) {
            return false;
        }
        for (var index = 0; index < port.length(); index++) {
            var character = port.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        var portNumber = Integer.parseInt(port);
        return portNumber >= 1 && portNumber <= 65_535;
    }

    private static String nonBlankEnvironmentVariableOrDefault(String name, String defaultValue) {
        var value = environmentVariableOrDefault(name, defaultValue);
        if (value.isBlank()) {
            throw new IllegalStateException(name + " must not be blank");
        }
        return value;
    }

    private static String environmentVariableOrDefault(String name, String defaultValue) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
