package no.beint.vev.gradle;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
            String version = version(bin);
            if (version != null && version.matches("(?s).*PostgreSQL\\) 18\\..*")) return bin;
        }
        throw new IllegalStateException("Vev requires PostgreSQL 18 server binaries (postgres, initdb, pg_ctl). Searched PATH and "
                + String.join(", ", locations.stream().filter(bin -> !bin.isBlank()).toList()) + "; install PostgreSQL 18 or set postgresBin");
    }

    private static String version(String bin) {
        Process process = null;
        try {
            process = new ProcessBuilder(bin.isBlank() ? "postgres" : Path.of(bin, "postgres").toString(), "--version")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) return null;
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.exitValue() == 0 ? output : null;
        } catch (java.io.IOException missing) {
            return null;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted locating PostgreSQL", failure);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
