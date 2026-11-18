package no.beint.vev.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.gradle.api.provider.ValueSource;
import org.gradle.api.provider.ValueSourceParameters;

/**
 * Finds PostgreSQL 18 server binaries: {@code PATH} first, then the standard Homebrew,
 * Debian/Ubuntu, PGDG RPM and Postgres.app locations, which do not put them on {@code PATH}.
 * An empty result means {@code PATH}.
 */
public abstract class PostgresBin implements ValueSource<String, ValueSourceParameters.None> {
    static final List<String> LOCATIONS = List.of("", "/opt/homebrew/opt/postgresql@18/bin", "/usr/local/opt/postgresql@18/bin",
            "/usr/lib/postgresql/18/bin", "/usr/pgsql-18/bin", "/Applications/Postgres.app/Contents/Versions/18/bin");

    @Override public String obtain() { return find(LOCATIONS); }

    static String find(List<String> locations) {
        for (String bin : locations) {
            if (!bin.isBlank() && !Files.isExecutable(Path.of(bin, "postgres"))) continue;
            if (isPostgres18(bin, "postgres") && isPostgres18(bin, "initdb") && isPostgres18(bin, "pg_ctl")) return bin;
        }
        throw new IllegalStateException("Vev requires PostgreSQL 18 server binaries (postgres, initdb, pg_ctl). Searched PATH and "
                + String.join(", ", locations.stream().filter(bin -> !bin.isBlank()).toList()) + "; install PostgreSQL 18 or set postgresBin");
    }

    private static boolean isPostgres18(String bin, String program) {
        try {
            return PostgresVersion.version(bin, program).contains("(PostgreSQL) 18.");
        } catch (IOException unavailable) {
            return false;
        }
    }
}
