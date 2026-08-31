plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":vev-core"))
    api("org.postgresql:postgresql:42.7.13")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "vev-postgres"
        }
    }
}
