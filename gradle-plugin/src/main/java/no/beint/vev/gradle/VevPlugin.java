package no.beint.vev.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension;

/** Generates main-source Kotlin query APIs before Kotlin compilation. */
public final class VevPlugin implements Plugin<Project> {
    @Override public void apply(Project project) {
        var compiler = project.getConfigurations().create("vevCompiler");
        compiler.setCanBeConsumed(false);
        project.getDependencies().add(compiler.getName(), "no.beint.vev:compiler:1.0.0");
        var task = project.getTasks().register("generateVev", GenerateSql.class, generate -> {
            generate.setGroup("build");
            generate.setDescription("Validate SQL against migrations and generate typed Kotlin APIs");
            generate.getQueries().convention(project.getLayout().getProjectDirectory().dir("src/main/sql"));
            generate.getMigrations().convention(project.getLayout().getProjectDirectory().dir("src/main/resources/db/migration"));
            generate.getOutput().convention(project.getLayout().getBuildDirectory().dir("generated/vev"));
            generate.getClassName().convention("Queries");
            generate.getPostgresBin().convention("");
            generate.getPostgresVersion().convention(project.getProviders().of(PostgresVersion.class,
                    specification -> specification.getParameters().getBin().set(generate.getPostgresBin())));
            generate.getCompilerClasspath().from(compiler);
        });
        project.getPluginManager().withPlugin("org.jetbrains.kotlin.jvm", ignored -> {
            var kotlin = project.getExtensions().getByType(KotlinJvmProjectExtension.class);
            kotlin.getSourceSets().getByName("main").getKotlin().srcDir(task.flatMap(GenerateSql::getOutput));
            var toolchains = project.getExtensions().getByType(JavaToolchainService.class);
            task.configure(generate -> generate.getJavaExecutable().convention(toolchains.launcherFor(spec -> spec.getLanguageVersion().set(JavaLanguageVersion.of(27)))
                    .map(launcher -> launcher.getExecutablePath().getAsFile().getAbsolutePath())));
            project.getDependencies().add("implementation", "no.beint.vev:runtime:1.0.0");
        });
    }
}
