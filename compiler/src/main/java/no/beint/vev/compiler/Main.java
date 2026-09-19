package no.beint.vev.compiler;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Set;

/** Command-line entry point used by Gradle and isolated consumers. */
public final class Main {
    private Main() {}
    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != 27) throw new IllegalStateException("Vev compiler requires JDK 27");
        var options = new HashMap<String, String>();
        var allowed = Set.of("--queries", "--migrations", "--output", "--package", "--class", "--postgres-bin", "--fixtures");
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 == args.length || !allowed.contains(args[i]) || options.putIfAbsent(args[i], args[i + 1]) != null) {
                throw new IllegalArgumentException("Expected unique --queries, --migrations, --output, --package, --class and optional --postgres-bin, --fixtures");
            }
        }
        for (String required : Set.of("--queries", "--migrations", "--output", "--package", "--class")) {
            if (!options.containsKey(required)) throw new IllegalArgumentException("Missing " + required);
        }
        try (Postgres postgres = new Postgres(options.getOrDefault("--postgres-bin", ""))) {
            postgres.migrate(Path.of(options.get("--migrations")), options.containsKey("--fixtures") ? Path.of(options.get("--fixtures")) : null);
            try (var connection = postgres.connect()) {
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                QueryCompiler.compile(connection, Path.of(options.get("--queries")), Path.of(options.get("--output")), options.get("--package"), options.get("--class"));
                connection.rollback();
            }
        }
    }
}
