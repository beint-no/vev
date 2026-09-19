pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "vev"
include("runtime", "compiler", "gradle-plugin", "verification", "benchmarks")
