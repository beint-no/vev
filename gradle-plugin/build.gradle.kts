plugins { `java-gradle-plugin` }
dependencies {
    implementation(project(":cluster"))
    compileOnly(project(":compiler"))
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.5.0-Beta2")
    testImplementation(gradleTestKit())
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
gradlePlugin { plugins { create("vev") { id = "no.beint.vev"; implementationClass = "no.beint.vev.gradle.VevPlugin" } } }
tasks.test {
    dependsOn(":compiler:publishAllPublicationsToReleaseRepository", ":runtime:publishAllPublicationsToReleaseRepository", ":cluster:publishAllPublicationsToReleaseRepository")
    if (providers.environmentVariable("GITHUB_ACTIONS").orNull == "true") {
        // Hosted runners already resolved the same dependencies for the outer build.
        systemProperty("org.gradle.testkit.dir", gradle.gradleUserHomeDir.absolutePath)
    }
    systemProperty("vev.repository", rootProject.layout.buildDirectory.dir("repository").get().asFile.absolutePath)
}

tasks.processResources {
    val releaseVersion = project.version.toString()
    inputs.property("vevVersion", releaseVersion)
    filesMatching("vev-version.txt") { expand("version" to releaseVersion) }
}
