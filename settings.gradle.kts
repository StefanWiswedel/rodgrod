// Pure-JVM build: the shared session engine (core) and developer tools.
// The Android app (android/) includes :core from here as well.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "rodgrod-jvm"
include(":core")
include(":scoring-experiment")
project(":scoring-experiment").projectDir = file("tools/scoring-experiment")
