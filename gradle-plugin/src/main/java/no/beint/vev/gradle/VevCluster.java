package no.beint.vev.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import no.beint.vev.cluster.Cluster;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

/**
 * One disposable PostgreSQL cluster per Gradle build, backed by a schema-digest template cache.
 *
 * <p>Booting a cluster and replaying migrations costs seconds, which a repository with many modules
 * would otherwise pay on every SQL edit. A cleanly stopped migrated data directory is cached per
 * schema digest so subsequent builds clone it with copy-on-write and skip both {@code initdb} and
 * Flyway replay.
 */
public abstract class VevCluster implements BuildService<BuildServiceParameters.None>, AutoCloseable {
    /** Connection details for the maintenance database of the running cluster. */
    public record Details(String url, String user, String password) {}

    @FunctionalInterface public interface Migrator { void migrate(Details details, String database); }

    private static final Logger LOGGER = Logging.getLogger(VevCluster.class);

    private Cluster cluster;
    private String selectedBin;
    private Path cacheTarget;
    private boolean loadedFromCache;
    private final Set<String> migrated = new HashSet<>();

    private synchronized Details start(String bin) {
        if (cluster != null && !selectedBin.equals(bin)) {
            throw new IllegalStateException("All Vev modules in one build must use the same postgresBin");
        }
        if (cluster == null) {
            try { cluster = new Cluster(bin); selectedBin = bin; }
            catch (IOException failure) { throw new IllegalStateException("Vev could not start PostgreSQL 18; install it or set postgresBin", failure); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted starting Vev PostgreSQL", failure); }
            LOGGER.lifecycle("Vev started PostgreSQL 18 on port {}", cluster.port());
        }
        return new Details(cluster.url(), cluster.user(), cluster.password());
    }

    public synchronized Details details() {
        if (cluster == null) throw new IllegalStateException("Vev build database has not been started");
        return new Details(cluster.url(), cluster.user(), cluster.password());
    }

    /** Apply one migration set once per build, reusing a cached migrated template when available. */
    public synchronized void withSchema(String database, String bin, String cacheDir, Migrator migrator) {
        if (cluster != null && !selectedBin.equals(bin)) {
            throw new IllegalStateException("All Vev modules in one build must use the same postgresBin");
        }
        Path templateDir = cacheDir != null && !cacheDir.isBlank() ? Path.of(cacheDir, database) : null;
        if (cluster == null && templateDir != null && Files.isDirectory(templateDir)) {
            try {
                cluster = Cluster.fromTemplate(bin, templateDir);
                selectedBin = bin;
                loadedFromCache = true;
                migrated.add(database);
                LOGGER.lifecycle("Vev reused cached PostgreSQL 18 schema {} on port {}", database, cluster.port());
                return;
            } catch (IOException failure) {
                LOGGER.warn("Vev could not reuse cached schema {}; rebuilding it", database, failure);
                deleteQuietly(templateDir);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted starting cached Vev PostgreSQL", failure);
            }
        }
        Details details = start(bin);
        if (migrated.add(database)) {
            LOGGER.lifecycle("Vev migrating build database {}", database);
            try {
                migrator.migrate(details, database);
                if (!loadedFromCache && cacheTarget == null && templateDir != null) cacheTarget = templateDir;
            } catch (RuntimeException failure) {
                migrated.remove(database);
                throw failure;
            }
        }
    }

    @Override public synchronized void close() throws IOException {
        if (cluster != null) {
            LOGGER.info("Vev stopping its PostgreSQL cluster");
            boolean shouldCache = !loadedFromCache && cacheTarget != null && migrated.size() == 1;
            Path target = cacheTarget;
            migrated.clear();
            cacheTarget = null;
            loadedFromCache = false;
            try {
                if (shouldCache) {
                    try {
                        cluster.closeAndCacheTemplate(target);
                        return;
                    } catch (IOException failure) {
                        LOGGER.warn("Vev could not cache schema template at {}", target, failure);
                    }
                }
                cluster.close();
            } finally {
                cluster = null;
            }
        }
    }

    private static void deleteQuietly(Path root) {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) { }
    }
}
