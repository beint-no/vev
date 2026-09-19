plugins { kotlin("jvm") }
dependencies {
    implementation(project(":runtime"))
    testImplementation(project(":compiler"))
    testImplementation("org.postgresql:postgresql:42.7.13")
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
kotlin { jvmToolchain(27); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_26) } }
val generator = configurations.create("generator")
dependencies { generator(project(":compiler")) }
val generateSql = tasks.register<JavaExec>("generateSql") {
    classpath = generator
    mainClass.set("no.beint.vev.compiler.Main")
    inputs.dir("src/main/sql")
    inputs.dir("src/main/schema")
    outputs.dir(layout.buildDirectory.dir("generated/vev"))
    args("--queries", file("src/main/sql"), "--migrations", file("src/main/schema"), "--output", layout.buildDirectory.dir("generated/vev").get().asFile, "--package", "no.beint.vev.fixture", "--class", "Queries")
}
kotlin.sourceSets.main { kotlin.srcDir(generateSql) }
