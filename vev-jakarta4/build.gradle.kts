plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":vev-core"))
    api(project(":vev-postgres"))
    api("jakarta.persistence:jakarta.persistence-api:4.0.0-M6")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "vev-jakarta4"
        }
    }
}
