plugins { java; id("me.champeau.jmh") }
dependencies {
    jmh(project(":runtime"))
    jmh(project(":compiler"))
    jmh(project(":verification"))
    jmh("org.postgresql:postgresql:42.7.13")
}
sourceSets.named("jmh") { resources.srcDir(project(":verification").file("src/main")) }
jmh {
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(3)
    timeOnIteration.set("2s")
    warmup.set("2s")
    profilers.set(listOf("gc"))
    resultFormat.set("JSON")
}
