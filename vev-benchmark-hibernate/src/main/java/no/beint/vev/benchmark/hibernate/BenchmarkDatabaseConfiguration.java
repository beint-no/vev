package no.beint.vev.benchmark.hibernate;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

final class BenchmarkDatabaseConfiguration {
    static final String DATABASE_NAME = "vev_bench";
    static final String APPLICATION_USER = "vev_bench_app";
    static final String APPLICATION_PASSWORD = "vev_bench_password";
    static final String OWNER_ROLE = "vev_bench_owner";

    private static final String JDBC_URL_ENVIRONMENT_VARIABLE = "VEV_BENCH_JDBC_URL";
    private static final String JDBC_USER_ENVIRONMENT_VARIABLE = "VEV_BENCH_USER";
    private static final String JDBC_PASSWORD_ENVIRONMENT_VARIABLE = "VEV_BENCH_PASSWORD";

    private final String jdbcUrl;
    private final String jdbcUser;
    private final String jdbcPassword;

    private BenchmarkDatabaseConfiguration(String jdbcUrl, String jdbcUser, String jdbcPassword) {
        this.jdbcUrl = jdbcUrl;
        this.jdbcUser = jdbcUser;
        this.jdbcPassword = jdbcPassword;
    }

    static BenchmarkDatabaseConfiguration fromEnvironment() {
        var adminConfiguration = BenchmarkAdminConfiguration.fromEnvironment();
        return new BenchmarkDatabaseConfiguration(
                nonBlankEnvironmentVariableOrDefault(
                        JDBC_URL_ENVIRONMENT_VARIABLE,
                        adminConfiguration.runtimeJdbcUrl()),
                nonBlankEnvironmentVariableOrDefault(JDBC_USER_ENVIRONMENT_VARIABLE, APPLICATION_USER),
                environmentVariableOrDefault(JDBC_PASSWORD_ENVIRONMENT_VARIABLE, APPLICATION_PASSWORD));
    }

    Connection openConnection(boolean readOnly) throws SQLException {
        var properties = new Properties();
        properties.setProperty("user", jdbcUser);
        properties.setProperty("password", jdbcPassword);
        var connection = DriverManager.getConnection(jdbcUrl, properties);
        try {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setReadOnly(readOnly);
            connection.setAutoCommit(false);
            return connection;
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    HikariDataSource openReadOnlyPool(int poolSize) {
        var poolConfiguration = new HikariConfig();
        poolConfiguration.setPoolName("vev-hibernate-benchmark");
        poolConfiguration.setJdbcUrl(jdbcUrl);
        poolConfiguration.setUsername(jdbcUser);
        poolConfiguration.setPassword(jdbcPassword);
        poolConfiguration.setMinimumIdle(poolSize);
        poolConfiguration.setMaximumPoolSize(poolSize);
        poolConfiguration.setAutoCommit(false);
        poolConfiguration.setTransactionIsolation("TRANSACTION_SERIALIZABLE");
        poolConfiguration.setConnectionInitSql("SET search_path = public");
        poolConfiguration.setConnectionTimeout(10_000);
        poolConfiguration.setInitializationFailTimeout(10_000);
        poolConfiguration.setRegisterMbeans(false);
        return new HikariDataSource(poolConfiguration);
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
