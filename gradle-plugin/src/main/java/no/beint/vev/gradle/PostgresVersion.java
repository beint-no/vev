package no.beint.vev.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ValueSource;
import org.gradle.api.provider.ValueSourceParameters;

/** Tracks the external PostgreSQL binary version as a generation input. */
public abstract class PostgresVersion implements ValueSource<String, PostgresVersion.Parameters> {
    public interface Parameters extends ValueSourceParameters { Property<String> getBin(); }
    @Override public String obtain() {
        try {
            return version(getParameters().getBin().get(), "postgres");
        } catch (IOException failure) {
            throw new IllegalStateException("Install PostgreSQL 18 or configure postgresBin", failure);
        }
    }

    static String version(String bin, String program) throws IOException {
        Process process = null;
        try {
            process = new ProcessBuilder(bin.isBlank() ? program : Path.of(bin, program).toString(), "--version").redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) throw new IOException(program + " --version timed out");
            String version = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) throw new IOException(program + " --version failed: " + version);
            return version;
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted reading PostgreSQL version", failure); }
        finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
}
