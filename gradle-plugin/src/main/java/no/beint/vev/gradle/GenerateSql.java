package no.beint.vev.gradle;

import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
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
    @Inject protected abstract ExecOperations getExecOperations();

    @TaskAction public void generate() {
        getExecOperations().javaexec(spec -> {
            spec.setExecutable(getJavaExecutable().get());
            spec.setClasspath(getCompilerClasspath());
            spec.getMainClass().set("no.beint.vev.compiler.Main");
            spec.args("--queries", getQueries().get().getAsFile(), "--migrations", getMigrations().get().getAsFile(),
                    "--output", getOutput().get().getAsFile(), "--package", getPackageName().get(), "--class", getClassName().get(),
                    "--postgres-bin", getPostgresBin().get());
            if (getFixtures().isPresent()) spec.args("--fixtures", getFixtures().get().getAsFile());
        }).assertNormalExitValue();
    }
}
