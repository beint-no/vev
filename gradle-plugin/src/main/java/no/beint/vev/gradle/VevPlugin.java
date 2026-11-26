package no.beint.vev.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension;

/** Generates main-source Kotlin query APIs before Kotlin compilation and checks that main code calls each one. */
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
            generate.getRelationsManifest().convention(project.getLayout().getBuildDirectory().file("vev/relations.txt"));
            generate.getClassName().convention("Queries");
            generate.getPostgresBin().convention(project.getProviders().of(PostgresBin.class, specification -> {}));
            generate.getSchemaCacheDir().convention(project.getGradle().getGradleUserHomeDir().toPath().resolve("vev/schemas").toString());
            generate.usesService(cluster);
            generate.getPostgresVersion().convention(project.getProviders().of(PostgresVersion.class,
                    specification -> specification.getParameters().getBin().set(generate.getPostgresBin())));
            generate.getCompilerClasspath().from(compiler);
        });
        project.getTasks().register("verifyVevTableUsage", VerifyTableUsage.class, check -> {
            check.setGroup("verification");
            check.setDescription("Fail when migrated tables are never referenced by any Vev query");
            check.getMigrations().convention(task.flatMap(GenerateSql::getMigrations));
            check.getFixtures().convention(task.flatMap(GenerateSql::getFixtures));
            check.getManifests().from(task.flatMap(GenerateSql::getRelationsManifest));
            check.getSchemas().convention(java.util.Set.of("public"));
            check.getIgnoredTables().convention(java.util.Set.of());
            check.getPostgresBin().convention(task.flatMap(GenerateSql::getPostgresBin));
            check.getPostgresVersion().convention(task.flatMap(GenerateSql::getPostgresVersion));
            check.getSchemaCacheDir().convention(task.flatMap(GenerateSql::getSchemaCacheDir));
            check.getCompilerClasspath().from(compiler);
            check.usesService(cluster);
            check.getReport().convention(project.getLayout().getBuildDirectory().file("reports/vev/table-usage.txt"));
        });
        project.getPluginManager().withPlugin("java", ignored -> {
            var main = project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets()
                    .getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            var verify = project.getTasks().register("verifyVevUsage", VerifyQueryUsage.class, check -> {
                check.setGroup("verification");
                check.setDescription("Fail when main code never calls a generated query");
                check.getClasses().from(main.getOutput().getClassesDirs());
                check.getQueries().set(task.flatMap(GenerateSql::getQueries));
                check.getPackageName().set(task.flatMap(GenerateSql::getPackageName));
                check.getClassName().set(task.flatMap(GenerateSql::getClassName));
                check.getProjectDirectory().set(project.getLayout().getProjectDirectory());
                check.getReport().set(project.getLayout().getBuildDirectory().file("reports/vev/query-usage.txt"));
            });
            var verifyCallGraph = project.getTasks().register("verifyVevCallGraph", VerifyCallGraph.class, check -> {
                check.setGroup("verification");
                check.setDescription("Verify cross-service Vev Db transaction boundaries across compiled main classes");
                check.getClasses().from(main.getOutput().getClassesDirs());
                check.getPackagePrefixes().convention(java.util.Set.of());
                check.getTenantBinders().convention(java.util.Set.of());
                check.getEntryPointAnnotations().convention(java.util.Set.of("org.springframework.scheduling.annotation.Scheduled"));
                check.getForbidSpringTransactional().convention(true);
                check.getForbidExternalIoInTransaction().convention(true);
                check.getForbiddenInTransaction().convention(java.util.Set.of());
                check.getReport().set(project.getLayout().getBuildDirectory().file("reports/vev/call-graph.txt"));
            });
            project.getTasks().named(LifecycleBasePlugin.CHECK_TASK_NAME).configure(check -> {
                check.dependsOn(verify);
                check.dependsOn(verifyCallGraph);
            });
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
