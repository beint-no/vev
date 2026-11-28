plugins { java; id("me.champeau.jmh") }
dependencies {
    jmh(project(":runtime"))
    jmh(project(":compiler"))
    jmh(project(":verification"))
    jmh("org.postgresql:postgresql:42.7.14")
    jmh("org.hibernate.orm:hibernate-core:7.4.14.Final")
}
sourceSets.named("jmh") { resources.srcDir(project(":verification").file("src/main")) }
jmh {
    failOnError.set(true)
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(3)
    timeOnIteration.set("2s")
    warmup.set("2s")
    profilers.set(listOf("gc"))
    resultFormat.set("JSON")
}

dependencies {
    implementation(project(":compiler"))
    implementation(project(":runtime"))
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.5.0-RC2")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.5.0-RC2")
    jmh(project(":reai-verification"))
}
sourceSets.named("jmh") { resources.srcDir(rootProject.file("test-fixtures/reai")) }
tasks.register<JavaExec>("buildPerformance") {
    group = "verification"
    description = "Measure PostgreSQL bootstrap, query generation, and Kotlin compilation separately"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("no.beint.vev.benchmark.BuildPerformance")
    args(rootProject.file("test-fixtures/reai/migration"), layout.buildDirectory.file("reports/build-performance.json").get().asFile)
}
if (providers.gradleProperty("benchmarkQuick").isPresent) {
    jmh {
        warmupIterations.set(2)
        iterations.set(3)
        fork.set(2)
        timeOnIteration.set("500ms")
        warmup.set("500ms")
    }
}

// Measurements must execute; a restored result file is not a new performance observation.
tasks.named("jmh") {
    outputs.upToDateWhen { false }
    outputs.cacheIf("Performance observations cannot be reused from a build cache") { false }
}

providers.gradleProperty("benchmarkInclude").orNull?.let { pattern ->
    jmh { includes.set(listOf(pattern)) }
}

if (providers.gradleProperty("benchmarkSettingsUpdate").isPresent) {
    jmh {
        includes.set(listOf("SettingsWriteBenchmark"))
        benchmarkParameters.put("operation", objects.listProperty(String::class.java).value(listOf("update")))
        benchmarkParameters.put("value", objects.listProperty(String::class.java).value(listOf("12.50")))
        resultsFile.set(layout.buildDirectory.file("results/jmh/settings-update-long.json"))
    }
}
