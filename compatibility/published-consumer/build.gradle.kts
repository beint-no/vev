import java.util.zip.ZipFile

plugins {
    java
}

val vevVersion = providers.gradleProperty("vevVersion").get()

java {
    toolchain.languageVersion = JavaLanguageVersion.of(27)
}

dependencies {
    implementation("no.beint.vev:vev-jakarta4:$vevVersion")
    annotationProcessor("no.beint.vev:vev-processor:$vevVersion")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 27
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror"))
}

val verifyGeneratedSchema = tasks.register("verifyGeneratedSchema") {
    val consumerJar = tasks.jar.flatMap { it.archiveFile }
    dependsOn(tasks.jar)
    inputs.file(consumerJar)
    doLast {
        ZipFile(consumerJar.get().asFile).use { archive ->
            val entry = checkNotNull(archive.getEntry("META-INF/vev/no.beint.vev.consumer.PublishedModel.schema.json")) {
                "Published processor did not package the generated schema contract"
            }
            val manifest = archive.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            check(manifest.contains("\"model\": \"no.beint.vev.consumer.PublishedModel\""))
            check(manifest.contains("\"formatVersion\": 1"))
        }
    }
}

tasks.check {
    dependsOn(verifyGeneratedSchema)
}
