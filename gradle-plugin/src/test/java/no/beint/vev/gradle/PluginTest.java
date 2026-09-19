package no.beint.vev.gradle;

import java.nio.file.Files;
import java.nio.file.Path;
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
                plugins { kotlin("jvm") version "2.4.20"; id("no.beint.vev") }
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

    private GradleRunner runner() {
        return GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("compileKotlin", "--console=plain", "--stacktrace");
    }
}
