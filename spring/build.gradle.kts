plugins { `java-library` }
dependencies {
    api(project(":runtime"))
    // The consumer owns its Spring version; Vev only needs the transaction-bound connection.
    compileOnly("org.springframework:spring-jdbc:7.0.4")
    compileOnly("org.springframework:spring-tx:7.0.4")
    testImplementation("org.springframework:spring-jdbc:7.0.4")
    testImplementation(project(":cluster"))
    testImplementation("org.postgresql:postgresql:42.7.13")
    testImplementation("org.springframework:spring-tx:7.0.4")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
