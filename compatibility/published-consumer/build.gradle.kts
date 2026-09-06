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
