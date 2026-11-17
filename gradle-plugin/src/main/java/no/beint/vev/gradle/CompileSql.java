package no.beint.vev.gradle;

import no.beint.vev.compiler.Main;
import org.gradle.api.provider.ListProperty;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;

/** Classloader isolation reuses the Gradle JVM without leaking the compiler/Flyway classpath. */
public abstract class CompileSql implements WorkAction<CompileSql.Parameters> {
    public interface Parameters extends WorkParameters { ListProperty<String> getArguments(); }
    @Override public void execute() {
        try { Main.main(getParameters().getArguments().get().toArray(String[]::new)); }
        catch (Exception failure) { throw new IllegalStateException("Vev SQL compilation failed: " + failure.getMessage(), failure); }
    }
}
