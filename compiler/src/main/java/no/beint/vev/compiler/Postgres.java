package no.beint.vev.compiler;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import no.beint.vev.cluster.Cluster;

/** Connects to a disposable Vev cluster and prepares the schema the compiler describes against. */
public final class Postgres implements AutoCloseable {
    private final Cluster cluster;

    /** Owns a fresh cluster for an isolated single-module run. */
    public Postgres(String bin) throws IOException, InterruptedException { cluster = new Cluster(bin); }

    public String url() { return cluster.url(); }
    public String url(String database) { return cluster.url(database); }
    public String user() { return cluster.user(); }
    public String password() { return cluster.password(); }
    public Connection connect() throws SQLException { return connect(url(), user(), password()); }

    public static Connection connect(String url, String user, String password) throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /** Create an empty database to migrate. Identifiers are generated, never taken from user SQL. */
    public static void createDatabase(String maintenanceUrl, String user, String password, String database) throws SQLException {
        identifier(database);
        try (Connection connection = connect(maintenanceUrl, user, password); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE \"" + database + "\"");
        }
    }

    public static void dropDatabase(String maintenanceUrl, String user, String password, String database) throws SQLException {
        identifier(database);
        try (Connection connection = connect(maintenanceUrl, user, password); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS \"" + database + "\" WITH (FORCE)");
        }
    }

    private static void identifier(String database) {
        if (!database.matches("[a-z][a-z0-9_]{0,62}")) throw new IllegalArgumentException("Invalid Vev database name: " + database);
    }

    public void migrate(Path migrations) { migrate(url(), user(), password(), migrations, null); }

    public void migrate(Path migrations, Path fixtures) { migrate(url(), user(), password(), migrations, fixtures); }

    public static void migrate(String url, String user, String password, Path migrations, Path fixtures) {
        String[] locations = fixtures == null ? new String[] {"filesystem:" + migrations.toAbsolutePath()}
                : new String[] {"filesystem:" + migrations.toAbsolutePath(), "filesystem:" + fixtures.toAbsolutePath()};
        var configuration = org.flywaydb.core.Flyway.configure().dataSource(url, user, password)
                .locations(locations)
                .validateMigrationNaming(true).cleanDisabled(true);
        configuration.getConfigurationExtension(org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension.class)
                .setTransactionalLock(false);
        configuration.load().migrate();
    }

    @Override public void close() throws IOException { cluster.close(); }
}
