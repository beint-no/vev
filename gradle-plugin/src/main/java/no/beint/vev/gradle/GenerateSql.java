package no.beint.vev.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.services.ServiceReference;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.work.DisableCachingByDefault;

/** Generation is incremental locally; external PostgreSQL binaries intentionally disable shared caching. */
@DisableCachingByDefault(because = "PostgreSQL patch version and extensions are external build inputs")
public abstract class GenerateSql extends DefaultTask {
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) public abstract DirectoryProperty getQueries();
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) public abstract DirectoryProperty getMigrations();
    @org.gradle.api.tasks.Optional @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) public abstract DirectoryProperty getFixtures();
    @OutputDirectory public abstract DirectoryProperty getOutput();
    @Input public abstract Property<String> getPackageName();
    @Input public abstract Property<String> getClassName();
    @Input public abstract Property<String> getPostgresBin();
    @Input public abstract Property<String> getPostgresVersion();
    @Input public abstract Property<String> getJavaExecutable();
    @Classpath public abstract ConfigurableFileCollection getCompilerClasspath();
    @ServiceReference("vevCluster") public abstract Property<VevCluster> getCluster();
    @Inject protected abstract ExecOperations getExecOperations();

    @TaskAction public void generate() {
        VevCluster cluster = getCluster().get();
        String database = "vev_" + schemaDigest();
        cluster.withSchema(database, (details, name) -> compiler(List.of(
                "--mode", "migrate",
                "--migrations", getMigrations().get().getAsFile().toString(),
                "--url", details.url(), "--user", details.user(), "--password", details.password(),
                "--database", name)));
        VevCluster.Details details = cluster.details();
        compiler(List.of(
                "--mode", "compile",
                "--queries", getQueries().get().getAsFile().toString(),
                "--output", getOutput().get().getAsFile().toString(),
                "--package", getPackageName().get(), "--class", getClassName().get(),
                "--url", details.url(), "--user", details.user(), "--password", details.password(),
                "--database", database));
    }

    private void compiler(List<String> arguments) {
        List<String> all = new ArrayList<>(arguments);
        if (getFixtures().isPresent()) { all.add("--fixtures"); all.add(getFixtures().get().getAsFile().toString()); }
        getExecOperations().javaexec(spec -> {
            spec.setExecutable(getJavaExecutable().get());
            spec.setClasspath(getCompilerClasspath());
            spec.getMainClass().set("no.beint.vev.compiler.Main");
            spec.args(all);
        }).assertNormalExitValue();
    }

    /**
     * Names the build database after the migration content, so unrelated schemas never share one
     * and an edited migration is replayed instead of silently reused.
     */
    private String schemaDigest() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(getPostgresVersion().get().getBytes(StandardCharsets.UTF_8));
            List<Path> roots = new ArrayList<>();
            roots.add(getMigrations().get().getAsFile().toPath());
            if (getFixtures().isPresent()) roots.add(getFixtures().get().getAsFile().toPath());
            for (Path root : roots) {
                try (var paths = Files.walk(root)) {
                    for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                        digest.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                        digest.update(Files.readAllBytes(file));
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 24);
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        catch (IOException failure) { throw new UncheckedIOException("Vev could not read its migrations", failure); }
    }
}
