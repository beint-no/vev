package no.beint.vev.gradle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PluginTest {
    @TempDir Path project;

    @Test void schemaDigestPreservesPathAndContentBoundaries() throws Exception {
        Path left = Files.createDirectory(project.resolve("left"));
        Path right = Files.createDirectory(project.resolve("right"));
        Files.writeString(left.resolve("V1__a.sql"), "x V2__b.sql");
        Files.writeString(left.resolve("V3__c.sql"), "z");
        Files.writeString(right.resolve("V1__a.sql"), "x ");
        Files.writeString(right.resolve("V2__b.sql"), "V3__c.sqlz");

        var leftHash = MessageDigest.getInstance("SHA-256");
        var rightHash = MessageDigest.getInstance("SHA-256");
        GenerateSql.hashTree(leftHash, left);
        GenerateSql.hashTree(rightHash, right);
        assertFalse(MessageDigest.isEqual(leftHash.digest(), rightHash.digest()));
    }

    @Test void generatedSourcesConfigurationCacheAndMigrationInvalidation() throws Exception {
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"consumer\"\n");
        String repository = System.getProperty("vev.repository").replace("\\", "/");
        Files.writeString(project.resolve("build.gradle.kts"), """
                plugins { kotlin("jvm") version "2.5.0-Beta2"; id("no.beint.vev") }
                repositories { maven { url = uri("%s") }; mavenCentral() }
                kotlin { jvmToolchain(27); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_27) } }
                tasks.named<no.beint.vev.gradle.GenerateSql>("generateVev") { packageName.set("sample") }
                """.formatted(repository));
        Files.writeString(project.resolve("gradle.properties"), "org.gradle.configuration-cache=true\n");
        Path sql = Files.createDirectories(project.resolve("src/main/sql")).resolve("find.sql");
        Path migration = Files.createDirectories(project.resolve("src/main/resources/db/migration")).resolve("V1__item.sql");
        Files.writeString(sql, "-- name: find :optional\n-- scope: global\nSELECT id,name FROM item WHERE id=:id;");
        Files.writeString(migration, "CREATE TABLE item(id integer PRIMARY KEY, name text NOT NULL);");
        Path consumer = Files.createDirectories(project.resolve("src/main/kotlin")).resolve("Consumer.kt");
        Files.writeString(consumer, "package sample\nfun name(): String? = Queries.find(1)?.name\n");
        var first = runner().build();
        assertTrue(first.getOutput().contains("BUILD SUCCESSFUL"));
        var second = runner().build();
        assertTrue(second.getOutput().contains("Reusing configuration cache"));
        assertTrue(second.getOutput().contains(":generateVev UP-TO-DATE"));
        Path facade = project.resolve("build/generated/vev/Queries.kt");
        var facadeTime = Files.getLastModifiedTime(facade);
        Files.writeString(sql, "-- name: find :optional\n-- scope: global\nSELECT id,name FROM item WHERE id=:id AND name <> 'changed';");
        assertTrue(runner().build().getOutput().contains("BUILD SUCCESSFUL"));
        assertEquals(facadeTime, Files.getLastModifiedTime(facade), "SQL-only edits must not rewrite the Kotlin facade");
        assertTrue(Files.readString(project.resolve("build/generated/vev/Queries_find_Sql.kt")).contains("changed"));
        Files.writeString(migration.resolveSibling("V2__index.sql"), "CREATE INDEX ON item(id);");
        var schemaOnly = runner().build();
        assertTrue(schemaOnly.getOutput().contains(":compileKotlin UP-TO-DATE"), schemaOnly.getOutput());
        assertFalse(schemaOnly.getOutput().contains(":generateVev UP-TO-DATE"), schemaOnly.getOutput());
        Files.writeString(migration, "CREATE TABLE item(id integer PRIMARY KEY, renamed text NOT NULL);");
        var failure = runner().buildAndFail();
        assertTrue(failure.getOutput().contains("find.sql"));
        assertTrue(failure.getOutput().contains("PostgreSQL rejected query"));
        Path bin = Files.createDirectory(project.resolve("custom-postgres"));
        Path version = bin.resolve("postgres"), init = bin.resolve("initdb");
        Files.writeString(version, "#!/bin/sh\nprintf 'postgres (PostgreSQL) 18.99\\n'\n");
        Files.writeString(init, "#!/bin/sh\nprintf 'vev-custom-initdb-sentinel\\n'\nexit 42\n");
        assertTrue(version.toFile().setExecutable(true));
        assertTrue(init.toFile().setExecutable(true));
        Files.writeString(project.resolve("build.gradle.kts"),
                "\ntasks.named<no.beint.vev.gradle.GenerateSql>(\"generateVev\") { postgresBin.set(\""
                        + bin.toString().replace("\\", "/") + "\") }\n", java.nio.file.StandardOpenOption.APPEND);
        assertTrue(runner().buildAndFail().getOutput().contains("vev-custom-initdb-sentinel"),
                "The shared cluster must use the task's configured binaries, not PATH");
    }

    /** A repository with many modules must pay for one cluster and one migration replay, not one per module. */
    @Test void modulesSharingASchemaStartOneClusterAndMigrateOnce() throws Exception {
        String repository = System.getProperty("vev.repository").replace("\\", "/");
        Files.writeString(project.resolve("settings.gradle.kts"),
                "rootProject.name = \"consumer\"\ninclude(\"first\", \"second\")\n");
        Files.writeString(project.resolve("build.gradle.kts"), """
                plugins { kotlin("jvm") version "2.5.0-Beta2" apply false; id("no.beint.vev") apply false }
                subprojects {
                    apply(plugin = "org.jetbrains.kotlin.jvm")
                    apply(plugin = "no.beint.vev")
                    repositories { maven { url = uri("%s") }; mavenCentral() }
                    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
                        jvmToolchain(27)
                        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_27) }
                    }
                    tasks.named<no.beint.vev.gradle.GenerateSql>("generateVev") {
                        packageName.set("sample." + project.name)
                        migrations.set(rootProject.layout.projectDirectory.dir("migration"))
                    }
                }
                """.formatted(repository));
        Files.writeString(project.resolve("gradle.properties"),
                "org.gradle.configuration-cache=true\n");
        Files.writeString(Files.createDirectories(project.resolve("migration")).resolve("V1__item.sql"),
                "CREATE TABLE item(id integer PRIMARY KEY, name text NOT NULL);");
        for (String module : List.of("first", "second")) {
            Files.writeString(Files.createDirectories(project.resolve(module + "/src/main/sql")).resolve("find.sql"),
                    "-- name: find :optional\n-- scope: global\nSELECT id,name FROM item WHERE id=:id;");
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

    @Test void generatedTypesAreInternalToTheirKotlinModule() throws Exception {
        Files.writeString(project.resolve("settings.gradle.kts"), """
                rootProject.name = "consumer"
                include("storage", "app")
                """);
        Path storage = Files.createDirectories(project.resolve("storage"));
        Path app = Files.createDirectories(project.resolve("app"));
        Files.writeString(project.resolve("build.gradle.kts"), """
                plugins { kotlin("jvm") version "2.5.0-Beta2" apply false; id("no.beint.vev") apply false }
                subprojects {
                    apply(plugin = "org.jetbrains.kotlin.jvm")
                    repositories { maven { url = uri("%s") }; mavenCentral() }
                    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> { jvmToolchain(27) }
                }
                """.formatted(System.getProperty("vev.repository").replace("\\", "/")));
        Files.writeString(storage.resolve("build.gradle.kts"), """
                plugins { id("no.beint.vev") }
                tasks.named<no.beint.vev.gradle.GenerateSql>("generateVev") {
                    packageName.set("storage.sql")
                }
                """);
        Files.writeString(app.resolve("build.gradle.kts"), "dependencies { \"implementation\"(project(\":storage\")) }");
        Files.writeString(Files.createDirectories(storage.resolve("src/main/resources/db/migration")).resolve("V1__item.sql"),
                "CREATE TABLE item(id int PRIMARY KEY, name text NOT NULL);");
        Files.writeString(Files.createDirectories(storage.resolve("src/main/sql")).resolve("find.sql"), """
                -- vev.name: find :optional
                -- vev.scope: global
                -- vev.type: id ItemId
                SELECT id, name FROM item WHERE id=:key;
                """);
        Files.writeString(Files.createDirectories(storage.resolve("src/main/kotlin")).resolve("Store.kt"),
                "package storage\nfun name(key: Int): String? = storage.sql.Queries.find(key)?.name");
        Files.writeString(Files.createDirectories(storage.resolve("src/test/kotlin")).resolve("Friend.kt"),
                "internal fun friend(key: Int): storage.sql.FindRow? = storage.sql.Queries.find(key)");
        Path client = Files.createDirectories(app.resolve("src/main/kotlin")).resolve("Client.kt");
        Files.writeString(client, """
                package storage.sql
                internal fun row(key: Int): FindRow? = Queries.find(key)
                internal fun id(value: Int): ItemId = ItemId(value)
                """);
        var runner = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath();
        var failure = runner.withArguments(":app:compileKotlin", "--configuration-cache").buildAndFail();
        for (String declaration : List.of("Queries", "FindRow", "ItemId")) {
            assertTrue(failure.getOutput().lines().anyMatch(line -> line.contains("Cannot access")
                    && line.contains(declaration) && line.contains("internal")), failure.getOutput());
        }
        Files.writeString(client, "fun name(key: Int): String? = storage.name(key)");
        runner.withArguments(":app:compileKotlin", ":storage:compileTestKotlin", "--configuration-cache").build();
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
