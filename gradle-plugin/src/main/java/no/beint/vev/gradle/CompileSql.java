package no.beint.vev.gradle;

import java.util.List;
import no.beint.vev.compiler.Main;
import org.gradle.api.GradleException;
import org.gradle.api.provider.ListProperty;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;

/** Classloader isolation reuses the Gradle JVM without leaking the compiler/Flyway classpath. */
public abstract class CompileSql implements WorkAction<CompileSql.Parameters> {
    public interface Parameters extends WorkParameters { ListProperty<String> getArguments(); }
    @Override public void execute() {
        List<String> arguments = getParameters().getArguments().get();
        try { Main.main(arguments.toArray(String[]::new)); }
        catch (Exception failure) {
            if (arguments.contains("verify-tables")) throw new GradleException(failure.getMessage(), failure);
            throw new IllegalStateException("Vev SQL compilation failed: " + failure.getMessage(), failure);
        }
    }
}
