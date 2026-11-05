pluginManagement {
    repositories {
        google()
        // Zusätzlicher Spiegel von Maven Central (optional)
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
    }
}
rootProject.name = "TagPlayer"
include(":app")
