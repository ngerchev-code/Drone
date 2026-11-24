pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DroneRadarReceiver"

// core е самостоятелна Kotlin/JVM библиотека (тества се и без Android SDK)
includeBuild("core")
include(":app")
