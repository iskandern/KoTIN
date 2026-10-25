rootProject.name = "kotin"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":app")
include(":androidApp")
include(":drawingworker")
include(":webhtml")
include(":baselineprofile")
