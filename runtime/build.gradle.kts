plugins { `java-library` }
dependencies {
    compileOnly("org.postgresql:postgresql:42.7.14")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
