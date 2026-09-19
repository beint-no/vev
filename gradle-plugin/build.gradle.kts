plugins { `java-gradle-plugin` }
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    testImplementation(gradleTestKit())
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
gradlePlugin { plugins { create("vev") { id = "no.beint.vev"; implementationClass = "no.beint.vev.gradle.VevPlugin" } } }
tasks.test {
    dependsOn(":compiler:publishAllPublicationsToReleaseRepository", ":runtime:publishAllPublicationsToReleaseRepository")
    systemProperty("vev.repository", rootProject.layout.buildDirectory.dir("repository").get().asFile.absolutePath)
}
