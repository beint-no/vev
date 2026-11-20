package no.beint.vev.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.gradle.api.provider.ValueSource;
import org.gradle.api.provider.ValueSourceParameters;

/**
 * Finds PostgreSQL 19 server binaries: {@code PATH} first, then the standard Homebrew,
 * Debian/Ubuntu, PGDG RPM and Postgres.app locations, which do not put them on {@code PATH}.
 * An empty result means {@code PATH}.
 */
public abstract class PostgresBin implements ValueSource<String, ValueSourceParameters.None> {
    static final List<String> LOCATIONS = List.of("", "/opt/homebrew/opt/postgresql@19/bin", "/usr/local/opt/postgresql@19/bin",
            "/usr/lib/postgresql/19/bin", "/usr/pgsql-19/bin", "/Applications/Postgres.app/Contents/Versions/19/bin");

    @Override public String obtain() { return find(LOCATIONS); }

    static String find(List<String> locations) {
        for (String bin : locations) {
            if (!bin.isBlank() && !Files.isExecutable(Path.of(bin, "postgres"))) continue;
            if (isPostgres19(bin, "postgres") && isPostgres19(bin, "initdb") && isPostgres19(bin, "pg_ctl")) return bin;
        }
        throw new IllegalStateException("Vev requires PostgreSQL 19 server binaries (postgres, initdb, pg_ctl). Searched PATH and "
                + String.join(", ", locations.stream().filter(bin -> !bin.isBlank()).toList()) + "; install PostgreSQL 19 or set postgresBin");
    }

    private static boolean isPostgres19(String bin, String program) {
        try {
            return PostgresVersion.version(bin, program).contains("(PostgreSQL) 19.");
        } catch (IOException unavailable) {
            return false;
        }
    }
}
