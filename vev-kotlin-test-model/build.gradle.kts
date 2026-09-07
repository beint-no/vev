plugins {
    kotlin("jvm") version "2.4.20"
}

description = "Unpublished synthetic Kotlin record fixtures for Vev compiler and PostgreSQL verification"

kotlin {
    jvmToolchain(27)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_26)
        allWarningsAsErrors.set(true)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 26
}

configurations.configureEach {
    if (name in setOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath", "apiElements", "runtimeElements")) {
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 27)
    }
}

dependencies {
    implementation(project(":vev-core"))
    implementation("jakarta.persistence:jakarta.persistence-api:4.0.0-M6")
}
