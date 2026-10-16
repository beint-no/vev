pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "vev"
include("cluster", "runtime", "compiler", "gradle-plugin", "spring", "verification", "benchmarks")
