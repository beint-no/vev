package no.beint.vev.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
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
import org.gradle.workers.WorkerExecutor;
import org.gradle.work.DisableCachingByDefault;

/** Generation is incremental locally; external PostgreSQL binaries intentionally disable shared caching. */
@DisableCachingByDefault(because = "PostgreSQL patch version and extensions are external build inputs")
public abstract class GenerateSql extends DefaultTask {
    public GenerateSql() {
        getInternalQueries().convention(false);
    }

    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) public abstract DirectoryProperty getQueries();
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) public abstract DirectoryProperty getMigrations();
    @org.gradle.api.tasks.Optional @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) public abstract DirectoryProperty getFixtures();
    @OutputDirectory public abstract DirectoryProperty getOutput();
    @Input public abstract Property<String> getPackageName();
    @Input public abstract Property<String> getClassName();
    @Input public abstract Property<Boolean> getInternalQueries();
    @Input public abstract Property<String> getPostgresBin();
    @Input public abstract Property<String> getPostgresVersion();
    @Classpath public abstract ConfigurableFileCollection getCompilerClasspath();
    @ServiceReference("vevCluster") public abstract Property<VevCluster> getCluster();
    @Inject protected abstract WorkerExecutor getWorkerExecutor();

    @TaskAction public void generate() {
        VevCluster cluster = getCluster().get();
        String database = "vev_" + schemaDigest();
        cluster.withSchema(database, getPostgresBin().get(), (details, name) -> compiler(List.of(
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
                "--visibility", getInternalQueries().get() ? "internal" : "public",
                "--url", details.url(), "--user", details.user(), "--password", details.password(),
                "--database", database));
    }

    private void compiler(List<String> arguments) {
        List<String> all = new ArrayList<>(arguments);
        if (getFixtures().isPresent()) { all.add("--fixtures"); all.add(getFixtures().get().getAsFile().toString()); }
        var queue = getWorkerExecutor().classLoaderIsolation(spec -> spec.getClasspath().from(getCompilerClasspath()));
        queue.submit(CompileSql.class, parameters -> parameters.getArguments().set(all));
        queue.await();
    }

    /**
     * Names the build database after the migration content, so unrelated schemas never share one
     * and an edited migration is replayed instead of silently reused.
     */
    private String schemaDigest() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            hashField(digest, getPostgresVersion().get().getBytes(StandardCharsets.UTF_8));
            hashTree(digest, getMigrations().get().getAsFile().toPath());
            if (getFixtures().isPresent()) hashTree(digest, getFixtures().get().getAsFile().toPath());
            return HexFormat.of().formatHex(digest.digest()).substring(0, 24);
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        catch (IOException failure) { throw new UncheckedIOException("Vev could not read its migrations", failure); }
    }

    static void hashTree(MessageDigest digest, Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            var files = paths.filter(Files::isRegularFile).sorted().toList();
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(files.size()).array());
            for (Path file : files) {
                hashField(digest, root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                hashField(digest, Files.readAllBytes(file));
            }
        }
    }

    private static void hashField(MessageDigest digest, byte[] bytes) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

}
