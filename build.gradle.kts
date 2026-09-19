plugins {
    base
    kotlin("jvm") version "2.4.20" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    id("me.champeau.jmh") version "0.7.3" apply false
}

allprojects {
    group = "no.beint.vev"
    version = "1.0.0"
}

subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion = JavaLanguageVersion.of(27)
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release = 27
            options.encoding = "UTF-8"
            options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror"))
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging { events("failed", "skipped") }
        }
        tasks.withType<Jar>().configureEach {
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
            from(rootProject.file("LICENSE")) { into("META-INF") }
        }
        tasks.withType<Javadoc>().configureEach {
            (options as StandardJavadocDocletOptions).apply {
                addStringOption("Xdoclint:all,-missing", "-quiet")
                addBooleanOption("notimestamp", true)
            }
        }
    }
    if (name in setOf("runtime", "compiler", "gradle-plugin")) {
        apply(plugin = "com.vanniktech.maven.publish")
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral()
            if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
            pom {
                name.set("Vev ${project.name}")
                description.set("SQL-first Kotlin persistence for JDK 27 and PostgreSQL 18")
                url.set("https://github.com/beint-no/vev")
                licenses { license { name.set("Apache-2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0.txt") } }
                developers { developer { id.set("beint-no"); name.set("Beint"); url.set("https://github.com/beint-no") } }
                scm { url.set("https://github.com/beint-no/vev"); connection.set("scm:git:https://github.com/beint-no/vev.git"); developerConnection.set("scm:git:ssh://git@github.com/beint-no/vev.git") }
            }
        }
        plugins.withId("maven-publish") {
            extensions.configure<PublishingExtension> {
                repositories { maven { name = "release"; url = uri(rootProject.layout.buildDirectory.dir("repository")) } }
            }
        }
    }
}
tasks.named("check") { dependsOn(subprojects.map { "${it.path}:check" }) }
tasks.register<Zip>("releaseBundle") {
    dependsOn(":runtime:publishAllPublicationsToReleaseRepository", ":compiler:publishAllPublicationsToReleaseRepository", ":gradle-plugin:publishAllPublicationsToReleaseRepository")
    from(layout.buildDirectory.dir("repository"))
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("vev-sql-1.0.0-maven.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
