package no.beint.vev.gradle;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import no.beint.vev.cluster.Cluster;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

/**
 * One disposable PostgreSQL cluster per Gradle build.
 *
 * <p>Booting a cluster and replaying migrations costs seconds, which a repository with many modules
 * would otherwise pay once per module. Each distinct migration set is applied to one database that
 * every module then describes against read-only, so concurrent generation needs no further copies.
 */
public abstract class VevCluster implements BuildService<VevCluster.Parameters>, AutoCloseable {
    public interface Parameters extends BuildServiceParameters { Property<String> getPostgresBin(); }

    /** Connection details for the maintenance database of the running cluster. */
    public record Details(String url, String user, String password) {}

    @FunctionalInterface public interface Migrator { void migrate(Details details, String database); }

    private static final Logger LOGGER = Logging.getLogger(VevCluster.class);

    private Cluster cluster;
    private final Set<String> migrated = new HashSet<>();

    public synchronized Details details() {
        if (cluster == null) {
            try { cluster = new Cluster(getParameters().getPostgresBin().getOrElse("")); }
            catch (IOException failure) { throw new IllegalStateException("Vev could not start PostgreSQL 18; install it or set postgresBin", failure); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted starting Vev PostgreSQL", failure); }
            LOGGER.lifecycle("Vev started PostgreSQL 18 on port {}", cluster.port());
        }
        return new Details(cluster.url(), cluster.user(), cluster.password());
    }

    /** Apply one migration set once per build, even when modules generate in parallel. */
    public synchronized void withSchema(String database, Migrator migrator) {
        Details details = details();
        if (migrated.add(database)) {
            LOGGER.lifecycle("Vev migrating build database {}", database);
            try { migrator.migrate(details, database); }
            catch (RuntimeException failure) { migrated.remove(database); throw failure; }
        }
    }

    @Override public synchronized void close() throws IOException {
        if (cluster != null) {
            LOGGER.info("Vev stopping its PostgreSQL cluster");
            migrated.clear();
            try { cluster.close(); } finally { cluster = null; }
        }
    }
}
