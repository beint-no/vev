package no.beint.vev.gradle;

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
        String bin = getParameters().getBin().get();
        Process process = null;
        try {
            process = new ProcessBuilder(bin.isBlank() ? "postgres" : Path.of(bin, "postgres").toString(), "--version").redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) throw new IllegalStateException("postgres --version timed out");
            String version = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) throw new IllegalStateException(version);
            return version;
        } catch (java.io.IOException failure) { throw new IllegalStateException("Install PostgreSQL 18 or configure postgresBin", failure); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted reading PostgreSQL version", failure); }
        finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
}
