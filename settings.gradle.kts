pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "vev"

include(
    "vev-core",
    "vev-postgres",
    "vev-processor",
    "vev-jakarta4",
    "vev-integration-tests",
    "vev-benchmark-vev",
    "vev-benchmark-hibernate"
)
