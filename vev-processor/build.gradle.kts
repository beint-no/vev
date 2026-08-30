plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    compileOnly("jakarta.persistence:jakarta.persistence-api:4.0.0-M6")
    testImplementation(project(":vev-core"))
    testImplementation(project(":vev-postgres"))
    testImplementation("jakarta.persistence:jakarta.persistence-api:4.0.0-M6")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "vev-processor"
        }
    }
}
