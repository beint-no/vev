package no.beint.vev.gradle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PluginTest {
    @TempDir Path project;

    @Test void generatedSourcesConfigurationCacheAndMigrationInvalidation() throws Exception {
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"consumer\"\n");
        String repository = System.getProperty("vev.repository").replace("\\", "/");
        Files.writeString(project.resolve("build.gradle.kts"), """
                plugins { kotlin("jvm") version "2.4.21"; id("no.beint.vev") }
                repositories { maven { url = uri("%s") }; mavenCentral() }
                kotlin { jvmToolchain(27); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_26) } }
                tasks.named<no.beint.vev.gradle.GenerateSql>("generateVev") { packageName.set("sample") }
                """.formatted(repository));
        Files.writeString(project.resolve("gradle.properties"), "org.gradle.configuration-cache=true\nkotlin.jvm.target.validation.mode=warning\n");
        Path sql = Files.createDirectories(project.resolve("src/main/sql")).resolve("find.sql");
        Path migration = Files.createDirectories(project.resolve("src/main/resources/db/migration")).resolve("V1__item.sql");
        Files.writeString(sql, "-- name: find :optional\nSELECT id,name FROM item WHERE id=:id;");
        Files.writeString(migration, "CREATE TABLE item(id integer PRIMARY KEY, name text NOT NULL);");
        Path consumer = Files.createDirectories(project.resolve("src/main/kotlin")).resolve("Consumer.kt");
        Files.writeString(consumer, "package sample\nfun name(session: no.beint.vev.Session): String? = Queries.find(session, 1)?.name\n");
        var first = runner().build();
        assertTrue(first.getOutput().contains("BUILD SUCCESSFUL"));
        var second = runner().build();
        assertTrue(second.getOutput().contains("Reusing configuration cache"));
        assertTrue(second.getOutput().contains(":generateVev UP-TO-DATE"));
        Files.writeString(migration, "CREATE TABLE item(id integer PRIMARY KEY, renamed text NOT NULL);");
        var failure = runner().buildAndFail();
        assertTrue(failure.getOutput().contains("find.sql"));
        assertTrue(failure.getOutput().contains("PostgreSQL rejected query"));
    }

    /** A repository with many modules must pay for one cluster and one migration replay, not one per module. */
    @Test void modulesSharingASchemaStartOneClusterAndMigrateOnce() throws Exception {
        String repository = System.getProperty("vev.repository").replace("\\", "/");
        Files.writeString(project.resolve("settings.gradle.kts"),
                "rootProject.name = \"consumer\"\ninclude(\"first\", \"second\")\n");
        Files.writeString(project.resolve("build.gradle.kts"), """
                plugins { kotlin("jvm") version "2.4.21" apply false; id("no.beint.vev") apply false }
                subprojects {
                    apply(plugin = "org.jetbrains.kotlin.jvm")
                    apply(plugin = "no.beint.vev")
                    repositories { maven { url = uri("%s") }; mavenCentral() }
                    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
                        jvmToolchain(27)
                        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_26) }
                    }
                    tasks.named<no.beint.vev.gradle.GenerateSql>("generateVev") {
                        packageName.set("sample." + project.name)
                        migrations.set(rootProject.layout.projectDirectory.dir("migration"))
                    }
                }
                """.formatted(repository));
        Files.writeString(project.resolve("gradle.properties"),
                "org.gradle.configuration-cache=true\nkotlin.jvm.target.validation.mode=warning\n");
        Files.writeString(Files.createDirectories(project.resolve("migration")).resolve("V1__item.sql"),
                "CREATE TABLE item(id integer PRIMARY KEY, name text NOT NULL);");
        for (String module : List.of("first", "second")) {
            Files.writeString(Files.createDirectories(project.resolve(module + "/src/main/sql")).resolve("find.sql"),
                    "-- name: find :optional\nSELECT id,name FROM item WHERE id=:id;");
        }
        var build = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("compileKotlin", "--console=plain", "--stacktrace").build();
        String output = build.getOutput();
        assertTrue(output.contains("BUILD SUCCESSFUL"), output);
        assertEquals(1, count(output, "Vev started PostgreSQL 18"), output);
        assertEquals(1, count(output, "Vev migrating build database"), output);
        assertTrue(Files.exists(project.resolve("first/build/generated/vev/Queries.kt")));
        assertTrue(Files.exists(project.resolve("second/build/generated/vev/Queries.kt")));
    }

    private static int count(String text, String needle) {
        int total = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) total++;
        return total;
    }

    private GradleRunner runner() {
        return GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("compileKotlin", "--console=plain", "--stacktrace");
    }
}
