package no.beint.vev.compiler;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Command-line entry point used by Gradle and isolated consumers.
 *
 * <p>{@code standalone} owns a cluster for one module. {@code migrate} and {@code compile} split
 * that work so one build can migrate once and describe every module against the same schema.
 */
public final class Main {
    private Main() {}

    private static final Set<String> OPTIONS = Set.of("--queries", "--migrations", "--output", "--package", "--class",
            "--postgres-bin", "--fixtures", "--mode", "--url", "--user", "--password", "--database");

    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != 27) throw new IllegalStateException("Vev compiler requires JDK 27");
        var options = new HashMap<String, String>();
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 == args.length || !OPTIONS.contains(args[i]) || options.putIfAbsent(args[i], args[i + 1]) != null) {
                throw new IllegalArgumentException("Expected unique options from " + OPTIONS);
            }
        }
        switch (options.getOrDefault("--mode", "standalone")) {
            case "standalone" -> standalone(options);
            case "migrate" -> migrate(options);
            case "compile" -> compile(options);
            default -> throw new IllegalArgumentException("Use --mode standalone, migrate or compile");
        }
    }

    private static void require(Map<String, String> options, String... names) {
        for (String name : names) if (!options.containsKey(name)) throw new IllegalArgumentException("Missing " + name);
    }

    private static void standalone(Map<String, String> options) throws Exception {
        require(options, "--queries", "--migrations", "--output", "--package", "--class");
        try (Postgres postgres = new Postgres(options.getOrDefault("--postgres-bin", ""))) {
            postgres.migrate(Path.of(options.get("--migrations")), fixtures(options));
            try (Connection connection = postgres.connect()) {
                describe(connection, options);
            }
        }
    }

    /** Create and migrate the shared build database. Repeated runs for one schema are the caller's concern. */
    private static void migrate(Map<String, String> options) {
        require(options, "--migrations", "--url", "--user", "--password", "--database");
        String maintenance = options.get("--url");
        String user = options.get("--user");
        String password = options.get("--password");
        String database = options.get("--database");
        try {
            Postgres.dropDatabase(maintenance, user, password, database);
            Postgres.createDatabase(maintenance, user, password, database);
            Postgres.migrate(databaseUrl(maintenance, database), user, password, Path.of(options.get("--migrations")), fixtures(options));
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Vev could not prepare its build database " + database, failure);
        }
    }

    /** Describe queries against an already migrated database. Nothing is written to it. */
    private static void compile(Map<String, String> options) throws Exception {
        require(options, "--queries", "--output", "--package", "--class", "--url", "--user", "--password", "--database");
        String url = databaseUrl(options.get("--url"), options.get("--database"));
        try (Connection connection = Postgres.connect(url, options.get("--user"), options.get("--password"))) {
            describe(connection, options);
        }
    }

    private static void describe(Connection connection, Map<String, String> options) throws Exception {
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        QueryCompiler.compile(connection, Path.of(options.get("--queries")), Path.of(options.get("--output")),
                options.get("--package"), options.get("--class"));
        connection.rollback();
    }

    private static Path fixtures(Map<String, String> options) {
        return options.containsKey("--fixtures") ? Path.of(options.get("--fixtures")) : null;
    }

    private static String databaseUrl(String maintenanceUrl, String database) {
        return maintenanceUrl.substring(0, maintenanceUrl.lastIndexOf('/') + 1) + database;
    }
}
