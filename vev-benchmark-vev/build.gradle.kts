plugins {
    application
}

dependencies {
    implementation(project(":vev-core"))
    implementation(project(":vev-postgres"))
    compileOnly("jakarta.persistence:jakarta.persistence-api:4.0.0-M6")
    annotationProcessor(project(":vev-processor"))
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.openjdk.jmh:jmh-core:1.37")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    runtimeOnly("org.postgresql:postgresql:42.7.13")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "org.openjdk.jmh.Main"
}
