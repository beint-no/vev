package no.beint.vev.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.services.ServiceReference;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.workers.WorkerExecutor;

/**
 * Fails when migrated tables in the configured schemas are never referenced by any compiled Vev query,
 * database view, materialized view, trigger, or user-defined function invoked by those queries.
 *
 * <p>In multi-module applications that share a migration directory, configure {@code manifests} on the
 * top-level application module with every Vev subproject's {@code generateVev.relationsManifest} and
 * wire this task into {@code check}.
 */
@DisableCachingByDefault(because = "PostgreSQL patch version and extensions are external build inputs")
public abstract class VerifyTableUsage extends DefaultTask {
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) public abstract DirectoryProperty getMigrations();
    @org.gradle.api.tasks.Optional @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) public abstract DirectoryProperty getFixtures();
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE) public abstract ConfigurableFileCollection getManifests();
    @Input public abstract SetProperty<String> getSchemas();
    @Input public abstract SetProperty<String> getIgnoredTables();
    @Input public abstract Property<String> getPostgresBin();
    @Input public abstract Property<String> getPostgresVersion();
    @Internal public abstract Property<String> getSchemaCacheDir();
    @Classpath public abstract ConfigurableFileCollection getCompilerClasspath();
    @ServiceReference("vevCluster") public abstract Property<VevCluster> getCluster();
    @OutputFile public abstract RegularFileProperty getReport();
    @Inject protected abstract WorkerExecutor getWorkerExecutor();

    @TaskAction public void verify() {
        VevCluster cluster = getCluster().get();
        String database = "vev_" + GenerateSql.schemaDigest(
                getPostgresVersion().get(),
                getMigrations().get().getAsFile().toPath(),
                getFixtures().isPresent() ? getFixtures().get().getAsFile().toPath() : null);
        cluster.withSchema(database, getPostgresBin().get(), getSchemaCacheDir().getOrElse(""), (details, name) -> compiler(List.of(
                "--mode", "migrate",
                "--migrations", getMigrations().get().getAsFile().toString(),
                "--url", details.url(), "--user", details.user(), "--password", details.password(),
                "--database", name), true));
        Path manifestsList = getTemporaryDir().toPath().resolve("manifests.txt");
        try {
            Files.createDirectories(manifestsList.getParent());
            var paths = new TreeSet<String>();
            for (File file : getManifests().getFiles()) paths.add(file.getAbsolutePath());
            Files.writeString(manifestsList, String.join("\n", paths) + (paths.isEmpty() ? "" : "\n"), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("Vev could not write table-usage manifest index", failure);
        }
        VevCluster.Details details = cluster.details();
        List<String> args = new ArrayList<>(List.of(
                "--mode", "verify-tables",
                "--manifests-file", manifestsList.toString(),
                "--schemas", String.join(",", new TreeSet<>(getSchemas().get())),
                "--report", getReport().get().getAsFile().toString(),
                "--url", details.url(), "--user", details.user(), "--password", details.password(),
                "--database", database));
        var ignored = new TreeSet<>(getIgnoredTables().get());
        if (!ignored.isEmpty()) {
            args.add("--ignore-tables");
            args.add(String.join(",", ignored));
        }
        compiler(args, false);
    }

    private void compiler(List<String> arguments, boolean includeFixtures) {
        List<String> all = new ArrayList<>(arguments);
        if (includeFixtures && getFixtures().isPresent()) {
            all.add("--fixtures");
            all.add(getFixtures().get().getAsFile().toString());
        }
        var queue = getWorkerExecutor().classLoaderIsolation(spec -> spec.getClasspath().from(getCompilerClasspath()));
        queue.submit(CompileSql.class, parameters -> parameters.getArguments().set(all));
        queue.await();
    }
}
