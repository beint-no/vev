pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "vevCompatibility"
                    url = uri(providers.gradleProperty("vevRepository").get())
                }
            }
            filter {
                includeGroup("no.beint.vev")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "vev-published-consumer"
