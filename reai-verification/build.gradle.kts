plugins { kotlin("jvm") }
dependencies {
    implementation(project(":runtime"))
    testImplementation(project(":compiler"))
    testImplementation("org.postgresql:postgresql:42.7.14")
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.5.0-Beta2")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
kotlin { jvmToolchain(27); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_27) } }
val generator = configurations.create("generator")
dependencies { generator(project(":compiler")) }
val generateSql = tasks.register<JavaExec>("generateSql") {
    classpath = generator
    mainClass.set("no.beint.vev.compiler.Main")
    inputs.dir("src/main/sql")
    inputs.dir("../test-fixtures/reai/migration")
    outputs.dir(layout.buildDirectory.dir("generated/vev"))
    args("--queries", file("src/main/sql"), "--migrations", file("../test-fixtures/reai/migration"), "--output", layout.buildDirectory.dir("generated/vev").get().asFile, "--package", "no.beint.vev.reai.fixture", "--class", "Queries")
}
kotlin.sourceSets.main { kotlin.srcDir(generateSql) }

tasks.test { inputs.dir(rootProject.file("test-fixtures/reai")) }
