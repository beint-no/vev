plugins { `java-library`; application }
dependencies {
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("org.flywaydb:flyway-core:13.7.0")
    runtimeOnly("org.flywaydb:flyway-database-postgresql:13.7.0")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
application { mainClass.set("no.beint.vev.compiler.Main") }
