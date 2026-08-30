plugins {
    java
}

val integrationTest = sourceSets.create("integrationTest")

configurations[integrationTest.implementationConfigurationName].extendsFrom(
    configurations.implementation.get(),
    configurations.testImplementation.get()
)
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
integrationTest.compileClasspath += sourceSets.main.get().output
integrationTest.runtimeClasspath += sourceSets.main.get().output

dependencies {
    implementation(project(":vev-core"))
    implementation(project(":vev-postgres"))
    implementation(project(":vev-jakarta4"))
    implementation("jakarta.persistence:jakarta.persistence-api:4.0.0-M6")
    annotationProcessor(project(":vev-processor"))

    add(integrationTest.implementationConfigurationName, "org.postgresql:postgresql:42.7.13")

    add(integrationTest.implementationConfigurationName, platform("org.junit:junit-bom:6.1.3"))
    add(integrationTest.implementationConfigurationName, "org.junit.jupiter:junit-jupiter")
    add(integrationTest.runtimeOnlyConfigurationName, "org.junit.platform:junit-platform-launcher")
}

tasks.test {
    enabled = false
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    description = "Runs Vev against a synthetic PostgreSQL 18 schema."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
    useJUnitPlatform()
}

tasks.check {
    dependsOn(integrationTestTask)
}
