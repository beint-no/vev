package no.beint.vev.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension;

/** Generates main-source Kotlin query APIs before Kotlin compilation. */
public final class VevPlugin implements Plugin<Project> {
    @Override public void apply(Project project) {
        String version = version();
        var compiler = project.getConfigurations().create("vevCompiler");
        compiler.setCanBeConsumed(false);
        project.getDependencies().add(compiler.getName(), "no.beint.vev:compiler:" + version);
        var cluster = project.getGradle().getSharedServices().registerIfAbsent("vevCluster", VevCluster.class,
                service -> {});
        var task = project.getTasks().register("generateVev", GenerateSql.class, generate -> {
            generate.setGroup("build");
            generate.setDescription("Validate SQL against migrations and generate typed Kotlin APIs");
            generate.getQueries().convention(project.getLayout().getProjectDirectory().dir("src/main/sql"));
            generate.getMigrations().convention(project.getLayout().getProjectDirectory().dir("src/main/resources/db/migration"));
            generate.getOutput().convention(project.getLayout().getBuildDirectory().dir("generated/vev"));
            generate.getClassName().convention("Queries");
            generate.getPostgresBin().convention("");
            generate.usesService(cluster);
            generate.getPostgresVersion().convention(project.getProviders().of(PostgresVersion.class,
                    specification -> specification.getParameters().getBin().set(generate.getPostgresBin())));
            generate.getCompilerClasspath().from(compiler);
        });
        project.getPluginManager().withPlugin("org.jetbrains.kotlin.jvm", ignored -> {
            var kotlin = project.getExtensions().getByType(KotlinJvmProjectExtension.class);
            kotlin.getSourceSets().getByName("main").getKotlin().srcDir(task.flatMap(GenerateSql::getOutput));
            project.getDependencies().add("implementation", "no.beint.vev:runtime:" + version);
        });
    }

    private static String version() {
        try (var input = VevPlugin.class.getResourceAsStream("/vev-version.txt")) {
            if (input == null) throw new IllegalStateException("Missing Vev plugin version resource");
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

}
