import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.GradleBuild
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.jvm.tasks.Jar
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    base
}

group = "no.beint.vev"
version = "0.1.0-SNAPSHOT"

val publicModules = setOf("vev-core", "vev-postgres", "vev-processor", "vev-jakarta4")

allprojects {
    group = rootProject.group
    version = rootProject.version

    dependencyLocking {
        lockAllConfigurations()
    }
}

subprojects {
    description = when (name) {
        "vev-core" -> "Compile-time-safe transaction and entity contracts for Vev"
        "vev-postgres" -> "PostgreSQL 18 runtime for Vev's closed AOT entity model"
        "vev-processor" -> "JDK 26 annotation processor for Vev entity models"
        "vev-jakarta4" -> "Experimental Jakarta Persistence 4 EntityAgent adapter for Vev"
        "vev-integration-tests" -> "Synthetic PostgreSQL integration verification for Vev"
        "vev-benchmark-vev" -> "JMH benchmark lane for Vev"
        "vev-benchmark-hibernate" -> "JMH comparison lane for Hibernate ORM 8"
        else -> "Experimental Vev module"
    }

    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion = JavaLanguageVersion.of(26)
            if (project.name in publicModules) {
                withSourcesJar()
                withJavadocJar()
            }
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release = 26
            options.encoding = "UTF-8"
            options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror", "-parameters"))
        }

        if (project.name in publicModules) {
            tasks.withType<Javadoc>().configureEach {
                (options as StandardJavadocDocletOptions).apply {
                    addBooleanOption("Xdoclint:all", true)
                    addBooleanOption("Werror", true)
                }
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            jvmArgs("-XX:+EnableDynamicAgentLoading")
            testLogging {
                events("failed", "skipped")
            }
        }

        tasks.withType<Jar>().configureEach {
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
            from(rootProject.layout.projectDirectory.file("LICENSE")) {
                into("META-INF")
            }
        }
    }

    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "compatibility"
                    url = uri(rootProject.layout.buildDirectory.dir("compatibility-repository"))
                }
            }
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set(project.name)
                    description.set(project.description)
                    url.set("https://github.com/beint-no/vev")
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("beint-no")
                            name.set("Beint")
                            url.set("https://github.com/beint-no")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/beint-no/vev.git")
                        developerConnection.set("scm:git:ssh://git@github.com/beint-no/vev.git")
                        url.set("https://github.com/beint-no/vev")
                    }
                    issueManagement {
                        system.set("GitHub")
                        url.set("https://github.com/beint-no/vev/issues")
                    }
                }
            }
        }
    }
}

tasks.wrapper {
    gradleVersion = "9.7.1"
    distributionType = Wrapper.DistributionType.BIN
}

tasks.named("check") {
    dependsOn(subprojects.map { "${it.path}:check" })
    dependsOn("publishedConsumerTest")
}

tasks.named("build") {
    dependsOn(subprojects.map { "${it.path}:build" })
}

tasks.register("integrationTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the PostgreSQL 18 differential integration suite."
    dependsOn(":vev-integration-tests:integrationTest")
}

tasks.register("publishToMavenLocal") {
    group = "publishing"
    description = "Publishes every public Vev module to Maven Local."
    dependsOn(
        ":vev-core:publishToMavenLocal",
        ":vev-postgres:publishToMavenLocal",
        ":vev-processor:publishToMavenLocal",
        ":vev-jakarta4:publishToMavenLocal"
    )
}

tasks.register("publishCompatibilityRepository") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Publishes public Vev modules to an isolated build repository."
    dependsOn(
        ":vev-core:publishMavenJavaPublicationToCompatibilityRepository",
        ":vev-postgres:publishMavenJavaPublicationToCompatibilityRepository",
        ":vev-processor:publishMavenJavaPublicationToCompatibilityRepository",
        ":vev-jakarta4:publishMavenJavaPublicationToCompatibilityRepository"
    )
}

tasks.register<GradleBuild>("publishedConsumerTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Compiles an isolated JPMS consumer against an isolated Vev repository."
    dependsOn("publishCompatibilityRepository")
    dir = file("compatibility/published-consumer")
    tasks = listOf("clean", "compileJava")
    startParameter.isOffline = true
    startParameter.isBuildCacheEnabled = false
    startParameter.isRerunTasks = true
    startParameter.projectProperties = mapOf(
        "vevRepository" to layout.buildDirectory.dir("compatibility-repository").get().asFile.toURI().toString(),
        "vevVersion" to project.version.toString()
    )
}
