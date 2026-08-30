plugins {
    application
}

dependencies {
    implementation("org.hibernate.orm:hibernate-core:8.0.0.Beta1")
    implementation("jakarta.persistence:jakarta.persistence-api:4.0.0-M6")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.openjdk.jmh:jmh-core:1.37")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    runtimeOnly("org.postgresql:postgresql:42.7.13")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "org.openjdk.jmh.Main"
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-processing")
}

tasks.register<JavaExec>("prepareBenchmarkData") {
    group = "benchmark"
    description = "Creates and verifies the disposable synthetic PostgreSQL 18 benchmark dataset."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "no.beint.vev.benchmark.hibernate.HibernateBenchmarkSetup"
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(26)
    }
}
